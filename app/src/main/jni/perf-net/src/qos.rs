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
use std::os::fd::BorrowedFd;

// Helper function to set socket option using libc (for options not in nix 0.28)
#[cfg(target_os = "android")]
fn set_sockopt_libc(fd: RawFd, level: i32, optname: i32, optval: *const libc::c_void, optlen: u32) -> i32 {
    unsafe {
        libc::setsockopt(fd, level, optname, optval, optlen as libc::socklen_t)
    }
}

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
    // Use libc directly as Priority may not be in nix 0.28
    #[cfg(target_os = "android")]
    {
        use libc::{SOL_SOCKET, SO_PRIORITY};
        let optval = priority as i32;
        let result = unsafe {
            libc::setsockopt(fd, SOL_SOCKET, SO_PRIORITY, &optval as *const _ as *const libc::c_void, std::mem::size_of::<i32>() as libc::socklen_t)
        };
        if result == 0 {
            debug!("Socket priority set to {} for fd {}", priority, fd);
            0
        } else {
            error!("Failed to set socket priority");
            -1
        }
    }
    #[cfg(not(target_os = "android"))]
    {
        // Try nix first, fallback to libc
        match setsockopt(fd, &sockopt::Priority, &(priority as i32)) {
            Ok(_) => {
                debug!("Socket priority set to {} for fd {}", priority, fd);
                0
            }
            Err(_) => {
                use libc::{SOL_SOCKET, SO_PRIORITY};
                let optval = priority as i32;
                let result = unsafe {
                    libc::setsockopt(fd, SOL_SOCKET, SO_PRIORITY, &optval as *const _ as *const libc::c_void, std::mem::size_of::<i32>() as libc::socklen_t)
                };
                if result == 0 {
                    debug!("Socket priority set to {} for fd {}", priority, fd);
                    0
                } else {
                    error!("Failed to set socket priority");
                    -1
                }
            }
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
    // Use libc directly as IpTos may not be in nix 0.28
    #[cfg(target_os = "android")]
    {
        use libc::{IPPROTO_IP, IP_TOS};
        let optval = tos as u8;
        let result = unsafe {
            libc::setsockopt(fd, IPPROTO_IP, IP_TOS, &optval as *const _ as *const libc::c_void, std::mem::size_of::<u8>() as libc::socklen_t)
        };
        if result == 0 {
            debug!("IP TOS set to 0x{:02x} for fd {}", tos, fd);
            0
        } else {
            error!("Failed to set IP TOS");
            -1
        }
    }
    #[cfg(not(target_os = "android"))]
    {
        match setsockopt(fd, &sockopt::IpTos, &(tos as u8)) {
            Ok(_) => {
                debug!("IP TOS set to 0x{:02x} for fd {}", tos, fd);
                0
            }
            Err(_) => {
                use libc::{IPPROTO_IP, IP_TOS};
                let optval = tos as u8;
                let result = unsafe {
                    libc::setsockopt(fd, IPPROTO_IP, IP_TOS, &optval as *const _ as *const libc::c_void, std::mem::size_of::<u8>() as libc::socklen_t)
                };
                if result == 0 {
                    debug!("IP TOS set to 0x{:02x} for fd {}", tos, fd);
                    0
                } else {
                    error!("Failed to set IP TOS");
                    -1
                }
            }
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
    // Use libc directly as TcpNoDelay may not be in nix 0.28
    let result = {
        #[cfg(target_os = "android")]
        {
            use libc::{IPPROTO_TCP, TCP_NODELAY};
            let result = unsafe {
                libc::setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &opt as *const _ as *const libc::c_void, std::mem::size_of::<i32>() as libc::socklen_t)
            };
            if result == 0 { 0 } else { -1 }
        }
        #[cfg(not(target_os = "android"))]
        {
            match setsockopt(fd, &sockopt::TcpNoDelay, &opt) {
                Ok(_) => 0,
                Err(_) => {
                    use libc::{IPPROTO_TCP, TCP_NODELAY};
                    let result = unsafe {
                        libc::setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &opt as *const _ as *const libc::c_void, std::mem::size_of::<i32>() as libc::socklen_t)
                    };
                    if result == 0 { 0 } else { -1 }
                }
            }
        }
    };

    if result != 0 {
        error!("Failed to set TCP_NODELAY");
    }

    // TCP_QUICKACK (quick ACK) - may not be available on all systems
    // Use libc directly as TcpQuickAck may not be in nix 0.28
    #[cfg(target_os = "android")]
    {
        use libc::{IPPROTO_TCP, TCP_QUICKACK};
        let _ = unsafe {
            libc::setsockopt(fd, IPPROTO_TCP, TCP_QUICKACK, &opt as *const _ as *const libc::c_void, std::mem::size_of::<i32>() as libc::socklen_t)
        };
    }

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

    let keepalive: bool = true;
    let keepidle: i32 = 60;    // 60 seconds before first probe
    let keepintvl: i32 = 10;   // 10 seconds between probes
    let keepcnt: i32 = 3;      // 3 probes before timeout

    // Enable keep-alive
    let borrowed_fd = unsafe { BorrowedFd::borrow_raw(fd) };
    match setsockopt(&borrowed_fd, sockopt::KeepAlive, &keepalive) {
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

    let borrowed_fd = unsafe { BorrowedFd::borrow_raw(fd) };
    let send_buf_usize = send_buf as usize;
    let recv_buf_usize = recv_buf as usize;
    let result1 = setsockopt(&borrowed_fd, sockopt::SndBuf, &send_buf_usize);
    let result2 = setsockopt(&borrowed_fd, sockopt::RcvBuf, &recv_buf_usize);

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




