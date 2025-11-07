/*
 * Hardware-Accelerated Crypto Implementation
 * Uses BoringSSL with ARM Crypto Extensions
 */

#include "quiche_crypto.h"
#include "quiche_utils.h"

#include <openssl/crypto.h>
#include <openssl/err.h>
#include <cstring>

#ifdef __aarch64__
#include <arm_neon.h>
#endif

#define LOG_TAG "QuicheCrypto"

namespace quiche_client {

// QuicheCrypto implementation

QuicheCrypto::QuicheCrypto(CryptoAlgorithm algorithm)
    : algorithm_(algorithm) {
}

QuicheCrypto::~QuicheCrypto() {
    if (aead_ctx_) {
        EVP_AEAD_CTX_cleanup(aead_ctx_);
        delete aead_ctx_;
        aead_ctx_ = nullptr;
    }
}

std::unique_ptr<QuicheCrypto> QuicheCrypto::Create(CryptoAlgorithm algorithm) {
    auto crypto = std::unique_ptr<QuicheCrypto>(new QuicheCrypto(algorithm));

    LOGI(LOG_TAG, "Created crypto handler (algorithm=%d)", algorithm);
    return crypto;
}

int QuicheCrypto::Initialize(const uint8_t* key, size_t key_len) {
    if (key_len > sizeof(key_)) {
        LOGE(LOG_TAG, "Key too large");
        return -1;
    }

    memcpy(key_, key, key_len);
    key_len_ = key_len;

    if (InitializeAEAD(key, key_len) != 0) {
        LOGE(LOG_TAG, "Failed to initialize AEAD");
        return -1;
    }

    LOGI(LOG_TAG, "Crypto initialized (key_len=%zu)", key_len);
    return 0;
}

int QuicheCrypto::InitializeAEAD(const uint8_t* key, size_t key_len) {
    const EVP_AEAD* aead = GetAEAD();
    if (!aead) {
        LOGE(LOG_TAG, "Failed to get AEAD");
        return -1;
    }

    aead_ctx_ = new EVP_AEAD_CTX();
    if (!aead_ctx_) {
        LOGE(LOG_TAG, "Failed to allocate AEAD context");
        return -1;
    }

    if (EVP_AEAD_CTX_init(aead_ctx_, aead, key, key_len,
                          EVP_AEAD_DEFAULT_TAG_LENGTH, nullptr) != 1) {
        LOGE(LOG_TAG, "EVP_AEAD_CTX_init failed");
        delete aead_ctx_;
        aead_ctx_ = nullptr;
        return -1;
    }

    return 0;
}

const EVP_AEAD* QuicheCrypto::GetAEAD() const {
    switch (algorithm_) {
        case CryptoAlgorithm::AES_128_GCM:
            return EVP_aead_aes_128_gcm();
        case CryptoAlgorithm::AES_256_GCM:
            return EVP_aead_aes_256_gcm();
        case CryptoAlgorithm::CHACHA20_POLY1305:
            return EVP_aead_chacha20_poly1305();
        default:
            return nullptr;
    }
}

ssize_t QuicheCrypto::Encrypt(
    const uint8_t* plaintext,
    size_t plaintext_len,
    uint8_t* ciphertext,
    size_t ciphertext_capacity,
    const uint8_t* nonce,
    size_t nonce_len) {

    if (!aead_ctx_) {
        return -1;
    }

    size_t out_len;
    if (EVP_AEAD_CTX_seal(aead_ctx_, ciphertext, &out_len, ciphertext_capacity,
                         nonce, nonce_len, plaintext, plaintext_len,
                         nullptr, 0) != 1) {
        return -1;
    }

    return out_len;
}

ssize_t QuicheCrypto::Decrypt(
    const uint8_t* ciphertext,
    size_t ciphertext_len,
    uint8_t* plaintext,
    size_t plaintext_capacity,
    const uint8_t* nonce,
    size_t nonce_len) {

    if (!aead_ctx_) {
        return -1;
    }

    size_t out_len;
    if (EVP_AEAD_CTX_open(aead_ctx_, plaintext, &out_len, plaintext_capacity,
                         nonce, nonce_len, ciphertext, ciphertext_len,
                         nullptr, 0) != 1) {
        return -1;
    }

    return out_len;
}

int QuicheCrypto::EncryptBatch(
    const uint8_t** plaintexts,
    const size_t* plaintext_lens,
    uint8_t** ciphertexts,
    size_t* ciphertext_lens,
    const uint8_t** nonces,
    size_t nonce_len,
    size_t count) {

    int encrypted = 0;

    for (size_t i = 0; i < count; i++) {
        ssize_t len = Encrypt(plaintexts[i], plaintext_lens[i],
                             ciphertexts[i], 65536,  // Max size
                             nonces[i], nonce_len);

        if (len > 0) {
            ciphertext_lens[i] = len;
            encrypted++;
        } else {
            ciphertext_lens[i] = 0;
        }
    }

    return encrypted;
}

int QuicheCrypto::DecryptBatch(
    const uint8_t** ciphertexts,
    const size_t* ciphertext_lens,
    uint8_t** plaintexts,
    size_t* plaintext_lens,
    const uint8_t** nonces,
    size_t nonce_len,
    size_t count) {

    int decrypted = 0;

    for (size_t i = 0; i < count; i++) {
        ssize_t len = Decrypt(ciphertexts[i], ciphertext_lens[i],
                             plaintexts[i], 65536,  // Max size
                             nonces[i], nonce_len);

        if (len > 0) {
            plaintext_lens[i] = len;
            decrypted++;
        } else {
            plaintext_lens[i] = 0;
        }
    }

    return decrypted;
}

CryptoCapabilities QuicheCrypto::GetCapabilities() {
    CryptoCapabilities caps;

#ifdef __aarch64__
    // Check ARM Crypto Extensions
    caps.has_aes_hardware = CRYPTO_is_ARMv8_AES_capable();
    caps.has_pmull_hardware = CRYPTO_is_ARMv8_PMULL_capable();
    caps.has_neon = CRYPTO_is_NEON_capable();
    caps.has_sha_hardware = CRYPTO_is_ARMv8_SHA1_capable() ||
                            CRYPTO_is_ARMv8_SHA256_capable();
    caps.cpu_model = "ARM64";
#else
    caps.has_aes_hardware = false;
    caps.has_pmull_hardware = false;
    caps.has_neon = false;
    caps.has_sha_hardware = false;
    caps.cpu_model = "Unknown";
#endif

    return caps;
}

bool QuicheCrypto::HasHardwareAES() {
#ifdef __aarch64__
    return CRYPTO_is_ARMv8_AES_capable();
#else
    return false;
#endif
}

CryptoAlgorithm QuicheCrypto::GetRecommendedAlgorithm() {
    // Prefer hardware AES-128-GCM if available (fastest)
    if (HasHardwareAES()) {
        LOGI(LOG_TAG, "Hardware AES available, using AES-128-GCM");
        return CryptoAlgorithm::AES_128_GCM;
    }

    // Fallback to ChaCha20-Poly1305 (NEON optimized)
    LOGI(LOG_TAG, "No hardware AES, using ChaCha20-Poly1305");
    return CryptoAlgorithm::CHACHA20_POLY1305;
}

void CryptoPerf::PrintCapabilities() {
    CryptoCapabilities caps = QuicheCrypto::GetCapabilities();

    LOGI(LOG_TAG, "=== Crypto Capabilities ===");
    LOGI(LOG_TAG, "CPU Model: %s", caps.cpu_model);
    LOGI(LOG_TAG, "Hardware AES: %s", caps.has_aes_hardware ? "YES" : "NO");
    LOGI(LOG_TAG, "Hardware PMULL: %s", caps.has_pmull_hardware ? "YES" : "NO");
    LOGI(LOG_TAG, "NEON SIMD: %s", caps.has_neon ? "YES" : "NO");
    LOGI(LOG_TAG, "Hardware SHA: %s", caps.has_sha_hardware ? "YES" : "NO");
    LOGI(LOG_TAG, "===========================");
}

double CryptoPerf::BenchmarkEncryption(
    CryptoAlgorithm algorithm,
    size_t packet_size,
    size_t iterations) {

    auto crypto = QuicheCrypto::Create(algorithm);

    uint8_t key[32] = {0};
    crypto->Initialize(key, 32);

    uint8_t plaintext[packet_size];
    uint8_t ciphertext[packet_size + 16];
    uint8_t nonce[12] = {0};

    memset(plaintext, 0xAA, sizeof(plaintext));

    uint64_t start = TimeUtils::GetTimestampUs();

    for (size_t i = 0; i < iterations; i++) {
        crypto->Encrypt(plaintext, packet_size, ciphertext, sizeof(ciphertext),
                       nonce, sizeof(nonce));
    }

    uint64_t elapsed = TimeUtils::GetTimestampUs() - start;

    double mbps = (packet_size * iterations * 8.0) / elapsed;

    return mbps;
}

} // namespace quiche_client
