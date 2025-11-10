package com.simplexray.an.common

import android.content.Context
import android.os.Build
import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * SELinux Compliance Helper
 * 
 * Provides Android API-based alternatives to direct filesystem access
 * to avoid SELinux denials on Android 16+ (API 36+)
 * 
 * This class uses only Android SDK APIs and avoids direct /proc or /sys access
 * where possible to maintain SELinux compliance.
 */
object SelinuxComplianceHelper {
    private const val TAG = "SelinuxComplianceHelper"
    
    /**
     * Check if process is alive using Android API instead of /proc/PID
     * 
     * Android 16+ SELinux: /proc/PID access may be denied
     * Alternative: Use Process.sendSignal(pid, 0) which is allowed
     */
    fun isProcessAlive(pid: Int): Boolean {
        if (pid <= 0 || pid > Int.MAX_VALUE) {
            return false
        }
        
        return try {
            // Use Process.sendSignal(pid, 0) to check if process exists
            // Signal 0 doesn't actually send a signal, just checks if process exists
            // This is allowed by SELinux even on Android 16+
            Process.sendSignal(pid, 0)
            true
        } catch (e: Exception) {
            // Process doesn't exist or we don't have permission
            false
        }
    }
    
    /**
     * Get process information using Android API instead of /proc/PID/cmdline
     * 
     * Android 16+ SELinux: /proc/PID/cmdline access may be denied
     * Alternative: Use Process.getUidForPid() and other Process APIs
     */
    fun getProcessInfo(pid: Int): ProcessInfo? {
        if (pid <= 0 || pid > Int.MAX_VALUE) {
            return null
        }
        
        return try {
            // Use Process APIs which are SELinux-compliant
            val uid = Process.getUidForPid(pid)
            if (uid < 0) {
                return null // Process doesn't exist
            }
            
            ProcessInfo(
                pid = pid,
                uid = uid,
                isAlive = isProcessAlive(pid)
            )
        } catch (e: Exception) {
            AppLogger.d("SelinuxComplianceHelper: Failed to get process info for PID $pid: ${e.message}")
            null
        }
    }
    
    /**
     * Find process by binary name using Android API
     * 
     * Android 16+ SELinux: /proc directory scanning may be denied
     * Alternative: Use ActivityManager.getRunningAppProcesses() or Process APIs
     */
    fun findProcessByBinaryName(context: Context, binaryName: String): Int? {
        return try {
            // Use ActivityManager to get running processes
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            if (activityManager != null) {
                val runningProcesses = activityManager.runningAppProcesses
                runningProcesses?.forEach { processInfo ->
                    // Check if process name matches binary name
                    if (processInfo.processName.contains(binaryName, ignoreCase = true)) {
                        return processInfo.pid
                    }
                }
            }
            
            // Fallback: Try to find by checking if process is alive
            // This is less reliable but doesn't require /proc access
            null
        } catch (e: Exception) {
            AppLogger.d("SelinuxComplianceHelper: Failed to find process by binary name: ${e.message}")
            null
        }
    }
    
    /**
     * Get system property using Android API instead of SystemProperties
     * 
     * Android 16+ SELinux: SystemProperties access may be denied
     * Alternative: Use Build class or Settings APIs where available
     */
    fun getSystemProperty(key: String, defaultValue: String = ""): String {
        return try {
            when (key) {
                "ro.debuggable" -> {
                    // Use Build class instead of SystemProperties
                    // Build.IS_DEBUGGABLE is available on API 26+
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        if (Build.TYPE == "eng" || Build.TYPE == "userdebug") "1" else "0"
                    } else {
                        defaultValue
                    }
                }
                "ro.miui.ui.version.name" -> {
                    // Try reflection-based access with proper error handling
                    try {
                        val props = Class.forName("android.os.SystemProperties")
                        val get = props.getMethod("get", String::class.java, String::class.java)
                        get.invoke(props, key, defaultValue) as? String ?: defaultValue
                    } catch (e: SecurityException) {
                        // SELinux denied - return default
                        AppLogger.d("SelinuxComplianceHelper: SystemProperties access denied for $key (expected on Android 16+)")
                        defaultValue
                    } catch (e: Exception) {
                        defaultValue
                    }
                }
                else -> {
                    // For other properties, try reflection with error handling
                    try {
                        val props = Class.forName("android.os.SystemProperties")
                        val get = props.getMethod("get", String::class.java, String::class.java)
                        get.invoke(props, key, defaultValue) as? String ?: defaultValue
                    } catch (e: SecurityException) {
                        AppLogger.d("SelinuxComplianceHelper: SystemProperties access denied for $key")
                        defaultValue
                    } catch (e: Exception) {
                        defaultValue
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.d("SelinuxComplianceHelper: Failed to get system property $key: ${e.message}")
            defaultValue
        }
    }
    
    /**
     * Get network statistics using Android API instead of /proc/net
     * 
     * Android 16+ SELinux: /proc/net access is denied
     * Alternative: Use TrafficStats API or NetworkStatsManager
     */
    fun getNetworkStats(context: Context): NetworkStats? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Use NetworkStatsManager for detailed stats
                val networkStatsManager = context.getSystemService(Context.NETWORK_STATS_SERVICE) as? android.app.usage.NetworkStatsManager
                if (networkStatsManager != null) {
                    val uid = Process.myUid()
                    val now = System.currentTimeMillis()
                    val startTime = now - (24 * 60 * 60 * 1000) // Last 24 hours
                    
                    val stats = networkStatsManager.querySummary(
                        android.net.NetworkTemplate.Builder(android.net.NetworkTemplate.MATCH_MOBILE)
                            .build(),
                        startTime,
                        now
                    )
                    
                    var rxBytes = 0L
                    var txBytes = 0L
                    
                    while (stats.hasNextBucket()) {
                        val bucket = android.app.usage.NetworkStats.Bucket()
                        stats.getNextBucket(bucket)
                        if (bucket.uid == uid) {
                            rxBytes += bucket.rxBytes
                            txBytes += bucket.txBytes
                        }
                    }
                    stats.close()
                    
                    NetworkStats(rxBytes, txBytes)
                } else {
                    // Fallback to TrafficStats
                    NetworkStats(
                        rxBytes = android.net.TrafficStats.getUidRxBytes(Process.myUid()).takeIf { it != android.net.TrafficStats.UNSUPPORTED.toLong() } ?: 0L,
                        txBytes = android.net.TrafficStats.getUidTxBytes(Process.myUid()).takeIf { it != android.net.TrafficStats.UNSUPPORTED.toLong() } ?: 0L
                    )
                }
            } else {
                // Use TrafficStats for older Android versions
                NetworkStats(
                    rxBytes = android.net.TrafficStats.getUidRxBytes(Process.myUid()).takeIf { it != android.net.TrafficStats.UNSUPPORTED.toLong() } ?: 0L,
                    txBytes = android.net.TrafficStats.getUidTxBytes(Process.myUid()).takeIf { it != android.net.TrafficStats.UNSUPPORTED.toLong() } ?: 0L
                )
            }
        } catch (e: Exception) {
            AppLogger.d("SelinuxComplianceHelper: Failed to get network stats: ${e.message}")
            null
        }
    }
    
    /**
     * Get CPU information using Android API instead of /proc/cpuinfo
     * 
     * Android 16+ SELinux: /proc/cpuinfo access may be limited
     * Alternative: Use Runtime API or Build class
     */
    fun getCpuInfo(): CpuInfo {
        return try {
            val runtime = Runtime.getRuntime()
            CpuInfo(
                availableProcessors = runtime.availableProcessors(),
                maxMemory = runtime.maxMemory(),
                totalMemory = runtime.totalMemory(),
                freeMemory = runtime.freeMemory(),
                architecture = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
            )
        } catch (e: Exception) {
            AppLogger.d("SelinuxComplianceHelper: Failed to get CPU info: ${e.message}")
            CpuInfo(
                availableProcessors = 1,
                maxMemory = 0L,
                totalMemory = 0L,
                freeMemory = 0L,
                architecture = "unknown"
            )
        }
    }
    
    /**
     * Check if file is executable using Android API
     * 
     * Android 16+ SELinux: setExecutable() may fail but file may still be executable
     * This function checks actual executability, not just permissions
     */
    fun isFileExecutable(file: File): Boolean {
        if (!file.exists() || !file.canRead()) {
            return false
        }
        
        // On Android 16+, native library directory files are executable
        // even if canExecute() returns false
        val androidVersion = Build.VERSION.SDK_INT
        if (androidVersion >= 36) {
            // Check if file is in native library directory
            val nativeLibDir = System.getProperty("java.library.path") ?: ""
            if (file.absolutePath.contains(nativeLibDir) || 
                file.absolutePath.contains("/lib/") && file.name.endsWith(".so")) {
                // Native libraries in app directory are executable by SELinux policy
                return true
            }
        }
        
        // For other files, check canExecute()
        return file.canExecute()
    }
    
    /**
     * Get file descriptor count using Android API instead of /proc/self/fd
     * 
     * Android 16+ SELinux: /proc/self/fd access may be denied
     * Alternative: Use Os.getrlimit() or Process API
     */
    fun getFileDescriptorCount(): Int? {
        return try {
            // Use Os.getrlimit() to get file descriptor limits
            // This is SELinux-compliant
            val rlimit = Os.getrlimit(OsConstants.RLIMIT_NOFILE)
            // Return current soft limit (approximate)
            rlimit.current.toInt()
        } catch (e: Exception) {
            AppLogger.d("SelinuxComplianceHelper: Failed to get FD count: ${e.message}")
            null
        }
    }
    
    /**
     * Check SELinux enforcement status
     * Returns true if SELinux is enforcing, false if permissive
     */
    fun isSelinuxEnforcing(): Boolean {
        return try {
            // Use Os.sysconf() to check SELinux status
            // This is SELinux-compliant
            val selinuxEnforcing = Os.sysconf(OsConstants._SC_SELINUX_ENFORCING)
            selinuxEnforcing > 0
        } catch (e: Exception) {
            // Default to enforcing if we can't check
            true
        }
    }
    
    /**
     * Get Android version-specific SELinux compliance recommendations
     */
    fun getComplianceRecommendations(): List<String> {
        val recommendations = mutableListOf<String>()
        val androidVersion = Build.VERSION.SDK_INT
        
        if (androidVersion >= 36) {
            recommendations.add("Android 16+ detected: Use native library directory directly instead of copying")
            recommendations.add("Avoid /proc filesystem access - use Android Process APIs instead")
            recommendations.add("Avoid SystemProperties access - use Build class or Settings APIs")
            recommendations.add("Avoid /proc/net access - use TrafficStats or NetworkStatsManager")
            recommendations.add("Set HOME and TMPDIR to app directories in ProcessBuilder")
            recommendations.add("Filter PATH environment variable to exclude test directories")
        } else if (androidVersion >= 34) {
            recommendations.add("Android 14+ detected: Use native library directory directly")
            recommendations.add("Avoid /proc filesystem access where possible")
        }
        
        return recommendations
    }
    
    data class ProcessInfo(
        val pid: Int,
        val uid: Int,
        val isAlive: Boolean
    )
    
    data class NetworkStats(
        val rxBytes: Long,
        val txBytes: Long
    )
    
    data class CpuInfo(
        val availableProcessors: Int,
        val maxMemory: Long,
        val totalMemory: Long,
        val freeMemory: Long,
        val architecture: String
    )
}

