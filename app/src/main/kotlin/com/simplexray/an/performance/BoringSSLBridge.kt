package com.simplexray.an.performance

import android.util.Log

/**
 * BoringSSL JNI Bridge
 * Provides Java/Kotlin interface to BoringSSL native functions
 */
object BoringSSLBridge {
    private const val TAG = "BoringSSLBridge"
    
    init {
        try {
            System.loadLibrary("perf-net")
            Log.d(TAG, "BoringSSL native library loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load perf-net library", e)
        }
    }
    
    /**
     * Generate random bytes using BoringSSL RAND_bytes
     * @param output ByteArray to fill with random bytes
     * @return 0 on success, -1 on error
     */
    external fun nativeRandBytes(output: ByteArray): Int
    
    /**
     * Create AEAD context for encryption/decryption
     * Supported algorithms: "aes-256-gcm", "aes-128-gcm", "chacha20-poly1305"
     * @param algorithm Cipher algorithm name
     * @return Context handle (non-zero on success), 0 on error
     */
    external fun nativeCreateAeadContext(algorithm: String): Long
    
    /**
     * AEAD encrypt
     * @param ctxHandle Context handle from nativeCreateAeadContext
     * @param key Encryption key (length depends on algorithm)
     * @param nonce Nonce/IV (12 bytes for AES-GCM, 12 bytes for ChaCha20-Poly1305)
     * @param aad Additional authenticated data (optional, can be null)
     * @param plaintext Plaintext to encrypt
     * @return Encrypted ciphertext with tag, or null on error
     */
    external fun nativeAeadEncrypt(
        ctxHandle: Long,
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray?,
        plaintext: ByteArray
    ): ByteArray?
    
    /**
     * AEAD decrypt
     * @param ctxHandle Context handle from nativeCreateAeadContext
     * @param key Encryption key (length depends on algorithm)
     * @param nonce Nonce/IV (12 bytes for AES-GCM, 12 bytes for ChaCha20-Poly1305)
     * @param aad Additional authenticated data (optional, can be null)
     * @param ciphertext Ciphertext with tag to decrypt
     * @return Decrypted plaintext, or null on error
     */
    external fun nativeAeadDecrypt(
        ctxHandle: Long,
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray?,
        ciphertext: ByteArray
    ): ByteArray?
    
    /**
     * Free AEAD context
     * @param ctxHandle Context handle to free
     */
    external fun nativeFreeAeadContext(ctxHandle: Long)
    
    /**
     * Convenience method: Generate random bytes
     */
    fun randBytes(length: Int): ByteArray? {
        val output = ByteArray(length)
        return if (nativeRandBytes(output) == 0) {
            output
        } else {
            null
        }
    }
    
    /**
     * Convenience method: Encrypt with AES-256-GCM
     */
    fun encryptAES256GCM(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray?,
        plaintext: ByteArray
    ): ByteArray? {
        val ctx = nativeCreateAeadContext("aes-256-gcm")
        if (ctx == 0L) {
            Log.e(TAG, "Failed to create AES-256-GCM context")
            return null
        }
        
        try {
            return nativeAeadEncrypt(ctx, key, nonce, aad, plaintext)
        } finally {
            nativeFreeAeadContext(ctx)
        }
    }
    
    /**
     * Convenience method: Decrypt with AES-256-GCM
     */
    fun decryptAES256GCM(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray?,
        ciphertext: ByteArray
    ): ByteArray? {
        val ctx = nativeCreateAeadContext("aes-256-gcm")
        if (ctx == 0L) {
            Log.e(TAG, "Failed to create AES-256-GCM context")
            return null
        }
        
        try {
            return nativeAeadDecrypt(ctx, key, nonce, aad, ciphertext)
        } finally {
            nativeFreeAeadContext(ctx)
        }
    }
}

