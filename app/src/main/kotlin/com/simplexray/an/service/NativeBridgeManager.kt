package com.simplexray.an.service

import android.content.Context
import com.simplexray.an.common.AppLogger

/**
 * Manages JNI calls to native library for TProxy service.
 * Provides safe wrappers with input validation and error handling.
 */
object NativeBridgeManager {
    private var libraryLoaded = false
    private var libraryLoadError: Throwable? = null
    
    init {
        try {
            System.loadLibrary("hev-socks5-tunnel")
            libraryLoaded = true
            AppLogger.d("NativeBridgeManager: Native library 'hev-socks5-tunnel' loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            libraryLoaded = false
            libraryLoadError = e
            AppLogger.e("NativeBridgeManager: Failed to load native library 'hev-socks5-tunnel'", e)
            AppLogger.e("NativeBridgeManager: Library not found. Ensure the library is built and included in the APK.")
        } catch (e: Exception) {
            libraryLoaded = false
            libraryLoadError = e
            AppLogger.e("NativeBridgeManager: Unexpected error loading native library", e)
        }
    }
    
    /**
     * Check if the native library is loaded.
     * 
     * @return true if library is loaded, false otherwise
     */
    fun isLibraryLoaded(): Boolean = libraryLoaded
    
    /**
     * Get the error that occurred during library loading, if any.
     * 
     * @return The error that occurred, or null if library loaded successfully
     */
    fun getLibraryLoadError(): Throwable? = libraryLoadError
    
    /**
     * Ensure library is loaded before making native calls.
     * 
     * @throws UnsatisfiedLinkError if library is not loaded
     */
    private fun ensureLibraryLoaded() {
        if (!libraryLoaded) {
            val error = libraryLoadError
            val errorMsg = if (error != null) {
                "Native library 'hev-socks5-tunnel' not loaded: ${error.message}"
            } else {
                "Native library 'hev-socks5-tunnel' not loaded"
            }
            AppLogger.e("NativeBridgeManager: $errorMsg")
            throw UnsatisfiedLinkError(errorMsg).apply {
                if (error != null) {
                    initCause(error)
                }
            }
        }
    }
    
    /**
     * Start TProxy service via native code.
     * 
     * @param configPath Path to TProxy configuration file
     * @param fd File descriptor for TUN interface
     * @throws IllegalArgumentException if inputs are invalid
     * @throws Exception if native call fails
     */
    fun startTProxyService(configPath: String, fd: Int) {
        ensureLibraryLoaded()
        
        // SEC: Validate configPath length to prevent buffer overflow
        if (configPath.length > 4096) {
            AppLogger.e("Config path too long: ${configPath.length} bytes")
            throw IllegalArgumentException("Config path exceeds maximum length")
        }
        
        // SEC: Validate file descriptor range
        if (fd < 0 || fd > Int.MAX_VALUE) {
            AppLogger.e("Invalid file descriptor: $fd")
            throw IllegalArgumentException("File descriptor out of valid range")
        }
        
        // Additional validation: file descriptors are typically small positive integers
        if (fd > 1000000) {
            AppLogger.w("File descriptor value suspiciously large: $fd")
            throw IllegalArgumentException("File descriptor value suspiciously large")
        }
        
        // SEC: Validate configPath doesn't contain null bytes or control characters
        if (configPath.any { it.code < 32 && it != '\t' && it != '\n' && it != '\r' }) {
            AppLogger.e("Config path contains invalid characters")
            throw IllegalArgumentException("Config path contains invalid characters")
        }
        
        // SEC: Path traversal check
        if (configPath.contains("..") || configPath.contains("//")) {
            AppLogger.e("Config path contains path traversal sequences")
            throw IllegalArgumentException("Config path contains path traversal sequences")
        }
        
        try {
            TProxyStartServiceNative(configPath, fd)
        } catch (e: Exception) {
            AppLogger.e("JNI TProxyStartService failed", e)
            throw e
        }
    }
    
    /**
     * Stop TProxy service via native code.
     * Best-effort operation - errors are logged but not thrown.
     */
    fun stopTProxyService() {
        try {
            TProxyStopServiceNative()
        } catch (e: UnsatisfiedLinkError) {
            AppLogger.e("Native library not loaded for TProxyStopService: ${e.message}", e)
        } catch (e: Exception) {
            AppLogger.e("Error stopping TProxy service via JNI: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }
    
    /**
     * Get statistics from native TProxy service.
     * 
     * @return Array of statistics, or null if unavailable or invalid
     */
    fun getTProxyStats(): LongArray? {
        if (!libraryLoaded) {
            AppLogger.w("Native library not loaded, cannot get TProxy stats")
            return null
        }
        
        return try {
            val stats = TProxyGetStatsNative()
            // Validate returned array
            if (stats != null) {
                // SEC: Validate array size to prevent buffer overflow
                if (stats.size > MAX_STATS_ARRAY_SIZE) {
                    AppLogger.w("Stats array size suspiciously large: ${stats.size}, max expected: $MAX_STATS_ARRAY_SIZE")
                    return null
                }
                // Validate array is not empty
                if (stats.isEmpty()) {
                    AppLogger.w("Stats array is empty")
                    return null
                }
                // Note: Some stats may legitimately be negative (e.g., error counts, deltas)
                // Only validate that values are within reasonable range
                if (stats.any { it < Long.MIN_VALUE / 2 || it > Long.MAX_VALUE / 2 }) {
                    AppLogger.w("Stats array contains values outside reasonable range")
                    return null
                }
            }
            stats
        } catch (e: Exception) {
            AppLogger.e("JNI TProxyGetStats failed", e)
            null
        }
    }
    
    /**
     * Get native library directory path.
     * 
     * @param context Application context
     * @return Path to native library directory, or null if unavailable
     */
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
    
    // Maximum expected stats array size (defensive limit)
    private const val MAX_STATS_ARRAY_SIZE = 100
    
    // Native JNI methods
    @Suppress("FunctionName")
    private external fun TProxyStartServiceNative(configPath: String, fd: Int)
    
    @Suppress("FunctionName")
    private external fun TProxyStopServiceNative()
    
    @Suppress("FunctionName")
    private external fun TProxyGetStatsNative(): LongArray?
}

