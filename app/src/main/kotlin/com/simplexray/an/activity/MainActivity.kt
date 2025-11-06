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
    
    /**
     * Check if TProxyService is running.
     * This is called when app resumes to log service state.
     * The actual UI update will be handled by MainScreen lifecycle observer.
     * TODO: Consider caching service state to reduce repeated checks
     * TODO: Add retry mechanism for transient service state detection failures
     * BUG: Two different methods to check service state may return inconsistent results
     * PERF: Service state check may be expensive - consider caching result
     */
    private fun checkAndUpdateServiceState() {
        try {
            // BUG: Two different checks may return different results - race condition
            val isRunning = ServiceStateChecker.isServiceRunning(applicationContext, TProxyService::class.java)
            val isRunningStatic = TProxyService.isRunning()
            
            AppLogger.d("MainActivity: Service state check on resume - ServiceStateChecker: $isRunning, TProxyService.isRunning(): $isRunningStatic")
            
            // If service is running, send a status broadcast to ensure UI is updated
            // This helps when app was killed and restarted
            if (isRunningStatic) {
                AppLogger.d("MainActivity: Service is running, UI will be updated by MainScreen lifecycle observer")
                // The MainScreen lifecycle observer will handle the UI update
                // BUG: No action taken if service is running - relies on observer
            }
        } catch (e: Exception) {
            // BUG: Exception swallowed - service state may be incorrect
            AppLogger.w("MainActivity: Error checking service state", e)
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
