package com.simplexray.an.xray

import android.util.Log
import com.simplexray.an.common.AppLogger

/**
 * Signal handler for Xray-core process
 * Installs handlers for SIGABRT, SIGSEGV, SIGBUS to log crashes to logcat
 */
object XraySignalHandler {
    private const val TAG = "XraySignalHandler"
    private var handlersInstalled = false
    
    init {
        try {
            System.loadLibrary("xray-signal-handler")
            AppLogger.d("$TAG: Native library loaded")
        } catch (e: UnsatisfiedLinkError) {
            AppLogger.w("$TAG: Failed to load native library: ${e.message}")
        } catch (e: Exception) {
            AppLogger.w("$TAG: Unexpected error loading native library: ${e.message}")
        }
    }
    
    /**
     * Install signal handlers for Xray-core process
     * Should be called before starting Xray-core
     */
    fun installHandlers(): Boolean {
        if (handlersInstalled) {
            AppLogger.d("$TAG: Handlers already installed")
            return true
        }
        
        return try {
            val result = nativeInstallHandlers()
            if (result == 0) {
                handlersInstalled = true
                AppLogger.i("$TAG: Signal handlers installed successfully")
                true
            } else {
                AppLogger.e("$TAG: Failed to install signal handlers (code: $result)")
                false
            }
        } catch (e: Exception) {
            AppLogger.e("$TAG: Exception installing signal handlers: ${e.message}", e)
            false
        }
    }
    
    /**
     * Restore original signal handlers
     * Should be called when Xray-core is stopped
     */
    fun restoreHandlers() {
        if (!handlersInstalled) {
            return
        }
        
        try {
            nativeRestoreHandlers()
            handlersInstalled = false
            AppLogger.i("$TAG: Signal handlers restored")
        } catch (e: Exception) {
            AppLogger.w("$TAG: Exception restoring signal handlers: ${e.message}", e)
        }
    }
    
    @JvmStatic
    private external fun nativeInstallHandlers(): Int
    
    @JvmStatic
    private external fun nativeRestoreHandlers()
}

