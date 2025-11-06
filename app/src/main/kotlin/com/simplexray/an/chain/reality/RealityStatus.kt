package com.simplexray.an.chain.reality

/**
 * Status of Reality SOCKS layer
 */
data class RealityStatus(
    val isRunning: Boolean,
    val localPort: Int?,
    val connectedClients: Int,
    val bytesUp: Long,
    val bytesDown: Long,
    val handshakeTimeMs: Long?,
    val lastError: String?
)

