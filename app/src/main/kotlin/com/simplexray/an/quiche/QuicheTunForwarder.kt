package com.simplexray.an.quiche

import com.simplexray.an.common.AppLogger

/**
 * TUN to QUIC Forwarder - Zero-Copy Packet Processing
 *
 * Forwards IP packets from TUN device to QUIC connection
 * with maximum performance optimizations
 */
class QuicheTunForwarder private constructor(
    private val handle: Long
) : AutoCloseable {

    companion object {
        private const val TAG = "QuicheTunForwarder"

        init {
            try {
                System.loadLibrary("quiche-client")
            } catch (e: UnsatisfiedLinkError) {
                AppLogger.e("$TAG: Failed to load QUICHE client library", e)
            }
        }

        /**
         * Create TUN forwarder
         */
        fun create(
            tunFd: Int,
            quicClient: QuicheClient,
            batchSize: Int = 64,
            useGSO: Boolean = true,
            useGRO: Boolean = true
        ): QuicheTunForwarder? {
            val handle = nativeCreate(
                tunFd,
                quicClient.getHandle(),
                batchSize,
                useGSO,
                useGRO
            )

            if (handle == 0L) {
                AppLogger.e("$TAG: Failed to create TUN forwarder")
                return null
            }

            return QuicheTunForwarder(handle)
        }

        // Remove @JvmStatic to fix JNI name mangling issue (same as QuicheClient)
        private external fun nativeCreate(
            tunFd: Int,
            clientHandle: Long,
            batchSize: Int,
            useGSO: Boolean,
            useGRO: Boolean
        ): Long

        private external fun nativeStart(handle: Long): Int

        private external fun nativeStop(handle: Long)

        private external fun nativeDestroy(handle: Long)

        private external fun nativeGetStats(handle: Long): LongArray?
    }

    /**
     * Start forwarding
     */
    fun start(): Boolean {
        val result = nativeStart(handle)
        if (result != 0) {
            AppLogger.e("$TAG: Failed to start forwarder: $result")
            return false
        }

        AppLogger.i("$TAG: TUN forwarder started")
        return true
    }

    /**
     * Stop forwarding
     */
    fun stop() {
        nativeStop(handle)
        AppLogger.i("$TAG: TUN forwarder stopped")
    }

    /**
     * Get statistics
     */
    fun getStats(): ForwarderStats? {
        val values = nativeGetStats(handle) ?: return null

        return ForwarderStats(
            packetsReceived = values[0],
            packetsSent = values[1],
            packetsDropped = values[2],
            bytesReceived = values[3],
            bytesSent = values[4]
        )
    }

    override fun close() {
        stop()
        nativeDestroy(handle)
        AppLogger.i("$TAG: TUN forwarder destroyed")
    }
}

/**
 * Forwarder statistics
 */
data class ForwarderStats(
    val packetsReceived: Long,
    val packetsSent: Long,
    val packetsDropped: Long,
    val bytesReceived: Long,
    val bytesSent: Long
) {
    val packetLossRate: Double
        get() = if (packetsReceived > 0) {
            packetsDropped.toDouble() / packetsReceived.toDouble()
        } else {
            0.0
        }

    override fun toString(): String {
        return "ForwarderStats(rx=$packetsReceived pkts, " +
                "tx=$packetsSent pkts, " +
                "dropped=$packetsDropped pkts, " +
                "loss=${"%.3f".format(packetLossRate * 100)}%)"
    }
}
