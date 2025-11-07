package com.simplexray.an.quiche

import com.simplexray.an.common.AppLogger

/**
 * Hardware-Accelerated Crypto Utilities
 *
 * Provides information about crypto capabilities and performance
 */
object QuicheCrypto {
    private const val TAG = "QuicheCrypto"

    init {
        try {
            System.loadLibrary("quiche-client")
        } catch (e: UnsatisfiedLinkError) {
            AppLogger.e(TAG, "Failed to load QUICHE client library", e)
        }
    }

    /**
     * Get crypto capabilities
     */
    fun getCapabilities(): CryptoCapabilities {
        val values = nativeGetCapabilities() ?: booleanArrayOf(false, false, false, false)

        return CryptoCapabilities(
            hasAesHardware = values[0],
            hasPmullHardware = values[1],
            hasNeon = values[2],
            hasShaHardware = values[3]
        )
    }

    /**
     * Print crypto capabilities (for debugging)
     */
    fun printCapabilities() {
        nativePrintCapabilities()
    }

    @JvmStatic
    private external fun nativeGetCapabilities(): BooleanArray?

    @JvmStatic
    private external fun nativePrintCapabilities()
}

/**
 * Crypto capabilities
 */
data class CryptoCapabilities(
    val hasAesHardware: Boolean,
    val hasPmullHardware: Boolean,
    val hasNeon: Boolean,
    val hasShaHardware: Boolean
) {
    override fun toString(): String {
        return "CryptoCapabilities(" +
                "AES=${if (hasAesHardware) "HW" else "SW"}, " +
                "PMULL=${if (hasPmullHardware) "HW" else "SW"}, " +
                "NEON=${if (hasNeon) "YES" else "NO"}, " +
                "SHA=${if (hasShaHardware) "HW" else "SW"})"
    }
}
