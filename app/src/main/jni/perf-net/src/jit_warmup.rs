/*
 * JIT Warm-Up (Rust Implementation)
 * Pre-compiles hot paths to reduce latency
 */

use jni::JNIEnv;
use jni::objects::{JClass, JByteArray};
use jni::sys::{jint, jlong};
use log::{debug, warn};
use std::alloc::{alloc, Layout};

/// Warm up JIT by running hot paths
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeJITWarmup(
    _env: JNIEnv,
    _class: JClass,
) {
    debug!("Starting JIT warm-up");

    // Run some CPU-intensive operations to warm up
    let mut sum: i64 = 0;
    for i in 0..100000 {
        sum += (i * i) as i64;
    }

    // Prevent compiler from optimizing away the loop
    std::hint::black_box(sum);

    // Prefetch some memory to warm up cache
    unsafe {
        let layout = Layout::from_size_align(4096, 64).unwrap();
        if let Ok(ptr) = alloc(layout).as_mut() {
            for i in (0..4096).step_by(64) {
                std::ptr::read_volatile(ptr.add(i));
            }
        } else {
            warn!("Failed to allocate memory for prefetch");
        }
    }

    debug!("JIT warm-up completed");
}

/// Request CPU boost (hint to scheduler)
/// Note: Requires root access on most devices, best-effort only
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeRequestCPUBoost(
    _env: JNIEnv,
    _class: JClass,
    duration_ms: jint,
) -> jint {
    if duration_ms < 0 || duration_ms > 10000 {
        warn!("Invalid CPU boost duration: {} ms (max 10000)", duration_ms);
        return -1;
    }

    // Try to write to CPU boost interface (requires root on most devices)
    use std::fs::File;
    use std::io::Write;

    if let Ok(mut file) = File::create("/sys/devices/system/cpu/cpu_boost/input_boost_ms") {
        if write!(file, "{}", duration_ms).is_ok() {
            debug!("CPU boost requested for {} ms", duration_ms);
            return 0;
        }
    }

    // Try alternative path (read-only check)
    if File::open("/sys/devices/system/cpu/cpu0/cpufreq/scaling_min_freq").is_ok() {
        // Could potentially set min freq to max freq temporarily
        // But requires root and is risky, so we skip it
    }

    debug!("CPU boost not available (requires root)");
    -1 // Not critical if it fails
}

