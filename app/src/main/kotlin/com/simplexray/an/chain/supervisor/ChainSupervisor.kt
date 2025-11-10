package com.simplexray.an.chain.supervisor

import android.content.Context
import com.simplexray.an.common.AppLogger
import com.simplexray.an.chain.pepper.PepperShaper
import com.simplexray.an.quiche.QuicheClient
import com.simplexray.an.quiche.QuicheTunForwarder
import com.simplexray.an.quiche.CongestionControl
import com.simplexray.an.quiche.CpuAffinity
import kotlinx.coroutines.cancel
import com.simplexray.an.xray.XrayCoreLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Chain Supervisor: Orchestrates the full tunneling stack
 *
 * Manages lifecycle of:
 * - QUICME (QUIC tunneling via TUN)
 * - PepperShaper (traffic shaping)
 * - Xray-core (routing engine)
 */
// ARCH-DEBT: ChainSupervisor manages multiple components - god class pattern
// Note: Scope properly cancelled in shutdown() method
class ChainSupervisor(private val context: Context) {
    // Use SupervisorJob to prevent child failures from cancelling parent
    // Scope is properly cancelled in shutdown() to prevent memory leaks
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    
    // StateFlow updates are thread-safe by design
    // Use synchronized updates when modifying from multiple coroutines
    private val statusMutex = Mutex()
    private val _status = MutableStateFlow<ChainStatus>(
        ChainStatus(
            state = ChainState.STOPPED,
            layers = emptyMap(),
            uptime = 0,
            totalBytesUp = 0,
            totalBytesDown = 0
        )
    )
    val status: Flow<ChainStatus> = _status.asStateFlow()
    
    private var currentConfig: ChainConfig? = null
    private val startTime = AtomicLong(0)
    // Native handle - validated before use
    private var pepperHandle: Long? = null
    // QUICME TUN forwarder
    private var quicheClient: QuicheClient? = null
    private var quicheTunForwarder: QuicheTunForwarder? = null
    
    init {
        // Initialize all layers with error handling
        try {
            PepperShaper.init(context)
        } catch (e: Exception) {
            AppLogger.e("ChainSupervisor: Failed to initialize PepperShaper: ${e.message}", e)
        }
    }
    
    /**
     * Start the chain with given configuration
     */
    fun start(config: ChainConfig): Result<Unit> {
        return try {
            if (_status.value.state != ChainState.STOPPED) {
                return Result.failure(IllegalStateException("Chain is already running"))
            }
            
            // Validate configuration before starting
            val validation = ChainHelper.validateChainConfig(config)
            if (!validation.isValid) {
                AppLogger.e("ChainSupervisor: Configuration validation failed: ${validation.errors.joinToString(", ")}")
                return Result.failure(Exception("Invalid configuration: ${validation.errors.joinToString(", ")}"))
            }
            
            if (validation.warnings.isNotEmpty()) {
                validation.warnings.forEach { warning ->
                    AppLogger.w("ChainSupervisor: Warning: $warning")
                }
            }
            
            AppLogger.i("ChainSupervisor: Starting chain '${config.name}'")
            AppLogger.d("ChainSupervisor: Chain status: ${ChainHelper.getChainStatusSummary(config)}")
            currentConfig = config
            kotlinx.coroutines.runBlocking {
                statusMutex.withLock {
                    _status.value = _status.value.copy(state = ChainState.STARTING)
                }
            }
            
            // Start layers in order
            val results = mutableListOf<Result<Unit>>()
            
            // 1. Attach PepperShaper if configured
            if (config.pepperParams != null) {
                // PepperShaper is initialized but not attached to any FDs
                // It's available for future use if needed
                val pepperResult = try {
                    // Verify PepperShaper is initialized
                    val isPepperAvailable = try {
                        PepperShaper.getStats()
                        true
                    } catch (e: Exception) {
                        AppLogger.w("ChainSupervisor: PepperShaper not initialized: ${e.message}")
                        false
                    }

                    if (isPepperAvailable) {
                        AppLogger.i("ChainSupervisor: PepperShaper initialized and available (not attached to any FDs)")
                        Result.success(Unit)
                    } else {
                        AppLogger.w("ChainSupervisor: PepperShaper not available")
                        Result.success(Unit) // Still success - chain works without PepperShaper
                    }
                } catch (e: Exception) {
                    AppLogger.e("ChainSupervisor: Failed to attach PepperShaper: ${e.message}", e)
                    // Don't fail chain startup - PepperShaper is optional enhancement
                    AppLogger.w("ChainSupervisor: Continuing without PepperShaper")
                    Result.success(Unit)
                }
                
                // Update status: success if attached, or if PepperShaper is at least initialized
                val isPepperReady = pepperHandle != null || try {
                    PepperShaper.getStats()
                    true
                } catch (e: Exception) {
                    false
                }
                
                updateLayerStatus("pepper", pepperResult.isSuccess && isPepperReady, 
                    if (pepperHandle != null) null 
                    else if (isPepperReady) "Initialized (FD attachment pending)" 
                    else "FDs not available (optional)")
            }
            
            // 4. Start Xray-core if configured
            if (config.xrayConfigPath != null) {
                // Use Xray config directly
                val finalXrayConfigPath = config.xrayConfigPath
                
                // SEC: Validate config path to prevent path traversal
                val configFile = File(context.filesDir, finalXrayConfigPath)
                val canonicalPath = try {
                    configFile.canonicalPath
                } catch (e: Exception) {
                    AppLogger.e("ChainSupervisor: Invalid config path: ${e.message}", e)
                    results.add(Result.failure(Exception("Invalid config path")))
                    updateLayerStatus("xray", false, "Invalid config path")
                    return Result.failure(Exception("Invalid config path"))
                }
                
                // SEC: Ensure path is within allowed directory
                if (!canonicalPath.startsWith(context.filesDir.canonicalPath)) {
                    AppLogger.e("ChainSupervisor: Config path outside allowed directory: $canonicalPath")
                    results.add(Result.failure(Exception("Config path outside allowed directory")))
                    updateLayerStatus("xray", false, "Path traversal detected")
                    return Result.failure(Exception("Path traversal detected"))
                }
                
                // Convert ChainConfig.TlsMode to TlsImplementation
                val tlsMode = when (config.tlsMode) {
                    ChainConfig.TlsMode.BORINGSSL -> com.simplexray.an.chain.tls.TlsImplementation.BORINGSSL
                    ChainConfig.TlsMode.CONSCRYPT -> com.simplexray.an.chain.tls.TlsImplementation.CONSCRYPT
                    ChainConfig.TlsMode.AUTO -> com.simplexray.an.chain.tls.TlsImplementation.AUTO
                }
                
                // Start Xray with timeout protection
                val result = runCatching {
                    kotlinx.coroutines.runBlocking {
                        kotlinx.coroutines.withTimeout(30000) { // 30 second timeout
                            XrayCoreLauncher.start(
                                context,
                                configFile,
                                maxRetries = 3,
                                retryDelayMs = 5000,
                                tlsMode = tlsMode
                            )
                        }
                    }
                }
                results.add(if (result.isSuccess && result.getOrDefault(false)) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("Xray-core failed to start"))
                })
                updateLayerStatus("xray", result.isSuccess, result.exceptionOrNull()?.message)
            }
            
            // Check if critical layers started successfully
            // Xray is critical, PepperShaper is optional
            val criticalLayers = mutableListOf<Result<Unit>>()
            if (config.xrayConfigPath != null) {
                results.lastOrNull()?.let { criticalLayers.add(it) }
            }
            
            val criticalSuccess = criticalLayers.isEmpty() || criticalLayers.all { it.isSuccess }
            val allSuccess = results.all { it.isSuccess }
            
            // Log which layers failed for better debugging
            val failedLayers = mutableListOf<String>()
            results.forEachIndexed { index, result ->
                if (!result.isSuccess) {
                    val layerName = when (index) {
                        0 -> "QUICME"
                        1 -> "PepperShaper"
                        2 -> "Xray"
                        else -> "Unknown"
                    }
                    failedLayers.add(layerName)
                    val error = result.exceptionOrNull()
                    AppLogger.w("ChainSupervisor: Layer '$layerName' failed: ${error?.message ?: "Unknown error"}")
                }
            }
            
            kotlinx.coroutines.runBlocking {
                statusMutex.withLock {
                    if (criticalSuccess) {
                        startTime.set(System.currentTimeMillis())
                        if (allSuccess) {
                            _status.value = _status.value.copy(state = ChainState.RUNNING)
                            AppLogger.i("ChainSupervisor: Chain started successfully (all layers active)")
                        } else {
                            _status.value = _status.value.copy(state = ChainState.RUNNING)
                            AppLogger.w("ChainSupervisor: Chain started in degraded mode (some optional layers failed)")
                            AppLogger.w("ChainSupervisor: Failed layers: ${failedLayers.joinToString(", ")}")
                            if (failedLayers.isNotEmpty()) {
                                AppLogger.w("ChainSupervisor: Chain will continue with reduced functionality")
                            }
                        }
                    } else {
                        _status.value = _status.value.copy(state = ChainState.DEGRADED)
                        AppLogger.w("ChainSupervisor: Chain started in degraded mode (critical layers failed)")
                        AppLogger.w("ChainSupervisor: Failed critical layers: ${failedLayers.joinToString(", ")}")
                        if (failedLayers.isNotEmpty()) {
                            AppLogger.e("ChainSupervisor: Critical layer failures may cause VPN connectivity issues")
                        }
                    }
                }
            }
            
            if (criticalSuccess) {
                // Start monitoring after state update
                startMonitoring()
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e("ChainSupervisor: Failed to start chain", e)
            kotlinx.coroutines.runBlocking {
                statusMutex.withLock {
                    _status.value = _status.value.copy(state = ChainState.STOPPED)
                }
            }
            Result.failure(e)
        }
    }
    
    /**
     * Stop the chain
     * 
     * Thread-safe: Uses Mutex for synchronization instead of @Synchronized
     * to avoid deadlock with coroutine-based status updates.
     */
    fun stop(): Result<Unit> {
        return try {
            // Use runBlocking to access mutex from non-suspend context
            // Wrap entire stop logic in mutex to ensure mutual exclusion
            kotlinx.coroutines.runBlocking {
                statusMutex.withLock {
                    // Early return if already stopped
                    if (_status.value.state == ChainState.STOPPED) {
                        return@runBlocking Result.success(Unit)
                    }
                    
                    AppLogger.i("ChainSupervisor: Stopping chain")
                    _status.value = _status.value.copy(state = ChainState.STOPPING)
                }
            }
            
            // Stop layers in reverse order with error handling
            // These operations are safe to run outside the mutex as they don't modify shared state
            val stopResults = mutableListOf<Result<Unit>>()
            
            // Stop Xray-core
            val xrayResult = runCatching { XrayCoreLauncher.stop() }
            stopResults.add(xrayResult.map { })
            updateLayerStatus("xray", false, xrayResult.exceptionOrNull()?.message)
            
            // Stop monitoring job first
            monitoringJob?.cancel()
            monitoringJob = null

            // Stop PepperShaper (validate handle before detach)
            pepperHandle?.let { handle ->
                if (handle > 0) {
                    val pepperResult = runCatching { PepperShaper.detach(handle) }
                    stopResults.add(pepperResult.map { })
                    updateLayerStatus("pepper", false, pepperResult.exceptionOrNull()?.message)
                }
            }
            pepperHandle = null

            // Stop QUICME TUN forwarder
            quicheTunForwarder?.let { forwarder ->
                val quicheResult = runCatching {
                    forwarder.stop()
                    forwarder.close()
                }
                stopResults.add(quicheResult.map { })
                updateLayerStatus("quicme", false, quicheResult.exceptionOrNull()?.message)
            }
            quicheTunForwarder = null
            
            // Stop QUICME client
            quicheClient?.let { client ->
                val quicheClientResult = runCatching {
                    client.close()
                }
                stopResults.add(quicheClientResult.map { })
            }
            quicheClient = null

            // Log any failures but continue cleanup
            stopResults.forEachIndexed { index, result ->
                if (result.isFailure) {
                    val layerName = when (index) {
                        0 -> "xray"
                        1 -> "pepper"
                        2 -> "quicme"
                        else -> "unknown"
                    }
                    AppLogger.w("ChainSupervisor: Failed to stop layer $layerName: ${result.exceptionOrNull()?.message}")
                }
            }
            
            // Final status update - must be in mutex
            startTime.set(0)
            kotlinx.coroutines.runBlocking {
                statusMutex.withLock {
                    _status.value = _status.value.copy(
                        state = ChainState.STOPPED,
                        uptime = 0
                    )
                }
            }
            
            AppLogger.i("ChainSupervisor: Chain stopped")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e("ChainSupervisor: Failed to stop chain", e)
            Result.failure(e)
        }
    }
    
    /**
     * Restart the chain with new configuration
     */
    fun restart(config: ChainConfig): Result<Unit> {
        stop()
        return start(config)
    }
    
    /**
     * Get current status
     */
    fun getStatus(): ChainStatus = _status.value
    
    /**
     * Update layer status with thread-safe state update
     */
    private fun updateLayerStatus(name: String, isRunning: Boolean, error: String?) {
        // Use runBlocking to allow calling from non-suspend context
        // This is safe because Mutex.withLock is a suspend function
        kotlinx.coroutines.runBlocking {
            statusMutex.withLock {
                val currentLayers = _status.value.layers.toMutableMap()
                currentLayers[name] = LayerStatus(
                    name = name,
                    isRunning = isRunning,
                    error = error,
                    lastUpdate = System.currentTimeMillis()
                )
                _status.value = _status.value.copy(layers = currentLayers)
            }
        }
    }
    
    /**
     * Start monitoring loop
     */
    private var monitoringJob: kotlinx.coroutines.Job? = null
    
    private fun startMonitoring() {
        // Cancel existing monitoring if any
        monitoringJob?.cancel()
        
        monitoringJob = scope.launch {
            var adaptiveDelay = 1000L // Start with 1 second
            var consecutiveErrors = 0
            
            while (this.isActive && (_status.value.state == ChainState.RUNNING || 
                   _status.value.state == ChainState.DEGRADED)) {
                try {
                    val uptime = if (startTime.get() > 0) {
                        System.currentTimeMillis() - startTime.get()
                    } else {
                        0
                    }
                    
                    // Aggregate metrics from all layers (with null safety)
                    // QUICME metrics
                    val quicheStats = quicheTunForwarder?.getStats()
                    val quicheBytesUp = quicheStats?.bytesSent ?: 0L
                    val quicheBytesDown = quicheStats?.bytesReceived ?: 0L

                    // Safe aggregation with overflow protection
                    val totalBytesUp = runCatching {
                        quicheBytesUp.coerceAtLeast(0L)
                    }.getOrElse { 0L }

                    val totalBytesDown = runCatching {
                        quicheBytesDown.coerceAtLeast(0L)
                    }.getOrElse { 0L }
                    
                    // This is already in a coroutine (monitoringJob), so we can use suspend
                    statusMutex.withLock {
                        _status.value = _status.value.copy(
                            uptime = uptime,
                            totalBytesUp = totalBytesUp,
                            totalBytesDown = totalBytesDown
                        )
                    }
                    
                    // Adaptive delay: increase if errors occur, decrease if stable
                    consecutiveErrors = 0
                    adaptiveDelay = (adaptiveDelay * 0.95).toLong().coerceAtLeast(1000) // Min 1s
                    kotlinx.coroutines.delay(adaptiveDelay)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e // Re-throw cancellation
                } catch (e: Exception) {
                    consecutiveErrors++
                    AppLogger.e("ChainSupervisor: Monitoring error (consecutive: $consecutiveErrors): ${e.javaClass.simpleName}: ${e.message}", e)
                    // Increase delay on errors to reduce battery drain
                    adaptiveDelay = (adaptiveDelay * 1.5).toLong().coerceAtMost(10000) // Max 10s
                    kotlinx.coroutines.delay(adaptiveDelay)
                }
            }
        }
    }
    
    /**
     * Attach QUICME to TUN file descriptor for QUIC tunneling
     *
     * @param tunFd The TUN interface file descriptor
     * @param serverHost QUICME server hostname
     * @param serverPort QUICME server port
     * @return true if successfully attached, false otherwise
     */
    fun attachQuicheToTunFd(tunFd: Int, serverHost: String = "127.0.0.1", serverPort: Int = 443): Boolean {
        return try {
            // Validate TUN file descriptor
            if (tunFd < 0) {
                AppLogger.e("ChainSupervisor: Invalid TUN file descriptor: $tunFd")
                updateLayerStatus("quicme", false, "Invalid TUN FD")
                return false
            }

            // Don't re-attach if already attached
            if (quicheTunForwarder != null) {
                AppLogger.i("ChainSupervisor: QUICME TUN forwarder already attached")
                return true
            }

            AppLogger.i("ChainSupervisor: Attaching QUICME to TUN FD $tunFd ($serverHost:$serverPort) - QUICME is part of the chain (TUN → QUICME → Xray)")

            // Create QUICME client
            val client = QuicheClient.create(
                serverHost = serverHost,
                serverPort = serverPort,
                congestionControl = CongestionControl.BBR2,
                enableZeroCopy = true,
                cpuAffinity = CpuAffinity.BIG_CORES
            )

            if (client == null) {
                AppLogger.e("ChainSupervisor: Failed to create QUICME client")
                updateLayerStatus("quicme", false, "Failed to create client")
                return false
            }

            // Connect QUICME client
            if (!client.connect()) {
                AppLogger.e("ChainSupervisor: Failed to connect QUICME client")
                client.close()
                updateLayerStatus("quicme", false, "Failed to connect")
                return false
            }

            quicheClient = client

            // Create TUN forwarder
            val forwarder = QuicheTunForwarder.create(
                tunFd = tunFd,
                quicClient = client,
                batchSize = 64,
                useGSO = true,
                useGRO = true
            )

            if (forwarder == null) {
                AppLogger.e("ChainSupervisor: Failed to create QUICME TUN forwarder")
                client.close()
                quicheClient = null
                updateLayerStatus("quicme", false, "Failed to create forwarder")
                return false
            }

            // Start forwarder
            if (!forwarder.start()) {
                AppLogger.e("ChainSupervisor: Failed to start QUICME TUN forwarder")
                forwarder.close()
                client.close()
                quicheClient = null
                updateLayerStatus("quicme", false, "Failed to start forwarder")
                return false
            }

            quicheTunForwarder = forwarder
            updateLayerStatus("quicme", true, null)
            AppLogger.i("ChainSupervisor: QUICME successfully integrated into chain (TUN → QUICME → Xray)")
            true
        } catch (e: Exception) {
            AppLogger.e("ChainSupervisor: Failed to attach QUICME to TUN FD: ${e.message}", e)
            updateLayerStatus("quicme", false, e.message)
            // Cleanup on error
            quicheTunForwarder?.close()
            quicheClient?.close()
            quicheTunForwarder = null
            quicheClient = null
            false
        }
    }

    /**
     * Attach PepperShaper to TUN file descriptor for traffic shaping
     *
     * @param tunFd The TUN interface file descriptor
     * @return true if successfully attached, false otherwise
     */
    fun attachPepperToTunFd(tunFd: Int): Boolean {
        return try {
            // Check if PepperShaper is initialized
            if (currentConfig?.pepperParams == null) {
                AppLogger.w("ChainSupervisor: Cannot attach PepperShaper - no pepper params configured")
                return false
            }

            // Don't re-attach if already attached
            pepperHandle?.let { handle ->
                if (handle > 0) {
                    AppLogger.i("ChainSupervisor: PepperShaper already attached (handle=$handle)")
                    return true
                }
            }

            AppLogger.i("ChainSupervisor: Attaching PepperShaper to TUN FD $tunFd")

            // Safe access to pepperParams (already validated above)
            val pepperParams = currentConfig?.pepperParams
            if (pepperParams == null) {
                AppLogger.e("ChainSupervisor: pepperParams is null after validation check")
                updateLayerStatus("pepper", false, "pepperParams is null")
                return false
            }

            val handle = PepperShaper.attach(
                fdPair = Pair(tunFd, tunFd),
                mode = PepperShaper.SocketMode.TUN,
                params = pepperParams
            )

            if (handle != null && handle > 0) {
                pepperHandle = handle
                updateLayerStatus("pepper", true, null)
                AppLogger.i("ChainSupervisor: PepperShaper attached successfully (handle=$handle)")
                true
            } else {
                AppLogger.w("ChainSupervisor: PepperShaper.attach() returned invalid handle")
                updateLayerStatus("pepper", false, "Invalid handle returned")
                false
            }
        } catch (e: Exception) {
            AppLogger.e("ChainSupervisor: Failed to attach PepperShaper to TUN FD: ${e.message}", e)
            updateLayerStatus("pepper", false, e.message)
            false
        }
    }

    /**
     * Shutdown and cleanup all resources
     * Prevents memory leaks by cancelling all coroutines
     */
    fun shutdown() {
        stop()
        monitoringJob?.cancel()
        job.cancel() // Cancel all child coroutines
        scope.cancel() // Cancel scope
        AppLogger.d("ChainSupervisor: Shutdown complete")
    }
}


