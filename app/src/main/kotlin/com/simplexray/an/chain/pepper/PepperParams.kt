package com.simplexray.an.chain.pepper

/**
 * Parameters for PepperShaper traffic shaping
 */
data class PepperParams(
    val mode: PepperMode = PepperMode.BURST_FRIENDLY,
    val maxBurstBytes: Long = 64 * 1024, // 64KB
    val targetRateBps: Long = 0, // 0 = unlimited
    val queueDiscipline: QueueDiscipline = QueueDiscipline.FQ,
    val lossAwareBackoff: Boolean = true,
    val enablePacing: Boolean = true
)

enum class PepperMode {
    BURST_FRIENDLY, // Allow bursts, smooth over time
    CONSTANT_RATE,  // Strict rate limiting
    ADAPTIVE        // Adjust based on loss/RTT
}

enum class QueueDiscipline {
    FQ,      // Fair Queue
    CODEL,   // CoDel-lite
    SIMPLE   // Simple FIFO
}

