/*
 * OpenSSL Detection and Information
 * Runtime detection of OpenSSL availability and version
 */

#include <jni.h>
#include <android/log.h>
#include <string.h>

#ifdef USE_OPENSSL
#include <openssl/opensslv.h>
#include <openssl/crypto.h>
#include <openssl/evp.h>
#include <openssl/aes.h>
#endif

#define LOG_TAG "PerfOpenSSL"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" {

/**
 * NOTE: The following functions are now implemented in perf_crypto_boringssl.cpp
 * using the crypto_wrapper API:
 * - nativeHasOpenSSL
 * - nativeGetOpenSSLVersion  
 * - nativeGetOpenSSLBuildInfo
 * 
 * This file now only contains benchmark functions.
 */

/**
 * Benchmark AES encryption
 * Returns total time in milliseconds
 */
JNIEXPORT jlong JNICALL
Java_com_simplexray_an_performance_OpenSSLDetector_nativeBenchmarkAESEncrypt(
    JNIEnv *env, jobject thiz, jint iterations, jint data_size) {
    (void)env;
    (void)thiz;

#ifdef USE_OPENSSL
    if (iterations <= 0 || data_size <= 0) {
        return -1;
    }

    // Allocate buffers
    unsigned char* plaintext = new unsigned char[data_size];
    unsigned char* ciphertext = new unsigned char[data_size + EVP_MAX_BLOCK_LENGTH];
    unsigned char* key = new unsigned char[16];  // AES-128
    unsigned char* iv = new unsigned char[16];

    // Initialize with test data
    memset(plaintext, 0xAA, data_size);
    memset(key, 0x42, 16);
    memset(iv, 0x00, 16);

    // Get start time
    struct timespec start, end;
    clock_gettime(CLOCK_MONOTONIC, &start);

    // Run benchmark
    for (int i = 0; i < iterations; i++) {
        EVP_CIPHER_CTX* ctx = EVP_CIPHER_CTX_new();
        if (!ctx) {
            delete[] plaintext;
            delete[] ciphertext;
            delete[] key;
            delete[] iv;
            return -1;
        }

        int len = 0;
        int ciphertext_len = 0;

        // Initialize encryption
        EVP_EncryptInit_ex(ctx, EVP_aes_128_cbc(), NULL, key, iv);

        // Encrypt
        EVP_EncryptUpdate(ctx, ciphertext, &len, plaintext, data_size);
        ciphertext_len = len;

        // Finalize
        EVP_EncryptFinal_ex(ctx, ciphertext + len, &len);
        ciphertext_len += len;

        EVP_CIPHER_CTX_free(ctx);
    }

    // Get end time
    clock_gettime(CLOCK_MONOTONIC, &end);

    // Calculate elapsed time in milliseconds
    long elapsed_ms = (end.tv_sec - start.tv_sec) * 1000 +
                      (end.tv_nsec - start.tv_nsec) / 1000000;

    // Cleanup
    delete[] plaintext;
    delete[] ciphertext;
    delete[] key;
    delete[] iv;

    LOGI("AES benchmark: %d iterations of %d bytes in %ld ms",
         iterations, data_size, elapsed_ms);

    return elapsed_ms;
#else
    (void)iterations;
    (void)data_size;
    return -1;
#endif
}

/**
 * Benchmark ChaCha20-Poly1305 encryption
 * Returns total time in milliseconds
 */
JNIEXPORT jlong JNICALL
Java_com_simplexray_an_performance_OpenSSLDetector_nativeBenchmarkChaChaPoly(
    JNIEnv *env, jobject thiz, jint iterations, jint data_size) {
    (void)env;
    (void)thiz;

#ifdef USE_OPENSSL
    if (iterations <= 0 || data_size <= 0) {
        return -1;
    }

    // Allocate buffers
    unsigned char* plaintext = new unsigned char[data_size];
    unsigned char* ciphertext = new unsigned char[data_size + 16];  // +16 for tag
    unsigned char* key = new unsigned char[32];  // ChaCha20 uses 256-bit key
    unsigned char* iv = new unsigned char[12];   // 96-bit nonce

    // Initialize with test data
    memset(plaintext, 0xAA, data_size);
    memset(key, 0x42, 32);
    memset(iv, 0x00, 12);

    // Get start time
    struct timespec start, end;
    clock_gettime(CLOCK_MONOTONIC, &start);

    // Run benchmark
    for (int i = 0; i < iterations; i++) {
        EVP_CIPHER_CTX* ctx = EVP_CIPHER_CTX_new();
        if (!ctx) {
            delete[] plaintext;
            delete[] ciphertext;
            delete[] key;
            delete[] iv;
            return -1;
        }

        int len = 0;
        int ciphertext_len = 0;

        // Initialize encryption with ChaCha20-Poly1305
        EVP_EncryptInit_ex(ctx, EVP_chacha20_poly1305(), NULL, key, iv);

        // Encrypt
        EVP_EncryptUpdate(ctx, ciphertext, &len, plaintext, data_size);
        ciphertext_len = len;

        // Finalize
        EVP_EncryptFinal_ex(ctx, ciphertext + len, &len);
        ciphertext_len += len;

        EVP_CIPHER_CTX_free(ctx);
    }

    // Get end time
    clock_gettime(CLOCK_MONOTONIC, &end);

    // Calculate elapsed time in milliseconds
    long elapsed_ms = (end.tv_sec - start.tv_sec) * 1000 +
                      (end.tv_nsec - start.tv_nsec) / 1000000;

    // Cleanup
    delete[] plaintext;
    delete[] ciphertext;
    delete[] key;
    delete[] iv;

    LOGI("ChaCha20-Poly1305 benchmark: %d iterations of %d bytes in %ld ms",
         iterations, data_size, elapsed_ms);

    return elapsed_ms;
#else
    (void)iterations;
    (void)data_size;
    return -1;
#endif
}

} // extern "C"
