/*
 * JNI Bridge for PepperShaper
 * Traffic shaping module with lock-free ring buffers
 */

#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <memory>
#include <unordered_map>
#include <mutex>
#include "pepper_queue.h"
#include "pepper_pacing.h"

#define LOG_TAG "PepperShaper"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Shaper handle with ring buffers and pacing
struct PepperShaperHandle {
    int readFd;
    int writeFd;
    int mode; // 0 = TCP, 1 = UDP
    std::atomic<bool> active;
    
    // Ring buffers for TX and RX
    PepperRingBuffer* txQueue;
    PepperRingBuffer* rxQueue;
    
    // Pacing state
    PepperPacingState pacingState;
    PepperPacingParams pacingParams;
    
    // Statistics
    PepperQueueStats stats;
    
    PepperShaperHandle(int rfd, int wfd, int m) 
        : readFd(rfd), writeFd(wfd), mode(m), active(true),
          txQueue(nullptr), rxQueue(nullptr) {
        memset(&pacingState, 0, sizeof(pacingState));
        memset(&pacingParams, 0, sizeof(pacingParams));
        memset(&stats, 0, sizeof(stats));
    }
    
    ~PepperShaperHandle() {
        if (txQueue) {
            pepper_queue_destroy(txQueue);
            txQueue = nullptr;
        }
        if (rxQueue) {
            pepper_queue_destroy(rxQueue);
            rxQueue = nullptr;
        }
    }
};

// Handle storage
// Properly cleaned up on JNI_OnUnload to prevent memory leaks
static std::mutex handleMutex;
static std::unordered_map<long, std::unique_ptr<PepperShaperHandle>> handles;
// nextHandleId uses atomic long - overflow is extremely unlikely (would require 2^63 handles)
static std::atomic<long> nextHandleId{1};
static std::atomic<bool> initialized{false};

// Helper to extract params from Java object
static void extractParams(JNIEnv* env, jobject params, PepperPacingParams* out) {
    if (!params || !out) {
        LOGE("extractParams: null params or out pointer");
        return;
    }
    
    // Check for exceptions and null return
    jclass paramsClass = env->GetObjectClass(params);
    if (!paramsClass || env->ExceptionCheck()) {
        LOGE("extractParams: failed to get object class");
        env->ExceptionClear();
        return;
    }
    
    // Get mode
    jfieldID modeField = env->GetFieldID(paramsClass, "mode", "Lcom/simplexray/an/chain/pepper/PepperShaper\$PepperMode;");
    if (modeField) {
        jobject modeObj = env->GetObjectField(params, modeField);
        // Mode is enum, we'll use default for now
    }
    
    // Get maxBurstBytes
    jfieldID burstField = env->GetFieldID(paramsClass, "maxBurstBytes", "J");
    if (burstField) {
        out->max_burst_bytes = env->GetLongField(params, burstField);
    }
    
    // Get targetRateBps
    jfieldID rateField = env->GetFieldID(paramsClass, "targetRateBps", "J");
    if (rateField) {
        out->target_rate_bps = env->GetLongField(params, rateField);
    }
    
    // Get lossAwareBackoff
    jfieldID backoffField = env->GetFieldID(paramsClass, "lossAwareBackoff", "Z");
    if (backoffField) {
        out->loss_aware_backoff = env->GetBooleanField(params, backoffField);
    }
    
    // Get enablePacing
    jfieldID pacingField = env->GetFieldID(paramsClass, "enablePacing", "Z");
    if (pacingField) {
        out->enable_pacing = env->GetBooleanField(params, pacingField);
    }
    
    // Set defaults
    if (out->min_pacing_interval_ns == 0) {
        out->min_pacing_interval_ns = 1000; // 1 microsecond default
    }
    
    env->DeleteLocalRef(paramsClass);
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_simplexray_an_chain_pepper_PepperShaper_nativeInit(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    if (initialized.exchange(true)) {
        LOGD("Already initialized");
        return;
    }
    LOGD("PepperShaper native initialized");
}

JNIEXPORT jlong JNICALL
Java_com_simplexray_an_chain_pepper_PepperShaper_nativeAttach(
    JNIEnv *env,
    jclass clazz,
    jint readFd,
    jint writeFd,
    jint mode,
    jobject params
) {
    (void)clazz;
    if (!initialized.load()) {
        LOGE("Not initialized");
        return 0;
    }
    
    if (readFd < 0 || writeFd < 0) {
        LOGE("Invalid file descriptors: readFd=%d, writeFd=%d", readFd, writeFd);
        return 0;
    }
    
    LOGD("Attaching shaper: readFd=%d, writeFd=%d, mode=%d", readFd, writeFd, mode);
    
    // Create handle
    long handleId = nextHandleId.fetch_add(1);
    auto handle = std::make_unique<PepperShaperHandle>(readFd, writeFd, mode);
    
    // Extract parameters
    extractParams(env, params, &handle->pacingParams);
    
    // Create ring buffers (64KB each)
    const size_t queueSize = 64 * 1024;
    handle->txQueue = pepper_queue_create(queueSize);
    handle->rxQueue = pepper_queue_create(queueSize);
    
    if (!handle->txQueue || !handle->rxQueue) {
        LOGE("Failed to create ring buffers");
        return 0;
    }
    
    // Initialize pacing
    pepper_pacing_init(&handle->pacingState, &handle->pacingParams);
    
    // Store handle
    std::lock_guard<std::mutex> lock(handleMutex);
    handles[handleId] = std::move(handle);
    
    LOGD("Shaper attached: handle=%ld", handleId);
    return handleId;
}

JNIEXPORT jboolean JNICALL
Java_com_simplexray_an_chain_pepper_PepperShaper_nativeDetach(
    JNIEnv *env,
    jclass clazz,
    jlong handle
) {
    (void)env; (void)clazz;
    if (handle <= 0) {
        return JNI_FALSE;
    }
    
    LOGD("Detaching shaper: handle=%ld", handle);
    
    std::lock_guard<std::mutex> lock(handleMutex);
    auto it = handles.find(handle);
    if (it != handles.end()) {
        it->second->active.store(false);
        handles.erase(it);
        LOGD("Shaper detached: handle=%ld", handle);
        return JNI_TRUE;
    }
    
    LOGE("Handle not found: %ld", handle);
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_simplexray_an_chain_pepper_PepperShaper_nativeUpdateParams(
    JNIEnv *env,
    jclass clazz,
    jlong handle,
    jobject params
) {
    (void)clazz;
    if (handle <= 0) {
        return JNI_FALSE;
    }
    
    LOGD("Updating params: handle=%ld", handle);
    
    std::lock_guard<std::mutex> lock(handleMutex);
    auto it = handles.find(handle);
    if (it == handles.end()) {
        LOGE("Handle not found: %ld", handle);
        return JNI_FALSE;
    }
    
    PepperPacingParams newParams;
    extractParams(env, params, &newParams);
    
    // Update params
    it->second->pacingParams = newParams;
    
    // Reinitialize pacing with new params
    pepper_pacing_init(&it->second->pacingState, &it->second->pacingParams);
    
    LOGD("Params updated: handle=%ld", handle);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_simplexray_an_chain_pepper_PepperShaper_nativeShutdown(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    if (!initialized.exchange(false)) {
        return;
    }
    
    LOGD("Shutting down PepperShaper");
    
    std::lock_guard<std::mutex> lock(handleMutex);
    // Clear all handles to prevent memory leaks
    handles.clear();
    nextHandleId.store(1);
    
    LOGD("PepperShaper shutdown complete");
}

// Cleanup on JNI unload to prevent memory leaks
JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved) {
    (void)reserved;
    LOGD("PepperShaper JNI unloading - cleaning up handles");
    
    std::lock_guard<std::mutex> lock(handleMutex);
    handles.clear();
    nextHandleId.store(1);
    initialized.store(false);
    
    LOGD("PepperShaper JNI unload complete");
}

} // extern "C"

