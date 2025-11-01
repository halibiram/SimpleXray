package com.simplexray.an.performance.optimizer

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.simplexray.an.performance.model.PerformanceMetrics
import com.simplexray.an.performance.benchmark.SpeedTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Smart connection management with automatic failover and optimization
 */
class SmartConnectionManager(
    private val context: Context
) {
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val speedTest = SpeedTest()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _activeServer = MutableStateFlow<ServerConfig?>(null)
    val activeServer: StateFlow<ServerConfig?> = _activeServer.asStateFlow()

    private val _serverHealthMap = MutableStateFlow<Map<String, ServerHealth>>(emptyMap())
    val serverHealthMap: StateFlow<Map<String, ServerHealth>> = _serverHealthMap.asStateFlow()
    private val serverConfigMap = ConcurrentHashMap<String, ServerConfig>()

    private var healthCheckJob: Job? = null
    private var networkCallbackRegistered = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Configuration
    private var failoverEnabled = true
    private var autoReconnectEnabled = true
    private var healthCheckInterval = 60000L // 1 minute

    // Failover thresholds
    private val latencyThreshold = 500 // ms
    private val packetLossThreshold = 5f // percentage
    private val consecutiveFailuresThreshold = 3

    /**
     * Connection state
     */
    sealed class ConnectionState {
        data object Disconnected : ConnectionState()
        data object Connecting : ConnectionState()
        data class Connected(val server: ServerConfig, val latency: Int) : ConnectionState()
        data class Reconnecting(val reason: String) : ConnectionState()
        data class Failed(val error: String) : ConnectionState()
    }

    /**
     * Server configuration
     */
    data class ServerConfig(
        val id: String,
        val name: String,
        val host: String,
        val port: Int,
        val protocol: String,
        val priority: Int = 0, // Higher = preferred
        val tags: List<String> = emptyList()
    )

    /**
     * Server health information
     */
    data class ServerHealth(
        val serverId: String,
        val isHealthy: Boolean = true,
        val latency: Int = 0,
        val packetLoss: Float = 0f,
        val consecutiveFailures: Int = 0,
        val lastChecked: Long = System.currentTimeMillis(),
        val lastSuccessful: Long = System.currentTimeMillis(),
        val score: Float = 100f
    ) {
        /**
         * Calculate health score (0-100)
         */
        fun calculateScore(): Float {
            var score = 100f

            // Latency penalty
            score -= when {
                latency < 50 -> 0f
                latency < 100 -> 5f
                latency < 200 -> 15f
                latency < 500 -> 30f
                else -> 50f
            }

            // Packet loss penalty
            score -= packetLoss * 10

            // Failure penalty
            score -= consecutiveFailures * 10f

            return score.coerceIn(0f, 100f)
        }
    }

    /**
     * Start connection management
     */
    fun start(servers: List<ServerConfig>) {
        // Initialize server health
        val initialHealth = servers.associate { server ->
            server.id to ServerHealth(serverId = server.id)
        }
        _serverHealthMap.value = initialHealth
        serverConfigMap.clear()
        servers.forEach { server ->
            registerServer(server)
        }

        // Register network callback
        registerNetworkCallback()

        // Start health checks
        startHealthChecks()
    }

    /**
     * Stop connection management
     */
    fun stop() {
        healthCheckJob?.cancel()
        unregisterNetworkCallback()
    }

    /**
     * Connect to server
     */
    suspend fun connect(server: ServerConfig) {
        registerServer(server)
        _connectionState.value = ConnectionState.Connecting
        _activeServer.value = server

        try {
            // Test connection
            val pingResult = speedTest.ping(server.host, server.port)

            if (pingResult.success) {
                _connectionState.value = ConnectionState.Connected(server, pingResult.latency)
                updateServerHealth(server.id, true, pingResult.latency, 0f)
            } else {
                _connectionState.value = ConnectionState.Failed(pingResult.error ?: "Connection failed")
                updateServerHealth(server.id, false)

                // Auto-failover if enabled
                if (failoverEnabled) {
                    performFailover()
                }
            }
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Failed(e.message ?: "Unknown error")
            updateServerHealth(server.id, false)

            if (failoverEnabled) {
                performFailover()
            }
        }
    }

    /**
     * Disconnect
     */
    fun disconnect() {
        _connectionState.value = ConnectionState.Disconnected
        _activeServer.value = null
    }

    /**
     * Perform automatic failover to best available server
     */
    private suspend fun performFailover() {
        val currentServer = _activeServer.value ?: return

        _connectionState.value = ConnectionState.Reconnecting("Poor connection quality")

        // Find best alternative server
        val bestServer = findBestServer(excludeServerId = currentServer.id)

        if (bestServer != null) {
            delay(2000) // Wait before reconnecting
            connect(bestServer)
        } else {
            _connectionState.value = ConnectionState.Failed("No healthy servers available")
        }
    }

    /**
     * Find best server based on health scores
     */
    private fun findBestServer(excludeServerId: String? = null): ServerConfig? {
        val candidates = _serverHealthMap.value.mapNotNull { (serverId, health) ->
            if (serverId == excludeServerId) return@mapNotNull null
            if (!health.isHealthy || health.consecutiveFailures >= consecutiveFailuresThreshold) {
                return@mapNotNull null
            }

            val config = serverConfigMap[serverId] ?: return@mapNotNull null
            config to health
        }

        if (candidates.isEmpty()) {
            return null
        }

        val comparator = compareByDescending<Pair<ServerConfig, ServerHealth>> { (_, health) ->
            health.score
        }
            .thenByDescending { (config, _) -> config.priority }
            .thenBy { (_, health) -> health.latency }
            .thenBy { (config, _) -> config.id }

        return candidates.maxWithOrNull(comparator)?.first
    }

    /**
     * Update server health information
     */
    private fun updateServerHealth(
        serverId: String,
        success: Boolean,
        latency: Int = 0,
        packetLoss: Float = 0f
    ) {
        val currentHealth = _serverHealthMap.value[serverId] ?: ServerHealth(serverId = serverId)

        val updatedHealth = if (success) {
            currentHealth.copy(
                isHealthy = true,
                latency = latency,
                packetLoss = packetLoss,
                consecutiveFailures = 0,
                lastChecked = System.currentTimeMillis(),
                lastSuccessful = System.currentTimeMillis()
            )
        } else {
            currentHealth.copy(
                isHealthy = false,
                consecutiveFailures = currentHealth.consecutiveFailures + 1,
                lastChecked = System.currentTimeMillis()
            )
        }

        val scoredHealth = updatedHealth.copy(score = updatedHealth.calculateScore())
        _serverHealthMap.update { current ->
            current + (serverId to scoredHealth)
        }
    }

    /**
     * Start periodic health checks
     */
    private fun startHealthChecks() {
        healthCheckJob?.cancel()

        healthCheckJob = scope.launch {
            while (isActive) {
                val serversSnapshot = serverConfigMap.values.toList()
                if (serversSnapshot.isEmpty()) {
                    delay(healthCheckInterval)
                    continue
                }

                serversSnapshot.forEach { server ->
                    launch {
                        checkServerHealth(server)
                    }
                }

                delay(healthCheckInterval)
            }
        }
    }

    private fun registerServer(server: ServerConfig) {
        serverConfigMap[server.id] = server

        _serverHealthMap.update { current ->
            if (current.containsKey(server.id)) {
                current
            } else {
                current + (server.id to ServerHealth(serverId = server.id))
            }
        }
    }

    /**
     * Check individual server health
     */
    private suspend fun checkServerHealth(server: ServerConfig) {
        try {
            val pingResults = speedTest.pingMultiple(server.host, server.port, count = 5)
            val successfulPings = pingResults.filter { it.success }

            if (successfulPings.isNotEmpty()) {
                val avgLatency = successfulPings.map { it.latency }.average().toInt()
                val packetLoss = speedTest.calculatePacketLoss(pingResults)

                val isHealthy = avgLatency < latencyThreshold && packetLoss < packetLossThreshold

                updateServerHealth(server.id, isHealthy, avgLatency, packetLoss)

                // Trigger failover if current server is unhealthy
                if (!isHealthy && _activeServer.value?.id == server.id && failoverEnabled) {
                    performFailover()
                }
            } else {
                updateServerHealth(server.id, false)
            }
        } catch (e: Exception) {
            updateServerHealth(server.id, false)
        }
    }

    /**
     * Register network connectivity callback
     */
    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return

        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        // Store callback as member variable to allow proper unregistration
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Network became available
                if (autoReconnectEnabled && _connectionState.value is ConnectionState.Failed) {
                    scope.launch {
                        _activeServer.value?.let { connect(it) }
                    }
                }
            }

            override fun onLost(network: Network) {
                // Network lost
                if (_connectionState.value is ConnectionState.Connected) {
                    _connectionState.value = ConnectionState.Reconnecting("Network lost")

                    if (autoReconnectEnabled) {
                        scope.launch {
                            delay(2000)
                            _activeServer.value?.let { connect(it) }
                        }
                    }
                }
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                // Network type changed (e.g., Wi-Fi to cellular)
                // Could trigger profile change
            }
        }

        networkCallback?.let { connectivityManager.registerNetworkCallback(networkRequest, it) }
        networkCallbackRegistered = true
    }

    /**
     * Unregister network callback
     */
    private fun unregisterNetworkCallback() {
        if (!networkCallbackRegistered) return

        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            networkCallback?.let { callback ->
                connectivityManager?.unregisterNetworkCallback(callback)
            }
            networkCallback = null
            networkCallbackRegistered = false
        } catch (e: Exception) {
            // Ignore exceptions during unregistration
        }
    }

    /**
     * Enable/disable failover
     */
    fun setFailoverEnabled(enabled: Boolean) {
        failoverEnabled = enabled
    }

    /**
     * Enable/disable auto-reconnect
     */
    fun setAutoReconnectEnabled(enabled: Boolean) {
        autoReconnectEnabled = enabled
    }

    /**
     * Set health check interval
     */
    fun setHealthCheckInterval(intervalMs: Long) {
        if (intervalMs <= 0 || intervalMs == healthCheckInterval) {
            return
        }

        healthCheckInterval = intervalMs

        if (serverConfigMap.isNotEmpty()) {
            startHealthChecks()
        }
    }
}
