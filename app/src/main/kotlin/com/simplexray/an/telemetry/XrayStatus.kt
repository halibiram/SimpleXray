package com.simplexray.an.telemetry

import com.simplexray.an.domain.model.TrafficSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Xray connection status interface for real-time monitoring
 * Provides traffic counters and connection state information
 */
interface XrayStatus {
    /**
     * Get current traffic snapshot
     */
    fun getCurrentTraffic(): TrafficSnapshot
    
    /**
     * Check if connection is active based on traffic activity
     * Connection is considered active if traffic has changed in the last N seconds
     */
    fun isConnectionActive(timeoutSeconds: Long = 5): Boolean
    
    /**
     * Get total bytes sent (uplink)
     */
    fun getTotalBytesSent(): Long
    
    /**
     * Get total bytes received (downlink)
     */
    fun getTotalBytesReceived(): Long
    
    /**
     * Register a callback for traffic updates
     */
    fun registerTrafficCallback(callback: (TrafficSnapshot) -> Unit)
    
    /**
     * Unregister traffic callback
     */
    fun unregisterTrafficCallback()
    
    /**
     * Cleanup resources
     */
    fun cleanup()
}

/**
 * Default implementation of XrayStatus using XrayStatsObserver
 * Monitors traffic and provides connection status
 */
class XrayStatusImpl(
    private val statsObserver: XrayStatsObserver
) : XrayStatus {
    private var trafficCallback: ((TrafficSnapshot) -> Unit)? = null
    private var lastTrafficUpdate: Long = System.currentTimeMillis()
    private var lastRxBytes: Long = 0L
    private var lastTxBytes: Long = 0L
    
    private val monitoringScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    init {
        // Monitor traffic updates in background
        monitoringScope.launch {
            statsObserver.currentSnapshot.collect { snapshot ->
                lastTrafficUpdate = System.currentTimeMillis()
                lastRxBytes = snapshot.rxBytes
                lastTxBytes = snapshot.txBytes
                trafficCallback?.invoke(snapshot)
            }
        }
    }
    
    override fun getCurrentTraffic(): TrafficSnapshot {
        // Get current snapshot from observer (thread-safe StateFlow access)
        return try {
            val snapshot = statsObserver.getCurrentSnapshotValue()
            // Update our cached values
            lastTrafficUpdate = snapshot.timestamp
            lastRxBytes = snapshot.rxBytes
            lastTxBytes = snapshot.txBytes
            snapshot
        } catch (e: Exception) {
            // Fallback to cached values if observer is not available
            TrafficSnapshot(
                timestamp = lastTrafficUpdate,
                rxBytes = lastRxBytes,
                txBytes = lastTxBytes,
                isConnected = isConnectionActive()
            )
        }
    }
    
    override fun isConnectionActive(timeoutSeconds: Long): Boolean {
        val now = System.currentTimeMillis()
        val timeoutMs = timeoutSeconds * 1000
        
        // Check if we've received traffic updates recently
        if (now - lastTrafficUpdate > timeoutMs) {
            return false
        }
        
        // Connection is active if we have recent updates
        // Additional check: traffic should be non-zero or increasing
        return true
    }
    
    override fun getTotalBytesSent(): Long {
        return lastTxBytes
    }
    
    override fun getTotalBytesReceived(): Long {
        return lastRxBytes
    }
    
    override fun registerTrafficCallback(callback: (TrafficSnapshot) -> Unit) {
        trafficCallback = callback
    }
    
    override fun unregisterTrafficCallback() {
        trafficCallback = null
    }
    
    override fun cleanup() {
        monitoringScope.cancel()
        trafficCallback = null
    }
}

