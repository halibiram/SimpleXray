/*
 * CPU Core Affinity & Pinning (Rust Implementation)
 * Pins threads to specific CPU cores for maximum performance
 */

use jni::JNIEnv;
use jni::objects::JClass;
use jni::sys::{jint, jlong};
use nix::sched::{CpuSet, sched_setaffinity};
use nix::unistd::Pid;
use libc;
use log::{debug, error};

/// Set CPU affinity for current thread
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeSetCPUAffinity(
    _env: JNIEnv,
    _class: JClass,
    cpu_mask: jlong,
) -> jint {
    let cpu_count = num_cpus::get();
    let mut cpuset = CpuSet::new();
    let mut cpus_set = 0;

    // Convert bitmask to CpuSet
    let cpu_mask_u64 = cpu_mask as u64;
    for i in 0..cpu_count.min(64) {
        if cpu_mask_u64 & (1u64 << i) != 0 {
            if let Err(e) = cpuset.set(i) {
                error!("Failed to set CPU {}: {}", i, e);
                return -1;
            }
            cpus_set += 1;
        }
    }

    if cpus_set == 0 {
        error!("CPU mask is empty - no CPUs selected");
        return -1;
    }

    // Set affinity for current thread
    let pid = Pid::from_raw(0); // 0 means current thread
    match sched_setaffinity(pid, &cpuset) {
        Ok(_) => {
            debug!("CPU affinity set successfully, mask: 0x{:x}, CPUs: {}", cpu_mask, cpus_set);
            0
        }
        Err(e) => {
            error!("Failed to set CPU affinity: {}", e);
            -1
        }
    }
}

/// Pin thread to big cores (typical: cores 4-7 on 8-core devices)
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativePinToBigCores(
    env: JNIEnv,
    class: JClass,
) -> jint {
    // Common big core layout: 4,5,6,7 (adjust based on device)
    let big_cores = (1u64 << 4) | (1u64 << 5) | (1u64 << 6) | (1u64 << 7);
    Java_com_simplexray_an_performance_PerformanceManager_nativeSetCPUAffinity(env, class, big_cores as jlong)
}

/// Pin thread to little cores (typical: cores 0-3)
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativePinToLittleCores(
    env: JNIEnv,
    class: JClass,
) -> jint {
    let little_cores = (1u64 << 0) | (1u64 << 1) | (1u64 << 2) | (1u64 << 3);
    Java_com_simplexray_an_performance_PerformanceManager_nativeSetCPUAffinity(env, class, little_cores as jlong)
}

/// Get current CPU core
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeGetCurrentCPU(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    unsafe {
        let cpu = libc::sched_getcpu();
        if cpu < 0 {
            error!("Failed to get current CPU");
            -1
        } else {
            cpu
        }
    }
}

/// Request performance CPU governor
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeRequestPerformanceGovernor(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    use std::fs;

    // Try to write to scaling_governor (usually requires root)
    let path = "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor";
    
    match fs::write(path, "performance") {
        Ok(_) => {
            // Verify that governor was actually set
            match fs::read_to_string(path) {
                Ok(current) => {
                    let trimmed = current.trim();
                    if trimmed == "performance" {
                        debug!("Performance governor set and verified successfully");
                        0
                    } else {
                        debug!("Performance governor requested but current governor is: {}", trimmed);
                        0 // Still return success as it's best-effort
                    }
                }
                Err(_) => {
                    debug!("Performance governor requested (verification unavailable)");
                    0
                }
            }
        }
        Err(e) => {
            debug!("Failed to set performance governor: {} (this is expected on non-root devices)", e);
            -1
        }
    }
}




