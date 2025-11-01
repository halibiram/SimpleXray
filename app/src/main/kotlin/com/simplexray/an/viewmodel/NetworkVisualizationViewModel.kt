package com.simplexray.an.viewmodel

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.simplexray.an.common.CoreStatsClient
import com.simplexray.an.performance.model.ConnectionQuality
import com.simplexray.an.performance.model.ConnectionStats
import com.simplexray.an.performance.model.PerformanceMetrics
import com.simplexray.an.performance.monitor.PerformanceMonitor
import com.simplexray.an.protocol.visualization.GraphDataPoint
import com.simplexray.an.protocol.visualization.NetworkConnection
import com.simplexray.an.protocol.visualization.NetworkNode
import com.simplexray.an.protocol.visualization.NetworkTopology
import com.simplexray.an.protocol.visualization.TimeSeriesData
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for the Network Visualization screen with real-time Xray core integration.
 *
 * This implementation enriches the original monitor-only behaviour with:
 * - derived network health state with quality, trend and resource metrics
 * - dynamic alerts for high latency, packet loss and throughput degradation
 * - topology updates that reflect the current connection quality and stability
 * - configurable update interval and graceful lifecycle handling
 */
class NetworkVisualizationViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val baseTopology = createInitialTopology()

    private var monitorUpdateIntervalMs: Long = DEFAULT_UPDATE_INTERVAL_MS
    private val coreStatsClientFlow = MutableStateFlow<CoreStatsClient?>(null)

    private val performanceMonitor: PerformanceMonitor by lazy {
        PerformanceMonitor(application, monitorUpdateIntervalMs, coreStatsClientFlow.value)
    }

    private val _topology = MutableStateFlow(baseTopologySnapshot())
    val topology: StateFlow<NetworkTopology> = _topology.asStateFlow()

    private val _latencyHistory = MutableStateFlow<List<TimeSeriesData>>(emptyList())
    val latencyHistory: StateFlow<List<TimeSeriesData>> = _latencyHistory.asStateFlow()

    private val _bandwidthHistory = MutableStateFlow<List<TimeSeriesData>>(emptyList())
    val bandwidthHistory: StateFlow<List<TimeSeriesData>> = _bandwidthHistory.asStateFlow()

    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring.asStateFlow()

    private val _networkHealth = MutableStateFlow(NetworkHealth())
    val networkHealth: StateFlow<NetworkHealth> = _networkHealth.asStateFlow()

    private val _activeAlerts = MutableStateFlow<List<NetworkAlert>>(emptyList())
    val activeAlerts: StateFlow<List<NetworkAlert>> = _activeAlerts.asStateFlow()

    private val _connectionStats = MutableStateFlow(ConnectionStats())
    val connectionStats: StateFlow<ConnectionStats> = _connectionStats.asStateFlow()

    private val latencyPoints = mutableListOf<GraphDataPoint>()
    private val uploadPoints = mutableListOf<GraphDataPoint>()
    private val downloadPoints = mutableListOf<GraphDataPoint>()

    private var metricsCollectionJob: Job? = null
    private var connectionStatsJob: Job? = null
    private var lastMetrics: PerformanceMetrics? = null
    private var hasCollectedSample = false

    init {
        observeCoreStatsClient()
        startMonitoring()
    }

    fun setCoreStatsClient(client: CoreStatsClient?) {
        coreStatsClientFlow.value = client
    }

    fun setUpdateInterval(intervalMs: Long) {
        if (intervalMs <= 0 || intervalMs == monitorUpdateIntervalMs) return

        monitorUpdateIntervalMs = intervalMs
        performanceMonitor.setUpdateInterval(intervalMs)
    }

    fun startMonitoring() {
        if (_isMonitoring.value) return

        _isMonitoring.value = true
        performanceMonitor.setUpdateInterval(monitorUpdateIntervalMs)
        performanceMonitor.setCoreStatsClient(coreStatsClientFlow.value)
        performanceMonitor.start()

        metricsCollectionJob?.cancel()
        metricsCollectionJob = viewModelScope.launch {
            performanceMonitor.currentMetrics.collectLatest { metrics ->
                if (!_isMonitoring.value) return@collectLatest
                handleMetrics(metrics)
            }
        }

        connectionStatsJob?.cancel()
        connectionStatsJob = viewModelScope.launch {
            performanceMonitor.connectionStats.collectLatest { stats ->
                if (!_isMonitoring.value) return@collectLatest
                _connectionStats.value = stats
            }
        }
    }

    fun stopMonitoring() {
        if (!_isMonitoring.value) return

        _isMonitoring.value = false
        metricsCollectionJob?.cancel()
        metricsCollectionJob = null
        connectionStatsJob?.cancel()
        connectionStatsJob = null
        performanceMonitor.stop()
    }

    fun refreshTopology() {
        viewModelScope.launch {
            delay(REFRESH_DELAY_MS)
            _topology.value = baseTopologySnapshot()
            lastMetrics?.let { updateTopology(it) }
        }
    }

    fun dismissAlert(alert: NetworkAlert) {
        _activeAlerts.update { current -> current.filterNot { it == alert } }
    }

    override fun onCleared() {
        super.onCleared()
        stopMonitoring()
    }

    private fun observeCoreStatsClient() {
        viewModelScope.launch {
            coreStatsClientFlow.collectLatest { client ->
                performanceMonitor.setCoreStatsClient(client)
            }
        }
    }

    private fun handleMetrics(metrics: PerformanceMetrics) {
        val isFirstSample = !hasCollectedSample
        hasCollectedSample = true
        lastMetrics = metrics

        updateTimeSeries(metrics)
        updateTopology(metrics)
        updateNetworkHealth(metrics)
        updateConnectionStats(metrics)
        evaluateAlerts(metrics, isFirstSample)
    }

    private fun updateTimeSeries(metrics: PerformanceMetrics) {
        val timestamp = metrics.timestamp
        appendPoint(latencyPoints, GraphDataPoint(timestamp, metrics.latency.toFloat()))
        appendPoint(uploadPoints, GraphDataPoint(timestamp, metrics.uploadSpeed / 1024f))
        appendPoint(downloadPoints, GraphDataPoint(timestamp, metrics.downloadSpeed / 1024f))

        _latencyHistory.value = listOf(
            TimeSeriesData(
                name = "Latency",
                dataPoints = latencyPoints.toList(),
                unit = "ms",
                color = LATENCY_COLOR
            )
        )

        _bandwidthHistory.value = listOf(
            TimeSeriesData(
                name = "Upload",
                dataPoints = uploadPoints.toList(),
                unit = "KB/s",
                color = UPLOAD_COLOR
            ),
            TimeSeriesData(
                name = "Download",
                dataPoints = downloadPoints.toList(),
                unit = "KB/s",
                color = DOWNLOAD_COLOR
            )
        )
    }

    private fun updateTopology(metrics: PerformanceMetrics) {
        val currentTopology = _topology.value
        val quality = metrics.overallQuality
        val stability = metrics.connectionStability

        val updatedNodes = currentTopology.nodes.map { node ->
            when (node.id) {
                CLIENT_NODE_ID -> node.copy(
                    metadata = node.metadata + mapOf(
                        "upload" to formatSpeed(metrics.uploadSpeed),
                        "download" to formatSpeed(metrics.downloadSpeed)
                    )
                )

                PROXY_NODE_ID -> node.copy(
                    status = mapQualityToNodeStatus(quality, stability),
                    metadata = node.metadata + mapOf(
                        "latency" to "${metrics.latency} ms",
                        "jitter" to "${metrics.jitter} ms",
                        "quality" to quality.displayName
                    )
                )

                TARGET_NODE_ID -> node.copy(
                    status = mapQualityToNodeStatus(quality, stability),
                    metadata = node.metadata + mapOf(
                        "latency" to "${metrics.latency} ms",
                        "stability" to "${stability.roundToInt()}%"
                    )
                )

                else -> node
            }
        }

        val updatedConnections = currentTopology.connections.map { connection ->
            val latency = when (connection.fromNodeId to connection.toNodeId) {
                CLIENT_NODE_ID to PROXY_NODE_ID -> (metrics.latency / 2).coerceAtLeast(1)
                PROXY_NODE_ID to TARGET_NODE_ID -> metrics.latency.coerceAtLeast(1)
                else -> if (metrics.latency > 0) metrics.latency else connection.latency
            }

            val bandwidth = when (connection.fromNodeId to connection.toNodeId) {
                CLIENT_NODE_ID to PROXY_NODE_ID -> metrics.uploadSpeed
                PROXY_NODE_ID to TARGET_NODE_ID -> metrics.downloadSpeed
                else -> max(connection.bandwidth, metrics.downloadSpeed)
            }

            connection.copy(
                latency = latency,
                bandwidth = bandwidth,
                status = mapQualityToConnectionStatus(quality, metrics.packetLoss)
            )
        }

        _topology.value = currentTopology.copy(nodes = updatedNodes, connections = updatedConnections)
    }

    private fun updateNetworkHealth(metrics: PerformanceMetrics) {
        _networkHealth.value = NetworkHealth(
            quality = metrics.overallQuality,
            latencyMs = metrics.latency,
            jitterMs = metrics.jitter,
            packetLossPercent = metrics.packetLoss,
            stabilityScore = metrics.connectionStability,
            uploadKbps = metrics.uploadSpeed / 1024f,
            downloadKbps = metrics.downloadSpeed / 1024f,
            cpuUsagePercent = metrics.cpuUsage,
            memoryUsageBytes = metrics.memoryUsage,
            nativeMemoryUsageBytes = metrics.nativeMemoryUsage,
            trend = calculateLatencyTrend()
        )
    }

    private fun updateConnectionStats(metrics: PerformanceMetrics) {
        _connectionStats.update { current ->
            current.copy(
                averageLatency = if (current.averageLatency == 0) {
                    metrics.latency
                } else {
                    ((current.averageLatency * 4) + metrics.latency) / 5
                },
                peakDownloadSpeed = max(current.peakDownloadSpeed, metrics.downloadSpeed),
                peakUploadSpeed = max(current.peakUploadSpeed, metrics.uploadSpeed)
            )
        }
    }

    private fun evaluateAlerts(metrics: PerformanceMetrics, skip: Boolean) {
        val now = System.currentTimeMillis()
        if (skip) {
            _activeAlerts.update { current ->
                current.filter { now - it.timestamp <= ALERT_RETENTION_MS }
            }
            return
        }

        val generated = mutableListOf<NetworkAlert>()

        if (metrics.latency >= HIGH_LATENCY_THRESHOLD_MS) {
            generated += NetworkAlert.HighLatency(metrics.latency, HIGH_LATENCY_THRESHOLD_MS)
        }

        if (metrics.packetLoss >= WARNING_PACKET_LOSS_PERCENT) {
            generated += NetworkAlert.PacketLoss(metrics.packetLoss, WARNING_PACKET_LOSS_PERCENT)
        }

        if (metrics.connectionStability <= STABILITY_WARNING_THRESHOLD) {
            generated += NetworkAlert.UnstableConnection(metrics.connectionStability)
        }

        val downloadKbps = metrics.downloadSpeed / 1024f
        if (downloadKbps <= LOW_THROUGHPUT_THRESHOLD_KBPS && metrics.overallQuality <= ConnectionQuality.Poor) {
            generated += NetworkAlert.InsufficientThroughput(downloadKbps)
        }

        _activeAlerts.update { current ->
            val pruned = current.filter { now - it.timestamp <= ALERT_RETENTION_MS }
            if (generated.isEmpty()) {
                pruned
            } else {
                val filtered = pruned.filter { existing ->
                    generated.none { it::class == existing::class }
                }
                (generated + filtered)
                    .sortedByDescending { it.timestamp }
                    .take(MAX_ACTIVE_ALERTS)
            }
        }
    }

    private fun appendPoint(buffer: MutableList<GraphDataPoint>, point: GraphDataPoint) {
        buffer += point
        if (buffer.size > HISTORY_CAPACITY) {
            buffer.removeAt(0)
        }
    }

    private fun mapQualityToNodeStatus(
        quality: ConnectionQuality,
        stability: Float
    ): NetworkNode.NodeStatus {
        if (stability <= STABILITY_CRITICAL_THRESHOLD) {
            return NetworkNode.NodeStatus.ERROR
        }
        if (stability <= STABILITY_WARNING_THRESHOLD) {
            return NetworkNode.NodeStatus.WARNING
        }

        return when (quality) {
            ConnectionQuality.Excellent, ConnectionQuality.Good -> NetworkNode.NodeStatus.ACTIVE
            ConnectionQuality.Fair -> NetworkNode.NodeStatus.WARNING
            ConnectionQuality.Poor, ConnectionQuality.VeryPoor -> NetworkNode.NodeStatus.ERROR
        }
    }

    private fun mapQualityToConnectionStatus(
        quality: ConnectionQuality,
        packetLoss: Float
    ): NetworkConnection.ConnectionStatus {
        if (packetLoss >= CRITICAL_PACKET_LOSS_PERCENT) {
            return NetworkConnection.ConnectionStatus.ERROR
        }

        return when (quality) {
            ConnectionQuality.Excellent, ConnectionQuality.Good -> NetworkConnection.ConnectionStatus.ESTABLISHED
            ConnectionQuality.Fair -> NetworkConnection.ConnectionStatus.CONNECTING
            ConnectionQuality.Poor -> NetworkConnection.ConnectionStatus.DISCONNECTED
            ConnectionQuality.VeryPoor -> NetworkConnection.ConnectionStatus.ERROR
        }
    }

    private fun calculateLatencyTrend(): NetworkTrend {
        if (latencyPoints.size < TREND_WINDOW_SIZE) {
            return NetworkTrend.Stable
        }

        val window = latencyPoints.takeLast(TREND_WINDOW_SIZE)
        val delta = window.last().value - window.first().value
        return when {
            delta <= -TREND_THRESHOLD_MS -> NetworkTrend.Improving
            delta >= TREND_THRESHOLD_MS -> NetworkTrend.Degrading
            else -> NetworkTrend.Stable
        }
    }

    private fun formatSpeed(bytesPerSecond: Long): String {
        if (bytesPerSecond <= 0) return "0 B/s"

        val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
        var value = bytesPerSecond.toDouble()
        var unitIndex = 0
        while (value >= 1024 && unitIndex < units.lastIndex) {
            value /= 1024
            unitIndex++
        }
        return String.format(Locale.US, "%.1f %s", value, units[unitIndex])
    }

    private fun baseTopologySnapshot(): NetworkTopology {
        return baseTopology.copy(
            nodes = baseTopology.nodes.map { it.copy(metadata = it.metadata.toMap()) },
            connections = baseTopology.connections.map { it.copy() }
        )
    }

    private fun createInitialTopology(): NetworkTopology {
        val nodes = listOf(
            NetworkNode(
                id = CLIENT_NODE_ID,
                label = "Your Device",
                type = NetworkNode.NodeType.CLIENT,
                status = NetworkNode.NodeStatus.ACTIVE,
                position = Offset(100f, 300f),
                metadata = mapOf("device" to "Android")
            ),
            NetworkNode(
                id = PROXY_NODE_ID,
                label = "Proxy Server",
                type = NetworkNode.NodeType.PROXY_SERVER,
                status = NetworkNode.NodeStatus.ACTIVE,
                position = Offset(400f, 300f),
                metadata = mapOf("location" to "Singapore", "ip" to "192.168.1.1")
            ),
            NetworkNode(
                id = DNS_NODE_ID,
                label = "DNS Server",
                type = NetworkNode.NodeType.DNS_SERVER,
                status = NetworkNode.NodeStatus.ACTIVE,
                position = Offset(400f, 150f),
                metadata = mapOf("server" to "8.8.8.8")
            ),
            NetworkNode(
                id = TARGET_NODE_ID,
                label = "Target Server",
                type = NetworkNode.NodeType.TARGET_SERVER,
                status = NetworkNode.NodeStatus.ACTIVE,
                position = Offset(700f, 300f),
                metadata = mapOf("domain" to "example.com")
            )
        )

        val connections = listOf(
            NetworkConnection(
                fromNodeId = CLIENT_NODE_ID,
                toNodeId = PROXY_NODE_ID,
                latency = 50,
                bandwidth = 10_000_000,
                protocol = "VLESS",
                status = NetworkConnection.ConnectionStatus.ESTABLISHED
            ),
            NetworkConnection(
                fromNodeId = CLIENT_NODE_ID,
                toNodeId = DNS_NODE_ID,
                latency = 30,
                bandwidth = 1_000_000,
                protocol = "DNS",
                status = NetworkConnection.ConnectionStatus.ESTABLISHED
            ),
            NetworkConnection(
                fromNodeId = PROXY_NODE_ID,
                toNodeId = TARGET_NODE_ID,
                latency = 100,
                bandwidth = 50_000_000,
                protocol = "TCP",
                status = NetworkConnection.ConnectionStatus.ESTABLISHED
            )
        )

        return NetworkTopology(nodes, connections)
    }

    private companion object {
        private const val DEFAULT_UPDATE_INTERVAL_MS = 1000L
        private const val HISTORY_CAPACITY = 120
        private const val TREND_WINDOW_SIZE = 5
        private const val TREND_THRESHOLD_MS = 25f
        private const val HIGH_LATENCY_THRESHOLD_MS = 250
        private const val WARNING_PACKET_LOSS_PERCENT = 1.5f
        private const val CRITICAL_PACKET_LOSS_PERCENT = 5f
        private const val LOW_THROUGHPUT_THRESHOLD_KBPS = 128f
        private const val STABILITY_WARNING_THRESHOLD = 60f
        private const val STABILITY_CRITICAL_THRESHOLD = 35f
        private const val ALERT_RETENTION_MS = 60_000L
        private const val MAX_ACTIVE_ALERTS = 4
        private const val REFRESH_DELAY_MS = 500L

        private const val CLIENT_NODE_ID = "client"
        private const val PROXY_NODE_ID = "proxy"
        private const val DNS_NODE_ID = "dns"
        private const val TARGET_NODE_ID = "target"

        private const val LATENCY_COLOR: Long = 0xFF2196F3
        private const val UPLOAD_COLOR: Long = 0xFFFF9800
        private const val DOWNLOAD_COLOR: Long = 0xFF4CAF50
    }
}

/**
 * Aggregated network health snapshot derived from `PerformanceMetrics`.
 */
data class NetworkHealth(
    val quality: ConnectionQuality = ConnectionQuality.Good,
    val latencyMs: Int = 0,
    val jitterMs: Int = 0,
    val packetLossPercent: Float = 0f,
    val stabilityScore: Float = 100f,
    val uploadKbps: Float = 0f,
    val downloadKbps: Float = 0f,
    val cpuUsagePercent: Float = 0f,
    val memoryUsageBytes: Long = 0,
    val nativeMemoryUsageBytes: Long = 0,
    val trend: NetworkTrend = NetworkTrend.Stable
)

/**
 * Trend indicator for latency evolution.
 */
enum class NetworkTrend {
    Improving,
    Stable,
    Degrading
}

/**
 * High-level alerts surfaced to the UI when the connection quality degrades.
 */
sealed class NetworkAlert(
    val severity: Severity,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    enum class Severity { INFO, WARNING, CRITICAL }

    data class HighLatency(val latencyMs: Int, val thresholdMs: Int) : NetworkAlert(
        severity = Severity.CRITICAL,
        message = "Latency $latencyMs ms exceeded $thresholdMs ms"
    )

    data class PacketLoss(val percentage: Float, val threshold: Float) : NetworkAlert(
        severity = Severity.WARNING,
        message = String.format(Locale.US, "Packet loss %.1f%% exceeds %.1f%%", percentage, threshold)
    )

    data class UnstableConnection(val stability: Float) : NetworkAlert(
        severity = Severity.WARNING,
        message = "Connection stability dropped to ${stability.roundToInt()}%"
    )

    data class InsufficientThroughput(val downloadKbps: Float) : NetworkAlert(
        severity = Severity.INFO,
        message = "Download throughput ${downloadKbps.roundToInt()} KB/s is below expected"
    )
}
