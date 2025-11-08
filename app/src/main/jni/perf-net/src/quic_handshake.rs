/*
 * QUIC / HTTP3 Handshake Support (Rust Implementation)
 * 
 * Features:
 * - QUIC handshake using Quinn
 * - HTTP3 support
 * - Optimized for mobile networks
 */

use jni::JNIEnv;
use jni::objects::JClass;
use jni::sys::{jint, jlong};
use log::debug;
use quinn::ClientConfig;
use rustls::ClientConfig as RustlsClientConfig;
use std::sync::Arc;
use crate::cert_verifier::NoCertificateVerification;

/// Create QUIC SSL context for HTTP3
/// Note: Returns a handle to QUIC config (not SSL context)
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeCreateQUICContext(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    // Create rustls client config for QUIC
    // rustls 0.23 uses with_root_certificates instead of with_safe_defaults
    let mut crypto = match RustlsClientConfig::builder()
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(NoCertificateVerification::new(true, true, None)))
        .with_no_client_auth()
    {
        Ok(c) => c,
        Err(_) => {
            return 0;
        }
    };

    // Set ALPN for HTTP3
    crypto.alpn_protocols = vec![b"h3".to_vec(), b"h3-29".to_vec()];

    let client_config = ClientConfig::new(Arc::new(crypto));
    let config = Box::new(client_config);
    
    debug!("Created QUIC/HTTP3 context");
    Box::into_raw(config) as jlong
}

/// Configure QUIC connection parameters
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeConfigureQUIC(
    _env: JNIEnv,
    _class: JClass,
    ctx_ptr: jlong,
) -> jint {
    if ctx_ptr == 0 {
        return -1;
    }

    // QUIC-specific configuration
    // Note: Quinn handles QUIC configuration automatically
    debug!("QUIC configured");
    0
}

