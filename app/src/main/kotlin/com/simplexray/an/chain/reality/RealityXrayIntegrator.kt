package com.simplexray.an.chain.reality

import android.content.Context
import com.google.gson.JsonParser
import com.simplexray.an.common.AppLogger
import java.io.File

/**
 * Integrates Reality and Xray for chain tunnel system
 * 
 * This class provides utilities to:
 * - Extract Reality config from Xray config
 * - Build unified Xray config with Reality outbound
 * - Create chain tunnel configurations
 */
object RealityXrayIntegrator {
    
    /**
     * Extract Reality configuration from Xray config file
     * 
     * @param xrayConfigPath Path to Xray JSON config file
     * @return RealityConfig if found, null otherwise
     */
    fun extractRealityFromXrayConfig(xrayConfigPath: String): RealityConfig? {
        return try {
            val configFile = File(xrayConfigPath)
            if (!configFile.exists() || !configFile.canRead()) {
                AppLogger.w("RealityXrayIntegrator: Config file not accessible: $xrayConfigPath")
                return null
            }
            
            val configJson = configFile.readText()
            val root = JsonParser.parseString(configJson).asJsonObject
            
            // Find VLESS + REALITY outbound
            val outbounds = root.getAsJsonArray("outbounds") ?: return null
            
            for (outboundElement in outbounds) {
                val outbound = outboundElement.asJsonObject
                val protocol = outbound.get("protocol")?.asString
                
                if (protocol == "vless") {
                    val streamSettings = outbound.getAsJsonObject("streamSettings")
                    val security = streamSettings?.get("security")?.asString
                    
                    if (security == "reality") {
                        val realitySettings = streamSettings.getAsJsonObject("realitySettings")
                        val settings = outbound.getAsJsonObject("settings")
                        val vnext = settings?.getAsJsonArray("vnext")?.firstOrNull()?.asJsonObject
                        
                        if (vnext != null && realitySettings != null) {
                            val server = vnext.get("address")?.asString ?: return null
                            val port = vnext.get("port")?.asInt ?: return null
                            val users = vnext.getAsJsonArray("users")?.firstOrNull()?.asJsonObject
                            val uuid = users?.get("id")?.asString
                            
                            val publicKey = realitySettings.get("publicKey")?.asString
                            // Validate publicKey is not empty
                            if (publicKey.isNullOrBlank()) {
                                AppLogger.w("RealityXrayIntegrator: Found REALITY outbound with empty publicKey, skipping")
                                continue
                            }
                            val shortIds = realitySettings.getAsJsonArray("shortIds")
                            val shortId = shortIds?.firstOrNull()?.asString ?: ""
                            val serverNames = realitySettings.getAsJsonArray("serverNames")
                            val serverName = serverNames?.firstOrNull()?.asString ?: ""
                            val dest = realitySettings.get("dest")?.asString ?: ""
                            val finalServerName = if (serverName.isNotBlank()) serverName else dest.split(":").firstOrNull() ?: ""
                            
                            val fingerprint = streamSettings.get("fingerprint")?.asString ?: "chrome"
                            val fingerprintProfile = when (fingerprint.lowercase()) {
                                "chrome" -> TlsFingerprintProfile.CHROME
                                "firefox" -> TlsFingerprintProfile.FIREFOX
                                "safari" -> TlsFingerprintProfile.SAFARI
                                "edge" -> TlsFingerprintProfile.EDGE
                                else -> TlsFingerprintProfile.CHROME
                            }
                            
                            AppLogger.d("RealityXrayIntegrator: Extracted Reality config from Xray: $server:$port")
                            
                            return RealityConfig(
                                server = server,
                                port = port,
                                shortId = shortId,
                                publicKey = publicKey,
                                serverName = finalServerName,
                                fingerprintProfile = fingerprintProfile,
                                localPort = 10808, // Default local SOCKS5 port for chain
                                uuid = uuid // Preserve UUID from Xray config
                            )
                        }
                    }
                }
            }
            
            null
        } catch (e: Exception) {
            AppLogger.e("RealityXrayIntegrator: Error extracting Reality config: ${e.message}", e)
            null
        }
    }
    
    /**
     * Build unified Xray config that integrates Reality outbound with existing Xray config
     * 
     * This creates a config where:
     * - Reality outbound is used for initial connection
     * - Xray routing rules are preserved
     * - Chain tunnel is properly configured
     * 
     * @param originalXrayConfigPath Path to original Xray config
     * @param realityConfig Reality configuration to integrate
     * @param chainMode If true, configures for chain tunnel (Reality → Hysteria2 → Xray)
     * @return Modified Xray config JSON as string, or null if failed
     */
    fun buildUnifiedXrayConfig(
        originalXrayConfigPath: String,
        realityConfig: RealityConfig,
        chainMode: Boolean = true
    ): String? {
        return try {
            // Validate Reality config before building
            if (realityConfig.publicKey.isBlank()) {
                AppLogger.e("RealityXrayIntegrator: Reality publicKey is empty, cannot build unified config")
                return null
            }
            if (realityConfig.server.isBlank()) {
                AppLogger.e("RealityXrayIntegrator: Reality server address is empty, cannot build unified config")
                return null
            }
            
            val configFile = File(originalXrayConfigPath)
            if (!configFile.exists()) {
                AppLogger.e("RealityXrayIntegrator: Original Xray config not found: $originalXrayConfigPath")
                return null
            }
            
            val configJson = configFile.readText()
            val root = JsonParser.parseString(configJson).asJsonObject
            
            // Get existing inbounds
            val inbounds = root.getAsJsonArray("inbounds") ?: com.google.gson.JsonArray()
            
            // Add Reality SOCKS inbound if not exists (for chain mode)
            if (chainMode) {
                val hasRealitySocks = inbounds.any { inbound ->
                    inbound.asJsonObject.get("tag")?.asString == "reality-socks-in"
                }
                
                if (!hasRealitySocks) {
                    val realitySocksInbound = com.google.gson.JsonObject().apply {
                        addProperty("listen", "127.0.0.1")
                        addProperty("port", realityConfig.localPort)
                        addProperty("protocol", "socks")
                        add("settings", com.google.gson.JsonObject().apply {
                            addProperty("auth", "noauth")
                            addProperty("udp", true)
                        })
                        addProperty("tag", "reality-socks-in")
                    }
                    inbounds.add(realitySocksInbound)
                }
            }
            
            root.add("inbounds", inbounds)
            
            // Get existing outbounds
            val outbounds = root.getAsJsonArray("outbounds") ?: com.google.gson.JsonArray()
            
            // Clean up existing REALITY outbounds with invalid settings
            // Remove or fix REALITY outbounds with empty publicKey or privateKey
            val cleanedOutbounds = com.google.gson.JsonArray()
            val validOutboundTags = mutableSetOf<String>()
            
            // First pass: Fix or remove invalid Reality outbounds and collect valid tags
            outbounds.forEach { outboundElement ->
                val outbound = outboundElement.asJsonObject
                val tag = outbound.get("tag")?.asString
                val streamSettings = outbound.getAsJsonObject("streamSettings")
                val realitySettings = streamSettings?.getAsJsonObject("realitySettings")
                
                // Check if this is a REALITY outbound
                val isReality = streamSettings?.get("security")?.asString == "reality"
                
                if (isReality && realitySettings != null) {
                    // Remove privateKey field if it exists (client-side doesn't need it)
                    if (realitySettings.has("privateKey")) {
                        realitySettings.remove("privateKey")
                        AppLogger.d("RealityXrayIntegrator: Removed privateKey from Reality outbound: $tag")
                    }
                    
                    // Check if publicKey is empty or blank
                    val publicKey = realitySettings.get("publicKey")?.asString
                    val hasValidPublicKey = !publicKey.isNullOrBlank()
                    
                    if (!hasValidPublicKey) {
                        // Remove Reality outbound with empty publicKey (cannot be fixed)
                        AppLogger.w("RealityXrayIntegrator: Removing invalid REALITY outbound: $tag (empty publicKey)")
                    } else {
                        // Valid Reality outbound - add to cleaned list
                        cleanedOutbounds.add(outbound)
                        if (tag != null) {
                            validOutboundTags.add(tag)
                        }
                    }
                } else {
                    // Not a Reality outbound - keep as is
                    cleanedOutbounds.add(outbound)
                    if (tag != null) {
                        validOutboundTags.add(tag)
                    }
                }
            }
            
            // Second pass: Remove detour outbounds that point to invalid outbounds
            val finalCleanedOutbounds = com.google.gson.JsonArray()
            cleanedOutbounds.forEach { outboundElement ->
                val outbound = outboundElement.asJsonObject
                val detourTag = outbound.get("detour")?.asString
                
                if (detourTag != null) {
                    // Check if detour points to a valid outbound
                    if (validOutboundTags.contains(detourTag)) {
                        finalCleanedOutbounds.add(outbound)
                    } else {
                        AppLogger.w("RealityXrayIntegrator: Removing detour outbound '${outbound.get("tag")?.asString}' pointing to invalid outbound: $detourTag")
                    }
                } else {
                    finalCleanedOutbounds.add(outbound)
                }
            }
            
            // Check if Reality outbound with tag "reality-out" already exists (after cleanup)
            val hasRealityOutbound = finalCleanedOutbounds.any { outbound ->
                val outboundObj = outbound.asJsonObject
                val tag = outboundObj.get("tag")?.asString
                val streamSettings = outboundObj.getAsJsonObject("streamSettings")
                tag == "reality-out" &&
                streamSettings?.get("security")?.asString == "reality" &&
                streamSettings.getAsJsonObject("realitySettings")?.get("publicKey")?.asString?.isNotBlank() == true
            }
            
            // Add Reality outbound if not exists or doesn't have correct tag
            if (!hasRealityOutbound) {
                try {
                    val realityOutbound = RealityXrayConfig.buildConfig(realityConfig)
                        .getAsJsonArray("outbounds")
                        ?.firstOrNull()
                        ?.asJsonObject
                    
                    if (realityOutbound != null) {
                        // Ensure tag is "reality-out"
                        realityOutbound.addProperty("tag", "reality-out")
                        
                        // Insert Reality outbound at the beginning (highest priority)
                        val newOutbounds = com.google.gson.JsonArray()
                        newOutbounds.add(realityOutbound)
                        
                        // Add existing cleaned outbounds
                        finalCleanedOutbounds.forEach { newOutbounds.add(it) }
                        
                        root.add("outbounds", newOutbounds)
                        AppLogger.d("RealityXrayIntegrator: Added Reality outbound with tag 'reality-out'")
                    } else {
                        AppLogger.w("RealityXrayIntegrator: Failed to build Reality outbound, using cleaned outbounds")
                        root.add("outbounds", finalCleanedOutbounds)
                    }
                } catch (e: Exception) {
                    AppLogger.e("RealityXrayIntegrator: Error building Reality outbound: ${e.message}", e)
                    // Use cleaned outbounds without Reality
                    root.add("outbounds", finalCleanedOutbounds)
                }
            } else {
                // Use cleaned outbounds
                root.add("outbounds", finalCleanedOutbounds)
                AppLogger.d("RealityXrayIntegrator: Reality outbound with tag 'reality-out' already exists")
            }
            
            // Update routing rules for chain mode
            // First, always clean up any existing routing rules that reference "reality-out"
            // Then, only add routing rule if Reality outbound with tag "reality-out" exists
            if (chainMode) {
                val routing = root.getAsJsonObject("routing") ?: com.google.gson.JsonObject()
                val rules = routing.getAsJsonArray("rules") ?: com.google.gson.JsonArray()
                
                // First, remove any existing routing rules that reference "reality-out" to avoid errors
                val filteredRules = com.google.gson.JsonArray()
                var removedCount = 0
                rules.forEach { rule ->
                    val outboundTag = rule.asJsonObject.get("outboundTag")?.asString
                    if (outboundTag != "reality-out") {
                        filteredRules.add(rule)
                    } else {
                        removedCount++
                    }
                }
                
                if (removedCount > 0) {
                    AppLogger.d("RealityXrayIntegrator: Removed $removedCount routing rule(s) referencing 'reality-out' tag")
                }
                
                // Verify that Reality outbound with tag "reality-out" exists
                val finalOutbounds = root.getAsJsonArray("outbounds") ?: com.google.gson.JsonArray()
                val hasRealityOutboundTag = finalOutbounds.any { outbound ->
                    outbound.asJsonObject.get("tag")?.asString == "reality-out"
                }
                
                if (hasRealityOutboundTag) {
                    // Add rule to route through Reality
                    val realityRule = com.google.gson.JsonObject().apply {
                        addProperty("type", "field")
                        addProperty("outboundTag", "reality-out")
                        add("network", com.google.gson.JsonPrimitive("tcp,udp"))
                    }
                    // Create new array with Reality rule first
                    val newRules = com.google.gson.JsonArray()
                    newRules.add(realityRule)
                    // Add existing filtered rules (without old reality-out references)
                    filteredRules.forEach { newRules.add(it) }
                    routing.add("rules", newRules)
                    AppLogger.d("RealityXrayIntegrator: Added routing rule for 'reality-out'")
                } else {
                    // Use filtered rules (without reality-out references)
                    routing.add("rules", filteredRules)
                    AppLogger.w("RealityXrayIntegrator: Cannot add routing rule - Reality outbound with tag 'reality-out' does not exist")
                }
                
                if (!routing.has("domainStrategy")) {
                    routing.addProperty("domainStrategy", "IPIfNonMatch")
                }
                root.add("routing", routing)
            }
            
            // Convert to JSON string
            com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(root)
        } catch (e: Exception) {
            AppLogger.e("RealityXrayIntegrator: Error building unified config: ${e.message}", e)
            null
        }
    }
    
    /**
     * Check if Xray config contains Reality outbound
     */
    fun hasRealityOutbound(xrayConfigPath: String): Boolean {
        return extractRealityFromXrayConfig(xrayConfigPath) != null
    }
    
    /**
     * Get Reality outbound tag from Xray config
     */
    fun getRealityOutboundTag(xrayConfigPath: String): String? {
        return try {
            val configFile = File(xrayConfigPath)
            if (!configFile.exists()) return null
            
            val configJson = configFile.readText()
            val root = JsonParser.parseString(configJson).asJsonObject
            val outbounds = root.getAsJsonArray("outbounds") ?: return null
            
            for (outboundElement in outbounds) {
                val outbound = outboundElement.asJsonObject
                val streamSettings = outbound.getAsJsonObject("streamSettings")
                if (streamSettings?.get("security")?.asString == "reality") {
                    return outbound.get("tag")?.asString ?: "reality-out"
                }
            }
            
            null
        } catch (e: Exception) {
            AppLogger.d("RealityXrayIntegrator: Error checking Reality outbound: ${e.message}")
            null
        }
    }
}

