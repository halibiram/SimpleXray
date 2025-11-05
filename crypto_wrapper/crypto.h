#pragma once

/**
 * SimpleXray Crypto Wrapper Layer
 *
 * Central crypto abstraction to encapsulate BoringSSL vs OpenSSL differences.
 * All native code should include this header instead of OpenSSL/BoringSSL headers directly.
 *
 * SECURITY NOTICE:
 * - All functions return 0 on success, negative error code on failure
 * - Buffers must be properly sized by caller
 * - No bounds checking is performed - caller responsibility
 */

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ========================================================================
 * INITIALIZATION & CONFIGURATION
 * ======================================================================== */

/**
 * Initialize crypto subsystem
 * @param mode "boringssl" | "openssl" | "hybrid" | NULL (auto-detect)
 * @return 0 on success, negative error code on failure
 */
int sxr_crypto_init(const char *mode);

/**
 * Cleanup crypto subsystem (call on shutdown)
 */
void sxr_crypto_cleanup(void);

/**
 * Get current crypto backend name
 * @return "boringssl" | "openssl" | "unknown"
 */
const char* sxr_crypto_get_backend(void);

/**
 * Get crypto backend version string
 */
const char* sxr_crypto_get_version(void);

/* ========================================================================
 * RANDOM NUMBER GENERATION
 * ======================================================================== */

/**
 * Generate cryptographically secure random bytes
 * @param buf Output buffer (caller allocated)
 * @param len Number of bytes to generate
 * @return 0 on success, -1 on failure
 */
int sxr_rand_bytes(uint8_t *buf, size_t len);

/**
 * Seed RNG with additional entropy (optional, system does this automatically)
 * @param buf Entropy input
 * @param len Length of entropy
 */
void sxr_rand_add(const uint8_t *buf, size_t len);

/* ========================================================================
 * AES-GCM AEAD ENCRYPTION
 * ======================================================================== */

/**
 * AES-GCM encrypt (one-shot)
 * @param key 16/24/32 bytes (AES-128/192/256)
 * @param keylen Key length in bytes
 * @param iv Initialization vector (12 bytes recommended)
 * @param ivlen IV length in bytes
 * @param aad Additional authenticated data (can be NULL)
 * @param aadlen AAD length
 * @param plaintext Input plaintext
 * @param plaintext_len Plaintext length
 * @param ciphertext Output buffer (must be >= plaintext_len)
 * @param tag Output authentication tag (16 bytes)
 * @param taglen Tag length (12 or 16 bytes recommended)
 * @return 0 on success, negative on failure
 */
int sxr_aes_gcm_encrypt(
    const uint8_t *key, size_t keylen,
    const uint8_t *iv, size_t ivlen,
    const uint8_t *aad, size_t aadlen,
    const uint8_t *plaintext, size_t plaintext_len,
    uint8_t *ciphertext,
    uint8_t *tag, size_t taglen
);

/**
 * AES-GCM decrypt (one-shot)
 * @param key 16/24/32 bytes (AES-128/192/256)
 * @param keylen Key length in bytes
 * @param iv Initialization vector
 * @param ivlen IV length in bytes
 * @param aad Additional authenticated data (can be NULL)
 * @param aadlen AAD length
 * @param ciphertext Input ciphertext
 * @param ciphertext_len Ciphertext length
 * @param tag Authentication tag (16 bytes)
 * @param taglen Tag length
 * @param plaintext Output buffer (must be >= ciphertext_len)
 * @return 0 on success, -1 on auth failure, -2 on other error
 */
int sxr_aes_gcm_decrypt(
    const uint8_t *key, size_t keylen,
    const uint8_t *iv, size_t ivlen,
    const uint8_t *aad, size_t aadlen,
    const uint8_t *ciphertext, size_t ciphertext_len,
    const uint8_t *tag, size_t taglen,
    uint8_t *plaintext
);

/* ========================================================================
 * CHACHA20-POLY1305 AEAD ENCRYPTION
 * ======================================================================== */

/**
 * ChaCha20-Poly1305 encrypt (one-shot)
 * @param key 32 bytes (256-bit key)
 * @param iv 12 bytes nonce (96-bit)
 * @param aad Additional authenticated data (can be NULL)
 * @param aadlen AAD length
 * @param plaintext Input plaintext
 * @param plaintext_len Plaintext length
 * @param ciphertext Output buffer (must be >= plaintext_len)
 * @param tag Output authentication tag (16 bytes)
 * @return 0 on success, negative on failure
 */
int sxr_chacha20_poly1305_encrypt(
    const uint8_t *key,
    const uint8_t *iv,
    const uint8_t *aad, size_t aadlen,
    const uint8_t *plaintext, size_t plaintext_len,
    uint8_t *ciphertext,
    uint8_t *tag
);

/**
 * ChaCha20-Poly1305 decrypt (one-shot)
 * @param key 32 bytes (256-bit key)
 * @param iv 12 bytes nonce (96-bit)
 * @param aad Additional authenticated data (can be NULL)
 * @param aadlen AAD length
 * @param ciphertext Input ciphertext
 * @param ciphertext_len Ciphertext length
 * @param tag Authentication tag (16 bytes)
 * @param plaintext Output buffer (must be >= ciphertext_len)
 * @return 0 on success, -1 on auth failure, -2 on other error
 */
int sxr_chacha20_poly1305_decrypt(
    const uint8_t *key,
    const uint8_t *iv,
    const uint8_t *aad, size_t aadlen,
    const uint8_t *ciphertext, size_t ciphertext_len,
    const uint8_t *tag,
    uint8_t *plaintext
);

/* ========================================================================
 * MEMORY POOL FOR CRYPTO OPERATIONS
 * ======================================================================== */

typedef struct sxr_crypto_pool_t sxr_crypto_pool_t;

/**
 * Create crypto memory pool for zero-allocation hot path
 * @param slot_size Size of each buffer slot
 * @param slot_count Number of slots
 * @return Pool handle, NULL on failure
 */
sxr_crypto_pool_t* sxr_crypto_pool_create(size_t slot_size, size_t slot_count);

/**
 * Acquire buffer from pool (lock-free, may fail if pool exhausted)
 * @param pool Pool handle
 * @param out_buffer Pointer to buffer pointer
 * @param out_size Size of allocated buffer
 * @return Slot ID on success, -1 if pool exhausted
 */
int sxr_crypto_pool_acquire(sxr_crypto_pool_t *pool, uint8_t **out_buffer, size_t *out_size);

/**
 * Release buffer back to pool
 * @param pool Pool handle
 * @param slot_id Slot ID from acquire
 */
void sxr_crypto_pool_release(sxr_crypto_pool_t *pool, int slot_id);

/**
 * Destroy crypto pool
 */
void sxr_crypto_pool_destroy(sxr_crypto_pool_t *pool);

/* ========================================================================
 * ASYNC CRYPTO QUEUE
 * ======================================================================== */

typedef struct sxr_crypto_queue_t sxr_crypto_queue_t;

typedef enum {
    SXR_CRYPTO_OP_AES_GCM_ENC = 1,
    SXR_CRYPTO_OP_AES_GCM_DEC = 2,
    SXR_CRYPTO_OP_CHACHA20_ENC = 3,
    SXR_CRYPTO_OP_CHACHA20_DEC = 4,
} sxr_crypto_op_t;

typedef struct {
    sxr_crypto_op_t op;
    const uint8_t *key;
    size_t keylen;
    const uint8_t *iv;
    size_t ivlen;
    const uint8_t *aad;
    size_t aadlen;
    const uint8_t *input;
    size_t input_len;
    uint8_t *output;
    uint8_t *tag;
    size_t taglen;
    void *user_data;
    void (*completion_cb)(void *user_data, int result);
} sxr_crypto_job_t;

/**
 * Create async crypto queue with worker threads
 * @param worker_count Number of worker threads (0 = auto)
 * @return Queue handle, NULL on failure
 */
sxr_crypto_queue_t* sxr_crypto_queue_create(int worker_count);

/**
 * Submit crypto job to queue (non-blocking)
 * @param queue Queue handle
 * @param job Job descriptor
 * @return 0 on success, -1 if queue full
 */
int sxr_crypto_queue_submit(sxr_crypto_queue_t *queue, const sxr_crypto_job_t *job);

/**
 * Wait for all pending jobs to complete
 */
void sxr_crypto_queue_wait(sxr_crypto_queue_t *queue);

/**
 * Destroy crypto queue (waits for pending jobs)
 */
void sxr_crypto_queue_destroy(sxr_crypto_queue_t *queue);

/* ========================================================================
 * HARDWARE CAPABILITY DETECTION
 * ======================================================================== */

/**
 * Check if AES hardware acceleration is available
 */
int sxr_crypto_has_aes_hw(void);

/**
 * Check if SHA hardware acceleration is available
 */
int sxr_crypto_has_sha_hw(void);

/**
 * Check if NEON is available (ARM)
 */
int sxr_crypto_has_neon(void);

/**
 * Get CPU features bitmask
 * Bits: [0]=AES [1]=SHA1 [2]=SHA2 [3]=NEON [4-31]=reserved
 */
uint32_t sxr_crypto_get_cpu_features(void);

#ifdef __cplusplus
}
#endif
