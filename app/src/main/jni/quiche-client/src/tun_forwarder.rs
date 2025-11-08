/*
 * TUN to QUIC Forwarder (Rust Implementation)
 * Zero-copy packet processing
 */

use crate::client::QuicheClient;
use std::sync::Arc;
use parking_lot::Mutex;
use std::os::unix::io::RawFd;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::thread;
use std::time::Duration;
use log::{debug, error, info, warn};
use crossbeam::channel;

#[derive(Clone, Debug)]
pub struct ForwarderConfig {
    pub tun_fd: RawFd,
    pub packet_pool_size: usize,
    pub batch_size: usize,
    pub use_gso: bool,
    pub use_gro: bool,
    pub use_zero_copy: bool,
    pub cpu_affinity: crate::client::CpuAffinity,
    pub enable_realtime: bool,
}

impl Default for ForwarderConfig {
    fn default() -> Self {
        Self {
            tun_fd: -1,
            packet_pool_size: 8192,
            batch_size: 64,
            use_gso: true,
            use_gro: true,
            use_zero_copy: true,
            cpu_affinity: crate::client::CpuAffinity::BigCores,
            enable_realtime: false,
        }
    }
}

#[derive(Clone, Debug, Default)]
pub struct ForwarderStats {
    pub packets_received: u64,
    pub packets_sent: u64,
    pub packets_dropped: u64,
    pub bytes_received: u64,
    pub bytes_sent: u64,
    pub rx_rate_mbps: f64,
    pub tx_rate_mbps: f64,
    pub avg_latency_us: u64,
}

pub struct QuicheTunForwarder {
    config: ForwarderConfig,
    quic_client: Arc<Mutex<QuicheClient>>,
    running: Arc<AtomicBool>,
    stats: Arc<Mutex<ForwarderStats>>,
    _forward_thread: Option<thread::JoinHandle<()>>,
}

impl QuicheTunForwarder {
    pub fn create(
        config: ForwarderConfig,
        quic_client: Arc<Mutex<QuicheClient>>,
    ) -> Result<Self, Box<dyn std::error::Error>> {
        if config.tun_fd < 0 {
            return Err("Invalid TUN fd".into());
        }

        info!("Creating TUN forwarder (tun_fd={})", config.tun_fd);

        Ok(Self {
            config,
            quic_client,
            running: Arc::new(AtomicBool::new(false)),
            stats: Arc::new(Mutex::new(ForwarderStats::default())),
            _forward_thread: None,
        })
    }

    pub fn start(&mut self) -> Result<(), Box<dyn std::error::Error>> {
        if self.running.swap(true, Ordering::AcqRel) {
            warn!("Already running");
            return Ok(());
        }

        info!("Starting TUN forwarder...");

        // Configure CPU affinity
        if let Err(e) = self.configure_cpu_affinity() {
            warn!("Failed to configure CPU affinity: {} (non-fatal)", e);
        }

        // Start forwarding thread
        let running = self.running.clone();
        let quic_client = self.quic_client.clone();
        let config = self.config.clone();
        let stats = self.stats.clone();

        let handle = thread::spawn(move || {
            Self::forwarding_loop(running, quic_client, config, stats);
        });

        self._forward_thread = Some(handle);
        info!("TUN forwarder started");
        Ok(())
    }

    pub fn stop(&mut self) {
        if !self.running.swap(false, Ordering::AcqRel) {
            return;
        }

        info!("Stopping TUN forwarder...");
        
        if let Some(handle) = self._forward_thread.take() {
            let _ = handle.join();
        }

        info!("TUN forwarder stopped");
    }

    pub fn get_stats(&self) -> ForwarderStats {
        self.stats.lock().clone()
    }

    fn configure_cpu_affinity(&self) -> Result<(), Box<dyn std::error::Error>> {
        use nix::sched::{CpuSet, sched_setaffinity};
        use nix::unistd::Pid;

        let cpu_mask = match self.config.cpu_affinity {
            crate::client::CpuAffinity::BigCores => {
                (1u64 << 4) | (1u64 << 5) | (1u64 << 6) | (1u64 << 7)
            }
            crate::client::CpuAffinity::LittleCores => {
                (1u64 << 0) | (1u64 << 1) | (1u64 << 2) | (1u64 << 3)
            }
            crate::client::CpuAffinity::Custom(mask) => mask,
            crate::client::CpuAffinity::None => return Ok(()),
        };

        let mut cpuset = CpuSet::new();
        for i in 0..64 {
            if cpu_mask & (1u64 << i) != 0 {
                cpuset.set(i)?;
            }
        }

        sched_setaffinity(Pid::from_raw(0), &cpuset)?;
        Ok(())
    }

    fn forwarding_loop(
        running: Arc<AtomicBool>,
        quic_client: Arc<Mutex<QuicheClient>>,
        config: ForwarderConfig,
        stats: Arc<Mutex<ForwarderStats>>,
    ) {
        const BATCH_SIZE: usize = 64;
        let mut buffer = vec![0u8; 65536];

        while running.load(Ordering::Acquire) {
            // Read from TUN
            use nix::unistd::read;
            match read(config.tun_fd, &mut buffer) {
                Ok(len) if len > 0 => {
                    let mut stats_guard = stats.lock();
                    stats_guard.packets_received += 1;
                    stats_guard.bytes_received += len as u64;
                    drop(stats_guard);

                    // Send via QUIC
                    let mut client = quic_client.lock();
                    if let Err(e) = client.send(&buffer[..len]) {
                        error!("Failed to send via QUIC: {}", e);
                        let mut stats_guard = stats.lock();
                        stats_guard.packets_dropped += 1;
                    } else {
                        let mut stats_guard = stats.lock();
                        stats_guard.packets_sent += 1;
                        stats_guard.bytes_sent += len as u64;
                    }
                }
                Ok(0) => {
                    // EOF
                    break;
                }
                Err(nix::errno::Errno::EAGAIN) | Err(nix::errno::Errno::EWOULDBLOCK) => {
                    // No data available
                    thread::sleep(Duration::from_millis(1));
                }
                Err(e) => {
                    error!("Read from TUN failed: {}", e);
                    break;
                }
            }
        }
    }
}

