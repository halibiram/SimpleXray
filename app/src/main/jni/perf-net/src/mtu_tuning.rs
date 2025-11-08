/*
 * MTU Tuning (Rust Implementation)
 * Optimal MTU discovery and socket buffer tuning using socket2
 */

use jni::JNIEnv;
use jni::objects::JClass;
use jni::sys::jint;
use nix::sys::socket::{setsockopt, sockopt};
use log::{debug, error};
use std::os::fd::BorrowedFd;

/// Set optimal MTU based on network type
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeSetOptimalMTU(
    _env: JNIEnv,
    _class: JClass,
    _fd: jint,
    network_type: jint,
) -> jint {
    let optimal_mtu = match network_type {
        0 => 1436, // LTE
        1 => 1460, // 5G
        2 => 1500, // WiFi
        _ => 1436,
    };

    debug!("Recommended MTU for network type {}: {} (not setting - use VpnService.Builder.setMtu())", 
           network_type, optimal_mtu);
    optimal_mtu
}

/// Get current MTU
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeGetMTU(
    _env: JNIEnv,
    _class: JClass,
    _fd: jint,
) -> jint {
    // SELinux blocks ioctl with interface names on VpnService FDs
    // This function cannot reliably get MTU on Android
    debug!("Cannot get MTU - SELinux restrictions. Use VpnService.Builder.getMtu()");
    -1
}

/// Set socket buffer sizes
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeSetSocketBuffers(
    _env: JNIEnv,
    _class: JClass,
    fd: jint,
    send_buffer: jint,
    recv_buffer: jint,
) -> jint {
    let fd = fd as std::os::unix::io::RawFd;
    let mut result = 0;

    // Set send buffer
    let borrowed_fd = unsafe { BorrowedFd::borrow_raw(fd) };
    let send_buf_usize = send_buffer as usize;
    if let Err(e) = setsockopt(&borrowed_fd, sockopt::SndBuf, &send_buf_usize) {
        error!("Failed to set send buffer: {}", e);
        result = -1;
    }

    // Set receive buffer
    let recv_buf_usize = recv_buffer as usize;
    if let Err(e) = setsockopt(&borrowed_fd, sockopt::RcvBuf, &recv_buf_usize) {
        error!("Failed to set recv buffer: {}", e);
        result = -1;
    }

    if result == 0 {
        debug!("Socket buffers set: send={}, recv={}", send_buffer, recv_buffer);
    }

    result
}
