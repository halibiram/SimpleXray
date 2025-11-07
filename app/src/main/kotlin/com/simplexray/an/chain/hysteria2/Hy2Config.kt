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
    val sni: String? = null, // SNI (Server Name Indication) for TLS handshake
    val upRateMbps: Int = 0, // 0 = auto
    val downRateMbps: Int = 0, // 0 = auto
    val bandwidthProbing: Boolean = true,
    val zeroRttEnabled: Boolean = true,
    val insecure: Boolean = false, // Skip certificate verification
    val obfs: String? = null, // Obfuscation type (e.g., "xplus")
    val obfsPassword: String? = null, // Obfuscation password
    val upstreamSocksAddr: InetSocketAddress? = null // Chain to Reality SOCKS
)

