/*
 * Epoll Loop (Rust Implementation)
 * Dedicated epoll loop for ultra-fast I/O using mio
 */

use jni::JNIEnv;
use jni::objects::JClass;
use jni::sys::{jint, jlong, jlongArray};
use mio::{Events, Poll, Token, Interest};
use std::collections::HashMap;
use std::sync::Arc;
use parking_lot::Mutex;
use std::os::unix::io::{AsRawFd, RawFd};
use log::{debug, error};

const MAX_EVENTS: usize = 256;

struct EpollContext {
    poll: Poll,
    registered_fds: HashMap<RawFd, Token>,
    next_token: usize,
}

impl EpollContext {
    fn new() -> Result<Self, std::io::Error> {
        let poll = Poll::new()?;
        Ok(Self {
            poll,
            registered_fds: HashMap::new(),
            next_token: 1,
        })
    }
}

static EPOLL_CONTEXT: Mutex<Option<Arc<Mutex<EpollContext>>>> = Mutex::new(None);

/// Initialize epoll loop
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeInitEpoll(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    let mut ctx_guard = EPOLL_CONTEXT.lock();
    if let Some(ref ctx) = *ctx_guard {
        return ctx.as_ptr() as jlong;
    }

    match EpollContext::new() {
        Ok(ctx) => {
            let ctx = Arc::new(Mutex::new(ctx));
            let handle = ctx.as_ptr() as jlong;
            *ctx_guard = Some(ctx);
            debug!("Epoll initialized");
            handle
        }
        Err(e) => {
            error!("Failed to create epoll: {}", e);
            0
        }
    }
}

/// Add file descriptor to epoll
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeEpollAdd(
    _env: JNIEnv,
    _class: JClass,
    epoll_handle: jlong,
    fd: jint,
    events: jint,
) -> jint {
    if epoll_handle == 0 || fd < 0 {
        error!("Invalid parameters: handle={}, fd={}", epoll_handle, fd);
        return -1;
    }

    let ctx_guard = EPOLL_CONTEXT.lock();
    let ctx = match ctx_guard.as_ref() {
        Some(c) => c.clone(),
        None => {
            error!("Epoll context not found");
            return -1;
        }
    };

    let mut ctx = ctx.lock();
    let fd = fd as RawFd;

    // Check if already registered
    if ctx.registered_fds.contains_key(&fd) {
        debug!("FD {} already registered", fd);
        return 0;
    }

    // Set non-blocking (should already be set, but ensure it)
    use nix::fcntl::{fcntl, FcntlArg, OFlag};
    let _ = fcntl(fd, FcntlArg::F_SETFL(OFlag::O_NONBLOCK));

    let token = Token(ctx.next_token);
    ctx.next_token += 1;

    // Convert JNI events to mio Interest
    let interest = if (events & 1) != 0 { // EPOLLIN
        Interest::READABLE
    } else if (events & 4) != 0 { // EPOLLOUT
        Interest::WRITABLE
    } else {
        Interest::READABLE | Interest::WRITABLE
    };

    // Create a wrapper for the raw FD
    struct FdWrapper(RawFd);
    impl AsRawFd for FdWrapper {
        fn as_raw_fd(&self) -> RawFd {
            self.0
        }
    }

    let wrapper = FdWrapper(fd);
    match ctx.poll.registry().register(&wrapper, token, interest) {
        Ok(_) => {
            ctx.registered_fds.insert(fd, token);
            debug!("Added fd {} to epoll", fd);
            0
        }
        Err(e) => {
            error!("Failed to add fd {} to epoll: {}", fd, e);
            -1
        }
    }
}

/// Remove file descriptor from epoll
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeEpollRemove(
    _env: JNIEnv,
    _class: JClass,
    epoll_handle: jlong,
    fd: jint,
) -> jint {
    if epoll_handle == 0 || fd < 0 {
        error!("Invalid parameters");
        return -1;
    }

    let ctx_guard = EPOLL_CONTEXT.lock();
    let ctx = match ctx_guard.as_ref() {
        Some(c) => c.clone(),
        None => return -1,
    };

    let mut ctx = ctx.lock();
    let fd = fd as RawFd;

    if let Some(token) = ctx.registered_fds.remove(&fd) {
        struct FdWrapper(RawFd);
        impl AsRawFd for FdWrapper {
            fn as_raw_fd(&self) -> RawFd {
                self.0
            }
        }

        let wrapper = FdWrapper(fd);
        match ctx.poll.registry().deregister(&wrapper) {
            Ok(_) => {
                debug!("Removed fd {} from epoll", fd);
                0
            }
            Err(e) => {
                error!("Failed to remove fd {} from epoll: {}", fd, e);
                -1
            }
        }
    } else {
        debug!("FD {} not found in epoll", fd);
        0
    }
}

/// Wait for events
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeEpollWait(
    env: JNIEnv,
    _class: JClass,
    epoll_handle: jlong,
    out_events: jlongArray,
    timeout_ms: jint,
) -> jint {
    if epoll_handle == 0 {
        error!("Invalid epoll handle");
        return -1;
    }

    let ctx_guard = EPOLL_CONTEXT.lock();
    let ctx = match ctx_guard.as_ref() {
        Some(c) => c.clone(),
        None => {
            error!("Epoll context not found");
            return -1;
        }
    };

    let mut ctx = ctx.lock();
    let mut events = Events::with_capacity(MAX_EVENTS);

    let timeout = if timeout_ms == -1 {
        None
    } else if timeout_ms == 0 {
        Some(std::time::Duration::ZERO)
    } else {
        Some(std::time::Duration::from_millis(timeout_ms as u64))
    };

    match ctx.poll.poll(&mut events, timeout) {
        Ok(_) => {
            let nfds = events.len();
            if nfds > 0 && !out_events.is_null() {
                let size = match env.get_array_length(out_events) {
                    Ok(s) => s,
                    Err(_) => {
                        error!("Failed to get array length");
                        return -1;
                    }
                };

                let nfds = nfds.min(size as usize);
                let mut arr = match env.get_long_array_elements(out_events, jni::objects::ReleaseMode::CopyBack) {
                    Ok(a) => a,
                    Err(_) => {
                        error!("Failed to get array elements");
                        return -1;
                    }
                };

                for (i, event) in events.iter().take(nfds).enumerate() {
                    // Find fd for this token
                    let fd = ctx.registered_fds.iter()
                        .find(|(_, &t)| t == event.token())
                        .map(|(&fd, _)| fd)
                        .unwrap_or(0);

                    // Pack fd and events into jlong
                    let events_bits = if event.is_readable() { 1 } else { 0 } |
                                     if event.is_writable() { 4 } else { 0 };
                    arr[i] = ((fd as jlong) << 32) | events_bits as jlong;
                }

                drop(arr);
            }
            nfds as jint
        }
        Err(e) => {
            error!("epoll_wait failed: {}", e);
            -1
        }
    }
}

/// Destroy epoll loop
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeDestroyEpoll(
    _env: JNIEnv,
    _class: JClass,
    epoll_handle: jlong,
) {
    if epoll_handle == 0 {
        return;
    }

    let mut ctx_guard = EPOLL_CONTEXT.lock();
    *ctx_guard = None;
    debug!("Epoll destroyed");
}
