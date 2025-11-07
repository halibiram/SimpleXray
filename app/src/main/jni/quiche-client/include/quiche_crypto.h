/*
 * Hardware-Accelerated Crypto for QUIC
 *
 * Uses BoringSSL with ARM Crypto Extensions for maximum performance:
 * - AES-128-GCM encryption (hardware accelerated)
 * - ChaCha20-Poly1305 (NEON optimized)
 * - Batch processing for multiple packets
 */

#ifndef QUICHE_CRYPTO_H
#define QUICHE_CRYPTO_H

#include <stdint.h>
#include <stddef.h>
#include <memory>

#include <openssl/evp.h>
#include <openssl/aead.h>

namespace quiche_client {

/**
 * Crypto algorithm selection
 */
enum class CryptoAlgorithm {
    AES_128_GCM,        // Hardware AES-128-GCM (fastest on ARM)
    AES_256_GCM,        // Hardware AES-256-GCM
    CHACHA20_POLY1305,  // ChaCha20-Poly1305 (NEON optimized)
};

/**
 * Crypto context for packet encryption/decryption
 */
struct CryptoContext {
    EVP_AEAD_CTX* aead_ctx;
    CryptoAlgorithm algorithm;
    uint8_t key[32];
    size_t key_len;
    uint64_t nonce_counter;
};

/**
 * Hardware crypto capabilities
 */
struct CryptoCapabilities {
    bool has_aes_hardware;      // ARM Crypto Extensions (AES)
    bool has_pmull_hardware;    // ARM PMULL (for GCM)
    bool has_neon;              // ARM NEON SIMD
    bool has_sha_hardware;      // ARM SHA extensions
    const char* cpu_model;
};

/**
 * Hardware-accelerated crypto handler
 */
class QuicheCrypto {
public:
    /**
     * Create crypto handler
     */
    static std::unique_ptr<QuicheCrypto> Create(CryptoAlgorithm algorithm);

    /**
     * Destructor
     */
    ~QuicheCrypto();

    /**
     * Initialize with key
     */
    int Initialize(const uint8_t* key, size_t key_len);

    /**
     * Encrypt single packet
     * Returns: encrypted length, or < 0 on error
     */
    ssize_t Encrypt(
        const uint8_t* plaintext,
        size_t plaintext_len,
        uint8_t* ciphertext,
        size_t ciphertext_capacity,
        const uint8_t* nonce,
        size_t nonce_len
    );

    /**
     * Decrypt single packet
     * Returns: decrypted length, or < 0 on error
     */
    ssize_t Decrypt(
        const uint8_t* ciphertext,
        size_t ciphertext_len,
        uint8_t* plaintext,
        size_t plaintext_capacity,
        const uint8_t* nonce,
        size_t nonce_len
    );

    /**
     * Batch encrypt multiple packets
     * Returns: number of packets encrypted
     */
    int EncryptBatch(
        const uint8_t** plaintexts,
        const size_t* plaintext_lens,
        uint8_t** ciphertexts,
        size_t* ciphertext_lens,
        const uint8_t** nonces,
        size_t nonce_len,
        size_t count
    );

    /**
     * Batch decrypt multiple packets
     * Returns: number of packets decrypted
     */
    int DecryptBatch(
        const uint8_t** ciphertexts,
        const size_t* ciphertext_lens,
        uint8_t** plaintexts,
        size_t* plaintext_lens,
        const uint8_t** nonces,
        size_t nonce_len,
        size_t count
    );

    /**
     * Get hardware capabilities
     */
    static CryptoCapabilities GetCapabilities();

    /**
     * Check if hardware AES is available
     */
    static bool HasHardwareAES();

    /**
     * Get recommended algorithm for current hardware
     */
    static CryptoAlgorithm GetRecommendedAlgorithm();

private:
    QuicheCrypto(CryptoAlgorithm algorithm);

    // Initialize BoringSSL AEAD context
    int InitializeAEAD(const uint8_t* key, size_t key_len);

    // Get EVP_AEAD for algorithm
    const EVP_AEAD* GetAEAD() const;

    // Algorithm
    CryptoAlgorithm algorithm_;

    // AEAD context
    EVP_AEAD_CTX* aead_ctx_ = nullptr;

    // Key
    uint8_t key_[32];
    size_t key_len_ = 0;

    // Nonce counter (for automatic nonce generation)
    uint64_t nonce_counter_ = 0;
};

/**
 * Crypto performance utilities
 */
class CryptoPerf {
public:
    /**
     * Benchmark encryption throughput
     * Returns: throughput in MB/s
     */
    static double BenchmarkEncryption(
        CryptoAlgorithm algorithm,
        size_t packet_size,
        size_t iterations
    );

    /**
     * Benchmark decryption throughput
     * Returns: throughput in MB/s
     */
    static double BenchmarkDecryption(
        CryptoAlgorithm algorithm,
        size_t packet_size,
        size_t iterations
    );

    /**
     * Print crypto capabilities
     */
    static void PrintCapabilities();
};

} // namespace quiche_client

#endif // QUICHE_CRYPTO_H
