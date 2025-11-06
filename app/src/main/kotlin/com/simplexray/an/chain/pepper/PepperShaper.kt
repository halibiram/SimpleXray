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
object PepperShaper {
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
        System.loadLibrary("pepper-shaper")
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
        return try {
            AppLogger.d("PepperShaper: Attaching to fds ${fdPair.first}/${fdPair.second}, mode=$mode")
            val handle = nativeAttach(fdPair.first, fdPair.second, mode.ordinal, params)
            if (handle > 0) {
                handle
            } else {
                AppLogger.e("PepperShaper: Failed to attach, handle=$handle")
                null
            }
        } catch (e: Exception) {
            AppLogger.e("PepperShaper: Exception attaching", e)
            null
        }
    }
    
    /**
     * Detach shaper from handle
     */
    fun detach(handle: Long): Boolean {
        return try {
            nativeDetach(handle)
        } catch (e: Exception) {
            AppLogger.e("PepperShaper: Exception detaching", e)
            false
        }
    }
    
    /**
     * Update parameters for an attached shaper
     */
    fun updateParams(handle: Long, params: PepperParams): Boolean {
        return try {
            nativeUpdateParams(handle, params)
        } catch (e: Exception) {
            AppLogger.e("PepperShaper: Exception updating params", e)
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

