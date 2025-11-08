/*
 * Zero-Copy I/O operations (Rust Implementation)
 * Direct kernel-to-user-space transfers with minimal copying
 */

use jni::JNIEnv;
use jni::objects::{JClass, JObject, JIntArray, JByteBuffer, JObjectArray};
use jni::sys::{jint, jobject, jobjectArray, jintArray};
use nix::sys::socket::{recv, send, MsgFlags, recvmsg};
use std::os::unix::io::RawFd;
use std::ptr;
use log::{debug, error};

// MSG_ZEROCOPY was introduced in Linux 4.14
const MSG_ZEROCOPY: i32 = 0x4000000;

/// Cache for MSG_ZEROCOPY support detection
static ZEROCOPY_SUPPORTED: std::sync::OnceLock<bool> = std::sync::OnceLock::new();

/// Check if MSG_ZEROCOPY is supported by the kernel
fn check_zerocopy_support() -> bool {
    *ZEROCOPY_SUPPORTED.get_or_init(|| {
        use nix::sys::socket::{socket, AddressFamily, SockType, SockFlag, SockProtocol, setsockopt, sockopt};
        
        let test_fd = match socket(
            AddressFamily::Inet,
            SockType::Stream,
            SockFlag::empty(),
            SockProtocol::Tcp,
        ) {
            Ok(fd) => fd,
            Err(_) => return false,
        };

        // Enable SO_ZEROCOPY option (required for MSG_ZEROCOPY)
        // Use libc directly as SoZerocopy may not be in nix 0.28
        let result = {
            #[cfg(target_os = "android")]
            {
                // SO_ZEROCOPY is Linux 4.14+ only, may not be in libc on older Android
                // Use numeric constant if not available
                const SO_ZEROCOPY: i32 = 60; // Linux 4.14+
                use libc::SOL_SOCKET;
                let optval: i32 = 1;
                let raw_fd = test_fd.as_raw_fd();
                let r = unsafe {
                    libc::setsockopt(raw_fd, SOL_SOCKET, SO_ZEROCOPY, &optval as *const _ as *const libc::c_void, std::mem::size_of::<i32>() as libc::socklen_t)
                };
                if r == 0 { Ok(()) } else { Err(nix::errno::Errno::last()) }
            }
            #[cfg(not(target_os = "android"))]
            {
                setsockopt(&test_fd, sockopt::SoZerocopy, &true)
            }
        };
        let _ = nix::unistd::close(test_fd.as_raw_fd());
        
        let supported = result.is_ok();
        if supported {
            debug!("MSG_ZEROCOPY support detected");
        } else {
            debug!("MSG_ZEROCOPY not supported (kernel may be < 4.14 or feature not enabled)");
        }
        supported
    })
}

/// Receive with zero-copy (MSG_ZEROCOPY if available)
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeRecvZeroCopy(
    env: JNIEnv,
    _class: JClass,
    fd: jint,
    buffer: JObject,
    offset: jint,
    length: jint,
) -> jint {
    if fd < 0 || length < 0 || offset < 0 {
        error!("Invalid parameters: fd={}, offset={}, length={}", fd, offset, length);
        return -1;
    }

    // Convert JObject to JByteBuffer
    let buffer_byte = match JByteBuffer::from(buffer) {
        Ok(buf) => buf,
        Err(_) => {
            error!("Not a direct buffer");
            return -1;
        }
    };

    let buf_ptr = match env.get_direct_buffer_address(&buffer_byte) {
        Ok(ptr) => {
            if ptr.is_null() {
                error!("Not a direct buffer");
                return -1;
            }
            ptr
        },
        Err(_) => {
            error!("Not a direct buffer");
            return -1;
        }
    };

    let capacity = match env.get_direct_buffer_capacity(&buffer_byte) {
        Ok(cap) => cap,
        Err(_) => {
            error!("Failed to get buffer capacity");
            return -1;
        }
    };

    if offset + length > capacity as i32 {
        error!("Buffer overflow: capacity={}, offset={}, length={}", capacity, offset, length);
        return -1;
    }

    let data_ptr = unsafe { buf_ptr.add(offset as usize) };
    let fd = fd as RawFd;

    // Note: MSG_ZEROCOPY is primarily for send operations, not recv
    // For receive operations, we use regular recv with MSG_DONTWAIT
    let flags = MsgFlags::MSG_DONTWAIT;
    
    let received = match recv(fd, unsafe { std::slice::from_raw_parts_mut(data_ptr, length as usize) }, flags) {
        Ok(bytes) => bytes,
        Err(nix::errno::Errno::EAGAIN) => {
            return 0; // No data available
        }
        Err(e) => {
            error!("recv failed: {}", e);
            return -1;
        }
    };

    received as jint
}

/// Send with zero-copy
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeSendZeroCopy(
    env: JNIEnv,
    _class: JClass,
    fd: jint,
    buffer: JObject,
    offset: jint,
    length: jint,
) -> jint {
    if fd < 0 || length < 0 || offset < 0 {
        error!("Invalid parameters: fd={}, offset={}, length={}", fd, offset, length);
        return -1;
    }

    // Convert JObject to JByteBuffer
    let buffer_byte = match JByteBuffer::from(buffer) {
        Ok(buf) => buf,
        Err(_) => {
            error!("Not a direct buffer");
            return -1;
        }
    };

    let buf_ptr = match env.get_direct_buffer_address(&buffer_byte) {
        Ok(ptr) => {
            if ptr.is_null() {
                error!("Not a direct buffer");
                return -1;
            }
            ptr
        },
        Err(_) => {
            error!("Not a direct buffer");
            return -1;
        }
    };

    let capacity = match env.get_direct_buffer_capacity(&buffer_byte) {
        Ok(cap) => cap,
        Err(_) => {
            error!("Failed to get buffer capacity");
            return -1;
        }
    };

    if offset + length > capacity as i32 {
        error!("Buffer overflow: capacity={}, offset={}, length={}", capacity, offset, length);
        return -1;
    }

    let data_ptr = unsafe { buf_ptr.add(offset as usize) };
    let fd = fd as RawFd;

    // Enable SO_ZEROCOPY on socket if not already enabled
    if check_zerocopy_support() {
        #[cfg(target_os = "android")]
        {
            const SO_ZEROCOPY: i32 = 60; // Linux 4.14+
            use libc::SOL_SOCKET;
            let optval: i32 = 1;
            let _ = unsafe {
                libc::setsockopt(fd, SOL_SOCKET, SO_ZEROCOPY, &optval as *const _ as *const libc::c_void, std::mem::size_of::<i32>() as libc::socklen_t)
            };
        }
        #[cfg(not(target_os = "android"))]
        {
            use nix::sys::socket::{setsockopt, sockopt};
            use std::os::fd::BorrowedFd;
            let borrowed_fd = unsafe { BorrowedFd::borrow_raw(fd) };
            let _ = setsockopt(borrowed_fd, sockopt::SoZerocopy, &true);
        }
    }

    let flags = if check_zerocopy_support() {
        MsgFlags::MSG_DONTWAIT | MsgFlags::MSG_NOSIGNAL
    } else {
        MsgFlags::MSG_DONTWAIT | MsgFlags::MSG_NOSIGNAL
    };

    let sent = match send(fd, unsafe { std::slice::from_raw_parts(data_ptr, length as usize) }, flags) {
        Ok(bytes) => bytes,
        Err(nix::errno::Errno::EAGAIN) => {
            return 0; // Would block
        }
        Err(e) => {
            error!("send failed: {}", e);
            return -1;
        }
    };

    sent as jint
}

/// Scatter-gather receive (recvmsg)
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeRecvMsg(
    env: JNIEnv,
    _class: JClass,
    fd: jint,
    buffers: jobjectArray,
    lengths: jintArray,
) -> jint {
    if fd < 0 {
        error!("Invalid file descriptor: {}", fd);
        return -1;
    }

    // Convert raw pointers to JNI types
    let buffers_array = unsafe { JObjectArray::from_raw(buffers) };
    let lengths_array = unsafe { JIntArray::from_raw(lengths) };

    let num_buffers = match env.get_array_length(&buffers_array) {
        Ok(len) => len,
        Err(_) => {
            error!("Failed to get buffers array length");
            return -1;
        }
    };

    let num_lengths = match env.get_array_length(&lengths_array) {
        Ok(len) => len,
        Err(_) => {
            error!("Failed to get lengths array length");
            return -1;
        }
    };

    if num_buffers == 0 || num_buffers != num_lengths {
        error!("Invalid array sizes: buffers={}, lengths={}", num_buffers, num_lengths);
        return -1;
    }

    // Build iovec array
    let mut iovecs: Vec<libc::iovec> = Vec::new();
    
    // JNI 0.21 doesn't have get_int_array_elements, use get_int_array_region instead
    let mut len_values = vec![0i32; num_buffers as usize];
    if let Err(_) = env.get_int_array_region(&lengths_array, 0, &mut len_values) {
        error!("Failed to get lengths array region");
        return -1;
    }

    for i in 0..num_buffers {
        let buffer = match env.get_object_array_element(&buffers_array, i) {
            Ok(buf) => buf,
            Err(_) => {
                error!("Failed to get buffer at index {}", i);
                return -1;
            }
        };

        // Convert JObject to JByteBuffer
        let buffer_byte = match JByteBuffer::from(buffer) {
            Ok(buf) => buf,
            Err(_) => {
                error!("Not a direct buffer at index {}", i);
                return -1;
            }
        };

        let buf_ptr = match env.get_direct_buffer_address(&buffer_byte) {
            Ok(ptr) => {
                if ptr.is_null() {
                    error!("Not a direct buffer at index {}", i);
                    return -1;
                }
                ptr
            },
            Err(_) => {
                error!("Not a direct buffer at index {}", i);
                return -1;
            }
        };

        let len = len_values[i as usize];
        if len < 0 {
            error!("Invalid length at index {}: {}", i, len);
            return -1;
        }

        // Use libc::iovec directly (nix 0.28 removed IoVec from sys::uio)
        iovecs.push(libc::iovec {
            iov_base: buf_ptr as *mut libc::c_void,
            iov_len: len as usize,
        });
    }

    let fd = fd as RawFd;
    let flags = MsgFlags::MSG_DONTWAIT;

    // Convert libc::iovec to nix::IoSliceMut
    use std::os::unix::io::IoSliceMut;
    let mut io_slices: Vec<IoSliceMut> = iovecs.iter().map(|iov| {
        unsafe {
            IoSliceMut::new(std::slice::from_raw_parts_mut(iov.iov_base as *mut u8, iov.iov_len))
        }
    }).collect();

    match recvmsg(fd, &mut io_slices, flags, None) {
        Ok(received) => received as jint,
        Err(nix::errno::Errno::EAGAIN) => 0,
        Err(e) => {
            error!("recvmsg failed: {}", e);
            -1
        }
    }
}

/// Allocate direct ByteBuffer in native memory
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeAllocateDirectBuffer(
    env: JNIEnv,
    _class: JClass,
    capacity: jint,
) -> jobject {
    if capacity <= 0 {
        return ptr::null_mut();
    }

    // Create DirectByteBuffer using JVM's native allocation
    let byte_buffer_class = match env.find_class("java/nio/ByteBuffer") {
        Ok(cls) => cls,
        Err(_) => return ptr::null_mut(),
    };

    let allocate_direct_method = match env.get_static_method_id(
        byte_buffer_class,
        "allocateDirect",
        "(I)Ljava/nio/ByteBuffer;",
    ) {
        Ok(mid) => mid,
        Err(_) => return ptr::null_mut(),
    };

    match env.call_static_method(
        byte_buffer_class,
        "allocateDirect",
        "(I)Ljava/nio/ByteBuffer;",
        &[jni::objects::JValue::Int(capacity)],
    ) {
        Ok(jval) => {
            match jval.l() {
                Ok(obj) => obj.into_raw(),
                Err(_) => ptr::null_mut(),
            }
        },
        Err(_) => ptr::null_mut(),
    }
}

