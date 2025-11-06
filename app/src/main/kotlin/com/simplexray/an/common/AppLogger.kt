package com.simplexray.an.common

import android.util.Log
import com.simplexray.an.BuildConfig
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Centralized logging utility for the SimpleXray application.
 * 
 * All logs are automatically disabled in release builds to prevent
 * exposing sensitive information and improve performance.
 * 
 * In production builds, errors and exceptions are automatically sent to
 * Firebase Crashlytics for monitoring and debugging.
 * 
 * Usage:
 * - AppLogger.d("Debug message")
 * - AppLogger.e("Error message", exception)
 * - AppLogger.w("Warning message")
 * - AppLogger.i("Info message")
 * - AppLogger.v("Verbose message")
 */
object AppLogger {
    private const val LOG_TAG = "SimpleXray"
    
    /**
     * Firebase Crashlytics instance (null if not configured).
     * Lazy initialization to avoid crashes if Firebase is not set up.
     */
    private val crashlytics: FirebaseCrashlytics? by lazy {
        try {
            FirebaseCrashlytics.getInstance()
        } catch (e: Exception) {
            // Firebase not configured, that's okay
            null
        }
    }

    /**
     * Log a debug message.
     * Only logs in debug builds.
     */
    fun d(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(LOG_TAG, message)
        }
    }

    /**
     * Log an error message.
     * Only logs in debug builds.
     * In production, errors are sent to Firebase Crashlytics for monitoring.
     * 
     * @param message Error message
     * @param throwable Optional exception to log
     */
    fun e(message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            if (throwable != null) {
                Log.e(LOG_TAG, message, throwable)
            } else {
                Log.e(LOG_TAG, message)
            }
        } else {
            // In production, send to Firebase Crashlytics
            try {
                crashlytics?.let { firebaseCrashlytics ->
                    firebaseCrashlytics.log("ERROR: $message")
                    if (throwable != null) {
                        firebaseCrashlytics.recordException(throwable)
                    } else {
                        // Create a custom exception for non-throwable errors
                        firebaseCrashlytics.recordException(Exception(message))
                    }
                }
            } catch (e: Exception) {
                // Log to system log if Crashlytics fails (fallback mechanism)
                // Use system log as fallback to ensure errors are not completely lost
                try {
                    Log.e(LOG_TAG, "Crashlytics error reporting failed: ${e.message}", e)
                    // Also log the original error message that we tried to report
                    Log.e(LOG_TAG, "Original error: $message", throwable)
                } catch (logException: Exception) {
                    // Last resort: write to stderr if even system log fails
                    System.err.println("FATAL: Both Crashlytics and system log failed. Original error: $message")
                    if (throwable != null) {
                        throwable.printStackTrace()
                    }
                    logException.printStackTrace()
                }
            }
        }
    }

    /**
     * Log a warning message.
     * Only logs in debug builds.
     * In production, warnings with throwables are sent to Crashlytics as non-fatal.
     */
    fun w(message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            if (throwable != null) {
                Log.w(LOG_TAG, message, throwable)
            } else {
                Log.w(LOG_TAG, message)
            }
        } else if (throwable != null) {
            // In production, send warnings with exceptions to Crashlytics as non-fatal
            try {
                crashlytics?.let { firebaseCrashlytics ->
                    firebaseCrashlytics.log("WARNING: $message")
                    firebaseCrashlytics.recordException(throwable)
                }
            } catch (e: Exception) {
                // Log to system log if Crashlytics fails (fallback mechanism)
                try {
                    Log.e(LOG_TAG, "Crashlytics warning reporting failed: ${e.message}", e)
                    // Also log the original warning message
                    Log.w(LOG_TAG, "Original warning: $message", throwable)
                } catch (logException: Exception) {
                    // Last resort: write to stderr if even system log fails
                    System.err.println("FATAL: Both Crashlytics and system log failed. Original warning: $message")
                    if (throwable != null) {
                        throwable.printStackTrace()
                    }
                    logException.printStackTrace()
                }
            }
        }
    }

    /**
     * Log an info message.
     * Only logs in debug builds.
     */
    fun i(message: String) {
        if (BuildConfig.DEBUG) {
            Log.i(LOG_TAG, message)
        }
    }

    /**
     * Log a verbose message.
     * Only logs in debug builds.
     */
    fun v(message: String) {
        if (BuildConfig.DEBUG) {
            Log.v(LOG_TAG, message)
        }
    }
    
    /**
     * Set a custom key-value pair for Crashlytics reporting.
     * This helps with debugging by adding context to crash reports.
     * 
     * @param key Custom key name
     * @param value Custom value
     */
    fun setCustomKey(key: String, value: String) {
        try {
            // Sanitize value to prevent privacy leaks
            val sanitizedValue = sanitize(value)
            crashlytics?.setCustomKey(key, sanitizedValue)
        } catch (e: Exception) {
            // Log configuration errors in debug builds
            if (BuildConfig.DEBUG) {
                Log.w(LOG_TAG, "Failed to set Crashlytics custom key '$key': ${e.message}", e)
            }
        }
    }
    
    /**
     * Set a custom key-value pair for Crashlytics reporting (boolean).
     */
    fun setCustomKey(key: String, value: Boolean) {
        try {
            crashlytics?.setCustomKey(key, value)
        } catch (e: Exception) {
            // Fail silently
        }
    }
    
    /**
     * Set a custom key-value pair for Crashlytics reporting (int).
     */
    fun setCustomKey(key: String, value: Int) {
        try {
            crashlytics?.setCustomKey(key, value)
        } catch (e: Exception) {
            // Fail silently
        }
    }
    
    /**
     * Set user identifier for Crashlytics.
     * This helps identify which users are affected by crashes.
     * 
     * @param userId User identifier (should be anonymized for privacy)
     */
    fun setUserId(userId: String) {
        try {
            crashlytics?.setUserId(userId)
        } catch (e: Exception) {
            // Fail silently
        }
    }
    
    /**
     * Add a breadcrumb log to Crashlytics.
     * Breadcrumbs help track the sequence of events leading to a crash.
     * 
     * @param message Breadcrumb message
     */
    fun addBreadcrumb(message: String) {
        try {
            crashlytics?.log(message)
        } catch (e: Exception) {
            // Fail silently
        }
    }
    
    /**
     * Sanitize sensitive data from log messages.
     * Removes or redacts passwords, tokens, API keys, and other sensitive information.
     * 
     * @param message Original message that may contain sensitive data
     * @return Sanitized message with sensitive data redacted
     */
    fun sanitize(message: String): String {
        // Always sanitize, even in debug builds, to prevent accidental leaks
        // Debug builds can use dSafe() method if they need to see sanitized data
        return sanitizeInternal(message)
    }
    
    /**
     * Internal sanitization logic.
     * Redacts common patterns of sensitive data.
     */
    private fun sanitizeInternal(message: String): String {
        var sanitized = message
        
        // Comprehensive sanitization patterns for sensitive data
        // Redact password patterns: password=xxx, pwd=xxx, pass=xxx
        sanitized = Regex("(?i)(password|pwd|pass)[=:](\\S+)").replace(sanitized) {
            "${it.groupValues[1]}=***REDACTED***"
        }
        
        // Redact token patterns: token=xxx, api_key=xxx, apikey=xxx, secret=xxx
        sanitized = Regex("(?i)(token|api[_-]?key|apikey|secret|auth[_-]?token|access[_-]?token|refresh[_-]?token)[=:](\\S+)").replace(sanitized) {
            "${it.groupValues[1]}=***REDACTED***"
        }
        
        // Redact UUID patterns that might be session IDs
        sanitized = Regex("(?i)(session[_-]?id|sessionid)[=:]([a-f0-9-]{20,})").replace(sanitized) {
            "${it.groupValues[1]}=***REDACTED***"
        }
        
        // Redact private keys (PEM format)
        sanitized = Regex("-----BEGIN\\s+(?:PRIVATE|RSA PRIVATE|EC PRIVATE|DSA PRIVATE)\\s+KEY-----.*?-----END\\s+(?:PRIVATE|RSA PRIVATE|EC PRIVATE|DSA PRIVATE)\\s+KEY-----", RegexOption.DOT_MATCHES_ALL).replace(sanitized) {
            "***REDACTED_PRIVATE_KEY***"
        }
        
        // Redact certificate patterns
        sanitized = Regex("-----BEGIN\\s+CERTIFICATE-----.*?-----END\\s+CERTIFICATE-----", RegexOption.DOT_MATCHES_ALL).replace(sanitized) {
            "***REDACTED_CERTIFICATE***"
        }
        
        // Redact IP addresses in sensitive contexts (optional - may be too aggressive)
        // sanitized = Regex("\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\b").replace(sanitized) {
        //     "***REDACTED_IP***"
        // }
        
        // Redact long hex strings that might be keys (32+ chars)
        sanitized = Regex("\\b[a-f0-9]{32,}\\b").replace(sanitized) {
            "***REDACTED_HEX***"
        }
        
        // Redact credit card patterns (13-19 digits with optional separators)
        sanitized = Regex("\\b(?:\\d[ -]?){13,19}\\b").replace(sanitized) {
            "***REDACTED_CARD***"
        }
        
        // Redact SSN patterns (XXX-XX-XXXX)
        sanitized = Regex("\\b\\d{3}-\\d{2}-\\d{4}\\b").replace(sanitized) {
            "***REDACTED_SSN***"
        }
        
        // Redact email addresses in sensitive contexts (optional - may be too aggressive)
        // sanitized = Regex("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b").replace(sanitized) {
        //     "***REDACTED_EMAIL***"
        // }
        
        return sanitized
    }
    
    /**
     * Log a debug message with automatic sanitization in production.
     * Only logs in debug builds.
     */
    fun dSafe(message: String) {
        d(sanitize(message))
    }
    
    /**
     * Log an error message with automatic sanitization in production.
     */
    fun eSafe(message: String, throwable: Throwable? = null) {
        e(sanitize(message), throwable)
    }
    
    /**
     * Log a warning message with automatic sanitization in production.
     */
    fun wSafe(message: String, throwable: Throwable? = null) {
        w(sanitize(message), throwable)
    }
    
    /**
     * Log an info message with automatic sanitization in production.
     */
    fun iSafe(message: String) {
        i(sanitize(message))
    }
}

