/*
 * Certificate Verifier Overrides and Trust Manager Bridge
 * 
 * Features:
 * - BoringSSL trust manager bridge
 * - X509_STORE_CTX_set_verify_cb() integration
 * - Hostname mismatch handling
 * - Certificate pinning bypass (for isolated test env)
 */

#include <jni.h>
#include <android/log.h>
#include <cstring>

#include <openssl/ssl.h>
#include <openssl/x509.h>
#include <openssl/x509v3.h>
#include <openssl/err.h>

#define LOG_TAG "PerfCertVerifier"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Verification callback context
struct VerifyContext {
    bool allow_hostname_mismatch;
    bool bypass_pinning;
    char* expected_hostname;
};

/**
 * Certificate verification callback
 * This is called during SSL handshake to verify certificates
 */
static int verify_callback(int preverify_ok, X509_STORE_CTX* ctx) {
    if (!ctx) {
        return 0;
    }
    
    // Get SSL context from store context
    SSL* ssl = static_cast<SSL*>(X509_STORE_CTX_get_ex_data(
        ctx, SSL_get_ex_data_X509_STORE_CTX_idx()));
    
    if (!ssl) {
        return preverify_ok;
    }
    
    // Get verification context from SSL
    VerifyContext* vctx = static_cast<VerifyContext*>(
        SSL_get_ex_data(ssl, 0));
    
    if (!vctx) {
        // No custom verification context, use default
        return preverify_ok;
    }
    
    // If preverify failed, check if we should allow it
    if (!preverify_ok) {
        int err = X509_STORE_CTX_get_error(ctx);
        
        // Allow hostname mismatch if configured
        if (vctx->allow_hostname_mismatch && 
            (err == X509_V_ERR_HOSTNAME_MISMATCH || 
             err == X509_V_ERR_SUBJECT_ISSUER_MISMATCH)) {
            LOGD("Allowing hostname mismatch (test mode)");
            return 1;
        }
        
        // Allow pinning bypass if configured (TEST ONLY)
        if (vctx->bypass_pinning && err == X509_V_ERR_CERT_HAS_EXPIRED) {
            LOGD("WARNING: Bypassing certificate pinning (TEST MODE ONLY)");
            return 1;
        }
    }
    
    return preverify_ok;
}

extern "C" {

/**
 * Create certificate verifier context
 */
JNIEXPORT jlong JNICALL
Java_com_simplexray_an_performance_PerformanceManager_nativeCreateCertVerifier(
    JNIEnv *env, jclass clazz, jboolean allow_hostname_mismatch, 
    jboolean bypass_pinning, jstring hostname) {
    
    VerifyContext* vctx = new VerifyContext();
    vctx->allow_hostname_mismatch = allow_hostname_mismatch == JNI_TRUE;
    vctx->bypass_pinning = bypass_pinning == JNI_TRUE;
    vctx->expected_hostname = nullptr;
    
    if (hostname) {
        const char* hostname_str = env->GetStringUTFChars(hostname, nullptr);
        if (hostname_str) {
            size_t len = strlen(hostname_str);
            vctx->expected_hostname = new char[len + 1];
            strcpy(vctx->expected_hostname, hostname_str);
            env->ReleaseStringUTFChars(hostname, hostname_str);
        }
    }
    
    LOGD("Created certificate verifier (hostname_mismatch=%d, bypass_pinning=%d)",
         vctx->allow_hostname_mismatch, vctx->bypass_pinning);
    
    return reinterpret_cast<jlong>(vctx);
}

/**
 * Set certificate verification callback for SSL context
 */
JNIEXPORT jint JNICALL
Java_com_simplexray_an_performance_PerformanceManager_nativeSetCertVerifyCallback(
    JNIEnv *env, jclass clazz, jlong ctx_ptr, jlong verifier_ptr) {
    
    if (!ctx_ptr) {
        LOGE("Invalid SSL context");
        return -1;
    }
    
    SSL_CTX* ctx = reinterpret_cast<SSL_CTX*>(ctx_ptr);
    VerifyContext* vctx = nullptr;
    
    if (verifier_ptr) {
        vctx = reinterpret_cast<VerifyContext*>(verifier_ptr);
    }
    
    // Set verification callback
    SSL_CTX_set_verify(ctx, SSL_VERIFY_PEER, verify_callback);
    
    // Store verification context in SSL context
    // Note: We'll need to set this per SSL connection, not context
    // For now, we store it in the context user data
    
    if (vctx) {
        SSL_CTX_set_ex_data(ctx, 0, vctx);
    }
    
    LOGD("Set certificate verification callback");
    
    return 0;
}

/**
 * Set verification callback for SSL connection
 */
JNIEXPORT jint JNICALL
Java_com_simplexray_an_performance_PerformanceManager_nativeSetSSLVerifyCallback(
    JNIEnv *env, jclass clazz, jlong ssl_ptr, jlong verifier_ptr) {
    
    if (!ssl_ptr) {
        LOGE("Invalid SSL connection");
        return -1;
    }
    
    SSL* ssl = reinterpret_cast<SSL*>(ssl_ptr);
    VerifyContext* vctx = nullptr;
    
    if (verifier_ptr) {
        vctx = reinterpret_cast<VerifyContext*>(verifier_ptr);
    }
    
    // Set verification callback
    SSL_set_verify(ssl, SSL_VERIFY_PEER, verify_callback);
    
    // Store verification context in SSL connection
    if (vctx) {
        SSL_set_ex_data(ssl, 0, vctx);
    }
    
    LOGD("Set SSL verification callback");
    
    return 0;
}

/**
 * Free certificate verifier context
 */
JNIEXPORT void JNICALL
Java_com_simplexray_an_performance_PerformanceManager_nativeFreeCertVerifier(
    JNIEnv *env, jclass clazz, jlong verifier_ptr) {
    
    if (verifier_ptr) {
        VerifyContext* vctx = reinterpret_cast<VerifyContext*>(verifier_ptr);
        if (vctx->expected_hostname) {
            delete[] vctx->expected_hostname;
        }
        delete vctx;
        LOGD("Freed certificate verifier");
    }
}

} // extern "C"


