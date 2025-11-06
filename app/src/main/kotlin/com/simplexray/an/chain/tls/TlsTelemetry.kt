package com.simplexray.an.chain.tls

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * TLS telemetry for monitoring TLS implementation and handshake metrics
 */
object TlsTelemetry {
    private val _tlsState = MutableStateFlow<TlsState>(
        TlsState(
            implementation = TlsImplementation.AUTO,
            info = null,
            handshakeTimeMs = null,
            cipherSuite = null,
            keyExchange = null,
            lastHandshakeTime = null
        )
    )
    val tlsState: Flow<TlsState> = _tlsState.asStateFlow()
    
    /**
     * Update TLS implementation info
     */
    fun updateTlsInfo(implementation: TlsImplementation, info: TlsInfo) {
        _tlsState.value = _tlsState.value.copy(
            implementation = implementation,
            info = info,
            cipherSuite = info.cipherSuites.firstOrNull(),
            keyExchange = info.keyExchange
        )
    }
    
    /**
     * Record handshake time
     */
    fun recordHandshake(handshakeTimeMs: Long, cipherSuite: String? = null) {
        _tlsState.value = _tlsState.value.copy(
            handshakeTimeMs = handshakeTimeMs,
            cipherSuite = cipherSuite ?: _tlsState.value.cipherSuite,
            lastHandshakeTime = System.currentTimeMillis()
        )
    }
    
    /**
     * Get current TLS state
     */
    fun getTlsState(): TlsState = _tlsState.value
}

data class TlsState(
    val implementation: TlsImplementation,
    val info: TlsInfo?,
    val handshakeTimeMs: Long?,
    val cipherSuite: String?,
    val keyExchange: String?,
    val lastHandshakeTime: Long?
)

