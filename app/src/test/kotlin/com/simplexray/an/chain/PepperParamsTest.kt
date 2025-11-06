package com.simplexray.an.chain

import com.simplexray.an.chain.pepper.PepperMode
import com.simplexray.an.chain.pepper.PepperParams
import com.simplexray.an.chain.pepper.QueueDiscipline
import org.junit.Assert.*
import org.junit.Test

class PepperParamsTest {
    
    @Test
    fun `PepperParams with valid parameters should be created`() {
        val params = PepperParams(
            mode = PepperMode.BURST_FRIENDLY,
            maxBurstBytes = 128 * 1024,
            targetRateBps = 10_000_000L, // 10 Mbps
            queueDiscipline = QueueDiscipline.FQ,
            lossAwareBackoff = true,
            enablePacing = true
        )
        
        assertEquals(PepperMode.BURST_FRIENDLY, params.mode)
        assertEquals(128 * 1024, params.maxBurstBytes)
        assertEquals(10_000_000L, params.targetRateBps)
        assertEquals(QueueDiscipline.FQ, params.queueDiscipline)
        assertTrue(params.lossAwareBackoff)
        assertTrue(params.enablePacing)
    }
    
    @Test
    fun `PepperParams should have sensible defaults`() {
        val params = PepperParams()
        
        assertEquals(PepperMode.BURST_FRIENDLY, params.mode)
        assertEquals(64 * 1024, params.maxBurstBytes) // 64KB
        assertEquals(0, params.targetRateBps) // 0 = unlimited
        assertEquals(QueueDiscipline.FQ, params.queueDiscipline)
        assertTrue(params.lossAwareBackoff)
        assertTrue(params.enablePacing)
    }
    
    @Test
    fun `PepperParams should support all modes`() {
        val modes = listOf(
            PepperMode.BURST_FRIENDLY,
            PepperMode.CONSTANT_RATE,
            PepperMode.ADAPTIVE
        )
        
        modes.forEach { mode ->
            val params = PepperParams(mode = mode)
            assertEquals(mode, params.mode)
        }
    }
    
    @Test
    fun `PepperParams should support all queue disciplines`() {
        val disciplines = listOf(
            QueueDiscipline.FQ,
            QueueDiscipline.CODEL,
            QueueDiscipline.SIMPLE
        )
        
        disciplines.forEach { discipline ->
            val params = PepperParams(queueDiscipline = discipline)
            assertEquals(discipline, params.queueDiscipline)
        }
    }
}

