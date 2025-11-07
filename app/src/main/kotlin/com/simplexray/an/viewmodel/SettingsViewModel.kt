package com.simplexray.an.viewmodel

import android.app.Application
import java.net.URL
import com.simplexray.an.BuildConfig
import com.simplexray.an.R
import com.simplexray.an.common.AppLogger
import com.simplexray.an.common.ThemeMode
import com.simplexray.an.data.source.FileManager
import com.simplexray.an.prefs.Preferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.regex.Pattern

private const val TAG = "SettingsViewModel"

/**
 * ViewModel for managing application settings.
 * Handles network settings, DNS configuration, theme, and connectivity testing.
 */
class SettingsViewModel(
    application: Application,
    private val prefs: Preferences,
    private val fileManager: FileManager,
    private val uiEventSender: (MainViewUiEvent) -> Unit
) : AndroidViewModel(application) {
    
    private val _settingsState = MutableStateFlow(
        SettingsState(
            socksPort = InputFieldState(prefs.socksPort.toString()),
            dnsIpv4 = InputFieldState(prefs.dnsIpv4),
            dnsIpv6 = InputFieldState(prefs.dnsIpv6),
            switches = SwitchStates(
                ipv6Enabled = prefs.ipv6,
                useTemplateEnabled = prefs.useTemplate,
                httpProxyEnabled = prefs.httpProxyEnabled,
                bypassLanEnabled = prefs.bypassLan,
                disableVpn = prefs.disableVpn,
                themeMode = prefs.theme,
                enablePerformanceMode = prefs.enablePerformanceMode
            ),
            info = InfoStates(
                appVersion = BuildConfig.VERSION_NAME,
                kernelVersion = "N/A",
                geoipSummary = "",
                geositeSummary = "",
                geoipUrl = prefs.geoipUrl,
                geositeUrl = prefs.geositeUrl
            ),
            files = FileStates(
                isGeoipCustom = prefs.customGeoipImported,
                isGeositeCustom = prefs.customGeositeImported
            ),
            connectivityTestTarget = InputFieldState(prefs.connectivityTestTarget),
            connectivityTestTimeout = InputFieldState(prefs.connectivityTestTimeout.toString()),
            quicSettings = QuicSettings(
                enabled = prefs.enableQuicMode,
                serverHost = prefs.quicServerHost ?: "",
                serverPort = prefs.quicServerPort,
                congestionControl = prefs.quicCongestionControl,
                zeroCopyEnabled = prefs.quicZeroCopyEnabled,
                cpuAffinity = prefs.quicCpuAffinity
            )
        )
    )
    val settingsState: StateFlow<SettingsState> = _settingsState.asStateFlow()
    
    init {
        viewModelScope.launch(Dispatchers.IO) {
            updateSettingsState()
            loadKernelVersion()
        }
    }
    
    fun updateSettingsState() {
        _settingsState.value = _settingsState.value.copy(
            socksPort = InputFieldState(prefs.socksPort.toString()),
            dnsIpv4 = InputFieldState(prefs.dnsIpv4),
            dnsIpv6 = InputFieldState(prefs.dnsIpv6),
            switches = SwitchStates(
                ipv6Enabled = prefs.ipv6,
                useTemplateEnabled = prefs.useTemplate,
                httpProxyEnabled = prefs.httpProxyEnabled,
                bypassLanEnabled = prefs.bypassLan,
                disableVpn = prefs.disableVpn,
                themeMode = prefs.theme,
                enablePerformanceMode = prefs.enablePerformanceMode
            ),
            info = _settingsState.value.info.copy(
                appVersion = BuildConfig.VERSION_NAME,
                geoipSummary = fileManager.getRuleFileSummary("geoip.dat"),
                geositeSummary = fileManager.getRuleFileSummary("geosite.dat"),
                geoipUrl = prefs.geoipUrl,
                geositeUrl = prefs.geositeUrl
            ),
            files = FileStates(
                isGeoipCustom = prefs.customGeoipImported,
                isGeositeCustom = prefs.customGeositeImported
            ),
            connectivityTestTarget = InputFieldState(prefs.connectivityTestTarget),
            connectivityTestTimeout = InputFieldState(prefs.connectivityTestTimeout.toString()),
            quicSettings = QuicSettings(
                enabled = prefs.enableQuicMode,
                serverHost = prefs.quicServerHost ?: "",
                serverPort = prefs.quicServerPort,
                congestionControl = prefs.quicCongestionControl,
                zeroCopyEnabled = prefs.quicZeroCopyEnabled,
                cpuAffinity = prefs.quicCpuAffinity
            )
        )
    }
    
    private fun loadKernelVersion() {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val app = getApplication<Application>()
                val xrayCore = java.io.File(app.filesDir, "libxray_copy.so")
                if (!xrayCore.exists() || !xrayCore.canExecute()) {
                    val libraryDir = app.applicationInfo.nativeLibraryDir
                    if (libraryDir == null) {
                        throw IllegalStateException("Native library directory not found")
                    }
                    val libxray = java.io.File(libraryDir, "libxray.so")
                    if (!libxray.exists() || !libxray.canRead()) {
                        throw IllegalStateException("Xray binary not found or not readable: ${libxray.absolutePath}")
                    }
                    libxray.inputStream().use { input ->
                        xrayCore.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (!xrayCore.setExecutable(true)) {
                        throw IllegalStateException("Failed to set executable permission on libxray_copy.so")
                    }
                    if (!xrayCore.canExecute()) {
                        throw IllegalStateException("Failed to make libxray_copy.so executable")
                    }
                }
                
                val process = ProcessBuilder(xrayCore.absolutePath, "version")
                    .redirectErrorStream(true)
                    .start()
                
                val output = try {
                    java.io.BufferedReader(java.io.InputStreamReader(process.inputStream)).use { reader ->
                        reader.readText().trim()
                    }
                } catch (e: java.io.IOException) {
                    AppLogger.w("Failed to read process output", e)
                    process.destroyForcibly()
                    throw IllegalStateException("Failed to read xray version output: ${e.message}")
                }
                
                val exited = try {
                    process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                } catch (e: InterruptedException) {
                    AppLogger.w("Process wait interrupted", e)
                    process.destroyForcibly()
                    throw e
                }
                
                if (!exited) {
                    AppLogger.w("Process did not exit within timeout, destroying")
                    process.destroyForcibly()
                    throw java.util.concurrent.TimeoutException("Process execution timeout")
                }
                
                val exitCode = process.exitValue()
                if (exitCode != 0) {
                    throw IllegalStateException("Xray version command failed with exit code: $exitCode, output: $output")
                }
                
                if (output.isBlank()) {
                    throw IllegalStateException("No output from xray version command")
                }
                
                output.lines().firstOrNull()?.trim() ?: output.trim()
            }
            
            result.fold(
                onSuccess = { version ->
                    _settingsState.value = _settingsState.value.copy(
                        info = _settingsState.value.info.copy(
                            kernelVersion = version
                        )
                    )
                },
                onFailure = { throwable ->
                    AppLogger.w("Failed to load kernel version: ${throwable.message}", throwable)
                    _settingsState.value = _settingsState.value.copy(
                        info = _settingsState.value.info.copy(
                            kernelVersion = "N/A"
                        )
                    )
                }
            )
        }
    }
    
    fun updateSocksPort(portString: String): Boolean {
        return try {
            val port = portString.toInt()
            if (port in 1025..65535) {
                prefs.socksPort = port
                _settingsState.value = _settingsState.value.copy(
                    socksPort = InputFieldState(portString)
                )
                true
            } else {
                _settingsState.value = _settingsState.value.copy(
                    socksPort = InputFieldState(
                        value = portString,
                        error = getApplication<Application>().getString(R.string.invalid_port_range),
                        isValid = false
                    )
                )
                false
            }
        } catch (e: NumberFormatException) {
            _settingsState.value = _settingsState.value.copy(
                socksPort = InputFieldState(
                    value = portString,
                    error = getApplication<Application>().getString(R.string.invalid_port),
                    isValid = false
                )
            )
            false
        }
    }
    
    fun updateDnsIpv4(ipv4Addr: String): Boolean {
        val matcher = IPV4_PATTERN.matcher(ipv4Addr)
        return if (matcher.matches()) {
            prefs.dnsIpv4 = ipv4Addr
            _settingsState.value = _settingsState.value.copy(
                dnsIpv4 = InputFieldState(ipv4Addr)
            )
            true
        } else {
            _settingsState.value = _settingsState.value.copy(
                dnsIpv4 = InputFieldState(
                    value = ipv4Addr,
                    error = getApplication<Application>().getString(R.string.invalid_ipv4),
                    isValid = false
                )
            )
            false
        }
    }
    
    fun updateDnsIpv6(ipv6Addr: String): Boolean {
        val matcher = IPV6_PATTERN.matcher(ipv6Addr)
        return if (matcher.matches()) {
            prefs.dnsIpv6 = ipv6Addr
            _settingsState.value = _settingsState.value.copy(
                dnsIpv6 = InputFieldState(ipv6Addr)
            )
            true
        } else {
            _settingsState.value = _settingsState.value.copy(
                dnsIpv6 = InputFieldState(
                    value = ipv6Addr,
                    error = getApplication<Application>().getString(R.string.invalid_ipv6),
                    isValid = false
                )
            )
            false
        }
    }
    
    fun setIpv6Enabled(enabled: Boolean) {
        prefs.ipv6 = enabled
        _settingsState.value = _settingsState.value.copy(
            switches = _settingsState.value.switches.copy(ipv6Enabled = enabled)
        )
    }
    
    fun setUseTemplateEnabled(enabled: Boolean) {
        prefs.useTemplate = enabled
        _settingsState.value = _settingsState.value.copy(
            switches = _settingsState.value.switches.copy(useTemplateEnabled = enabled)
        )
    }
    
    fun setHttpProxyEnabled(enabled: Boolean) {
        prefs.httpProxyEnabled = enabled
        _settingsState.value = _settingsState.value.copy(
            switches = _settingsState.value.switches.copy(httpProxyEnabled = enabled)
        )
    }
    
    fun setBypassLanEnabled(enabled: Boolean) {
        prefs.bypassLan = enabled
        _settingsState.value = _settingsState.value.copy(
            switches = _settingsState.value.switches.copy(bypassLanEnabled = enabled)
        )
    }
    
    fun setDisableVpnEnabled(enabled: Boolean) {
        prefs.disableVpn = enabled
        _settingsState.value = _settingsState.value.copy(
            switches = _settingsState.value.switches.copy(disableVpn = enabled)
        )
    }
    
    fun setEnablePerformanceMode(enabled: Boolean) {
        prefs.enablePerformanceMode = enabled
        _settingsState.value = _settingsState.value.copy(
            switches = _settingsState.value.switches.copy(enablePerformanceMode = enabled)
        )
        AppLogger.d("Performance mode ${if (enabled) "enabled" else "disabled"}")
    }

    // QUIC Mode Settings
    fun setQuicModeEnabled(enabled: Boolean) {
        prefs.enableQuicMode = enabled
        _settingsState.value = _settingsState.value.copy(
            quicSettings = _settingsState.value.quicSettings.copy(enabled = enabled)
        )
        AppLogger.d("QUIC mode ${if (enabled) "enabled" else "disabled"}")
    }

    fun setQuicServerHost(host: String) {
        prefs.quicServerHost = host
        _settingsState.value = _settingsState.value.copy(
            quicSettings = _settingsState.value.quicSettings.copy(serverHost = host)
        )
        AppLogger.d("QUIC server host set to: $host")
    }

    fun setQuicServerPort(port: Int) {
        prefs.quicServerPort = port
        _settingsState.value = _settingsState.value.copy(
            quicSettings = _settingsState.value.quicSettings.copy(serverPort = port)
        )
        AppLogger.d("QUIC server port set to: $port")
    }

    fun setQuicCongestionControl(algorithm: String) {
        prefs.quicCongestionControl = algorithm
        _settingsState.value = _settingsState.value.copy(
            quicSettings = _settingsState.value.quicSettings.copy(congestionControl = algorithm)
        )
        AppLogger.d("QUIC congestion control set to: $algorithm")
    }

    fun setQuicZeroCopyEnabled(enabled: Boolean) {
        prefs.quicZeroCopyEnabled = enabled
        _settingsState.value = _settingsState.value.copy(
            quicSettings = _settingsState.value.quicSettings.copy(zeroCopyEnabled = enabled)
        )
        AppLogger.d("QUIC zero-copy ${if (enabled) "enabled" else "disabled"}")
    }

    fun setQuicCpuAffinity(affinity: String) {
        prefs.quicCpuAffinity = affinity
        _settingsState.value = _settingsState.value.copy(
            quicSettings = _settingsState.value.quicSettings.copy(cpuAffinity = affinity)
        )
        AppLogger.d("QUIC CPU affinity set to: $affinity")
    }

    fun setTheme(mode: ThemeMode) {
        prefs.theme = mode
        _settingsState.value = _settingsState.value.copy(
            switches = _settingsState.value.switches.copy(themeMode = mode)
        )
    }
    
    fun updateConnectivityTestTarget(target: String) {
        val isValid = try {
            val url = URL(target)
            url.protocol == "http" || url.protocol == "https"
        } catch (e: Exception) {
            false
        }
        if (isValid) {
            prefs.connectivityTestTarget = target
            _settingsState.value = _settingsState.value.copy(
                connectivityTestTarget = InputFieldState(target)
            )
        } else {
            _settingsState.value = _settingsState.value.copy(
                connectivityTestTarget = InputFieldState(
                    value = target,
                    error = getApplication<Application>().getString(R.string.connectivity_test_invalid_url),
                    isValid = false
                )
            )
        }
    }
    
    fun updateConnectivityTestTimeout(timeout: String) {
        val timeoutInt = timeout.toIntOrNull()
        if (timeoutInt != null && timeoutInt > 0) {
            prefs.connectivityTestTimeout = timeoutInt
            _settingsState.value = _settingsState.value.copy(
                connectivityTestTimeout = InputFieldState(timeout)
            )
        } else {
            _settingsState.value = _settingsState.value.copy(
                connectivityTestTimeout = InputFieldState(
                    value = timeout,
                    error = getApplication<Application>().getString(R.string.invalid_timeout),
                    isValid = false
                )
            )
        }
    }
    
    fun testConnectivity(
        isServiceEnabled: Boolean,
        socksAddress: String,
        socksPort: Int
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val urlResult = runCatching {
                URL(prefs.connectivityTestTarget)
            }
            
            val url = urlResult.getOrElse { throwable ->
                uiEventSender(MainViewUiEvent.ShowSnackbar(
                    getApplication<Application>().getString(R.string.connectivity_test_invalid_url)
                ))
                return@launch
            }
            
            val host = url.host
            val port = if (url.port > 0) url.port else url.defaultPort
            val path = if (url.path.isNullOrEmpty()) "/" else url.path
            val isHttps = url.protocol == "https"
            val proxy = java.net.Proxy(
                java.net.Proxy.Type.SOCKS,
                java.net.InetSocketAddress(socksAddress, socksPort)
            )
            val timeout = prefs.connectivityTestTimeout
            val start = System.currentTimeMillis()
            
            val testResult = runCatching {
                java.net.Socket(proxy).use { socket ->
                    socket.soTimeout = timeout
                    socket.connect(java.net.InetSocketAddress(host, port), timeout)
                    
                    if (isHttps) {
                        val sslSocket = (javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory)
                            .createSocket(socket, host, port, true) as javax.net.ssl.SSLSocket
                        try {
                            sslSocket.soTimeout = timeout
                            sslSocket.startHandshake()
                            sslSocket.outputStream.bufferedWriter().use { writer ->
                                sslSocket.inputStream.bufferedReader().use { reader ->
                                    val sanitizedPath = path.replace("\r", "").replace("\n", "")
                                    val sanitizedHost = host.replace("\r", "").replace("\n", "")
                                    writer.write("GET $sanitizedPath HTTP/1.1\r\nHost: $sanitizedHost\r\nConnection: close\r\n\r\n")
                                    writer.flush()
                                    val firstLine = reader.readLine()
                                    val latency = System.currentTimeMillis() - start
                                    if (firstLine != null && firstLine.matches(Regex("^HTTP/\\d\\.\\d\\s+\\d{3}.*"))) {
                                        latency.toInt()
                                    } else {
                                        null
                                    }
                                }
                            }
                        } finally {
                            try {
                                sslSocket.close()
                            } catch (e: Exception) {
                                AppLogger.w("Error closing SSL socket", e)
                            }
                        }
                    } else {
                        socket.getOutputStream().bufferedWriter().use { writer ->
                            socket.getInputStream().bufferedReader().use { reader ->
                                writer.write("GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n")
                                writer.flush()
                                val firstLine = reader.readLine()
                                val latency = System.currentTimeMillis() - start
                                if (firstLine != null && firstLine.startsWith("HTTP/")) {
                                    latency.toInt()
                                } else {
                                    null
                                }
                            }
                        }
                    }
                }
            }
            
            testResult.fold(
                onSuccess = { latency ->
                    if (latency != null) {
                        uiEventSender(MainViewUiEvent.ShowSnackbar(
                            getApplication<Application>().getString(
                                R.string.connectivity_test_latency,
                                latency
                            )
                        ))
                    } else {
                        uiEventSender(MainViewUiEvent.ShowSnackbar(
                            getApplication<Application>().getString(R.string.connectivity_test_failed)
                        ))
                    }
                },
                onFailure = { throwable ->
                    AppLogger.e("Connectivity test failed", throwable)
                    uiEventSender(MainViewUiEvent.ShowSnackbar(
                        getApplication<Application>().getString(R.string.connectivity_test_failed)
                    ))
                }
            )
        }
    }
    
    companion object {
        private const val IPV4_REGEX =
            "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
        private val IPV4_PATTERN: Pattern = Pattern.compile(IPV4_REGEX)
        private const val IPV6_REGEX =
            "^(([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,7}:|([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,5}(:[0-9a-fA-F]{1,4}){1,2}|([0-9a-fA-F]{1,4}:){1,4}(:[0-9a-fA-F]{1,4}){1,3}|([0-9a-fA-F]{1,4}:){1,3}(:[0-9a-fA-F]{1,4}){1,4}|([0-9a-fA-F]{1,4}:){1,2}(:[0-9a-fA-F]{1,4}){1,5}|[0-9a-fA-F]{1,4}:((:[0-9a-fA-F]{1,4}){1,6})|:((:[0-9a-fA-F]{1,4}){1,7}|:)|fe80::(fe80(:[0-9a-fA-F]{0,4})?){0,4}%[0-9a-zA-Z]+|::(ffff(:0{1,4})?:)?((25[0-5]|(2[0-4]|1?\\d)?\\d)\\.){3}(25[0-5]|(2[0-4]|1?\\d)?\\d)|([0-9a-fA-F]{1,4}:){1,4}:((25[0-5]|(2[0-4]|1?\\d)?\\d)\\.){3}(25[0-5]|(2[0-4]|1?\\d)?\\d))$"
        private val IPV6_PATTERN: Pattern = Pattern.compile(IPV6_REGEX)
    }
}

