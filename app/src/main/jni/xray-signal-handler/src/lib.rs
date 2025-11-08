/*
 * Signal Handler for Xray-core Process (Rust Implementation)
 * Catches SIGABRT, SIGSEGV, SIGBUS and logs to logcat
 */

use jni::JNIEnv;
use jni::objects::JClass;
use jni::sys::jint;
use log::{error, info};
use std::sync::Mutex;

// Original signal handlers storage
static OLD_HANDLERS: Mutex<Option<Vec<libc::sigaction>>> = Mutex::new(None);

// Maximum stack trace depth
const MAX_STACK_DEPTH: usize = 32;

/// Signal handler function
extern "C" fn signal_handler(sig: libc::c_int) {
    let sig_name = match sig {
        libc::SIGABRT => "SIGABRT",
        libc::SIGSEGV => "SIGSEGV",
        libc::SIGBUS => "SIGBUS",
        _ => "UNKNOWN",
    };

    error!("========================================");
    error!("Xray-core Signal Caught: {} (signal {})", sig_name, sig);
    error!("PID: {}, UID: {}", unsafe { libc::getpid() }, unsafe { libc::getuid() });

    // Print stack trace using backtrace
    print_stack_trace();

    error!("========================================");

    // Call original handler if it exists
    let old_handlers = OLD_HANDLERS.lock().unwrap();
    if let Some(ref handlers) = *old_handlers {
        let idx = match sig {
            libc::SIGABRT => 0,
            libc::SIGSEGV => 1,
            libc::SIGBUS => 2,
            _ => return,
        };
        
        if idx < handlers.len() {
            let sa = &handlers[idx];
            unsafe {
                if sa.sa_sigaction != libc::SIG_DFL && sa.sa_sigaction != libc::SIG_IGN {
                    if sa.sa_flags & libc::SA_SIGINFO != 0 {
                        // Use sigaction with siginfo
                        let mut info: libc::siginfo_t = std::mem::zeroed();
                        let mut context: libc::ucontext_t = std::mem::zeroed();
                        let handler: extern "C" fn(libc::c_int, *mut libc::siginfo_t, *mut libc::ucontext_t) =
                            std::mem::transmute(sa.sa_sigaction);
                        handler(sig, &mut info, &mut context);
                    } else {
                        // Use simple handler - access union field correctly
                        #[cfg(target_os = "android")]
                        let handler_addr = unsafe {
                            // On Android, sa_handler is part of a union, access via sa_sigaction
                            // For simple handlers, we need to check if it's a function pointer
                            if sa.sa_sigaction != libc::SIG_DFL as usize && sa.sa_sigaction != libc::SIG_IGN as usize {
                                sa.sa_sigaction
                            } else {
                                return;
                            }
                        };
                        #[cfg(not(target_os = "android"))]
                        let handler_addr = unsafe {
                            // On other platforms, use sa_handler directly
                            std::mem::transmute::<_, usize>(sa.sa_handler)
                        };
                        let handler: extern "C" fn(libc::c_int) = unsafe { std::mem::transmute(handler_addr) };
                        handler(sig);
                    }
                } else {
                    // Default action: abort
                    libc::_exit(128 + sig);
                }
            }
        }
    } else {
        // Default action: abort
        unsafe {
            libc::_exit(128 + sig);
        }
    }
}

/// Print stack trace to logcat
fn print_stack_trace() {
    let backtrace = std::backtrace::Backtrace::capture();
    error!("Stack trace:");
    // Use format! to convert backtrace to string
    let bt_str = format!("{:?}", backtrace);
    for (i, line) in bt_str.lines().take(MAX_STACK_DEPTH).enumerate() {
        error!("  #{}: {}", i, line);
    }
}

/// Install signal handlers
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_xray_XraySignalHandler_nativeInstallHandlers(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    // Initialize logger
    android_logger::init_once(
        android_logger::Config::default()
            .with_tag("XraySignalHandler")
            .with_max_level(log::LevelFilter::Debug),
    );

    let signals = [libc::SIGABRT, libc::SIGSEGV, libc::SIGBUS];
    let mut old_handlers = Vec::new();

    for sig in signals.iter() {
        unsafe {
            let mut old_sa: libc::sigaction = std::mem::zeroed();
            let mut new_sa: libc::sigaction = std::mem::zeroed();
            
            new_sa.sa_sigaction = signal_handler as usize;
            new_sa.sa_flags = libc::SA_SIGINFO | libc::SA_ONSTACK;
            libc::sigemptyset(&mut new_sa.sa_mask);

            if libc::sigaction(*sig, &new_sa, &mut old_sa) == 0 {
                old_handlers.push(old_sa);
                info!("Signal {} handler installed", sig);
            } else {
                error!("Failed to install signal {} handler", sig);
                return -1;
            }
        }
    }

    *OLD_HANDLERS.lock().unwrap() = Some(old_handlers);
    info!("All signal handlers installed successfully");
    0
}

/// Restore original signal handlers
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_xray_XraySignalHandler_nativeRestoreHandlers(
    _env: JNIEnv,
    _class: JClass,
) {
    let signals = [libc::SIGABRT, libc::SIGSEGV, libc::SIGBUS];
    let old_handlers = OLD_HANDLERS.lock().unwrap();
    
    if let Some(ref handlers) = *old_handlers {
        for (i, sig) in signals.iter().enumerate() {
            if i < handlers.len() {
                unsafe {
                    let _ = libc::sigaction(*sig, &handlers[i], std::ptr::null_mut());
                }
            }
        }
    }

    drop(old_handlers);
    info!("Signal handlers restored");
}

