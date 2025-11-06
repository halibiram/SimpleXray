package com.simplexray.an.chain.supervisor

/**
 * State machine for the tunneling chain
 */
enum class ChainState {
    STOPPED,
    STARTING,
    RUNNING,
    DEGRADED,
    STOPPING
}

/**
 * Status of individual chain layer
 */
data class LayerStatus(
    val name: String,
    val isRunning: Boolean,
    val error: String?,
    val lastUpdate: Long
)

/**
 * Overall chain status
 */
data class ChainStatus(
    val state: ChainState,
    val layers: Map<String, LayerStatus>,
    val uptime: Long, // milliseconds
    val totalBytesUp: Long,
    val totalBytesDown: Long
)

