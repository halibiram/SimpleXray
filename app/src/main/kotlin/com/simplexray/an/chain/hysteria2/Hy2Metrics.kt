package com.simplexray.an.chain.hysteria2

/**
 * Metrics from Hysteria2 QUIC client
 */
data class Hy2Metrics(
    val isConnected: Boolean,
    val bytesUp: Long,
    val bytesDown: Long,
    val rtt: Long, // milliseconds
    val loss: Float, // 0.0-1.0
    val bandwidthUp: Long, // bps
    val bandwidthDown: Long, // bps
    val zeroRttHits: Long,
    val handshakeTimeMs: Long?
)

