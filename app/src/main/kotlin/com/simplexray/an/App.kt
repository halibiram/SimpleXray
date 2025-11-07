package com.simplexray.an

import android.app.Application
import android.util.Log
import com.simplexray.an.common.AppLogger
import androidx.work.Configuration
import androidx.work.WorkManager
import com.simplexray.an.db.TrafficPruneWorker
import com.simplexray.an.alert.BurstDetector
import com.simplexray.an.power.PowerAdaptive
import com.simplexray.an.telemetry.FpsMonitor
import com.simplexray.an.telemetry.MemoryMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class App : Application() {
    // Use Dispatchers.IO for I/O operations instead of Default
    // Properly cancelled in cleanupResources() and onTerminate()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Lifecycle-aware detector initialization to prevent memory leaks
    // Properly stopped in cleanupResources()
    private var detector: BurstDetector? = null
    
    /**
     * Check if current process is the main application process (not :native)
     * Uses cached result to avoid expensive process name detection
     */
    private var cachedIsMainProcess: Boolean? = null
    
    private fun isMainProcess(): Boolean {
        // Return cached result if available
        cachedIsMainProcess?.let { return it }
        
        val processName = try {
            val pid = android.os.Process.myPid()
            // Use ApplicationInfo.processName as more reliable alternative
            val appInfo = applicationInfo
            if (appInfo.processName != null) {
                appInfo.processName
            } else {
                // Fallback to deprecated method only if needed
                val activityManager = getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                activityManager?.runningAppProcesses?.find { it.pid == pid }?.processName
            }
        } catch (e: Exception) {
            AppLogger.w("Error detecting process name: ${e.message}", e)
            // Default to true (main process) on error to avoid breaking initialization
            null
        }
        
        // Main process name is package name, native process has ":native" suffix
        val isMain = processName != null && processName == packageName && !processName.contains(":native")
        cachedIsMainProcess = isMain
        return isMain
    }
    
    override fun onCreate() {
        super.onCreate()
        
        // Only initialize WorkManager in main process
        // TProxyService runs in :native process where WorkManager is not needed
        if (isMainProcess()) {
            try {
                val config = Configuration.Builder()
                    .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.INFO)
                    .build()
                WorkManager.initialize(this, config)
                
                // Schedule background maintenance only in main process
                try {
                    TrafficPruneWorker.schedule(this)
                } catch (e: Exception) {
                    AppLogger.e("Failed to schedule TrafficPruneWorker", e)
                }
            } catch (e: IllegalStateException) {
                // WorkManager might already be initialized, ignore
                AppLogger.d("WorkManager already initialized")
            } catch (e: Exception) {
                AppLogger.e("WorkManager initialization failed: ${e.javaClass.simpleName}: ${e.message}", e)
                // WorkManager is optional - app can function without it, but background tasks won't work
            }
            
            // Start burst/throttle detector (uses global BitrateBus)
            // Add error handling for detector initialization failures
            try {
                detector = BurstDetector(this, appScope)
                detector?.start()
            } catch (e: Exception) {
                AppLogger.e("Failed to initialize BurstDetector", e)
                detector = null
            }
            // Initialize power-adaptive polling
            try {
                PowerAdaptive.init(this)
            } catch (e: Exception) {
                AppLogger.w("Failed to initialize PowerAdaptive: ${e.message}", e)
                // Continue without power-adaptive features
            }
            // Start telemetry monitors
            // Check if telemetry is enabled via preferences (default to false for privacy)
            try {
                // For now, telemetry is disabled by default for privacy
                // Can be enabled via SharedPreferences if needed in the future
                val sharedPrefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
                val telemetryEnabled = sharedPrefs.getBoolean("telemetry_enabled", false)
                if (telemetryEnabled) {
                    FpsMonitor.start()
                    MemoryMonitor.start(appScope)
                    AppLogger.d("Telemetry monitors started")
                } else {
                    AppLogger.d("Telemetry disabled by user preference (default)")
                }
            } catch (e: Exception) {
                // Log telemetry initialization failures with full details
                AppLogger.e("Failed to start telemetry monitors: ${e.javaClass.simpleName}: ${e.message}", e)
                // Continue without telemetry - app can function without it
            }
        } else {
            // In native process, skip WorkManager and UI-related initialization
            AppLogger.d("Running in native process, skipping WorkManager initialization")
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        // Cleanup resources when memory is low
        cleanupResources()
    }
    
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Cleanup resources when system requests memory trimming
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            cleanupResources()
        }
    }
    
    private fun cleanupResources() {
        // Perform partial cleanup - continue even if individual steps fail
        try {
            PowerAdaptive.cleanup()
        } catch (e: Exception) {
            AppLogger.w("Error cleaning up PowerAdaptive: ${e.message}", e)
        }
        try {
            detector?.stop()
            detector = null
        } catch (e: Exception) {
            AppLogger.w("Error stopping BurstDetector: ${e.message}", e)
        }
        try {
            MemoryMonitor.stop()
        } catch (e: Exception) {
            AppLogger.w("Error stopping MemoryMonitor: ${e.message}", e)
        }
        try {
            FpsMonitor.stop()
        } catch (e: Exception) {
            AppLogger.w("Error stopping FpsMonitor: ${e.message}", e)
        }
        try {
            appScope.cancel()
        } catch (e: Exception) {
            AppLogger.w("Error cancelling app scope: ${e.message}", e)
        }
    }
    
    override fun onTerminate() {
        super.onTerminate()
        // onTerminate() is not called on Android, but include cleanup for completeness
        cleanupResources()
    }
}
