/*
 * Operator Throttling Evasion (Rust Implementation)
 * 
 * Features:
 * - Random padding frames
 * - Paced handshake timings
 * - Record size jitter
 * - Traffic pattern randomization
 */

use jni::JNIEnv;
use jni::objects::{JClass, JByteArray};
use jni::sys::jint;
use log::debug;
use rand::Rng;

static mut RNG: Option<rand::rngs::ThreadRng> = None;

fn get_rng() -> &'static mut rand::rngs::ThreadRng {
    unsafe {
        if RNG.is_none() {
            RNG = Some(rand::thread_rng());
        }
        RNG.as_mut().unwrap()
    }
}

/// Generate random padding bytes for TLS evasion
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeGeneratePadding(
    env: JNIEnv,
    _class: JClass,
    output: JByteArray,
) -> jint {
    let capacity = match env.get_array_length(&output) {
        Ok(len) => len as usize,
        Err(_) => return -1,
    };

    if capacity == 0 {
        return -1;
    }

    // Generate random padding length (up to capacity, max 255)
    let padding_len = std::cmp::min(capacity, 255);
    let padding_len = get_rng().gen_range(0..=padding_len);

    let mut bytes = vec![0u8; padding_len];
    get_rng().fill(&mut bytes[..]);

    // Convert Vec<u8> to &[i8] for JNI
    let bytes_i8: Vec<i8> = bytes.iter().map(|&b| b as i8).collect();
    if let Err(_) = env.set_byte_array_region(&output, 0, &bytes_i8) {
        return -1;
    }

    debug!("Generated {} bytes of padding", padding_len);
    padding_len as jint
}

/// Get handshake pacing delay (with jitter)
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeGetHandshakePacingDelay(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    // Generate jitter delay (0-50ms) for handshake pacing
    let delay = get_rng().gen_range(0..=50);
    delay
}

/// Apply record size jitter to TLS record
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeApplyRecordJitter(
    _env: JNIEnv,
    _class: JClass,
    base_size: jint,
) -> jint {
    if base_size <= 0 {
        return base_size;
    }

    // Jitter: ±10% of base size
    let jitter_range = base_size / 10;
    let jitter = get_rng().gen_range(-jitter_range..=jitter_range);
    let result = base_size + jitter;

    if result < 0 {
        base_size
    } else {
        result
    }
}

/// Generate ECH GREASE value for TLS evasion
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeGenerateECHGREASE(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    // Generate random GREASE value (0x1A1A, 0x2A2A, etc.)
    let grease_values = [0x1A1A, 0x2A2A, 0x3A3A, 0x4A4A, 0x5A5A, 0x6A6A, 0x7A7A, 0x8A8A];
    let idx = get_rng().gen_range(0..grease_values.len());
    grease_values[idx] as jint
}




