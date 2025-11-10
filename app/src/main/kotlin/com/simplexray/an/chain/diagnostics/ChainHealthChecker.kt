package com.simplexray.an.chain.diagnostics

import android.content.Context
import com.simplexray.an.common.AppLogger
import com.simplexray.an.chain.reality.RealitySocks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit

/**
 * Health checks for the tunneling chain
 */
object ChainHealthChecker {
    
    /**
     * Run all health checks
     */
    suspend fun runAllChecks(context: Context): HealthCheckResult = withContext(Dispatchers.IO) {
        val checks = mutableListOf<SingleCheckResult>()
        
        // 1. Check local SOCKS5 server (Reality SOCKS)
        checks.add(checkLocalSocks())
        
        // 2. Check egress IP
        checks.add(checkEgressIp())
        
        // 3. Check Xray-core process
        checks.add(checkXrayProcess())
        
        val allPassed = checks.all { it.passed }
        val criticalFailed = checks.any { !it.passed && it.critical }
        
        HealthCheckResult(
            allPassed = allPassed,
            criticalFailed = criticalFailed,
            checks = checks,
            timestamp = System.currentTimeMillis()
        )
    }
    
    /**
     * Check if local SOCKS5 server is accepting connections
     */
    private suspend fun checkLocalSocks(): SingleCheckResult = withContext(Dispatchers.IO) {
        return@withContext try {
            val status = RealitySocks.getStatus()
            if (!status.isRunning) {
                SingleCheckResult(
                    name = "Local SOCKS5 Server",
                    passed = false,
                    critical = true,
                    message = "SOCKS5 server is not running",
                    details = null
                )
            } else {
                val localAddr = RealitySocks.getLocalAddress()
                if (localAddr != null) {
                    // Try to connect to SOCKS5 port
                    val socket = Socket()
                    try {
                        socket.connect(localAddr, 2000) // 2 second timeout
                        socket.close()
                        SingleCheckResult(
                            name = "Local SOCKS5 Server",
                            passed = true,
                            critical = true,
                            message = "SOCKS5 server is accepting connections on ${localAddr.port}",
                            details = mapOf("port" to localAddr.port.toString())
                        )
                    } catch (e: Exception) {
                        SingleCheckResult(
                            name = "Local SOCKS5 Server",
                            passed = false,
                            critical = true,
                            message = "Cannot connect to SOCKS5 server: ${e.message}",
                            details = mapOf<String, String>("error" to (e.message ?: "Unknown"))
                        )
                    }
                } else {
                    SingleCheckResult(
                        name = "Local SOCKS5 Server",
                        passed = false,
                        critical = true,
                        message = "SOCKS5 server address is null",
                        details = null
                    )
                }
            }
        } catch (e: Exception) {
            SingleCheckResult(
                name = "Local SOCKS5 Server",
                passed = false,
                critical = true,
                message = "Error checking SOCKS5 server: ${e.message}",
                details = mapOf<String, String>("error" to (e.message ?: "Unknown"))
            )
        }
    }
    
    /**
     * Check egress IP (verify traffic is going through proxy)
     */
    private suspend fun checkEgressIp(): SingleCheckResult = withContext(Dispatchers.IO) {
        return@withContext try {
            // Try multiple IP check services
            val ipServices = listOf(
                "https://api.ipify.org",
                "https://icanhazip.com",
                "https://ifconfig.me"
            )
            
            var ip: String? = null
            var error: String? = null
            
            for (serviceUrl in ipServices) {
                try {
                    val url = URL(serviceUrl)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    connection.requestMethod = "GET"
                    
                    val responseCode = connection.responseCode
                    if (responseCode == 200) {
                        ip = connection.inputStream.bufferedReader().readText().trim()
                        connection.disconnect()
                        break
                    } else {
                        connection.disconnect()
                    }
                } catch (e: Exception) {
                    error = e.message
                    continue
                }
            }
            
            if (ip != null) {
                SingleCheckResult(
                    name = "Egress IP Check",
                    passed = true,
                    critical = false,
                    message = "Egress IP: $ip",
                    details = mapOf("ip" to ip)
                )
            } else {
                SingleCheckResult(
                    name = "Egress IP Check",
                    passed = false,
                    critical = false,
                    message = "Failed to get egress IP: ${error ?: "All services failed"}",
                    details = mapOf("error" to (error ?: "Unknown"))
                )
            }
        } catch (e: Exception) {
            SingleCheckResult(
                name = "Egress IP Check",
                passed = false,
                critical = false,
                message = "Error checking egress IP: ${e.message}",
                details = mapOf<String, String>("error" to (e.message ?: "Unknown"))
            )
        }
    }
    
    /**
     * Check Xray-core process
     */
    private suspend fun checkXrayProcess(): SingleCheckResult = withContext(Dispatchers.IO) {
        return@withContext try {
            val isRunning = com.simplexray.an.xray.XrayCoreLauncher.isRunning()
            SingleCheckResult(
                name = "Xray-core Process",
                passed = isRunning,
                critical = true,
                message = if (isRunning) {
                    "Xray-core process is running"
                } else {
                    "Xray-core process is not running"
                },
                details = mapOf("running" to isRunning.toString())
            )
        } catch (e: Exception) {
            SingleCheckResult(
                name = "Xray-core Process",
                passed = false,
                critical = true,
                message = "Error checking Xray process: ${e.message}",
                details = mapOf<String, String>("error" to (e.message ?: "Unknown"))
            )
        }
    }
}

data class HealthCheckResult(
    val allPassed: Boolean,
    val criticalFailed: Boolean,
    val checks: List<SingleCheckResult>,
    val timestamp: Long
)

data class SingleCheckResult(
    val name: String,
    val passed: Boolean,
    val critical: Boolean,
    val message: String,
    val details: Map<String, String>?
)

