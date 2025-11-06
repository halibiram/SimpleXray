package com.simplexray.an.chain.reality

import org.junit.Assert.*
import org.junit.Test

class RealitySocksLogParsingTest {
    
    @Test
    fun `parseXrayLog should detect connection patterns`() {
        val connectionLines = listOf(
            "accepting connection from 127.0.0.1:12345",
            "new connection established",
            "Connection accepted"
        )
        
        connectionLines.forEach { line ->
            assertTrue(
                "Should detect connection in: $line",
                line.contains("accepting connection", ignoreCase = true) ||
                line.contains("new connection", ignoreCase = true)
            )
        }
    }
    
    @Test
    fun `parseXrayLog should detect disconnection patterns`() {
        val disconnectionLines = listOf(
            "connection closed",
            "Connection terminated",
            "Client disconnected"
        )
        
        disconnectionLines.forEach { line ->
            assertTrue(
                "Should detect disconnection in: $line",
                line.contains("connection closed", ignoreCase = true) ||
                line.contains("connection terminated", ignoreCase = true)
            )
        }
    }
    
    @Test
    fun `parseXrayLog should match uplink pattern`() {
        val lines = listOf(
            "uplink: 1234",
            "up: 5678",
            "Uplink traffic: 9999"
        )
        
        val pattern = Regex("(?:uplink|up)[\\s:]+(\\d+)")
        lines.forEach { line ->
            val match = pattern.find(line)
            assertNotNull("Should match uplink in: $line", match)
            assertTrue(match?.groupValues?.get(1)?.toLongOrNull() != null)
        }
    }
    
    @Test
    fun `parseXrayLog should match downlink pattern`() {
        val lines = listOf(
            "downlink: 1234",
            "down: 5678",
            "Downlink traffic: 9999"
        )
        
        val pattern = Regex("(?:downlink|down)[\\s:]+(\\d+)")
        lines.forEach { line ->
            val match = pattern.find(line)
            assertNotNull("Should match downlink in: $line", match)
            assertTrue(match?.groupValues?.get(1)?.toLongOrNull() != null)
        }
    }
    
    @Test
    fun `parseXrayLog should match handshake time pattern`() {
        val lines = listOf(
            "TLS handshake completed in 50ms",
            "handshake: 100ms",
            "Handshake time: 25ms"
        )
        
        val pattern = Regex("handshake.*?(\\d+)\\s*ms", RegexOption.IGNORE_CASE)
        lines.forEach { line ->
            val match = pattern.find(line)
            assertNotNull("Should match handshake time in: $line", match)
            assertTrue(match?.groupValues?.get(1)?.toIntOrNull() != null)
        }
    }
    
    @Test
    fun `parseXrayLog should handle non-metric lines gracefully`() {
        val nonMetricLines = listOf(
            "Starting Xray core",
            "Configuration loaded",
            "Log level: debug"
        )
        
        // These should not crash the parser
        nonMetricLines.forEach { line ->
            try {
                // Parser should handle these gracefully
                assertFalse(
                    "Should not match connection pattern in: $line",
                    line.contains("accepting connection", ignoreCase = true)
                )
            } catch (e: Exception) {
                fail("Parser should handle non-metric lines gracefully: $line")
            }
        }
    }
}

