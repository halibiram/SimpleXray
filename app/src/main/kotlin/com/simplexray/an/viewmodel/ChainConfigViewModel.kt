package com.simplexray.an.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonParser
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
 * - Selected configs for chain
 */
class ChainConfigViewModel(application: Application) : AndroidViewModel(application) {
    
    private val prefs = Preferences(application)
    
    // Xray configs with extracted Reality configs
    private val _xrayConfigs = MutableStateFlow<List<Pair<File, RealityConfig?>>>(emptyList())
    val xrayConfigs: StateFlow<List<Pair<File, RealityConfig?>>> = _xrayConfigs.asStateFlow()
    
    // Selected configs
    private val _selectedRealityConfig = MutableStateFlow<RealityConfig?>(null)
    val selectedRealityConfig: StateFlow<RealityConfig?> = _selectedRealityConfig.asStateFlow()
    
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
     * Select Reality config
     */
    fun selectRealityConfig(config: RealityConfig?) {
        _selectedRealityConfig.value = config
        AppLogger.d("Selected Reality config: ${config?.server}:${config?.port}")
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
    
}

