package com.simplexray.an.viewmodel

import com.simplexray.an.common.AppLogger
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.simplexray.an.chain.supervisor.ChainConfig
import com.simplexray.an.chain.supervisor.ChainState
import com.simplexray.an.chain.supervisor.ChainStatus
import com.simplexray.an.chain.supervisor.ChainSupervisor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for chain management UI
 */
class ChainViewModel(application: Application) : AndroidViewModel(application) {
    // Properly cleaned up in onCleared()
    private val supervisor = ChainSupervisor(application)
    
    // StateFlow updates are thread-safe by design
    private val _status = MutableStateFlow<ChainStatus>(
        ChainStatus(
            state = ChainState.STOPPED,
            layers = emptyMap(),
            uptime = 0,
            totalBytesUp = 0,
            totalBytesDown = 0
        )
    )
    val status: StateFlow<ChainStatus> = _status.asStateFlow()
    
    private var isStarting = false
    private var isStopping = false
    
    init {
        // Observe supervisor status (automatically cancelled when viewModelScope is cancelled)
        viewModelScope.launch {
            supervisor.status.collect { newStatus ->
                _status.value = newStatus
            }
        }
    }
    
    @Synchronized
    fun startChain(config: ChainConfig) {
        // Validate config before starting
        if (config.xrayConfigPath == null && config.realityConfig == null && config.hysteria2Config == null) {
            AppLogger.e("ChainViewModel: Invalid config - no layers configured")
            return
        }
        
        // Prevent concurrent starts
        if (isStarting) {
            AppLogger.w("ChainViewModel: Chain start already in progress")
            return
        }
        
        if (_status.value.state != ChainState.STOPPED) {
            AppLogger.w("ChainViewModel: Chain is not stopped, current state: ${_status.value.state}")
            return
        }
        
        isStarting = true
        viewModelScope.launch {
            try {
                val result = supervisor.start(config)
                result.fold(
                    onSuccess = { AppLogger.d("ChainViewModel: Chain started successfully") },
                    onFailure = { e -> AppLogger.e("ChainViewModel: Failed to start chain: ${e.message}", e) }
                )
            } finally {
                synchronized(this@ChainViewModel) {
                    isStarting = false
                }
            }
        }
    }
    
    @Synchronized
    fun stopChain() {
        // Prevent concurrent stops
        if (isStopping) {
            AppLogger.w("ChainViewModel: Chain stop already in progress")
            return
        }
        
        if (_status.value.state == ChainState.STOPPED) {
            AppLogger.d("ChainViewModel: Chain already stopped")
            return
        }
        
        isStopping = true
        viewModelScope.launch {
            try {
                val result = supervisor.stop()
                result.fold(
                    onSuccess = { AppLogger.d("ChainViewModel: Chain stopped successfully") },
                    onFailure = { e -> AppLogger.e("ChainViewModel: Failed to stop chain: ${e.message}", e) }
                )
            } finally {
                synchronized(this@ChainViewModel) {
                    isStopping = false
                }
            }
        }
    }
    
    @Synchronized
    fun restartChain(config: ChainConfig) {
        // Validate config
        if (config.xrayConfigPath == null && config.realityConfig == null && config.hysteria2Config == null) {
            AppLogger.e("ChainViewModel: Invalid config - no layers configured")
            return
        }
        
        // Prevent concurrent operations
        if (isStarting || isStopping) {
            AppLogger.w("ChainViewModel: Chain operation already in progress")
            return
        }
        
        viewModelScope.launch {
            // Stop first, then start (supervisor.restart() handles this atomically)
            val result = supervisor.restart(config)
            result.fold(
                onSuccess = { AppLogger.d("ChainViewModel: Chain restarted successfully") },
                onFailure = { e -> AppLogger.e("ChainViewModel: Failed to restart chain: ${e.message}", e) }
            )
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        AppLogger.d("ChainViewModel cleared - shutting down supervisor")
        // Stop supervisor and cleanup resources
        supervisor.shutdown()
    }
}

