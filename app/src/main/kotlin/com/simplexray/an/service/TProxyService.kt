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

    override fun onCreate() {
        super.onCreate()
        isServiceRunning.set(true)
        logFileManager = LogFileManager(this)
        
        // Initialize performance optimizations if enabled
        // Add configuration validation before initializing performance mode
        if (enablePerformanceMode) {
            try {
                perfIntegration = PerformanceIntegration(this)
                perfIntegration?.initialize()
                AppLogger.d("Performance mode enabled")
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
        
        AppLogger.d("TProxyService created.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle null intent (service restart after being killed by system)
        if (intent == null) {
            AppLogger.w("TProxyService: Restarted with null intent, attempting state recovery")
            val prefs = Preferences(this)
            
            // Check if VPN was running before process death
            if (prefs.vpnServiceWasRunning) {
                AppLogger.d("TProxyService: VPN was running before restart, attempting recovery")
                
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
                                val channelName = if (prefs.disableVpn) "nosocks" else "socks5"
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
                    // Even in core-only mode, ensure foreground notification is shown
                    // This prevents the service from being killed when app goes to background
                    @Suppress("SameParameterValue") val channelName = "nosocks"
                    initNotificationChannel(channelName)
                    createNotification(channelName)
                    
                    // Start monitoring even in core-only mode (to detect service issues)
                    startConnectionMonitoring()
                    
                    // Save state for core-only mode too
                    prefs.vpnServiceWasRunning = true
                    
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
        AppLogger.d("TProxyService: App task removed, ensuring service continues")
        // Don't stop the service - let it continue running in background
        // The service will keep running even when app is removed from recent apps
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning.set(false)
        
        // Stop connection monitoring
        stopConnectionMonitoring()
        
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
        AppLogger.d("TProxyService destroyed.")
        // Removed exitProcess(0) - let Android handle service lifecycle properly
        // exitProcess() forcefully kills the entire app process which prevents proper cleanup
    }

    override fun onRevoke() {
        AppLogger.w("TProxyService: VPN connection revoked by system")
        // Clear state that VPN is running
        val prefs = Preferences(this)
        prefs.vpnServiceWasRunning = false
        // VPN was revoked, stop the service
        stopXray()
        super.onRevoke()
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
            try {
                processPid = currentProcess.javaClass.getMethod("pid").invoke(currentProcess) as? Long ?: -1L
                AppLogger.i("Xray process started successfully with PID: $processPid")
            } catch (e: Exception) {
                AppLogger.w("Could not get process PID", e)
                processPid = -1L
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
            InputStreamReader(inputStream).use { isr ->
                BufferedReader(isr).use { reader ->
                    var line: String?
                    AppLogger.d("Reading xray process output.")
                    // Use a timeout mechanism for readLine() to prevent indefinite blocking
                    // BUG: Timeout check happens before read, not during - readLine() can still block
                    val timeoutMs = 30000L // 30 seconds timeout
                    val startTime = System.currentTimeMillis()
                    while (true) {
                        // Check timeout before reading
                        if (System.currentTimeMillis() - startTime > timeoutMs) {
                            AppLogger.w("Timeout reading xray process output")
                            break
                        }
                        // Try to read with timeout protection
                        try {
                            line = reader.readLine() ?: break
                        } catch (e: java.io.InterruptedIOException) {
                            AppLogger.d("Reading interrupted")
                            break
                        } catch (e: java.io.IOException) {
                            // Check if timeout occurred
                            if (e.message?.contains("timeout", ignoreCase = true) == true ||
                                e.message?.contains("timed out", ignoreCase = true) == true) {
                                AppLogger.w("Timeout reading xray process output")
                                break
                            }
                            throw e
                        }
                        line?.let {
                            logFileManager.appendLog(it)
                            synchronized(logBroadcastBuffer) {
                                // Prevent unbounded growth
                                if (logBroadcastBuffer.size >= MAX_LOG_BUFFER_SIZE) {
                                    logBroadcastBuffer.removeAt(0) // Remove oldest
                                }
                                logBroadcastBuffer.add(it)
                                if (!handler.hasCallbacks(broadcastLogsRunnable)) {
                                    handler.postDelayed(broadcastLogsRunnable, BROADCAST_DELAY_MS)
                                }
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
        val environment = processBuilder.environment()
        
        // Set xray-specific environment variables
        environment["XRAY_LOCATION_ASSET"] = filesDir.path
        
        // Restrict filesystem access to prevent SELinux denials
        // Set HOME and TMPDIR to app-accessible directories to prevent system directory probing
        // Also prevents access to tests directories (shell_test_data_file context)
        environment["HOME"] = filesDir.path
        environment["TMPDIR"] = cacheDir.path
        environment["TMP"] = cacheDir.path
        // Ensure BORINGSSL_TEST_DATA_ROOT is not set to prevent test data access
        // Test data should only be accessed in test builds, not production
        environment.remove("BORINGSSL_TEST_DATA_ROOT")
        
        processBuilder.directory(filesDir)
        processBuilder.redirectErrorStream(true)
        return processBuilder
    }

    private fun stopXray() {
        AppLogger.d("stopXray called with keepExecutorAlive=" + false)
        
        // Clear state that VPN is running
        val prefs = Preferences(this)
        prefs.vpnServiceWasRunning = false
        AppLogger.d("TProxyService: VPN state cleared - service is stopping")
        
        // Stop connection monitoring
        stopConnectionMonitoring()
        
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
            // Try to get PID from Process reference
            try {
                proc?.javaClass?.getMethod("pid")?.invoke(proc) as? Long ?: -1L
            } catch (e: Exception) {
                -1L
            }
        }
        
        if (effectivePid == -1L) {
            AppLogger.w("Cannot kill process: no valid PID or Process reference")
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
                    // Verify this is still the xray process (prevents PID reuse race condition)
                    if (!isXrayProcess(effectivePid.toInt())) {
                        AppLogger.w("Process (PID: $effectivePid) does not appear to be xray process. Skipping kill.")
                        return
                    }
                    
                    AppLogger.d("Killing process by PID: $effectivePid (Process reference unavailable or invalid)")
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
     * Check if a process is alive by PID.
     * Uses /proc/PID directory existence check.
     */
    private fun isProcessAlive(pid: Int): Boolean {
        // SEC: Validate PID is positive and within valid range
        if (pid <= 0 || pid > Int.MAX_VALUE) {
            return false
        }
        return try {
            // Check /proc/PID directory exists
            // SEC: Path traversal risk mitigated by PID validation above
            File("/proc/$pid").exists()
        } catch (e: Exception) {
            // If we can't check, return false to avoid unnecessary kill attempts
            AppLogger.w("Error checking process alive status for PID $pid", e)
            false
        }
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
            cmdline.endsWith("/xray_core", ignoreCase = true)
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
        // BUG: FileDescriptor.valid() may not be reliable on all Android versions
        // UPGRADE-RISK: FileDescriptor.valid() behavior may change in future Android versions
        // TEST-GAP: File descriptor validation not tested across Android versions
        try {
            val isValid = fd.fileDescriptor.valid()
            if (!isValid) {
                AppLogger.w("TProxyService: VPN file descriptor is invalid")
                tunFd = null
                if (Companion.isRunning()) {
                    AppLogger.d("TProxyService: Attempting to restore VPN connection")
                    // BUG: Race condition - service may stop between check and launch
                    serviceScope.launch {
                        try {
                            startXray()
                        } catch (e: Exception) {
                            // BUG: Exception swallowed - may hide critical errors
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
            // BUG: Assumes valid on error - may miss connection loss
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

    private fun startService() {
        if (tunFd != null) return
        val prefs = Preferences(this)
        val builder = getVpnBuilder(prefs)
        var establishedFd: ParcelFileDescriptor? = null
        try {
            establishedFd = builder.establish()
            if (establishedFd == null) {
                // Clear state on failure
                prefs.vpnServiceWasRunning = false
                stopXray()
                return
            }
            
            tunFd = establishedFd
            
            // Save state that VPN is running
            prefs.vpnServiceWasRunning = true
            AppLogger.d("TProxyService: VPN state saved - service is running")
            val tproxyFile = File(cacheDir, "tproxy.conf")
            try {
                tproxyFile.createNewFile()
                FileOutputStream(tproxyFile, false).use { fos ->
                    val tproxyConf = getTproxyConf(prefs)
                    fos.write(tproxyConf.toByteArray())
                }
            } catch (e: IOException) {
                AppLogger.e(e.toString())
                stopXray()
                return
            }
            
            establishedFd.fd.let { fd ->
                NativeBridgeManager.startTProxyService(tproxyFile.absolutePath, fd)
                
                // Apply performance optimizations if enabled
                if (enablePerformanceMode && perfIntegration != null) {
                    try {
                        perfIntegration?.applyNetworkOptimizations(fd)
                    } catch (e: Exception) {
                        AppLogger.w("Failed to apply network optimizations", e)
                    }
                }
            }

            val successIntent = Intent(ACTION_START)
            successIntent.setPackage(application.packageName)
            sendBroadcast(successIntent)
            @Suppress("SameParameterValue") val channelName = "socks5"
            initNotificationChannel(channelName)
            createNotification(channelName)
            
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
            // Safe JNI call with error handling
            NativeBridgeManager.stopTProxyService()
        }
        exit()
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

        // Delegate to NativeBridgeManager for JNI operations
        @JvmStatic
        fun TProxyGetStats(): LongArray? = NativeBridgeManager.getTProxyStats()
        
        fun getNativeLibraryDir(context: Context?): String? = NativeBridgeManager.getNativeLibraryDir(context)

        private fun getTproxyConf(prefs: Preferences): String {
            var tproxyConf = """misc:
  task-stack-size: ${prefs.taskStackSize}
tunnel:
  mtu: ${prefs.tunnelMtu}
"""
            tproxyConf += """socks5:
  port: ${prefs.socksPort}
  address: '${prefs.socksAddress}'
  udp: '${if (prefs.udpInTcp) "tcp" else "udp"}'
"""
            if (prefs.socksUsername.isNotEmpty() && prefs.socksPassword.isNotEmpty()) {
                tproxyConf += "  username: '" + prefs.socksUsername + "'\n"
                tproxyConf += "  password: '" + prefs.socksPassword + "'\n"
            }
            return tproxyConf
        }
    }
}
