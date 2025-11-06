package com.simplexray.an.activity

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simplexray.an.common.AppLogger
import com.simplexray.an.common.ServiceStateChecker
import com.simplexray.an.db.TrafficPruneWorker
import com.simplexray.an.prefs.Preferences
import com.simplexray.an.service.TProxyService
import com.simplexray.an.ui.navigation.AppNavHost
import com.simplexray.an.viewmodel.MainViewModel
import com.simplexray.an.viewmodel.MainViewModelFactory
import com.simplexray.an.worker.TrafficWorkScheduler

class MainActivity : ComponentActivity() {
    // Track if workers have been scheduled to prevent duplicate scheduling
    private var workersScheduled = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Only clear Xray Settings server info on first creation, not on configuration changes
        if (savedInstanceState == null) {
            Preferences(applicationContext).clearXrayServerInfo()
        }
        
        // Schedule workers only once per process lifecycle
        // Workers use ExistingPeriodicWorkPolicy.KEEP, so duplicate calls are safe,
        // but we avoid unnecessary calls for better performance
        if (!workersScheduled) {
            try {
                TrafficPruneWorker.schedule(applicationContext)
                TrafficWorkScheduler.schedule(this)
                workersScheduled = true
            } catch (e: Exception) {
                AppLogger.e("Error scheduling workers", e)
            }
        }
        setContent {
            MaterialTheme {
                Surface {
                    App()
                }
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Check service state when app comes to foreground
        // This ensures UI shows correct connection state even if app was killed
        checkAndUpdateServiceState()
    }
    
    // Cache service state to reduce repeated checks
    private var cachedServiceState: Boolean? = null
    private var serviceStateCacheTime = 0L
    private val serviceStateCacheValidityMs = 5000L // 5 second cache
    
    /**
     * Check if TProxyService is running.
     * This is called when app resumes to log service state.
     * The actual UI update will be handled by MainScreen lifecycle observer.
     * Uses caching to reduce expensive service state checks
     */
    private fun checkAndUpdateServiceState() {
        try {
            // Check cache first
            val now = System.currentTimeMillis()
            if (cachedServiceState != null && (now - serviceStateCacheTime) < serviceStateCacheValidityMs) {
                return // Use cached state
            }
            
            // Use static method as primary source of truth (more reliable)
            val isRunningStatic = TProxyService.isRunning()
            
            // Verify with ServiceStateChecker for consistency
            val isRunningChecker = try {
                ServiceStateChecker.isServiceRunning(applicationContext, TProxyService::class.java)
            } catch (e: Exception) {
                AppLogger.w("ServiceStateChecker failed: ${e.message}", e)
                null // Use null to indicate check failed
            }
            
            // BUG: Service state mismatch detected but not resolved
            // Resolve mismatch by checking actual service state via ActivityManager
            if (isRunningChecker != null && isRunningChecker != isRunningStatic) {
                AppLogger.w("Service state mismatch - Static: $isRunningStatic, Checker: $isRunningChecker")
                // Try to resolve by checking actual service state
                try {
                    val activityManager = getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                    val actualRunning = activityManager?.getRunningServices(Integer.MAX_VALUE)
                        ?.any { it.service.className == TProxyService::class.java.name } ?: false
                    AppLogger.d("Actual service state from ActivityManager: $actualRunning")
                    // Use actual state as source of truth
                    cachedServiceState = actualRunning
                } catch (e: Exception) {
                    AppLogger.w("Failed to resolve service state mismatch: ${e.message}", e)
                }
            }
            
            // Use static method result as source of truth
            cachedServiceState = isRunningStatic
            serviceStateCacheTime = now
            
            AppLogger.d("MainActivity: Service state check on resume - Running: $isRunningStatic")
            
            // If service is running, the MainScreen lifecycle observer will handle the UI update
            // No additional action needed here as the observer is more reliable
        } catch (e: SecurityException) {
            AppLogger.e("Security error checking service state: ${e.message}", e)
            cachedServiceState = null // Invalidate cache on security error
        } catch (e: Exception) {
            AppLogger.e("Error checking service state: ${e.javaClass.simpleName}: ${e.message}", e)
            cachedServiceState = null // Invalidate cache on error
        }
    }
}

@Composable
private fun App() {
    val mainViewModel: MainViewModel = viewModel(
        factory = MainViewModelFactory(androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application)
    )
    AppNavHost(mainViewModel = mainViewModel)
}
