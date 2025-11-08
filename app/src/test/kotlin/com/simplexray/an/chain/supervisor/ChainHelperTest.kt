package com.simplexray.an.chain.supervisor

import com.simplexray.an.chain.reality.RealityConfig
import com.simplexray.an.chain.reality.TlsFingerprintProfile
import com.simplexray.an.chain.hysteria2.Hy2Config
import com.simplexray.an.chain.pepper.PepperParams
import com.simplexray.an.chain.pepper.PepperMode
import com.simplexray.an.chain.pepper.QueueDiscipline
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for ChainHelper validation and utility functions
 */
class ChainHelperTest {
    
    @Test
    fun `validateChainConfig should reject config with no components`() {
        val config = ChainConfig(
            name = "Empty Config",
            realityConfig = null,
            hysteria2Config = null,
            pepperParams = null,
            xrayConfigPath = null,
            tlsMode = ChainConfig.TlsMode.AUTO
        )
        
        val result = ChainHelper.validateChainConfig(config)
        assertFalse(result.isValid, "Config with no components should be invalid")
        assertTrue(result.errors.isNotEmpty(), "Should have validation errors")
    }
    
    @Test
    fun `validateChainConfig should accept config with Xray path`() {
        val config = ChainConfig(
            name = "Xray Only",
            realityConfig = null,
            hysteria2Config = null,
            pepperParams = null,
            xrayConfigPath = "xray.json",
            tlsMode = ChainConfig.TlsMode.AUTO
        )
        
        val result = ChainHelper.validateChainConfig(config)
        assertTrue(result.isValid, "Config with Xray path should be valid")
    }
    
    @Test
    fun `validateChainConfig should reject Reality config with empty server`() {
        val realityConfig = RealityConfig(
            server = "", // Empty server
            port = 443,
            shortId = "test",
            publicKey = "test-key",
            serverName = "example.com",
            fingerprintProfile = TlsFingerprintProfile.CHROME
        )
        
        val config = ChainConfig(
            name = "Invalid Reality",
            realityConfig = realityConfig,
            hysteria2Config = null,
            pepperParams = null,
            xrayConfigPath = null,
            tlsMode = ChainConfig.TlsMode.AUTO
        )
        
        val result = ChainHelper.validateChainConfig(config)
        assertFalse(result.isValid, "Reality config with empty server should be invalid")
        assertTrue(result.errors.any { it.contains("server") }, "Should have server validation error")
    }
    
    @Test
    fun `validateChainConfig should reject Reality config with invalid port`() {
        val realityConfig = RealityConfig(
            server = "example.com",
            port = 70000, // Invalid port (> 65535)
            shortId = "test",
            publicKey = "test-key",
            serverName = "example.com",
            fingerprintProfile = TlsFingerprintProfile.CHROME
        )
        
        val config = ChainConfig(
            name = "Invalid Port",
            realityConfig = realityConfig,
            hysteria2Config = null,
            pepperParams = null,
            xrayConfigPath = null,
            tlsMode = ChainConfig.TlsMode.AUTO
        )
        
        val result = ChainHelper.validateChainConfig(config)
        assertFalse(result.isValid, "Reality config with invalid port should be invalid")
        assertTrue(result.errors.any { it.contains("port") }, "Should have port validation error")
    }
    
    @Test
    fun `validateChainConfig should reject Reality config with empty publicKey`() {
        val realityConfig = RealityConfig(
            server = "example.com",
            port = 443,
            shortId = "test",
            publicKey = "", // Empty publicKey
            serverName = "example.com",
            fingerprintProfile = TlsFingerprintProfile.CHROME
        )
        
        val config = ChainConfig(
            name = "Empty PublicKey",
            realityConfig = realityConfig,
            hysteria2Config = null,
            pepperParams = null,
            xrayConfigPath = null,
            tlsMode = ChainConfig.TlsMode.AUTO
        )
        
        val result = ChainHelper.validateChainConfig(config)
        assertFalse(result.isValid, "Reality config with empty publicKey should be invalid")
        assertTrue(result.errors.any { it.contains("publicKey") }, "Should have publicKey validation error")
    }
    
    @Test
    fun `validateChainConfig should reject Hysteria2 config with empty server`() {
        val hy2Config = Hy2Config(
            server = "", // Empty server
            port = 443,
            auth = "test-auth"
        )
        
        val config = ChainConfig(
            name = "Invalid Hysteria2",
            realityConfig = null,
            hysteria2Config = hy2Config,
            pepperParams = null,
            xrayConfigPath = "xray.json",
            tlsMode = ChainConfig.TlsMode.AUTO
        )
        
        val result = ChainHelper.validateChainConfig(config)
        assertFalse(result.isValid, "Hysteria2 config with empty server should be invalid")
        assertTrue(result.errors.any { it.contains("Hysteria2") && it.contains("server") }, 
            "Should have Hysteria2 server validation error")
    }
    
    @Test
    fun `validateChainConfig should reject Hysteria2 config with empty auth`() {
        val hy2Config = Hy2Config(
            server = "example.com",
            port = 443,
            auth = "" // Empty auth
        )
        
        val config = ChainConfig(
            name = "Empty Auth",
            realityConfig = null,
            hysteria2Config = hy2Config,
            pepperParams = null,
            xrayConfigPath = "xray.json",
            tlsMode = ChainConfig.TlsMode.AUTO
        )
        
        val result = ChainHelper.validateChainConfig(config)
        assertFalse(result.isValid, "Hysteria2 config with empty auth should be invalid")
        assertTrue(result.errors.any { it.contains("auth") }, "Should have auth validation error")
    }
    
    @Test
    fun `validateChainConfig should accept valid Reality config`() {
        val realityConfig = RealityConfig(
            server = "example.com",
            port = 443,
            shortId = "test",
            publicKey = "test-public-key",
            serverName = "example.com",
            fingerprintProfile = TlsFingerprintProfile.CHROME,
            localPort = 10808
        )
        
        val config = ChainConfig(
            name = "Valid Reality",
            realityConfig = realityConfig,
            hysteria2Config = null,
            pepperParams = null,
            xrayConfigPath = null,
            tlsMode = ChainConfig.TlsMode.AUTO
        )
        
        val result = ChainHelper.validateChainConfig(config)
        assertTrue(result.isValid, "Valid Reality config should pass validation")
        assertTrue(result.errors.isEmpty(), "Should have no validation errors")
    }
    
    @Test
    fun `validateChainConfig should accept config with all components`() {
        val realityConfig = RealityConfig(
            server = "example.com",
            port = 443,
            shortId = "test",
            publicKey = "test-key",
            serverName = "example.com",
            fingerprintProfile = TlsFingerprintProfile.CHROME
        )
        
        val hy2Config = Hy2Config(
            server = "hysteria.example.com",
            port = 443,
            auth = "test-auth"
        )
        
        val pepperParams = PepperParams(
            mode = PepperMode.BURST_FRIENDLY,
            maxBurstBytes = 64 * 1024,
            targetRateBps = 10_000_000L,
            queueDiscipline = QueueDiscipline.FQ,
            lossAwareBackoff = true,
            enablePacing = true
        )
        
        val config = ChainConfig(
            name = "Full Chain",
            realityConfig = realityConfig,
            hysteria2Config = hy2Config,
            pepperParams = pepperParams,
            xrayConfigPath = "xray.json",
            tlsMode = ChainConfig.TlsMode.BORINGSSL
        )
        
        val result = ChainHelper.validateChainConfig(config)
        assertTrue(result.isValid, "Full chain config should be valid")
    }
    
    @Test
    fun `getChainStatusSummary should return summary string`() {
        val config = ChainConfig(
            name = "Test Chain",
            realityConfig = RealityConfig(
                server = "example.com",
                port = 443,
                shortId = "test",
                publicKey = "key",
                serverName = "example.com"
            ),
            hysteria2Config = Hy2Config(
                server = "hysteria.example.com",
                port = 443,
                auth = "auth"
            ),
            pepperParams = PepperParams(),
            xrayConfigPath = "xray.json",
            tlsMode = ChainConfig.TlsMode.AUTO
        )
        
        val summary = ChainHelper.getChainStatusSummary(config)
        assertTrue(summary.isNotEmpty(), "Summary should not be empty")
        assertTrue(summary.contains("Reality"), "Summary should include Reality status")
        assertTrue(summary.contains("Hysteria2"), "Summary should include Hysteria2 status")
        assertTrue(summary.contains("PepperShaper"), "Summary should include PepperShaper status")
        assertTrue(summary.contains("Xray"), "Summary should include Xray status")
    }
}





