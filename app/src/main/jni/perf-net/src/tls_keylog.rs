/*
 * TLS Keylog Export and Session Ticket Caching (Rust Implementation)
 * 
 * Features:
 * - TLS keylog export for debugging
 * - Session ticket caching optimization
 * - Session resumption timing histogram
 */

use jni::JNIEnv;
use jni::objects::{JClass, JString, JLongArray, JByteArray};
use jni::sys::{jint, jlong, jlongArray};
use log::{debug, error};
use parking_lot::Mutex;
use std::fs::OpenOptions;
use std::io::Write;
use std::time::{SystemTime, UNIX_EPOCH};
use hashbrown::HashMap;

struct SessionTiming {
    handshake_start: u64,
    handshake_end: u64,
    key_schedule_derive: u64,
    traffic_secret_update: u64,
}

static KEYLOG_PATH: Mutex<Option<String>> = Mutex::new(None);
static KEYLOG_ENABLED: Mutex<bool> = Mutex::new(false);
static SESSION_TIMINGS: LazyLock<Mutex<HashMap<u64, SessionTiming>>> = LazyLock::new(|| Mutex::new(HashMap::new()));

fn get_timestamp_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_millis() as u64
}

fn write_keylog_entry(label: &str, client_random: &[u8], secret: &[u8]) {
    let enabled = *KEYLOG_ENABLED.lock();
    if !enabled {
        return;
    }

    let path = KEYLOG_PATH.lock();
    let path = match path.as_ref() {
        Some(p) => p,
        None => return,
    };

    let mut file = match OpenOptions::new().create(true).append(true).open(path) {
        Ok(f) => f,
        Err(e) => {
            error!("Failed to open keylog file: {}", e);
            return;
        }
    };

    // Format: LABEL CLIENT_RANDOM SECRET
    if let Err(e) = write!(file, "{} ", label) {
        error!("Failed to write keylog label: {}", e);
        return;
    }

    // Write client random (32 bytes hex)
    for byte in client_random.iter().take(32) {
        if let Err(e) = write!(file, "{:02x}", byte) {
            error!("Failed to write client random: {}", e);
            return;
        }
    }

    if let Err(e) = write!(file, " ") {
        error!("Failed to write separator: {}", e);
        return;
    }

    // Write secret (hex)
    for byte in secret {
        if let Err(e) = write!(file, "{:02x}", byte) {
            error!("Failed to write secret: {}", e);
            return;
        }
    }

    if let Err(e) = writeln!(file) {
        error!("Failed to write newline: {}", e);
    }
}

/// Enable TLS keylog export
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeEnableTLSKeylog(
    env: JNIEnv,
    _class: JClass,
    filepath: JString,
) -> jint {
    let path = match env.get_string(&filepath) {
        Ok(s) => s.to_string_lossy().to_string(),
        Err(_) => {
            error!("Invalid filepath");
            return -1;
        }
    };

    *KEYLOG_PATH.lock() = Some(path.clone());
    *KEYLOG_ENABLED.lock() = true;

    debug!("TLS keylog enabled: {}", path);
    0
}

/// Disable TLS keylog export
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeDisableTLSKeylog(
    _env: JNIEnv,
    _class: JClass,
) {
    *KEYLOG_ENABLED.lock() = false;
    debug!("TLS keylog disabled");
}

/// Record handshake start
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeRecordHandshakeStart(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    let session_id = get_timestamp_ms();
    let mut timings = SESSION_TIMINGS.lock();
    timings.insert(session_id, SessionTiming {
        handshake_start: session_id,
        handshake_end: 0,
        key_schedule_derive: 0,
        traffic_secret_update: 0,
    });
    session_id as jlong
}

/// Record key schedule derive
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeRecordKeyScheduleDerive(
    env: JNIEnv,
    _class: JClass,
    session_id: jlong,
    client_random: JByteArray,
    secret: JByteArray,
) -> jint {
    let mut timings = SESSION_TIMINGS.lock();
    if let Some(timing) = timings.get_mut(&(session_id as u64)) {
        timing.key_schedule_derive = get_timestamp_ms();
    }

    let client_random_len = match env.get_array_length(&client_random) {
        Ok(len) => len as usize,
        Err(_) => return -1,
    };
    let mut client_random_bytes = vec![0i8; client_random_len];
    if let Err(_) = env.get_byte_array_region(&client_random, 0, &mut client_random_bytes) {
        return -1;
    }

    let secret_len = match env.get_array_length(&secret) {
        Ok(len) => len as usize,
        Err(_) => return -1,
    };
    let mut secret_bytes = vec![0i8; secret_len];
    if let Err(_) = env.get_byte_array_region(&secret, 0, &mut secret_bytes) {
        return -1;
    }

    // Convert i8 to u8 for keylog
    let client_random_u8: Vec<u8> = client_random_bytes.iter().map(|&b| b as u8).collect();
    let secret_u8: Vec<u8> = secret_bytes.iter().map(|&b| b as u8).collect();
    write_keylog_entry("CLIENT_HANDSHAKE_TRAFFIC_SECRET", &client_random_u8, &secret_u8);
    0
}

/// Record traffic secret update
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeRecordTrafficSecretUpdate(
    env: JNIEnv,
    _class: JClass,
    session_id: jlong,
    client_random: JByteArray,
    secret: JByteArray,
) -> jint {
    let mut timings = SESSION_TIMINGS.lock();
    if let Some(timing) = timings.get_mut(&(session_id as u64)) {
        timing.traffic_secret_update = get_timestamp_ms();
    }

    let client_random_len = match env.get_array_length(&client_random) {
        Ok(len) => len as usize,
        Err(_) => return -1,
    };
    let mut client_random_bytes = vec![0i8; client_random_len];
    if let Err(_) = env.get_byte_array_region(&client_random, 0, &mut client_random_bytes) {
        return -1;
    }

    let secret_len = match env.get_array_length(&secret) {
        Ok(len) => len as usize,
        Err(_) => return -1,
    };
    let mut secret_bytes = vec![0i8; secret_len];
    if let Err(_) = env.get_byte_array_region(&secret, 0, &mut secret_bytes) {
        return -1;
    }

    // Convert i8 to u8 for keylog
    let client_random_u8: Vec<u8> = client_random_bytes.iter().map(|&b| b as u8).collect();
    let secret_u8: Vec<u8> = secret_bytes.iter().map(|&b| b as u8).collect();
    write_keylog_entry("CLIENT_TRAFFIC_SECRET_0", &client_random_u8, &secret_u8);
    0
}

/// Record handshake end
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeRecordHandshakeEnd(
    _env: JNIEnv,
    _class: JClass,
    session_id: jlong,
) -> jlong {
    let mut timings = SESSION_TIMINGS.lock();
    if let Some(timing) = timings.get_mut(&(session_id as u64)) {
        timing.handshake_end = get_timestamp_ms();
        return (timing.handshake_end - timing.handshake_start) as jlong;
    }
    0
}

/// Get session timing histogram
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeGetSessionTimingHistogram(
    env: JNIEnv,
    _class: JClass,
) -> jlongArray {
    let timings = SESSION_TIMINGS.lock();
    let mut histogram = vec![0u64; 10]; // 10 buckets

    for timing in timings.values() {
        let duration = timing.handshake_end - timing.handshake_start;
        let bucket = std::cmp::min((duration / 100) as usize, histogram.len() - 1);
        histogram[bucket] += 1;
    }

    match env.new_long_array(histogram.len() as i32) {
        Ok(result) => {
            let values: Vec<jlong> = histogram.iter().map(|&v| v as jlong).collect();
            if let Err(_) = env.set_long_array_region(result, 0, &values) {
                return std::ptr::null_mut();
            }
            result.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

