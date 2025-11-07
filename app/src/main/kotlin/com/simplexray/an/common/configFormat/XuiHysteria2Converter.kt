package com.simplexray.an.common.configFormat

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.simplexray.an.common.FilenameValidator
import com.simplexray.an.data.source.FileManager.Companion.TAG
import java.net.URI
import java.net.URLDecoder

/**
 * x-ui Hysteria2 Config Converter
 * 
 * Converts x-ui generated Hysteria2 configs (link or JSON) to Hysteria2 JSON format
 * 
 * x-ui Hysteria2 Link Format:
 * hysteria2://auth@host:port?insecure=0&upmbps=100&downmbps=500&obfs=xplus&obfs-password=password&sni=www.youtube.com#remark
 * 
 * x-ui JSON Format:
 * {
 *   "v": "2",
 *   "ps": "remark",
 *   "add": "host",
 *   "port": 443,
 *   "auth": "auth-string",
 *   "obfs": "xplus",
 *   "obfs-password": "password",
 *   "sni": "www.youtube.com",
 *   "insecure": "0",
 *   "upmbps": "100",
 *   "downmbps": "500"
 * }
 */
class XuiHysteria2Converter : ConfigFormatConverter {
    
    override fun detect(content: String): Boolean {
        val trimmed = content.trim()
        // Detect x-ui Hysteria2 link
        if (trimmed.startsWith("hysteria2://", ignoreCase = true) || 
            trimmed.startsWith("hysteria://", ignoreCase = true)) {
            return true
        }
        // Detect x-ui JSON format
        if (trimmed.startsWith("{") && (trimmed.contains("\"obfs\"") || trimmed.contains("\"upmbps\""))) {
            return try {
                val json = JsonParser.parseString(trimmed).asJsonObject
                json.has("v") && json.has("add") && json.has("auth")
            } catch (e: Exception) {
                false
            }
        }
        return false
    }
    
    override fun convert(context: Context, content: String): Result<DetectedConfig> {
        return try {
            val trimmed = content.trim()
            
            // Try link format first
            if (trimmed.startsWith("hysteria2://", ignoreCase = true) || 
                trimmed.startsWith("hysteria://", ignoreCase = true)) {
                convertFromLink(context, trimmed)
            } else {
                // Try JSON format
                convertFromJson(context, trimmed)
            }
        } catch (e: Exception) {
            try {
                Log.e(TAG, "Failed to convert x-ui Hysteria2 config", e)
            } catch (logException: Exception) {
                // Log not available in unit tests
            }
            Result.failure(RuntimeException("Failed to convert x-ui Hysteria2 config: ${e.message}", e))
        }
    }
    
    /**
     * Convert x-ui Hysteria2 link to Hysteria2 JSON
     */
    private fun convertFromLink(context: Context, link: String): Result<DetectedConfig> {
        val uri = URI(link)
        
        val scheme = uri.scheme?.lowercase()
        if (scheme != "hysteria2" && scheme != "hysteria") {
            return Result.failure(RuntimeException("Invalid Hysteria2 URI scheme"))
        }
        
        // Extract auth
        val auth = uri.userInfo ?: return Result.failure(RuntimeException("Missing auth"))
        
        // Extract host and port
        val host = uri.host ?: return Result.failure(RuntimeException("Missing host"))
        val port = if (uri.port > 0) uri.port else 443
        
        // Parse query parameters (x-ui format)
        val queryParams = parseQuery(uri.query)
        
        // x-ui specific parameters
        val insecure = queryParams["insecure"] ?: "0"
        val upmbps = queryParams["upmbps"]?.toIntOrNull() ?: 0
        val downmbps = queryParams["downmbps"]?.toIntOrNull() ?: 0
        val obfs = queryParams["obfs"]
        val obfsPassword = queryParams["obfs-password"] ?: queryParams["obfspassword"]
        val sni = queryParams["sni"]
        val alpn = queryParams["alpn"] ?: "h3"
        val fastOpen = queryParams["fastopen"]?.toBoolean() ?: true
        val bandwidthProbe = queryParams["bandwidthprobe"]?.toBoolean() ?: true
        
        // Build Hysteria2 config
        val config = buildHysteria2Config(
            auth = auth,
            host = host,
            port = port,
            insecure = insecure == "1",
            upmbps = upmbps,
            downmbps = downmbps,
            obfs = obfs,
            obfsPassword = obfsPassword,
            sni = sni,
            alpn = alpn,
            fastOpen = fastOpen,
            bandwidthProbe = bandwidthProbe
        )
        
        // Generate filename from fragment or host:port
        val name = if (!uri.fragment.isNullOrBlank()) {
            URLDecoder.decode(uri.fragment, "UTF-8")
        } else {
            "xui_hysteria2_${host}_${port}"
        }
        
        val filenameError = FilenameValidator.validateFilename(context, name)
        if (filenameError != null) {
            return Result.failure(RuntimeException("Invalid filename: $filenameError"))
        }
        
        return Result.success(name to config)
    }
    
    /**
     * Convert x-ui JSON format to Hysteria2 JSON
     */
    private fun convertFromJson(context: Context, jsonContent: String): Result<DetectedConfig> {
        val json = JsonParser.parseString(jsonContent).asJsonObject
        
        // Extract basic info
        val auth = json.get("auth")?.asString ?: return Result.failure(RuntimeException("Missing auth"))
        val host = json.get("add")?.asString ?: return Result.failure(RuntimeException("Missing add"))
        val port = json.get("port")?.asInt ?: 443
        
        // Extract x-ui specific parameters
        val insecure = json.get("insecure")?.asString ?: "0"
        val upmbps = json.get("upmbps")?.asString?.toIntOrNull() ?: 0
        val downmbps = json.get("downmbps")?.asString?.toIntOrNull() ?: 0
        val obfs = json.get("obfs")?.asString
        val obfsPassword = json.get("obfs-password")?.asString ?: json.get("obfspassword")?.asString
        val sni = json.get("sni")?.asString
        val alpn = json.get("alpn")?.asString ?: "h3"
        val fastOpen = json.get("fastopen")?.asBoolean ?: true
        val bandwidthProbe = json.get("bandwidthprobe")?.asBoolean ?: true
        
        // Build Hysteria2 config
        val config = buildHysteria2Config(
            auth = auth,
            host = host,
            port = port,
            insecure = insecure == "1",
            upmbps = upmbps,
            downmbps = downmbps,
            obfs = obfs,
            obfsPassword = obfsPassword,
            sni = sni,
            alpn = alpn,
            fastOpen = fastOpen,
            bandwidthProbe = bandwidthProbe
        )
        
        // Generate filename from remark or host:port
        val name = json.get("ps")?.asString?.takeIf { it.isNotBlank() } 
            ?: "xui_hysteria2_${host}_${port}"
        
        val filenameError = FilenameValidator.validateFilename(context, name)
        if (filenameError != null) {
            return Result.failure(RuntimeException("Invalid filename: $filenameError"))
        }
        
        return Result.success(name to config)
    }
    
    private fun parseQuery(query: String?): Map<String, String> {
        if (query.isNullOrBlank()) return emptyMap()
        
        return query.split("&").associate { param ->
            val parts = param.split("=", limit = 2)
            val key = URLDecoder.decode(parts[0], "UTF-8")
            val value = if (parts.size > 1) URLDecoder.decode(parts[1], "UTF-8") else ""
            key to value
        }
    }
    
    private fun buildHysteria2Config(
        auth: String,
        host: String,
        port: Int,
        insecure: Boolean,
        upmbps: Int,
        downmbps: Int,
        obfs: String?,
        obfsPassword: String?,
        sni: String?,
        alpn: String,
        fastOpen: Boolean,
        bandwidthProbe: Boolean
    ): String {
        return buildString {
            appendLine("{")
            appendLine("  \"logLevel\": \"info\",")
            appendLine("  \"server\": \"$host:$port\",")
            appendLine("  \"auth\": \"$auth\",")
            appendLine("  \"alpn\": \"$alpn\"")
            
            // SNI (Server Name Indication)
            if (!sni.isNullOrBlank()) {
                appendLine(",")
                appendLine("  \"sni\": \"$sni\"")
            }
            
            // Insecure (skip certificate verification)
            if (insecure) {
                appendLine(",")
                appendLine("  \"insecure\": true")
            }
            
            // Bandwidth settings
            if (upmbps > 0 || downmbps > 0) {
                appendLine(",")
                appendLine("  \"bandwidth\": {")
                if (upmbps > 0) {
                    appendLine("    \"up\": \"${upmbps}Mbps\"")
                    if (downmbps > 0) {
                        appendLine(",")
                    }
                }
                if (downmbps > 0) {
                    appendLine("    \"down\": \"${downmbps}Mbps\"")
                }
                appendLine("  }")
            }
            
            // Obfuscation (x-ui obfs support)
            if (!obfs.isNullOrBlank() && obfs == "xplus") {
                appendLine(",")
                appendLine("  \"obfs\": {")
                appendLine("    \"type\": \"xplus\"")
                if (!obfsPassword.isNullOrBlank()) {
                    appendLine(",")
                    appendLine("    \"password\": \"$obfsPassword\"")
                }
                appendLine("  }")
            }
            
            // QUIC settings
            appendLine(",")
            appendLine("  \"quic\": {")
            appendLine("    \"initStreamReceiveWindow\": 8388608,")
            appendLine("    \"maxStreamReceiveWindow\": 8388608,")
            appendLine("    \"initConnReceiveWindow\": 20971520,")
            appendLine("    \"maxConnReceiveWindow\": 20971520,")
            appendLine("    \"maxIdleTimeout\": 30,")
            appendLine("    \"maxIncomingStreams\": 1024,")
            appendLine("    \"disablePathMTUDiscovery\": false")
            appendLine("  }")
            
            // Fast Open (0-RTT)
            if (fastOpen) {
                appendLine(",")
                appendLine("  \"fastOpen\": true")
            }
            
            // Bandwidth probing
            if (bandwidthProbe) {
                appendLine(",")
                appendLine("  \"bandwidthProbe\": true")
            }
            
            // SOCKS5 proxy (for upstream chaining - can be added later)
            // This will be added when chain is configured
            appendLine(",")
            appendLine("  \"socks5\": {")
            appendLine("    \"listen\": \"127.0.0.1:0\"")
            appendLine("  }")
            
            appendLine("}")
        }
    }
}

