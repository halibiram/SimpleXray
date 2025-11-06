package com.simplexray.an.chain.hysteria2

import java.net.InetSocketAddress

/**
 * Configuration for Hysteria2 QUIC client
 */
data class Hy2Config(
    val server: String,
    val port: Int,
    val auth: String,
    val alpn: String = "h3",
    val upRateMbps: Int = 0, // 0 = auto
    val downRateMbps: Int = 0, // 0 = auto
    val bandwidthProbing: Boolean = true,
    val zeroRttEnabled: Boolean = true,
    val upstreamSocksAddr: InetSocketAddress? = null // Chain to Reality SOCKS
)

