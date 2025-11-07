package com.simplexray.an.quiche

import com.simplexray.an.common.AppLogger

/**
 * QUICHE Native Client - High-Performance QUIC Implementation
 *
 * Uses Cloudflare QUICHE + BoringSSL for maximum performance
 */
class QuicheClient private constructor(
    private val handle: Long
) : AutoCloseable {

    companion object {
        private const val TAG = "QuicheClient"

        init {
            try {
                System.loadLibrary("quiche-client")
                AppLogger.i(TAG, "QUICHE client library loaded")
            } catch (e: UnsatisfiedLinkError) {
                AppLogger.e(TAG, "Failed to load QUICHE client library", e)
            }
        }

        /**
         * Create QUIC client
         */
        fun create(
            serverHost: String,
            serverPort: Int,
            congestionControl: CongestionControl = CongestionControl.BBR2,
            enableZeroCopy: Boolean = true,
            cpuAffinity: CpuAffinity = CpuAffinity.BIG_CORES
        ): QuicheClient? {
            val handle = nativeCreate(
                serverHost,
                serverPort,
                congestionControl.ordinal,
                enableZeroCopy,
                cpuAffinity.ordinal
            )

            if (handle == 0L) {
                AppLogger.e(TAG, "Failed to create QUIC client")
                return null
            }

            return QuicheClient(handle)
        }

        @JvmStatic
        private external fun nativeCreate(
            serverHost: String,
            serverPort: Int,
            congestionControl: Int,
            enableZeroCopy: Boolean,
            cpuAffinity: Int
        ): Long

        @JvmStatic
        private external fun nativeConnect(handle: Long): Int

        @JvmStatic
        private external fun nativeDisconnect(handle: Long)

        @JvmStatic
        private external fun nativeDestroy(handle: Long)

        @JvmStatic
        private external fun nativeIsConnected(handle: Long): Boolean

        @JvmStatic
        private external fun nativeSend(handle: Long, data: ByteArray): Int

        @JvmStatic
        private external fun nativeGetMetrics(handle: Long): DoubleArray?
    }

    /**
     * Connect to QUIC server
     */
    fun connect(): Boolean {
        val result = nativeConnect(handle)
        if (result != 0) {
            AppLogger.e(TAG, "Failed to connect: $result")
            return false
        }

        AppLogger.i(TAG, "Connected to QUIC server")
        return true
    }

    /**
     * Disconnect from server
     */
    fun disconnect() {
        nativeDisconnect(handle)
        AppLogger.i(TAG, "Disconnected from server")
    }

    /**
     * Check if connected
     */
    fun isConnected(): Boolean {
        return nativeIsConnected(handle)
    }

    /**
     * Send data
     */
    fun send(data: ByteArray): Int {
        return nativeSend(handle, data)
    }

    /**
     * Get metrics
     */
    fun getMetrics(): QuicMetrics? {
        val values = nativeGetMetrics(handle) ?: return null

        return QuicMetrics(
            throughputMbps = values[0],
            rttUs = values[1].toLong(),
            packetLossRate = values[2],
            bytesSent = values[3].toLong(),
            bytesReceived = values[4].toLong(),
            packetsSent = values[5].toLong(),
            packetsReceived = values[6].toLong(),
            cwnd = values[7].toLong()
        )
    }

    /**
     * Get native handle (for TUN forwarder)
     */
    internal fun getHandle(): Long = handle

    override fun close() {
        disconnect()
        nativeDestroy(handle)
        AppLogger.i(TAG, "QUIC client destroyed")
    }
}

/**
 * Congestion control algorithms
 */
enum class CongestionControl {
    RENO,
    CUBIC,
    BBR,
    BBR2  // Recommended for mobile
}

/**
 * CPU affinity modes
 */
enum class CpuAffinity {
    NONE,
    BIG_CORES,      // High performance cores (4-7)
    LITTLE_CORES,   // Efficiency cores (0-3)
    CUSTOM
}

/**
 * QUIC connection metrics
 */
data class QuicMetrics(
    val throughputMbps: Double,
    val rttUs: Long,
    val packetLossRate: Double,
    val bytesSent: Long,
    val bytesReceived: Long,
    val packetsSent: Long,
    val packetsReceived: Long,
    val cwnd: Long
) {
    override fun toString(): String {
        return "QuicMetrics(throughput=${"%.2f".format(throughputMbps)} Mbps, " +
                "rtt=${rttUs / 1000} ms, " +
                "loss=${"%.3f".format(packetLossRate * 100)}%, " +
                "sent=$bytesSent bytes, " +
                "received=$bytesReceived bytes)"
    }
}
