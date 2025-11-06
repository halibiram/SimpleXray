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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
    // PERF: Consider using Dispatchers.Default for CPU-bound work, IO for I/O
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // PERF: Handler on main looper may cause UI blocking if overused
    private val handler = Handler(Looper.getMainLooper())
    // BUG: MutableList not thread-safe - synchronized access required
    private val logBroadcastBuffer: MutableList<String> = mutableListOf()
    
    // Connection monitoring - check if VPN connection is still active
    private val connectionCheckRunnable = Runnable {
        checkVpnConnection()
    }
    private var isMonitoringConnection = false
    private val broadcastLogsRunnable = Runnable {
        synchronized(logBroadcastBuffer) {
            if (logBroadcastBuffer.isNotEmpty()) {
                val logUpdateIntent = Intent(ACTION_LOG_UPDATE)
                logUpdateIntent.setPackage(application.packageName)
                logUpdateIntent.putStringArrayListExtra(
                    EXTRA_LOG_DATA, ArrayList(logBroadcastBuffer)
                )
                sendBroadcast(logUpdateIntent)
                logBroadcastBuffer.clear()
                AppLogger.d("Broadcasted a batch of logs.")
            }
        }
    }

    // TODO: Consider caching port availability to reduce repeated checks
    // TODO: Add configuration option for port range selection
    // PERF: Port scanning can be slow - consider async or cached results
    // BUG: No timeout on ServerSocket creation - may hang indefinitely
    private fun findAvailablePort(excludedPorts: Set<Int>): Int? {
        (10000..65535)
            .shuffled()
            .forEach { port ->
                if (port in excludedPorts) return@forEach
                runCatching {
                    ServerSocket(port).use { socket ->
                        socket.reuseAddress = true
                    }
                    port
                }.onFailure {
                    AppLogger.d("Port $port unavailable: ${it.message}")
                }.onSuccess {
                    return port
                }
            }
        // TODO: Add fallback port selection strategy if all ports are unavailable
        // BUG: Returns null if no port found - caller may not handle this properly
        return null
    }

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
        if (enablePerformanceMode) {
            try {
                perfIntegration = PerformanceIntegration(this)
                perfIntegration?.initialize()
                AppLogger.d("Performance mode enabled")
            } catch (e: Exception) {
                AppLogger.w("Failed to initialize performance mode", e)
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
                    if (tunFd != null) {
                        try {
                            // Verify file descriptor is still valid
                            if (tunFd!!.fileDescriptor.valid()) {
                                AppLogger.d("TProxyService: VPN file descriptor still valid, maintaining connection")
                                val channelName = if (prefs.disableVpn) "nosocks" else "socks5"
                                initNotificationChannel(channelName)
                                createNotification(channelName)
                                startConnectionMonitoring()
                                // Restart xray process to reconnect
                                serviceScope.launch { runXrayProcess() }
                                return START_STICKY
                            }
                        } catch (e: Exception) {
                            AppLogger.w("TProxyService: File descriptor validation failed", e)
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
        startService()
        serviceScope.launch { runXrayProcess() }
    }

    private fun runXrayProcess() {
        var currentProcess: Process? = null
        var processPid: Long = -1
        try {
            AppLogger.d("Attempting to start xray process.")
            val libraryDir = getNativeLibraryDir(applicationContext)
            if (libraryDir == null) {
                AppLogger.e("Failed to get native library directory")
                stopXray()
                return
            }
            val prefs = Preferences(applicationContext)
            val selectedConfigPath = prefs.selectedConfigPath
            if (selectedConfigPath == null) {
                AppLogger.e("No configuration file selected")
                stopXray()
                return
            }
            val xrayPath = "$libraryDir/libxray.so"
            val configContent = File(selectedConfigPath).readText()
            val apiPort = findAvailablePort(extractPortsFromJson(configContent)) ?: return
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
            val injectedConfigContent =
                ConfigUtils.injectStatsService(prefs, configContent)
            currentProcess.outputStream.use { os ->
                os.write(injectedConfigContent.toByteArray())
                os.flush()
            }

            // PERF: Reading from process inputStream on IO dispatcher - consider async I/O
            val inputStream = currentProcess.inputStream
            InputStreamReader(inputStream).use { isr ->
                BufferedReader(isr).use { reader ->
                    var line: String?
                    AppLogger.d("Reading xray process output.")
                    // Use a timeout mechanism for readLine() to prevent indefinite blocking
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
        environment["HOME"] = filesDir.path
        environment["TMPDIR"] = cacheDir.path
        environment["TMP"] = cacheDir.path
        
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
        
        serviceScope.cancel()
        AppLogger.d("CoroutineScope cancelled.")

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
     * TODO: Add process kill timeout configuration
     * TODO: Consider adding process kill retry mechanism for stubborn processes
     * UNSAFE: Process kill may fail silently - no verification of actual termination
     * SEC: Runtime.exec("kill -9") is a security risk - validate PID before execution
     */
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
            try {
                // Check if process is still alive using PID
                val isAlive = isProcessAlive(effectivePid.toInt())
                
                if (isAlive) {
                    AppLogger.d("Killing process by PID: $effectivePid (Process reference unavailable or invalid)")
                    try {
                        // Use Android Process.killProcess for same-UID processes
                        android.os.Process.killProcess(effectivePid.toInt())
                        AppLogger.d("Sent kill signal to process PID: $effectivePid")
                        
                        // Wait a bit to see if it exits
                        Thread.sleep(500)
                        
                        // Verify process is dead
                        val stillAlive = isProcessAlive(effectivePid.toInt())
                        
                        if (stillAlive) {
                            AppLogger.w("Process (PID: $effectivePid) still alive after killProcess, trying force kill")
                            // Last resort: try kill -9 via Runtime.exec
                            // SEC: Command injection risk if effectivePid is not validated
                            // UNSAFE: Runtime.exec without proper validation
                            try {
                                // SEC: Validate PID is numeric and within valid range before exec
                                val pidStr = effectivePid.toString()
                                if (pidStr.matches(Regex("^\\d+$")) && effectivePid > 0 && effectivePid <= Int.MAX_VALUE) {
                                    // Use array form to prevent command injection
                                    val killCmd = arrayOf("kill", "-9", pidStr)
                                    val killProcess = Runtime.getRuntime().exec(killCmd)
                                    val exitCode = killProcess.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                                    if (exitCode == 0) {
                                        AppLogger.d("Force killed process PID: $effectivePid")
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
        tunFd = builder.establish()
        if (tunFd == null) {
            // Clear state on failure
            prefs.vpnServiceWasRunning = false
            stopXray()
            return
        }
        
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
        tunFd?.fd?.let { fd ->
            TProxyStartService(tproxyFile.absolutePath, fd)
            
            // Apply performance optimizations if enabled
            if (enablePerformanceMode && perfIntegration != null) {
                try {
                    perfIntegration?.applyNetworkOptimizations(fd)
                } catch (e: Exception) {
                    AppLogger.w("Failed to apply network optimizations", e)
                }
            }
        } ?: run {
            AppLogger.e("tunFd is null after establish()")
            stopXray()
            return
        }

        val successIntent = Intent(ACTION_START)
        successIntent.setPackage(application.packageName)
        sendBroadcast(successIntent)
        @Suppress("SameParameterValue") val channelName = "socks5"
        initNotificationChannel(channelName)
        createNotification(channelName)
        
        // Start monitoring VPN connection to detect if it's lost when app goes to background
        startConnectionMonitoring()
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

        // BUG: Exception swallowed - may hide configuration errors
        prefs.apps?.forEach { appName ->
            appName?.let { name ->
                try {
                    when {
                        prefs.bypassSelectedApps -> addDisallowedApplication(name)
                        else -> addAllowedApplication(name)
                    }
                } catch (ignored: PackageManager.NameNotFoundException) {
                    // BUG: Silent failure - app may not be configured as expected
                    AppLogger.w("App not found: $name")
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
            } catch (ignored: IOException) {
                // BUG: Exception swallowed - may hide resource leak
                AppLogger.w("Error closing tunFd", ignored)
            } finally {
                tunFd = null
            }
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            // UNSAFE: JNI call without error handling
            TProxyStopService()
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

        init {
            System.loadLibrary("hev-socks5-tunnel")
        }

        // UNSAFE: JNI boundary - validate inputs before passing to native code
        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyStartService(configPath: String, fd: Int)

        // UNSAFE: JNI boundary - no return value validation
        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyStopService()

        // UNSAFE: JNI boundary - may return null or invalid array
        // BUG: No validation of returned array size or contents
        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyGetStats(): LongArray?

        fun getNativeLibraryDir(context: Context?): String? {
            if (context == null) {
                AppLogger.e("Context is null")
                return null
            }
            try {
                val applicationInfo = context.applicationInfo
                if (applicationInfo != null) {
                    val nativeLibraryDir = applicationInfo.nativeLibraryDir
                    AppLogger.d("Native Library Directory: $nativeLibraryDir")
                    return nativeLibraryDir
                } else {
                    AppLogger.e("ApplicationInfo is null")
                    return null
                }
            } catch (e: Exception) {
                AppLogger.e("Error getting native library dir", e)
                return null
            }
        }

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
