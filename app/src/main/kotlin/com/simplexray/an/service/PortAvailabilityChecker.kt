package com.simplexray.an.service

import com.simplexray.an.common.AppLogger
import java.net.ServerSocket

/**
 * Manages port availability checking with caching to reduce repeated scans.
 * Thread-safe implementation for concurrent access.
 */
class PortAvailabilityChecker(
    private val portRangeStart: Int = 10000,
    private val portRangeEnd: Int = 65535,
    private val portCacheValidityMs: Long = 60000L // 1 minute cache
) {
    // Cache port availability to reduce repeated checks
    @Volatile
    private var cachedAvailablePort: Int? = null
    
    @Volatile
    private var portCacheTime = 0L
    
    /**
     * Find an available port, excluding the provided set of ports.
     * Uses caching to avoid repeated scans.
     * 
     * @param excludedPorts Set of ports to exclude from selection
     * @return Available port number, or null if no port could be found
     */
    @Synchronized
    fun findAvailablePort(excludedPorts: Set<Int>): Int? {
        // Check cache first (thread-safe)
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (cachedAvailablePort != null && (now - portCacheTime) < portCacheValidityMs) {
                val cached = cachedAvailablePort!!
                if (cached !in excludedPorts) {
                    // Verify cached port is still available (with timeout)
                    runCatching {
                        val socket = ServerSocket()
                        try {
                            socket.reuseAddress = true
                            socket.bind(java.net.InetSocketAddress(cached), 1)
                            socket.close()
                            return cached
                        } catch (e: Exception) {
                            socket.close()
                            throw e
                        }
                    }
                }
            }
        }
        
        // Scan ports with timeout (limit attempts for performance)
        val portsToTry = (portRangeStart..portRangeEnd).shuffled().take(100)
        for (port in portsToTry) {
            if (port in excludedPorts) continue
            
            runCatching {
                val socket = ServerSocket()
                try {
                    socket.reuseAddress = true
                    socket.bind(java.net.InetSocketAddress(port), 1)
                    socket.close()
                    // Cache successful port (thread-safe)
                    synchronized(this) {
                        cachedAvailablePort = port
                        portCacheTime = System.currentTimeMillis()
                    }
                    return port
                } finally {
                    if (!socket.isClosed) {
                        socket.close()
                    }
                }
            }.onFailure {
                AppLogger.d("Port $port unavailable: ${it.message}")
            }
        }
        
        // Fallback: try system-assigned port
        return try {
            ServerSocket(0).use { socket ->
                val fallbackPort = socket.localPort
                synchronized(this) {
                    cachedAvailablePort = fallbackPort
                    portCacheTime = now
                }
                fallbackPort
            }
        } catch (e: Exception) {
            AppLogger.e("Failed to find any available port: ${e.message}", e)
            null
        }
    }
    
    /**
     * Clear the cached port to force a fresh scan on next call.
     */
    fun clearCache() {
        synchronized(this) {
            cachedAvailablePort = null
            portCacheTime = 0L
        }
    }
}







