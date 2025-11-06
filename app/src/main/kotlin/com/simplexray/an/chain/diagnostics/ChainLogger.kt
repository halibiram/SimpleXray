package com.simplexray.an.chain.diagnostics

import android.content.Context
import com.simplexray.an.common.AppLogger
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Chain-specific logger with rotation and structured logging
 */
class ChainLogger(context: Context) {
    private val logDir = File(context.filesDir, "chain_logs")
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileDateFormat = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val maxFileSizeBytes = 10 * 1024 * 1024L // 10MB
    private val maxFiles = 7 // Keep 7 days of logs
    private val lineCount = AtomicLong(0)
    
    init {
        if (!logDir.exists()) {
            logDir.mkdirs()
        }
        // Cleanup old log files
        cleanupOldLogs()
    }
    
    /**
     * Get current log file (one per day)
     */
    private fun getCurrentLogFile(): File {
        val today = fileDateFormat.format(Date())
        return File(logDir, "chain_$today.log")
    }
    
    /**
     * Log chain event
     */
    @Synchronized
    fun log(level: LogLevel, component: String, message: String, error: Throwable? = null) {
        try {
            val timestamp = dateFormat.format(Date())
            val logFile = getCurrentLogFile()
            
            // Check file size and rotate if needed
            if (logFile.exists() && logFile.length() > maxFileSizeBytes) {
                rotateLogFile(logFile)
            }
            
            FileWriter(logFile, true).use { writer ->
                PrintWriter(writer).use { pw ->
                    val logLine = buildString {
                        append("[$timestamp] ")
                        append("[${level.name}] ")
                        append("[$component] ")
                        append(message)
                        if (error != null) {
                            append(" | Exception: ${error.javaClass.simpleName}: ${error.message}")
                            error.stackTrace.take(5).forEach { trace ->
                                append("\n  at ${trace}")
                            }
                        }
                    }
                    pw.println(logLine)
                    lineCount.incrementAndGet()
                }
            }
            
            // Also log to AppLogger
            when (level) {
                LogLevel.DEBUG -> AppLogger.d("[$component] $message")
                LogLevel.INFO -> AppLogger.i("[$component] $message")
                LogLevel.WARN -> AppLogger.w("[$component] $message", error)
                LogLevel.ERROR -> AppLogger.e("[$component] $message", error)
            }
        } catch (e: Exception) {
            AppLogger.e("ChainLogger: Failed to write log", e)
        }
    }
    
    /**
     * Rotate log file when it exceeds size limit
     */
    private fun rotateLogFile(logFile: File) {
        try {
            val timestamp = System.currentTimeMillis()
            val rotatedFile = File(logDir, "${logFile.nameWithoutExtension}_${timestamp}.log")
            logFile.renameTo(rotatedFile)
            AppLogger.d("ChainLogger: Rotated log file to ${rotatedFile.name}")
        } catch (e: Exception) {
            AppLogger.e("ChainLogger: Failed to rotate log file", e)
        }
    }
    
    /**
     * Cleanup old log files (keep only maxFiles days)
     */
    private fun cleanupOldLogs() {
        try {
            val files = logDir.listFiles { _, name -> name.startsWith("chain_") && name.endsWith(".log") }
                ?: return
            
            // Sort by modification time (newest first)
            val sortedFiles = files.sortedByDescending { it.lastModified() }
            
            // Delete files beyond maxFiles
            sortedFiles.drop(maxFiles).forEach { file ->
                if (file.delete()) {
                    AppLogger.d("ChainLogger: Deleted old log file: ${file.name}")
                }
            }
        } catch (e: Exception) {
            AppLogger.e("ChainLogger: Failed to cleanup old logs", e)
        }
    }
    
    /**
     * Get recent logs (last N lines)
     */
    fun getRecentLogs(lines: Int = 100): String {
        return try {
            val logFile = getCurrentLogFile()
            if (!logFile.exists()) return ""
            
            val allLines = logFile.readLines()
            allLines.takeLast(lines).joinToString("\n")
        } catch (e: Exception) {
            AppLogger.e("ChainLogger: Failed to read logs", e)
            ""
        }
    }
    
    /**
     * Get all log files
     */
    fun getAllLogFiles(): List<File> {
        return logDir.listFiles { _, name -> name.startsWith("chain_") && name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }
    
    /**
     * Get logcat command for this app
     */
    fun getLogcatCommand(pid: Int? = null): String {
        val packageName = "com.simplexray.an"
        return if (pid != null) {
            "adb logcat --pid=$pid | grep -E '(SimpleXray|RealitySocks|Hysteria2|PepperShaper|ChainSupervisor)'"
        } else {
            "adb logcat | grep -E '(SimpleXray|RealitySocks|Hysteria2|PepperShaper|ChainSupervisor)'"
        }
    }
    
    enum class LogLevel {
        DEBUG,
        INFO,
        WARN,
        ERROR
    }
}

