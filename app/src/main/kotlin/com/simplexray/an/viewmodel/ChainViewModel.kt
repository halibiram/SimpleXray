package com.simplexray.an.viewmodel

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
    private val supervisor = ChainSupervisor(application)
    
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
    
    init {
        // Observe supervisor status
        viewModelScope.launch {
            supervisor.status.collect { newStatus ->
                _status.value = newStatus
            }
        }
    }
    
    fun startChain(config: ChainConfig) {
        viewModelScope.launch {
            supervisor.start(config)
        }
    }
    
    fun stopChain() {
        viewModelScope.launch {
            supervisor.stop()
        }
    }
    
    fun restartChain(config: ChainConfig) {
        viewModelScope.launch {
            supervisor.restart(config)
        }
    }
}

