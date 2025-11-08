/*
 * QoS Tricks for Critical Packets (Rust Implementation)
 * High-priority socket flags for latency-sensitive traffic
 */

use jni::JNIEnv;
use jni::objects::JClass;
use jni::sys::jint;
use log::{debug, error};
use nix::sys::socket::{setsockopt, sockopt};
use std::os::unix::io::RawFd;

/// Set socket priority for QoS
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeSetSocketPriority(
    _env: JNIEnv,
    _class: JClass,
    fd: jint,
    priority: jint,
) -> jint {
    let fd = fd as RawFd;

    if fd < 0 {
        error!("Invalid file descriptor: {}", fd);
        return -1;
    }

    if priority < 0 || priority > 6 {
        error!("Invalid priority: {} (must be 0-6)", priority);
        return -1;
    }

    // SO_PRIORITY (0-6, higher = more important)
    match setsockopt(fd, &sockopt::Priority, &(priority as i32)) {
        Ok(_) => {
            debug!("Socket priority set to {} for fd {}", priority, fd);
            0
        }
        Err(e) => {
            error!("Failed to set socket priority: {}", e);
            -1
        }
    }
}

/// Set IP TOS (Type of Service) for QoS
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeSetIPTOS(
    _env: JNIEnv,
    _class: JClass,
    fd: jint,
    tos: jint,
) -> jint {
    let fd = fd as RawFd;

    if fd < 0 {
        error!("Invalid file descriptor: {}", fd);
        return -1;
    }

    if tos < 0 || tos > 255 {
        error!("Invalid TOS value: {} (must be 0-255)", tos);
        return -1;
    }

    // IPTOS_LOWDELAY (0x10) for low latency
    // IPTOS_THROUGHPUT (0x08) for high throughput
    // IPTOS_RELIABILITY (0x04) for reliability
    match setsockopt(fd, &sockopt::IpTos, &(tos as u8)) {
        Ok(_) => {
            debug!("IP TOS set to 0x{:02x} for fd {}", tos, fd);
            0
        }
        Err(e) => {
            error!("Failed to set IP TOS: {}", e);
            -1
        }
    }
}

/// Enable TCP Low Latency mode (if supported)
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeEnableTCPLowLatency(
    _env: JNIEnv,
    _class: JClass,
    fd: jint,
) -> jint {
    let fd = fd as RawFd;

    if fd < 0 {
        error!("Invalid file descriptor: {}", fd);
        return -1;
    }

    let opt: i32 = 1;

    // TCP_NODELAY (disable Nagle's algorithm)
    let result = match setsockopt(fd, &sockopt::TcpNoDelay, &opt) {
        Ok(_) => 0,
        Err(e) => {
            error!("Failed to set TCP_NODELAY: {}", e);
            -1
        }
    };

    // TCP_QUICKACK (quick ACK) - may not be available on all systems
    let _ = setsockopt(fd, &sockopt::TcpQuickAck, &opt);

    if result == 0 {
        debug!("TCP low latency enabled for fd {}", fd);
    }

    result
}

/// Optimize TCP Keep-Alive settings
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeOptimizeKeepAlive(
    _env: JNIEnv,
    _class: JClass,
    fd: jint,
) -> jint {
    let fd = fd as RawFd;

    let keepalive: i32 = 1;
    let keepidle: i32 = 60;    // 60 seconds before first probe
    let keepintvl: i32 = 10;   // 10 seconds between probes
    let keepcnt: i32 = 3;      // 3 probes before timeout

    // Enable keep-alive
    match setsockopt(fd, &sockopt::KeepAlive, &keepalive) {
        Ok(_) => {}
        Err(e) => {
            error!("Failed to enable SO_KEEPALIVE: {}", e);
            return -1;
        }
    }

    // Set keep-alive parameters (Linux-specific)
    // Note: These may not be available on all systems or in nix 0.28
    // Using libc directly for Android compatibility
    #[cfg(target_os = "android")]
    {
        use libc::{setsockopt, SOL_TCP, TCP_KEEPIDLE, TCP_KEEPINTVL, TCP_KEEPCNT};
        unsafe {
            let _ = setsockopt(fd, SOL_TCP, TCP_KEEPIDLE as i32, &keepidle as *const _ as *const _, std::mem::size_of::<i32>() as u32);
            let _ = setsockopt(fd, SOL_TCP, TCP_KEEPINTVL as i32, &keepintvl as *const _ as *const _, std::mem::size_of::<i32>() as u32);
            let _ = setsockopt(fd, SOL_TCP, TCP_KEEPCNT as i32, &keepcnt as *const _ as *const _, std::mem::size_of::<i32>() as u32);
        }
    }
    #[cfg(not(target_os = "android"))]
    {
        // For non-Android systems, try nix socket options if available
        // These may not be available in nix 0.28
        // let _ = setsockopt(fd, &sockopt::TcpKeepIdle, &keepidle);
        // let _ = setsockopt(fd, &sockopt::TcpKeepIntvl, &keepintvl);
        // let _ = setsockopt(fd, &sockopt::TcpKeepCnt, &keepcnt);
    }

    debug!("TCP keep-alive optimized for fd {} (idle: {}, intvl: {}, cnt: {})", 
           fd, keepidle, keepintvl, keepcnt);
    0
}

/// Optimize socket buffer sizes based on network type
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeOptimizeSocketBuffers(
    _env: JNIEnv,
    _class: JClass,
    fd: jint,
    network_type: jint,
) -> jint {
    let fd = fd as RawFd;

    // Network type: 0=WiFi, 1=5G, 2=LTE, 3=Other
    let (send_buf, recv_buf) = match network_type {
        0 => (512 * 1024, 512 * 1024),  // WiFi: 512 KB
        1 => (1024 * 1024, 1024 * 1024), // 5G: 1 MB
        2 => (256 * 1024, 256 * 1024),  // LTE: 256 KB
        3 => (256 * 1024, 256 * 1024),  // Other: 256 KB
        _ => {
            error!("Invalid network type: {} (expected 0-3: WiFi=0, 5G=1, LTE=2, Other=3). Using default.", network_type);
            (256 * 1024, 256 * 1024)
        }
    };

    let result1 = setsockopt(fd, &sockopt::SndBuf, &(send_buf as u32));
    let result2 = setsockopt(fd, &sockopt::RcvBuf, &(recv_buf as u32));

    match (result1, result2) {
        (Ok(_), Ok(_)) => {
            debug!("Socket buffers optimized for fd {} (send: {}, recv: {})", fd, send_buf, recv_buf);
            0
        }
        _ => {
            error!("Failed to optimize socket buffers");
            -1
        }
    }
}




