package com.simplexray.an.chain

import com.simplexray.an.chain.pepper.PepperParams
import com.simplexray.an.chain.reality.RealityConfig
import com.simplexray.an.chain.reality.TlsFingerprintProfile
import com.simplexray.an.chain.supervisor.ChainConfig
import org.junit.Assert.*
import org.junit.Test

class ChainConfigTest {
    
    @Test
    fun `ChainConfig with all components should be valid`() {
        val config = ChainConfig(
            name = "Test Profile",
            pepperParams = createPepperParams(),
            xrayConfigPath = "xray.json",
            tlsMode = ChainConfig.TlsMode.AUTO
        )
        
        assertNotNull(config)
        assertEquals("Test Profile", config.name)
        assertNotNull(config.pepperParams)
        assertEquals("xray.json", config.xrayConfigPath)
    }
    
    @Test
    fun `ChainConfig with minimal components should be valid`() {
        val config = ChainConfig(
            name = "Minimal Profile",
            pepperParams = null,
            xrayConfigPath = "xray.json",
            tlsMode = ChainConfig.TlsMode.BORINGSSL
        )
        
        assertNotNull(config)
        assertEquals("Minimal Profile", config.name)
        assertNull(config.pepperParams)
    }
    
    @Test
    fun `ChainConfig TLS mode should default to AUTO`() {
        val config = ChainConfig(
            name = "Default TLS",
            pepperParams = null,
            xrayConfigPath = null
        )
        
        assertEquals(ChainConfig.TlsMode.AUTO, config.tlsMode)
    }
    
    private fun createPepperParams(): PepperParams {
        return PepperParams(
            mode = com.simplexray.an.chain.pepper.PepperMode.BURST_FRIENDLY,
            maxBurstBytes = 64 * 1024,
            targetRateBps = 0,
            queueDiscipline = com.simplexray.an.chain.pepper.QueueDiscipline.FQ
        )
    }
}

