package com.simplexray.an.chain

import com.simplexray.an.chain.reality.RealityConfig
import com.simplexray.an.chain.reality.TlsFingerprintProfile
import org.junit.Assert.*
import org.junit.Test

class RealityConfigTest {
    
    @Test
    fun `RealityConfig with valid parameters should be created`() {
        val config = RealityConfig(
            server = "example.com",
            port = 443,
            shortId = "abc123",
            publicKey = "test-public-key",
            serverName = "example.com",
            fingerprintProfile = TlsFingerprintProfile.CHROME,
            localPort = 10808
        )
        
        assertEquals("example.com", config.server)
        assertEquals(443, config.port)
        assertEquals("abc123", config.shortId)
        assertEquals("test-public-key", config.publicKey)
        assertEquals("example.com", config.serverName)
        assertEquals(TlsFingerprintProfile.CHROME, config.fingerprintProfile)
        assertEquals(10808, config.localPort)
    }
    
    @Test
    fun `RealityConfig should default to CHROME fingerprint and port 10808`() {
        val config = RealityConfig(
            server = "example.com",
            port = 443,
            shortId = "test",
            publicKey = "key",
            serverName = "example.com"
        )
        
        assertEquals(TlsFingerprintProfile.CHROME, config.fingerprintProfile)
        assertEquals(10808, config.localPort)
    }
    
    @Test
    fun `RealityConfig should support all fingerprint profiles`() {
        val profiles = listOf(
            TlsFingerprintProfile.CHROME,
            TlsFingerprintProfile.FIREFOX,
            TlsFingerprintProfile.SAFARI,
            TlsFingerprintProfile.EDGE,
            TlsFingerprintProfile.CUSTOM
        )
        
        profiles.forEach { profile ->
            val config = RealityConfig(
                server = "example.com",
                port = 443,
                shortId = "test",
                publicKey = "key",
                serverName = "example.com",
                fingerprintProfile = profile
            )
            assertEquals(profile, config.fingerprintProfile)
        }
    }
}

