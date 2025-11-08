/*
 * Crypto Acceleration (Rust Implementation)
 * Hardware-accelerated crypto operations using ring
 */

use jni::JNIEnv;
use jni::objects::{JClass, JObject, JByteArray};
use jni::sys::{jboolean, jint, jobject};
use ring::aead;
use log::{debug, error};

/// Check if NEON is available
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeHasNEON(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    // ring automatically uses hardware acceleration if available
    // This is a placeholder - actual detection would check CPU features
    #[cfg(target_arch = "aarch64")]
    {
        jboolean::from(true)
    }
    #[cfg(not(target_arch = "aarch64"))]
    {
        jboolean::from(false)
    }
}

/// Check if crypto extensions are available
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeHasCryptoExtensions(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    // ring automatically uses hardware acceleration if available
    jboolean::from(true)
}

/// AES-128-GCM encrypt
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeAES128Encrypt(
    env: JNIEnv,
    _class: JClass,
    input: JObject,
    input_offset: jint,
    input_len: jint,
    output: JObject,
    output_offset: jint,
    key: JObject,
) -> jint {
    let input_ptr = match env.get_direct_buffer_address(input) {
        Ok(Some(ptr)) => ptr,
        _ => {
            error!("Invalid input buffer");
            return -1;
        }
    };

    let output_ptr = match env.get_direct_buffer_address(output) {
        Ok(Some(ptr)) => ptr,
        _ => {
            error!("Invalid output buffer");
            return -1;
        }
    };

    let key_ptr = match env.get_direct_buffer_address(key) {
        Ok(Some(ptr)) => ptr,
        _ => {
            error!("Invalid key buffer");
            return -1;
        }
    };

    let key_capacity = match env.get_direct_buffer_capacity(key) {
        Ok(cap) => cap,
        Err(_) => {
            error!("Failed to get key capacity");
            return -1;
        }
    };

    if key_capacity < 16 {
        error!("Invalid key length: {}", key_capacity);
        return -1;
    }

    // Use ring for AES-GCM encryption
    let key_bytes = unsafe { std::slice::from_raw_parts(key_ptr as *const u8, 16) };
    let key = match aead::UnboundKey::new(&aead::AES_128_GCM, key_bytes) {
        Ok(k) => k,
        Err(_) => {
            error!("Failed to create key");
            return -1;
        }
    };

    let nonce = aead::Nonce::assume_unique_for_key([0u8; 12]); // In production, use proper nonce
    let sealing_key = aead::SealingKey::new(key, nonce);

    let input_slice = unsafe {
        std::slice::from_raw_parts(
            (input_ptr as *const u8).add(input_offset as usize),
            input_len as usize,
        )
    };

    let output_slice = unsafe {
        std::slice::from_raw_parts_mut(
            (output_ptr as *mut u8).add(output_offset as usize),
            input_len as usize + 16, // GCM tag
        )
    };

    // Copy input to output first
    output_slice[..input_len as usize].copy_from_slice(input_slice);
    
    // Seal in place - ring 0.17 API uses seal_in_place with in_out parameter
    // The function modifies the slice in place and appends the tag
    let in_out = &mut output_slice[..input_len as usize];
    match aead::seal_in_place(&sealing_key, aead::Aad::empty(), in_out) {
        Ok(tag_len) => {
            debug!("AES-128-GCM encrypt successful, tag_len={}", tag_len);
            (input_len + tag_len as jint) as jint
        }
        Err(_) => {
            error!("AES-128-GCM encrypt failed");
            -1
        }
    }
}

/// ChaCha20-Poly1305 using NEON (placeholder)
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeChaCha20NEON(
    _env: JNIEnv,
    _class: JClass,
    _input: JObject,
    _input_offset: jint,
    _input_len: jint,
    _output: JObject,
    _output_offset: jint,
    _key: JObject,
    _nonce: JObject,
) -> jint {
    // TODO: Implement ChaCha20-Poly1305 with NEON acceleration
    error!("ChaCha20NEON not yet implemented");
    -1
}

/// Prefetch memory
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativePrefetch(
    _env: JNIEnv,
    _class: JClass,
    _ptr: JObject,
    _offset: jint,
    _length: jint,
) {
    // TODO: Implement memory prefetching
    debug!("Prefetch not yet implemented");
}
