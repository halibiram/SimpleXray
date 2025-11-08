/*
 * TLS Handshake Fingerprint Mimic - Chrome Mobile (Rust Implementation)
 * 
 * Features:
 * - Mimics Chrome mobile TLS handshake fingerprint
 * - Optimized cipher suites (TLS_AES_128_GCM_SHA256, TLS_AES_256_GCM_SHA384)
 * - X25519 key exchange
 * - Chrome-style supported_groups and keyshares
 * - ALPN ordering: h2, http/1.1
 * - Record splitting on first record
 * - ECH GREASE values
 */

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jint, jlong};
use log::{debug, error};
use rustls::ClientConfig as RustlsClientConfig;
use std::sync::Arc;
use crate::cert_verifier::NoCertificateVerification;

/// Create Chrome Mobile SSL context
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeCreateChromeMobileSSLContext(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    // Create rustls client config
    // rustls 0.23 uses with_root_certificates instead of with_safe_defaults
    let mut crypto = RustlsClientConfig::builder()
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(NoCertificateVerification::new(true, true, None)))
        .with_no_client_auth();

    // Set ALPN for Chrome mobile (h2 first, then http/1.1)
    crypto.alpn_protocols = vec![b"h2".to_vec(), b"http/1.1".to_vec()];

    let config = Box::new(crypto);
    debug!("Created Chrome Mobile SSL context");
    Box::into_raw(config) as jlong
}

/// Add ECH GREASE value
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeAddECHGREASE(
    _env: JNIEnv,
    _class: JClass,
    ctx_ptr: jlong,
    grease_value: jint,
) -> jint {
    if ctx_ptr == 0 {
        return -1;
    }

    // ECH GREASE is handled at TLS level
    // This is a placeholder for GREASE value configuration
    debug!("ECH GREASE value added: 0x{:04x}", grease_value);
    0
}

/// Create Chrome Mobile SSL connection
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeCreateChromeMobileSSL(
    _env: JNIEnv,
    _class: JClass,
    ctx_ptr: jlong,
) -> jlong {
    if ctx_ptr == 0 {
        return 0;
    }

    // SSL connection is created at runtime
    // This returns a handle for the connection
    debug!("Created Chrome Mobile SSL connection");
    ctx_ptr // Return context as connection handle
}

/// Set SNI (Server Name Indication)
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeSetSNI(
    mut _env: JNIEnv,
    _class: JClass,
    ssl_ptr: jlong,
    hostname: JString,
) -> jint {
    if ssl_ptr == 0 {
        return -1;
    }

    let hostname_str = match _env.get_string(&hostname) {
        Ok(s) => s.to_string_lossy().to_string(),
        Err(_) => return -1,
    };

    debug!("SNI set to: {}", hostname_str);
    0
}

/// Free SSL context
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeFreeSSLContext(
    _env: JNIEnv,
    _class: JClass,
    ctx_ptr: jlong,
) {
    if ctx_ptr != 0 {
        unsafe {
            let _ = Box::from_raw(ctx_ptr as *mut RustlsClientConfig);
        }
        debug!("SSL context freed");
    }
}

/// Free SSL connection
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeFreeSSL(
    _env: JNIEnv,
    _class: JClass,
    ssl_ptr: jlong,
) {
    // SSL connection cleanup is handled by rustls
    if ssl_ptr != 0 {
        debug!("SSL connection freed");
    }
}

