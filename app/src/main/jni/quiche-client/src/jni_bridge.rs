/*
 * JNI Bridge for QUICHE Client (Rust Implementation)
 * Java/Kotlin bindings for QUICHE native client
 */

use jni::JNIEnv;
use jni::objects::{JClass, JByteArray};
use jni::sys::{jboolean, jbooleanArray, jdoubleArray, jint, jlong, jlongArray};
use std::sync::Arc;
use parking_lot::Mutex;
use log::error;

use crate::client::{QuicheClient, QuicConfig, CongestionControl, CpuAffinity};
use crate::tun_forwarder::{QuicheTunForwarder, ForwarderConfig};
use crate::crypto::QuicheCrypto;

// Helper to convert Java string to Rust string
fn jstring_to_string(env: &JNIEnv, jstr: jni::sys::jstring) -> String {
    if jstr.is_null() {
        return String::new();
    }
    
    unsafe {
        let jstring = jni::objects::JString::from_raw(jstr);
        match env.get_string(&jstring) {
            Ok(s) => s.to_string_lossy().to_string(),
            Err(_) => String::new(),
        }
    }
}

/// Create QUIC client
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_quiche_QuicheClient_nativeCreate(
    env: JNIEnv,
    _class: JClass,
    server_host: jni::sys::jstring,
    server_port: jint,
    congestion_control: jint,
    enable_zero_copy: jboolean,
    cpu_affinity: jint,
) -> jlong {
    android_logger::init_once(
        android_logger::Config::default()
            .with_tag("QuicheJNI")
            .with_max_level(log::LevelFilter::Debug),
    );

    let host = jstring_to_string(&env, server_host);
    let cc = match congestion_control {
        0 => CongestionControl::Reno,
        1 => CongestionControl::Cubic,
        2 => CongestionControl::Bbr,
        3 => CongestionControl::Bbr2,
        _ => CongestionControl::Bbr2,
    };
    
    let cpu_aff = match cpu_affinity {
        0 => CpuAffinity::None,
        1 => CpuAffinity::BigCores,
        2 => CpuAffinity::LittleCores,
        _ => CpuAffinity::BigCores,
    };

    let config = QuicConfig {
        server_host: host,
        server_port: server_port as u16,
        cc_algorithm: cc,
        enable_zero_copy: enable_zero_copy != 0,
        cpu_affinity: cpu_aff,
        ..Default::default()
    };

    match QuicheClient::create(config) {
        Ok(client) => {
            let client = Arc::new(Mutex::new(client));
            Box::into_raw(Box::new(client)) as jlong
        }
        Err(e) => {
            error!("Failed to create QUIC client: {}", e);
            0
        }
    }
}

/// Connect to server
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_quiche_QuicheClient_nativeConnect(
    _env: JNIEnv,
    _class: JClass,
    client_handle: jlong,
) -> jint {
    if client_handle == 0 {
        return -1;
    }

    let client = unsafe { &*(client_handle as *const Arc<Mutex<QuicheClient>>) };
    let mut client = client.lock();
    
    match client.connect() {
        Ok(_) => 0,
        Err(e) => {
            error!("Connect failed: {}", e);
            -1
        }
    }
}

/// Disconnect from server
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_quiche_QuicheClient_nativeDisconnect(
    _env: JNIEnv,
    _class: JClass,
    client_handle: jlong,
) {
    if client_handle == 0 {
        return;
    }

    let client = unsafe { &*(client_handle as *const Arc<Mutex<QuicheClient>>) };
    let mut client = client.lock();
    client.disconnect();
}

/// Destroy client
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_quiche_QuicheClient_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
    client_handle: jlong,
) {
    if client_handle != 0 {
        unsafe {
            let _ = Box::from_raw(client_handle as *mut Arc<Mutex<QuicheClient>>);
        }
    }
}

/// Check if connected
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_quiche_QuicheClient_nativeIsConnected(
    _env: JNIEnv,
    _class: JClass,
    client_handle: jlong,
) -> jboolean {
    if client_handle == 0 {
        return jboolean::from(false);
    }

    let client = unsafe { &*(client_handle as *const Arc<Mutex<QuicheClient>>) };
    let client = client.lock();
    jboolean::from(client.is_connected())
}

/// Send data
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_quiche_QuicheClient_nativeSend(
    env: JNIEnv,
    _class: JClass,
    client_handle: jlong,
    data: JByteArray,
) -> jint {
    if client_handle == 0 {
        return -1;
    }

    let array_length = match env.get_array_length(&data) {
        Ok(len) => len,
        Err(_) => {
            error!("Failed to get array length");
            return -1;
        }
    };

    let src = match env.get_array_elements(&data, jni::objects::ReleaseMode::NoCopyBack) {
        Ok(elems) => elems,
        Err(_) => {
            error!("Failed to get byte array elements");
            return -1;
        }
    };

    let client = unsafe { &*(client_handle as *const Arc<Mutex<QuicheClient>>) };
    let mut client = client.lock();
    
    let data_slice = unsafe {
        std::slice::from_raw_parts(src.as_ptr(), array_length as usize)
    };

    let result = match client.send(data_slice) {
        Ok(bytes) => bytes as jint,
        Err(_) => -1,
    };

    drop(src);
    result
}

/// Get metrics
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_quiche_QuicheClient_nativeGetMetrics(
    env: JNIEnv,
    _class: JClass,
    client_handle: jlong,
) -> jdoubleArray {
    if client_handle == 0 {
        return std::ptr::null_mut();
    }

    let client = unsafe { &*(client_handle as *const Arc<Mutex<QuicheClient>>) };
    let client = client.lock();
    let metrics = client.get_metrics();

    let result = match env.new_double_array(8) {
        Ok(arr) => arr,
        Err(_) => return std::ptr::null_mut(),
    };

    let values = [
        metrics.throughput_mbps,
        metrics.rtt_us as f64,
        metrics.packet_loss_rate,
        metrics.bytes_sent as f64,
        metrics.bytes_received as f64,
        metrics.packets_sent as f64,
        metrics.packets_received as f64,
        metrics.cwnd as f64,
    ];

    if let Err(_) = env.set_double_array_region(&result, 0, &values) {
        return std::ptr::null_mut();
    }

    result.into_raw() as jni::sys::jdoubleArray
}

/// Create TUN forwarder
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_quiche_QuicheTunForwarder_nativeCreate(
    _env: JNIEnv,
    _class: JClass,
    tun_fd: jint,
    client_handle: jlong,
    batch_size: jint,
    use_gso: jboolean,
    use_gro: jboolean,
) -> jlong {
    if client_handle == 0 || tun_fd < 0 {
        error!("Invalid parameters");
        return 0;
    }

    let client = unsafe { &*(client_handle as *const Arc<Mutex<QuicheClient>>) };
    let client_clone = client.clone();

    let config = ForwarderConfig {
        tun_fd: tun_fd as std::os::unix::io::RawFd,
        batch_size: batch_size as usize,
        use_gso: use_gso != 0,
        use_gro: use_gro != 0,
        ..Default::default()
    };

    match QuicheTunForwarder::create(config, client_clone) {
        Ok(forwarder) => {
            let forwarder = Arc::new(Mutex::new(forwarder));
            Box::into_raw(Box::new(forwarder)) as jlong
        }
        Err(e) => {
            error!("Failed to create TUN forwarder: {}", e);
            0
        }
    }
}

/// Start TUN forwarder
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_quiche_QuicheTunForwarder_nativeStart(
    _env: JNIEnv,
    _class: JClass,
    forwarder_handle: jlong,
) -> jint {
    if forwarder_handle == 0 {
        return -1;
    }

    let forwarder = unsafe { &*(forwarder_handle as *const Arc<Mutex<QuicheTunForwarder>>) };
    let mut forwarder = forwarder.lock();
    
    match forwarder.start() {
        Ok(_) => 0,
        Err(e) => {
            error!("Failed to start forwarder: {}", e);
            -1
        }
    }
}

/// Stop TUN forwarder
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_quiche_QuicheTunForwarder_nativeStop(
    _env: JNIEnv,
    _class: JClass,
    forwarder_handle: jlong,
) {
    if forwarder_handle == 0 {
        return;
    }

    let forwarder = unsafe { &*(forwarder_handle as *const Arc<Mutex<QuicheTunForwarder>>) };
    let mut forwarder = forwarder.lock();
    forwarder.stop();
}

/// Destroy TUN forwarder
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_quiche_QuicheTunForwarder_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
    forwarder_handle: jlong,
) {
    if forwarder_handle != 0 {
        unsafe {
            let _ = Box::from_raw(forwarder_handle as *mut Arc<Mutex<QuicheTunForwarder>>);
        }
    }
}

/// Get forwarder statistics
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_quiche_QuicheTunForwarder_nativeGetStats(
    env: JNIEnv,
    _class: JClass,
    forwarder_handle: jlong,
) -> jlongArray {
    if forwarder_handle == 0 {
        return std::ptr::null_mut();
    }

    let forwarder = unsafe { &*(forwarder_handle as *const Arc<Mutex<QuicheTunForwarder>>) };
    let forwarder = forwarder.lock();
    let stats = forwarder.get_stats();

    let result = match env.new_long_array(5) {
        Ok(arr) => arr,
        Err(_) => return std::ptr::null_mut(),
    };

    let values = [
        stats.packets_received as jlong,
        stats.packets_sent as jlong,
        stats.packets_dropped as jlong,
        stats.bytes_received as jlong,
        stats.bytes_sent as jlong,
    ];

    if let Err(_) = env.set_long_array_region(&result, 0, &values) {
        return std::ptr::null_mut();
    }

    result.into_raw() as jni::sys::jlongArray
}

/// Get crypto capabilities
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_quiche_QuicheCrypto_nativeGetCapabilities(
    env: JNIEnv,
    _class: JClass,
) -> jbooleanArray {
    let caps = QuicheCrypto::get_capabilities();

    let result = match env.new_boolean_array(4) {
        Ok(arr) => arr,
        Err(_) => return std::ptr::null_mut(),
    };

    let values = [
        jboolean::from(caps.has_aes_hardware),
        jboolean::from(caps.has_pmull_hardware),
        jboolean::from(caps.has_neon),
        jboolean::from(caps.has_sha_hardware),
    ];

    if let Err(_) = env.set_boolean_array_region(&result, 0, &values) {
        return std::ptr::null_mut();
    }

    result.into_raw() as jni::sys::jbooleanArray
}

/// Print crypto capabilities
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_quiche_QuicheCrypto_nativePrintCapabilities(
    _env: JNIEnv,
    _class: JClass,
) {
    QuicheCrypto::print_capabilities();
}




