package com.simplexray.an.chain

import com.simplexray.an.chain.hysteria2.Hy2Config
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress

class Hy2ConfigTest {
    
    @Test
    fun `Hy2Config with valid parameters should be created`() {
        val config = Hy2Config(
            server = "example.com",
            port = 443,
            auth = "test-auth-string",
            alpn = "h3",
            upRateMbps = 100,
            downRateMbps = 500,
            bandwidthProbing = true,
            zeroRttEnabled = true,
            upstreamSocksAddr = InetSocketAddress("127.0.0.1", 10808)
        )
        
        assertEquals("example.com", config.server)
        assertEquals(443, config.port)
        assertEquals("test-auth-string", config.auth)
        assertEquals("h3", config.alpn)
        assertEquals(100, config.upRateMbps)
        assertEquals(500, config.downRateMbps)
        assertTrue(config.bandwidthProbing)
        assertTrue(config.zeroRttEnabled)
        assertNotNull(config.upstreamSocksAddr)
    }
    
    @Test
    fun `Hy2Config should default to h3 ALPN and auto rates`() {
        val config = Hy2Config(
            server = "example.com",
            port = 443,
            auth = "test-auth"
        )
        
        assertEquals("h3", config.alpn)
        assertEquals(0, config.upRateMbps) // 0 = auto
        assertEquals(0, config.downRateMbps) // 0 = auto
        assertTrue(config.bandwidthProbing)
        assertTrue(config.zeroRttEnabled)
        assertNull(config.upstreamSocksAddr)
    }
    
    @Test
    fun `Hy2Config should support upstream SOCKS chaining`() {
        val upstream = InetSocketAddress("127.0.0.1", 10808)
        val config = Hy2Config(
            server = "example.com",
            port = 443,
            auth = "test-auth",
            upstreamSocksAddr = upstream
        )
        
        assertNotNull(config.upstreamSocksAddr)
        assertEquals("127.0.0.1", config.upstreamSocksAddr?.hostString)
        assertEquals(10808, config.upstreamSocksAddr?.port)
    }
}

