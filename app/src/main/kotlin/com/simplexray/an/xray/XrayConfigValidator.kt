package com.simplexray.an.xray

import android.content.Context
import com.simplexray.an.common.AppLogger
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Validates Xray-core configuration JSON files
 * 
 * Uses xray-core -test flag to validate config before use
 */
object XrayConfigValidator {
    
    /**
     * Validation result
     */
    data class ValidationResult(
        val isValid: Boolean,
        val message: String,
        val errors: List<String> = emptyList()
    )
    
    /**
     * Test config JSON file using xray-core -test flag
     * 
     * @param context Android Context
     * @param configFile Config file to validate (default: xray.json)
     * @return ValidationResult with validation status and messages
     */
    fun validateConfig(
        context: Context,
        configFile: File? = null
    ): ValidationResult {
        val cfg = configFile ?: File(context.filesDir, "xray.json")
        
        // Validate config file path
        if (!cfg.canonicalPath.startsWith(context.filesDir.canonicalPath) && 
            !cfg.canonicalPath.startsWith(context.cacheDir.canonicalPath)) {
            return ValidationResult(
                isValid = false,
                message = "Config file path outside allowed directories: ${cfg.absolutePath}",
                errors = listOf("Invalid config file path")
            )
        }
        
        // Check if config file exists
        if (!cfg.exists()) {
            return ValidationResult(
                isValid = false,
                message = "Config file not found: ${cfg.absolutePath}",
                errors = listOf("Config file does not exist")
            )
        }
        
        // Validate config file size (max 10MB)
        if (cfg.length() > 10 * 1024 * 1024) {
            return ValidationResult(
                isValid = false,
                message = "Config file too large: ${cfg.length()} bytes (max 10MB)",
                errors = listOf("Config file exceeds size limit")
            )
        }
        
        // Get xray binary
        val bin = XrayCoreLauncher.copyExecutable(context) ?: run {
            return ValidationResult(
                isValid = false,
                message = "Xray binary not found",
                errors = listOf("Xray binary unavailable")
            )
        }
        
        // Validate binary path
        val nativeLibDir = context.applicationInfo.nativeLibraryDir ?: ""
        if (!bin.canonicalPath.startsWith(context.filesDir.canonicalPath) &&
            !bin.canonicalPath.startsWith(nativeLibDir)) {
            return ValidationResult(
                isValid = false,
                message = "Binary path outside allowed directories: ${bin.absolutePath}",
                errors = listOf("Invalid binary path")
            )
        }
        
        // Execute xray-core -test -config config.json
        return try {
            val pb = ProcessBuilder(
                bin.absolutePath,
                "-test",
                "-config",
                cfg.absolutePath
            )
            
            val filesDir = context.filesDir
            val cacheDir = context.cacheDir
            val environment = pb.environment()
            
            // Set environment variables (same as XrayCoreLauncher)
            environment["HOME"] = filesDir.path
            environment["TMPDIR"] = cacheDir.path
            environment["TMP"] = cacheDir.path
            environment.remove("BORINGSSL_TEST_DATA_ROOT")
            environment.remove("TEST_DATA_ROOT")
            environment.remove("TEST_DIR")
            environment.remove("GO_TEST_DIR")
            
            pb.directory(filesDir)
            pb.redirectErrorStream(true)
            
            // Capture output
            val outputFile = File(filesDir, "xray_test_output.log")
            pb.redirectOutput(outputFile)
            
            val process = pb.start()
            
            // Wait for process with timeout (30 seconds)
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            
            if (!finished) {
                process.destroyForcibly()
                return ValidationResult(
                    isValid = false,
                    message = "Config validation timeout (exceeded 30 seconds)",
                    errors = listOf("Validation process timeout")
                )
            }
            
            val exitCode = process.exitValue()
            
            // Read output
            val output = if (outputFile.exists()) {
                outputFile.readText().take(2000) // Limit to 2KB
            } else {
                ""
            }
            
            // Clean up output file
            try {
                outputFile.delete()
            } catch (e: Exception) {
                AppLogger.w("Failed to delete test output file: ${e.message}")
            }
            
            if (exitCode == 0) {
                ValidationResult(
                    isValid = true,
                    message = "Config validation successful",
                    errors = emptyList()
                )
            } else {
                ValidationResult(
                    isValid = false,
                    message = "Config validation failed (exit code: $exitCode)",
                    errors = if (output.isNotBlank()) {
                        output.lines().filter { it.isNotBlank() }
                    } else {
                        listOf("Validation failed with exit code $exitCode")
                    }
                )
            }
        } catch (e: Exception) {
            AppLogger.e("Config validation error: ${e.message}", e)
            ValidationResult(
                isValid = false,
                message = "Config validation error: ${e.message}",
                errors = listOf(e.message ?: "Unknown error")
            )
        }
    }
    
    /**
     * Quick validation check (synchronous, blocks thread)
     * Use this for pre-flight checks before starting Xray
     */
    fun quickValidate(context: Context, configFile: File? = null): Boolean {
        return validateConfig(context, configFile).isValid
    }
}








