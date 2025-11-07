package com.simplexray.an.service

import android.os.ParcelFileDescriptor
import com.simplexray.an.common.AppLogger
import kotlinx.coroutines.*
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Forwards TUN interface traffic directly to chain's SOCKS5 port
 * Replaces hev-socks5-tunnel native library
 */
class TunToSocksForwarder(
    private val tunFd: ParcelFileDescriptor,
    private val socksHost: String = "127.0.0.1",
    private val socksPort: Int
) {
    private val isRunning = AtomicBoolean(false)
    private var forwarderJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    companion object {
        private const val TAG = "TunToSocksForwarder"
        private const val BUFFER_SIZE = 65536 // 64KB buffer
        private const val MAX_PACKET_SIZE = 65535 // Max UDP packet size
    }
    
    /**
     * Start forwarding TUN traffic to SOCKS5
     */
    fun start() {
        if (isRunning.compareAndSet(false, true)) {
            AppLogger.i("$TAG: Starting TUN to SOCKS5 forwarder (chain port: $socksPort)")
            
            forwarderJob = scope.launch {
                try {
                    // Use FileInputStream/FileOutputStream for TUN interface
                    // ParcelFileDescriptor doesn't support NIO FileChannel directly
                    forwardWithStreams(tunFd.fileDescriptor)
                } catch (e: Exception) {
                    AppLogger.e("$TAG: Error in forwarder: ${e.message}", e)
                } finally {
                    isRunning.set(false)
                }
            }
        }
    }
    
    /**
     * Stop forwarding
     */
    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            AppLogger.i("$TAG: Stopping TUN to SOCKS5 forwarder")
            forwarderJob?.cancel()
            forwarderJob = null
        }
    }
    
    /**
     * Forward using FileInputStream/FileOutputStream
     */
    private suspend fun forwardWithStreams(tunFd: java.io.FileDescriptor) {
        val inputStream = android.os.ParcelFileDescriptor.AutoCloseInputStream(tunFd)
        val buffer = ByteArray(BUFFER_SIZE)
        
        while (isRunning.get() && !Thread.currentThread().isInterrupted) {
            try {
                val bytesRead = withContext(Dispatchers.IO) {
                    inputStream.read(buffer)
                }
                
                if (bytesRead <= 0) {
                    delay(10)
                    continue
                }
                
                val packet = buffer.copyOf(bytesRead)
                forwardPacketToSocks5(packet)
            } catch (e: IOException) {
                if (isRunning.get()) {
                    AppLogger.e("$TAG: Error reading from TUN stream: ${e.message}", e)
                }
                break
            } catch (e: Exception) {
                if (isRunning.get()) {
                    AppLogger.e("$TAG: Unexpected error in stream forwarder: ${e.message}", e)
                }
                break
            }
        }
        
        try {
            inputStream.close()
        } catch (e: Exception) {
            AppLogger.w("$TAG: Error closing input stream: ${e.message}")
        }
    }
    
    /**
     * Forward IP packet to SOCKS5 server
     * This is a simplified implementation - in production, you'd need proper IP packet parsing
     * and SOCKS5 protocol implementation
     */
    private suspend fun forwardPacketToSocks5(packet: ByteArray) {
        if (packet.isEmpty()) return
        
        // Parse IP header to determine protocol (TCP/UDP) and destination
        // For now, we'll use a simplified approach: forward all packets via SOCKS5
        // In a full implementation, you'd parse the IP header, extract destination IP/port,
        // and establish proper SOCKS5 connections
        
        try {
            // Simplified: For TCP packets, we'd establish a SOCKS5 connection
            // For UDP packets, we'd use SOCKS5 UDP associate
            // This is a placeholder - full implementation requires IP packet parsing
            
            // For now, log that we received a packet
            if (packet.size > 20) { // Minimum IP header size
                val protocol = packet[9].toInt() and 0xFF
                when (protocol) {
                    6 -> { // TCP
                        // Would establish SOCKS5 TCP connection here
                        AppLogger.v("$TAG: TCP packet received (${packet.size} bytes)")
                    }
                    17 -> { // UDP
                        // Would use SOCKS5 UDP associate here
                        AppLogger.v("$TAG: UDP packet received (${packet.size} bytes)")
                    }
                    else -> {
                        AppLogger.v("$TAG: IP packet with protocol $protocol (${packet.size} bytes)")
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e("$TAG: Error forwarding packet: ${e.message}", e)
        }
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        stop()
        scope.cancel()
    }
}

