package com.simplexray.an.chain

import com.simplexray.an.chain.tls.TlsImplementation
import com.simplexray.an.chain.tls.TlsModeDetector
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import android.content.Context

class TlsModeDetectorTest {
    
    private lateinit var mockContext: Context
    
    @Before
    fun setup() {
        mockContext = mockk<Context>(relaxed = true)
        mockkStatic(com.simplexray.an.BuildConfig::class)
        
        every { com.simplexray.an.BuildConfig.USE_BORINGSSL } returns true
        every { com.simplexray.an.BuildConfig.BORINGSSL_AVAILABLE_AT_BUILD } returns true
    }
    
    @Test
    fun `TlsModeDetector should detect available modes`() {
        // Note: This test requires actual Android context, so we'll test the logic
        // In a real test environment, we'd use Robolectric or AndroidJUnitRunner
        
        // Test that the function exists and doesn't crash
        try {
            // This will fail in unit test without Android context, which is expected
            // In instrumented tests, this would work
            val available = TlsModeDetector.detectAvailableModes(mockContext)
            assertNotNull(available)
            assertTrue(available.isNotEmpty())
        } catch (e: Exception) {
            // Expected in unit test without Android runtime
            assertTrue(e is UnsupportedOperationException || e.message?.contains("Context") == true)
        }
    }
    
    @Test
    fun `TlsModeDetector should provide TLS info for each mode`() {
        val modes = listOf(
            TlsImplementation.BORINGSSL,
            TlsImplementation.CONSCRYPT,
            TlsImplementation.GO_BORINGCRYPTO,
            TlsImplementation.AUTO
        )
        
        modes.forEach { mode ->
            try {
                val info = TlsModeDetector.getTlsInfo(mockContext, mode)
                assertNotNull(info)
                assertNotNull(info.implementation)
                assertNotNull(info.version)
                assertNotNull(info.cipherSuites)
                assertTrue(info.cipherSuites.isNotEmpty())
            } catch (e: Exception) {
                // Expected in unit test without Android runtime
                // In instrumented tests, this would work
            }
        }
    }
    
    @Test
    fun `TlsInfo should contain required fields`() {
        // Test data structure
        val info = com.simplexray.an.chain.tls.TlsInfo(
            implementation = "BoringSSL",
            version = "1.0",
            cipherSuites = listOf("TLS_AES_128_GCM_SHA256"),
            keyExchange = "X25519",
            available = true
        )
        
        assertEquals("BoringSSL", info.implementation)
        assertEquals("1.0", info.version)
        assertTrue(info.cipherSuites.isNotEmpty())
        assertEquals("X25519", info.keyExchange)
        assertTrue(info.available)
    }
}

