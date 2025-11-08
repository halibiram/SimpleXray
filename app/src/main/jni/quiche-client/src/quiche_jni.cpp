/*
 * QUICHE JNI Interface
 * Java/Kotlin bindings for QUICHE native client
 */

#include <jni.h>
#include <android/log.h>

#include "quiche_client.h"
#include "quiche_tun_forwarder.h"
#include "quiche_crypto.h"
#include "quiche_utils.h"

#define LOG_TAG "QuicheJNI"

using namespace quiche_client;

// Helper to convert Java string to C++ string
static std::string JStringToString(JNIEnv* env, jstring jstr) {
    if (!jstr) {
        return "";
    }

    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return result;
}

extern "C" {

/*
 * Create QUIC client
 *
 * Returns: client handle (pointer), or 0 on failure
 */
JNIEXPORT jlong JNICALL
Java_com_simplexray_an_quiche_QuicheClient_nativeCreate(
    JNIEnv* env,
    jclass /* clazz */,
    jstring server_host,
    jint server_port,
    jint congestion_control,
    jboolean enable_zero_copy,
    jint cpu_affinity) {

    QuicConfig config;
    config.server_host = JStringToString(env, server_host);
    config.server_port = server_port;
    config.cc_algorithm = static_cast<CongestionControl>(congestion_control);
    config.enable_zero_copy = enable_zero_copy;
    config.cpu_affinity = static_cast<CpuAffinity>(cpu_affinity);

    auto client = QuicheClient::Create(config);
    if (!client) {
        LOGE(LOG_TAG, "Failed to create QUIC client");
        return 0;
    }

    return reinterpret_cast<jlong>(client.release());
}

/*
 * Connect to server
 *
 * Returns: 0 on success, < 0 on error
 */
JNIEXPORT jint JNICALL
Java_com_simplexray_an_quiche_QuicheClient_nativeConnect(
    JNIEnv* /* env */,
    jclass /* clazz */,
    jlong client_handle) {

    auto* client = reinterpret_cast<QuicheClient*>(client_handle);
    if (!client) {
        return -1;
    }

    return client->Connect();
}

/*
 * Disconnect from server
 */
JNIEXPORT void JNICALL
Java_com_simplexray_an_quiche_QuicheClient_nativeDisconnect(
    JNIEnv* /* env */,
    jclass /* clazz */,
    jlong client_handle) {

    auto* client = reinterpret_cast<QuicheClient*>(client_handle);
    if (client) {
        client->Disconnect();
    }
}

/*
 * Destroy client
 */
JNIEXPORT void JNICALL
Java_com_simplexray_an_quiche_QuicheClient_nativeDestroy(
    JNIEnv* /* env */,
    jclass /* clazz */,
    jlong client_handle) {

    auto* client = reinterpret_cast<QuicheClient*>(client_handle);
    if (client) {
        delete client;
    }
}

/*
 * Check if connected
 */
JNIEXPORT jboolean JNICALL
Java_com_simplexray_an_quiche_QuicheClient_nativeIsConnected(
    JNIEnv* /* env */,
    jclass /* clazz */,
    jlong client_handle) {

    auto* client = reinterpret_cast<QuicheClient*>(client_handle);
    if (!client) {
        return JNI_FALSE;
    }

    return client->IsConnected() ? JNI_TRUE : JNI_FALSE;
}

/*
 * Send data
 *
 * Returns: bytes sent, or < 0 on error
 */
JNIEXPORT jint JNICALL
Java_com_simplexray_an_quiche_QuicheClient_nativeSend(
    JNIEnv* env,
    jclass /* clazz */,
    jlong client_handle,
    jbyteArray data) {

    auto* client = reinterpret_cast<QuicheClient*>(client_handle);
    if (!client) {
        return -1;
    }

    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    jsize len = env->GetArrayLength(data);

    ssize_t sent = client->Send(reinterpret_cast<const uint8_t*>(bytes), len);

    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);

    return sent;
}

/*
 * Get metrics
 *
 * Returns: metrics array [throughput_mbps, rtt_us, packet_loss_rate, ...]
 */
JNIEXPORT jdoubleArray JNICALL
Java_com_simplexray_an_quiche_QuicheClient_nativeGetMetrics(
    JNIEnv* env,
    jclass /* clazz */,
    jlong client_handle) {

    auto* client = reinterpret_cast<QuicheClient*>(client_handle);
    if (!client) {
        return nullptr;
    }

    QuicMetrics metrics = client->GetMetrics();

    jdoubleArray result = env->NewDoubleArray(8);
    jdouble values[8] = {
        metrics.throughput_mbps,
        static_cast<double>(metrics.rtt_us),
        metrics.packet_loss_rate,
        static_cast<double>(metrics.bytes_sent),
        static_cast<double>(metrics.bytes_received),
        static_cast<double>(metrics.packets_sent),
        static_cast<double>(metrics.packets_received),
        static_cast<double>(metrics.cwnd)
    };

    env->SetDoubleArrayRegion(result, 0, 8, values);
    return result;
}

/*
 * Create TUN forwarder
 *
 * Returns: forwarder handle (pointer), or 0 on failure
 */
JNIEXPORT jlong JNICALL
Java_com_simplexray_an_quiche_QuicheTunForwarder_nativeCreate(
    JNIEnv* /* env */,
    jclass /* clazz */,
    jint tun_fd,
    jlong client_handle,
    jint batch_size,
    jboolean use_gso,
    jboolean use_gro) {

    auto* client = reinterpret_cast<QuicheClient*>(client_handle);
    if (!client) {
        LOGE(LOG_TAG, "Invalid client handle");
        return 0;
    }

    ForwarderConfig config;
    config.tun_fd = tun_fd;
    config.batch_size = batch_size;
    config.use_gso = use_gso;
    config.use_gro = use_gro;
    config.cpu_affinity = CpuAffinity::BIG_CORES;

    auto forwarder = QuicheTunForwarder::Create(config, client);
    if (!forwarder) {
        LOGE(LOG_TAG, "Failed to create TUN forwarder");
        return 0;
    }

    return reinterpret_cast<jlong>(forwarder.release());
}

/*
 * Start TUN forwarder
 */
JNIEXPORT jint JNICALL
Java_com_simplexray_an_quiche_QuicheTunForwarder_nativeStart(
    JNIEnv* /* env */,
    jclass /* clazz */,
    jlong forwarder_handle) {

    auto* forwarder = reinterpret_cast<QuicheTunForwarder*>(forwarder_handle);
    if (!forwarder) {
        return -1;
    }

    return forwarder->Start();
}

/*
 * Stop TUN forwarder
 */
JNIEXPORT void JNICALL
Java_com_simplexray_an_quiche_QuicheTunForwarder_nativeStop(
    JNIEnv* /* env */,
    jclass /* clazz */,
    jlong forwarder_handle) {

    auto* forwarder = reinterpret_cast<QuicheTunForwarder*>(forwarder_handle);
    if (forwarder) {
        forwarder->Stop();
    }
}

/*
 * Destroy TUN forwarder
 */
JNIEXPORT void JNICALL
Java_com_simplexray_an_quiche_QuicheTunForwarder_nativeDestroy(
    JNIEnv* /* env */,
    jclass /* clazz */,
    jlong forwarder_handle) {

    auto* forwarder = reinterpret_cast<QuicheTunForwarder*>(forwarder_handle);
    if (forwarder) {
        delete forwarder;
    }
}

/*
 * Get forwarder statistics
 *
 * Returns: stats array [packets_rx, packets_tx, bytes_rx, bytes_tx, ...]
 */
JNIEXPORT jlongArray JNICALL
Java_com_simplexray_an_quiche_QuicheTunForwarder_nativeGetStats(
    JNIEnv* env,
    jclass /* clazz */,
    jlong forwarder_handle) {

    auto* forwarder = reinterpret_cast<QuicheTunForwarder*>(forwarder_handle);
    if (!forwarder) {
        return nullptr;
    }

    ForwarderStats stats = forwarder->GetStats();

    jlongArray result = env->NewLongArray(5);
    jlong values[5] = {
        static_cast<jlong>(stats.packets_received),
        static_cast<jlong>(stats.packets_sent),
        static_cast<jlong>(stats.packets_dropped),
        static_cast<jlong>(stats.bytes_received),
        static_cast<jlong>(stats.bytes_sent)
    };

    env->SetLongArrayRegion(result, 0, 5, values);
    return result;
}

/*
 * Get crypto capabilities
 *
 * Returns: capabilities array [has_aes, has_pmull, has_neon, has_sha]
 */
JNIEXPORT jbooleanArray JNICALL
Java_com_simplexray_an_quiche_QuicheCrypto_nativeGetCapabilities(
    JNIEnv* env,
    jclass /* clazz */) {

    CryptoCapabilities caps = QuicheCrypto::GetCapabilities();

    jbooleanArray result = env->NewBooleanArray(4);
    jboolean values[4] = {
        static_cast<jboolean>(caps.has_aes_hardware ? JNI_TRUE : JNI_FALSE),
        static_cast<jboolean>(caps.has_pmull_hardware ? JNI_TRUE : JNI_FALSE),
        static_cast<jboolean>(caps.has_neon ? JNI_TRUE : JNI_FALSE),
        static_cast<jboolean>(caps.has_sha_hardware ? JNI_TRUE : JNI_FALSE)
    };

    env->SetBooleanArrayRegion(result, 0, 4, values);
    return result;
}

/*
 * Print crypto capabilities (for debugging)
 */
JNIEXPORT void JNICALL
Java_com_simplexray_an_quiche_QuicheCrypto_nativePrintCapabilities(
    JNIEnv* /* env */,
    jclass /* clazz */) {

    CryptoPerf::PrintCapabilities();
}

} // extern "C"
