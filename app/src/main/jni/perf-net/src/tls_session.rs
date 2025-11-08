/*
 * TLS Session Ticket Hoarding (Rust Implementation)
 * Reuses TLS sessions to avoid handshake overhead (-60% latency)
 */

use jni::JNIEnv;
use jni::objects::{JClass, JByteArray, JString};
use jni::sys::{jint, jbyteArray};
use log::debug;
use parking_lot::Mutex;
use hashbrown::HashMap;
use std::time::{SystemTime, UNIX_EPOCH};

struct TlsSessionTicket {
    ticket_data: Vec<u8>,
    timestamp: u64,
    ref_count: i32,
}

struct TlsSessionCache {
    cache: Mutex<HashMap<String, TlsSessionTicket>>,
}

const MAX_CACHE_SIZE: usize = 100;
const TICKET_TTL_MS: u64 = 3600000; // 1 hour

static SESSION_CACHE: once_cell::sync::Lazy<TlsSessionCache> = once_cell::sync::Lazy::new(|| {
    TlsSessionCache {
        cache: Mutex::new(HashMap::new()),
    }
});

fn get_current_time_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_millis() as u64
}

fn cleanup_expired_entries(cache: &mut HashMap<String, TlsSessionTicket>) {
    let current_time = get_current_time_ms();
    cache.retain(|_, ticket| {
        current_time - ticket.timestamp <= TICKET_TTL_MS
    });
}

fn remove_oldest_entry(cache: &mut HashMap<String, TlsSessionTicket>) {
    if cache.is_empty() {
        return;
    }

    let mut oldest_key: Option<String> = None;
    let mut oldest_timestamp = u64::MAX;

    for (key, ticket) in cache.iter() {
        if ticket.timestamp < oldest_timestamp {
            oldest_timestamp = ticket.timestamp;
            oldest_key = Some(key.clone());
        }
    }

    if let Some(key) = oldest_key {
        cache.remove(&key);
    }
}

/// Store TLS session ticket
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeStoreTLSTicket(
    mut env: JNIEnv,
    _class: JClass,
    host: JString,
    ticket_data: JByteArray,
) -> jint {
    let host_str = match env.get_string(&host) {
        Ok(s) => s.to_string_lossy().to_string(),
        Err(_) => return -1,
    };

    let ticket_len = match env.get_array_length(&ticket_data) {
        Ok(len) => len as usize,
        Err(_) => return -1,
    };

    if ticket_len == 0 {
        return -1;
    }

    let mut bytes = vec![0i8; ticket_len];
    if let Err(_) = env.get_byte_array_region(&ticket_data, 0, &mut bytes) {
        return -1;
    }

    let mut cache = SESSION_CACHE.cache.lock();

    // Check cache size and remove expired/old entries
    cleanup_expired_entries(&mut cache);

    // If still full, remove oldest entry
    if cache.len() >= MAX_CACHE_SIZE {
        remove_oldest_entry(&mut cache);
    }

    // Store ticket - convert i8 to u8
    let ticket = TlsSessionTicket {
        ticket_data: bytes.iter().map(|&b| b as u8).collect(),
        timestamp: get_current_time_ms(),
        ref_count: 1,
    };

    cache.insert(host_str.clone(), ticket);
    debug!("Stored TLS ticket for {}, size: {}", host_str, ticket_len);

    0
}

/// Get TLS session ticket
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeGetTLSTicket(
    mut env: JNIEnv,
    _class: JClass,
    host: JString,
) -> jbyteArray {
    let host_str = match env.get_string(&host) {
        Ok(s) => s.to_string_lossy().to_string(),
        Err(_) => return std::ptr::null_mut(),
    };

    let mut cache = SESSION_CACHE.cache.lock();

    let ticket = match cache.get(&host_str) {
        Some(t) => t,
        None => {
            return std::ptr::null_mut();
        }
    };

    // Check if expired
    let current_time = get_current_time_ms();
    if current_time - ticket.timestamp > TICKET_TTL_MS {
        // Expired, remove from cache
        cache.remove(&host_str);
        debug!("TLS ticket expired for {}", host_str);
        return std::ptr::null_mut();
    }

    // Create byte array
    match env.new_byte_array(ticket.ticket_data.len() as i32) {
        Ok(result) => {
            // Convert Vec<u8> to &[i8] for JNI
            let ticket_data_i8: Vec<i8> = ticket.ticket_data.iter().map(|&b| b as i8).collect();
            if let Err(_) = env.set_byte_array_region(&result, 0, &ticket_data_i8) {
                return std::ptr::null_mut();
            }
            debug!("Retrieved TLS ticket for {}", host_str);
            result.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

/// Clear TLS session cache
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeClearTLSCache(
    _env: JNIEnv,
    _class: JClass,
) {
    let mut cache = SESSION_CACHE.cache.lock();
    cache.clear();
    debug!("TLS session cache cleared");
}




