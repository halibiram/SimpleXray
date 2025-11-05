/*
 * BoringSSL JNI Bridge
 * Exposes essential BoringSSL functions to Java/Kotlin code
 */

#include <jni.h>
#include <android/log.h>
#include <cstring>
#include "crypto_adapter.h"

#define LOG_TAG "BoringSSLBridge"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// BoringSSL includes
#define OPENSSL_HEADER_STATIC
#include <openssl/evp.h>
#include <openssl/aead.h>
#include <openssl/rand.h>
#include <openssl/err.h>

// AEAD context structure
struct AeadContext {
    const EVP_AEAD* aead;
    EVP_AEAD_CTX ctx;
    int initialized;
};

extern "C" {

/**
 * Generate random bytes
 * Class: com.simplexray.an.performance.BoringSSLBridge
 * Method: nativeRandBytes([B)I
 */
JNIEXPORT jint JNICALL
Java_com_simplexray_an_performance_BoringSSLBridge_nativeRandBytes(
    JNIEnv *env, jclass clazz, jbyteArray output) {
    (void)clazz;
    
    if (!output) {
        LOGE("Output buffer is null");
        return -1;
    }
    
    jsize len = env->GetArrayLength(output);
    if (len <= 0) {
        LOGE("Invalid output length: %d", len);
        return -1;
    }
    
    jbyte* bytes = env->GetByteArrayElements(output, nullptr);
    if (!bytes) {
        LOGE("Failed to get byte array elements");
        return -1;
    }
    
    // Generate random bytes using BoringSSL
    if (RAND_bytes(reinterpret_cast<unsigned char*>(bytes), len) != 1) {
        LOGE("RAND_bytes failed: %s", ERR_error_string(ERR_get_error(), nullptr));
        env->ReleaseByteArrayElements(output, bytes, JNI_ABORT);
        return -1;
    }
    
    env->ReleaseByteArrayElements(output, bytes, 0);
    return 0;
}

/**
 * Create AEAD context (AES-GCM or ChaCha20-Poly1305)
 * Class: com.simplexray.an.performance.BoringSSLBridge
 * Method: nativeCreateAeadContext(Ljava/lang/String;)J
 */
JNIEXPORT jlong JNICALL
Java_com_simplexray_an_performance_BoringSSLBridge_nativeCreateAeadContext(
    JNIEnv *env, jclass clazz, jstring algorithm) {
    (void)clazz;
    
    if (!algorithm) {
        LOGE("Algorithm string is null");
        return 0;
    }
    
    const char* alg_str = env->GetStringUTFChars(algorithm, nullptr);
    if (!alg_str) {
        LOGE("Failed to get algorithm string");
        return 0;
    }
    
    AeadContext* ctx = new AeadContext();
    ctx->initialized = 0;
    
    // Select AEAD cipher based on algorithm string
    if (strcmp(alg_str, "aes-256-gcm") == 0 || strcmp(alg_str, "AES-256-GCM") == 0) {
        ctx->aead = EVP_aead_aes_256_gcm();
    } else if (strcmp(alg_str, "aes-128-gcm") == 0 || strcmp(alg_str, "AES-128-GCM") == 0) {
        ctx->aead = EVP_aead_aes_128_gcm();
    } else if (strcmp(alg_str, "chacha20-poly1305") == 0 || 
               strcmp(alg_str, "ChaCha20-Poly1305") == 0) {
        ctx->aead = EVP_aead_chacha20_poly1305();
    } else {
        LOGE("Unsupported algorithm: %s", alg_str);
        env->ReleaseStringUTFChars(algorithm, alg_str);
        delete ctx;
        return 0;
    }
    
    env->ReleaseStringUTFChars(algorithm, alg_str);
    
    if (!ctx->aead) {
        LOGE("Failed to get AEAD cipher");
        delete ctx;
        return 0;
    }
    
    // Initialize context (will be initialized with key in encrypt/decrypt)
    EVP_AEAD_CTX_zero(&ctx->ctx);
    ctx->initialized = 0;
    
    return reinterpret_cast<jlong>(ctx);
}

/**
 * AEAD encrypt
 * Class: com.simplexray.an.performance.BoringSSLBridge
 * Method: nativeAeadEncrypt(J[B[B[B[B)[B
 */
JNIEXPORT jbyteArray JNICALL
Java_com_simplexray_an_performance_BoringSSLBridge_nativeAeadEncrypt(
    JNIEnv *env, jclass clazz,
    jlong ctxHandle, jbyteArray key, jbyteArray nonce,
    jbyteArray aad, jbyteArray plaintext) {
    (void)clazz;
    
    if (!ctxHandle) {
        LOGE("Invalid context handle");
        return nullptr;
    }
    
    AeadContext* ctx = reinterpret_cast<AeadContext*>(ctxHandle);
    if (!ctx || !ctx->aead) {
        LOGE("Invalid context");
        return nullptr;
    }
    
    // Get array lengths
    jsize key_len = env->GetArrayLength(key);
    jsize nonce_len = env->GetArrayLength(nonce);
    jsize aad_len = aad ? env->GetArrayLength(aad) : 0;
    jsize plaintext_len = env->GetArrayLength(plaintext);
    
    // Validate key length (should match AEAD key size)
    size_t expected_key_len = EVP_AEAD_key_length(ctx->aead);
    if (static_cast<size_t>(key_len) != expected_key_len) {
        LOGE("Invalid key length: %d (expected: %zu)", key_len, expected_key_len);
        return nullptr;
    }
    
    // Get array elements
    jbyte* key_bytes = env->GetByteArrayElements(key, nullptr);
    jbyte* nonce_bytes = env->GetByteArrayElements(nonce, nullptr);
    jbyte* aad_bytes = aad ? env->GetByteArrayElements(aad, nullptr) : nullptr;
    jbyte* plaintext_bytes = env->GetByteArrayElements(plaintext, nullptr);
    
    if (!key_bytes || !nonce_bytes || !plaintext_bytes) {
        LOGE("Failed to get array elements");
        if (key_bytes) env->ReleaseByteArrayElements(key, key_bytes, JNI_ABORT);
        if (nonce_bytes) env->ReleaseByteArrayElements(nonce, nonce_bytes, JNI_ABORT);
        if (aad_bytes) env->ReleaseByteArrayElements(aad, aad_bytes, JNI_ABORT);
        if (plaintext_bytes) env->ReleaseByteArrayElements(plaintext, plaintext_bytes, JNI_ABORT);
        return nullptr;
    }
    
    // Initialize context if needed
    if (!ctx->initialized) {
        if (!EVP_AEAD_CTX_init(&ctx->ctx, ctx->aead,
                               reinterpret_cast<const uint8_t*>(key_bytes), key_len,
                               EVP_AEAD_DEFAULT_TAG_LENGTH, nullptr)) {
            LOGE("Failed to initialize AEAD context");
            env->ReleaseByteArrayElements(key, key_bytes, JNI_ABORT);
            env->ReleaseByteArrayElements(nonce, nonce_bytes, JNI_ABORT);
            if (aad_bytes) env->ReleaseByteArrayElements(aad, aad_bytes, JNI_ABORT);
            env->ReleaseByteArrayElements(plaintext, plaintext_bytes, JNI_ABORT);
            return nullptr;
        }
        ctx->initialized = 1;
    }
    
    // Calculate output size (plaintext + tag)
    size_t tag_len = EVP_AEAD_max_overhead(ctx->aead);
    size_t out_len = plaintext_len + tag_len;
    
    // Allocate output buffer
    uint8_t* out_buf = new uint8_t[out_len];
    if (!out_buf) {
        LOGE("Failed to allocate output buffer");
        env->ReleaseByteArrayElements(key, key_bytes, JNI_ABORT);
        env->ReleaseByteArrayElements(nonce, nonce_bytes, JNI_ABORT);
        if (aad_bytes) env->ReleaseByteArrayElements(aad, aad_bytes, JNI_ABORT);
        env->ReleaseByteArrayElements(plaintext, plaintext_bytes, JNI_ABORT);
        return nullptr;
    }
    
    size_t actual_out_len = 0;
    
    // Encrypt
    if (!EVP_AEAD_CTX_seal(&ctx->ctx,
                          out_buf, &actual_out_len, out_len,
                          reinterpret_cast<const uint8_t*>(nonce_bytes), nonce_len,
                          reinterpret_cast<const uint8_t*>(plaintext_bytes), plaintext_len,
                          reinterpret_cast<const uint8_t*>(aad_bytes), aad_len)) {
        LOGE("AEAD encryption failed: %s", ERR_error_string(ERR_get_error(), nullptr));
        delete[] out_buf;
        env->ReleaseByteArrayElements(key, key_bytes, JNI_ABORT);
        env->ReleaseByteArrayElements(nonce, nonce_bytes, JNI_ABORT);
        if (aad_bytes) env->ReleaseByteArrayElements(aad, aad_bytes, JNI_ABORT);
        env->ReleaseByteArrayElements(plaintext, plaintext_bytes, JNI_ABORT);
        return nullptr;
    }
    
    // Create Java byte array
    jbyteArray result = env->NewByteArray(static_cast<jsize>(actual_out_len));
    if (!result) {
        LOGE("Failed to create result array");
        delete[] out_buf;
        env->ReleaseByteArrayElements(key, key_bytes, JNI_ABORT);
        env->ReleaseByteArrayElements(nonce, nonce_bytes, JNI_ABORT);
        if (aad_bytes) env->ReleaseByteArrayElements(aad, aad_bytes, JNI_ABORT);
        env->ReleaseByteArrayElements(plaintext, plaintext_bytes, JNI_ABORT);
        return nullptr;
    }
    
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(actual_out_len),
                           reinterpret_cast<const jbyte*>(out_buf));
    
    delete[] out_buf;
    env->ReleaseByteArrayElements(key, key_bytes, JNI_ABORT);
    env->ReleaseByteArrayElements(nonce, nonce_bytes, JNI_ABORT);
    if (aad_bytes) env->ReleaseByteArrayElements(aad, aad_bytes, JNI_ABORT);
    env->ReleaseByteArrayElements(plaintext, plaintext_bytes, JNI_ABORT);
    
    return result;
}

/**
 * AEAD decrypt
 * Class: com.simplexray.an.performance.BoringSSLBridge
 * Method: nativeAeadDecrypt(J[B[B[B[B)[B
 */
JNIEXPORT jbyteArray JNICALL
Java_com_simplexray_an_performance_BoringSSLBridge_nativeAeadDecrypt(
    JNIEnv *env, jclass clazz,
    jlong ctxHandle, jbyteArray key, jbyteArray nonce,
    jbyteArray aad, jbyteArray ciphertext) {
    (void)clazz;
    
    if (!ctxHandle) {
        LOGE("Invalid context handle");
        return nullptr;
    }
    
    AeadContext* ctx = reinterpret_cast<AeadContext*>(ctxHandle);
    if (!ctx || !ctx->aead) {
        LOGE("Invalid context");
        return nullptr;
    }
    
    // Get array lengths
    jsize key_len = env->GetArrayLength(key);
    jsize nonce_len = env->GetArrayLength(nonce);
    jsize aad_len = aad ? env->GetArrayLength(aad) : 0;
    jsize ciphertext_len = env->GetArrayLength(ciphertext);
    
    // Validate key length
    size_t expected_key_len = EVP_AEAD_key_length(ctx->aead);
    if (static_cast<size_t>(key_len) != expected_key_len) {
        LOGE("Invalid key length: %d (expected: %zu)", key_len, expected_key_len);
        return nullptr;
    }
    
    // Get array elements
    jbyte* key_bytes = env->GetByteArrayElements(key, nullptr);
    jbyte* nonce_bytes = env->GetByteArrayElements(nonce, nullptr);
    jbyte* aad_bytes = aad ? env->GetByteArrayElements(aad, nullptr) : nullptr;
    jbyte* ciphertext_bytes = env->GetByteArrayElements(ciphertext, nullptr);
    
    if (!key_bytes || !nonce_bytes || !ciphertext_bytes) {
        LOGE("Failed to get array elements");
        if (key_bytes) env->ReleaseByteArrayElements(key, key_bytes, JNI_ABORT);
        if (nonce_bytes) env->ReleaseByteArrayElements(nonce, nonce_bytes, JNI_ABORT);
        if (aad_bytes) env->ReleaseByteArrayElements(aad, aad_bytes, JNI_ABORT);
        if (ciphertext_bytes) env->ReleaseByteArrayElements(ciphertext, ciphertext_bytes, JNI_ABORT);
        return nullptr;
    }
    
    // Initialize context if needed
    if (!ctx->initialized) {
        if (!EVP_AEAD_CTX_init(&ctx->ctx, ctx->aead,
                               reinterpret_cast<const uint8_t*>(key_bytes), key_len,
                               EVP_AEAD_DEFAULT_TAG_LENGTH, nullptr)) {
            LOGE("Failed to initialize AEAD context");
            env->ReleaseByteArrayElements(key, key_bytes, JNI_ABORT);
            env->ReleaseByteArrayElements(nonce, nonce_bytes, JNI_ABORT);
            if (aad_bytes) env->ReleaseByteArrayElements(aad, aad_bytes, JNI_ABORT);
            env->ReleaseByteArrayElements(ciphertext, ciphertext_bytes, JNI_ABORT);
            return nullptr;
        }
        ctx->initialized = 1;
    }
    
    // Calculate output size (ciphertext - tag)
    size_t tag_len = EVP_AEAD_max_overhead(ctx->aead);
    if (static_cast<size_t>(ciphertext_len) < tag_len) {
        LOGE("Ciphertext too short: %d (minimum: %zu)", ciphertext_len, tag_len);
        env->ReleaseByteArrayElements(key, key_bytes, JNI_ABORT);
        env->ReleaseByteArrayElements(nonce, nonce_bytes, JNI_ABORT);
        if (aad_bytes) env->ReleaseByteArrayElements(aad, aad_bytes, JNI_ABORT);
        env->ReleaseByteArrayElements(ciphertext, ciphertext_bytes, JNI_ABORT);
        return nullptr;
    }
    
    size_t out_len = ciphertext_len - tag_len;
    uint8_t* out_buf = new uint8_t[out_len];
    if (!out_buf) {
        LOGE("Failed to allocate output buffer");
        env->ReleaseByteArrayElements(key, key_bytes, JNI_ABORT);
        env->ReleaseByteArrayElements(nonce, nonce_bytes, JNI_ABORT);
        if (aad_bytes) env->ReleaseByteArrayElements(aad, aad_bytes, JNI_ABORT);
        env->ReleaseByteArrayElements(ciphertext, ciphertext_bytes, JNI_ABORT);
        return nullptr;
    }
    
    size_t actual_out_len = 0;
    
    // Decrypt
    if (!EVP_AEAD_CTX_open(&ctx->ctx,
                          out_buf, &actual_out_len, out_len,
                          reinterpret_cast<const uint8_t*>(nonce_bytes), nonce_len,
                          reinterpret_cast<const uint8_t*>(ciphertext_bytes), ciphertext_len,
                          reinterpret_cast<const uint8_t*>(aad_bytes), aad_len)) {
        LOGE("AEAD decryption failed: %s", ERR_error_string(ERR_get_error(), nullptr));
        delete[] out_buf;
        env->ReleaseByteArrayElements(key, key_bytes, JNI_ABORT);
        env->ReleaseByteArrayElements(nonce, nonce_bytes, JNI_ABORT);
        if (aad_bytes) env->ReleaseByteArrayElements(aad, aad_bytes, JNI_ABORT);
        env->ReleaseByteArrayElements(ciphertext, ciphertext_bytes, JNI_ABORT);
        return nullptr;
    }
    
    // Create Java byte array
    jbyteArray result = env->NewByteArray(static_cast<jsize>(actual_out_len));
    if (!result) {
        LOGE("Failed to create result array");
        delete[] out_buf;
        env->ReleaseByteArrayElements(key, key_bytes, JNI_ABORT);
        env->ReleaseByteArrayElements(nonce, nonce_bytes, JNI_ABORT);
        if (aad_bytes) env->ReleaseByteArrayElements(aad, aad_bytes, JNI_ABORT);
        env->ReleaseByteArrayElements(ciphertext, ciphertext_bytes, JNI_ABORT);
        return nullptr;
    }
    
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(actual_out_len),
                           reinterpret_cast<const jbyte*>(out_buf));
    
    delete[] out_buf;
    env->ReleaseByteArrayElements(key, key_bytes, JNI_ABORT);
    env->ReleaseByteArrayElements(nonce, nonce_bytes, JNI_ABORT);
    if (aad_bytes) env->ReleaseByteArrayElements(aad, aad_bytes, JNI_ABORT);
    env->ReleaseByteArrayElements(ciphertext, ciphertext_bytes, JNI_ABORT);
    
    return result;
}

/**
 * Free AEAD context
 * Class: com.simplexray.an.performance.BoringSSLBridge
 * Method: nativeFreeAeadContext(J)V
 */
JNIEXPORT void JNICALL
Java_com_simplexray_an_performance_BoringSSLBridge_nativeFreeAeadContext(
    JNIEnv *env, jclass clazz, jlong ctxHandle) {
    (void)env; (void)clazz;
    
    if (!ctxHandle) return;
    
    AeadContext* ctx = reinterpret_cast<AeadContext*>(ctxHandle);
    if (ctx->initialized) {
        EVP_AEAD_CTX_cleanup(&ctx->ctx);
    }
    delete ctx;
}

} // extern "C"

