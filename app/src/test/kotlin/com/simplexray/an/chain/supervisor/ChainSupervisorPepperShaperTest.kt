package com.simplexray.an.chain.supervisor

import com.simplexray.an.chain.pepper.PepperParams
import com.simplexray.an.chain.pepper.PepperMode
import com.simplexray.an.chain.pepper.QueueDiscipline
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import android.content.Context
import io.mockk.*

class ChainSupervisorPepperShaperTest {
    
    private lateinit var mockContext: Context
    private lateinit var chainSupervisor: ChainSupervisor
    
    @Before
    fun setup() {
        mockContext = mockk<Context>(relaxed = true)
        mockkObject(com.simplexray.an.chain.pepper.PepperShaper)
        mockkObject(com.simplexray.an.chain.reality.RealitySocks)
        mockkObject(com.simplexray.an.chain.hysteria2.Hysteria2)
        
        every { com.simplexray.an.chain.pepper.PepperShaper.init(any()) } just Runs
        every { com.simplexray.an.chain.reality.RealitySocks.init(any()) } just Runs
        every { com.simplexray.an.chain.hysteria2.Hysteria2.init(any()) } just Runs
        
        chainSupervisor = ChainSupervisor(mockContext)
    }
    
    @Test
    fun `ChainConfig with PepperParams should be valid`() {
        val pepperParams = PepperParams(
            mode = PepperMode.BURST_FRIENDLY,
            maxBurstBytes = 64 * 1024,
            targetRateBps = 10_000_000L,
            queueDiscipline = QueueDiscipline.FQ,
            lossAwareBackoff = true,
            enablePacing = true
        )
        
        val config = ChainConfig(
            name = "Test Chain",
            realityConfig = null,
            hysteria2Config = null,
            pepperParams = pepperParams,
            xrayConfigPath = null,
            tlsMode = ChainConfig.TlsMode.AUTO
        )
        
        assertNotNull(config.pepperParams)
        assertEquals(PepperMode.BURST_FRIENDLY, config.pepperParams?.mode)
        assertEquals(64 * 1024, config.pepperParams?.maxBurstBytes)
    }
    
    @Test
    fun `ChainConfig without PepperParams should allow null`() {
        val config = ChainConfig(
            name = "Test Chain",
            realityConfig = null,
            hysteria2Config = null,
            pepperParams = null,
            xrayConfigPath = null,
            tlsMode = ChainConfig.TlsMode.AUTO
        )
        
        assertNull(config.pepperParams)
    }
    
    @Test
    fun `PepperParams should have all required fields`() {
        val params = PepperParams()
        
        assertNotNull(params.mode)
        assertTrue(params.maxBurstBytes > 0)
        assertNotNull(params.queueDiscipline)
        assertNotNull(params.lossAwareBackoff)
        assertNotNull(params.enablePacing)
    }
}

