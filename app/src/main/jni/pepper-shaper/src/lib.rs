/*
 * PepperShaper: Traffic shaping module (Rust Implementation)
 * Provides burst-friendly streaming with loss-aware backoff
 */

mod queue;
mod pacing;

use jni::JNIEnv;
use jni::objects::{JClass, JObject};
use jni::sys::{jboolean, jint, jlong};
use std::sync::Arc;
use parking_lot::Mutex;
use log::{debug, error, info};
use std::collections::HashMap;
use std::sync::OnceLock;

use queue::PepperRingBuffer;
use pacing::{PepperPacingState, PepperPacingParams, can_send, update_after_send, get_time_ns};

/// Shaper handle with ring buffers and pacing
struct PepperShaperHandle {
    read_fd: i32,
    write_fd: i32,
    mode: i32,
    active: Arc<std::sync::atomic::AtomicBool>,
    tx_queue: Arc<PepperRingBuffer>,
    rx_queue: Arc<PepperRingBuffer>,
    pacing_state: Arc<Mutex<PepperPacingState>>,
    pacing_params: Arc<Mutex<PepperPacingParams>>,
}

// Handle storage
static HANDLES: OnceLock<Mutex<HashMap<i64, Arc<PepperShaperHandle>>>> = OnceLock::new();
static NEXT_HANDLE_ID: std::sync::atomic::AtomicI64 = std::sync::atomic::AtomicI64::new(1);
static INITIALIZED: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

fn get_handles() -> &'static Mutex<HashMap<i64, Arc<PepperShaperHandle>>> {
    HANDLES.get_or_init(|| Mutex::new(HashMap::new()))
}

/// Initialize PepperShaper
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_chain_pepper_PepperShaper_nativeInit(
    _env: JNIEnv,
    _class: JClass,
) {
    android_logger::init_once(
        android_logger::Config::default()
            .with_tag("PepperShaper")
            .with_max_level(log::LevelFilter::Debug),
    );

    if INITIALIZED.swap(true, std::sync::atomic::Ordering::AcqRel) {
        debug!("Already initialized");
        return;
    }
    info!("PepperShaper native initialized");
}

/// Extract parameters from Java object
fn extract_params(env: &mut JNIEnv, params: &JObject) -> Option<PepperPacingParams> {
    let _params_class = env.get_object_class(params).ok()?;
    
    let max_burst_bytes = env.get_field(params, "maxBurstBytes", "J").ok()?.j().ok()?;
    let target_rate_bps = env.get_field(params, "targetRateBps", "J").ok()?.j().ok()?;
    let loss_aware_backoff = env.get_field(params, "lossAwareBackoff", "Z").ok()?.z().ok()?;
    let enable_pacing = env.get_field(params, "enablePacing", "Z").ok()?.z().ok()?;
    
    Some(PepperPacingParams {
        target_rate_bps: target_rate_bps as u64,
        max_burst_bytes: max_burst_bytes as u64,
        loss_aware_backoff,
        enable_pacing,
        min_pacing_interval_ns: 1000, // 1 microsecond default
    })
}

/// Attach shaper to a socket/file descriptor pair
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_chain_pepper_PepperShaper_nativeAttach(
    mut env: JNIEnv,
    _class: JClass,
    read_fd: jint,
    write_fd: jint,
    mode: jint,
    params: JObject,
) -> jlong {
    if !INITIALIZED.load(std::sync::atomic::Ordering::Acquire) {
        error!("Not initialized");
        return 0;
    }

    if read_fd < 0 || write_fd < 0 {
        error!("Invalid file descriptors: readFd={}, writeFd={}", read_fd, write_fd);
        return 0;
    }

    debug!("Attaching shaper: readFd={}, writeFd={}, mode={}", read_fd, write_fd, mode);

    let pacing_params = match extract_params(&mut env, &params) {
        Some(p) => p,
        None => {
            error!("Failed to extract parameters");
            return 0;
        }
    };

    let handle_id = NEXT_HANDLE_ID.fetch_add(1, std::sync::atomic::Ordering::AcqRel);
    
    // Create ring buffers (64KB each)
    const QUEUE_SIZE: usize = 64 * 1024;
    let tx_queue = Arc::new(PepperRingBuffer::new(QUEUE_SIZE));
    let rx_queue = Arc::new(PepperRingBuffer::new(QUEUE_SIZE));

    let pacing_state = PepperPacingState::new(&pacing_params);
    let pacing_state = Arc::new(Mutex::new(pacing_state));

    let handle = Arc::new(PepperShaperHandle {
        read_fd,
        write_fd,
        mode,
        active: Arc::new(std::sync::atomic::AtomicBool::new(true)),
        tx_queue,
        rx_queue,
        pacing_state,
        pacing_params: Arc::new(Mutex::new(pacing_params)),
    });

    let mut handles = get_handles().lock();
    handles.insert(handle_id, handle);

    debug!("Shaper attached: handle={}", handle_id);
    handle_id
}

/// Detach shaper
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_chain_pepper_PepperShaper_nativeDetach(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    if handle <= 0 {
        return jboolean::from(false);
    }

    debug!("Detaching shaper: handle={}", handle);

    let mut handles = get_handles().lock();
    if let Some(h) = handles.remove(&handle) {
        h.active.store(false, std::sync::atomic::Ordering::Release);
        debug!("Shaper detached: handle={}", handle);
        jboolean::from(true)
    } else {
        error!("Handle not found: {}", handle);
        jboolean::from(false)
    }
}

/// Update shaper parameters
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_chain_pepper_PepperShaper_nativeUpdateParams(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    params: JObject,
) -> jboolean {
    if handle <= 0 {
        return jboolean::from(false);
    }

    debug!("Updating params: handle={}", handle);

    let pacing_params = match extract_params(&mut env, &params) {
        Some(p) => p,
        None => {
            error!("Failed to extract parameters");
            return jboolean::from(false);
        }
    };

    let handles = get_handles().lock();
    if let Some(h) = handles.get(&handle) {
        *h.pacing_params.lock() = pacing_params.clone();
        *h.pacing_state.lock() = PepperPacingState::new(&pacing_params);
        debug!("Params updated: handle={}", handle);
        jboolean::from(true)
    } else {
        error!("Handle not found: {}", handle);
        jboolean::from(false)
    }
}

/// Shutdown PepperShaper
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_chain_pepper_PepperShaper_nativeShutdown(
    _env: JNIEnv,
    _class: JClass,
) {
    if !INITIALIZED.swap(false, std::sync::atomic::Ordering::AcqRel) {
        return;
    }

    info!("Shutting down PepperShaper");

    let mut handles = get_handles().lock();
    for (_, handle) in handles.iter() {
        handle.active.store(false, std::sync::atomic::Ordering::Release);
    }
    handles.clear();
    NEXT_HANDLE_ID.store(1, std::sync::atomic::Ordering::Release);

    info!("PepperShaper shutdown complete");
}

/// Cleanup on JNI unload
#[no_mangle]
pub extern "C" fn JNI_OnUnload(_vm: jni::JavaVM, _reserved: *mut std::ffi::c_void) {
    info!("PepperShaper JNI unloading - cleaning up handles");

    if let Some(handles) = HANDLES.get() {
        let mut handles = handles.lock();
        handles.clear();
    }
    NEXT_HANDLE_ID.store(1, std::sync::atomic::Ordering::Release);
    INITIALIZED.store(false, std::sync::atomic::Ordering::Release);

    info!("PepperShaper JNI unload complete");
}

