package com.simplexray.an.xray

import android.net.VpnService
import android.util.Log
import com.simplexray.an.common.AppLogger
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Test utility to verify VpnService.protect() binding works correctly
 * Tests if sockets are properly protected from VPN routing
 */
object VpnProtectTester {
    private const val TAG = "VpnProtectTester"
    
    /**
     * Test if VpnService.protect() is working correctly
     * Creates a test socket and verifies it can be protected
     * 
     * @param vpnService VpnService instance to use for protection
     * @return true if protect() works, false otherwise
     */
    fun testProtectBinding(vpnService: VpnService?): Boolean {
        if (vpnService == null) {
            AppLogger.e("$TAG: VpnService is null, cannot test protect()")
            return false
        }
        
        var testSocket: Socket? = null
        return try {
            // Create a test socket
            testSocket = Socket()
            testSocket.connect(InetSocketAddress("8.8.8.8", 53), 5000) // Google DNS
            val fd = testSocket.getFileDescriptor()?.fd ?: -1
            
            if (fd < 0) {
                AppLogger.e("$TAG: Failed to get file descriptor from test socket")
                return false
            }
            
            AppLogger.d("$TAG: Testing protect() on fd=$fd")
            
            // Test protect() call
            val protected = vpnService.protect(fd)
            
            if (protected) {
                AppLogger.i("$TAG: protect() test PASSED - socket fd=$fd is protected")
                Log.i(TAG, "VpnService.protect(fd=$fd) returned true - binding works correctly")
            } else {
                AppLogger.w("$TAG: protect() test FAILED - socket fd=$fd protection returned false")
                Log.w(TAG, "VpnService.protect(fd=$fd) returned false - binding may not work")
            }
            
            // Verify socket is still valid after protect() call
            if (testSocket.isConnected && !testSocket.isClosed) {
                AppLogger.d("$TAG: Socket remains valid after protect() call")
            } else {
                AppLogger.w("$TAG: Socket became invalid after protect() call")
            }
            
            protected
        } catch (e: Exception) {
            AppLogger.e("$TAG: Exception during protect() test: ${e.message}", e)
            Log.e(TAG, "protect() test exception: ${e.message}", e)
            false
        } finally {
            try {
                testSocket?.close()
            } catch (e: Exception) {
                AppLogger.w("$TAG: Error closing test socket: ${e.message}")
            }
        }
    }
    
    /**
     * Test protect() with multiple file descriptors
     * Useful for verifying protect() works across different socket types
     */
    fun testMultipleProtect(vpnService: VpnService?, count: Int = 5): Int {
        if (vpnService == null) {
            AppLogger.e("$TAG: VpnService is null, cannot test protect()")
            return 0
        }
        
        var successCount = 0
        val sockets = mutableListOf<Socket>()
        
        try {
            for (i in 1..count) {
                try {
                    val socket = Socket()
                    socket.connect(InetSocketAddress("8.8.8.8", 53), 5000)
                    sockets.add(socket)
                    
                    val fd = socket.getFileDescriptor()?.fd ?: -1
                    if (fd >= 0) {
                        val protected = vpnService.protect(fd)
                        if (protected) {
                            successCount++
                            AppLogger.d("$TAG: protect() test $i/$count PASSED (fd=$fd)")
                        } else {
                            AppLogger.w("$TAG: protect() test $i/$count FAILED (fd=$fd)")
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.w("$TAG: protect() test $i/$count exception: ${e.message}")
                }
            }
            
            AppLogger.i("$TAG: Multiple protect() test completed: $successCount/$count successful")
        } finally {
            sockets.forEach { socket ->
                try {
                    socket.close()
                } catch (e: Exception) {
                    // Ignore close errors
                }
            }
        }
        
        return successCount
    }
}

