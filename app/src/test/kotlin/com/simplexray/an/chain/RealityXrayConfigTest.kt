package com.simplexray.an.chain

import com.google.gson.JsonObject
import com.simplexray.an.chain.reality.RealityConfig
import com.simplexray.an.chain.reality.RealityXrayConfig
import com.simplexray.an.chain.reality.TlsFingerprintProfile
import org.junit.Assert.*
import org.junit.Test

class RealityXrayConfigTest {
    
    @Test
    fun `buildConfig should create valid Xray JSON`() {
        val config = RealityConfig(
            server = "example.com",
            port = 443,
            shortId = "test-id",
            publicKey = "test-public-key",
            serverName = "example.com",
            fingerprintProfile = TlsFingerprintProfile.CHROME,
            localPort = 10808
        )
        
        val xrayConfig = RealityXrayConfig.buildConfig(config)
        
        assertNotNull(xrayConfig)
        assertTrue(xrayConfig.has("inbounds"))
        assertTrue(xrayConfig.has("outbounds"))
        assertTrue(xrayConfig.has("routing"))
    }
    
    @Test
    fun `buildConfig should include SOCKS5 inbound`() {
        val config = createTestConfig()
        val xrayConfig = RealityXrayConfig.buildConfig(config)
        
        val inbounds = xrayConfig.getAsJsonArray("inbounds")
        assertNotNull(inbounds)
        assertTrue(inbounds.size() > 0)
        
        val socksInbound = inbounds[0].asJsonObject
        assertEquals("socks", socksInbound.get("protocol").asString)
        assertEquals(10808, socksInbound.get("port").asInt)
        assertEquals("127.0.0.1", socksInbound.get("listen").asString)
    }
    
    @Test
    fun `buildConfig should include REALITY outbound`() {
        val config = createTestConfig()
        val xrayConfig = RealityXrayConfig.buildConfig(config)
        
        val outbounds = xrayConfig.getAsJsonArray("outbounds")
        assertNotNull(outbounds)
        
        val realityOutbound = outbounds.firstOrNull { outbound ->
            outbound.asJsonObject.get("tag")?.asString == "reality-out"
        }?.asJsonObject
        
        assertNotNull(realityOutbound)
        assertEquals("vless", realityOutbound?.get("protocol")?.asString)
        
        val streamSettings = realityOutbound?.getAsJsonObject("streamSettings")
        assertNotNull(streamSettings)
        assertEquals("reality", streamSettings?.get("security")?.asString)
    }
    
    @Test
    fun `buildConfig should map fingerprint profiles correctly`() {
        val profiles = mapOf(
            TlsFingerprintProfile.CHROME to "chrome",
            TlsFingerprintProfile.FIREFOX to "firefox",
            TlsFingerprintProfile.SAFARI to "safari",
            TlsFingerprintProfile.EDGE to "edge",
            TlsFingerprintProfile.CUSTOM to "random"
        )
        
        profiles.forEach { (profile, expected) ->
            val config = createTestConfig(fingerprintProfile = profile)
            val xrayConfig = RealityXrayConfig.buildConfig(config)
            
            val outbounds = xrayConfig.getAsJsonArray("outbounds")
            val realityOutbound = outbounds.firstOrNull { 
                it.asJsonObject.get("tag")?.asString == "reality-out"
            }?.asJsonObject
            
            val streamSettings = realityOutbound?.getAsJsonObject("streamSettings")
            val fingerprint = streamSettings?.get("fingerprint")?.asString
            
            assertEquals("Fingerprint for $profile", expected, fingerprint)
        }
    }
    
    private fun createTestConfig(
        fingerprintProfile: TlsFingerprintProfile = TlsFingerprintProfile.CHROME
    ): RealityConfig {
        return RealityConfig(
            server = "example.com",
            port = 443,
            shortId = "test-id",
            publicKey = "test-key",
            serverName = "example.com",
            fingerprintProfile = fingerprintProfile,
            localPort = 10808
        )
    }
}

