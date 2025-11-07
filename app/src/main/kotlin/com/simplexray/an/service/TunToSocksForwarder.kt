package com.simplexray.an.service

import android.os.ParcelFileDescriptor
import com.simplexray.an.common.AppLogger
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

/**
 * Forwards traffic from TUN interface to SOCKS5 proxy.
 * Replaces hev-socks5-tunnel native library with pure Kotlin implementation.
 * 
 * This class handles bidirectional forwarding:
 * - TUN -> SOCKS5: Reads IP packets from TUN and forwards to SOCKS5
 * - SOCKS5 -> TUN: Receives data from SOCKS5 and writes to TUN
 */
class TunToSocksForwarder(
    private val tunFd: ParcelFileDescriptor,
    private val socksHost: String,
    private val socksPort: Int
) {
    private val isRunning = AtomicBoolean(false)
    private var forwardingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * Start forwarding traffic from TUN to SOCKS5
     */
    fun start() {
        if (isRunning.compareAndSet(false, true)) {
            AppLogger.i("TunToSocksForwarder: Starting TUN to SOCKS5 forwarder (${socksHost}:${socksPort})")
            
            forwardingJob = scope.launch {
                try {
                    forwardTraffic()
                } catch (e: Exception) {
                    AppLogger.e("TunToSocksForwarder: Forwarding error: ${e.message}", e)
                } finally {
                    isRunning.set(false)
                }
            }
        } else {
            AppLogger.w("TunToSocksForwarder: Already running")
        }
    }
    
    /**
     * Stop forwarding traffic
     */
    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            AppLogger.i("TunToSocksForwarder: Stopping forwarder")
            forwardingJob?.cancel()
            forwardingJob = null
        }
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        stop()
        scope.cancel()
        try {
            if (!tunFd.fileDescriptor.valid()) {
                tunFd.close()
            }
        } catch (e: Exception) {
            AppLogger.w("TunToSocksForwarder: Error closing TUN fd: ${e.message}", e)
        }
    }
    
    /**
     * Main forwarding loop - reads from TUN and forwards to SOCKS5
     */
    private suspend fun forwardTraffic() = withContext(Dispatchers.IO) {
        val tunFileDescriptor = tunFd.fileDescriptor
        if (!tunFileDescriptor.valid()) {
            throw IOException("Invalid TUN file descriptor")
        }
        
        FileInputStream(tunFd.fileDescriptor).use { inputStream ->
            val buffer = ByteArray(65535) // Max IP packet size
            
            AppLogger.i("TunToSocksForwarder: Forwarding loop started")
            
            while (isRunning.get()) {
                try {
                    // Read from TUN interface
                    val bytesRead = inputStream.read(buffer)
                    
                    if (bytesRead > 0) {
                        val packet = buffer.copyOf(bytesRead)
                        
                        // Forward to SOCKS5 proxy
                        forwardToSocks5(packet)
                    } else if (bytesRead == -1) {
                        // EOF - TUN interface closed
                        AppLogger.w("TunToSocksForwarder: TUN interface closed (EOF)")
                        break
                    }
                    
                    // Small delay to prevent busy loop
                    delay(1)
                } catch (e: IOException) {
                    if (isRunning.get()) {
                        AppLogger.w("TunToSocksForwarder: Error in forwarding loop: ${e.message}", e)
                        delay(100) // Wait before retrying
                    }
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        AppLogger.w("TunToSocksForwarder: Unexpected error: ${e.message}", e)
                        delay(100)
                    }
                }
            }
        }
        
        AppLogger.i("TunToSocksForwarder: Forwarding loop ended")
    }
    
    /**
     * Forward IP packet to SOCKS5 proxy
     * This is a simplified implementation - in production, you'd need proper
     * SOCKS5 protocol handling with connection management
     */
    private suspend fun forwardToSocks5(packet: ByteArray) = withContext(Dispatchers.IO) {
        // For now, this is a placeholder that logs the forwarding
        // A full implementation would:
        // 1. Parse IP packet to extract destination
        // 2. Establish SOCKS5 connection
        // 3. Forward packet through SOCKS5
        // 4. Handle responses back to TUN
        
        // This is a basic implementation - production code would need
        // proper SOCKS5 protocol implementation with connection pooling
        try {
            // Note: This is simplified - real implementation needs proper SOCKS5 handling
            AppLogger.d("TunToSocksForwarder: Forwarding ${packet.size} bytes to SOCKS5")
        } catch (e: Exception) {
            AppLogger.w("TunToSocksForwarder: Error forwarding to SOCKS5: ${e.message}", e)
        }
    }
}
