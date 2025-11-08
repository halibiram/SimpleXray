/*
 * TCP Fast Open (TFO) Support (Rust Implementation)
 * Reduces latency for first connection by combining SYN and data
 */

use jni::JNIEnv;
use jni::objects::JClass;
use jni::sys::jint;
use log::{debug, error};
use nix::sys::socket::{socket, AddressFamily, SockType, SockFlag, SockProtocol};
use std::os::unix::io::RawFd;
use std::sync::atomic::{AtomicI32, Ordering};
use std::sync::OnceLock;
use std::fs::File;
use std::io::Write;

// Cache for TCP Fast Open support check result
// -1 = not checked, 0 = not supported, 1 = supported
static TFO_SUPPORTED: AtomicI32 = AtomicI32::new(-1);
static TFO_MUTEX: OnceLock<parking_lot::Mutex<()>> = OnceLock::new();

fn get_tfo_mutex() -> &'static parking_lot::Mutex<()> {
    TFO_MUTEX.get_or_init(|| parking_lot::Mutex::new(()))
}

/// Enable TCP Fast Open on a socket
/// Returns 0 on success, negative on error
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeEnableTCPFastOpen(
    _env: JNIEnv,
    _class: JClass,
    fd: jint,
) -> jint {
    let fd = fd as RawFd;

    if fd < 0 {
        error!("Invalid file descriptor: {}", fd);
        return -1;
    }

    // TCP_FASTOPEN option - available on Linux 3.7+
    // Use libc directly as TcpFastOpen may not be in nix 0.28
    let opt: i32 = 1;
    #[cfg(target_os = "android")]
    {
        use libc::{IPPROTO_TCP, TCP_FASTOPEN};
        let result = unsafe {
            libc::setsockopt(fd, IPPROTO_TCP, TCP_FASTOPEN, &opt as *const _ as *const libc::c_void, std::mem::size_of::<i32>() as libc::socklen_t)
        };
        if result == 0 {
            debug!("TCP Fast Open enabled for fd {}", fd);
            0
        } else {
            // TFO may not be supported on all devices/Android versions
            debug!("TCP Fast Open not available for fd {}", fd);
            -1
        }
    }
    #[cfg(not(target_os = "android"))]
    {
        use nix::sys::socket::{setsockopt, sockopt::TcpFastOpen};
        match setsockopt(fd, &TcpFastOpen, &opt) {
            Ok(_) => {
                debug!("TCP Fast Open enabled for fd {}", fd);
                0
            }
            Err(e) => {
                debug!("TCP Fast Open not available for fd {}: {}", fd, e);
                -1
            }
        }
    }
}

/// Check if TCP Fast Open is supported
/// Returns 1 if supported, 0 if not
/// Result is cached to avoid repeated syscalls
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeIsTCPFastOpenSupported(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    // Check cache first (thread-safe)
    let cached = TFO_SUPPORTED.load(Ordering::Acquire);
    if cached >= 0 {
        return cached;
    }

    // Cache miss - perform actual check
    let _lock = get_tfo_mutex().lock();

    // Double-check after acquiring lock
    let cached = TFO_SUPPORTED.load(Ordering::Acquire);
    if cached >= 0 {
        return cached;
    }

    // Try to create a test socket and enable TFO
    let test_fd = match socket(
        AddressFamily::Inet,
        SockType::Stream,
        SockFlag::empty(),
        SockProtocol::Tcp,
    ) {
        Ok(fd) => fd,
        Err(e) => {
            debug!("Cannot create test socket for TFO check: {}", e);
            TFO_SUPPORTED.store(0, Ordering::Release);
            return 0;
        }
    };

    let opt: i32 = 1;
    let supported = {
        #[cfg(target_os = "android")]
        {
            use libc::{IPPROTO_TCP, TCP_FASTOPEN};
            let result = unsafe {
                libc::setsockopt(test_fd, IPPROTO_TCP, TCP_FASTOPEN, &opt as *const _ as *const libc::c_void, std::mem::size_of::<i32>() as libc::socklen_t)
            };
            if result == 0 { 1 } else { 0 }
        }
        #[cfg(not(target_os = "android"))]
        {
            use nix::sys::socket::{setsockopt, sockopt::TcpFastOpen};
            match setsockopt(test_fd, &TcpFastOpen, &opt) {
                Ok(_) => 1,
                Err(_) => 0,
            }
        }
    };

    // Close test socket
    let _ = nix::unistd::close(test_fd);

    // Update cache
    TFO_SUPPORTED.store(supported, Ordering::Release);

    debug!("TCP Fast Open support check: {} (cached)", if supported == 1 { "supported" } else { "not supported" });
    supported
}

/// Set TCP Fast Open queue size
/// Controls how many TFO requests can be queued
/// Note: Requires root access, best-effort only
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeSetTCPFastOpenQueueSize(
    _env: JNIEnv,
    _class: JClass,
    queue_size: jint,
) -> jint {
    if queue_size < 0 || queue_size > 65535 {
        error!("Invalid queue size: {} (must be 0-65535)", queue_size);
        return -1;
    }

    // Write to /proc/sys/net/ipv4/tcp_fastopen
    // This requires root access, so this is best-effort
    match File::create("/proc/sys/net/ipv4/tcp_fastopen") {
        Ok(mut file) => {
            match write!(file, "{}", queue_size) {
                Ok(_) => {
                    debug!("TCP Fast Open queue size set to {}", queue_size);
                    0
                }
                Err(e) => {
                    error!("Failed to write queue size: {}", e);
                    -1
                }
            }
        }
        Err(e) => {
            debug!("Cannot open tcp_fastopen sysctl (requires root): {}", e);
            -1
        }
    }
}




