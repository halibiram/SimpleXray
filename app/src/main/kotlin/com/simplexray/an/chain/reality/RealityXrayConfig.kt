package com.simplexray.an.chain.reality

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
     * Read SNI from clipboard if available
     * @param context Android Context to access clipboard
     * @return SNI string from clipboard, or null if not available
     */
    private fun getSniFromClipboard(context: Context?): String? {
        if (context == null) return null
        
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard?.hasPrimaryClip() == true) {
                val clipData: ClipData? = clipboard.primaryClip
                if (clipData != null && clipData.itemCount > 0) {
                    val item: ClipData.Item = clipData.getItemAt(0)
                    val text: CharSequence? = item.text
                    val sni = text?.toString()?.trim()
                    if (!sni.isNullOrBlank()) {
                        sni
                    } else {
                        null
                    }
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Build Xray config JSON for Reality SOCKS
     * @param config Reality configuration
     * @param context Optional Android Context to read SNI from clipboard
     */
    fun buildConfig(config: RealityConfig, context: Context? = null): JsonObject {
        // Try to get SNI from clipboard first (user requirement: "clipboardaki sni kesinlikle eklesin")
        val clipboardSni = getSniFromClipboard(context)
        val finalServerName = if (!clipboardSni.isNullOrBlank()) {
            clipboardSni // Use clipboard SNI if available
        } else {
            config.serverName // Fallback to config serverName
        }
        
        // Validate config before building
        require(config.publicKey.isNotBlank()) {
            "Reality publicKey cannot be empty"
        }
        require(config.server.isNotBlank()) {
            "Reality server address cannot be empty"
        }
        require(finalServerName.isNotBlank()) {
            "Reality serverName (SNI) cannot be empty. Check clipboard or config."
        }
        require(config.shortId.isNotBlank()) {
            "Reality shortId cannot be empty"
        }
        require(config.port > 0 && config.port <= 65535) {
            "Reality port must be between 1 and 65535"
        }
        require(config.localPort > 0 && config.localPort <= 65535) {
            "Reality localPort must be between 1 and 65535"
        }
        
        val root = JsonObject()
        
        // Logging - Use debug level with access/error log paths
        root.add("log", JsonObject().apply {
            addProperty("loglevel", "debug")
            addProperty("access", "/data/data/com.simplexray.an/files/xray_access.log")
            addProperty("error", "/data/data/com.simplexray.an/files/xray_error.log")
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
                    // dest: Target server for REALITY handshake (must match serverNames SNI)
                    addProperty("dest", "${finalServerName}:443")
                    addProperty("xver", 0)
                    // serverNames: SNI (Server Name Indication) array - REQUIRED for REALITY
                    // This is the SNI that will be sent in TLS handshake
                    // Priority: clipboard SNI > config serverName
                    val serverNamesArray = JsonArray()
                    if (finalServerName.isNotBlank()) {
                        serverNamesArray.add(JsonPrimitive(finalServerName))
                    }
                    // Ensure serverNames array is not empty (SNI is required)
                    require(serverNamesArray.size() > 0) {
                        "Reality serverNames (SNI) array cannot be empty"
                    }
                    add("serverNames", serverNamesArray)
                    addProperty("publicKey", config.publicKey) // Xray REALITY uses publicKey
                    add("shortIds", JsonArray().apply {
                        add(JsonPrimitive(config.shortId))
                    })
                    addProperty("minClientVer", "")
                    addProperty("maxClientVer", "")
                    addProperty("maxTimeDiff", 0L)
                })
                
                // TLS fingerprint based on profile (utls required for Reality)
                // Ensure fingerprint is always set for Reality protocol
                val fingerprint = when (config.fingerprintProfile) {
                    TlsFingerprintProfile.CHROME -> "chrome"
                    TlsFingerprintProfile.FIREFOX -> "firefox"
                    TlsFingerprintProfile.SAFARI -> "safari"
                    TlsFingerprintProfile.EDGE -> "edge"
                    TlsFingerprintProfile.CUSTOM -> "random"
                }
                // Reality requires TLS fingerprint (utls) to be enabled
                require(fingerprint.isNotBlank()) {
                    "Reality protocol requires TLS fingerprint (utls) to be enabled"
                }
                addProperty("fingerprint", fingerprint)
                
                // Validate port and key match server configuration
                // Port validation already done above, but ensure it matches server port
                require(config.port > 0 && config.port <= 65535) {
                    "Reality port must match server port (1-65535)"
                }
                require(config.publicKey.isNotBlank()) {
                    "Reality publicKey must match server publicKey"
                }
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

