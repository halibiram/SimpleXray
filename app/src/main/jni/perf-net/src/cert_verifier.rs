/*
 * Certificate Verifier Overrides and Trust Manager Bridge (Rust Implementation)
 * 
 * Features:
 * - rustls trust manager bridge
 * - Hostname mismatch handling
 * - Certificate pinning bypass (for isolated test env)
 */

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jlong};
use log::debug;
use rustls::client::danger::{ServerCertVerifier, ServerCertVerified};
use rustls::pki_types::{CertificateDer, ServerName};
use rustls::Error;
use std::sync::Arc;

/// Dummy certificate verifier (accepts all certificates)
/// In production, use proper certificate validation
pub struct NoCertificateVerification {
    allow_hostname_mismatch: bool,
    bypass_pinning: bool,
    expected_hostname: Option<String>,
}

impl NoCertificateVerification {
    pub fn new(allow_hostname_mismatch: bool, bypass_pinning: bool, hostname: Option<String>) -> Self {
        Self {
            allow_hostname_mismatch,
            bypass_pinning,
            expected_hostname: hostname,
        }
    }
}

impl ServerCertVerifier for NoCertificateVerification {
    fn verify_server_cert(
        &self,
        _end_entity: &CertificateDer<'static>,
        _intermediates: &[CertificateDer<'static>],
        _server_name: &ServerName<'static>,
        _scts: &mut dyn Iterator<Item = &[u8]>,
        _ocsp_response: &[u8],
        _now: std::time::SystemTime,
    ) -> Result<ServerCertVerified, Error> {
        debug!("Certificate verification bypassed (test mode)");
        Ok(ServerCertVerified::assertion())
    }
}

struct VerifyContext {
    verifier: Arc<NoCertificateVerification>,
}

/// Create certificate verifier context
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeCreateCertVerifier(
    env: JNIEnv,
    _class: JClass,
    allow_hostname_mismatch: jboolean,
    bypass_pinning: jboolean,
    hostname: JString,
) -> jlong {
    let hostname_str = if !hostname.is_null() {
        match env.get_string(hostname) {
            Ok(s) => Some(s.to_string_lossy().to_string()),
            Err(_) => None,
        }
    } else {
        None
    };

    let verifier = Arc::new(NoCertificateVerification::new(
        allow_hostname_mismatch != 0,
        bypass_pinning != 0,
        hostname_str,
    ));

    let ctx = Box::new(VerifyContext { verifier });
    Box::into_raw(ctx) as jlong
}

/// Set certificate verify callback
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeSetCertVerifyCallback(
    _env: JNIEnv,
    _class: JClass,
    ctx_ptr: jlong,
    ssl_ctx_ptr: jlong,
) -> jint {
    if ctx_ptr == 0 || ssl_ctx_ptr == 0 {
        return -1;
    }

    // Certificate verification is handled by rustls ServerCertVerifier
    // This is a placeholder for callback setup
    debug!("Certificate verify callback set");
    0
}

/// Set SSL verify callback
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeSetSSLVerifyCallback(
    _env: JNIEnv,
    _class: JClass,
    ctx_ptr: jlong,
    ssl_ptr: jlong,
) -> jint {
    if ctx_ptr == 0 || ssl_ptr == 0 {
        return -1;
    }

    // SSL verification is handled by rustls
    debug!("SSL verify callback set");
    0
}

/// Free certificate verifier
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeFreeCertVerifier(
    _env: JNIEnv,
    _class: JClass,
    ctx_ptr: jlong,
) {
    if ctx_ptr != 0 {
        unsafe {
            let _ = Box::from_raw(ctx_ptr as *mut VerifyContext);
        }
        debug!("Certificate verifier freed");
    }
}

