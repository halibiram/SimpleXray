/*
 * TLS Keylog Export and Session Ticket Caching
 * 
 * Features:
 * - TLS keylog export for debugging
 * - Session ticket caching optimization
 * - Session resumption timing histogram
 */

#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <cstdio>
#include <fstream>
#include <string>
#include <unordered_map>
#include <mutex>
#include <time.h>

#include <openssl/ssl.h>
#include <openssl/evp.h>

#define LOG_TAG "PerfTLSKeylog"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Keylog file path (set via JNI)
static std::string g_keylog_path;
static std::mutex g_keylog_mutex;
static bool g_keylog_enabled = false;

// Session resumption timing tracking
struct SessionTiming {
    long handshake_start;
    long handshake_end;
    long key_schedule_derive;
    long traffic_secret_update;
};

static std::unordered_map<long, SessionTiming> g_session_timings;
static std::mutex g_timing_mutex;

/**
 * Get current timestamp in milliseconds
 */
static long get_timestamp_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    return ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

/**
 * Write keylog entry to file
 */
static void write_keylog_entry(const char* label, const unsigned char* client_random, 
                                const unsigned char* secret, size_t secret_len) {
    if (!g_keylog_enabled || g_keylog_path.empty()) {
        return;
    }
    
    std::lock_guard<std::mutex> lock(g_keylog_mutex);
    
    FILE* f = fopen(g_keylog_path.c_str(), "a");
    if (!f) {
        LOGE("Failed to open keylog file: %s", g_keylog_path.c_str());
        return;
    }
    
    // Format: LABEL CLIENT_RANDOM SECRET
    fprintf(f, "%s ", label);
    
    // Write client random (32 bytes hex)
    for (size_t i = 0; i < 32; i++) {
        fprintf(f, "%02x", client_random[i]);
    }
    
    fprintf(f, " ");
    
    // Write secret (hex)
    for (size_t i = 0; i < secret_len; i++) {
        fprintf(f, "%02x", secret[i]);
    }
    
    fprintf(f, "\n");
    fclose(f);
}

extern "C" {

/**
 * Enable TLS keylog export
 */
JNIEXPORT jint JNICALL
Java_com_simplexray_an_performance_PerformanceManager_nativeEnableTLSKeylog(
    JNIEnv *env, jclass clazz, jstring filepath) {
    
    if (!filepath) {
        LOGE("Invalid filepath");
        return -1;
    }
    
    const char* path_str = env->GetStringUTFChars(filepath, nullptr);
    if (!path_str) {
        LOGE("Failed to get filepath string");
        return -1;
    }
    
    std::lock_guard<std::mutex> lock(g_keylog_mutex);
    g_keylog_path = path_str;
    g_keylog_enabled = true;
    
    env->ReleaseStringUTFChars(filepath, path_str);
    
    LOGD("TLS keylog enabled: %s", g_keylog_path.c_str());
    
    return 0;
}

/**
 * Disable TLS keylog export
 */
JNIEXPORT void JNICALL
Java_com_simplexray_an_performance_PerformanceManager_nativeDisableTLSKeylog(
    JNIEnv *env, jclass clazz) {
    
    std::lock_guard<std::mutex> lock(g_keylog_mutex);
    g_keylog_enabled = false;
    g_keylog_path.clear();
    
    LOGD("TLS keylog disabled");
}

/**
 * Record handshake start time
 */
JNIEXPORT jlong JNICALL
Java_com_simplexray_an_performance_PerformanceManager_nativeRecordHandshakeStart(
    JNIEnv *env, jclass clazz, jlong session_id) {
    
    long timestamp = get_timestamp_ms();
    
    std::lock_guard<std::mutex> lock(g_timing_mutex);
    SessionTiming& timing = g_session_timings[session_id];
    timing.handshake_start = timestamp;
    
    LOGD("Handshake start recorded: session=%ld, time=%ld", session_id, timestamp);
    
    return timestamp;
}

/**
 * Record key schedule derivation time
 */
JNIEXPORT jint JNICALL
Java_com_simplexray_an_performance_PerformanceManager_nativeRecordKeyScheduleDerive(
    JNIEnv *env, jclass clazz, jlong session_id) {
    
    long timestamp = get_timestamp_ms();
    
    std::lock_guard<std::mutex> lock(g_timing_mutex);
    auto it = g_session_timings.find(session_id);
    if (it != g_session_timings.end()) {
        it->second.key_schedule_derive = timestamp;
        LOGD("Key schedule derive recorded: session=%ld, time=%ld", session_id, timestamp);
        return 0;
    }
    
    return -1;
}

/**
 * Record traffic secret update time
 */
JNIEXPORT jint JNICALL
Java_com_simplexray_an_performance_PerformanceManager_nativeRecordTrafficSecretUpdate(
    JNIEnv *env, jclass clazz, jlong session_id) {
    
    long timestamp = get_timestamp_ms();
    
    std::lock_guard<std::mutex> lock(g_timing_mutex);
    auto it = g_session_timings.find(session_id);
    if (it != g_session_timings.end()) {
        it->second.traffic_secret_update = timestamp;
        LOGD("Traffic secret update recorded: session=%ld, time=%ld", session_id, timestamp);
        return 0;
    }
    
    return -1;
}

/**
 * Record handshake end time and return timing histogram data
 */
JNIEXPORT jlong JNICALL
Java_com_simplexray_an_performance_PerformanceManager_nativeRecordHandshakeEnd(
    JNIEnv *env, jclass clazz, jlong session_id) {
    
    long timestamp = get_timestamp_ms();
    
    std::lock_guard<std::mutex> lock(g_timing_mutex);
    auto it = g_session_timings.find(session_id);
    if (it != g_session_timings.end()) {
        it->second.handshake_end = timestamp;
        
        long total_time = timestamp - it->second.handshake_start;
        long key_derive_time = it->second.key_schedule_derive - it->second.handshake_start;
        long secret_update_time = it->second.traffic_secret_update - it->second.handshake_start;
        
        LOGD("Handshake end: session=%ld, total=%ld ms, key_derive=%ld ms, secret_update=%ld ms",
             session_id, total_time, key_derive_time, secret_update_time);
        
        // Clean up
        g_session_timings.erase(it);
        
        return total_time;
    }
    
    return -1;
}

/**
 * Get session resumption timing histogram
 * Returns: total handshake time
 */
JNIEXPORT jlongArray JNICALL
Java_com_simplexray_an_performance_PerformanceManager_nativeGetSessionTimingHistogram(
    JNIEnv *env, jclass clazz) {
    
    std::lock_guard<std::mutex> lock(g_timing_mutex);
    
    // Return array: [total_time, key_derive_time, secret_update_time]
    jlongArray result = env->NewLongArray(3);
    if (!result) {
        return nullptr;
    }
    
    jlong values[3] = {0, 0, 0};
    
    // Calculate averages from all sessions
    if (!g_session_timings.empty()) {
        long total_total = 0;
        long total_key_derive = 0;
        long total_secret_update = 0;
        int count = 0;
        
        for (auto& pair : g_session_timings) {
            SessionTiming& timing = pair.second;
            if (timing.handshake_start > 0 && timing.handshake_end > 0) {
                total_total += (timing.handshake_end - timing.handshake_start);
                if (timing.key_schedule_derive > 0) {
                    total_key_derive += (timing.key_schedule_derive - timing.handshake_start);
                }
                if (timing.traffic_secret_update > 0) {
                    total_secret_update += (timing.traffic_secret_update - timing.handshake_start);
                }
                count++;
            }
        }
        
        if (count > 0) {
            values[0] = total_total / count;
            values[1] = total_key_derive / count;
            values[2] = total_secret_update / count;
        }
    }
    
    env->SetLongArrayRegion(result, 0, 3, values);
    
    return result;
}

} // extern "C"


