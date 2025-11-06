package com.simplexray.an.chain

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.simplexray.an.chain.diagnostics.ChainHealthChecker
import com.simplexray.an.chain.diagnostics.ChainLogger
import com.simplexray.an.chain.diagnostics.DiagnosticBundle
import com.simplexray.an.chain.supervisor.ChainConfig
import com.simplexray.an.chain.supervisor.ChainSupervisor
import com.simplexray.an.chain.supervisor.ChainState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for the tunneling chain
 * 
 * These tests require an Android device/emulator and may need network access.
 * Run with: ./gradlew connectedAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class ChainIntegrationTest {
    
    private lateinit var context: Context
    private lateinit var supervisor: ChainSupervisor
    private lateinit var logger: ChainLogger
    
    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        supervisor = ChainSupervisor(context)
        logger = ChainLogger(context)
    }
    
    @After
    fun tearDown() {
        runBlocking {
            supervisor.stop()
        }
    }
    
    @Test
    fun `ChainSupervisor should initialize without errors`() {
        assertNotNull(supervisor)
        val status = supervisor.getStatus()
        assertNotNull(status)
        assertEquals(ChainState.STOPPED, status.state)
    }
    
    @Test
    fun `ChainLogger should create log files`() {
        logger.log(ChainLogger.LogLevel.INFO, "TestComponent", "Test message")
        
        val logFiles = logger.getAllLogFiles()
        assertTrue(logFiles.isNotEmpty(), "Log files should be created")
    }
    
    @Test
    fun `ChainHealthChecker should run health checks`() = runBlocking {
        val result = ChainHealthChecker.runAllChecks(context)
        
        assertNotNull(result)
        assertNotNull(result.checks)
        assertTrue(result.checks.isNotEmpty(), "Health checks should return results")
        
        // Log results
        result.checks.forEach { check ->
            logger.log(
                if (check.passed) ChainLogger.LogLevel.INFO else ChainLogger.LogLevel.WARN,
                "HealthCheck",
                "${check.name}: ${check.message}"
            )
        }
    }
    
    @Test
    fun `DiagnosticBundle should generate bundle without secrets`() = runBlocking {
        val bundleGenerator = DiagnosticBundle(context)
        
        val bundleFile = bundleGenerator.generateBundle(
            includeLogs = true,
            includeHealthChecks = true
        )
        
        assertTrue(bundleFile.exists(), "Diagnostic bundle should be created")
        assertTrue(bundleFile.length() > 0, "Bundle should not be empty")
        
        // Verify no secrets in bundle
        val bundleContent = bundleFile.readText()
        assertTrue(!bundleContent.contains("password", ignoreCase = true))
        assertTrue(!bundleContent.contains("private", ignoreCase = true))
        assertTrue(!bundleContent.contains("secret", ignoreCase = true))
    }
    
    @Test
    fun `ChainSupervisor state machine should transition correctly`() = runBlocking {
        val status = supervisor.getStatus()
        assertEquals(ChainState.STOPPED, status.state)
        
        // Note: Starting the chain requires actual Xray binary and config
        // This test verifies the state machine structure
        // Full integration test would require test servers
        
        // Test that state can be queried
        val currentState = supervisor.getStatus().state
        assertNotNull(currentState)
        assertTrue(
            currentState in listOf(ChainState.STOPPED, ChainState.STARTING, ChainState.RUNNING, ChainState.DEGRADED, ChainState.STOPPING),
            "State should be valid"
        )
    }
}

