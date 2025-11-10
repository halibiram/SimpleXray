package com.simplexray.an.chain.supervisor

import com.simplexray.an.chain.pepper.PepperParams

/**
 * Complete chain configuration
 */
data class ChainConfig(
    val name: String,
    val pepperParams: PepperParams?,
    val xrayConfigPath: String?,
    val tlsMode: TlsMode = TlsMode.AUTO
) {
    enum class TlsMode {
        BORINGSSL,
        CONSCRYPT,
        AUTO
    }
}

