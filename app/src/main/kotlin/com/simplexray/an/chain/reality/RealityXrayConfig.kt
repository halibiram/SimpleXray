package com.simplexray.an.chain.reality

import com.google.gson.JsonObject
import com.google.gson.JsonArray
import com.google.gson.JsonPrimitive

/**
 * Builds Xray JSON configuration for Reality SOCKS
 * 
 * Creates:
 * - SOCKS5 inbound on local port
 * - REALITY outbound to remote server
 */
object RealityXrayConfig {
    
    /**
     * Build Xray config JSON for Reality SOCKS
     */
    fun buildConfig(config: RealityConfig): JsonObject {
        val root = JsonObject()
        
        // Logging
        root.add("log", JsonObject().apply {
            addProperty("loglevel", "warning")
        })
        
        // Inbounds: SOCKS5 server on local port
        val inbounds = JsonArray()
        val socksInbound = JsonObject().apply {
            addProperty("listen", "127.0.0.1")
            addProperty("port", config.localPort)
            addProperty("protocol", "socks")
            add("settings", JsonObject().apply {
                addProperty("auth", "noauth")
                addProperty("udp", true)
            })
            addProperty("tag", "reality-socks-in")
        }
        inbounds.add(socksInbound)
        root.add("inbounds", inbounds)
        
        // Outbounds: REALITY to remote server
        val outbounds = JsonArray()
        val realityOutbound = JsonObject().apply {
            addProperty("protocol", "vless")
            addProperty("tag", "reality-out")
            
            add("settings", JsonObject().apply {
                val vnext = JsonArray()
                val server = JsonObject().apply {
                    addProperty("address", config.server)
                    addProperty("port", config.port)
                    val users = JsonArray()
                    val user = JsonObject().apply {
                        addProperty("id", config.uuid ?: generateUUID()) // Use config UUID or generate
                        addProperty("encryption", "none")
                    }
                    users.add(user)
                    add("users", users)
                }
                vnext.add(server)
                add("vnext", vnext)
            })
            
            add("streamSettings", JsonObject().apply {
                addProperty("network", "tcp")
                add("security", JsonPrimitive("reality"))
                add("realitySettings", JsonObject().apply {
                    addProperty("show", false)
                    addProperty("dest", "${config.serverName}:443")
                    addProperty("xver", 0)
                    add("serverNames", JsonArray().apply {
                        add(JsonPrimitive(config.serverName))
                    })
                    addProperty("publicKey", config.publicKey) // Xray REALITY uses publicKey
                    add("shortIds", JsonArray().apply {
                        add(JsonPrimitive(config.shortId))
                    })
                    addProperty("minClientVer", "")
                    addProperty("maxClientVer", "")
                    addProperty("maxTimeDiff", 0L)
                })
                
                // TLS fingerprint based on profile
                val fingerprint = when (config.fingerprintProfile) {
                    TlsFingerprintProfile.CHROME -> "chrome"
                    TlsFingerprintProfile.FIREFOX -> "firefox"
                    TlsFingerprintProfile.SAFARI -> "safari"
                    TlsFingerprintProfile.EDGE -> "edge"
                    TlsFingerprintProfile.CUSTOM -> "random"
                }
                addProperty("fingerprint", fingerprint)
            })
        }
        outbounds.add(realityOutbound)
        
        // Add direct outbound for fallback
        val directOutbound = JsonObject().apply {
            addProperty("protocol", "freedom")
            addProperty("tag", "direct")
        }
        outbounds.add(directOutbound)
        root.add("outbounds", outbounds)
        
        // Routing: Route all traffic through reality-out
        root.add("routing", JsonObject().apply {
            addProperty("domainStrategy", "IPIfNonMatch")
            add("rules", JsonArray().apply {
                val rule = JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("outboundTag", "reality-out")
                    add("network", JsonPrimitive("tcp,udp"))
                }
                add(rule)
            })
        })
        
        return root
    }
    
    /**
     * Generate a UUID for VLESS user ID
     * In production, this should come from config or be persistent
     */
    private fun generateUUID(): String {
        // Simple UUID v4 generation (for demo)
        // In production, use java.util.UUID.randomUUID().toString()
        return java.util.UUID.randomUUID().toString()
    }
}

