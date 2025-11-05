/*
 * Operator Throttling Evasion
 * 
 * Features:
 * - Random padding frames
 * - Paced handshake timings
 * - Record size jitter
 * - Traffic pattern randomization
 */

#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <cstdlib>
#include <ctime>
#include <random>

#define LOG_TAG "PerfTLSEvasion"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Random number generator for jitter
// Use time-based seed for initialization
static unsigned int get_seed() {
    return static_cast<unsigned int>(std::time(nullptr));
}
static std::mt19937 g_rng(get_seed());

/**
 * Generate random padding length (0-255 bytes)
 */
static int generate_padding_length() {
    std::uniform_int_distribution<int> dist(0, 255);
    return dist(g_rng);
}

/**
 * Generate jitter delay (0-50ms) for handshake pacing
 */
static int generate_jitter_delay() {
    std::uniform_int_distribution<int> dist(0, 50);
    return dist(g_rng);
}

/**
 * Generate record size jitter (variation in TLS record sizes)
 */
static int generate_record_jitter(int base_size) {
    // Jitter: ±10% of base size
    int jitter_range = base_size / 10;
    std::uniform_int_distribution<int> dist(-jitter_range, jitter_range);
    return base_size + dist(g_rng);
}

extern "C" {

/**
 * Generate random padding bytes for TLS evasion
 */
JNIEXPORT jint JNICALL
Java_com_simplexray_an_performance_PerformanceManager_nativeGeneratePadding(
    JNIEnv *env, jclass clazz, jbyteArray output) {
    
    if (!output) {
        LOGE("Invalid output array");
        return -1;
    }
    
    jsize capacity = env->GetArrayLength(output);
    if (capacity <= 0) {
        LOGE("Invalid capacity: %d", capacity);
        return -1;
    }
    
    // Generate random padding length (up to capacity)
    int padding_len = generate_padding_length();
    if (padding_len > capacity) {
        padding_len = capacity;
    }
    
    jbyte* bytes = env->GetByteArrayElements(output, nullptr);
    if (!bytes) {
        LOGE("Failed to get array elements");
        return -1;
    }
    
    // Fill with random bytes
    for (int i = 0; i < padding_len; i++) {
        bytes[i] = static_cast<jbyte>(g_rng() & 0xFF);
    }
    
    env->ReleaseByteArrayElements(output, bytes, 0);
    
    LOGD("Generated %d bytes of padding", padding_len);
    
    return padding_len;
}

/**
 * Get handshake pacing delay (with jitter)
 */
JNIEXPORT jint JNICALL
Java_com_simplexray_an_performance_PerformanceManager_nativeGetHandshakePacingDelay(
    JNIEnv *env, jclass clazz) {
    
    int delay = generate_jitter_delay();
    LOGD("Handshake pacing delay: %d ms", delay);
    return delay;
}

/**
 * Apply record size jitter to base size
 */
JNIEXPORT jint JNICALL
Java_com_simplexray_an_performance_PerformanceManager_nativeApplyRecordJitter(
    JNIEnv *env, jclass clazz, jint base_size) {
    
    if (base_size <= 0) {
        LOGE("Invalid base size: %d", base_size);
        return base_size;
    }
    
    int jittered_size = generate_record_jitter(base_size);
    
    // Ensure minimum size
    if (jittered_size < 64) {
        jittered_size = 64;
    }
    
    LOGD("Record size jitter: %d -> %d", base_size, jittered_size);
    
    return jittered_size;
}

/**
 * Generate ECH GREASE value (random value for ECH extension)
 */
JNIEXPORT jint JNICALL
Java_com_simplexray_an_performance_PerformanceManager_nativeGenerateECHGREASE(
    JNIEnv *env, jclass clazz) {
    
    // ECH GREASE values are typically 16-bit random values
    // that are multiples of 0x1a1a
    std::uniform_int_distribution<int> dist(0, 0xFFFF / 0x1a1a);
    int grease_value = dist(g_rng) * 0x1a1a;
    
    LOGD("Generated ECH GREASE value: 0x%04x", grease_value);
    
    return grease_value;
}

} // extern "C"

