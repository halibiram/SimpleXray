/*
 * Common definitions for Performance Module
 * Shared across multiple source files
 */

#ifndef PERF_COMMON_H
#define PERF_COMMON_H

#include <jni.h>

// Cached JNI class and method IDs (reduces JNI lookups)
struct alignas(64) JNICache {
    jclass byteBufferClass{nullptr};
    jmethodID allocateDirectMethod{nullptr};
    bool initialized{false};
};

// Global JNI cache (defined in perf_jni.cpp, accessed via extern from other modules)
extern JNICache g_jni_cache;

#endif // PERF_COMMON_H
