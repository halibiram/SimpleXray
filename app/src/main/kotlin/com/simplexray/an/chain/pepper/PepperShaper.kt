package com.simplexray.an.chain.pepper

import android.content.Context
import com.simplexray.an.common.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * PepperShaper: Traffic shaping module
 * 
 * Provides burst-friendly streaming with loss-aware backoff.
 * Implemented as Kotlin + JNI pair for TCP and UDP flows.
 */
// ARCH-DEBT: Object singleton pattern - may cause issues with multiple instances
// UNSAFE: Native library loaded in init - may crash if library not found
object PepperShaper {
    // STATE-HAZARD: MutableStateFlow updated from native code
    private val _stats = MutableStateFlow<PepperStats>(
        PepperStats(
            bytesShaped = 0,
            packetsShaped = 0,
            drops = 0,
            currentRateBps = 0,
            queueDepth = 0
        )
    )
    val stats: Flow<PepperStats> = _stats.asStateFlow()
    
    private var isInitialized = false
    
    init {
        // Safe library loading with error handling
        try {
            System.loadLibrary("pepper-shaper")
        } catch (e: UnsatisfiedLinkError) {
            AppLogger.e("Failed to load pepper-shaper native library: ${e.message}", e)
            // Mark as failed to prevent further operations
            isInitialized = false
        } catch (e: Exception) {
            AppLogger.e("Unexpected error loading pepper-shaper library: ${e.message}", e)
            isInitialized = false
        }
    }
    
    /**
     * Initialize PepperShaper
     */
    fun init(context: Context) {
        if (isInitialized) return
        AppLogger.d("PepperShaper: Initializing")
        nativeInit()
        isInitialized = true
    }
    
    /**
     * Attach shaper to a socket/file descriptor pair
     * 
     * @param fdPair Pair of (readFd, writeFd)
     * @param mode TCP or UDP mode
     * @param params Shaping parameters
     * @return Shaper handle or null on failure
     */
    fun attach(
        fdPair: Pair<Int, Int>,
        mode: SocketMode,
        params: PepperParams
    ): Long? {
        // Validate file descriptors before native call
        if (fdPair.first < 0 || fdPair.second < 0) {
            AppLogger.e("PepperShaper: Invalid file descriptors: ${fdPair.first}/${fdPair.second}")
            return null
        }
        
        if (!isInitialized) {
            AppLogger.e("PepperShaper: Not initialized, cannot attach")
            return null
        }
        
        return try {
            AppLogger.d("PepperShaper: Attaching to fds ${fdPair.first}/${fdPair.second}, mode=$mode")
            val handle = nativeAttach(fdPair.first, fdPair.second, mode.ordinal, params)
            if (handle > 0) {
                AppLogger.d("PepperShaper: Attached successfully, handle=$handle")
                handle
            } else {
                AppLogger.e("PepperShaper: Failed to attach, native returned handle=$handle (invalid or error)")
                null
            }
        } catch (e: UnsatisfiedLinkError) {
            AppLogger.e("PepperShaper: Native library not loaded: ${e.message}", e)
            null
        } catch (e: Exception) {
            AppLogger.e("PepperShaper: Exception attaching to fds ${fdPair.first}/${fdPair.second}: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }
    
    /**
     * Detach shaper from handle
     */
    fun detach(handle: Long): Boolean {
        // Validate handle before native call
        if (handle <= 0) {
            AppLogger.e("PepperShaper: Invalid handle for detach: $handle")
            return false
        }
        
        if (!isInitialized) {
            AppLogger.w("PepperShaper: Not initialized, cannot detach")
            return false
        }
        
        return try {
            val result = nativeDetach(handle)
            if (result) {
                AppLogger.d("PepperShaper: Detached handle=$handle successfully")
            } else {
                AppLogger.w("PepperShaper: Detach returned false for handle=$handle")
            }
            result
        } catch (e: UnsatisfiedLinkError) {
            AppLogger.e("PepperShaper: Native library not loaded: ${e.message}", e)
            false
        } catch (e: Exception) {
            AppLogger.e("PepperShaper: Exception detaching handle=$handle: ${e.javaClass.simpleName}: ${e.message}", e)
            false
        }
    }
    
    /**
     * Update parameters for an attached shaper
     */
    fun updateParams(handle: Long, params: PepperParams): Boolean {
        // Validate handle before native call
        if (handle <= 0) {
            AppLogger.e("PepperShaper: Invalid handle for updateParams: $handle")
            return false
        }
        
        if (!isInitialized) {
            AppLogger.w("PepperShaper: Not initialized, cannot update params")
            return false
        }
        
        return try {
            val result = nativeUpdateParams(handle, params)
            if (result) {
                AppLogger.d("PepperShaper: Updated params for handle=$handle successfully")
            } else {
                AppLogger.w("PepperShaper: Update params returned false for handle=$handle")
            }
            result
        } catch (e: UnsatisfiedLinkError) {
            AppLogger.e("PepperShaper: Native library not loaded: ${e.message}", e)
            false
        } catch (e: Exception) {
            AppLogger.e("PepperShaper: Exception updating params for handle=$handle: ${e.javaClass.simpleName}: ${e.message}", e)
            false
        }
    }
    
    /**
     * Get current statistics
     */
    fun getStats(): PepperStats = _stats.value
    
    /**
     * Cleanup and shutdown
     */
    fun shutdown() {
        if (!isInitialized) return
        AppLogger.d("PepperShaper: Shutting down")
        nativeShutdown()
        isInitialized = false
    }
    
    enum class SocketMode {
        TCP,
        UDP
    }
    
    data class PepperStats(
        val bytesShaped: Long,
        val packetsShaped: Long,
        val drops: Long,
        val currentRateBps: Long,
        val queueDepth: Int
    )
    
    // Native methods
    private external fun nativeInit()
    private external fun nativeAttach(
        readFd: Int,
        writeFd: Int,
        mode: Int,
        params: PepperParams
    ): Long
    private external fun nativeDetach(handle: Long): Boolean
    private external fun nativeUpdateParams(handle: Long, params: PepperParams): Boolean
    private external fun nativeShutdown()
}

