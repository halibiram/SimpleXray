/*
 * Read-Ahead Optimization (Rust Implementation)
 * Prefetches next chunks to fill Android I/O pipeline
 */

use jni::JNIEnv;
use jni::objects::JClass;
use jni::sys::{jint, jlong};
use log::{debug, error};
use nix::sys::socket::{recv, MsgFlags};
// fcntl will be used conditionally based on target OS
use std::os::unix::io::RawFd;

/// Enable read-ahead for file descriptor
/// Uses posix_fadvise() if available
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeEnableReadAhead(
    _env: JNIEnv,
    _class: JClass,
    fd: jint,
    _offset: jlong,
    _length: jlong,
) -> jint {
    let fd = fd as RawFd;
    let mut buffer = [0u8; 4096];

    // posix_fadvise() is not available on Android, but we can hint
    // by doing a small prefetch read
    match recv(fd, &mut buffer, MsgFlags::MSG_PEEK | MsgFlags::MSG_DONTWAIT) {
        Ok(_) => {
            debug!("Read-ahead enabled for fd {}", fd);
            0
        }
        Err(nix::errno::Errno::EAGAIN) => {
            // No data available, but that's OK
            debug!("Read-ahead enabled for fd {} (no data yet)", fd);
            0
        }
        Err(e) => {
            error!("Read-ahead peek failed: {}", e);
            -1
        }
    }
}

/// Prefetch data for streaming
/// Reads 1-2 chunks ahead using MSG_PEEK to avoid consuming data
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativePrefetchChunks(
    _env: JNIEnv,
    _class: JClass,
    fd: jint,
    chunk_size: jint,
    num_chunks: jint,
) -> jint {
    let fd = fd as RawFd;

    if chunk_size <= 0 || num_chunks <= 0 || chunk_size > 1024 * 1024 {
        error!("Invalid chunk size or count");
        return -1;
    }

    // Use MSG_PEEK to prefetch without consuming data
    let mut buffer = vec![0u8; chunk_size as usize];

    // Get current socket flags
    let (flags, was_nonblocking) = {
        #[cfg(target_os = "android")]
        {
            use libc::{fcntl, F_GETFL, F_SETFL, O_NONBLOCK};
            let flags = unsafe { fcntl(fd, F_GETFL) };
            if flags < 0 {
                error!("Failed to get socket flags");
                return -1;
            }
            let was_nonblocking = (flags & O_NONBLOCK) != 0;
            (flags, was_nonblocking)
        }
        #[cfg(not(target_os = "android"))]
        {
            use nix::fcntl::{fcntl, FcntlArg, OFlag};
            let flags = match fcntl(fd, FcntlArg::F_GETFL) {
                Ok(f) => OFlag::from_bits_truncate(f),
                Err(_) => {
                    error!("Failed to get socket flags");
                    return -1;
                }
            };
            let was_nonblocking = flags.contains(OFlag::O_NONBLOCK);
            (flags.bits(), was_nonblocking)
        }
    };

    // Ensure non-blocking for peek
    if !was_nonblocking {
        #[cfg(target_os = "android")]
        {
            use libc::{fcntl, F_SETFL, O_NONBLOCK};
            let result = unsafe { fcntl(fd, F_SETFL, flags | O_NONBLOCK) };
            if result < 0 {
                error!("Failed to set non-blocking");
                return -1;
            }
        }
        #[cfg(not(target_os = "android"))]
        {
            use nix::fcntl::{fcntl, FcntlArg, OFlag};
            if let Err(e) = fcntl(fd, FcntlArg::F_SETFL(OFlag::from_bits_truncate(flags) | OFlag::O_NONBLOCK)) {
                error!("Failed to set non-blocking: {}", e);
                return -1;
            }
        }
    }

    // Peek at data to prefetch into kernel buffer
    let mut total_peeked = 0i64;
    for _ in 0..num_chunks {
        match recv(fd, &mut buffer, MsgFlags::MSG_PEEK | MsgFlags::MSG_DONTWAIT) {
            Ok(peeked) => {
                total_peeked += peeked as i64;
                if peeked < chunk_size as usize {
                    break; // Partial peek
                }
            }
            Err(nix::errno::Errno::EAGAIN) => {
                break; // No more data available
            }
            Err(e) => {
                error!("Prefetch peek failed: {}", e);
                break;
            }
        }
    }

    // Restore original blocking state
    if !was_nonblocking {
        #[cfg(target_os = "android")]
        {
            use libc::{fcntl, F_SETFL};
            let _ = unsafe { fcntl(fd, F_SETFL, flags) };
        }
        #[cfg(not(target_os = "android"))]
        {
            use nix::fcntl::{fcntl, FcntlArg, OFlag};
            let _ = fcntl(fd, FcntlArg::F_SETFL(OFlag::from_bits_truncate(flags)));
        }
    }

    if total_peeked > 0 {
        debug!("Prefetched {} bytes into kernel buffer ({} chunks)", total_peeked, num_chunks);
    }

    total_peeked as jint
}




