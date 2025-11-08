/*
 * JNI Bridge for Performance Module (Rust Implementation)
 * Main entry point for Java/Kotlin integration
 */

use jni::JNIEnv;
use jni::JavaVM;
use jni::objects::JClass;
use jni::sys::{jint, JNI_VERSION_1_6};
use log::info;

// Global JavaVM pointer for thread attachment
static mut G_JVM: *mut JavaVM = std::ptr::null_mut();

#[no_mangle]
pub extern "C" fn JNI_OnLoad(vm: JavaVM, _reserved: *mut std::ffi::c_void) -> jint {
    unsafe {
        G_JVM = Box::into_raw(Box::new(vm));
    }

    // Initialize logger
    android_logger::init_once(
        android_logger::Config::default()
            .with_tag("PerfJNI")
            .with_min_level(log::Level::Debug),
    );

    info!("Performance module JNI loaded (Rust)");
    JNI_VERSION_1_6
}

#[no_mangle]
pub extern "C" fn JNI_OnUnload(_vm: JavaVM, _reserved: *mut std::ffi::c_void) {
    unsafe {
        if !G_JVM.is_null() {
            let _ = Box::from_raw(G_JVM);
            G_JVM = std::ptr::null_mut();
        }
    }

    // Cleanup connection pools
    crate::connection_pool::Java_com_simplexray_an_performance_PerformanceManager_nativeDestroyConnectionPool(
        unsafe { std::mem::zeroed() },
        unsafe { std::mem::zeroed() },
    );

    info!("Performance module JNI unloaded and cleaned up");
}

