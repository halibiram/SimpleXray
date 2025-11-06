package com.simplexray.an.chain.reality

import android.content.Context
import com.google.gson.Gson
import com.simplexray.an.common.AppLogger
import com.simplexray.an.xray.XrayCoreLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Reality SOCKS5 server that mimics TLS fingerprint via Xray REALITY
 * 
 * Provides a local SOCKS5 server (127.0.0.1:PORT) that forwards
 * outbound traffic via Xray REALITY to remote server.
 * 
 * Implementation: Uses Xray-core's built-in SOCKS inbound and REALITY outbound
 */
object RealitySocks {
    private val _status = MutableStateFlow<RealityStatus>(
        RealityStatus(
            isRunning = false,
            localPort = null,
            connectedClients = 0,
            bytesUp = 0,
            bytesDown = 0,
            handshakeTimeMs = null,
            lastError = null
        )
    )
    val status: Flow<RealityStatus> = _status.asStateFlow()
    
    private var isInitialized = false
    private var currentConfig: RealityConfig? = null
    private var configFile: File? = null
    private var context: Context? = null
    private val monitoringScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connectedClients = AtomicInteger(0)
    private val bytesUp = AtomicLong(0)
    private val bytesDown = AtomicLong(0)
    
    /**
     * Initialize the Reality SOCKS layer
     */
    fun init(context: Context) {
        if (isInitialized) return
        AppLogger.d("RealitySocks: Initializing")
        this.context = context.applicationContext
        isInitialized = true
    }
    
    /**
     * Start Reality SOCKS server with given configuration
     * 
     * Creates an Xray config with:
     * - SOCKS5 inbound on local port
     * - REALITY outbound to remote server
     */
    fun start(config: RealityConfig): Result<Unit> {
        return try {
            val ctx = context ?: return Result.failure(IllegalStateException("Not initialized"))
            
            AppLogger.i("RealitySocks: Starting on port ${config.localPort}")
            
            // Build Xray config for Reality SOCKS
            val xrayConfig = RealityXrayConfig.buildConfig(config)
            
            // Write config to file
            val configFile = File(ctx.filesDir, "reality-socks-${config.localPort}.json")
            configFile.writeText(Gson().toJson(xrayConfig))
            this.configFile = configFile
            this.currentConfig = config
            
            AppLogger.d("RealitySocks: Config written to ${configFile.absolutePath}")
            
            // Start Xray-core with this config
            val started = XrayCoreLauncher.start(
                context = ctx,
                configFile = configFile,
                maxRetries = 3,
                retryDelayMs = 2000,
                onLogLine = { line ->
                    // Parse Xray logs for metrics
                    parseXrayLog(line)
                }
            )
            
            if (!started) {
                return Result.failure(Exception("Failed to start Xray-core for Reality SOCKS"))
            }
            
            // Wait a bit for Xray to start
            Thread.sleep(500)
            
            // Verify Xray is running
            if (!XrayCoreLauncher.isRunning()) {
                return Result.failure(Exception("Xray-core failed to start"))
            }
            
            _status.value = _status.value.copy(
                isRunning = true,
                localPort = config.localPort,
                lastError = null
            )
            
            // Start monitoring
            startMonitoring()
            
            AppLogger.i("RealitySocks: Started successfully on port ${config.localPort}")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e("RealitySocks: Failed to start", e)
            _status.value = _status.value.copy(
                isRunning = false,
                lastError = e.message
            )
            Result.failure(e)
        }
    }
    
    /**
     * Stop Reality SOCKS server
     */
    fun stop(): Result<Unit> {
        return try {
            AppLogger.i("RealitySocks: Stopping")
            
            // Stop Xray-core if we started it
            if (XrayCoreLauncher.isRunning()) {
                // Only stop if we're the one managing it
                // In a full chain, ChainSupervisor manages Xray
                // For now, we'll let ChainSupervisor handle it
                AppLogger.d("RealitySocks: Xray-core is running, but letting supervisor manage it")
            }
            
            // Cleanup
            configFile = null
            currentConfig = null
            connectedClients.set(0)
            bytesUp.set(0)
            bytesDown.set(0)
            
            _status.value = _status.value.copy(
                isRunning = false,
                localPort = null,
                connectedClients = 0,
                bytesUp = 0,
                bytesDown = 0
            )
            
            AppLogger.i("RealitySocks: Stopped")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e("RealitySocks: Failed to stop", e)
            Result.failure(e)
        }
    }
    
    /**
     * Get current status
     */
    fun getStatus(): RealityStatus = _status.value
    
    /**
     * Get local SOCKS5 address
     */
    fun getLocalAddress(): InetSocketAddress? {
        val port = _status.value.localPort ?: return null
        return InetSocketAddress("127.0.0.1", port)
    }
    
    /**
     * Parse Xray log lines to extract metrics.
     * 
     * Extracts the following metrics from Xray log output:
     * - **Connected clients count**: Detects connection/disconnection events
     * - **Traffic stats**: Parses uplink/downlink byte counts
     * - **Handshake times**: Extracts TLS handshake duration
     * 
     * Supported log patterns:
     * - Connection: "accepting connection", "new connection"
     * - Disconnection: "connection closed", "connection terminated"
     * - Traffic: "uplink: X", "downlink: Y"
     * - Handshake: "handshake completed in Xms"
     * 
     * @param line Log line from Xray process output
     */
    private fun parseXrayLog(line: String) {
        try {
            // Parse Xray log lines for metrics
            // Xray logs may contain:
            // - Connection info: "accepting connection from ..."
            // - Traffic stats: "uplink: 1234, downlink: 5678"
            // - Handshake info: "TLS handshake completed in 50ms"
            
            // Look for connection patterns
            if (line.contains("accepting connection", ignoreCase = true) ||
                line.contains("new connection", ignoreCase = true)) {
                connectedClients.incrementAndGet()
            }
            
            // Look for disconnection patterns
            if (line.contains("connection closed", ignoreCase = true) ||
                line.contains("connection terminated", ignoreCase = true)) {
                val current = connectedClients.get()
                if (current > 0) {
                    connectedClients.decrementAndGet()
                }
            }
            
            // Parse traffic stats from log
            // Pattern: "uplink: 1234, downlink: 5678" or "up: 1234, down: 5678"
            val uplinkPattern = Regex("(?:uplink|up)[\\s:]+(\\d+)")
            uplinkPattern.find(line)?.groupValues?.get(1)?.toLongOrNull()?.let {
                bytesUp.addAndGet(it)
            }
            
            val downlinkPattern = Regex("(?:downlink|down)[\\s:]+(\\d+)")
            downlinkPattern.find(line)?.groupValues?.get(1)?.toLongOrNull()?.let {
                bytesDown.addAndGet(it)
            }
            
            // Parse handshake time
            // Pattern: "handshake completed in 50ms" or "TLS handshake: 50ms"
            val handshakePattern = Regex("handshake.*?(\\d+)\\s*ms", RegexOption.IGNORE_CASE)
            handshakePattern.find(line)?.groupValues?.get(1)?.toIntOrNull()?.let {
                _status.value = _status.value.copy(handshakeTimeMs = it)
            }
        } catch (e: Exception) {
            // Ignore parse errors for non-metric lines
            AppLogger.d("RealitySocks: Log line (not metric): $line")
        }
    }
    
    /**
     * Start monitoring loop
     */
    private fun startMonitoring() {
        monitoringScope.launch {
            while (_status.value.isRunning) {
                try {
                    // Update status with current metrics
                    _status.value = _status.value.copy(
                        connectedClients = connectedClients.get(),
                        bytesUp = bytesUp.get(),
                        bytesDown = bytesDown.get()
                    )
                    
                    delay(1000) // Update every second
                } catch (e: Exception) {
                    AppLogger.e("RealitySocks: Monitoring error", e)
                    delay(5000)
                }
            }
        }
    }
}

