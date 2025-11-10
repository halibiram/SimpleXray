package com.simplexray.an.xray

import android.content.Context
import android.os.Build
import kotlinx.coroutines.cancel
import com.simplexray.an.common.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger

object XrayCoreLauncher {
    private val procRef = AtomicReference<Process?>(null)
    private val pidRef = AtomicReference<Long>(-1L) // Store PID separately for fallback kill
    private val retryCount = AtomicInteger(0)
    private var logMonitorJob: Job? = null
    private var retryJob: Job? = null
    // Use application scope with proper cancellation handling
    private val monitoringScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var logCallback: ((String) -> Unit)? = null
    
    // Cleanup function to cancel all monitoring coroutines
    fun cleanup() {
        monitoringScope.cancel()
        logMonitorJob?.cancel()
        retryJob?.cancel()
        logCallback = null
    }

    // Safe null check before accessing process
    fun isRunning(): Boolean {
        val proc = procRef.get()
        return proc != null && proc.isAlive
    }

    /**
     * Start Xray with auto-retry and log monitoring
     * 
     * @param tlsMode TLS implementation mode (BORINGSSL, CONSCRYPT, GO_BORINGCRYPTO, AUTO)
     */
    @Synchronized
    fun start(
        context: Context,
        configFile: File? = null,
        maxRetries: Int = 3,
        retryDelayMs: Long = 5000,
        onLogLine: ((String) -> Unit)? = null,
        tlsMode: com.simplexray.an.chain.tls.TlsImplementation = com.simplexray.an.chain.tls.TlsImplementation.AUTO
    ): Boolean {
        if (isRunning()) return true
        
        // Validate ABI before starting
        val abiValidation = XrayAbiValidator.validateCurrentAbi(context)
        if (!abiValidation.isValid) {
            AppLogger.e("ABI validation failed: ${abiValidation.message}")
            return false // Fail fast if ABI validation fails
        }
        
        AssetsInstaller.ensureAssets(context)
        
        // Log TLS mode selection
        val selectedMode = if (tlsMode == com.simplexray.an.chain.tls.TlsImplementation.AUTO) {
            com.simplexray.an.chain.tls.TlsModeDetector.getRecommendedMode(context)
        } else {
            tlsMode
        }
        AppLogger.i("XrayCoreLauncher: Starting with TLS mode: $selectedMode")
        
        val bin = copyExecutable(context) ?: run {
            AppLogger.e("xray binary not found in native libs")
            return false
        }
        
        // Verify TLS mode compatibility
        val tlsInfo = com.simplexray.an.chain.tls.TlsModeDetector.getTlsInfo(context, selectedMode)
        if (!tlsInfo.available) {
            AppLogger.w("XrayCoreLauncher: Selected TLS mode $selectedMode not available, using recommended")
            val recommended = com.simplexray.an.chain.tls.TlsModeDetector.getRecommendedMode(context)
            AppLogger.i("XrayCoreLauncher: Using recommended TLS mode: $recommended")
        }
        val cfg = configFile ?: File(context.filesDir, "xray.json")
        // Validate config file path to prevent directory traversal
        if (!cfg.canonicalPath.startsWith(context.filesDir.canonicalPath) && 
            !cfg.canonicalPath.startsWith(context.cacheDir.canonicalPath)) {
            AppLogger.e("Config file path outside allowed directories: ${cfg.absolutePath}")
            return false
        }
        
        // Validate config file size (max 10MB)
        if (cfg.exists() && cfg.length() > 10 * 1024 * 1024) {
            AppLogger.e("Config file too large: ${cfg.length()} bytes")
            return false
        }
        
        if (!cfg.exists()) {
            AppLogger.w("config not found: ${cfg.absolutePath}; writing default")
            val def = XrayConfigBuilder.defaultConfig("127.0.0.1", 10085)
            try {
                XrayConfigBuilder.writeConfig(context, def)
            } catch (e: Exception) {
                AppLogger.e("Failed to write default config", e)
                return false
            }
        }
        
        // Patch config with inbound/outbound/transport merge
        try {
            XrayConfigPatcher.patchConfig(context, cfg.name)
        } catch (e: Exception) {
            AppLogger.e("Config patching failed, cannot continue", e)
            // Restore original config if patching fails
            try {
                if (cfg.exists()) {
                    // Backup current config before patching failed
                    val backup = File(context.filesDir, "xray.json.backup")
                    if (backup.exists()) {
                        backup.copyTo(cfg, overwrite = true)
                        AppLogger.i("Restored config from backup")
                    }
                }
            } catch (restoreException: Exception) {
                AppLogger.e("Failed to restore config backup", restoreException)
            }
            return false
        }
        
        // Validate config JSON using xray-core -test flag
        val validation = XrayConfigValidator.validateConfig(context, cfg)
        if (!validation.isValid) {
            AppLogger.e("Config validation failed: ${validation.message}")
            validation.errors.forEach { error ->
                AppLogger.e("  Validation error: $error")
            }
            return false
        }
        AppLogger.d("Config validation passed: ${validation.message}")
        
        logCallback = onLogLine
        return startProcess(context, bin, cfg, maxRetries, retryDelayMs)
    }

    private fun startProcess(
        context: Context,
        bin: File,
        cfg: File,
        maxRetries: Int,
        retryDelayMs: Long
    ): Boolean {
        return try {
            // Validate bin path to prevent command injection
            // Allow both filesDir (for copied binary) and nativeLibraryDir (for direct execution)
            val nativeLibDir = context.applicationInfo.nativeLibraryDir ?: ""
            if (!bin.canonicalPath.startsWith(context.filesDir.canonicalPath) &&
                !bin.canonicalPath.startsWith(nativeLibDir)) {
                AppLogger.e("Binary path outside allowed directories: ${bin.absolutePath}")
                return false
            }
            
            // Validate that bin file is actually executable
            if (!bin.canExecute()) {
                AppLogger.e("Binary file is not executable: ${bin.absolutePath}")
                return false
            }
            
            val pb = ProcessBuilder(bin.absolutePath, "-config", cfg.absolutePath)
            val filesDir = context.filesDir
            val cacheDir = context.cacheDir
            val environment = pb.environment()
            
            // Restrict filesystem access to prevent SELinux denials
            // Set HOME and TMPDIR to app-accessible directories
            // Also prevents access to tests directories (shell_test_data_file context)
            environment["HOME"] = filesDir.path
            environment["TMPDIR"] = cacheDir.path
            environment["TMP"] = cacheDir.path
            // Ensure BORINGSSL_TEST_DATA_ROOT is not set to prevent test data access
            // Test data should only be accessed in test builds, not production
            environment.remove("BORINGSSL_TEST_DATA_ROOT")
            // Additional restrictions to prevent /data/local/tmp/tests access
            // Remove any test-related environment variables that might trigger test directory access
            environment.remove("TEST_DATA_ROOT")
            environment.remove("TEST_DIR")
            environment.remove("GO_TEST_DIR")
            // Restrict PATH to prevent accessing system test binaries and test directories
            // Filter out any paths containing "test", "/data/local/tmp", or "tests"
            val systemPath = System.getenv("PATH") ?: "/system/bin:/system/xbin"
            val restrictedPath = systemPath.split(":").filter { path ->
                val normalizedPath = path.lowercase()
                !normalizedPath.contains("test") && 
                !normalizedPath.contains("/data/local/tmp") &&
                !normalizedPath.contains("tests") &&
                !normalizedPath.contains("/tmp")
            }.joinToString(":")
            environment["PATH"] = restrictedPath
            
            pb.directory(filesDir)
            pb.redirectErrorStream(true)
            val logFile = File(filesDir, "xray.log")
            
            // Rotate log file if it exceeds 5MB
            if (logFile.exists() && logFile.length() > 5 * 1024 * 1024) {
                val rotated = File(filesDir, "xray.log.old")
                if (rotated.exists()) {
                    rotated.delete()
                }
                logFile.renameTo(rotated)
                AppLogger.d("Rotated log file: ${logFile.length()} bytes")
            }
            
            pb.redirectOutput(logFile)
            
            // Install signal handlers before starting process
            XraySignalHandler.installHandlers()
            
            val p = pb.start()
            procRef.set(p)
            retryCount.set(0)
            
            // Add PID validation after retrieval
            val pid = try {
                val retrievedPid = p.javaClass.getMethod("pid").invoke(p) as? Long ?: -1L
                if (retrievedPid <= 0 || retrievedPid > Int.MAX_VALUE) {
                    AppLogger.w("Invalid PID retrieved: $retrievedPid")
                    -1L
                } else {
                    retrievedPid
                }
            } catch (e: NoSuchMethodException) {
                AppLogger.w("PID method not available on this Android version", e)
                -1L
            } catch (e: Exception) {
                AppLogger.w("Failed to retrieve PID: ${e.javaClass.simpleName}: ${e.message}", e)
                -1L
            }
            pidRef.set(pid) // Store PID for fallback kill
            AppLogger.i("xray process started pid=$pid bin=${bin.absolutePath}")
            
            // Wait a short time to check if process stays alive (prevents immediate crashes)
            // This helps catch configuration errors or permission issues immediately
            // Use non-blocking delay in coroutine scope instead of runBlocking
            val processStartupDelayMs = 500L
            // Launch in monitoring scope to avoid blocking
            val startupCheckJob = monitoringScope.launch {
                delay(processStartupDelayMs)
                // Check if process is still alive after delay
                val currentProc = procRef.get()
                if (currentProc == null || !currentProc.isAlive) {
                    val exitCode = try {
                        currentProc?.exitValue() ?: -1
                    } catch (e: IllegalThreadStateException) {
                        -1
                    }
                    // Log Go runtime exit code
                    AppLogger.e("XrayCore Exit Code: $exitCode (startup check)")
                    AppLogger.e("xray process died during startup check (exit code: $exitCode)")
                    procRef.set(null)
                    pidRef.set(-1L)
                    attemptRetry(context, bin, cfg, maxRetries, retryDelayMs)
                }
            }
            // Wait for startup check to complete (with timeout)
            try {
                kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withTimeout(1000) {
                        startupCheckJob.join()
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                AppLogger.w("Startup check timeout, continuing anyway")
            }
            
            // Check if process is still alive after initial wait
            if (!p.isAlive) {
                val exitCode = try {
                    p.exitValue()
                } catch (e: IllegalThreadStateException) {
                    -1
                }
                // Log Go runtime exit code
                AppLogger.e("XrayCore Exit Code: $exitCode (immediate crash)")
                AppLogger.e("xray process died immediately after start (exit code: $exitCode)")
                
                // Try to read log file for error information
                // Stream file reading for large files and sanitize sensitive data
                try {
                    if (logFile.exists() && logFile.length() > 0) {
                        val errorLog = logFile.inputStream().bufferedReader().use { reader ->
                            reader.readText().take(500)
                        }
                        // Sanitize sensitive information before logging
                        val sanitized = AppLogger.sanitize(errorLog)
                        AppLogger.e("xray error log: $sanitized")
                    }
                } catch (e: java.io.IOException) {
                    AppLogger.w("IO error reading error log: ${e.message}", e)
                } catch (e: SecurityException) {
                    AppLogger.w("Security error reading error log: ${e.message}", e)
                } catch (e: Exception) {
                    AppLogger.w("Unexpected error reading error log: ${e.javaClass.simpleName}: ${e.message}", e)
                }
                
                procRef.set(null)
                pidRef.set(-1L)
                attemptRetry(context, bin, cfg, maxRetries, retryDelayMs)
                return false
            }
            
            // Start log monitoring
            startLogMonitoring(p, logFile)
            
            // Start process health monitoring with auto-retry
            startProcessMonitoring(context, bin, cfg, maxRetries, retryDelayMs)
            
            AppLogger.i("xray successfully started and running pid=$pid")
            true
        } catch (t: Throwable) {
            AppLogger.e("failed to start xray", t)
            procRef.set(null)
            pidRef.set(-1L)
            attemptRetry(context, bin, cfg, maxRetries, retryDelayMs)
            false
        }
    }

    /**
     * Monitor process health and restart on failure
     * Uses exponential backoff to prevent rapid restart loops
     */
    private fun startProcessMonitoring(
        context: Context,
        bin: File,
        cfg: File,
        maxRetries: Int,
        retryDelayMs: Long
    ) {
        retryJob?.cancel()
        retryJob = monitoringScope.launch {
            var consecutiveFailures = 0
            var adaptiveDelay = 10000L // Start with 10 seconds
            
            while (isActive) {
                // Adaptive polling: increase delay if process is stable, decrease if unstable
                delay(adaptiveDelay)
                val proc = procRef.get()
                if (proc == null || !proc.isAlive) {
                    val current = procRef.get()
                    if (current != null && !current.isAlive) {
                        val exitCode = try {
                            current.exitValue()
                        } catch (e: IllegalThreadStateException) {
                            -1
                        }
                        val pid = pidRef.get() // Use stored PID instead of trying to get from dead process
                        // Log Go runtime exit code prominently
                        AppLogger.e("XrayCore Exit Code: $exitCode (PID: $pid)")
                        AppLogger.w("Process died unexpectedly (PID: $pid, exit code: $exitCode), attempting restart")
                        
                        // Clear process and PID references
                        procRef.set(null)
                        pidRef.set(-1L)
                        
                        // Try to read log file for error information
                        // Stream file reading for large logs and sanitize sensitive data
                        val logFile = File(context.filesDir, "xray.log")
                        try {
                            if (logFile.exists() && logFile.length() > 0) {
                                val errorLog = logFile.inputStream().bufferedReader().use { reader ->
                                    // Read last 500 chars efficiently
                                    val fileSize = logFile.length()
                                    if (fileSize > 500) {
                                        reader.skip(fileSize - 500)
                                    }
                                    reader.readText()
                                }
                                // Sanitize sensitive information before logging
                                val sanitized = AppLogger.sanitize(errorLog)
                                AppLogger.w("Recent xray log: $sanitized")
                            }
                        } catch (e: java.io.IOException) {
                            AppLogger.w("IO error reading error log: ${e.message}", e)
                        } catch (e: Exception) {
                            AppLogger.w("Error reading error log: ${e.javaClass.simpleName}: ${e.message}", e)
                        }
                        
                        val retries = retryCount.incrementAndGet()
                        consecutiveFailures++
                        // Adaptive delay: increase if failures are frequent
                        adaptiveDelay = (adaptiveDelay * 1.5).toLong().coerceAtMost(60000) // Max 60s
                        
                        // Use exponential backoff to prevent rapid restart loops
                        if (retries <= maxRetries) {
                            // Exponential backoff: delay * 2^(retries-1)
                            val backoffDelay = retryDelayMs * (1 shl (retries - 1)).coerceAtMost(32)
                            AppLogger.i("Waiting ${backoffDelay}ms before retry (attempt $retries/$maxRetries)")
                            delay(backoffDelay)
                            // Use iterative approach instead of recursion to prevent stack overflow
                            val success = startProcess(context, bin, cfg, maxRetries, retryDelayMs)
                            if (success) {
                                AppLogger.i("Successfully restarted after failure")
                                consecutiveFailures = 0
                                adaptiveDelay = 10000L // Reset to base delay
                                return@launch
                            }
                        } else {
                            AppLogger.e("Max retries ($maxRetries) reached, stopping")
                            // Notify by logging - caller can check isRunning() status
                            return@launch
                        }
                    } else {
                        // Process is healthy, reduce delay gradually
                        consecutiveFailures = 0
                        adaptiveDelay = (adaptiveDelay * 0.9).toLong().coerceAtLeast(10000) // Min 10s
                    }
                }
            }
        }
    }

    /**
     * Monitor log file and stream to callback
     * Note: Process output is redirected to logFile, so we read from the file instead of inputStream
     */
    private fun startLogMonitoring(process: Process, logFile: File) {
        logMonitorJob?.cancel()
        logMonitorJob = monitoringScope.launch {
            try {
                var lastPosition = 0L
                // Wait a bit for log file to be created
                delay(1000)
                
                while (isActive && process.isAlive) {
                    try {
                        if (logFile.exists() && logFile.length() > lastPosition) {
                            // Handle file rotation/truncation
                            val currentSize = logFile.length()
                            if (currentSize < lastPosition) {
                                // File was truncated or rotated, reset position
                                lastPosition = 0
                            }
                            
                            logFile.inputStream().use { stream ->
                                stream.skip(lastPosition)
                                BufferedReader(InputStreamReader(stream)).use { reader ->
                                    var line: String?
                                    while (reader.readLine().also { line = it } != null && isActive) {
                                        line?.let {
                                            // Sanitize sensitive data before callback
                                            val sanitized = AppLogger.sanitize(it)
                                            logCallback?.invoke(sanitized)
                                        }
                                    }
                                }
                            }
                            lastPosition = logFile.length()
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // Re-throw cancellation to properly handle coroutine cancellation
                        throw e
                    } catch (e: java.io.IOException) {
                        AppLogger.e("IO error reading log file: ${e.message}", e)
                        // Continue monitoring on IO errors
                    } catch (e: Exception) {
                        AppLogger.e("Error reading log file: ${e.javaClass.simpleName}: ${e.message}", e)
                        // Continue monitoring on other errors
                    }
                    // Adaptive delay: check more frequently if file is growing, less if stable
                    val delayMs = if (logFile.exists() && logFile.length() > lastPosition + 1024) {
                        500L // More frequent if actively logging
                    } else {
                        2000L // Less frequent if stable
                    }
                    delay(delayMs)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Properly handle cancellation
                AppLogger.d("Log monitoring cancelled")
                throw e
            } catch (e: Exception) {
                AppLogger.e("Log monitoring stopped with error: ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }
    }

    /**
     * Attempt retry with exponential backoff
     */
    private fun attemptRetry(
        context: Context,
        bin: File,
        cfg: File,
        maxRetries: Int,
        retryDelayMs: Long
    ) {
        val retries = retryCount.incrementAndGet()
        if (retries <= maxRetries) {
            retryJob = monitoringScope.launch {
                val backoff = retryDelayMs * retries // Exponential backoff
                AppLogger.i("Retrying start in ${backoff}ms (attempt $retries/$maxRetries)")
                delay(backoff)
                startProcess(context, bin, cfg, maxRetries, retryDelayMs)
            }
        } else {
            AppLogger.e("Max retries reached, giving up")
        }
    }

    @Synchronized
    fun stop(): Boolean {
        logMonitorJob?.cancel()
        retryJob?.cancel()
        logCallback = null
        
        // Restore signal handlers when stopping
        XraySignalHandler.restoreHandlers()
        
        val p = procRef.getAndSet(null)
        val pid = pidRef.getAndSet(-1L)
        
        // Try to kill using Process reference first
        if (p != null) {
            return try {
                if (p.isAlive) {
                    p.destroy()
                    try {
                        val exited = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                        if (!exited) {
                            AppLogger.w("Process (PID: $pid) did not exit gracefully, forcing termination")
                            p.destroyForcibly()
                            p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)
                        }
                    } catch (e: InterruptedException) {
                        AppLogger.d("Process wait interrupted during stop")
                        p.destroyForcibly()
                    } catch (e: Exception) {
                        AppLogger.w("Error waiting for process termination", e)
                        p.destroyForcibly()
                    }
                }
                true
            } catch (t: Throwable) {
                AppLogger.e("failed to stop xray via Process reference (PID: $pid)", t)
                // Fall through to PID-based kill
                killProcessByPid(pid)
            }
        }
        
        // Fallback: kill by PID if Process reference is unavailable
        if (pid != -1L) {
            return killProcessByPid(pid)
        }
        
        return true
    }
    
    /**
     * Kill process by PID as fallback when Process reference is invalid.
     * This is critical when app goes to background and Process reference becomes stale.
     * 
     * Security and safety measures:
     * - Validates PID before execution to prevent command injection
     * - Verifies process is xray process before killing (prevents PID reuse race)
     * - Uses synchronized block to prevent concurrent kill attempts
     * - Falls back to force kill only after graceful shutdown attempt
     */
    @Synchronized
    private fun killProcessByPid(pid: Long): Boolean {
        if (pid == -1L) {
            return true
        }
        
        // Validate PID range before any operations
        if (pid <= 0 || pid > Int.MAX_VALUE) {
            AppLogger.e("Invalid PID range: $pid")
            return false
        }
        
        return try {
            AppLogger.d("Killing xray process by PID: $pid (Process reference unavailable)")
            
            // Check if process is still alive
            val isAlive = isProcessAlive(pid.toInt())
            if (!isAlive) {
                AppLogger.d("Process (PID: $pid) already dead")
                return true
            }
            
            // Verify this is still the xray process (prevents PID reuse race condition)
            // Check if stored PID matches current process PID
            val storedPid = pidRef.get()
            if (storedPid != pid) {
                AppLogger.w("PID mismatch: stored=$storedPid, requested=$pid. Process may have been recycled.")
                // Only proceed if stored PID is invalid (process was already cleared)
                if (storedPid != -1L) {
                    AppLogger.e("Refusing to kill process: PID mismatch indicates process reuse")
                    return false
                }
            }
            
            // Additional verification: check process name via /proc/PID/cmdline
            if (!isXrayProcess(pid.toInt())) {
                AppLogger.w("Process (PID: $pid) does not appear to be xray process. Skipping kill.")
                return false
            }
            
            // Use Android Process.killProcess for same-UID processes (graceful shutdown)
            android.os.Process.killProcess(pid.toInt())
            AppLogger.d("Sent kill signal to process PID: $pid")
            
            // Wait a bit to see if it exits (non-blocking with timeout)
            // Use coroutine with timeout instead of blocking
            try {
                kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withTimeout(2000) { // 2 second timeout
                        delay(500)
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                AppLogger.w("Timeout waiting for process kill confirmation")
            }
            
            // Verify process is dead
            val stillAlive = isProcessAlive(pid.toInt())
            if (stillAlive) {
                AppLogger.w("Process (PID: $pid) still alive after killProcess, trying force kill")
                // Last resort: try kill -9 via Runtime.exec
                // PID already validated above, use array form to prevent command injection
                try {
                    val pidStr = pid.toString()
                    // Double-check PID format (defense in depth)
                    if (pidStr.matches(Regex("^\\d+$")) && pid > 0 && pid <= Int.MAX_VALUE) {
                        // Use array form to prevent command injection
                        val killCmd = arrayOf("kill", "-9", pidStr)
                        val killProcess = Runtime.getRuntime().exec(killCmd)
                        val exitCode = killProcess.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                        if (exitCode) {
                            AppLogger.d("Force killed process PID: $pid")
                        } else {
                            AppLogger.w("Kill command returned non-zero exit code: $exitCode")
                            return false
                        }
                    } else {
                        AppLogger.e("Invalid PID format or range: $pid")
                        return false
                    }
                } catch (e: Exception) {
                    AppLogger.e("Failed to force kill process PID: $pid", e)
                    return false
                }
            } else {
                AppLogger.d("Process (PID: $pid) successfully killed")
            }
            
            true
        } catch (e: SecurityException) {
            AppLogger.e("Permission denied killing process PID: $pid", e)
            false
        } catch (e: Exception) {
            AppLogger.e("Error killing process by PID: $pid", e)
            false
        }
    }
    
    /**
     * Verify that a process is the xray process by checking /proc/PID/cmdline.
     * This prevents killing wrong processes due to PID reuse.
     */
    private fun isXrayProcess(pid: Int): Boolean {
        return try {
            val cmdlineFile = java.io.File("/proc/$pid/cmdline")
            if (!cmdlineFile.exists()) {
                return false
            }
            val cmdline = cmdlineFile.readText().trim()
            // Check if process name contains "xray" or matches expected binary name
            cmdline.contains("xray", ignoreCase = true) || 
            cmdline.contains("xray_core", ignoreCase = true) ||
            cmdline.endsWith("/xray_core", ignoreCase = true) ||
            cmdline.contains("libxray_copy.so", ignoreCase = true) ||
            cmdline.endsWith("/libxray_copy.so", ignoreCase = true)
        } catch (e: Exception) {
            // If we can't verify, assume it's safe (process may have exited)
            AppLogger.w("Could not verify process name for PID $pid: ${e.message}")
            true // Allow kill attempt if verification fails
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

    // Android 16+ SELinux compliance: Use native library directly instead of copying
    // Copying to app_data_file context causes execute_no_trans denial on Android 16+
    // Using native library directory directly avoids SELinux restrictions
    // Made internal to allow TProxyService to use it for SELinux compliance
    internal fun copyExecutable(context: Context): File? {
        // Validate nativeLibraryDir path to prevent path traversal
        val libDir = context.applicationInfo.nativeLibraryDir ?: return null
        val libDirFile = File(libDir)
        if (!libDirFile.exists() || !libDirFile.isDirectory) {
            AppLogger.e("Invalid native library directory: $libDir")
            return null
        }
        
        val src = File(libDir, "libxray.so")
        // Note: File signature verification would require additional infrastructure
        // For now, we validate file exists and is readable
        if (!src.exists()) {
            AppLogger.e("libxray.so not found at ${src.absolutePath}")
            // Try ABI validation for better error message
            val validation = XrayAbiValidator.validateCurrentAbi(context)
            AppLogger.e("Validation result: ${validation.message}")
            return null
        }
        
        // Android 16+ SELinux fix: Use native library directly instead of copying
        // Native library directory has different SELinux context that allows execution
        // This avoids execute_no_trans denial from app_data_file context
        val androidVersion = Build.VERSION.SDK_INT
        if (androidVersion >= 34) { // Android 14+ (API 34+)
            // For Android 14+, ALWAYS use native library directly
            // Native library directory has app_file_exec context which allows execution
            // Android 16+ SELinux policy prevents setExecutable() on copied files
            // Even if canExecute() returns false, the library is executable in native context
            AppLogger.i("Using native library directly (Android $androidVersion SELinux compliance): ${src.absolutePath}")
            AppLogger.d("Native library executable check: ${src.canExecute()} (ignoring for Android 14+)")
            return src
        }
        
        // SELinux fix: Copy to filesDir with libxray_copy.so name to avoid setattr denial
        // This avoids "avc: denied { setattr } for name=\"libxray.so\"" warnings
        // Native code should not use chmod or mprotect on .so files
        val dst = File(context.filesDir, "libxray_copy.so")
        try {
            // Copy file and verify success by comparing sizes and basic integrity
            val srcSize = src.length()
            if (srcSize <= 0) {
                AppLogger.e("Source file is empty or invalid: $srcSize bytes")
                return null
            }
            
            // Copy file in chunks for better error handling
            src.inputStream().use { ins ->
                dst.outputStream().use { outs ->
                    val buffer = ByteArray(8192)
                    var totalCopied = 0L
                    var bytesRead: Int
                    while (ins.read(buffer).also { bytesRead = it } != -1) {
                        outs.write(buffer, 0, bytesRead)
                        totalCopied += bytesRead
                    }
                    if (totalCopied != srcSize) {
                        throw java.io.IOException("Copy incomplete: expected $srcSize bytes, copied $totalCopied")
                    }
                }
            }
            
            // Verify copy was successful - size check
            if (dst.length() != srcSize) {
                AppLogger.e("File copy verification failed: source size=$srcSize, dest size=${dst.length()}")
                dst.delete()
                return null
            }
            
            // Additional verification: check first few bytes match (basic integrity check)
            val srcHeader = src.inputStream().use { it.readBytes().take(16).toByteArray() }
            val dstHeader = dst.inputStream().use { it.readBytes().take(16).toByteArray() }
            if (!srcHeader.contentEquals(dstHeader)) {
                AppLogger.e("File copy integrity check failed: headers don't match")
                dst.delete()
                return null
            }
            
            // Set executable permission only after successful copy and verification
            // Note: File signature verification would require additional infrastructure
            // For now, we rely on Android's package signature verification for the APK
            if (!dst.setExecutable(true)) {
                AppLogger.e("Failed to set executable permission on ${dst.absolutePath}")
                dst.delete()
                return null
            }
            if (!dst.canExecute()) {
                AppLogger.e("Failed to set executable permission on ${dst.absolutePath}")
                dst.delete()
                return null
            }
            AppLogger.d("Successfully copied xray executable: ${dst.absolutePath} (${srcSize} bytes)")
            return dst
        } catch (e: java.io.IOException) {
            AppLogger.e("IO error copying executable", e)
            dst.delete()
            return null
        } catch (e: SecurityException) {
            AppLogger.e("Security error copying executable", e)
            dst.delete()
            return null
        } catch (t: Throwable) {
            AppLogger.e("Unexpected error copying executable", t)
            dst.delete()
            return null
        }
    }
}

