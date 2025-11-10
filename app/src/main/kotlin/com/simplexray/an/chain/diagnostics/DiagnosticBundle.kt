package com.simplexray.an.chain.diagnostics

import android.content.Context
import android.os.Build
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.simplexray.an.BuildConfig
import com.simplexray.an.common.AppLogger
import com.simplexray.an.chain.supervisor.ChainSupervisor
import com.simplexray.an.chain.tls.TlsModeDetector
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Generates sanitized diagnostic bundles (no secrets)
 */
class DiagnosticBundle(private val context: Context) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    
    /**
     * Generate diagnostic bundle
     */
    suspend fun generateBundle(
        includeLogs: Boolean = true,
        includeHealthChecks: Boolean = true
    ): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val bundleFile = File(context.getExternalFilesDir(null), "diagnostic_bundle_$timestamp.json")
        
        try {
            val bundle = DiagnosticData(
                timestamp = System.currentTimeMillis(),
                appVersion = BuildConfig.VERSION_NAME,
                appVersionCode = BuildConfig.VERSION_CODE,
                androidVersion = Build.VERSION.SDK_INT,
                deviceModel = Build.MODEL,
                deviceManufacturer = Build.MANUFACTURER,
                abi = Build.SUPPORTED_ABIS.joinToString(", "),
                chainStatus = getChainStatus(),
                tlsInfo = getTlsInfo(),
                healthChecks = if (includeHealthChecks) {
                    ChainHealthChecker.runAllChecks(context).checks
                } else {
                    null
                },
                recentLogs = if (includeLogs) {
                    ChainLogger(context).getRecentLogs(200)
                } else {
                    null
                },
                systemInfo = getSystemInfo()
            )
            
            bundleFile.writeText(gson.toJson(bundle))
            AppLogger.i("DiagnosticBundle: Generated bundle at ${bundleFile.absolutePath}")
            return bundleFile
        } catch (e: Exception) {
            AppLogger.e("DiagnosticBundle: Failed to generate bundle", e)
            throw e
        }
    }
    
    /**
     * Get chain status (sanitized)
     */
    private fun getChainStatus(): Map<String, Any?> {
        return try {
            // Note: In a real implementation, we'd get this from ChainSupervisor
            // For now, return basic info
            mapOf(
                "state" to "unknown", // Would get from ChainSupervisor
                "layers" to mapOf(
                    "reality" to mapOf("running" to false),
                    "pepper" to mapOf("running" to false),
                    "xray" to mapOf("running" to false)
                )
            )
        } catch (e: Exception) {
            mapOf("error" to (e.message ?: "Unknown"))
        }
    }
    
    /**
     * Get TLS info (sanitized)
     */
    private fun getTlsInfo(): Map<String, String> {
        return try {
            val available = TlsModeDetector.detectAvailableModes(context)
            val recommended = TlsModeDetector.getRecommendedMode(context)
            val info = TlsModeDetector.getTlsInfo(context, recommended)
            
            mapOf(
                "availableModes" to available.joinToString(", "),
                "recommendedMode" to recommended.name,
                "implementation" to info.implementation,
                "version" to info.version,
                "keyExchange" to info.keyExchange
            )
        } catch (e: Exception) {
            mapOf("error" to (e.message ?: "Unknown"))
        }
    }
    
    /**
     * Get system info
     */
    private fun getSystemInfo(): Map<String, Any> {
        return mapOf<String, Any>(
            "androidVersion" to Build.VERSION.SDK_INT,
            "androidRelease" to Build.VERSION.RELEASE,
            "deviceModel" to Build.MODEL,
            "deviceManufacturer" to Build.MANUFACTURER,
            "abi" to Build.SUPPORTED_ABIS.joinToString(", "),
            "cpuAbi" to (Build.SUPPORTED_ABIS.getOrNull(0) ?: "N/A"),
            "cpuAbi2" to (Build.SUPPORTED_ABIS.getOrNull(1) ?: "N/A")
        )
    }
}

data class DiagnosticData(
    val timestamp: Long,
    val appVersion: String,
    val appVersionCode: Int,
    val androidVersion: Int,
    val deviceModel: String,
    val deviceManufacturer: String,
    val abi: String,
    val chainStatus: Map<String, Any?>,
    val tlsInfo: Map<String, String>,
    val healthChecks: List<SingleCheckResult>?,
    val recentLogs: String?,
    val systemInfo: Map<String, Any>
)

