package com.simplexray.an.chain.supervisor

import com.simplexray.an.chain.pepper.PepperParams
import com.simplexray.an.chain.pepper.PepperMode
import com.simplexray.an.chain.pepper.QueueDiscipline
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for ChainHelper validation and utility functions
 */
class ChainHelperTest {
    
    @Test
    fun `validateChainConfig should reject config with no components`() {
        val config = ChainConfig(
            name = "Empty Config",
            pepperParams = null,
            xrayConfigPath = null,
            tlsMode = ChainConfig.TlsMode.AUTO
        )
        
        val result = ChainHelper.validateChainConfig(config)
        assertFalse(result.isValid, "Config with no components should be invalid")
        assertTrue(result.errors.isNotEmpty(), "Should have validation errors")
    }
    
    @Test
    fun `validateChainConfig should accept config with Xray path`() {
        val config = ChainConfig(
            name = "Xray Only",
            pepperParams = null,
            xrayConfigPath = "xray.json",
            tlsMode = ChainConfig.TlsMode.AUTO
        )
        
        val result = ChainHelper.validateChainConfig(config)
        assertTrue(result.isValid, "Config with Xray path should be valid")
    }
    
    @Test
    fun `validateChainConfig should accept config with all components`() {
        val pepperParams = PepperParams(
            mode = PepperMode.BURST_FRIENDLY,
            maxBurstBytes = 64 * 1024,
            targetRateBps = 10_000_000L,
            queueDiscipline = QueueDiscipline.FQ,
            lossAwareBackoff = true,
            enablePacing = true
        )
        
        val config = ChainConfig(
            name = "Full Chain",
            pepperParams = pepperParams,
            xrayConfigPath = "xray.json",
            tlsMode = ChainConfig.TlsMode.BORINGSSL
        )
        
        val result = ChainHelper.validateChainConfig(config)
        assertTrue(result.isValid, "Full chain config should be valid")
    }
    
    @Test
    fun `getChainStatusSummary should return summary string`() {
        val config = ChainConfig(
            name = "Test Chain",
            pepperParams = PepperParams(),
            xrayConfigPath = "xray.json",
            tlsMode = ChainConfig.TlsMode.AUTO
        )
        
        val summary = ChainHelper.getChainStatusSummary(config)
        assertTrue(summary.isNotEmpty(), "Summary should not be empty")
        assertTrue(summary.contains("PepperShaper"), "Summary should include PepperShaper status")
        assertTrue(summary.contains("Xray"), "Summary should include Xray status")
    }
}







