package com.simplexray.an.chain.supervisor

import com.simplexray.an.common.AppLogger
import com.simplexray.an.chain.pepper.PepperShaper

/**
 * Helper functions for chain operations
 */
object ChainHelper {
    
    /**
     * Check if all chain layers are ready and operational
     */
    fun checkChainHealth(config: ChainConfig): ChainHealthReport {
        val report = ChainHealthReport()
        
        // Check QUICME (managed by ChainSupervisor)
        // QUICME status is tracked in ChainSupervisor.status
        
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
        
        // QUICME is part of the chain (TUN → QUICME → Xray)
        parts.add("QUICME: configured")
        
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
        
        // Validate Xray config path
        if (config.xrayConfigPath != null) {
            if (config.xrayConfigPath.isBlank()) {
                errors.add("Xray config path is empty")
            }
        }
        
        // Check if at least one critical component is configured
        if (config.xrayConfigPath == null) {
            errors.add("Xray must be configured")
        }
        
        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
    }
    
    data class ChainHealthReport(
        var quicmeRunning: Boolean? = null,
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

