/*
 * Crypto Adapter Layer - OpenSSL to BoringSSL Migration
 * Provides compatibility layer and abstraction for crypto operations
 */

#ifndef CRYPTO_ADAPTER_H
#define CRYPTO_ADAPTER_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// BoringSSL compatibility defines
#define USE_BORINGSSL 1

// Hardware acceleration detection
typedef struct {
    int has_neon;
    int has_crypto_extensions;
    int use_hw_aes;
    int use_chacha_fallback;
} CryptoCapabilities;

/**
 * Initialize crypto adapter and detect hardware capabilities
 */
void crypto_adapter_init(CryptoCapabilities* caps);

/**
 * Check if ARMv8 Crypto Extensions are available
 */
int crypto_adapter_has_crypto_extensions(void);

/**
 * Check if NEON is available
 */
int crypto_adapter_has_neon(void);

/**
 * Get recommended cipher (AES-GCM if hardware available, ChaCha20-Poly1305 otherwise)
 */
const char* crypto_adapter_get_recommended_cipher(const CryptoCapabilities* caps);

/**
 * Generate random bytes using BoringSSL RAND_bytes
 * Returns 1 on success, 0 on error
 */
int crypto_adapter_rand_bytes(uint8_t* buf, size_t len);

/**
 * HKDF-SHA256 key derivation
 * Returns 1 on success, 0 on error
 */
int crypto_adapter_hkdf_sha256(
    const uint8_t* salt, size_t salt_len,
    const uint8_t* ikm, size_t ikm_len,
    const uint8_t* info, size_t info_len,
    uint8_t* out, size_t out_len
);

/**
 * ECDH key exchange (X25519)
 * Returns 1 on success, 0 on error
 */
int crypto_adapter_ecdh_x25519(
    const uint8_t* private_key,
    const uint8_t* public_key,
    uint8_t* shared_secret
);

#ifdef __cplusplus
}
#endif

#endif // CRYPTO_ADAPTER_H

