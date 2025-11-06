package com.simplexray.an.chain

import com.google.gson.JsonObject
import com.simplexray.an.chain.hysteria2.Hy2Config
import com.simplexray.an.chain.hysteria2.Hy2ConfigBuilder
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress

class Hy2ConfigBuilderTest {
    
    @Test
    fun `buildConfig should create valid Hysteria2 JSON`() {
        val config = Hy2Config(
            server = "example.com",
            port = 443,
            auth = "test-auth",
            alpn = "h3"
        )
        
        val hy2Config = Hy2ConfigBuilder.buildConfig(config)
        
        assertNotNull(hy2Config)
        assertTrue(hy2Config.has("server"))
        assertTrue(hy2Config.has("auth"))
        assertTrue(hy2Config.has("alpn"))
    }
    
    @Test
    fun `buildConfig should include server address and port`() {
        val config = Hy2Config(
            server = "example.com",
            port = 443,
            auth = "test-auth"
        )
        
        val hy2Config = Hy2ConfigBuilder.buildConfig(config)
        
        assertEquals("example.com:443", hy2Config.get("server").asString)
        assertEquals("test-auth", hy2Config.get("auth").asString)
    }
    
    @Test
    fun `buildConfig should include bandwidth settings when specified`() {
        val config = Hy2Config(
            server = "example.com",
            port = 443,
            auth = "test-auth",
            upRateMbps = 100,
            downRateMbps = 500
        )
        
        val hy2Config = Hy2ConfigBuilder.buildConfig(config)
        
        assertTrue(hy2Config.has("bandwidth"))
        val bandwidth = hy2Config.getAsJsonObject("bandwidth")
        assertEquals("100Mbps", bandwidth.get("up").asString)
        assertEquals("500Mbps", bandwidth.get("down").asString)
    }
    
    @Test
    fun `buildConfig should include upstream SOCKS5 proxy when specified`() {
        val upstream = InetSocketAddress("127.0.0.1", 10808)
        val config = Hy2Config(
            server = "example.com",
            port = 443,
            auth = "test-auth",
            upstreamSocksAddr = upstream
        )
        
        val hy2Config = Hy2ConfigBuilder.buildConfig(config)
        
        assertTrue(hy2Config.has("proxy"))
        val proxy = hy2Config.getAsJsonObject("proxy")
        assertEquals("socks5://127.0.0.1:10808", proxy.get("url").asString)
    }
    
    @Test
    fun `buildConfig should include QUIC settings`() {
        val config = Hy2Config(
            server = "example.com",
            port = 443,
            auth = "test-auth"
        )
        
        val hy2Config = Hy2ConfigBuilder.buildConfig(config)
        
        assertTrue(hy2Config.has("quic"))
        val quic = hy2Config.getAsJsonObject("quic")
        assertTrue(quic.has("initStreamReceiveWindow"))
        assertTrue(quic.has("maxStreamReceiveWindow"))
    }
    
    @Test
    fun `buildConfig should include fastOpen when zeroRttEnabled`() {
        val config = Hy2Config(
            server = "example.com",
            port = 443,
            auth = "test-auth",
            zeroRttEnabled = true
        )
        
        val hy2Config = Hy2ConfigBuilder.buildConfig(config)
        
        assertTrue(hy2Config.has("fastOpen"))
        assertTrue(hy2Config.get("fastOpen").asBoolean)
    }
}

