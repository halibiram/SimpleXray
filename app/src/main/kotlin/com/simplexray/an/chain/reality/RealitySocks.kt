package com.simplexray.an.chain.reality

import android.content.Context
import com.google.gson.Gson
import com.simplexray.an.common.AppLogger
import com.simplexray.an.xray.XrayCoreLauncher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
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
    // StateFlow updates are thread-safe by design
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
    // Properly cancelled in stop() and shutdown()
    private val monitoringScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitoringJob: kotlinx.coroutines.Job? = null
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
            // Pass context to read SNI from clipboard if available
            val xrayConfig = RealityXrayConfig.buildConfig(config, ctx)
            
            // Write config to file (path is within filesDir, safe)
            val configFile = File(ctx.filesDir, "reality-socks-${config.localPort}.json")
            // Validate config file path
            if (!configFile.canonicalPath.startsWith(ctx.filesDir.canonicalPath)) {
                return Result.failure(Exception("Config path outside allowed directory"))
            }
            configFile.writeText(Gson().toJson(xrayConfig))
            this.configFile = configFile
            this.currentConfig = config
            
            AppLogger.d("RealitySocks: Config written to ${configFile.absolutePath}")
            
            // Start Xray-core with this config (with timeout protection)
            val started = try {
                kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withTimeout(30000) { // 30 second timeout
                        XrayCoreLauncher.start(
                            context = ctx,
                            configFile = configFile,
                            maxRetries = 3,
                            retryDelayMs = 2000,
                            onLogLine = { line ->
                                // Parse Xray logs for metrics (with rate limiting)
                                parseXrayLog(line)
                            }
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                AppLogger.e("RealitySocks: Xray startup timed out after 30 seconds", e)
                val errorDetails = getXrayErrorDetails(ctx)
                val errorMessage = if (errorDetails != null) {
                    "Xray-core startup timed out: $errorDetails"
                } else {
                    "Xray-core startup timed out after 30 seconds. Process may be stuck."
                }
                return Result.failure(Exception(errorMessage))
            }
            
            if (!started) {
                // Try to get more detailed error information from Xray log
                val errorDetails = getXrayErrorDetails(ctx)
                val errorMessage = if (errorDetails != null) {
                    "Failed to start Xray-core for Reality SOCKS: $errorDetails"
                } else {
                    "Failed to start Xray-core for Reality SOCKS. Check logs for details."
                }
                return Result.failure(Exception(errorMessage))
            }
            
            // Wait a bit for Xray to start (non-blocking)
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.delay(500)
            }
            
            // Verify Xray is running
            if (!XrayCoreLauncher.isRunning()) {
                // Try to get error details from log
                val errorDetails = getXrayErrorDetails(ctx)
                val errorMessage = if (errorDetails != null) {
                    "Xray-core failed to start: $errorDetails"
                } else {
                    "Xray-core process died after startup. Check logs for details."
                }
                return Result.failure(Exception(errorMessage))
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
    @Synchronized
    fun stop(): Result<Unit> {
        return try {
            AppLogger.i("RealitySocks: Stopping")
            
            // Stop monitoring first
            monitoringJob?.cancel()
            monitoringJob = null
            
            // Stop Xray-core if we started it (only if we're managing it)
            // Note: In chain mode, ChainSupervisor manages Xray, so we don't stop it here
            // This is intentional to avoid stopping Xray when it's used by other layers
            
            // Cleanup resources
            try {
                configFile?.delete()
            } catch (e: Exception) {
                AppLogger.w("RealitySocks: Failed to delete config file: ${e.message}", e)
            }
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
                _status.value = _status.value.copy(handshakeTimeMs = it.toLong())
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
        // Cancel existing monitoring if any
        monitoringJob?.cancel()
        
        monitoringJob = monitoringScope.launch {
            var adaptiveDelay = 1000L // Start with 1 second
            var consecutiveErrors = 0
            
            while (this.isActive && _status.value.isRunning) {
                try {
                    // Update status with current metrics (with overflow protection)
                    val currentBytesUp = bytesUp.get().coerceAtLeast(0L)
                    val currentBytesDown = bytesDown.get().coerceAtLeast(0L)
                    
                    _status.value = _status.value.copy(
                        connectedClients = connectedClients.get().coerceAtLeast(0),
                        bytesUp = currentBytesUp,
                        bytesDown = currentBytesDown
                    )
                    
                    // Adaptive delay: decrease if stable, increase on errors
                    consecutiveErrors = 0
                    adaptiveDelay = (adaptiveDelay * 0.95).toLong().coerceAtLeast(1000) // Min 1s
                    delay(adaptiveDelay)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e // Re-throw cancellation
                } catch (e: Exception) {
                    consecutiveErrors++
                    AppLogger.e("RealitySocks: Monitoring error (consecutive: $consecutiveErrors): ${e.javaClass.simpleName}: ${e.message}", e)
                    // Increase delay on errors
                    adaptiveDelay = (adaptiveDelay * 1.5).toLong().coerceAtMost(10000) // Max 10s
                    delay(adaptiveDelay)
                }
            }
        }
    }
    
    /**
     * Shutdown and cleanup all resources
     */
    fun shutdown() {
        stop()
        monitoringJob?.cancel()
        monitoringScope.coroutineContext.cancel()
        AppLogger.d("RealitySocks: Shutdown complete")
    }
    
    /**
     * Extract error details from Xray log file
     * Returns a user-friendly error message or null if no details found
     */
    private fun getXrayErrorDetails(context: Context): String? {
        return try {
            val logFile = File(context.filesDir, "xray.log")
            if (!logFile.exists() || logFile.length() == 0L) {
                return null
            }
            
            // Read last 1000 characters of log file for recent errors
            val logContent = logFile.inputStream().bufferedReader().use { reader ->
                val fileSize = logFile.length()
                val readSize = minOf(1000L, fileSize)
                if (fileSize > readSize) {
                    reader.skip(fileSize - readSize)
                }
                reader.readText()
            }
            
            // Sanitize sensitive data
            val sanitized = AppLogger.sanitize(logContent)
            
            // Extract error patterns from Xray logs
            val errorPatterns = listOf(
                Regex("(?i)error[\\s:]+(.{0,200})", RegexOption.MULTILINE),
                Regex("(?i)failed[\\s:]+(.{0,200})", RegexOption.MULTILINE),
                Regex("(?i)invalid[\\s:]+(.{0,200})", RegexOption.MULTILINE),
                Regex("(?i)cannot[\\s:]+(.{0,200})", RegexOption.MULTILINE),
                Regex("(?i)denied[\\s:]+(.{0,200})", RegexOption.MULTILINE),
                Regex("(?i)permission[\\s:]+(.{0,200})", RegexOption.MULTILINE)
            )
            
            // Find the most relevant error message
            for (pattern in errorPatterns) {
                val match = pattern.find(sanitized)
                if (match != null) {
                    val errorMsg = match.groupValues.getOrNull(1)?.trim()
                    if (!errorMsg.isNullOrBlank()) {
                        // Truncate if too long
                        return if (errorMsg.length > 150) {
                            errorMsg.take(150) + "..."
                        } else {
                            errorMsg
                        }
                    }
                }
            }
            
            // If no specific pattern found, return last few lines
            val lines = sanitized.lines().filter { it.isNotBlank() }
            if (lines.isNotEmpty()) {
                val lastLine = lines.last()
                if (lastLine.length > 150) {
                    lastLine.take(150) + "..."
                } else {
                    lastLine
                }
            } else {
                null
            }
        } catch (e: Exception) {
            AppLogger.w("RealitySocks: Failed to read Xray error log: ${e.message}", e)
            null
        }
    }
}

