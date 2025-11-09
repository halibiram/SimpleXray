package com.simplexray.an.chain.supervisor

import com.simplexray.an.chain.pepper.PepperParams
import com.simplexray.an.chain.reality.RealityConfig

/**
 * Complete chain configuration
 */
data class ChainConfig(
    val name: String,
    val realityConfig: RealityConfig?,
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

