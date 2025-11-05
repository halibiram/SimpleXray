/**
 * BoringSSL/OpenSSL Crypto Integration for perf-net
 * 
 * This file replaces direct OpenSSL calls with crypto_wrapper calls.
 */

#include <jni.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <android/log.h>
#include <time.h>
#include "crypto.h"

#define LOG_TAG "perf-crypto-boringssl"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static bool g_module_initialized = false;

static void ensure_initialized() {
    if (!g_module_initialized) {
        const char *mode = getenv("SXR_SSL_MODE");
        if (!mode) mode = "boringssl";
        if (sxr_crypto_init(mode) == 0) {
            LOGI("Crypto initialized: backend=%s version=%s", 
                 sxr_crypto_get_backend(), sxr_crypto_get_version());
            g_module_initialized = true;
        } else {
            LOGE("Failed to initialize crypto subsystem");
        }
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_simplexray_an_performance_PerformanceManager_nativeHasCryptoExtensions(
    JNIEnv* env, jobject thiz) {
    ensure_initialized();
    return sxr_crypto_has_aes_hw() || sxr_crypto_has_sha_hw();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_simplexray_an_performance_PerformanceManager_nativeHasNEON(
    JNIEnv* env, jobject thiz) {
    ensure_initialized();
    return sxr_crypto_has_neon();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_simplexray_an_performance_PerformanceManager_nativeAES128Encrypt(
    JNIEnv* env, jobject thiz, jbyteArray j_key, jbyteArray j_iv,
    jbyteArray j_plaintext, jbyteArray j_ciphertext, jbyteArray j_tag) {
    ensure_initialized();
    
    if (!j_key || !j_iv || !j_plaintext || !j_ciphertext || !j_tag) return -1;
    
    jsize key_len = env->GetArrayLength(j_key);
    jsize iv_len = env->GetArrayLength(j_iv);
    jsize plaintext_len = env->GetArrayLength(j_plaintext);
    jsize tag_len = env->GetArrayLength(j_tag);
    
    if (key_len != 16 || iv_len != 12 || tag_len != 16) return -1;
    
    jbyte *key = env->GetByteArrayElements(j_key, NULL);
    jbyte *iv = env->GetByteArrayElements(j_iv, NULL);
    jbyte *plaintext = env->GetByteArrayElements(j_plaintext, NULL);
    jbyte *ciphertext = env->GetByteArrayElements(j_ciphertext, NULL);
    jbyte *tag = env->GetByteArrayElements(j_tag, NULL);
    
    int result = sxr_aes_gcm_encrypt(
        (const uint8_t*)key, 16, (const uint8_t*)iv, 12, NULL, 0,
        (const uint8_t*)plaintext, plaintext_len,
        (uint8_t*)ciphertext, (uint8_t*)tag, 16);
    
    env->ReleaseByteArrayElements(j_key, key, JNI_ABORT);
    env->ReleaseByteArrayElements(j_iv, iv, JNI_ABORT);
    env->ReleaseByteArrayElements(j_plaintext, plaintext, JNI_ABORT);
    env->ReleaseByteArrayElements(j_ciphertext, ciphertext, 0);
    env->ReleaseByteArrayElements(j_tag, tag, 0);
    
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_simplexray_an_performance_PerformanceManager_nativeChaCha20NEON(
    JNIEnv* env, jobject thiz, jbyteArray j_key, jbyteArray j_nonce,
    jbyteArray j_plaintext, jbyteArray j_ciphertext, jbyteArray j_tag) {
    ensure_initialized();
    
    if (!j_key || !j_nonce || !j_plaintext || !j_ciphertext || !j_tag) return -1;
    
    jsize key_len = env->GetArrayLength(j_key);
    jsize nonce_len = env->GetArrayLength(j_nonce);
    jsize plaintext_len = env->GetArrayLength(j_plaintext);
    
    if (key_len != 32 || nonce_len != 12) return -1;
    
    jbyte *key = env->GetByteArrayElements(j_key, NULL);
    jbyte *nonce = env->GetByteArrayElements(j_nonce, NULL);
    jbyte *plaintext = env->GetByteArrayElements(j_plaintext, NULL);
    jbyte *ciphertext = env->GetByteArrayElements(j_ciphertext, NULL);
    jbyte *tag = env->GetByteArrayElements(j_tag, NULL);
    
    int result = sxr_chacha20_poly1305_encrypt(
        (const uint8_t*)key, (const uint8_t*)nonce, NULL, 0,
        (const uint8_t*)plaintext, plaintext_len,
        (uint8_t*)ciphertext, (uint8_t*)tag);
    
    env->ReleaseByteArrayElements(j_key, key, JNI_ABORT);
    env->ReleaseByteArrayElements(j_nonce, nonce, JNI_ABORT);
    env->ReleaseByteArrayElements(j_plaintext, plaintext, JNI_ABORT);
    env->ReleaseByteArrayElements(j_ciphertext, ciphertext, 0);
    env->ReleaseByteArrayElements(j_tag, tag, 0);
    
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_simplexray_an_performance_OpenSSLDetector_nativeHasOpenSSL(
    JNIEnv* env, jobject thiz) {
    ensure_initialized();
    const char *backend = sxr_crypto_get_backend();
    return (strcmp(backend, "openssl") == 0 || strcmp(backend, "boringssl") == 0);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_simplexray_an_performance_OpenSSLDetector_nativeGetOpenSSLVersion(
    JNIEnv* env, jobject thiz) {
    ensure_initialized();
    return env->NewStringUTF(sxr_crypto_get_version());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_simplexray_an_performance_OpenSSLDetector_nativeGetOpenSSLBuildInfo(
    JNIEnv* env, jobject thiz) {
    ensure_initialized();
    char info[512];
    snprintf(info, sizeof(info), 
             "Backend: %s | Version: %s | CPU: AES=%d SHA=%d NEON=%d",
             sxr_crypto_get_backend(), sxr_crypto_get_version(),
             sxr_crypto_has_aes_hw(), sxr_crypto_has_sha_hw(), sxr_crypto_has_neon());
    return env->NewStringUTF(info);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_simplexray_an_performance_OpenSSLDetector_nativeBenchmarkAESEncrypt(
    JNIEnv* env, jobject thiz, jint iterations, jint dataSize) {
    ensure_initialized();
    if (iterations <= 0 || dataSize <= 0) return -1;
    
    uint8_t key[16], iv[12], *plaintext = new uint8_t[dataSize];
    uint8_t *ciphertext = new uint8_t[dataSize], tag[16];
    
    sxr_rand_bytes(key, 16); sxr_rand_bytes(iv, 12); sxr_rand_bytes(plaintext, dataSize);
    
    struct timespec start, end;
    clock_gettime(CLOCK_MONOTONIC, &start);
    for (int i = 0; i < iterations; i++) {
        sxr_aes_gcm_encrypt(key, 16, iv, 12, NULL, 0, plaintext, dataSize, ciphertext, tag, 16);
    }
    clock_gettime(CLOCK_MONOTONIC, &end);
    
    long elapsed_ns = (end.tv_sec - start.tv_sec) * 1000000000L + (end.tv_nsec - start.tv_nsec);
    delete[] plaintext; delete[] ciphertext;
    return elapsed_ns;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_simplexray_an_performance_OpenSSLDetector_nativeBenchmarkChaChaPoly(
    JNIEnv* env, jobject thiz, jint iterations, jint dataSize) {
    ensure_initialized();
    if (iterations <= 0 || dataSize <= 0) return -1;
    
    uint8_t key[32], iv[12], *plaintext = new uint8_t[dataSize];
    uint8_t *ciphertext = new uint8_t[dataSize], tag[16];
    
    sxr_rand_bytes(key, 32); sxr_rand_bytes(iv, 12); sxr_rand_bytes(plaintext, dataSize);
    
    struct timespec start, end;
    clock_gettime(CLOCK_MONOTONIC, &start);
    for (int i = 0; i < iterations; i++) {
        sxr_chacha20_poly1305_encrypt(key, iv, NULL, 0, plaintext, dataSize, ciphertext, tag);
    }
    clock_gettime(CLOCK_MONOTONIC, &end);
    
    long elapsed_ns = (end.tv_sec - start.tv_sec) * 1000000000L + (end.tv_nsec - start.tv_nsec);
    delete[] plaintext; delete[] ciphertext;
    return elapsed_ns;
}
