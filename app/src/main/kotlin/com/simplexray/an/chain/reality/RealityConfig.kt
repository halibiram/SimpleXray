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
    val localPort: Int = 10809, // Default local SOCKS5 port (10809 to avoid conflict with Xray main port 10808)
    val uuid: String? = null // VLESS UUID (optional, will be generated if not provided)
)

enum class TlsFingerprintProfile {
    CHROME,
    FIREFOX,
    SAFARI,
    EDGE,
    CUSTOM
}

