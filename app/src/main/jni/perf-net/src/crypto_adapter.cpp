/*
 * Crypto Adapter Implementation - BoringSSL Backend
 * Provides OpenSSL-compatible API using BoringSSL
 */

#include "crypto_adapter.h"
#include <android/log.h>

#define LOG_TAG "CryptoAdapter"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// BoringSSL includes (compatible with OpenSSL API)
#define OPENSSL_HEADER_STATIC
#include <openssl/evp.h>
#include <openssl/rand.h>
#include <openssl/hkdf.h>
#include <openssl/curve25519.h>
#include <openssl/err.h>

#if defined(__aarch64__) && defined(__ANDROID_API__) && __ANDROID_API__ >= 18
#include <sys/auxv.h>
#endif

#if defined(__aarch64__) || defined(__arm__)
#define HAS_NEON_AVAILABLE 1
#else
#define HAS_NEON_AVAILABLE 0
#endif

// Static capability cache
static CryptoCapabilities g_caps = {0};
static int g_caps_initialized = 0;

void crypto_adapter_init(CryptoCapabilities* caps) {
    if (!caps) return;
    
    // Initialize once
    if (!g_caps_initialized) {
        // Check NEON availability
        #if HAS_NEON_AVAILABLE
        g_caps.has_neon = 1;
        #else
        g_caps.has_neon = 0;
        #endif
        
        // Check ARMv8 Crypto Extensions
        #if defined(__aarch64__) && defined(__ANDROID_API__) && __ANDROID_API__ >= 18
        unsigned long hwcap = getauxval(AT_HWCAP);
        g_caps.has_crypto_extensions = (hwcap & HWCAP_AES) ? 1 : 0;
        #else
        g_caps.has_crypto_extensions = 0;
        #endif
        
        // Decision: Use hardware AES if available, otherwise prefer ChaCha20
        g_caps.use_hw_aes = (g_caps.has_crypto_extensions && g_caps.has_neon) ? 1 : 0;
        g_caps.use_chacha_fallback = !g_caps.use_hw_aes;
        
        g_caps_initialized = 1;
        
        LOGD("Crypto capabilities: NEON=%d, CryptoExt=%d, UseHWAES=%d, UseChaCha=%d",
             g_caps.has_neon, g_caps.has_crypto_extensions,
             g_caps.use_hw_aes, g_caps.use_chacha_fallback);
    }
    
    *caps = g_caps;
}

int crypto_adapter_has_crypto_extensions(void) {
    if (!g_caps_initialized) {
        CryptoCapabilities caps;
        crypto_adapter_init(&caps);
    }
    return g_caps.has_crypto_extensions;
}

int crypto_adapter_has_neon(void) {
    if (!g_caps_initialized) {
        CryptoCapabilities caps;
        crypto_adapter_init(&caps);
    }
    return g_caps.has_neon;
}

const char* crypto_adapter_get_recommended_cipher(const CryptoCapabilities* caps) {
    if (!caps) return "chacha20-poly1305";
    
    if (caps->use_hw_aes) {
        return "aes-256-gcm";
    } else {
        return "chacha20-poly1305";
    }
}

int crypto_adapter_rand_bytes(uint8_t* buf, size_t len) {
    if (!buf || len == 0) {
        LOGE("Invalid parameters for RAND_bytes");
        return 0;
    }
    
    // BoringSSL RAND_bytes is compatible with OpenSSL
    return RAND_bytes(buf, static_cast<int>(len)) == 1 ? 1 : 0;
}

int crypto_adapter_hkdf_sha256(
    const uint8_t* salt, size_t salt_len,
    const uint8_t* ikm, size_t ikm_len,
    const uint8_t* info, size_t info_len,
    uint8_t* out, size_t out_len
) {
    if (!salt || !ikm || !info || !out) {
        LOGE("Invalid parameters for HKDF");
        return 0;
    }
    
    // BoringSSL HKDF API (same as OpenSSL)
    const EVP_MD* md = EVP_sha256();
    if (!md) {
        LOGE("Failed to get SHA256 digest");
        return 0;
    }
    
    int ret = HKDF(out, static_cast<size_t>(out_len), md,
                   ikm, ikm_len,
                   salt, salt_len,
                   info, info_len);
    
    if (ret != 1) {
        LOGE("HKDF failed: %s", ERR_error_string(ERR_get_error(), nullptr));
        return 0;
    }
    
    return 1;
}

int crypto_adapter_ecdh_x25519(
    const uint8_t* private_key,
    const uint8_t* public_key,
    uint8_t* shared_secret
) {
    if (!private_key || !public_key || !shared_secret) {
        LOGE("Invalid parameters for X25519");
        return 0;
    }
    
    // BoringSSL X25519 API
    // Note: BoringSSL uses different API than OpenSSL
    // X25519 function signature: X25519(out, scalar, point)
    if (!X25519(shared_secret, private_key, public_key)) {
        LOGE("X25519 key exchange failed");
        return 0;
    }
    
    return 1;
}

