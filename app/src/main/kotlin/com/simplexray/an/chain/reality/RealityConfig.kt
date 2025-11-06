package com.simplexray.an.chain.reality

/**
 * Configuration for Reality SOCKS layer
 */
data class RealityConfig(
    val server: String,
    val port: Int,
    val shortId: String,
    val publicKey: String,
    val serverName: String,
    val fingerprintProfile: TlsFingerprintProfile = TlsFingerprintProfile.CHROME,
    val localPort: Int = 10808 // Default local SOCKS5 port
)

enum class TlsFingerprintProfile {
    CHROME,
    FIREFOX,
    SAFARI,
    EDGE,
    CUSTOM
}

