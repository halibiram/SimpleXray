package com.simplexray.an.chain.hysteria2

import com.google.gson.JsonObject
import com.google.gson.JsonArray
import com.google.gson.JsonPrimitive
import java.net.InetSocketAddress

/**
 * Builds Hysteria2 JSON configuration
 * 
 * Hysteria2 uses a JSON config file format similar to Xray
 */
object Hy2ConfigBuilder {
    
    /**
     * Build Hysteria2 config JSON
     */
    fun buildConfig(config: Hy2Config): JsonObject {
        val root = JsonObject()
        
        // Log level
        root.addProperty("logLevel", "info")
        
        // Server address
        root.addProperty("server", "${config.server}:${config.port}")
        
        // Authentication
        root.addProperty("auth", config.auth)
        
        // Bandwidth settings
        if (config.upRateMbps > 0 || config.downRateMbps > 0) {
            val bandwidth = JsonObject()
            if (config.upRateMbps > 0) {
                bandwidth.addProperty("up", "${config.upRateMbps}Mbps")
            }
            if (config.downRateMbps > 0) {
                bandwidth.addProperty("down", "${config.downRateMbps}Mbps")
            }
            root.add("bandwidth", bandwidth)
        }
        
        // ALPN
        root.addProperty("alpn", config.alpn)
        
        // SOCKS5 proxy (upstream chaining)
        if (config.upstreamSocksAddr != null) {
            val socks5 = JsonObject().apply {
                addProperty("listen", "127.0.0.1:0") // Let Hysteria2 choose port
                addProperty("timeout", 300) // 5 minutes
            }
            root.add("socks5", socks5)
            
            // Upstream SOCKS5 proxy
            val proxy = JsonObject().apply {
                addProperty("url", "socks5://${config.upstreamSocksAddr.hostString}:${config.upstreamSocksAddr.port}")
            }
            root.add("proxy", proxy)
        }
        
        // QUIC settings
        val quic = JsonObject().apply {
            addProperty("initStreamReceiveWindow", 8388608) // 8MB
            addProperty("maxStreamReceiveWindow", 8388608) // 8MB
            addProperty("initConnReceiveWindow", 20971520) // 20MB
            addProperty("maxConnReceiveWindow", 20971520) // 20MB
            addProperty("maxIdleTimeout", 30) // 30 seconds
            addProperty("maxIncomingStreams", 1024)
            addProperty("disablePathMTUDiscovery", false)
        }
        root.add("quic", quic)
        
        // 0-RTT
        if (config.zeroRttEnabled) {
            root.addProperty("fastOpen", true)
        }
        
        // Bandwidth probing
        if (config.bandwidthProbing) {
            root.addProperty("bandwidthProbe", true)
        }
        
        // ACL (optional, for routing rules)
        // Can be added later if needed
        
        return root
    }
    
    /**
     * Build minimal config for testing
     */
    fun buildMinimalConfig(server: String, port: Int, auth: String): JsonObject {
        return buildConfig(
            Hy2Config(
                server = server,
                port = port,
                auth = auth,
                upstreamSocksAddr = null
            )
        )
    }
}

