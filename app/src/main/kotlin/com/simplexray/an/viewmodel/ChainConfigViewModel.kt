package com.simplexray.an.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonParser
import com.simplexray.an.chain.hysteria2.Hy2Config
import com.simplexray.an.chain.reality.RealityConfig
import com.simplexray.an.chain.reality.TlsFingerprintProfile
import com.simplexray.an.common.AppLogger
import com.simplexray.an.prefs.Preferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.InetSocketAddress

/**
 * ViewModel for Chain Config Selection
 * 
 * Manages:
 * - Xray config files and their Reality configs
 * - Hysteria2 config files
 * - Selected configs for chain
 */
class ChainConfigViewModel(application: Application) : AndroidViewModel(application) {
    
    private val prefs = Preferences(application)
    
    // Xray configs with extracted Reality configs
    private val _xrayConfigs = MutableStateFlow<List<Pair<File, RealityConfig?>>>(emptyList())
    val xrayConfigs: StateFlow<List<Pair<File, RealityConfig?>>> = _xrayConfigs.asStateFlow()
    
    // Hysteria2 configs
    private val _hysteria2Configs = MutableStateFlow<List<Pair<File, Hy2Config?>>>(emptyList())
    val hysteria2Configs: StateFlow<List<Pair<File, Hy2Config?>>> = _hysteria2Configs.asStateFlow()
    
    // Selected configs
    private val _selectedRealityConfig = MutableStateFlow<RealityConfig?>(null)
    val selectedRealityConfig: StateFlow<RealityConfig?> = _selectedRealityConfig.asStateFlow()
    
    private val _selectedHysteria2Config = MutableStateFlow<Hy2Config?>(null)
    val selectedHysteria2Config: StateFlow<Hy2Config?> = _selectedHysteria2Config.asStateFlow()
    
    /**
     * Load Xray config files and extract Reality configs
     */
    fun loadXrayConfigs() {
        viewModelScope.launch {
            try {
                val filesDir = getApplication<Application>().filesDir
                val configFiles = filesDir.listFiles { file ->
                    file.isFile && file.name.endsWith(".json")
                }?.toList() ?: emptyList()
                
                val configsWithReality = configFiles.map { file ->
                    try {
                        val realityConfig = extractRealityConfigFromXray(file.absolutePath)
                        file to realityConfig
                    } catch (e: Exception) {
                        AppLogger.w("Failed to extract Reality config from ${file.name}: ${e.message}")
                        file to null
                    }
                }
                
                _xrayConfigs.value = configsWithReality
            } catch (e: Exception) {
                AppLogger.e("Failed to load Xray configs: ${e.message}", e)
                _xrayConfigs.value = emptyList()
            }
        }
    }
    
    /**
     * Load Hysteria2 config files
     */
    fun loadHysteria2Configs() {
        viewModelScope.launch {
            try {
                val filesDir = getApplication<Application>().filesDir
                // Look for Hysteria2 config files (hysteria2-*.json or hy2-*.json)
                val hysteria2Files = filesDir.listFiles { file ->
                    file.isFile && (
                        file.name.startsWith("hysteria2-") && file.name.endsWith(".json") ||
                        file.name.startsWith("hy2-") && file.name.endsWith(".json") ||
                        file.name.endsWith("-hysteria2.json")
                    )
                }?.toList() ?: emptyList()
                
                val configs = hysteria2Files.map { file ->
                    try {
                        val configJson = file.readText()
                        val hy2Config = parseHysteria2Config(configJson)
                        file to hy2Config
                    } catch (e: Exception) {
                        AppLogger.w("Failed to parse Hysteria2 config ${file.name}: ${e.message}")
                        file to null
                    }
                }
                
                _hysteria2Configs.value = configs
            } catch (e: Exception) {
                AppLogger.e("Failed to load Hysteria2 configs: ${e.message}", e)
                _hysteria2Configs.value = emptyList()
            }
        }
    }
    
    /**
     * Select Reality config
     */
    fun selectRealityConfig(config: RealityConfig?) {
        _selectedRealityConfig.value = config
        AppLogger.d("Selected Reality config: ${config?.server}:${config?.port}")
    }
    
    /**
     * Select Hysteria2 config
     */
    fun selectHysteria2Config(config: Hy2Config?) {
        _selectedHysteria2Config.value = config
        AppLogger.d("Selected Hysteria2 config: ${config?.server}:${config?.port}")
    }
    
    /**
     * Extract Reality config from Xray config file
     */
    private fun extractRealityConfigFromXray(configPath: String): RealityConfig? {
        return try {
            val configFile = File(configPath)
            if (!configFile.exists() || !configFile.canRead()) {
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
                            val uuid = users?.get("id")?.asString ?: return null
                            
                            val publicKey = realitySettings.get("publicKey")?.asString
                            // Validate publicKey is not empty
                            if (publicKey.isNullOrBlank()) {
                                AppLogger.w("ChainConfigViewModel: Found REALITY outbound with empty publicKey, skipping")
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
                            
                            return RealityConfig(
                                server = server,
                                port = port,
                                shortId = shortId,
                                publicKey = publicKey,
                                serverName = finalServerName,
                                fingerprintProfile = fingerprintProfile,
                                localPort = 10808,
                                uuid = uuid
                            )
                        }
                    }
                }
            }
            
            null
        } catch (e: Exception) {
            AppLogger.w("Failed to extract Reality config: ${e.message}")
            null
        }
    }
    
    /**
     * Parse Hysteria2 config from JSON
     */
    private fun parseHysteria2Config(configJson: String): Hy2Config? {
        return try {
            val root = JsonParser.parseString(configJson).asJsonObject
            
            val serverStr = root.get("server")?.asString ?: return null
            val serverParts = serverStr.split(":")
            val server = serverParts[0]
            val port = if (serverParts.size > 1) serverParts[1].toIntOrNull() ?: 443 else 443
            
            val auth = root.get("auth")?.asString ?: return null
            val alpn = root.get("alpn")?.asString ?: "h3"
            val sni = root.get("sni")?.asString
            val insecure = root.get("insecure")?.asBoolean ?: false
            
            // Bandwidth
            val bandwidth = root.getAsJsonObject("bandwidth")
            val upStr = bandwidth?.get("up")?.asString?.replace("Mbps", "")?.toIntOrNull() ?: 0
            val downStr = bandwidth?.get("down")?.asString?.replace("Mbps", "")?.toIntOrNull() ?: 0
            
            // Obfs
            val obfs = root.getAsJsonObject("obfs")
            val obfsType = obfs?.get("type")?.asString
            val obfsPassword = obfs?.get("password")?.asString
            
            // Other settings
            val fastOpen = root.get("fastOpen")?.asBoolean ?: true
            val bandwidthProbe = root.get("bandwidthProbe")?.asBoolean ?: true
            
            // Upstream SOCKS (for chaining)
            val proxy = root.getAsJsonObject("proxy")
            val upstreamSocksAddr = proxy?.get("url")?.asString?.let { url ->
                // Parse socks5://127.0.0.1:10808
                val regex = Regex("socks5://([^:]+):(\\d+)")
                regex.find(url)?.let { match ->
                    val host = match.groupValues[1]
                    val port = match.groupValues[2].toIntOrNull() ?: 10808
                    InetSocketAddress(host, port)
                }
            }
            
            Hy2Config(
                server = server,
                port = port,
                auth = auth,
                alpn = alpn,
                sni = sni,
                upRateMbps = upStr,
                downRateMbps = downStr,
                bandwidthProbing = bandwidthProbe,
                zeroRttEnabled = fastOpen,
                insecure = insecure,
                obfs = obfsType,
                obfsPassword = obfsPassword,
                upstreamSocksAddr = upstreamSocksAddr
            )
        } catch (e: Exception) {
            AppLogger.w("Failed to parse Hysteria2 config: ${e.message}")
            null
        }
    }
}

