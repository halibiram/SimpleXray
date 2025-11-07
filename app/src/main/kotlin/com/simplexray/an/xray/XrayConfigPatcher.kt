package com.simplexray.an.xray

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.reflect.TypeToken
import com.simplexray.an.performance.optimizer.PerformanceOptimizer
import com.simplexray.an.protocol.optimization.ProtocolConfig
import com.simplexray.an.protocol.routing.AdvancedRouter
import com.simplexray.an.protocol.routing.AdvancedRouter.RoutingAction
import com.simplexray.an.protocol.routing.AdvancedRouter.RoutingMatcher
import com.simplexray.an.protocol.routing.AdvancedRouter.RoutingRule
import com.simplexray.an.prefs.Preferences
import java.io.File

object XrayConfigPatcher {
    private const val TAG = "XrayConfigPatcher"
    private val gson = Gson()

    /**
     * Enhanced config patcher that merges inbound/outbound/transport while preserving user config
     */
    fun patchConfig(
        context: Context,
        filename: String = "xray.json",
        mergeInbounds: Boolean = true,
        mergeOutbounds: Boolean = true,
        mergeTransport: Boolean = true
    ): File {
        val file = File(context.filesDir, filename)
        // SEC: Validate file path to prevent directory traversal
        // SEC: Validate filename doesn't contain path traversal sequences
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            throw SecurityException("Invalid filename: contains path traversal characters")
        }
        // SEC: Validate filename length
        if (filename.length > 255) {
            throw IllegalArgumentException("Filename too long: ${filename.length} characters")
        }
        val root = if (file.exists()) {
            try {
                // SEC: Add file size limit check before reading (10MB max)
                val fileSize = file.length()
                if (fileSize > 10 * 1024 * 1024) {
                    throw IllegalArgumentException("Config file too large: $fileSize bytes (max 10MB)")
                }
                if (fileSize == 0L) {
                    Log.w(TAG, "Config file is empty, creating new")
                    JsonObject()
                }
                val text = file.readText()
                // PERF: Reading entire file into memory - consider streaming for large files
                val parsed = gson.fromJson(text, JsonObject::class.java)
                if (parsed == null) {
                    Log.w(TAG, "Failed to parse config JSON, creating new")
                    JsonObject()
                } else {
                    parsed
                }
            } catch (e: SecurityException) {
                // Re-throw security exceptions
                throw e
            } catch (e: IllegalArgumentException) {
                // Re-throw validation exceptions
                throw e
            } catch (e: Exception) {
                // Log error with full context - config parsing failures are critical
                Log.e(TAG, "Failed to parse existing config, creating new: ${e.javaClass.simpleName}: ${e.message}", e)
                // Return empty config - caller should handle this appropriately
                JsonObject()
            }
        } else {
            JsonObject()
        }

        // Always ensure API/Stats/Policy
        ensureApiStatsPolicy(root, context)

        // Apply performance optimization settings
        applyPerformanceConfig(root, context)
        
        // Apply gaming optimizations if enabled
        applyGamingConfig(root, context)

        // Merge inbound/outbound/transport if requested
        if (mergeInbounds) {
            mergeInboundSection(root, context)
        }
        if (mergeOutbounds) {
            mergeOutboundSection(root, context)
        }
        if (mergeTransport) {
            mergeTransportSection(root, context)
        }

        // Apply advanced routing rules
        applyAdvancedRoutingRules(root, context)

        // Write back
        file.writeText(gson.toJson(root))
        Log.d(TAG, "Config patched and saved")
        return file
    }

    fun ensureApiStatsPolicy(context: Context, filename: String = "xray.json"): File {
        val file = File(context.filesDir, filename)
        if (!file.exists()) {
            val cfg = XrayConfigBuilder.defaultConfig("127.0.0.1", 10085)
            XrayConfigBuilder.writeConfig(context, cfg, filename)
            return file
        }
        // SEC: Validate filename doesn't contain path traversal sequences
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            throw SecurityException("Invalid filename: contains path traversal characters")
        }
        // SEC: Validate filename length
        if (filename.length > 255) {
            throw IllegalArgumentException("Filename too long: ${filename.length} characters")
        }
        // SEC: Add file size validation before reading
        val text = try {
            if (file.exists()) {
                val fileSize = file.length()
                if (fileSize > 10 * 1024 * 1024) {
                    throw IllegalArgumentException("Config file too large: $fileSize bytes (max 10MB)")
                }
                if (fileSize == 0L) {
                    ""
                } else {
                    file.readText()
                }
            } else {
                ""
            }
        } catch (e: SecurityException) {
            throw e // Re-throw security exceptions
        } catch (e: IllegalArgumentException) {
            throw e // Re-throw validation exceptions
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read config file: ${e.javaClass.simpleName}: ${e.message}", e)
            ""
        }
        // Parse config with proper error handling
        val root = try {
            if (text.isBlank()) {
                JsonObject()
            } else {
                gson.fromJson(text, JsonObject::class.java) ?: JsonObject()
            }
        } catch (e: Exception) {
            // Log error with full context - config parsing failures are critical
            Log.e(TAG, "Failed to parse config JSON: ${e.javaClass.simpleName}: ${e.message}", e)
            // Return empty config - caller should handle this appropriately
            JsonObject()
        }

        // api
        val api = (root.get("api") as? JsonObject) ?: JsonObject().also { root.add("api", it) }
        if (!api.has("tag")) api.addProperty("tag", "api")
        val services = when (val s = api.get("services")) {
            is JsonArray -> s
            else -> JsonArray().also { api.add("services", it) }
        }
        if (!servicesContains(services, "StatsService")) services.add("StatsService")

        // stats
        if (root.get("stats") !is JsonObject) root.add("stats", JsonObject())

        // policy
        val policy = (root.get("policy") as? JsonObject) ?: JsonObject().also { root.add("policy", it) }
        val system = (policy.get("system") as? JsonObject) ?: JsonObject().also { policy.add("system", it) }
        system.addProperty("statsInboundUplink", true)
        system.addProperty("statsInboundDownlink", true)
        system.addProperty("statsOutboundUplink", true)
        system.addProperty("statsOutboundDownlink", true)
        val levels = (policy.get("levels") as? JsonObject) ?: JsonObject().also { policy.add("levels", it) }
        val level0 = (levels.get("0") as? JsonObject) ?: JsonObject().also { levels.add("0", it) }
        level0.addProperty("statsUserUplink", true)
        level0.addProperty("statsUserDownlink", true)

        // inbounds: ensure dokodemo-door API listener
        val host = com.simplexray.an.config.ApiConfig.getHost(context)
        val port = com.simplexray.an.config.ApiConfig.getPort(context)
        val inbounds = (root.get("inbounds") as? JsonArray) ?: JsonArray().also { root.add("inbounds", it) }
        // Remove any existing api-in inbound first to prevent duplicates
        removeApiInbound(inbounds)
        if (!hasApiInbound(inbounds, host, port)) {
            val ib = JsonObject().apply {
                addProperty("listen", host)
                addProperty("port", port)
                addProperty("protocol", "dokodemo-door")
                addProperty("tag", "api-in")
                add("settings", JsonObject().apply { addProperty("address", host) })
            }
            inbounds.add(ib)
        }

        // routing: route api inbound to api outbound (special tag)
        val routing = (root.get("routing") as? JsonObject) ?: JsonObject().also { root.add("routing", it) }
        val rules = (routing.get("rules") as? JsonArray) ?: JsonArray().also { routing.add("rules", it) }
        if (!hasApiRule(rules)) {
            val rule = JsonObject().apply {
                addProperty("type", "field")
                add("inboundTag", JsonArray().apply { add("api-in") })
                addProperty("outboundTag", "api")
            }
            rules.add(rule)
        }

        file.writeText(gson.toJson(root))
        return file
    }

    private fun servicesContains(arr: JsonArray, name: String): Boolean {
        for (e: JsonElement in arr) {
            if (e.isJsonPrimitive && e.asJsonPrimitive.isString && e.asString == name) return true
        }
        return false
    }

    private fun hasApiInbound(inbounds: JsonArray, host: String, port: Int): Boolean {
        for (e in inbounds) {
            if (e is JsonObject) {
                val t = e.get("tag")?.asString ?: ""
                // Check by tag first - tag "api-in" must be unique
                if (t == "api-in") {
                    // Verify it matches expected settings
                    val proto = e.get("protocol")?.asString ?: ""
                    val p = e.get("port")?.asInt ?: -1
                    if (proto == "dokodemo-door" && p == port) {
                        return true
                    }
                    // Tag exists but with wrong settings - will be removed and re-added
                    return false
                }
            }
        }
        return false
    }
    
    /**
     * Remove any existing inbounds with tag "api-in" to prevent duplicates
     */
    private fun removeApiInbound(inbounds: JsonArray) {
        val indicesToRemove = mutableListOf<Int>()
        for (i in 0 until inbounds.size()) {
            val e = inbounds[i]
            if (e is JsonObject) {
                val t = e.get("tag")?.asString ?: ""
                if (t == "api-in") {
                    indicesToRemove.add(i)
                }
            }
        }
        // Remove in reverse order to maintain indices
        for (i in indicesToRemove.reversed()) {
            inbounds.remove(i)
            Log.d(TAG, "Removed existing api-in inbound to prevent duplicate")
        }
    }

    private fun hasApiRule(rules: JsonArray): Boolean {
        for (e in rules) {
            if (e is JsonObject) {
                val outbound = e.get("outboundTag")?.asString ?: ""
                if (outbound == "api") return true
            }
        }
        return false
    }

    /**
     * Ensure API, Stats, and Policy sections exist
     */
    private fun ensureApiStatsPolicy(root: JsonObject, context: Context) {
        // api
        val api = (root.get("api") as? JsonObject) ?: JsonObject().also { root.add("api", it) }
        if (!api.has("tag")) api.addProperty("tag", "api")
        val services = when (val s = api.get("services")) {
            is JsonArray -> s
            else -> JsonArray().also { api.add("services", it) }
        }
        if (!servicesContains(services, "StatsService")) services.add("StatsService")

        // stats
        if (root.get("stats") !is JsonObject) root.add("stats", JsonObject())

        // policy
        val policy = (root.get("policy") as? JsonObject) ?: JsonObject().also { root.add("policy", it) }
        val system = (policy.get("system") as? JsonObject) ?: JsonObject().also { policy.add("system", it) }
        system.addProperty("statsInboundUplink", true)
        system.addProperty("statsInboundDownlink", true)
        system.addProperty("statsOutboundUplink", true)
        system.addProperty("statsOutboundDownlink", true)
        val levels = (policy.get("levels") as? JsonObject) ?: JsonObject().also { policy.add("levels", it) }
        val level0 = (levels.get("0") as? JsonObject) ?: JsonObject().also { levels.add("0", it) }
        level0.addProperty("statsUserUplink", true)
        level0.addProperty("statsUserDownlink", true)

        // inbounds: ensure dokodemo-door API listener
        val host = com.simplexray.an.config.ApiConfig.getHost(context)
        val port = com.simplexray.an.config.ApiConfig.getPort(context)
        val inbounds = (root.get("inbounds") as? JsonArray) ?: JsonArray().also { root.add("inbounds", it) }
        // Remove any existing api-in inbound first to prevent duplicates
        removeApiInbound(inbounds)
        if (!hasApiInbound(inbounds, host, port)) {
            val ib = JsonObject().apply {
                addProperty("listen", host)
                addProperty("port", port)
                addProperty("protocol", "dokodemo-door")
                addProperty("tag", "api-in")
                add("settings", JsonObject().apply { addProperty("address", host) })
            }
            inbounds.add(ib)
        }

        // routing: route api inbound to api outbound
        val routing = (root.get("routing") as? JsonObject) ?: JsonObject().also { root.add("routing", it) }
        val rules = (routing.get("rules") as? JsonArray) ?: JsonArray().also { routing.add("rules", it) }
        if (!hasApiRule(rules)) {
            val rule = JsonObject().apply {
                addProperty("type", "field")
                add("inboundTag", JsonArray().apply { add("api-in") })
                addProperty("outboundTag", "api")
            }
            rules.add(rule)
        }
    }

    /**
     * Merge inbounds while preserving user-defined inbounds
     */
    private fun mergeInboundSection(root: JsonObject, context: Context) {
        val inbounds = (root.get("inbounds") as? JsonArray) ?: JsonArray().also { root.add("inbounds", it) }
        val host = com.simplexray.an.config.ApiConfig.getHost(context)
        val port = com.simplexray.an.config.ApiConfig.getPort(context)
        
        // Remove any existing api-in inbound first to prevent duplicates
        removeApiInbound(inbounds)
        // Ensure API inbound exists
        if (!hasApiInbound(inbounds, host, port)) {
            val ib = JsonObject().apply {
                addProperty("listen", host)
                addProperty("port", port)
                addProperty("protocol", "dokodemo-door")
                addProperty("tag", "api-in")
                add("settings", JsonObject().apply { addProperty("address", host) })
            }
            inbounds.add(ib)
            Log.d(TAG, "Added API inbound listener")
        }
    }

    /**
     * Merge outbounds while preserving user-defined outbounds
     */
    private fun mergeOutboundSection(root: JsonObject, context: Context) {
        val outbounds = (root.get("outbounds") as? JsonArray) ?: JsonArray().also { root.add("outbounds", it) }
        
        // Check if API outbound exists
        var hasApiOutbound = false
        for (e in outbounds) {
            if (e is JsonObject) {
                val tag = e.get("tag")?.asString ?: ""
                if (tag == "api") {
                    hasApiOutbound = true
                    break
                }
            }
        }
        
        if (!hasApiOutbound) {
            // Add a simple API outbound (required for routing)
            val ob = JsonObject().apply {
                addProperty("tag", "api")
                addProperty("protocol", "freedom")
                add("settings", JsonObject())
            }
            outbounds.add(ob)
            Log.d(TAG, "Added API outbound")
        }
    }

    /**
     * Merge transport configuration (e.g., gRPC, WebSocket) while preserving user config
     */
    private fun mergeTransportSection(root: JsonObject, context: Context) {
        // Only merge if transport section doesn't exist
        if (!root.has("transport")) {
            // Example: Add gRPC transport for API if needed
            // User can customize this in their config
            Log.d(TAG, "Transport section not present, user can add manually")
        } else {
            val transport = root.get("transport") as? JsonObject
            if (transport != null) {
                // Preserve existing transport settings
                Log.d(TAG, "Transport section exists, preserving user config")
            }
        }
    }

    /**
     * Apply performance optimization configuration from PerformanceOptimizer
     */
    private fun applyPerformanceConfig(root: JsonObject, context: Context) {
        try {
            val optimizer = PerformanceOptimizer(context)
            val perfConfig = optimizer.getCurrentXrayConfig()
            
            // Apply log level - default to debug if not set
            val logObj = (root.get("log") as? JsonObject) ?: JsonObject().also { root.add("log", it) }
            val perfLog = perfConfig["log"] as? Map<*, *>
            val logLevel = perfLog?.get("loglevel")?.toString() ?: "debug"
            logObj.addProperty("loglevel", logLevel)
            
            // Ensure access and error log paths are set
            if (!logObj.has("access")) {
                logObj.addProperty("access", "/data/data/com.simplexray.an/files/xray_access.log")
            }
            if (!logObj.has("error")) {
                logObj.addProperty("error", "/data/data/com.simplexray.an/files/xray_error.log")
            }
            
            // Apply policy settings
            val policyObj = (root.get("policy") as? JsonObject) ?: JsonObject().also { root.add("policy", it) }
            val perfPolicy = perfConfig["policy"] as? Map<*, *>
            
            // Apply system policy settings
            val systemObj = (policyObj.get("system") as? JsonObject) ?: JsonObject().also { policyObj.add("system", it) }
            val perfSystem = perfPolicy?.get("system") as? Map<*, *>
            perfSystem?.forEach { (key, value) ->
                when (value) {
                    is Boolean -> systemObj.addProperty(key.toString(), value)
                    is Number -> systemObj.addProperty(key.toString(), value)
                    is String -> systemObj.addProperty(key.toString(), value)
                }
            }
            
            // Apply level 0 policy settings (buffer size, timeouts, etc.)
            val levelsObj = (policyObj.get("levels") as? JsonObject) ?: JsonObject().also { policyObj.add("levels", it) }
            val perfLevels = perfPolicy?.get("levels") as? Map<*, *>
            val perfLevel0 = perfLevels?.get("0") as? Map<*, *>
            
            if (perfLevel0 != null) {
                val level0Obj = (levelsObj.get("0") as? JsonObject) ?: JsonObject().also { levelsObj.add("0", it) }
                perfLevel0.forEach { (key, value) ->
                    when (value) {
                        is Boolean -> level0Obj.addProperty(key.toString(), value)
                        is Number -> level0Obj.addProperty(key.toString(), value.toInt())
                        is String -> level0Obj.addProperty(key.toString(), value)
                    }
                }
            }
            
            Log.d(TAG, "Performance config applied successfully")
        } catch (e: Exception) {
            // Log error with full context - performance optimizations are optional but failures should be visible
            Log.e(TAG, "Failed to apply performance config, continuing without it: ${e.javaClass.simpleName}: ${e.message}", e)
            // Note: Performance config is optional, so we continue without it rather than failing the entire config patch
        }
    }

    /**
     * Apply gaming-specific optimizations when gaming mode is enabled
     */
    private fun applyGamingConfig(root: JsonObject, context: Context) {
        try {
            val prefs = com.simplexray.an.prefs.Preferences(context)
            val isGamingEnabled = prefs.gamingOptimizationEnabled
            val gameProfileName = prefs.selectedGameProfile

            if (!isGamingEnabled || gameProfileName == null) {
                return // Gaming optimization not enabled
            }

            Log.d(TAG, "Applying gaming optimizations for profile: $gameProfileName")

            // Get gaming profile
            val gameProfile = try {
                com.simplexray.an.protocol.gaming.GamingOptimizer.GameProfile.valueOf(gameProfileName)
            } catch (e: Exception) {
                Log.w(TAG, "Invalid game profile: $gameProfileName", e)
                return
            }

            val gameConfig = gameProfile.config

            // Apply gaming-specific policy optimizations
            val policyObj = (root.get("policy") as? JsonObject) ?: JsonObject().also { root.add("policy", it) }
            val levelsObj = (policyObj.get("levels") as? JsonObject) ?: JsonObject().also { policyObj.add("levels", it) }
            val level0Obj = (levelsObj.get("0") as? JsonObject) ?: JsonObject().also { levelsObj.add("0", it) }

            // Apply gaming-specific buffer and timeout settings
            // These are already set by Gaming performance profile, but we can add game-specific tweaks
            val currentBufferSize = level0Obj.get("bufferSize")?.asInt ?: (128 * 1024 / 1024) // Default 128KB in KB
            val gamingBufferSize = (gameConfig.bufferSize / 1024).coerceAtLeast(32).coerceAtMost(512) // KB, min 32KB, max 512KB
            
            if (gamingBufferSize != currentBufferSize) {
                level0Obj.addProperty("bufferSize", gamingBufferSize)
                Log.d(TAG, "Set gaming buffer size to ${gamingBufferSize}KB")
            }

            // Apply TCP optimizations if enabled
            if (gameConfig.tcpNoDelay) {
                // TCP_NODELAY equivalent in Xray is handled at transport level
                // We ensure handshake timeout is minimized for low latency
                val currentHandshake = level0Obj.get("handshake")?.asInt ?: 8000
                val gamingHandshake = gameConfig.keepAliveInterval * 1000 // Convert to ms
                if (currentHandshake > gamingHandshake) {
                    level0Obj.addProperty("handshake", gamingHandshake)
                    Log.d(TAG, "Set gaming handshake timeout to ${gamingHandshake}ms")
                }
            }

            // Ensure connection idle timeout is appropriate for gaming (keep connections alive longer)
            val currentIdle = level0Obj.get("connIdle")?.asInt ?: 600000
            val gamingIdle = 300000.coerceAtMost(currentIdle) // 5 minutes max for gaming
            if (currentIdle > gamingIdle) {
                level0Obj.addProperty("connIdle", gamingIdle)
                Log.d(TAG, "Set gaming connection idle timeout to ${gamingIdle}ms")
            }

            // Apply protocol-level optimizations from ProtocolConfig
            applyProtocolConfig(root, prefs)

            Log.d(TAG, "Gaming config applied successfully for ${gameProfile.displayName}")
        } catch (e: Exception) {
            // Log error with full context - gaming optimizations are optional but failures should be visible
            Log.e(TAG, "Failed to apply gaming config, continuing without it: ${e.javaClass.simpleName}: ${e.message}", e)
            // Note: Gaming config is optional, so we continue without it rather than failing the entire config patch
        }
    }

    /**
     * Apply protocol-level optimizations (TLS, QUIC, compression) from ProtocolConfig
     */
    private fun applyProtocolConfig(root: JsonObject, prefs: com.simplexray.an.prefs.Preferences) {
        try {
            val protocolConfigJson = prefs.gamingProtocolConfig
            if (protocolConfigJson == null) {
                Log.d(TAG, "No gaming protocol config found, skipping protocol optimizations")
                return
            }

            val protocolConfig = gson.fromJson(protocolConfigJson, ProtocolConfig::class.java)
            if (protocolConfig == null) {
                Log.w(TAG, "Failed to parse gaming protocol config")
                return
            }

            Log.d(TAG, "Applying protocol config: HTTP/3=${protocolConfig.http3Enabled}, TLS1.3=${protocolConfig.tls13Enabled}, EarlyData=${protocolConfig.tls13EarlyData}")

            // Apply to all outbounds
            val outbounds = root.get("outbounds") as? JsonArray
            if (outbounds != null) {
                for (element in outbounds) {
                    if (element is JsonObject) {
                        applyProtocolConfigToOutbound(element, protocolConfig)
                    }
                }
            }

            // Apply transport-level settings (QUIC, etc.)
            applyTransportConfig(root, protocolConfig)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply protocol config: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    /**
     * Apply protocol config to a single outbound's streamSettings
     */
    private fun applyProtocolConfigToOutbound(outbound: JsonObject, config: ProtocolConfig) {
        try {
            // Get or create streamSettings
            val streamSettings = (outbound.get("streamSettings") as? JsonObject)
                ?: JsonObject().also { outbound.add("streamSettings", it) }

            // Apply TLS 1.3 settings
            // Note: Only apply if security is "tls" or not set (don't override "reality" or other security types)
            if (config.tls13Enabled) {
                val currentSecurity = streamSettings.get("security")?.asString
                
                // Only apply TLS settings if security is "tls" or not set
                if (currentSecurity == null || currentSecurity == "none" || currentSecurity == "tls") {
                    // Set security to "tls" if not already set
                    if (currentSecurity == null || currentSecurity == "none") {
                        streamSettings.addProperty("security", "tls")
                    }

                    // Get or create tlsSettings
                    val tlsSettings = (streamSettings.get("tlsSettings") as? JsonObject)
                        ?: JsonObject().also { streamSettings.add("tlsSettings", it) }

                    // Set minimum TLS version to 1.3 (only if not already set)
                    if (!tlsSettings.has("minVersion")) {
                        tlsSettings.addProperty("minVersion", "1.3")
                    }
                    if (!tlsSettings.has("maxVersion")) {
                        tlsSettings.addProperty("maxVersion", "1.3")
                    }

                    // Apply preferred cipher suites for gaming (low latency)
                    if (config.preferredCipherSuites.isNotEmpty() && !tlsSettings.has("cipherSuites")) {
                        val cipherSuites = JsonArray()
                        config.preferredCipherSuites.forEach { cipher ->
                            cipherSuites.add(cipher)
                        }
                        tlsSettings.add("cipherSuites", cipherSuites)
                    }

                    // Enable session tickets for faster reconnection (0-RTT support)
                    if (config.tls13SessionTickets && !tlsSettings.has("sessionTicket")) {
                        tlsSettings.addProperty("sessionTicket", true)
                    }

                    // Enable debug logging for BoringSSL handshake trace
                    // This will log: "crypto/tls: handshake start" and "crypto/tls: handshake done"
                    if (!tlsSettings.has("loglevel")) {
                        tlsSettings.addProperty("loglevel", "debug")
                    }

                    // Note: TLS 1.3 Early Data (0-RTT) is handled by Xray automatically when session tickets are enabled
                    Log.d(TAG, "Applied TLS 1.3 settings with early data support and handshake trace")
                } else {
                    Log.d(TAG, "Skipping TLS settings - outbound uses security: $currentSecurity")
                }
            }

            // Apply QUIC/HTTP/3 settings if enabled
            // Note: Only apply QUIC settings if network is already set to "quic" or not set
            // We don't force network change to avoid breaking existing configs
            if (config.http3Enabled) {
                val currentNetwork = streamSettings.get("network")?.asString
                
                // Only apply QUIC settings if network is quic or not set
                if (currentNetwork == null || currentNetwork == "quic") {
                    // Get or create quicSettings
                    val quicSettings = (streamSettings.get("quicSettings") as? JsonObject)
                        ?: JsonObject().also { streamSettings.add("quicSettings", it) }

                    // Apply QUIC optimizations for gaming
                    quicSettings.addProperty("maxIdleTimeout", config.quicIdleTimeout.toInt())
                    
                    // Note: security and key are typically set by server config
                    // We only optimize timeout settings for gaming

                    // Set network to quic if not already set
                    if (currentNetwork == null) {
                        streamSettings.addProperty("network", "quic")
                    }
                    
                    Log.d(TAG, "Applied QUIC/HTTP/3 settings for gaming")
                } else {
                    Log.d(TAG, "Skipping QUIC settings - outbound uses network: $currentNetwork")
                }
            }

            // Compression settings are typically handled at application level (HTTP headers)
            // Xray doesn't directly control compression in streamSettings, but we log it for reference
            if (!config.brotliEnabled && !config.gzipEnabled) {
                Log.d(TAG, "Compression disabled for gaming (lower latency)")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply protocol config to outbound: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    /**
     * Apply transport-level protocol settings
     */
    private fun applyTransportConfig(root: JsonObject, config: ProtocolConfig) {
        try {
            // Get or create transport section
            val transport = (root.get("transport") as? JsonObject)
                ?: JsonObject().also { root.add("transport", it) }

            // Apply QUIC transport settings if HTTP/3 is enabled
            if (config.http3Enabled) {
                val quicTransport = (transport.get("quicSettings") as? JsonObject)
                    ?: JsonObject().also { transport.add("quicSettings", it) }

                // Set QUIC version
                quicTransport.addProperty("maxIdleTimeout", config.quicIdleTimeout.toInt())
                
                // Note: maxIncomingStreams and maxOutgoingStreams are set per connection
                // Xray may have different parameter names, so we apply what we can
                
                Log.d(TAG, "Applied QUIC transport settings")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply transport config: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    /**
     * Apply advanced routing rules from Preferences to Xray routing configuration
     */
    private fun applyAdvancedRoutingRules(root: JsonObject, context: Context) {
        try {
            val prefs = Preferences(context)
            val rulesJson = prefs.advancedRoutingRules

            if (rulesJson.isNullOrBlank()) {
                Log.d(TAG, "No advanced routing rules found, skipping")
                return
            }

            // Parse routing rules from JSON
            val type = object : TypeToken<List<RoutingRule>>() {}.type
            val rules: List<RoutingRule> = try {
                gson.fromJson(rulesJson, type) ?: emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse advanced routing rules: ${e.message}", e)
                return
            }

            if (rules.isEmpty()) {
                Log.d(TAG, "Advanced routing rules list is empty")
                return
            }

            // Ensure routing section exists
            val routing = (root.get("routing") as? JsonObject) ?: JsonObject().also { root.add("routing", it) }
            val rulesArray = (routing.get("rules") as? JsonArray) ?: JsonArray().also { routing.add("rules", it) }

            // Remove existing advanced routing rules (identified by comment or marker)
            // We'll add a marker to identify our rules
            removeAdvancedRoutingRules(rulesArray)

            // Ensure required outbounds exist
            ensureRequiredOutbounds(root)

            // Convert and add advanced routing rules
            // Sort by priority (higher priority first) and filter enabled rules
            val enabledRules = rules
                .filter { it.enabled }
                .sortedByDescending { it.priority }

            var addedCount = 0
            var skippedCount = 0

            for (rule in enabledRules) {
                try {
                    val xrayRule = convertRoutingRuleToXray(rule)
                    if (xrayRule != null) {
                        // Add marker to identify this as an advanced routing rule
                        xrayRule.addProperty("_advancedRouting", true)
                        xrayRule.addProperty("_ruleId", rule.id)
                        xrayRule.addProperty("_ruleName", rule.name)
                        
                        rulesArray.add(xrayRule)
                        addedCount++
                        Log.d(TAG, "Added advanced routing rule: ${rule.name} (priority: ${rule.priority})")
                    } else {
                        skippedCount++
                        Log.w(TAG, "Skipped advanced routing rule: ${rule.name} (unsupported matchers)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to convert routing rule ${rule.name}: ${e.message}", e)
                    skippedCount++
                }
            }

            Log.d(TAG, "Advanced routing rules applied: $addedCount added, $skippedCount skipped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply advanced routing rules: ${e.javaClass.simpleName}: ${e.message}", e)
            // Note: Advanced routing is optional, so we continue without it rather than failing the entire config patch
        }
    }

    /**
     * Remove existing advanced routing rules from rules array
     */
    private fun removeAdvancedRoutingRules(rulesArray: JsonArray) {
        val indicesToRemove = mutableListOf<Int>()
        for (i in 0 until rulesArray.size()) {
            val rule = rulesArray[i]
            if (rule is JsonObject) {
                // Check if this is an advanced routing rule (has our marker)
                val isAdvanced = rule.get("_advancedRouting")?.asBoolean ?: false
                if (isAdvanced) {
                    indicesToRemove.add(i)
                }
            }
        }
        // Remove in reverse order to maintain indices
        for (i in indicesToRemove.reversed()) {
            rulesArray.remove(i)
        }
        if (indicesToRemove.isNotEmpty()) {
            Log.d(TAG, "Removed ${indicesToRemove.size} existing advanced routing rules")
        }
    }

    /**
     * Ensure required outbounds exist (direct, block, proxy)
     */
    private fun ensureRequiredOutbounds(root: JsonObject) {
        val outbounds = (root.get("outbounds") as? JsonArray) ?: JsonArray().also { root.add("outbounds", it) }

        // Check existing outbound tags
        val existingTags = mutableSetOf<String>()
        for (element in outbounds) {
            if (element is JsonObject) {
                val tag = element.get("tag")?.asString
                if (tag != null) {
                    existingTags.add(tag)
                }
            }
        }

        // Add direct outbound if missing
        if (!existingTags.contains("direct")) {
            val directOutbound = JsonObject().apply {
                addProperty("protocol", "freedom")
                addProperty("tag", "direct")
            }
            outbounds.add(directOutbound)
            Log.d(TAG, "Added direct outbound")
        }

        // Add block outbound if missing
        if (!existingTags.contains("block")) {
            val blockOutbound = JsonObject().apply {
                addProperty("protocol", "blackhole")
                addProperty("tag", "block")
            }
            outbounds.add(blockOutbound)
            Log.d(TAG, "Added block outbound")
        }

        // Note: "proxy" outbound should be provided by user config
        // We don't create it automatically as it depends on user's proxy setup
    }

    /**
     * Convert AdvancedRouter RoutingRule to Xray routing rule format
     * Returns null if rule contains unsupported matchers
     */
    private fun convertRoutingRuleToXray(rule: RoutingRule): JsonObject? {
        val xrayRule = JsonObject().apply {
            addProperty("type", "field")
        }

        var hasSupportedMatchers = false
        val domainList = mutableListOf<String>()
        val ipList = mutableListOf<String>()
        val portRanges = mutableListOf<Pair<Int, Int>>() // (start, end) pairs
        val networkList = mutableListOf<String>()
        val sourceList = mutableListOf<String>()

        // Convert matchers
        for (matcher in rule.matchers) {
            when (matcher) {
                is RoutingMatcher.DomainMatcher -> {
                    domainList.addAll(matcher.domains)
                    hasSupportedMatchers = true
                }
                is RoutingMatcher.IpMatcher -> {
                    // Convert CIDR ranges to Xray format
                    matcher.ipRanges.forEach { ipRange ->
                        ipList.add(ipRange.cidr)
                    }
                    hasSupportedMatchers = true
                }
                is RoutingMatcher.PortMatcher -> {
                    // Collect port ranges for processing
                    matcher.ports.forEach { portRange ->
                        portRanges.add(Pair(portRange.start, portRange.end))
                    }
                    hasSupportedMatchers = true
                }
                is RoutingMatcher.ProtocolMatcher -> {
                    matcher.protocols.forEach { protocol ->
                        when (protocol) {
                            AdvancedRouter.Protocol.TCP -> networkList.add("tcp")
                            AdvancedRouter.Protocol.UDP -> networkList.add("udp")
                            AdvancedRouter.Protocol.ICMP -> {
                                // Xray doesn't support ICMP in network field
                                // Skip or log warning
                                Log.w(TAG, "ICMP protocol not supported in Xray routing rules, skipping")
                            }
                            AdvancedRouter.Protocol.ANY -> {
                                // ANY means match all, so we don't add network restriction
                            }
                        }
                    }
                    if (networkList.isNotEmpty()) {
                        hasSupportedMatchers = true
                    }
                }
                is RoutingMatcher.GeoIpMatcher -> {
                    // Convert country codes to geoip format
                    matcher.countries.forEach { country ->
                        ipList.add("geoip:$country")
                    }
                    hasSupportedMatchers = true
                }
                is RoutingMatcher.AppMatcher -> {
                    // Xray supports source field for app-based routing on Android
                    // Note: This requires Xray to be configured with proper source support
                    sourceList.addAll(matcher.packages)
                    hasSupportedMatchers = true
                    Log.d(TAG, "App-based routing rule added (requires Xray source support): ${matcher.packages}")
                }
                is RoutingMatcher.TimeMatcher -> {
                    // Xray doesn't support time-based routing
                    Log.w(TAG, "Time-based routing not supported in Xray, rule ${rule.name} will be skipped")
                    return null
                }
                is RoutingMatcher.NetworkTypeMatcher -> {
                    // Xray doesn't support network type matching
                    Log.w(TAG, "Network type routing not supported in Xray, rule ${rule.name} will be skipped")
                    return null
                }
            }
        }

        // If no supported matchers, skip this rule
        if (!hasSupportedMatchers) {
            Log.w(TAG, "Rule ${rule.name} has no supported matchers, skipping")
            return null
        }

        // Add matcher fields to Xray rule
        if (domainList.isNotEmpty()) {
            val domainArray = JsonArray()
            domainList.forEach { domainArray.add(it) }
            xrayRule.add("domain", domainArray)
        }

        if (ipList.isNotEmpty()) {
            val ipArray = JsonArray()
            ipList.forEach { ipArray.add(it) }
            xrayRule.add("ip", ipArray)
        }

        if (portRanges.isNotEmpty()) {
            // Xray supports single port (integer) or port range (string "start:end")
            // For multiple port ranges, we'll use the first one
            // Note: Xray doesn't support multiple separate ports/ranges in a single rule
            // If multiple ports are needed, consider creating separate rules
            val firstRange = portRanges[0]
            if (firstRange.first == firstRange.second) {
                // Single port
                xrayRule.addProperty("port", firstRange.first)
            } else {
                // Port range - Xray uses string format "start:end"
                xrayRule.addProperty("port", "${firstRange.first}:${firstRange.second}")
            }
            if (portRanges.size > 1) {
                Log.d(TAG, "Multiple port ranges in rule ${rule.name}, using first: ${firstRange.first}${if (firstRange.first != firstRange.second) ":${firstRange.second}" else ""}")
            }
        }

        if (networkList.isNotEmpty()) {
            // Xray supports single network type
            xrayRule.addProperty("network", networkList[0])
            if (networkList.size > 1) {
                Log.d(TAG, "Multiple network types in rule ${rule.name}, using first: ${networkList[0]}")
            }
        }

        if (sourceList.isNotEmpty()) {
            val sourceArray = JsonArray()
            sourceList.forEach { sourceArray.add(it) }
            xrayRule.add("source", sourceArray)
        }

        // Convert action to outboundTag
        val outboundTag = when (rule.action) {
            is RoutingAction.Proxy -> "proxy" // Default proxy outbound
            is RoutingAction.Direct -> "direct"
            is RoutingAction.Block -> "block"
            is RoutingAction.CustomProxy -> {
                // Use custom proxy ID as outbound tag
                // Note: This assumes the custom proxy outbound exists with this tag
                rule.action.proxyId
            }
        }

        xrayRule.addProperty("outboundTag", outboundTag)

        return xrayRule
    }
}
