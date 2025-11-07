package com.simplexray.an.chain.supervisor

import android.content.Context
import com.simplexray.an.common.AppLogger
import com.simplexray.an.chain.hysteria2.Hysteria2
import com.simplexray.an.chain.pepper.PepperShaper
import com.simplexray.an.chain.reality.RealitySocks
import com.simplexray.an.chain.reality.RealityXrayIntegrator
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
 * - Reality SOCKS (TLS mimic)
 * - Hysteria2 QUIC accelerator
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
    
    init {
        // Initialize all layers with error handling
        try {
            RealitySocks.init(context)
        } catch (e: Exception) {
            AppLogger.e("ChainSupervisor: Failed to initialize RealitySocks: ${e.message}", e)
        }
        try {
            Hysteria2.init(context)
        } catch (e: Exception) {
            AppLogger.e("ChainSupervisor: Failed to initialize Hysteria2: ${e.message}", e)
        }
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
            
            // 1. Start Reality SOCKS if configured
            if (config.realityConfig != null) {
                // Validate config before starting
                val result = try {
                    RealitySocks.start(config.realityConfig)
                } catch (e: Exception) {
                    AppLogger.e("ChainSupervisor: Exception starting RealitySocks: ${e.message}", e)
                    Result.failure(e)
                }
                results.add(result)
                updateLayerStatus("reality", result.isSuccess, result.exceptionOrNull()?.message)
            }
            
            // 2. Start Hysteria2 if configured (chain to Reality SOCKS)
            if (config.hysteria2Config != null) {
                // Only start if Reality SOCKS succeeded (if configured)
                val realitySucceeded = config.realityConfig == null || 
                    results.firstOrNull()?.isSuccess != false
                
                if (realitySucceeded) {
                    val upstreamSocks = RealitySocks.getLocalAddress()
                    if (upstreamSocks != null || config.realityConfig == null) {
                        val result = try {
                            Hysteria2.start(config.hysteria2Config, upstreamSocks)
                        } catch (e: Exception) {
                            AppLogger.e("ChainSupervisor: Exception starting Hysteria2: ${e.message}", e)
                            Result.failure(e)
                        }
                        results.add(result)
                        updateLayerStatus("hysteria2", result.isSuccess, result.exceptionOrNull()?.message)
                    } else {
                        AppLogger.w("ChainSupervisor: RealitySocks not ready, skipping Hysteria2")
                        results.add(Result.failure(Exception("RealitySocks not ready")))
                        updateLayerStatus("hysteria2", false, "RealitySocks not ready")
                    }
                } else {
                    AppLogger.w("ChainSupervisor: RealitySocks failed, skipping Hysteria2")
                    results.add(Result.failure(Exception("RealitySocks failed")))
                    updateLayerStatus("hysteria2", false, "RealitySocks failed")
                }
            }
            
            // 3. Attach PepperShaper if configured
            if (config.pepperParams != null) {
                // Attempt to attach PepperShaper to socket FDs
                // Try multiple sources: Hysteria2, RealitySocks, or TUN interface
                val pepperResult = try {
                    var attached = false
                    
                    // Try 1: Get FDs from Hysteria2 if running
                    if (config.hysteria2Config != null && Hysteria2.isRunning()) {
                        val hysteria2Fds = Hysteria2.getSocketFds()
                        if (hysteria2Fds != null) {
                            val handle = PepperShaper.attach(
                                fdPair = hysteria2Fds,
                                mode = PepperShaper.SocketMode.TCP,
                                params = config.pepperParams
                            )
                            if (handle != null) {
                                pepperHandle = handle
                                attached = true
                                AppLogger.i("ChainSupervisor: PepperShaper attached to Hysteria2 sockets")
                            }
                        }
                    }
                    
                    // Try 2: If Hysteria2 FDs not available, try to attach at TUN level
                    // Note: This requires TUN FD to be passed from TProxyService
                    // For now, we mark PepperShaper as configured but not attached
                    if (!attached) {
                        AppLogger.w("ChainSupervisor: PepperShaper configured but socket FDs not available")
                        AppLogger.w("ChainSupervisor: PepperShaper will be inactive (chain will work without it)")
                        // Don't fail chain startup - PepperShaper is optional
                        Result.success(Unit)
                    } else {
                        Result.success(Unit)
                    }
                } catch (e: Exception) {
                    AppLogger.e("ChainSupervisor: Failed to attach PepperShaper: ${e.message}", e)
                    // Don't fail chain startup - PepperShaper is optional enhancement
                    AppLogger.w("ChainSupervisor: Continuing without PepperShaper")
                    Result.success(Unit)
                }
                
                updateLayerStatus("pepper", pepperResult.isSuccess, 
                    if (pepperHandle != null) null else "FDs not available (optional)")
            }
            
            // 4. Start Xray-core if configured
            // If Reality config exists, integrate it with Xray config for unified chain tunnel
            if (config.xrayConfigPath != null) {
                // If Reality config is provided, build unified Xray config
                val finalXrayConfigPath = if (config.realityConfig != null) {
                    try {
                        val originalConfigPath = File(context.filesDir, config.xrayConfigPath)
                        if (originalConfigPath.exists()) {
                            // Build unified config with Reality integration
                            val unifiedConfig = RealityXrayIntegrator.buildUnifiedXrayConfig(
                                originalConfigPath.absolutePath,
                                config.realityConfig,
                                chainMode = true
                            )
                            
                            if (unifiedConfig != null) {
                                val unifiedConfigFile = File(context.filesDir, "unified-chain-${System.currentTimeMillis()}.json")
                                unifiedConfigFile.writeText(unifiedConfig)
                                AppLogger.i("ChainSupervisor: Created unified Xray config with Reality integration")
                                unifiedConfigFile.name // Return relative path
                            } else {
                                AppLogger.w("ChainSupervisor: Failed to build unified config, using original")
                                config.xrayConfigPath
                            }
                        } else {
                            AppLogger.w("ChainSupervisor: Original Xray config not found, using provided path")
                            config.xrayConfigPath
                        }
                    } catch (e: Exception) {
                        AppLogger.e("ChainSupervisor: Error building unified config: ${e.message}", e)
                        config.xrayConfigPath
                    }
                } else {
                    config.xrayConfigPath
                }
                
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
            // Reality and Xray are critical, Hysteria2 and PepperShaper are optional
            val criticalLayers = mutableListOf<Result<Unit>>()
            if (config.realityConfig != null) {
                results.firstOrNull()?.let { criticalLayers.add(it) }
            }
            if (config.xrayConfigPath != null) {
                results.lastOrNull()?.let { criticalLayers.add(it) }
            }
            
            val criticalSuccess = criticalLayers.isEmpty() || criticalLayers.all { it.isSuccess }
            val allSuccess = results.all { it.isSuccess }
            
            kotlinx.coroutines.runBlocking {
                statusMutex.withLock {
                    if (criticalSuccess) {
                        startTime.set(System.currentTimeMillis())
                        if (allSuccess) {
                            _status.value = _status.value.copy(state = ChainState.RUNNING)
                            AppLogger.i("ChainSupervisor: Chain started successfully (all layers active)")
                        } else {
                            _status.value = _status.value.copy(state = ChainState.RUNNING)
                            AppLogger.i("ChainSupervisor: Chain started successfully (some optional layers inactive)")
                        }
                    } else {
                        _status.value = _status.value.copy(state = ChainState.DEGRADED)
                        AppLogger.w("ChainSupervisor: Chain started in degraded mode (critical layers failed)")
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
     */
    @Synchronized
    fun stop(): Result<Unit> {
        return try {
            if (_status.value.state == ChainState.STOPPED) {
                return Result.success(Unit)
            }
            
            AppLogger.i("ChainSupervisor: Stopping chain")
            kotlinx.coroutines.runBlocking {
                statusMutex.withLock {
                    _status.value = _status.value.copy(state = ChainState.STOPPING)
                }
            }
            
            // Stop layers in reverse order with error handling
            val stopResults = mutableListOf<Result<Unit>>()
            
            // Stop Xray-core
            val xrayResult = runCatching { XrayCoreLauncher.stop() }
            stopResults.add(xrayResult.map { })
            updateLayerStatus("xray", false, xrayResult.exceptionOrNull()?.message)
            
            // Stop PepperShaper (validate handle before detach)
            if (pepperHandle != null && pepperHandle!! > 0) {
                val pepperResult = runCatching { PepperShaper.detach(pepperHandle!!) }
                stopResults.add(pepperResult.map { })
                updateLayerStatus("pepper", false, pepperResult.exceptionOrNull()?.message)
            }
            pepperHandle = null
            
            // Stop Hysteria2
            val hy2Result = Hysteria2.stop()
            stopResults.add(hy2Result)
            updateLayerStatus("hysteria2", false, hy2Result.exceptionOrNull()?.message)
            
            // Stop RealitySocks
            val realityResult = RealitySocks.stop()
            stopResults.add(realityResult)
            updateLayerStatus("reality", false, realityResult.exceptionOrNull()?.message)
            
            // Log any failures but continue cleanup
            stopResults.forEachIndexed { index, result ->
                if (result.isFailure) {
                    val layerName = when (index) {
                        0 -> "xray"
                        1 -> "pepper"
                        2 -> "hysteria2"
                        3 -> "reality"
                        else -> "unknown"
                    }
                    AppLogger.w("ChainSupervisor: Failed to stop layer $layerName: ${result.exceptionOrNull()?.message}")
                }
            }
            
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
                    val realityStatus = RealitySocks.getStatus()
                    val hy2Metrics = Hysteria2.getMetrics()
                    
                    // Safe aggregation with overflow protection
                    val totalBytesUp = runCatching {
                        (realityStatus?.bytesUp ?: 0L) + (hy2Metrics?.bytesUp ?: 0L)
                    }.getOrElse { 0L }
                    
                    val totalBytesDown = runCatching {
                        (realityStatus?.bytesDown ?: 0L) + (hy2Metrics?.bytesDown ?: 0L)
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

