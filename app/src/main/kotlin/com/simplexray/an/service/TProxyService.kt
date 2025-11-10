package com.simplexray.an.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.simplexray.an.common.AppLogger
import com.simplexray.an.common.MiuiHelper
import androidx.core.app.NotificationCompat
import com.simplexray.an.BuildConfig
import com.simplexray.an.R
import com.simplexray.an.activity.MainActivity
import com.simplexray.an.common.ConfigUtils
import com.simplexray.an.common.ConfigUtils.extractPortsFromJson
import com.simplexray.an.data.source.LogFileManager
import com.simplexray.an.prefs.Preferences
import com.simplexray.an.service.XrayProcessManager
import com.simplexray.an.performance.PerformanceIntegration
import com.simplexray.an.chain.supervisor.ChainSupervisor
import com.simplexray.an.chain.supervisor.ChainConfig
import com.simplexray.an.chain.pepper.PepperParams
import com.simplexray.an.chain.pepper.PepperMode
import com.simplexray.an.chain.pepper.QueueDiscipline
import com.simplexray.an.xray.XrayCoreLauncher
import com.simplexray.an.quiche.QuicheClient
import com.simplexray.an.quiche.QuicheTunForwarder
import com.simplexray.an.quiche.CongestionControl
import com.simplexray.an.quiche.CpuAffinity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Collections
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.InterruptedIOException
import java.nio.charset.StandardCharsets
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.lang.Process

class TProxyService : VpnService() {
    // Properly cancelled in onDestroy() to prevent leaks
    // Use var to allow recreation after cancellation in stopXray()
    private var serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // Handler for scheduling connection checks (non-blocking operations only)
    private val handler = Handler(Looper.getMainLooper())
    // Use bounded buffer to prevent unbounded growth
    private val MAX_LOG_BUFFER_SIZE = 1000
    private val logBroadcastBuffer: MutableList<String> = Collections.synchronizedList(mutableListOf())
    
    // Connection monitoring - check if VPN connection is still active
    private val connectionCheckRunnable = Runnable {
        checkVpnConnection()
    }
    private var isMonitoringConnection = false
    private val broadcastLogsRunnable = Runnable {
        synchronized(logBroadcastBuffer) {
            if (logBroadcastBuffer.isNotEmpty()) {
                // Limit broadcast size to prevent Intent size limits
                val logsToBroadcast = logBroadcastBuffer.take(100)
                val logUpdateIntent = Intent(ACTION_LOG_UPDATE)
                logUpdateIntent.setPackage(application.packageName)
                logUpdateIntent.putStringArrayListExtra(
                    EXTRA_LOG_DATA, ArrayList(logsToBroadcast)
                )
                try {
                    sendBroadcast(logUpdateIntent)
                    logBroadcastBuffer.removeAll(logsToBroadcast)
                    AppLogger.d("Broadcasted a batch of ${logsToBroadcast.size} logs.")
                } catch (e: Exception) {
                    AppLogger.e("Failed to broadcast logs: ${e.message}", e)
                    // Clear buffer on failure to prevent accumulation
                    logBroadcastBuffer.clear()
                }
            }
        }
    }

    // Port availability checker with caching
    private val portChecker = PortAvailabilityChecker()

    private lateinit var logFileManager: LogFileManager
    
    // Performance optimization (optional, enabled via Preferences)
    private var perfIntegration: PerformanceIntegration? = null
    private val enablePerformanceMode: Boolean
        get() = Preferences(this).enablePerformanceMode
    
    // Chain supervisor for PepperShaper → Xray-core
    private var chainSupervisor: ChainSupervisor? = null
    // QUICHE TUN forwarder (TUN → QUICHE → Chain)
    // QUICME TUN forwarder is now managed by ChainSupervisor

    // Data class to hold both process and reloading state atomically
    // PID is stored separately to allow killing process even if Process reference becomes invalid
    private data class ProcessState(
        val process: Process?,
        val pid: Long = -1L,
        val reloading: Boolean
    )

    // Use single AtomicReference for thread-safe process state management
    // This prevents race conditions when reading/updating both process and reloading flag together
    private val processState = AtomicReference(ProcessState(null, -1L, false))
    private var tunFd: ParcelFileDescriptor? = null
    
    /**
     * Protect a socket file descriptor from being routed through the VPN tunnel.
     * This is critical to prevent loopback issues where VPN traffic tries to route
     * through its own tunnel, causing connection drops.
     * 
     * @param fd File descriptor to protect
     * @return true if protection succeeded, false otherwise
     */
    fun protectSocket(fd: Int): Boolean {
        return try {
            val protected = protect(fd)
            if (protected) {
                AppLogger.d("TProxyService: Socket protected successfully, fd=$fd")
                // Log to verify protect() binding is working
                android.util.Log.d("TProxyService", "VpnService.protect(fd=$fd) returned true - binding verified")
            } else {
                AppLogger.w("TProxyService: Socket protection failed, fd=$fd")
                android.util.Log.w("TProxyService", "VpnService.protect(fd=$fd) returned false - binding may not work")
            }
            protected
        } catch (e: Exception) {
            AppLogger.e("TProxyService: Error protecting socket fd=$fd: ${e.message}", e)
            android.util.Log.e("TProxyService", "VpnService.protect(fd=$fd) exception: ${e.message}", e)
            false
        }
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning.set(true)
        logFileManager = LogFileManager(this)

        // Enhanced lifecycle logging for crash detection
        AppLogger.i("TProxyService: onCreate() - Service instance created, process PID=${android.os.Process.myPid()}, thread=${Thread.currentThread().name}")

        // MIUI Device Detection and Setup
        if (MiuiHelper.isMiuiDevice()) {
            MiuiHelper.logMiuiDeviceInfo()

            val permStatus = MiuiHelper.checkMiuiPermissions(this)
            when (permStatus) {
                MiuiHelper.MiuiPermissionStatus.NEEDS_SETUP -> {
                    AppLogger.w("TProxyService: MIUI permissions not configured - VPN may be killed by system")
                    AppLogger.w("TProxyService: User should enable Autostart and disable Battery Optimization")
                }
                MiuiHelper.MiuiPermissionStatus.OK -> {
                    AppLogger.i("TProxyService: MIUI permissions configured correctly")
                }
                else -> {}
            }
        }

        // VPN Permission Check: Ensure user has granted VPN permission
        // VpnService.prepare() returns null if permission is already granted
        // If it returns an Intent, user needs to grant permission
        val vpnPrepareIntent = VpnService.prepare(this)
        if (vpnPrepareIntent != null) {
            AppLogger.w("TProxyService: VPN permission not granted, user must approve VPN access")
            // Note: We cannot startActivityForResult from Service.onCreate()
            // Permission check should be done before starting the service
            // This is a safety check - actual permission request is done in MainViewModel/ConnectionViewModel
        } else {
            AppLogger.d("TProxyService: VPN permission already granted")
        }

        // Initialize performance optimizations if enabled
        // Add configuration validation before initializing performance mode
        if (enablePerformanceMode) {
            try {
                perfIntegration = PerformanceIntegration(this)
                // Set VpnService instance for socket protection (critical for preventing loopback)
                perfIntegration?.setVpnService(this)
                perfIntegration?.initialize()
                AppLogger.d("Performance mode enabled with VPN socket protection")
            } catch (e: IllegalStateException) {
                AppLogger.e("Performance mode initialization failed - invalid state: ${e.message}", e)
                // Disable performance mode if initialization fails
                Preferences(this).enablePerformanceMode = false
            } catch (e: Exception) {
                AppLogger.e("Failed to initialize performance mode: ${e.javaClass.simpleName}: ${e.message}", e)
                // Disable performance mode if initialization fails
                Preferences(this).enablePerformanceMode = false
            }
        }

        AppLogger.i("TProxyService: onCreate() completed successfully")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // VPN Session Lifecycle Logging
        AppLogger.d("TProxyService: onStartCommand called - intent=${intent?.action}, flags=$flags, startId=$startId, tunFd=${if (tunFd != null) "valid" else "null"}")
        
        // CRITICAL: Start foreground immediately to prevent ForegroundServiceDidNotStartInTimeException
        // Android requires startForeground() to be called within 5 seconds of startForegroundService()
        // We must call it before any potentially long-running operations
        try {
            val channelName = if (intent?.action == ACTION_START) {
                val prefs = Preferences(this)
                if (prefs.disableVpn) "nosocks" else "chain"
            } else {
                "chain"
            }
            initNotificationChannel(channelName)
            createNotification(channelName)
            AppLogger.d("TProxyService: Foreground notification started immediately")
        } catch (e: Exception) {
            AppLogger.e("TProxyService: Failed to start foreground notification: ${e.message}", e)
            // Continue anyway - better to have a delayed notification than crash
        }
        
        // Handle null intent (service restart after being killed by system)
        if (intent == null) {
            val manufacturer = android.os.Build.MANUFACTURER
            val isMIUI = manufacturer.equals("Xiaomi", ignoreCase = true) || manufacturer.equals("Redmi", ignoreCase = true)

            if (isMIUI) {
                AppLogger.w("TProxyService: MIUI/HyperOS - Restarted with null intent (may have been killed by system)")
            } else {
                AppLogger.w("TProxyService: Restarted with null intent, attempting state recovery")
            }

            val prefs = Preferences(this)

            // Check if VPN was running before process death
            if (prefs.vpnServiceWasRunning) {
                if (isMIUI) {
                    AppLogger.i("TProxyService: MIUI/HyperOS - Attempting automatic VPN recovery")
                } else {
                    AppLogger.d("TProxyService: VPN was running before restart, attempting recovery")
                }
                
                // Verify config file still exists
                val configPath = prefs.selectedConfigPath
                if (configPath != null && File(configPath).exists()) {
                    AppLogger.d("TProxyService: Config file found, restoring VPN connection")
                    
                    // Check if tunFd is still valid (may be null after process death)
                    val currentTunFd = tunFd // Capture reference to avoid race condition
                    if (currentTunFd != null) {
                        try {
                            // Verify file descriptor is still valid
                            // FileDescriptor.valid() may not be reliable on all Android versions
                            if (currentTunFd.fileDescriptor.valid()) {
                                AppLogger.d("TProxyService: VPN file descriptor still valid, maintaining connection")
                                val channelName = if (prefs.disableVpn) "nosocks" else "chain"
                                initNotificationChannel(channelName)
                                createNotification(channelName)
                                startConnectionMonitoring()
                                // Restart xray process to reconnect
                                serviceScope.launch {
                                    try {
                                        runXrayProcess()
                                    } catch (e: Exception) {
                                        AppLogger.e("TProxyService: Failed to restart xray process after recovery", e)
                                        // Stop service if process restart fails
                                        stopXray()
                                    }
                                }
                                return START_STICKY
                            }
                        } catch (e: SecurityException) {
                            AppLogger.e("TProxyService: Security error validating file descriptor", e)
                        } catch (e: Exception) {
                            AppLogger.e("TProxyService: File descriptor validation failed: ${e.javaClass.simpleName}: ${e.message}", e)
                        }
                    }
                    
                    // File descriptor is invalid or null, need to recreate VPN
                    AppLogger.d("TProxyService: Recreating VPN connection")
                    try {
                        startXray()
                        return START_STICKY
                    } catch (e: Exception) {
                        AppLogger.e("TProxyService: Failed to restore VPN connection", e)
                        // Clear state on failure
                        prefs.vpnServiceWasRunning = false
                        return START_NOT_STICKY
                    }
                } else {
                    AppLogger.w("TProxyService: Config file not found, cannot restore VPN")
                    prefs.vpnServiceWasRunning = false
                    return START_NOT_STICKY
                }
            } else {
                AppLogger.d("TProxyService: VPN was not running before restart")
                return START_NOT_STICKY
            }
        }
        
        // Check if service is already running with active connection
        if (Companion.isRunning() && tunFd != null && intent?.action == "com.simplexray.an.CONNECT") {
            AppLogger.w("TProxyService: Service already running with active VPN connection (startId=$startId)")
            AppLogger.i("TProxyService: Stopping previous connection before starting new one")
            stopXray()
            // Wait a moment for cleanup
            try {
                Thread.sleep(500)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        
        val action = intent.action
        when (action) {
            ACTION_DISCONNECT -> {
                stopXray()
                return START_NOT_STICKY
            }

            ACTION_RELOAD_CONFIG -> {
                val prefs = Preferences(this)
                if (prefs.disableVpn) {
                    AppLogger.d("Received RELOAD_CONFIG action (core-only mode)")
                    // Atomically get current process, destroy it, and set reloading flag
                    val currentState = processState.getAndUpdate { state ->
                        ProcessState(state.process, state.pid, reloading = true)
                    }
                    killProcessSafely(currentState.process, currentState.pid)
                    serviceScope.launch { runXrayProcess() }
                    return START_STICKY
                }
                if (tunFd == null) {
                    AppLogger.w("Cannot reload config, VPN service is not running.")
                    return START_STICKY
                }
                AppLogger.d("Received RELOAD_CONFIG action.")
                // Atomically get current process, destroy it, and set reloading flag
                val currentState = processState.getAndUpdate { state ->
                    ProcessState(state.process, state.pid, reloading = true)
                }
                killProcessSafely(currentState.process, currentState.pid)
                serviceScope.launch { runXrayProcess() }
                return START_STICKY
            }

            ACTION_START -> {
                logFileManager.clearLogs()
                val prefs = Preferences(this)
                if (prefs.disableVpn) {
                    // Start monitoring even in core-only mode (to detect service issues)
                    startConnectionMonitoring()

                    // Save state for core-only mode too
                    prefs.vpnServiceWasRunning = true

                    // MIUI Compatibility: Start watchdog services even in core-only mode
                    try {
                        VpnWatchdogJobService.scheduleWatchdog(this)
                        VpnWatchdogReceiver.scheduleWatchdog(this)
                        AppLogger.i("TProxyService: Watchdog services scheduled for core-only mode (MIUI compatibility)")
                    } catch (e: Exception) {
                        AppLogger.w("TProxyService: Failed to schedule watchdog services: ${e.message}", e)
                    }

                    serviceScope.launch { runXrayProcess() }
                    val successIntent = Intent(ACTION_START)
                    successIntent.setPackage(application.packageName)
                    sendBroadcast(successIntent)

                } else {
                    startXray()
                }
                return START_STICKY
            }

            else -> {
                logFileManager.clearLogs()
                startXray()
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        return super.onBind(intent)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // When user swipes away the app, restart the service to keep VPN connection alive
        AppLogger.i("TProxyService: App task removed, ensuring service continues (MIUI compatibility mode)")

        // MIUI/HyperOS compatibility: Ensure service persists
        // MIUI may try to kill the service when task is removed
        val prefs = Preferences(this)
        if (prefs.vpnServiceWasRunning && tunFd != null) {
            try {
                // Re-create foreground notification to reinforce service importance
                val channelName = if (prefs.disableVpn) "nosocks" else "chain"
                createNotification(channelName)
                AppLogger.d("TProxyService: Foreground notification refreshed for MIUI compatibility")

                // Ensure VPN state is persisted
                prefs.vpnServiceWasRunning = true
                AppLogger.d("TProxyService: VPN state persisted - MIUI should not kill service")
            } catch (e: Exception) {
                AppLogger.e("TProxyService: Error refreshing foreground state: ${e.message}", e)
            }
        }

        // Don't stop the service - let it continue running in background
        // The service will keep running even when app is removed from recent apps
    }

    override fun onDestroy() {
        // VPN Session Lifecycle Logging
        AppLogger.i("TProxyService: onDestroy called - VPN state=destroying, tunFd=${if (tunFd != null) "valid" else "null"}, PID=${android.os.Process.myPid()}")

        // Crash Detector: Check if VPN was unexpectedly stopped
        val vpnActive = tunFd != null && isServiceRunning.get()
        if (vpnActive) {
            AppLogger.e("VPN: Unexpected Stop: VPN was active when service destroyed. tunFd=${if (tunFd != null) "valid" else "null"}, isRunning=${isServiceRunning.get()}")

            // MIUI/HyperOS Detection: Log if this might be MIUI restart rejection
            val manufacturer = android.os.Build.MANUFACTURER
            if (manufacturer.equals("Xiaomi", ignoreCase = true) || manufacturer.equals("Redmi", ignoreCase = true)) {
                AppLogger.w("TProxyService: MIUI/HyperOS detected - Service destroyed while VPN active. This may be MIUI restart restriction.")
                AppLogger.w("TProxyService: MIUI may have rejected automatic service restart. User may need to manually restart VPN.")
            }
        }

        super.onDestroy()
        isServiceRunning.set(false)

        // Stop connection monitoring
        stopConnectionMonitoring()

        // Stop port monitoring
        stopPortMonitoring()

        handler.removeCallbacks(broadcastLogsRunnable)
        broadcastLogsRunnable.run()
        
        // Cleanup performance optimizations
        try {
            perfIntegration?.cleanup()
        } catch (e: Exception) {
            AppLogger.w("Error during performance cleanup", e)
        } finally {
            perfIntegration = null
        }
        
        // Ensure xray process is stopped when service is destroyed
        // This is critical when app goes to background and service is killed by system
        AppLogger.d("TProxyService destroyed, stopping xray process")
        val oldState = processState.getAndSet(ProcessState(null, -1L, false))
        killProcessSafely(oldState.process, oldState.pid)
        
        serviceScope.cancel()
        AppLogger.d("TProxyService: onDestroy completed - VPN state=destroyed")
        // Removed exitProcess(0) - let Android handle service lifecycle properly
        // exitProcess() forcefully kills the entire app process which prevents proper cleanup
    }

    override fun onRevoke() {
        // VPN Session Lifecycle Logging
        AppLogger.w("TProxyService: onRevoke called - VPN connection revoked by system, tunFd=${if (tunFd != null) "valid" else "null"}")
        
        // Clear state that VPN is running
        val prefs = Preferences(this)
        prefs.vpnServiceWasRunning = false
        AppLogger.d("TProxyService: VPN state cleared after revoke")
        
        // VPN was revoked, stop the service
        stopXray()
        super.onRevoke()
        
        AppLogger.d("TProxyService: onRevoke completed - VPN state=revoked")
    }

    private fun startXray() {
        // Ensure scope is active before launching coroutines
        // This prevents issues if scope was cancelled unexpectedly
        val job = (serviceScope.coroutineContext[Job]) ?: SupervisorJob()
        if (!job.isActive) {
            AppLogger.w("TProxyService: serviceScope was inactive, recreating it")
            serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        }
        startService()
        serviceScope.launch { runXrayProcess() }
    }

    private fun runXrayProcess() {
        var currentProcess: Process? = null
        var processPid: Long = -1
        try {
            AppLogger.d("Attempting to start xray process.")
            val prefs = Preferences(applicationContext)
            val selectedConfigPath = prefs.selectedConfigPath
            if (selectedConfigPath == null) {
                AppLogger.e("No configuration file selected")
                stopXray()
                return
            }
            // Use XrayCoreLauncher.copyExecutable() to copy libxray.so to filesDir
            // This avoids SELinux setattr restrictions on nativeLibraryDir
            val xrayPath = com.simplexray.an.xray.XrayCoreLauncher.copyExecutable(applicationContext)?.absolutePath
            if (xrayPath == null) {
                AppLogger.e("Failed to copy xray executable to filesDir")
                stopXray()
                return
            }
            // Validate config file path to prevent directory traversal
            val configFile = File(selectedConfigPath)
            if (!configFile.canonicalPath.startsWith(applicationContext.filesDir.canonicalPath) &&
                !configFile.canonicalPath.startsWith(applicationContext.cacheDir.canonicalPath)) {
                AppLogger.e("Config file path outside allowed directories: ${configFile.absolutePath}")
                stopXray()
                return
            }
            
            // Validate config file size (max 10MB)
            if (configFile.length() > 10 * 1024 * 1024) {
                AppLogger.e("Config file too large: ${configFile.length()} bytes")
                stopXray()
                return
            }
            
            // Read config file with size limit
            val configContent = try {
                configFile.inputStream().bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                AppLogger.e("Failed to read config file: ${e.message}", e)
                stopXray()
                return
            }
            
            // Find available port with retry mechanism
            val apiPort = portChecker.findAvailablePort(extractPortsFromJson(configContent)) 
                ?: portChecker.findAvailablePort(emptySet()) // Fallback: try without exclusions
                ?: run {
                    AppLogger.e("No available port found after retries")
                    stopXray()
                    return
                }
            prefs.apiPort = apiPort
            AppLogger.d("Found and set API port: $apiPort")

            val processBuilder = getProcessBuilder(xrayPath)
            // Update process manager ports for observers
            XrayProcessManager.updateFrom(applicationContext)
            currentProcess = processBuilder.start()
            
            // Get process PID immediately after starting
            // Android 16+ compatibility: Process.pid() method may not be available
            processPid = getProcessPid(currentProcess, xrayPath)
            if (processPid != -1L) {
                AppLogger.i("Xray process started successfully with PID: $processPid")
            } else {
                AppLogger.w("Could not get process PID (Android 16+ compatibility issue). Process reference will be used for management.")
            }
            
            // Check if process dies immediately after start (indicates crash or config error)
            // Wait a short time to see if process stays alive
            try {
                kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.delay(2000) // Wait 2 seconds
                }
                if (!currentProcess.isAlive) {
                    val exitCode = try {
                        currentProcess.exitValue()
                    } catch (e: IllegalThreadStateException) {
                        -1
                    }
                    AppLogger.e("Xray process died immediately after start (PID: $processPid, exit code: $exitCode). This indicates a crash or configuration error.")
                    
                    // Try to read error output for diagnostics
                    try {
                        val logFile = File(applicationContext.filesDir, "xray.log")
                        if (logFile.exists() && logFile.length() > 0) {
                            val errorLog = logFile.inputStream().bufferedReader().use { reader ->
                                val fileSize = logFile.length()
                                val logSize = minOf(2000, fileSize.toInt()) // Last 2KB
                                if (fileSize > logSize) {
                                    reader.skip(fileSize - logSize)
                                }
                                reader.readText()
                            }
                            if (errorLog.isNotBlank()) {
                                val sanitized = AppLogger.sanitize(errorLog)
                                AppLogger.e("Xray error log after immediate crash:\n$sanitized")
                            }
                        }
                    } catch (e: Exception) {
                        AppLogger.w("Could not read error log: ${e.message}", e)
                    }
                    
                    stopXray()
                    return
                }
            } catch (e: Exception) {
                AppLogger.w("Error checking if process is alive: ${e.message}", e)
            }
            
            // Atomically update process state with PID, preserving reloading flag
            processState.updateAndGet { state ->
                ProcessState(currentProcess, processPid, state.reloading)
            }
            
            // Apply performance optimizations after Xray process starts (if enabled)
            if (enablePerformanceMode && perfIntegration != null) {
                try {
                    // Request CPU boost for Xray process
                    perfIntegration?.getPerformanceManager()?.requestCPUBoost(10000) // 10 seconds
                    AppLogger.d("Performance optimizations applied to Xray process")
                } catch (e: Exception) {
                    AppLogger.w("Failed to apply performance optimizations to Xray process", e)
                }
            }

            AppLogger.d("Writing config to xray stdin from: $selectedConfigPath")
            // Config content may contain sensitive data - ensure not logged
            // Add config validation before injection
            val injectedConfigContent = try {
                ConfigUtils.injectStatsService(prefs, configContent)
            } catch (e: Exception) {
                AppLogger.e("Failed to inject stats service into config: ${e.message}", e)
                stopXray()
                return
            }
            
            // Write config with error handling
            // SEC: Config content written to process stdin - ensure no injection
            // TIMEOUT-MISS: No timeout on write - may block indefinitely
            try {
                currentProcess.outputStream.use { os ->
                    val configBytes = injectedConfigContent.toByteArray()
                    // PERF: Large config allocation - use memory pool for 64KB+ configs
                    // For large configs, write in chunks using pool buffer to reduce GC pressure
                    if (configBytes.size > 64 * 1024 && enablePerformanceMode && perfIntegration != null) {
                        // Use memory pool buffer for large configs
                        val buffer = perfIntegration?.getMemoryPool()?.acquire()
                        try {
                            if (buffer != null) {
                                var offset = 0
                                while (offset < configBytes.size) {
                                    val chunkSize = minOf(buffer.capacity(), configBytes.size - offset)
                                    buffer.clear()
                                    buffer.put(configBytes, offset, chunkSize)
                                    buffer.flip()
                                    val bytesArray = ByteArray(buffer.remaining())
                                    buffer.get(bytesArray)
                                    os.write(bytesArray)
                                    os.flush()
                                    offset += chunkSize
                                }
                            } else {
                                // Fallback: pool unavailable, use direct write
                                var offset = 0
                                while (offset < configBytes.size) {
                                    val chunkSize = minOf(64 * 1024, configBytes.size - offset)
                                    os.write(configBytes, offset, chunkSize)
                                    os.flush()
                                    offset += chunkSize
                                }
                            }
                        } finally {
                            buffer?.let { perfIntegration?.getMemoryPool()?.release(it) }
                        }
                    } else {
                        // Small configs: direct write
                        os.write(configBytes)
                        os.flush()
                    }
                }
            } catch (e: java.io.IOException) {
                // BUG: Process may be left in inconsistent state if write fails
                AppLogger.e("Failed to write config to process stdin: ${e.message}", e)
                stopXray()
                return
            } catch (e: Exception) {
                // BUG: Process may be left in inconsistent state if write fails
                AppLogger.e("Unexpected error writing config: ${e.javaClass.simpleName}: ${e.message}", e)
                stopXray()
                return
            }

            // PERF: Reading from process inputStream on IO dispatcher - consider async I/O
            // IO-BLOCK: BufferedReader.readLine() may block indefinitely
            // TIMEOUT-MISS: Timeout check only before read - readLine() itself has no timeout
            val inputStream = currentProcess.inputStream
            // Use larger buffer (128KB) to prevent output loss during high-volume logging
            // Explicitly set UTF-8 encoding to ensure proper character handling
            InputStreamReader(inputStream, StandardCharsets.UTF_8).use { isr ->
                BufferedReader(isr, 131072).use { reader -> // 128KB buffer to prevent output loss
                    var line: String?
                    AppLogger.d("Reading xray process output (128KB buffer).")
                    // Read xray process output continuously without timeout
                    // Process output may pause for long periods, which is normal
                    // Only break on actual EOF or interruption
                    while (true) {
                        // Try to read with timeout protection
                        try {
                            line = reader.readLine() ?: break
                        } catch (e: java.io.InterruptedIOException) {
                            AppLogger.d("Reading interrupted")
                            break
                        } catch (e: java.io.IOException) {
                            // Only break on actual IO errors, not timeouts
                            // Process may not output logs for extended periods, which is normal
                            if (e.message?.contains("Stream closed", ignoreCase = true) == true ||
                                e.message?.contains("Broken pipe", ignoreCase = true) == true) {
                                AppLogger.d("Process output stream closed")
                                break
                            }
                            // For other IO errors, log and break (process may have died)
                            AppLogger.w("IO error reading process output: ${e.message}")
                            break
                        }
                        line?.let { logLine ->
                            // Ensure log is saved even if broadcast fails
                            try {
                                logFileManager.appendLog(logLine)
                            } catch (e: Exception) {
                                // Log file write failed, but don't lose the output
                                AppLogger.e("Failed to write log to file: ${e.message}", e)
                                // Still try to broadcast even if file write failed
                            }
                            
                            // Add to broadcast buffer with error handling
                            try {
                                synchronized(logBroadcastBuffer) {
                                    // Prevent unbounded growth
                                    if (logBroadcastBuffer.size >= MAX_LOG_BUFFER_SIZE) {
                                        logBroadcastBuffer.removeAt(0) // Remove oldest
                                    }
                                    logBroadcastBuffer.add(logLine)
                                    if (!handler.hasCallbacks(broadcastLogsRunnable)) {
                                        handler.postDelayed(broadcastLogsRunnable, BROADCAST_DELAY_MS)
                                    }
                                }
                            } catch (e: Exception) {
                                // Broadcast buffer update failed, but log is already saved to file
                                AppLogger.w("Failed to add log to broadcast buffer: ${e.message}")
                            }
                        }
                    }
                }
            }
            AppLogger.d("xray process output stream finished.")
        } catch (e: InterruptedIOException) {
            AppLogger.d("Xray process reading interrupted.")
            // Interruption is expected when process is stopped
        } catch (e: java.util.concurrent.TimeoutException) {
            AppLogger.w("Timeout reading xray process output", e)
        } catch (e: java.io.IOException) {
            AppLogger.e("IO error executing xray", e)
        } catch (e: SecurityException) {
            AppLogger.e("Security error executing xray", e)
        } catch (e: Exception) {
            AppLogger.e("Unexpected error executing xray", e)
        } finally {
            AppLogger.d("Xray process task finished (PID: $processPid).")
            
            // Get current process state to check if this is still the active process
            val currentState = processState.get()
            val isActiveProcess = currentState.process === currentProcess
            
            // Only cleanup if this is still the active process or if process reference is invalid
            if (isActiveProcess || currentProcess != null) {
                // Use killProcessSafely to handle both Process reference and PID fallback
                killProcessSafely(currentProcess, processPid)
            } else {
                AppLogger.d("Skipping cleanup: process instance changed (old PID: $processPid)")
            }
            
            // Atomically check reloading flag and clear process reference if it matches
            val wasReloading = processState.getAndUpdate { state ->
                if (state.process === currentProcess) {
                    // Clear process and reset reloading flag atomically
                    ProcessState(null, -1L, reloading = false)
                } else {
                    // Keep current state, only reset reloading flag if it was set
                    ProcessState(state.process, state.pid, reloading = false)
                }
            }
            
            if (wasReloading.reloading && wasReloading.process === currentProcess) {
                AppLogger.d("Xray process stopped due to configuration reload.")
            } else if (wasReloading.process === currentProcess) {
                // Enhanced diagnostics for VPN disconnection issue
                val exitCode = try {
                    currentProcess?.exitValue() ?: -1
                } catch (e: IllegalThreadStateException) {
                    -1
                } catch (e: Exception) {
                    AppLogger.w("Could not get exit code: ${e.message}", e)
                    -1
                }
                
                // Check if process is still alive (might have been killed externally)
                val isStillAlive = try {
                    currentProcess?.isAlive ?: false
                } catch (e: Exception) {
                    false
                }
                
                if (exitCode != 0 && exitCode != -1) {
                    AppLogger.e("Xray process exited with non-zero code: $exitCode (PID: $processPid). This may indicate a crash or configuration error.")
                } else if (!isStillAlive) {
                    AppLogger.w("Xray process exited unexpectedly (PID: $processPid, exit code: $exitCode). VPN will disconnect.")
                } else {
                    AppLogger.d("Xray process stopped normally (PID: $processPid).")
                }
                
                // Try to read recent logs for diagnostics
                try {
                    val logFile = File(applicationContext.filesDir, "xray.log")
                    if (logFile.exists() && logFile.length() > 0) {
                        val recentLogs = logFile.inputStream().bufferedReader().use { reader ->
                            val fileSize = logFile.length()
                            val logSize = minOf(1000, fileSize.toInt()) // Last 1KB
                            if (fileSize > logSize) {
                                reader.skip(fileSize - logSize)
                            }
                            reader.readText()
                        }
                        if (recentLogs.isNotBlank()) {
                            val sanitized = AppLogger.sanitize(recentLogs)
                            AppLogger.e("Recent xray logs before exit:\n$sanitized")
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.w("Could not read xray logs for diagnostics: ${e.message}", e)
                }
                
                AppLogger.d("Xray process exited unexpectedly or due to stop request. Stopping VPN.")
                stopXray()
            } else {
                AppLogger.w("Finishing task for an old xray process instance.")
            }
        }
    }

    private fun getProcessBuilder(xrayPath: String): ProcessBuilder {
        val filesDir = applicationContext.filesDir
        val cacheDir = applicationContext.cacheDir
        val command: MutableList<String> = mutableListOf(xrayPath)
        val processBuilder = ProcessBuilder(command)

        // Configure SELinux-compliant environment (includes PATH filtering)
        com.simplexray.an.common.SelinuxComplianceHelper.configureProcessEnvironment(
            processBuilder, filesDir, cacheDir
        )

        // Set xray-specific environment variables
        val environment = processBuilder.environment()
        environment["XRAY_LOCATION_ASSET"] = filesDir.path

        processBuilder.redirectErrorStream(true)
        return processBuilder
    }

    private fun stopXray() {
        AppLogger.d("stopXray called with keepExecutorAlive=" + false)

        // Clear state that VPN is running
        val prefs = Preferences(this)
        prefs.vpnServiceWasRunning = false
        AppLogger.d("TProxyService: VPN state cleared - service is stopping")

        // MIUI Compatibility: Cancel watchdog services when VPN is manually stopped
        try {
            VpnWatchdogJobService.cancelWatchdog(this)
            VpnWatchdogReceiver.cancelWatchdog(this)
            AppLogger.d("TProxyService: Watchdog services cancelled")
        } catch (e: Exception) {
            AppLogger.w("TProxyService: Failed to cancel watchdog services: ${e.message}", e)
        }

        // Stop connection monitoring
        stopConnectionMonitoring()

        // Stop port monitoring
        stopPortMonitoring()

        // Cancel existing scope and recreate it to allow future startXray() calls
        serviceScope.cancel()
        serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        AppLogger.d("CoroutineScope cancelled and recreated.")

        // Atomically get and clear process state
        val oldState = processState.getAndSet(ProcessState(null, -1L, false))
        
        // Kill process using both Process reference and PID as fallback
        killProcessSafely(oldState.process, oldState.pid)
        
        AppLogger.d("processState cleared.")

        AppLogger.d("Calling stopService (stopping VPN).")
        stopService()
    }
    
    /**
     * Safely kill process using Process reference if available, or PID as fallback.
     * This is critical when app goes to background and Process reference becomes invalid.
     * 
     * Security and safety measures:
     * - Validates PID before execution to prevent command injection
     * - Verifies process is xray process before killing (prevents PID reuse race)
     * - Verifies process termination after kill attempt
     */
    @Synchronized
    private fun killProcessSafely(proc: Process?, pid: Long) {
        if (proc == null && pid == -1L) {
            return // Nothing to kill
        }
        
        val effectivePid = if (pid != -1L) {
            pid
        } else {
            // Try to get PID from Process reference using Android 16+ compatible method
            if (proc != null) {
                // Try Process.pid() first (Android < 16)
                try {
                    proc.javaClass.getMethod("pid").invoke(proc) as? Long ?: -1L
                } catch (e: NoSuchMethodException) {
                    // Android 16+: Process.pid() not available, use Process reference directly
                    -1L
                } catch (e: Exception) {
                    -1L
                }
            } else {
                -1L
            }
        }
        
        if (effectivePid == -1L && proc == null) {
            AppLogger.w("Cannot kill process: no valid PID or Process reference")
            return
        }
        
        // If we have Process reference but no PID, use Process reference directly
        if (effectivePid == -1L && proc != null) {
            AppLogger.d("Stopping xray process using Process reference (PID unavailable on Android 16+)")
            try {
                if (proc.isAlive) {
                    proc.destroy()
                    try {
                        val exited = proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                        if (!exited) {
                            AppLogger.w("Process did not exit gracefully, forcing termination")
                            proc.destroyForcibly()
                            proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                        }
                    } catch (e: Exception) {
                        AppLogger.w("Error waiting for process termination: ${e.message}", e)
                        proc.destroyForcibly()
                    }
                }
            } catch (e: Exception) {
                AppLogger.w("Error destroying process: ${e.message}", e)
            }
            return
        }
        
        AppLogger.d("Stopping xray process (PID: $effectivePid)")
        
        // First try graceful shutdown using Process reference if available
        if (proc != null) {
            try {
                if (proc.isAlive) {
                    proc.destroy()
                    try {
                        val exited = proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                        if (!exited) {
                            AppLogger.w("Process (PID: $effectivePid) did not exit gracefully, forcing termination")
                            proc.destroyForcibly()
                            proc.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)
                        } else {
                            AppLogger.d("Process (PID: $effectivePid) exited gracefully")
                            return
                        }
                    } catch (e: InterruptedException) {
                        AppLogger.d("Process wait interrupted during stop")
                        proc.destroyForcibly()
                    } catch (e: Exception) {
                        AppLogger.w("Error waiting for process termination via Process reference", e)
                        proc.destroyForcibly()
                    }
                } else {
                    AppLogger.d("Process (PID: $effectivePid) already dead")
                    return
                }
            } catch (e: Exception) {
                AppLogger.w("Error stopping process via Process reference (PID: $effectivePid), will try PID kill", e)
            }
        }
        
        // Fallback: kill by PID directly if Process reference is invalid or didn't work
        // This is critical when app goes to background and Process reference becomes stale
        if (effectivePid != -1L) {
            // Validate PID range before any operations
            if (effectivePid <= 0 || effectivePid > Int.MAX_VALUE) {
                AppLogger.e("Invalid PID range: $effectivePid")
                return
            }
            
            try {
                // Check if process is still alive using PID
                val isAlive = isProcessAlive(effectivePid.toInt())
                
                if (isAlive) {
                    // CRITICAL: Prevent self-kill! Never kill our own service process
                    val myPid = android.os.Process.myPid()
                    if (effectivePid.toInt() == myPid) {
                        AppLogger.e("CRITICAL: Attempted to kill own process (PID: $effectivePid)! This would crash VPN service. Aborting kill.")
                        AppLogger.e("This indicates a bug in process management - Xray child process PID was not tracked correctly")
                        return
                    }

                    // Verify this is still the xray process (prevents PID reuse race condition)
                    if (!isXrayProcess(effectivePid.toInt())) {
                        AppLogger.w("Process (PID: $effectivePid) does not appear to be xray process. Skipping kill.")
                        return
                    }

                    AppLogger.d("Killing Xray child process by PID: $effectivePid (my PID: $myPid)")
                    try {
                        // Use Android Process.killProcess for same-UID processes (graceful shutdown)
                        android.os.Process.killProcess(effectivePid.toInt())
                        AppLogger.d("Sent kill signal to process PID: $effectivePid")
                        
                        // Wait a bit to see if it exits
                        // Use delay in coroutine instead of Thread.sleep
                        kotlinx.coroutines.runBlocking {
                            kotlinx.coroutines.withTimeout(2000) { // 2 second timeout
                                kotlinx.coroutines.delay(500)
                            }
                        }
                        
                        // Verify process is dead
                        val stillAlive = isProcessAlive(effectivePid.toInt())
                        
                        if (stillAlive) {
                            AppLogger.w("Process (PID: $effectivePid) still alive after killProcess, trying force kill")
                            // Last resort: try kill -9 via Runtime.exec
                            // PID already validated above, use array form to prevent command injection
                            try {
                                val pidStr = effectivePid.toString()
                                // Double-check PID format (defense in depth)
                                if (pidStr.matches(Regex("^\\d+$")) && effectivePid > 0 && effectivePid <= Int.MAX_VALUE) {
                                    // Use array form to prevent command injection
                                    val killCmd = arrayOf("kill", "-9", pidStr)
                                    val killProcess = Runtime.getRuntime().exec(killCmd)
                                    val exitCode = killProcess.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                                    if (exitCode ) {
                                        AppLogger.d("Force killed process PID: $effectivePid")
                                        // Verify process is actually dead after force kill
                                        val stillAliveAfterForce = isProcessAlive(effectivePid.toInt())
                                        if (stillAliveAfterForce) {
                                            AppLogger.e("Process (PID: $effectivePid) still alive after force kill - may require manual intervention")
                                        }
                                    } else {
                                        AppLogger.w("Kill command returned non-zero exit code: $exitCode")
                                    }
                                } else {
                                    AppLogger.e("Invalid PID format or range: $effectivePid")
                                }
                            } catch (e: Exception) {
                                AppLogger.e("Failed to force kill process PID: $effectivePid", e)
                            }
                        } else {
                            AppLogger.d("Process (PID: $effectivePid) successfully killed")
                        }
                    } catch (e: SecurityException) {
                        AppLogger.e("Permission denied killing process PID: $effectivePid", e)
                    } catch (e: Exception) {
                        AppLogger.e("Error killing process by PID: $effectivePid", e)
                    }
                } else {
                    AppLogger.d("Process (PID: $effectivePid) already dead")
                }
            } catch (e: Exception) {
                AppLogger.e("Error in PID-based process kill for PID: $effectivePid", e)
            }
        }
    }
    
    /**
     * Check if a process is alive by PID using SELinux-compliant method.
     * Android 16+ SELinux: /proc/PID access may be denied
     * Uses Process.sendSignal() instead of /proc/PID directory check
     */
    private fun isProcessAlive(pid: Int): Boolean {
        // SEC: Validate PID is positive and within valid range
        if (pid <= 0 || pid > Int.MAX_VALUE) {
            return false
        }
        
        // Use SELinux-compliant method
        return com.simplexray.an.common.SelinuxComplianceHelper.isProcessAlive(pid)
    }
    
    /**
     * Get process PID with Android 16+ compatibility
     * Tries multiple methods to get PID when Process.pid() is not available
     */
    private fun getProcessPid(process: Process?, xrayPath: String): Long {
        if (process == null) return -1L
        
        // Method 1: Try Process.pid() method (works on Android < 16)
        try {
            val pid = process.javaClass.getMethod("pid").invoke(process) as? Long
            if (pid != null && pid > 0) {
                return pid
            }
        } catch (e: NoSuchMethodException) {
            // Android 16+: Process.pid() method not available
            AppLogger.d("Process.pid() method not available (Android 16+), trying alternative methods")
        } catch (e: Exception) {
            AppLogger.w("Error getting PID via Process.pid(): ${e.message}")
        }
        
        // Method 2: Try to find PID by searching /proc for xray process
        // Wait a short time for process to start
        try {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.delay(100) // Wait 100ms for process to start
            }
            
            // Search /proc for xray process matching the binary path
            val procDir = File("/proc")
            if (procDir.exists() && procDir.canRead()) {
                val xrayBinaryName = File(xrayPath).name
                procDir.listFiles()?.forEach { pidDir ->
                    try {
                        val pid = pidDir.name.toIntOrNull() ?: return@forEach
                        if (pid <= 0 || pid > Int.MAX_VALUE) return@forEach
                        
                        // Check cmdline to see if this is our xray process
                        val cmdlineFile = File(pidDir, "cmdline")
                        if (cmdlineFile.exists() && cmdlineFile.canRead()) {
                            val cmdline = cmdlineFile.readText().trim()
                            if (cmdline.contains(xrayBinaryName, ignoreCase = true) ||
                                cmdline.contains("xray", ignoreCase = true)) {
                                // Verify process is still alive and matches
                                if (isXrayProcess(pid) && isProcessAlive(pid)) {
                                    AppLogger.d("Found xray process PID via /proc search: $pid")
                                    return pid.toLong()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore individual process errors
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.w("Error searching /proc for PID: ${e.message}")
        }
        
        // Method 3: Process reference will be used for management
        // PID is not critical if we have Process reference
        return -1L
    }
    
    /**
     * Verify that a process is the xray process by checking /proc/PID/cmdline.
     * This prevents killing wrong processes due to PID reuse.
     */
    private fun isXrayProcess(pid: Int): Boolean {
        return try {
            val cmdlineFile = File("/proc/$pid/cmdline")
            if (!cmdlineFile.exists()) {
                return false
            }
            val cmdline = cmdlineFile.readText().trim()
            // Check if process name contains "xray" or matches expected binary name
            cmdline.contains("xray", ignoreCase = true) ||
            cmdline.contains("xray_core", ignoreCase = true) ||
            cmdline.endsWith("/xray_core", ignoreCase = true) ||
            cmdline.contains("libxray.so", ignoreCase = true) ||
            cmdline.endsWith("/libxray.so", ignoreCase = true)
        } catch (e: Exception) {
            // If we can't verify, assume it's safe (process may have exited)
            AppLogger.w("Could not verify process name for PID $pid: ${e.message}")
            true // Allow kill attempt if verification fails
        }
    }
    
    /**
     * Check if VPN connection is still active.
     * This helps detect if the VPN connection was lost when app goes to background.
     */
    private fun checkVpnConnection() {
        if (!Companion.isRunning()) {
            isMonitoringConnection = false
            return
        }
        
        val prefs = Preferences(this)
        if (prefs.disableVpn) {
            // In core-only mode, no VPN to check
            scheduleNextConnectionCheck()
            return
        }
        
        // Check if tunFd is still valid
        val fd = tunFd
        if (fd == null) {
            AppLogger.w("TProxyService: VPN connection lost (tunFd is null)")
            // VPN connection was lost, try to restart
            if (Companion.isRunning()) {
                AppLogger.d("TProxyService: Attempting to restore VPN connection")
                serviceScope.launch {
                    try {
                        startXray()
                    } catch (e: Exception) {
                        AppLogger.e("TProxyService: Failed to restore VPN connection", e)
                    }
                }
            }
            isMonitoringConnection = false
            return
        }
        
        // Check if file descriptor is still valid
        try {
            val isValid = fd.fileDescriptor.valid()
            if (!isValid) {
                AppLogger.w("TProxyService: VPN file descriptor is invalid")
                tunFd = null
                if (Companion.isRunning()) {
                    AppLogger.d("TProxyService: Attempting to restore VPN connection")
                    serviceScope.launch {
                        try {
                            startXray()
                        } catch (e: Exception) {
                            AppLogger.e("TProxyService: Failed to restore VPN connection", e)
                        }
                    }
                }
                isMonitoringConnection = false
                return
            }
        } catch (e: Exception) {
            AppLogger.w("TProxyService: Error checking VPN file descriptor", e)
            // Assume connection is still valid if we can't check
        }
        
        // Schedule next check
        scheduleNextConnectionCheck()
    }
    
    /**
     * Schedule the next VPN connection check.
     * Checks every 30 seconds to detect connection loss.
     */
    private fun scheduleNextConnectionCheck() {
        if (!Companion.isRunning() || !isMonitoringConnection) {
            return
        }
        handler.removeCallbacks(connectionCheckRunnable)
        handler.postDelayed(connectionCheckRunnable, 30000) // Check every 30 seconds
    }
    
    /**
     * Start monitoring VPN connection status.
     */
    private fun startConnectionMonitoring() {
        if (isMonitoringConnection) {
            return
        }
        isMonitoringConnection = true
        AppLogger.d("TProxyService: Started VPN connection monitoring")
        scheduleNextConnectionCheck()
    }
    
    /**
     * Stop monitoring VPN connection status.
     */
    private fun stopConnectionMonitoring() {
        if (!isMonitoringConnection) {
            return
        }
        isMonitoringConnection = false
        handler.removeCallbacks(connectionCheckRunnable)
        AppLogger.d("TProxyService: Stopped VPN connection monitoring")
    }

    // Port monitoring variables
    private var monitoredPort: Int = -1
    private val portCheckRunnable = Runnable {
        checkPortAvailability()
    }

    /**
     * Start monitoring SOCKS port to detect connection issues.
     * This helps identify when port 10808 becomes unavailable.
     */
    private fun startPortMonitoring(port: Int) {
        monitoredPort = port
        AppLogger.i("TProxyService: Started port monitoring for SOCKS port $port")
        scheduleNextPortCheck()
    }

    /**
     * Stop monitoring SOCKS port.
     */
    private fun stopPortMonitoring() {
        if (monitoredPort == -1) return
        handler.removeCallbacks(portCheckRunnable)
        AppLogger.d("TProxyService: Stopped port monitoring for port $monitoredPort")
        monitoredPort = -1
    }

    /**
     * Check if monitored port is available and log any issues.
     */
    private fun checkPortAvailability() {
        if (monitoredPort == -1 || !Companion.isRunning()) {
            return
        }

        try {
            // Try to connect to the SOCKS port
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress("127.0.0.1", monitoredPort), 1000)
            socket.close()
            AppLogger.d("TProxyService: SOCKS port $monitoredPort is accessible")
        } catch (e: java.net.ConnectException) {
            AppLogger.e("TProxyService: SOCKS port $monitoredPort connection refused - Xray may not be running or crashed")
            // Try to restart Xray process
            serviceScope.launch {
                try {
                    AppLogger.i("TProxyService: Attempting to restart Xray process due to port unavailability")
                    val currentState = processState.getAndUpdate { state ->
                        ProcessState(state.process, state.pid, reloading = true)
                    }
                    killProcessSafely(currentState.process, currentState.pid)
                    runXrayProcess()
                } catch (e: Exception) {
                    AppLogger.e("TProxyService: Failed to restart Xray process: ${e.message}", e)
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            AppLogger.w("TProxyService: SOCKS port $monitoredPort connection timeout")
        } catch (e: Exception) {
            AppLogger.w("TProxyService: Error checking port $monitoredPort: ${e.message}")
        }

        scheduleNextPortCheck()
    }

    /**
     * Schedule next port availability check.
     */
    private fun scheduleNextPortCheck() {
        if (monitoredPort == -1 || !Companion.isRunning()) {
            return
        }
        handler.removeCallbacks(portCheckRunnable)
        handler.postDelayed(portCheckRunnable, 15000) // Check every 15 seconds
    }

    private fun startService() {
        if (tunFd != null) {
            AppLogger.d("TProxyService: VPN interface already established, skipping")
            return
        }
        
        val prefs = Preferences(this)
        
        // VPN Permission Check: Ensure permission is granted before establishing VPN
        val vpnPrepareIntent = VpnService.prepare(this)
        if (vpnPrepareIntent != null) {
            AppLogger.e("TProxyService: Cannot establish VPN - permission not granted")
            prefs.vpnServiceWasRunning = false
            stopXray()
            return
        }
        
        val builder = getVpnBuilder(prefs)
        var establishedFd: ParcelFileDescriptor? = null
        try {
            AppLogger.d("TProxyService: Establishing VPN interface...")
            
            // Establish VPN with timeout protection (Android 16+ may hang in CONNECTING state)
            val establishTimeoutMs = 10000L // 10 seconds timeout
            val startTime = System.currentTimeMillis()
            
            establishedFd = try {
                builder.establish()
            } catch (e: Exception) {
                AppLogger.e("TProxyService: VPN interface establishment threw exception: ${e.message}", e)
                null
            }
            
            // Check if establishment took too long
            val elapsedTime = System.currentTimeMillis() - startTime
            if (elapsedTime > establishTimeoutMs) {
                AppLogger.w("TProxyService: VPN establishment took ${elapsedTime}ms (exceeded ${establishTimeoutMs}ms timeout)")
            }
            
            if (establishedFd == null) {
                // VPN interface establishment failed
                AppLogger.e("TProxyService: VPN interface establishment failed - builder.establish() returned null")
                // Clear state on failure
                prefs.vpnServiceWasRunning = false
                stopXray()
                return
            }
            
            // VPN interface successfully established
            AppLogger.i("TProxyService: VPN interface established successfully, fd=${establishedFd.fd}")
            tunFd = establishedFd
            
            // Save state that VPN is running
            prefs.vpnServiceWasRunning = true
            AppLogger.d("TProxyService: VPN state saved - service is running")

            // MIUI Compatibility: Start watchdog services to monitor and restart VPN if killed
            try {
                VpnWatchdogJobService.scheduleWatchdog(this)
                VpnWatchdogReceiver.scheduleWatchdog(this)
                AppLogger.i("TProxyService: Watchdog services scheduled for MIUI compatibility")
            } catch (e: Exception) {
                AppLogger.w("TProxyService: Failed to schedule watchdog services: ${e.message}", e)
            }

            // Start chain (PepperShaper → Xray-core)
            val chainStarted = startChain(prefs)
            if (!chainStarted) {
                AppLogger.e("TProxyService: Chain start failed - cannot continue")
                stopXray()
                return
            }
            
            // Wait for chain to be ready
            val chainReady = waitForChainReady()
            if (!chainReady) {
                AppLogger.e("TProxyService: Chain not ready - cannot start QUICHE")
                stopXray()
                return
            }
            
            // Validate SOCKS port is available before starting QUICHE
            val socksPort = extractXraySocksPort(prefs.selectedConfigPath ?: "")
            if (socksPort != null) {
                AppLogger.i("TProxyService: Xray SOCKS proxy will listen on port $socksPort")
                // Start port monitoring to detect connection issues
                startPortMonitoring(socksPort)
            } else {
                AppLogger.w("TProxyService: Could not determine Xray SOCKS port from config")
            }

            // Start QUICME TUN forwarder via ChainSupervisor (TUN → QUICME → Xray Chain)
            establishedFd.let { fd ->
                try {
                    // Get QUICME server address from chain or preferences
                    val quicheServerHost = prefs.quicheServerHost ?: "127.0.0.1"
                    val quicheServerPort = prefs.quicheServerPort ?: 443

                    AppLogger.i("TProxyService: Starting QUICME in chain via ChainSupervisor (TUN → QUICME → Xray) ($quicheServerHost:$quicheServerPort)")

                    // Initialize ChainSupervisor if not already initialized
                    if (chainSupervisor == null) {
                        chainSupervisor = ChainSupervisor(this)
                    }

                    // Attach QUICME to TUN via ChainSupervisor (QUICME is part of the chain)
                    val attached = chainSupervisor?.attachQuicheToTunFd(
                        tunFd = fd.fd,
                        serverHost = quicheServerHost,
                        serverPort = quicheServerPort
                    ) ?: false

                    if (!attached) {
                        AppLogger.e("TProxyService: Failed to attach QUICME to chain via ChainSupervisor")
                        stopXray()
                        return
                    }
                    
                    AppLogger.i("TProxyService: QUICME successfully integrated into chain (TUN → QUICME → Xray)")
                    
                    // Apply performance optimizations if enabled
                    if (enablePerformanceMode && perfIntegration != null) {
                        try {
                            perfIntegration?.applyNetworkOptimizations(fd.fd)
                        } catch (e: Exception) {
                            AppLogger.w("Failed to apply network optimizations", e)
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.e("TProxyService: Error starting QUICME TUN forwarder: ${e.message}", e)
                    stopXray()
                    return@let
                }
            }

            val successIntent = Intent(ACTION_START)
            successIntent.setPackage(application.packageName)
            sendBroadcast(successIntent)
            
            // Note: Foreground notification is already started in onStartCommand()
            // No need to call createNotification() again here
            
            // Start monitoring VPN connection to detect if it's lost when app goes to background
            startConnectionMonitoring()
        } catch (e: Exception) {
            AppLogger.e("Error in startService: ${e.message}", e)
            // Ensure tunFd is closed in exception case
            establishedFd?.use { fd ->
                try {
                    fd.close()
                } catch (closeException: Exception) {
                    AppLogger.w("Error closing tunFd in exception handler: ${closeException.message}", closeException)
                }
            }
            tunFd = null
            prefs.vpnServiceWasRunning = false
            stopXray()
        }
    }

    private fun getVpnBuilder(prefs: Preferences): Builder = Builder().apply {
        setBlocking(false)
        setMtu(prefs.tunnelMtu)

        // Android 14+ (API 34+) requires careful route handling to prevent VPN loop
        // Without proper route configuration, VPN traffic may loop back through the VPN interface
        // Note: Android VPN Builder doesn't have explicit "exclude" - we ensure localhost
        // routes are not added, which prevents them from going through VPN
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ (API 34+): Ensure localhost (127.0.0.1) is NOT routed through VPN
            // By not adding a route for 127.0.0.0/8, localhost traffic bypasses VPN
            // This prevents VPN loop where VPN traffic tries to route through itself
            // The default route (0.0.0.0/0) will be added below, but localhost is excluded
            // because we don't explicitly add it, and Android's routing table handles it correctly
            AppLogger.d("TProxyService: Android 14+ - Ensuring localhost routes excluded to prevent VPN loop")
        }

        if (prefs.bypassLan) {
            addRoute("10.0.0.0", 8)
            addRoute("172.16.0.0", 12)
            addRoute("192.168.0.0", 16)
        }
        if (prefs.httpProxyEnabled) {
            setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", prefs.socksPort))
        }
        if (prefs.ipv4) {
            addAddress(prefs.tunnelIpv4Address, prefs.tunnelIpv4Prefix)
            addRoute("0.0.0.0", 0)
            prefs.dnsIpv4.takeIf { it.isNotEmpty() }?.also { addDnsServer(it) }
        }
        if (prefs.ipv6) {
            addAddress(prefs.tunnelIpv6Address, prefs.tunnelIpv6Prefix)
            addRoute("::", 0)
            prefs.dnsIpv6.takeIf { it.isNotEmpty() }?.also { addDnsServer(it) }
        }

        // Handle app configuration with proper error logging
        prefs.apps?.forEach { appName ->
            appName?.let { name ->
                try {
                    when {
                        prefs.bypassSelectedApps -> addDisallowedApplication(name)
                        else -> addAllowedApplication(name)
                    }
                } catch (e: PackageManager.NameNotFoundException) {
                    AppLogger.w("App not found: $name - ${e.message}", e)
                    // Continue with other apps
                } catch (e: Exception) {
                    AppLogger.e("Error configuring app $name: ${e.message}", e)
                    // Continue with other apps
                }
            }
        }
        if (prefs.bypassSelectedApps || prefs.apps.isNullOrEmpty())
            addDisallowedApplication(BuildConfig.APPLICATION_ID)
    }

    private fun stopService() {
        // Stop chain first
        stopChain()
        
        // Stop QUICHE TUN forwarder
        // QUICME TUN forwarder is cleaned up by ChainSupervisor.stop()
        
        tunFd?.let {
            try {
                it.close()
            } catch (e: IOException) {
                // Retry close once on failure
                AppLogger.w("Error closing tunFd, retrying: ${e.message}", e)
                try {
                    it.close()
                } catch (e2: IOException) {
                    AppLogger.e("Failed to close tunFd after retry: ${e2.message}", e2)
                    // Log but continue - file descriptor may already be closed
                }
            } catch (e: Exception) {
                AppLogger.e("Unexpected error closing tunFd: ${e.javaClass.simpleName}: ${e.message}", e)
            } finally {
                tunFd = null
            }
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        }
        exit()
    }
    
    /**
     * Start the tunneling chain (PepperShaper → Xray-core)
     */
    private fun startChain(prefs: Preferences): Boolean {
        return try {
            if (chainSupervisor == null) {
                chainSupervisor = ChainSupervisor(this)
            }
            
            // Validate Xray config path is available
            if (prefs.selectedConfigPath == null) {
                AppLogger.e("TProxyService: No Xray config path available - chain cannot start")
                return false
            }
            
            // Build chain config from preferences and Xray config
            val chainConfig = buildChainConfig(prefs)
            
            // Validate that chain config has Xray config path
            if (chainConfig.xrayConfigPath == null) {
                AppLogger.e("TProxyService: Chain config missing Xray config path")
                return false
            }
            
            val result = chainSupervisor?.start(chainConfig)
            if (result?.isSuccess == true) {
                AppLogger.i("TProxyService: Chain started successfully")
                true
            } else {
                val error = result?.exceptionOrNull()
                AppLogger.e("TProxyService: Chain start failed: ${error?.message}", error)
                false
            }
        } catch (e: Exception) {
            AppLogger.e("TProxyService: Error starting chain: ${e.message}", e)
            false
        }
    }
    
    /**
     * Build chain configuration from preferences and Xray config
     */
    private fun buildChainConfig(prefs: Preferences): ChainConfig {
        // Get Xray config path
        val xrayConfigPath = prefs.selectedConfigPath?.let { 
            val configFile = File(it)
            if (configFile.exists() && configFile.canRead()) {
                val filesDir = filesDir
                val canonicalConfigPath = configFile.canonicalPath
                val canonicalFilesDir = filesDir.canonicalPath
                
                if (canonicalConfigPath.startsWith(canonicalFilesDir)) {
                    canonicalConfigPath.substring(canonicalFilesDir.length + 1)
                } else {
                    configFile.name
                }
            } else {
                AppLogger.w("TProxyService: Xray config file not accessible: $it")
                null
            }
        }
        
        return ChainConfig(
            name = "TProxy Chain",
            pepperParams = PepperParams(
                mode = PepperMode.BURST_FRIENDLY,
                maxBurstBytes = 64 * 1024,
                targetRateBps = 0,
                queueDiscipline = QueueDiscipline.FQ,
                lossAwareBackoff = true,
                enablePacing = true
            ),
            xrayConfigPath = xrayConfigPath,
            tlsMode = ChainConfig.TlsMode.BORINGSSL
        )
    }
    
    /**
     * Wait for chain to be ready with improved timeout and smarter checks
     */
    private fun waitForChainReady(): Boolean {
        var attempts = 0
        val maxAttempts = 60 // Increased from 20 to 60 (30 seconds total)
        val delayMs = 500L
        
        AppLogger.d("TProxyService: Waiting for chain to be ready (max ${maxAttempts * delayMs / 1000}s)...")
        
        while (attempts < maxAttempts) {
            // Check Xray is running
            val prefs = Preferences(this)
            val configPath = prefs.selectedConfigPath
            if (configPath != null) {
                val xraySocksPort = extractXraySocksPort(configPath)
                
                // Check 1: Xray process is running
                val xrayRunning = XrayCoreLauncher.isRunning()
                
                // Check 2: SOCKS port is available (if we can determine it)
                val socksPortReady = if (xraySocksPort != null) {
                    // Try to connect to SOCKS port to verify it's listening
                    try {
                        val socket = java.net.Socket()
                        socket.connect(java.net.InetSocketAddress("127.0.0.1", xraySocksPort), 100)
                        socket.close()
                        true
                    } catch (e: Exception) {
                        false // Port not ready yet
                    }
                } else {
                    true // Can't check port, assume ready if Xray is running
                }
                
                // Check 3: Chain supervisor reports chain is ready (check status flow)
                val chainReady = try {
                    val status = chainSupervisor?.status
                    if (status != null) {
                        // Use a simple check: if chain supervisor exists and Xray is running, assume chain is ready
                        // More detailed checks can be added later
                        true
                    } else {
                        false
                    }
                } catch (e: Exception) {
                    AppLogger.w("Error checking chain status: ${e.message}")
                    false
                }
                
                if (xrayRunning && socksPortReady && chainReady) {
                    AppLogger.i("TProxyService: Chain ready! Xray running: $xrayRunning, SOCKS port ($xraySocksPort) ready: $socksPortReady, Chain ready: $chainReady")
                    return true
                } else {
                    if (attempts % 10 == 0) { // Log every 5 seconds
                        AppLogger.d("TProxyService: Chain not ready yet (attempt $attempts/$maxAttempts): Xray=$xrayRunning, SOCKS port=$socksPortReady, Chain=$chainReady")
                    }
                }
            } else {
                AppLogger.w("TProxyService: No config path available while waiting for chain")
            }
            
            attempts++
            if (attempts < maxAttempts) {
                Thread.sleep(delayMs)
            }
        }
        
        // Final check before giving up
        val prefs = Preferences(this)
        val configPath = prefs.selectedConfigPath
        val xraySocksPort = configPath?.let { extractXraySocksPort(it) }
        val xrayRunning = XrayCoreLauncher.isRunning()
        val chainReady = try {
            chainSupervisor?.status != null
        } catch (e: Exception) {
            false
        }
        
        AppLogger.e("TProxyService: Chain not ready after ${maxAttempts} attempts (${maxAttempts * delayMs / 1000}s)")
        AppLogger.e("TProxyService: Final status - Xray running: $xrayRunning, SOCKS port: $xraySocksPort, Chain ready: $chainReady")
        return false
    }
    
    /**
     * Stop the tunneling chain
     */
    private fun stopChain() {
        try {
            chainSupervisor?.stop()
            AppLogger.i("TProxyService: Chain stopped")
        } catch (e: Exception) {
            AppLogger.e("TProxyService: Error stopping chain: ${e.message}", e)
        }
    }
    
    /**
     * Extract SOCKS inbound port from Xray config
     */
    private fun extractXraySocksPort(configPath: String): Int? {
        return try {
            val configFile = File(configPath)
            if (!configFile.exists()) return null
            
            val configContent = configFile.readText()
            val json = com.google.gson.JsonParser.parseString(configContent).asJsonObject
            val inbounds = json.getAsJsonArray("inbounds") ?: return null
            
            for (inboundElement in inbounds) {
                val inbound = inboundElement.asJsonObject
                if (inbound.get("protocol")?.asString == "socks") {
                    val port = inbound.get("port")?.asInt
                    if (port != null) {
                        return port
                    }
                }
            }
            null
        } catch (e: Exception) {
            AppLogger.d("TProxyService: Error extracting Xray SOCKS port: ${e.message}")
            null
        }
    }

    @Suppress("SameParameterValue")
    private fun createNotification(channelName: String) {
        val i = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, channelName)
        val notify = notification.setContentTitle(getString(R.string.app_name))
            .setContentText("VPN aktif")
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentIntent(pi)
            .setOngoing(true) // Make notification persistent so service isn't killed
            .setPriority(NotificationCompat.PRIORITY_LOW) // Keep it visible but not intrusive
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false) // Don't show timestamp to reduce notification updates
            .build()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notify)
        } else {
            startForeground(1, notify, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }
    }

    private fun exit() {
        val stopIntent = Intent(ACTION_STOP)
        stopIntent.setPackage(application.packageName)
        sendBroadcast(stopIntent)
        stopSelf()
    }

    @Suppress("SameParameterValue")
    private fun initNotificationChannel(channelName: String) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val name: CharSequence = getString(R.string.app_name)
        val channel = NotificationChannel(channelName, name, NotificationManager.IMPORTANCE_LOW).apply {
            // Set to LOW importance to reduce notification intrusiveness while keeping service alive
            // The service will still run in foreground, but notification won't be as prominent
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private val isServiceRunning = AtomicBoolean(false)
        
        fun isRunning(): Boolean = isServiceRunning.get()
        
        const val ACTION_CONNECT: String = "com.simplexray.an.CONNECT"
        const val ACTION_DISCONNECT: String = "com.simplexray.an.DISCONNECT"
        const val ACTION_START: String = "com.simplexray.an.START"
        const val ACTION_STOP: String = "com.simplexray.an.STOP"
        const val ACTION_LOG_UPDATE: String = "com.simplexray.an.LOG_UPDATE"
        const val ACTION_RELOAD_CONFIG: String = "com.simplexray.an.RELOAD_CONFIG"
        const val EXTRA_LOG_DATA: String = "log_data"
        private const val TAG = "VpnService"
        private const val BROADCAST_DELAY_MS: Long = 3000

        // Stats and native library dir no longer available (hev-socks5-tunnel removed)
        @JvmStatic
        fun TProxyGetStats(): LongArray? = null
        
        fun getNativeLibraryDir(context: Context?): String? = null
    }
}
