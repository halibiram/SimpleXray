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
 * x-ui VLESS Config Converter
 * 
 * Converts x-ui generated VLESS configs (link or JSON) to Xray JSON format
 * 
 * x-ui VLESS Link Format:
 * vless://UUID@host:port?type=tcp&security=reality&pbk=publicKey&sid=shortId&fp=chrome&spx=serverName&dest=destination&xver=0#remark
 * 
 * x-ui JSON Format:
 * {
 *   "v": "2",
 *   "ps": "remark",
 *   "add": "host",
 *   "port": 443,
 *   "id": "uuid",
 *   "aid": "0",
 *   "scy": "none",
 *   "net": "tcp",
 *   "type": "none",
 *   "host": "",
 *   "path": "",
 *   "tls": "reality",
 *   "sni": "",
 *   "alpn": "",
 *   "fp": "chrome",
 *   "pbk": "publicKey",
 *   "sid": "shortId",
 *   "spx": "serverName"
 * }
 */
class XuiVlessConverter : ConfigFormatConverter {
    
    override fun detect(content: String): Boolean {
        val trimmed = content.trim()
        // Detect x-ui VLESS link
        if (trimmed.startsWith("vless://", ignoreCase = true)) {
            // Check for x-ui specific parameters
            return trimmed.contains("pbk=") || trimmed.contains("sid=") || 
                   trimmed.contains("spx=") || trimmed.contains("dest=")
        }
        // Detect x-ui JSON format
        if (trimmed.startsWith("{") && trimmed.contains("\"pbk\"") || trimmed.contains("\"sid\"")) {
            return try {
                val json = JsonParser.parseString(trimmed).asJsonObject
                json.has("v") && json.has("add") && json.has("id")
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
            if (trimmed.startsWith("vless://", ignoreCase = true)) {
                convertFromLink(context, trimmed)
            } else {
                // Try JSON format
                convertFromJson(context, trimmed)
            }
        } catch (e: Exception) {
            try {
                Log.e(TAG, "Failed to convert x-ui VLESS config", e)
            } catch (logException: Exception) {
                // Log not available in unit tests
            }
            Result.failure(RuntimeException("Failed to convert x-ui VLESS config: ${e.message}", e))
        }
    }
    
    /**
     * Convert x-ui VLESS link to Xray JSON
     */
    private fun convertFromLink(context: Context, link: String): Result<DetectedConfig> {
        val uri = URI(link)
        
        if (uri.scheme?.lowercase() != "vless") {
            return Result.failure(RuntimeException("Invalid VLESS URI scheme"))
        }
        
        // Extract UUID
        val uuid = uri.userInfo ?: return Result.failure(RuntimeException("Missing UUID"))
        
        // Extract host and port
        val host = uri.host ?: return Result.failure(RuntimeException("Missing host"))
        val port = if (uri.port > 0) uri.port else 443
        
        // Parse query parameters (x-ui format)
        val queryParams = parseQuery(uri.query)
        
        // x-ui specific parameters
        val network = queryParams["type"] ?: queryParams["net"] ?: "tcp"
        val security = queryParams["security"] ?: queryParams["tls"] ?: "none"
        val pbk = queryParams["pbk"] // publicKey
        val sid = queryParams["sid"] // shortId
        val fp = queryParams["fp"] ?: "chrome" // fingerprint
        val spx = queryParams["spx"] // serverName
        val dest = queryParams["dest"] // destination
        val sni = queryParams["sni"]
        val xver = queryParams["xver"] ?: "0"
        val flow = queryParams["flow"]
        val encryption = queryParams["encryption"] ?: queryParams["scy"] ?: "none"
        
        // Build Xray config
        val config = buildXrayConfig(
            uuid = uuid,
            host = host,
            port = port,
            network = network,
            security = security,
            sni = sni,
            flow = flow,
            encryption = encryption,
            pbk = pbk,
            sid = sid,
            fp = fp,
            spx = spx,
            dest = dest,
            xver = xver,
            queryParams = queryParams
        )
        
        // Generate filename from fragment or host:port
        val name = if (!uri.fragment.isNullOrBlank()) {
            URLDecoder.decode(uri.fragment, "UTF-8")
        } else {
            "xui_vless_${host}_${port}"
        }
        
        val filenameError = FilenameValidator.validateFilename(context, name)
        if (filenameError != null) {
            return Result.failure(RuntimeException("Invalid filename: $filenameError"))
        }
        
        return Result.success(name to config)
    }
    
    /**
     * Convert x-ui JSON format to Xray JSON
     */
    private fun convertFromJson(context: Context, jsonContent: String): Result<DetectedConfig> {
        val json = JsonParser.parseString(jsonContent).asJsonObject
        
        // Extract basic info
        val uuid = json.get("id")?.asString ?: return Result.failure(RuntimeException("Missing id"))
        val host = json.get("add")?.asString ?: return Result.failure(RuntimeException("Missing add"))
        val port = json.get("port")?.asInt ?: 443
        
        // Extract network and security
        val network = json.get("net")?.asString ?: "tcp"
        val security = json.get("tls")?.asString ?: "none"
        val encryption = json.get("scy")?.asString ?: "none"
        val flow = json.get("flow")?.asString
        
        // x-ui Reality parameters
        val pbk = json.get("pbk")?.asString
        val sid = json.get("sid")?.asString
        val fp = json.get("fp")?.asString ?: "chrome"
        val spx = json.get("spx")?.asString
        val sni = json.get("sni")?.asString
        
        // Build query params map for compatibility
        val queryParams = mutableMapOf<String, String>()
        queryParams["type"] = network
        queryParams["security"] = security
        queryParams["encryption"] = encryption
        pbk?.let { queryParams["pbk"] = it }
        sid?.let { queryParams["sid"] = it }
        queryParams["fp"] = fp
        spx?.let { queryParams["spx"] = it }
        sni?.let { queryParams["sni"] = it }
        flow?.let { queryParams["flow"] = it }
        
        // WebSocket/HTTP/GRPC specific
        json.get("host")?.asString?.let { queryParams["host"] = it }
        json.get("path")?.asString?.let { queryParams["path"] = it }
        json.get("serviceName")?.asString?.let { queryParams["serviceName"] = it }
        
        // Build Xray config
        val config = buildXrayConfig(
            uuid = uuid,
            host = host,
            port = port,
            network = network,
            security = security,
            sni = sni,
            flow = flow,
            encryption = encryption,
            pbk = pbk,
            sid = sid,
            fp = fp,
            spx = spx,
            dest = null, // Not in x-ui JSON format
            xver = "0",
            queryParams = queryParams
        )
        
        // Generate filename from remark or host:port
        val name = json.get("ps")?.asString?.takeIf { it.isNotBlank() } 
            ?: "xui_vless_${host}_${port}"
        
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
    
    private fun buildXrayConfig(
        uuid: String,
        host: String,
        port: Int,
        network: String,
        security: String,
        sni: String?,
        flow: String?,
        encryption: String,
        pbk: String?,
        sid: String?,
        fp: String,
        spx: String?,
        dest: String?,
        xver: String,
        queryParams: Map<String, String>
    ): String {
        return buildString {
            appendLine("{")
            appendLine("  \"log\": {")
            appendLine("    \"loglevel\": \"warning\"")
            appendLine("  },")
            appendLine("  \"inbounds\": [")
            appendLine("    {")
            appendLine("      \"listen\": \"127.0.0.1\",")
            appendLine("      \"port\": 10808,")
            appendLine("      \"protocol\": \"socks\",")
            appendLine("      \"settings\": {")
            appendLine("        \"auth\": \"noauth\",")
            appendLine("        \"udp\": true")
            appendLine("      },")
            appendLine("      \"sniffing\": {")
            appendLine("        \"enabled\": true,")
            appendLine("        \"destOverride\": [\"http\", \"tls\", \"quic\", \"fakedns\"]")
            appendLine("      },")
            appendLine("      \"tag\": \"socks\"")
            appendLine("    }")
            appendLine("  ],")
            appendLine("  \"outbounds\": [")
            appendLine("    {")
            appendLine("      \"protocol\": \"vless\",")
            appendLine("      \"tag\": \"proxy\",")
            appendLine("      \"settings\": {")
            appendLine("        \"vnext\": [")
            appendLine("          {")
            appendLine("            \"address\": \"$host\",")
            appendLine("            \"port\": $port,")
            appendLine("            \"users\": [")
            appendLine("              {")
            appendLine("                \"id\": \"$uuid\",")
            appendLine("                \"encryption\": \"$encryption\"")
            if (!flow.isNullOrBlank()) {
                appendLine(",")
                appendLine("                \"flow\": \"$flow\"")
            }
            appendLine("              }")
            appendLine("            ]")
            appendLine("          }")
            appendLine("        ]")
            appendLine("      },")
            appendLine("      \"streamSettings\": {")
            appendLine("        \"network\": \"$network\"")
            
            // Network-specific settings
            when (network.lowercase()) {
                "ws", "websocket" -> {
                    appendLine(",")
                    appendLine("        \"wsSettings\": {")
                    val path = queryParams["path"] ?: "/"
                    appendLine("          \"path\": \"$path\"")
                    val hostHeader = queryParams["host"]
                    if (!hostHeader.isNullOrBlank()) {
                        appendLine(",")
                        appendLine("          \"headers\": {")
                        appendLine("            \"Host\": \"$hostHeader\"")
                        appendLine("          }")
                    }
                    appendLine("        }")
                }
                "grpc" -> {
                    appendLine(",")
                    appendLine("        \"grpcSettings\": {")
                    val serviceName = queryParams["serviceName"] ?: queryParams["sni"] ?: ""
                    appendLine("          \"serviceName\": \"$serviceName\"")
                    appendLine("        }")
                }
                "http", "h2" -> {
                    appendLine(",")
                    appendLine("        \"httpSettings\": {")
                    val path = queryParams["path"] ?: "/"
                    appendLine("          \"path\": \"$path\"")
                    val hostHeader = queryParams["host"]
                    if (!hostHeader.isNullOrBlank()) {
                        appendLine(",")
                        appendLine("          \"host\": [\"$hostHeader\"]")
                    }
                    appendLine("        }")
                }
                else -> {
                    // TCP
                    appendLine(",")
                    appendLine("        \"tcpSettings\": {")
                    appendLine("          \"header\": {")
                    appendLine("            \"type\": \"none\"")
                    appendLine("          }")
                    appendLine("        }")
                }
            }
            
            // Security settings (x-ui Reality format)
            if (security == "reality") {
                appendLine(",")
                appendLine("        \"security\": \"reality\",")
                appendLine("        \"realitySettings\": {")
                appendLine("          \"show\": false")
                
                if (!dest.isNullOrBlank()) {
                    appendLine(",")
                    appendLine("          \"dest\": \"$dest\"")
                } else if (!spx.isNullOrBlank()) {
                    // Use spx as dest if dest not provided
                    appendLine(",")
                    appendLine("          \"dest\": \"$spx:443\"")
                }
                
                if (xver != "0") {
                    appendLine(",")
                    appendLine("          \"xver\": $xver")
                }
                
                if (!spx.isNullOrBlank()) {
                    appendLine(",")
                    appendLine("          \"serverNames\": [\"$spx\"]")
                } else if (!sni.isNullOrBlank()) {
                    appendLine(",")
                    appendLine("          \"serverNames\": [\"$sni\"]")
                }
                
                if (!pbk.isNullOrBlank()) {
                    appendLine(",")
                    appendLine("          \"publicKey\": \"$pbk\"")
                }
                
                if (!sid.isNullOrBlank()) {
                    appendLine(",")
                    appendLine("          \"shortIds\": [\"$sid\"]")
                }
                
                appendLine(",")
                appendLine("          \"minClientVer\": \"\",")
                appendLine("          \"maxClientVer\": \"\",")
                appendLine("          \"maxTimeDiff\": 0")
                appendLine("        },")
                appendLine("        \"fingerprint\": \"$fp\"")
            } else if (security == "tls") {
                appendLine(",")
                appendLine("        \"security\": \"tls\",")
                appendLine("        \"tlsSettings\": {")
                if (!sni.isNullOrBlank()) {
                    appendLine("          \"serverName\": \"$sni\"")
                } else {
                    appendLine("          \"serverName\": \"\"")
                }
                appendLine("        },")
                appendLine("        \"fingerprint\": \"$fp\"")
            }
            
            appendLine("      }")
            appendLine("    },")
            appendLine("    {")
            appendLine("      \"protocol\": \"freedom\",")
            appendLine("      \"tag\": \"direct\"")
            appendLine("    },")
            appendLine("    {")
            appendLine("      \"protocol\": \"blackhole\",")
            appendLine("      \"tag\": \"block\"")
            appendLine("    }")
            appendLine("  ],")
            appendLine("  \"routing\": {")
            appendLine("    \"domainStrategy\": \"IPIfNonMatch\",")
            appendLine("    \"rules\": [")
            appendLine("      {")
            appendLine("        \"type\": \"field\",")
            appendLine("        \"outboundTag\": \"proxy\",")
            appendLine("        \"network\": \"tcp,udp\"")
            appendLine("      }")
            appendLine("    ]")
            appendLine("  }")
            appendLine("}")
        }
    }
}







