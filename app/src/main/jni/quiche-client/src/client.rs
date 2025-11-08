/*
 * QUIC Client Implementation (Rust)
 * High-performance QUIC client using Quinn
 */

use quinn::{ClientConfig, Endpoint, Connection};
use quinn_proto::CongestionControlAlgorithm;
use std::sync::Arc;
use std::net::{SocketAddr, ToSocketAddrs};
use std::time::Duration;
use log::{debug, error, info, warn};
use tokio::runtime::Runtime;
use tokio::io::{AsyncWriteExt, AsyncReadExt};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use parking_lot::Mutex;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CongestionControl {
    Reno,
    Cubic,
    Bbr,
    Bbr2,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CpuAffinity {
    None,
    BigCores,
    LittleCores,
    Custom(u64),
}

#[derive(Clone, Debug)]
pub struct QuicConfig {
    pub server_host: String,
    pub server_port: u16,
    pub cc_algorithm: CongestionControl,
    pub enable_zero_copy: bool,
    pub cpu_affinity: CpuAffinity,
    pub initial_max_data: u64,
    pub initial_max_stream_data: u64,
    pub initial_max_streams_bidi: u64,
    pub initial_max_streams_uni: u64,
    pub max_idle_timeout_ms: u64,
    pub max_udp_payload_size: u16,
    pub enable_early_data: bool,
    pub enable_pacing: bool,
    pub enable_dgram: bool,
    pub enable_hystart: bool,
}

impl Default for QuicConfig {
    fn default() -> Self {
        Self {
            server_host: String::new(),
            server_port: 443,
            cc_algorithm: CongestionControl::Bbr2,
            enable_zero_copy: true,
            cpu_affinity: CpuAffinity::BigCores,
            initial_max_data: 100 * 1024 * 1024,
            initial_max_stream_data: 50 * 1024 * 1024,
            initial_max_streams_bidi: 1000,
            initial_max_streams_uni: 1000,
            max_idle_timeout_ms: 300000,
            max_udp_payload_size: 1350,
            enable_early_data: true,
            enable_pacing: false,
            enable_dgram: true,
            enable_hystart: true,
        }
    }
}

#[derive(Clone, Debug, Default)]
pub struct QuicMetrics {
    pub bytes_sent: u64,
    pub bytes_received: u64,
    pub throughput_mbps: f64,
    pub rtt_us: u64,
    pub min_rtt_us: u64,
    pub packets_sent: u64,
    pub packets_received: u64,
    pub packets_lost: u64,
    pub packet_loss_rate: f64,
    pub cwnd: u64,
    pub bytes_in_flight: u64,
    pub is_established: bool,
    pub is_in_early_data: bool,
    pub handshake_duration_us: u64,
}

pub struct QuicheClient {
    config: QuicConfig,
    endpoint: Option<Endpoint>,
    connection: Option<Connection>,
    runtime: Runtime,
    connected: Arc<AtomicBool>,
    metrics: Arc<Mutex<QuicMetrics>>,
}

impl QuicheClient {
    pub fn create(config: QuicConfig) -> Result<Self, Box<dyn std::error::Error>> {
        info!("Creating QUIC client for {}:{}", config.server_host, config.server_port);

        // Create Tokio runtime
        let runtime = Runtime::new()?;

        // Configure CPU affinity
        if let Err(e) = Self::configure_cpu_affinity(&config) {
            warn!("Failed to configure CPU affinity: {} (non-fatal)", e);
        }

        Ok(Self {
            config,
            endpoint: None,
            connection: None,
            runtime,
            connected: Arc::new(AtomicBool::new(false)),
            metrics: Arc::new(Mutex::new(QuicMetrics::default())),
        })
    }

    fn configure_cpu_affinity(config: &QuicConfig) -> Result<(), Box<dyn std::error::Error>> {
        use nix::sched::{CpuSet, sched_setaffinity};
        use nix::unistd::Pid;

        let cpu_mask = match config.cpu_affinity {
            CpuAffinity::BigCores => {
                // Typical: cores 4-7
                (1u64 << 4) | (1u64 << 5) | (1u64 << 6) | (1u64 << 7)
            }
            CpuAffinity::LittleCores => {
                // Typical: cores 0-3
                (1u64 << 0) | (1u64 << 1) | (1u64 << 2) | (1u64 << 3)
            }
            CpuAffinity::Custom(mask) => mask,
            CpuAffinity::None => return Ok(()),
        };

        let mut cpuset = CpuSet::new();
        for i in 0..64 {
            if cpu_mask & (1u64 << i) != 0 {
                cpuset.set(i)?;
            }
        }

        sched_setaffinity(Pid::from_raw(0), &cpuset)?;
        info!("CPU affinity set to mask 0x{:x}", cpu_mask);
        Ok(())
    }

    pub fn connect(&mut self) -> Result<(), Box<dyn std::error::Error>> {
        if self.connected.load(Ordering::Acquire) {
            warn!("Already connected");
            return Ok(());
        }

        info!("Connecting to {}:{}...", self.config.server_host, self.config.server_port);

        // Resolve server address
        let addr = format!("{}:{}", self.config.server_host, self.config.server_port);
        let mut addrs = addr.to_socket_addrs()?;
        let server_addr = addrs.next()
            .ok_or("Failed to resolve server address")?;

        // Create client config with rustls
        // For now, use default config (accepts any certificate)
        // In production, you should use proper certificate validation
        let mut crypto = rustls::ClientConfig::builder()
            .with_safe_defaults()
            .with_custom_certificate_verifier(Arc::new(NoCertificateVerification))
            .with_no_client_auth();
        
        let client_config = ClientConfig::new(Arc::new(crypto));

        // Create endpoint
        let endpoint = Endpoint::client("[::]:0".parse()?)?;
        let endpoint = endpoint.with_default_client_config(client_config);

        // Connect
        let new_conn = self.runtime.block_on(async {
            endpoint.connect(server_addr, &self.config.server_host)?.await
        })?;

        self.endpoint = Some(endpoint);
        self.connection = Some(new_conn.connection.clone());
        self.connected.store(true, Ordering::Release);

        // Update metrics
        let mut metrics = self.metrics.lock();
        metrics.is_established = true;
        drop(metrics);

        info!("Connected successfully");
        Ok(())
    }

    pub fn disconnect(&mut self) {
        if !self.connected.load(Ordering::Acquire) {
            return;
        }

        info!("Disconnecting...");
        self.connected.store(false, Ordering::Release);

        if let Some(conn) = &self.connection {
            conn.close(0u32.into(), b"");
        }

        self.connection = None;
        self.endpoint = None;

        let mut metrics = self.metrics.lock();
        metrics.is_established = false;
        drop(metrics);

        info!("Disconnected");
    }

    pub fn is_connected(&self) -> bool {
        self.connected.load(Ordering::Acquire) && 
        self.connection.is_some()
    }

    pub fn send(&mut self, data: &[u8]) -> Result<usize, Box<dyn std::error::Error>> {
        if !self.is_connected() {
            return Err("Not connected".into());
        }

        let conn = self.connection.as_ref().unwrap();
        
        self.runtime.block_on(async {
            let mut send_stream = conn.open_uni().await?;
            send_stream.write_all(data).await?;
            send_stream.finish().await?;
            Ok(data.len())
        })
    }

    pub fn get_metrics(&self) -> QuicMetrics {
        self.metrics.lock().clone()
    }
}

use rustls::client::{ServerCertVerifier, ServerCertVerified};
use rustls::{Certificate, Error};

// Dummy certificate verifier (accepts all certificates)
// In production, use proper certificate validation
struct NoCertificateVerification;

impl ServerCertVerifier for NoCertificateVerification {
    fn verify_server_cert(
        &self,
        _end_entity: &Certificate,
        _intermediates: &[Certificate],
        _server_name: &rustls::ServerName,
        _scts: &mut dyn Iterator<Item = &[u8]>,
        _ocsp_response: &[u8],
        _now: std::time::SystemTime,
    ) -> Result<ServerCertVerified, Error> {
        Ok(ServerCertVerified::assertion())
    }
}

