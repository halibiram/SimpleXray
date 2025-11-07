package com.simplexray.an.chain.supervisor

import com.simplexray.an.common.AppLogger
import com.simplexray.an.chain.hysteria2.Hysteria2
import com.simplexray.an.chain.pepper.PepperShaper
import com.simplexray.an.chain.reality.RealitySocks

/**
 * Helper functions for chain operations
 */
object ChainHelper {
    
    /**
     * Check if all chain layers are ready and operational
     */
    fun checkChainHealth(config: ChainConfig): ChainHealthReport {
        val report = ChainHealthReport()
        
        // Check Reality SOCKS
        if (config.realityConfig != null) {
            val realityStatus = RealitySocks.getStatus()
            report.realityRunning = realityStatus.isRunning
            report.realityPort = realityStatus.localPort
            if (!realityStatus.isRunning) {
                report.issues.add("Reality SOCKS is not running: ${realityStatus.lastError ?: "Unknown error"}")
            }
        } else {
            report.realityRunning = null // Not configured
        }
        
        // Check Hysteria2
        if (config.hysteria2Config != null) {
            val hy2Running = Hysteria2.isRunning()
            report.hysteria2Running = hy2Running
            report.hysteria2BinaryAvailable = Hysteria2.isBinaryAvailable()
            val hy2Metrics = Hysteria2.getMetrics()
            report.hysteria2Connected = hy2Metrics.isConnected
            report.hysteria2Rtt = hy2Metrics.rtt
            
            if (!hy2Running) {
                report.issues.add("Hysteria2 is not running")
            } else if (!report.hysteria2BinaryAvailable) {
                report.issues.add("Hysteria2 binary not available (running in simulation mode)")
            }
        } else {
            report.hysteria2Running = null // Not configured
        }
        
        // Check PepperShaper
        if (config.pepperParams != null) {
            // PepperShaper doesn't expose running status directly
            // We check if native library is loaded
            val pepperAvailable = try {
                PepperShaper.getStats() // Try to get stats to check if initialized
                true
            } catch (e: Exception) {
                false
            }
            report.pepperShaperAvailable = pepperAvailable
            
            if (!pepperAvailable) {
                report.issues.add("PepperShaper native library not available")
            }
        } else {
            report.pepperShaperAvailable = null // Not configured
        }
        
        // Overall health
        report.isHealthy = report.issues.isEmpty()
        
        return report
    }
    
    /**
     * Get chain status summary for logging/UI
     */
    fun getChainStatusSummary(config: ChainConfig): String {
        val parts = mutableListOf<String>()
        
        if (config.realityConfig != null) {
            val status = RealitySocks.getStatus()
            parts.add("Reality: ${if (status.isRunning) "✓" else "✗"}")
        }
        
        if (config.hysteria2Config != null) {
            val running = Hysteria2.isRunning()
            val binary = Hysteria2.isBinaryAvailable()
            val status = if (running) "✓" else "✗"
            val sim = if (!binary) " (sim)" else ""
            parts.add("Hysteria2: $status$sim")
        }
        
        if (config.pepperParams != null) {
            parts.add("PepperShaper: ${if (try { PepperShaper.getStats(); true } catch (e: Exception) { false }) "✓" else "✗"}")
        }
        
        if (config.xrayConfigPath != null) {
            parts.add("Xray: configured")
        }
        
        return parts.joinToString(", ")
    }
    
    /**
     * Validate chain configuration before starting
     */
    fun validateChainConfig(config: ChainConfig): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        
        // Validate Reality config
        if (config.realityConfig != null) {
            if (config.realityConfig.server.isBlank()) {
                errors.add("Reality server address is empty")
            }
            if (config.realityConfig.port <= 0 || config.realityConfig.port > 65535) {
                errors.add("Reality port is invalid: ${config.realityConfig.port}")
            }
            if (config.realityConfig.publicKey.isBlank()) {
                errors.add("Reality publicKey is empty")
            }
            if (config.realityConfig.localPort <= 0 || config.realityConfig.localPort > 65535) {
                errors.add("Reality localPort is invalid: ${config.realityConfig.localPort}")
            }
        }
        
        // Validate Hysteria2 config
        if (config.hysteria2Config != null) {
            if (config.hysteria2Config.server.isBlank()) {
                errors.add("Hysteria2 server address is empty")
            }
            if (config.hysteria2Config.port <= 0 || config.hysteria2Config.port > 65535) {
                errors.add("Hysteria2 port is invalid: ${config.hysteria2Config.port}")
            }
            if (config.hysteria2Config.auth.isBlank()) {
                errors.add("Hysteria2 auth is empty")
            }
            
            // Check if binary is available
            if (!Hysteria2.isBinaryAvailable()) {
                warnings.add("Hysteria2 binary not available - will run in simulation mode")
            }
        }
        
        // Validate Xray config path
        if (config.xrayConfigPath != null) {
            if (config.xrayConfigPath.isBlank()) {
                errors.add("Xray config path is empty")
            }
        }
        
        // Check if at least one critical component is configured
        if (config.realityConfig == null && config.xrayConfigPath == null) {
            errors.add("At least one of Reality or Xray must be configured")
        }
        
        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
    }
    
    data class ChainHealthReport(
        var realityRunning: Boolean? = null,
        var realityPort: Int? = null,
        var hysteria2Running: Boolean? = null,
        var hysteria2BinaryAvailable: Boolean = false,
        var hysteria2Connected: Boolean = false,
        var hysteria2Rtt: Long = 0,
        var pepperShaperAvailable: Boolean? = null,
        var isHealthy: Boolean = false,
        val issues: MutableList<String> = mutableListOf()
    )
    
    data class ValidationResult(
        val isValid: Boolean,
        val errors: List<String>,
        val warnings: List<String>
    )
}

