/**
 * SimpleXray Crypto Wrapper Implementation
 *
 * This file routes crypto operations to either BoringSSL or OpenSSL
 * based on compile-time and runtime configuration.
 */

#include "crypto.h"
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <pthread.h>
#include <atomic>

// Conditional includes based on build configuration
#if defined(USE_BORINGSSL)
    #include <openssl/crypto.h>
    #include <openssl/evp.h>
    #include <openssl/aead.h>
    #include <openssl/rand.h>
    #define CRYPTO_BACKEND "boringssl"
#elif defined(USE_OPENSSL)
    #include <openssl/evp.h>
    #include <openssl/rand.h>
    #include <openssl/opensslv.h>
    #define CRYPTO_BACKEND "openssl"
#else
    #define CRYPTO_BACKEND "none"
#endif

// ARM NEON detection
#if defined(__aarch64__) || defined(__ARM_NEON)
    #include <sys/auxv.h>
    #include <asm/hwcap.h>
    #define HAS_NEON_SUPPORT 1
#else
    #define HAS_NEON_SUPPORT 0
#endif

/* Global state */
static std::atomic<bool> g_crypto_initialized(false);
static const char *g_crypto_mode = CRYPTO_BACKEND;
static uint32_t g_cpu_features = 0;

/* CPU feature detection */
static void detect_cpu_features() {
    g_cpu_features = 0;
#if defined(__aarch64__)
    unsigned long hwcaps = getauxval(AT_HWCAP);
    #ifdef HWCAP_AES
    if (hwcaps & HWCAP_AES) g_cpu_features |= (1 << 0);
    #endif
    #ifdef HWCAP_SHA1
    if (hwcaps & HWCAP_SHA1) g_cpu_features |= (1 << 1);
    #endif
    #ifdef HWCAP_SHA2
    if (hwcaps & HWCAP_SHA2) g_cpu_features |= (1 << 2);
    #endif
    #ifdef HWCAP_ASIMD
    if (hwcaps & HWCAP_ASIMD) g_cpu_features |= (1 << 3);
    #endif
#elif defined(__arm__)
    unsigned long hwcaps = getauxval(AT_HWCAP);
    #ifdef HWCAP_NEON
    if (hwcaps & HWCAP_NEON) g_cpu_features |= (1 << 3);
    #endif
#endif
}

int sxr_crypto_init(const char *mode) {
    if (g_crypto_initialized.load()) return 0;
    detect_cpu_features();
    if (mode && strlen(mode) > 0) {
        if (strcmp(mode, "hybrid") == 0) {
            #if defined(USE_BORINGSSL)
            g_crypto_mode = "boringssl";
            #elif defined(USE_OPENSSL)
            g_crypto_mode = "openssl";
            #else
            g_crypto_mode = "none";
            #endif
        } else {
            g_crypto_mode = mode;
        }
    }
    g_crypto_initialized.store(true);
    return 0;
}

void sxr_crypto_cleanup() {
    g_crypto_initialized.store(false);
}

const char* sxr_crypto_get_backend() {
    return g_crypto_mode;
}

const char* sxr_crypto_get_version() {
#if defined(USE_BORINGSSL)
    return "BoringSSL";
#elif defined(USE_OPENSSL)
    return OpenSSL_version(OPENSSL_VERSION);
#else
    return "No crypto library";
#endif
}

int sxr_rand_bytes(uint8_t *buf, size_t len) {
    if (!buf || len == 0) return -1;
#if defined(USE_BORINGSSL) || defined(USE_OPENSSL)
    return RAND_bytes(buf, (int)len) == 1 ? 0 : -1;
#else
    FILE *f = fopen("/dev/urandom", "rb");
    if (!f) return -1;
    size_t n = fread(buf, 1, len, f);
    fclose(f);
    return (n == len) ? 0 : -1;
#endif
}

void sxr_rand_add(const uint8_t *buf, size_t len) {
#if defined(USE_BORINGSSL) || defined(USE_OPENSSL)
    RAND_add(buf, (int)len, (double)len);
#else
    (void)buf; (void)len;
#endif
}

int sxr_aes_gcm_encrypt(
    const uint8_t *key, size_t keylen,
    const uint8_t *iv, size_t ivlen,
    const uint8_t *aad, size_t aadlen,
    const uint8_t *plaintext, size_t plaintext_len,
    uint8_t *ciphertext,
    uint8_t *tag, size_t taglen)
{
#if defined(USE_BORINGSSL)
    const EVP_AEAD *aead;
    if (keylen == 16) aead = EVP_aead_aes_128_gcm();
    else if (keylen == 32) aead = EVP_aead_aes_256_gcm();
    else return -1;
    
    EVP_AEAD_CTX ctx;
    if (!EVP_AEAD_CTX_init(&ctx, aead, key, keylen, taglen, NULL)) return -1;
    
    size_t out_len;
    int ret = EVP_AEAD_CTX_seal(&ctx, ciphertext, &out_len,
                                 plaintext_len + taglen,
                                 iv, ivlen, plaintext, plaintext_len,
                                 aad, aadlen) ? 0 : -1;
    if (ret == 0) memcpy(tag, ciphertext + plaintext_len, taglen);
    EVP_AEAD_CTX_cleanup(&ctx);
    return ret;
#elif defined(USE_OPENSSL)
    EVP_CIPHER_CTX *ctx = EVP_CIPHER_CTX_new();
    if (!ctx) return -1;
    const EVP_CIPHER *cipher = (keylen == 16) ? EVP_aes_128_gcm() : EVP_aes_256_gcm();
    int ret = -1, len;
    if (EVP_EncryptInit_ex(ctx, cipher, NULL, NULL, NULL) != 1) goto cleanup;
    if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_IVLEN, (int)ivlen, NULL) != 1) goto cleanup;
    if (EVP_EncryptInit_ex(ctx, NULL, NULL, key, iv) != 1) goto cleanup;
    if (aad && aadlen > 0) {
        if (EVP_EncryptUpdate(ctx, NULL, &len, aad, (int)aadlen) != 1) goto cleanup;
    }
    if (EVP_EncryptUpdate(ctx, ciphertext, &len, plaintext, (int)plaintext_len) != 1) goto cleanup;
    if (EVP_EncryptFinal_ex(ctx, ciphertext + len, &len) != 1) goto cleanup;
    if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_GET_TAG, (int)taglen, tag) != 1) goto cleanup;
    ret = 0;
cleanup:
    EVP_CIPHER_CTX_free(ctx);
    return ret;
#else
    return -1;
#endif
}

int sxr_aes_gcm_decrypt(
    const uint8_t *key, size_t keylen,
    const uint8_t *iv, size_t ivlen,
    const uint8_t *aad, size_t aadlen,
    const uint8_t *ciphertext, size_t ciphertext_len,
    const uint8_t *tag, size_t taglen,
    uint8_t *plaintext)
{
#if defined(USE_BORINGSSL)
    const EVP_AEAD *aead = (keylen == 16) ? EVP_aead_aes_128_gcm() : EVP_aead_aes_256_gcm();
    EVP_AEAD_CTX ctx;
    if (!EVP_AEAD_CTX_init(&ctx, aead, key, keylen, taglen, NULL)) return -2;
    uint8_t *combined = (uint8_t*)malloc(ciphertext_len + taglen);
    if (!combined) { EVP_AEAD_CTX_cleanup(&ctx); return -2; }
    memcpy(combined, ciphertext, ciphertext_len);
    memcpy(combined + ciphertext_len, tag, taglen);
    size_t out_len;
    int ret = EVP_AEAD_CTX_open(&ctx, plaintext, &out_len, ciphertext_len,
                                 iv, ivlen, combined, ciphertext_len + taglen,
                                 aad, aadlen) ? 0 : -1;
    free(combined);
    EVP_AEAD_CTX_cleanup(&ctx);
    return ret;
#elif defined(USE_OPENSSL)
    EVP_CIPHER_CTX *ctx = EVP_CIPHER_CTX_new();
    if (!ctx) return -2;
    const EVP_CIPHER *cipher = (keylen == 16) ? EVP_aes_128_gcm() : EVP_aes_256_gcm();
    int ret = -1, len;
    if (EVP_DecryptInit_ex(ctx, cipher, NULL, NULL, NULL) != 1) goto cleanup;
    if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_IVLEN, (int)ivlen, NULL) != 1) goto cleanup;
    if (EVP_DecryptInit_ex(ctx, NULL, NULL, key, iv) != 1) goto cleanup;
    if (aad && aadlen > 0) {
        if (EVP_DecryptUpdate(ctx, NULL, &len, aad, (int)aadlen) != 1) goto cleanup;
    }
    if (EVP_DecryptUpdate(ctx, plaintext, &len, ciphertext, (int)ciphertext_len) != 1) goto cleanup;
    if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_TAG, (int)taglen, (void*)tag) != 1) goto cleanup;
    ret = (EVP_DecryptFinal_ex(ctx, plaintext + len, &len) == 1) ? 0 : -1;
cleanup:
    EVP_CIPHER_CTX_free(ctx);
    return ret;
#else
    return -2;
#endif
}

int sxr_chacha20_poly1305_encrypt(const uint8_t *key, const uint8_t *iv,
                                   const uint8_t *aad, size_t aadlen,
                                   const uint8_t *plaintext, size_t plaintext_len,
                                   uint8_t *ciphertext, uint8_t *tag) {
#if defined(USE_BORINGSSL)
    const EVP_AEAD *aead = EVP_aead_chacha20_poly1305();
    EVP_AEAD_CTX ctx;
    if (!EVP_AEAD_CTX_init(&ctx, aead, key, 32, 16, NULL)) return -1;
    size_t out_len;
    int ret = EVP_AEAD_CTX_seal(&ctx, ciphertext, &out_len, plaintext_len + 16,
                                 iv, 12, plaintext, plaintext_len, aad, aadlen) ? 0 : -1;
    if (ret == 0) memcpy(tag, ciphertext + plaintext_len, 16);
    EVP_AEAD_CTX_cleanup(&ctx);
    return ret;
#elif defined(USE_OPENSSL)
    EVP_CIPHER_CTX *ctx = EVP_CIPHER_CTX_new();
    if (!ctx) return -1;
    int ret = -1, len;
    if (EVP_EncryptInit_ex(ctx, EVP_chacha20_poly1305(), NULL, key, iv) != 1) goto cleanup;
    if (aad && aadlen > 0 && EVP_EncryptUpdate(ctx, NULL, &len, aad, (int)aadlen) != 1) goto cleanup;
    if (EVP_EncryptUpdate(ctx, ciphertext, &len, plaintext, (int)plaintext_len) != 1) goto cleanup;
    if (EVP_EncryptFinal_ex(ctx, ciphertext + len, &len) != 1) goto cleanup;
    if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_AEAD_GET_TAG, 16, tag) != 1) goto cleanup;
    ret = 0;
cleanup:
    EVP_CIPHER_CTX_free(ctx);
    return ret;
#else
    return -1;
#endif
}

int sxr_chacha20_poly1305_decrypt(const uint8_t *key, const uint8_t *iv,
                                   const uint8_t *aad, size_t aadlen,
                                   const uint8_t *ciphertext, size_t ciphertext_len,
                                   const uint8_t *tag, uint8_t *plaintext) {
#if defined(USE_BORINGSSL)
    const EVP_AEAD *aead = EVP_aead_chacha20_poly1305();
    EVP_AEAD_CTX ctx;
    if (!EVP_AEAD_CTX_init(&ctx, aead, key, 32, 16, NULL)) return -2;
    uint8_t *combined = (uint8_t*)malloc(ciphertext_len + 16);
    if (!combined) { EVP_AEAD_CTX_cleanup(&ctx); return -2; }
    memcpy(combined, ciphertext, ciphertext_len);
    memcpy(combined + ciphertext_len, tag, 16);
    size_t out_len;
    int ret = EVP_AEAD_CTX_open(&ctx, plaintext, &out_len, ciphertext_len,
                                 iv, 12, combined, ciphertext_len + 16,
                                 aad, aadlen) ? 0 : -1;
    free(combined);
    EVP_AEAD_CTX_cleanup(&ctx);
    return ret;
#elif defined(USE_OPENSSL)
    EVP_CIPHER_CTX *ctx = EVP_CIPHER_CTX_new();
    if (!ctx) return -2;
    int ret = -1, len;
    if (EVP_DecryptInit_ex(ctx, EVP_chacha20_poly1305(), NULL, key, iv) != 1) goto cleanup;
    if (aad && aadlen > 0 && EVP_DecryptUpdate(ctx, NULL, &len, aad, (int)aadlen) != 1) goto cleanup;
    if (EVP_DecryptUpdate(ctx, plaintext, &len, ciphertext, (int)ciphertext_len) != 1) goto cleanup;
    if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_AEAD_SET_TAG, 16, (void*)tag) != 1) goto cleanup;
    ret = (EVP_DecryptFinal_ex(ctx, plaintext + len, &len) == 1) ? 0 : -1;
cleanup:
    EVP_CIPHER_CTX_free(ctx);
    return ret;
#else
    return -2;
#endif
}

struct sxr_crypto_pool_t { size_t slot_size; size_t slot_count; };
sxr_crypto_pool_t* sxr_crypto_pool_create(size_t sz, size_t cnt) { return nullptr; }
int sxr_crypto_pool_acquire(sxr_crypto_pool_t *p, uint8_t **b, size_t *s) { return -1; }
void sxr_crypto_pool_release(sxr_crypto_pool_t *p, int id) {}
void sxr_crypto_pool_destroy(sxr_crypto_pool_t *p) {}

struct sxr_crypto_queue_t { int worker_count; };
sxr_crypto_queue_t* sxr_crypto_queue_create(int w) { return nullptr; }
int sxr_crypto_queue_submit(sxr_crypto_queue_t *q, const sxr_crypto_job_t *j) { return -1; }
void sxr_crypto_queue_wait(sxr_crypto_queue_t *q) {}
void sxr_crypto_queue_destroy(sxr_crypto_queue_t *q) {}

int sxr_crypto_has_aes_hw() { return (g_cpu_features & (1 << 0)) != 0; }
int sxr_crypto_has_sha_hw() { return (g_cpu_features & ((1 << 1) | (1 << 2))) != 0; }
int sxr_crypto_has_neon() { return (g_cpu_features & (1 << 3)) != 0; }
uint32_t sxr_crypto_get_cpu_features() { return g_cpu_features; }
