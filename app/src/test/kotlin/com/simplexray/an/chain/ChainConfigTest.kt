package com.simplexray.an.chain

import com.simplexray.an.chain.hysteria2.Hy2Config
import com.simplexray.an.chain.pepper.PepperParams
import com.simplexray.an.chain.reality.RealityConfig
import com.simplexray.an.chain.reality.TlsFingerprintProfile
import com.simplexray.an.chain.supervisor.ChainConfig
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress

class ChainConfigTest {
    
    @Test
    fun `ChainConfig with all components should be valid`() {
        val config = ChainConfig(
            name = "Test Profile",
            realityConfig = createRealityConfig(),
            hysteria2Config = createHy2Config(),
            pepperParams = createPepperParams(),
            xrayConfigPath = "xray.json",
            tlsMode = ChainConfig.TlsMode.AUTO
        )
        
        assertNotNull(config)
        assertEquals("Test Profile", config.name)
        assertNotNull(config.realityConfig)
        assertNotNull(config.hysteria2Config)
        assertNotNull(config.pepperParams)
        assertEquals("xray.json", config.xrayConfigPath)
    }
    
    @Test
    fun `ChainConfig with minimal components should be valid`() {
        val config = ChainConfig(
            name = "Minimal Profile",
            realityConfig = null,
            hysteria2Config = null,
            pepperParams = null,
            xrayConfigPath = "xray.json",
            tlsMode = ChainConfig.TlsMode.BORINGSSL
        )
        
        assertNotNull(config)
        assertEquals("Minimal Profile", config.name)
        assertNull(config.realityConfig)
        assertNull(config.hysteria2Config)
        assertNull(config.pepperParams)
    }
    
    @Test
    fun `ChainConfig TLS mode should default to AUTO`() {
        val config = ChainConfig(
            name = "Default TLS",
            realityConfig = null,
            hysteria2Config = null,
            pepperParams = null,
            xrayConfigPath = null
        )
        
        assertEquals(ChainConfig.TlsMode.AUTO, config.tlsMode)
    }
    
    private fun createRealityConfig(): RealityConfig {
        return RealityConfig(
            server = "example.com",
            port = 443,
            shortId = "test-id",
            publicKey = "test-key",
            serverName = "example.com",
            fingerprintProfile = TlsFingerprintProfile.CHROME,
            localPort = 10808
        )
    }
    
    private fun createHy2Config(): Hy2Config {
        return Hy2Config(
            server = "example.com",
            port = 443,
            auth = "test-auth",
            alpn = "h3",
            upstreamSocksAddr = InetSocketAddress("127.0.0.1", 10808)
        )
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

