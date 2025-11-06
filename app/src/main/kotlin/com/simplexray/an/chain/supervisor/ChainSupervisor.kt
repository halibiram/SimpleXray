package com.simplexray.an.chain.supervisor

import android.content.Context
import com.simplexray.an.common.AppLogger
import com.simplexray.an.chain.hysteria2.Hysteria2
import com.simplexray.an.chain.pepper.PepperShaper
import com.simplexray.an.chain.reality.RealitySocks
import com.simplexray.an.xray.XrayCoreLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
class ChainSupervisor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
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
    private var pepperHandle: Long? = null
    
    init {
        // Initialize all layers
        RealitySocks.init(context)
        Hysteria2.init(context)
        PepperShaper.init(context)
    }
    
    /**
     * Start the chain with given configuration
     */
    fun start(config: ChainConfig): Result<Unit> {
        return try {
            if (_status.value.state != ChainState.STOPPED) {
                return Result.failure(IllegalStateException("Chain is already running"))
            }
            
            AppLogger.i("ChainSupervisor: Starting chain '${config.name}'")
            currentConfig = config
            _status.value = _status.value.copy(state = ChainState.STARTING)
            
            // Start layers in order
            val results = mutableListOf<Result<Unit>>()
            
            // 1. Start Reality SOCKS if configured
            if (config.realityConfig != null) {
                val result = RealitySocks.start(config.realityConfig)
                results.add(result)
                updateLayerStatus("reality", result.isSuccess, result.exceptionOrNull()?.message)
            }
            
            // 2. Start Hysteria2 if configured (chain to Reality SOCKS)
            if (config.hysteria2Config != null) {
                val upstreamSocks = RealitySocks.getLocalAddress()
                val result = Hysteria2.start(config.hysteria2Config, upstreamSocks)
                results.add(result)
                updateLayerStatus("hysteria2", result.isSuccess, result.exceptionOrNull()?.message)
            }
            
            // 3. Attach PepperShaper if configured
            if (config.pepperParams != null) {
                // Attempt to attach PepperShaper to socket FDs
                // Note: Proper FD extraction requires:
                // 1. Hysteria2/RealitySocks to expose socket FDs via JNI
                // 2. Or attach at Xray level if Xray exposes FDs
                // For now, we attempt attachment if FDs are available
                val pepperResult = try {
                    // Try to get socket FDs from Hysteria2 if it's running
                    // This is a placeholder - actual FD extraction needs to be implemented
                    // in Hysteria2.getSocketFds() or similar method
                    val hysteria2Fds = if (config.hysteria2Config != null && Hysteria2.isRunning()) {
                        // TODO: Implement Hysteria2.getSocketFds() to return Pair<readFd, writeFd>
                        // For now, return null to indicate FDs not available
                        null
                    } else {
                        null
                    }
                    
                    if (hysteria2Fds != null) {
                        val handle = PepperShaper.attach(
                            fdPair = hysteria2Fds,
                            mode = PepperShaper.SocketMode.TCP, // Default to TCP
                            params = config.pepperParams
                        )
                        if (handle != null) {
                            pepperHandle = handle
                            Result.success(Unit)
                        } else {
                            Result.failure(Exception("Failed to attach PepperShaper"))
                        }
                    } else {
                        // FDs not available yet - mark as enabled but not attached
                        // This allows the chain to start, but PepperShaper won't be active
                        // until FDs become available
                        AppLogger.w("ChainSupervisor: PepperShaper configured but socket FDs not available")
                        Result.success(Unit) // Don't fail the chain startup
                    }
                } catch (e: Exception) {
                    AppLogger.e("ChainSupervisor: Failed to attach PepperShaper", e)
                    Result.failure(e)
                }
                
                updateLayerStatus("pepper", pepperResult.isSuccess, pepperResult.exceptionOrNull()?.message)
            }
            
            // 4. Start Xray-core if configured
            if (config.xrayConfigPath != null) {
                val configFile = File(context.filesDir, config.xrayConfigPath)
                
                // Convert ChainConfig.TlsMode to TlsImplementation
                val tlsMode = when (config.tlsMode) {
                    ChainConfig.TlsMode.BORINGSSL -> com.simplexray.an.chain.tls.TlsImplementation.BORINGSSL
                    ChainConfig.TlsMode.CONSCRYPT -> com.simplexray.an.chain.tls.TlsImplementation.CONSCRYPT
                    ChainConfig.TlsMode.AUTO -> com.simplexray.an.chain.tls.TlsImplementation.AUTO
                }
                
                val result = runCatching {
                    XrayCoreLauncher.start(
                        context,
                        configFile,
                        maxRetries = 3,
                        retryDelayMs = 5000,
                        tlsMode = tlsMode
                    )
                }
                results.add(if (result.isSuccess && result.getOrDefault(false)) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("Xray-core failed to start"))
                })
                updateLayerStatus("xray", result.isSuccess, result.exceptionOrNull()?.message)
            }
            
            // Check if all critical layers started successfully
            val allSuccess = results.all { it.isSuccess }
            
            if (allSuccess) {
                startTime.set(System.currentTimeMillis())
                _status.value = _status.value.copy(state = ChainState.RUNNING)
                AppLogger.i("ChainSupervisor: Chain started successfully")
                
                // Start monitoring
                startMonitoring()
            } else {
                _status.value = _status.value.copy(state = ChainState.DEGRADED)
                AppLogger.w("ChainSupervisor: Chain started in degraded mode")
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e("ChainSupervisor: Failed to start chain", e)
            _status.value = _status.value.copy(state = ChainState.STOPPED)
            Result.failure(e)
        }
    }
    
    /**
     * Stop the chain
     */
    fun stop(): Result<Unit> {
        return try {
            if (_status.value.state == ChainState.STOPPED) {
                return Result.success(Unit)
            }
            
            AppLogger.i("ChainSupervisor: Stopping chain")
            _status.value = _status.value.copy(state = ChainState.STOPPING)
            
            // Stop layers in reverse order
            XrayCoreLauncher.stop()
            updateLayerStatus("xray", false, null)
            
            pepperHandle?.let { PepperShaper.detach(it) }
            pepperHandle = null
            updateLayerStatus("pepper", false, null)
            
            Hysteria2.stop()
            updateLayerStatus("hysteria2", false, null)
            
            RealitySocks.stop()
            updateLayerStatus("reality", false, null)
            
            startTime.set(0)
            _status.value = _status.value.copy(
                state = ChainState.STOPPED,
                uptime = 0
            )
            
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
     * Update layer status
     */
    private fun updateLayerStatus(name: String, isRunning: Boolean, error: String?) {
        val currentLayers = _status.value.layers.toMutableMap()
        currentLayers[name] = LayerStatus(
            name = name,
            isRunning = isRunning,
            error = error,
            lastUpdate = System.currentTimeMillis()
        )
        _status.value = _status.value.copy(layers = currentLayers)
    }
    
    /**
     * Start monitoring loop
     */
    private fun startMonitoring() {
        scope.launch {
            while (_status.value.state == ChainState.RUNNING || 
                   _status.value.state == ChainState.DEGRADED) {
                try {
                    val uptime = if (startTime.get() > 0) {
                        System.currentTimeMillis() - startTime.get()
                    } else {
                        0
                    }
                    
                    // Aggregate metrics from all layers
                    val realityStatus = RealitySocks.getStatus()
                    val hy2Metrics = Hysteria2.getMetrics()
                    
                    _status.value = _status.value.copy(
                        uptime = uptime,
                        totalBytesUp = realityStatus.bytesUp + hy2Metrics.bytesUp,
                        totalBytesDown = realityStatus.bytesDown + hy2Metrics.bytesDown
                    )
                    
                    kotlinx.coroutines.delay(1000) // Update every second
                } catch (e: Exception) {
                    AppLogger.e("ChainSupervisor: Monitoring error", e)
                    kotlinx.coroutines.delay(5000)
                }
            }
        }
    }
}

