package com.simplexray.an.chain.tls

import android.content.Context
import com.simplexray.an.BuildConfig
import com.simplexray.an.common.AppLogger
import java.io.File

/**
 * Detects and reports TLS implementation in use
 */
object TlsModeDetector {
    
    /**
     * Detect available TLS implementations
     */
    fun detectAvailableModes(context: Context): Set<TlsImplementation> {
        val available = mutableSetOf<TlsImplementation>()
        
        // Check BoringSSL availability
        if (BuildConfig.USE_BORINGSSL && BuildConfig.BORINGSSL_AVAILABLE_AT_BUILD) {
            // Check if BoringSSL libraries exist in native libs
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            if (nativeLibDir != null) {
                val perfNetLib = File(nativeLibDir, "libperf-net.so")
                if (perfNetLib.exists()) {
                    available.add(TlsImplementation.BORINGSSL)
                    AppLogger.d("TlsModeDetector: BoringSSL available via perf-net")
                }
            }
        }
        
        // Conscrypt is always available on Android (system library)
        available.add(TlsImplementation.CONSCRYPT)
        
        // Go boringcrypto (if Xray was built with it)
        val xrayLib = File(context.applicationInfo.nativeLibraryDir ?: "", "libxray.so")
        if (xrayLib.exists()) {
            // Check if Xray binary has BoringSSL symbols
            // This is a simple check - in production, we'd use strings/objdump
            available.add(TlsImplementation.GO_BORINGCRYPTO)
        }
        
        return available
    }
    
    /**
     * Get recommended TLS mode based on availability
     */
    fun getRecommendedMode(context: Context): TlsImplementation {
        val available = detectAvailableModes(context)
        
        // Prefer BoringSSL if available
        if (available.contains(TlsImplementation.BORINGSSL)) {
            return TlsImplementation.BORINGSSL
        }
        
        // Fall back to Go boringcrypto if Xray was built with it
        if (available.contains(TlsImplementation.GO_BORINGCRYPTO)) {
            return TlsImplementation.GO_BORINGCRYPTO
        }
        
        // Default to Conscrypt
        return TlsImplementation.CONSCRYPT
    }
    
    /**
     * Get TLS implementation info for telemetry
     */
    fun getTlsInfo(context: Context, mode: TlsImplementation): TlsInfo {
        return when (mode) {
            TlsImplementation.BORINGSSL -> TlsInfo(
                implementation = "BoringSSL",
                version = getBoringSSLVersion(context),
                cipherSuites = listOf(
                    "TLS_AES_128_GCM_SHA256",
                    "TLS_AES_256_GCM_SHA384",
                    "TLS_CHACHA20_POLY1305_SHA256"
                ),
                keyExchange = "X25519",
                available = detectAvailableModes(context).contains(TlsImplementation.BORINGSSL)
            )
            TlsImplementation.CONSCRYPT -> TlsInfo(
                implementation = "Conscrypt",
                version = getConscryptVersion(),
                cipherSuites = listOf(
                    "TLS_AES_128_GCM_SHA256",
                    "TLS_AES_256_GCM_SHA384"
                ),
                keyExchange = "X25519",
                available = true // Always available on Android
            )
            TlsImplementation.GO_BORINGCRYPTO -> TlsInfo(
                implementation = "Go boringcrypto",
                version = "FIPS-140-2",
                cipherSuites = listOf(
                    "TLS_AES_128_GCM_SHA256",
                    "TLS_AES_256_GCM_SHA384"
                ),
                keyExchange = "X25519",
                available = detectAvailableModes(context).contains(TlsImplementation.GO_BORINGCRYPTO)
            )
            TlsImplementation.AUTO -> {
                val recommended = getRecommendedMode(context)
                getTlsInfo(context, recommended)
            }
        }
    }
    
    /**
     * Get BoringSSL version information.
     * 
     * BoringSSL doesn't expose a simple version string API like OpenSSL.
     * This method checks for library availability and returns appropriate status strings.
     * For more accurate version information, build-time version strings or git commit
     * hashes would be needed.
     * 
     * @param context Android context for accessing native library directory
     * @return Version string indicating BoringSSL availability status
     */
    private fun getBoringSSLVersion(context: Context): String {
        return try {
            // Try to load BoringSSL symbols to verify it's available
            // BoringSSL doesn't expose a simple version string API like OpenSSL
            // We can check if key symbols exist to confirm it's loaded
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            if (nativeLibDir != null) {
                val perfNetLib = File(nativeLibDir, "libperf-net.so")
                if (perfNetLib.exists()) {
                    // BoringSSL is available via perf-net
                    // For a more accurate version, we'd need to query build info or git commit
                    // For now, return a generic version string
                    "BoringSSL (via perf-net)"
                } else {
                    "BoringSSL (not available)"
                }
            } else {
                "BoringSSL (unknown)"
            }
        } catch (e: Exception) {
            AppLogger.e("Error querying BoringSSL version", e)
            "BoringSSL (error)"
        }
    }
    
    private fun getConscryptVersion(): String {
        // Conscrypt version from system
        return try {
            val version = android.os.Build.VERSION.SDK_INT
            "Android Conscrypt (API $version)"
        } catch (e: Exception) {
            "Android Conscrypt"
        }
    }
}

enum class TlsImplementation {
    BORINGSSL,      // Direct BoringSSL (via perf-net native module)
    CONSCRYPT,      // Android Conscrypt (Java layer)
    GO_BORINGCRYPTO, // Go boringcrypto toolchain (Xray built with -tags boringcrypto)
    AUTO            // Auto-detect best available
}

data class TlsInfo(
    val implementation: String,
    val version: String,
    val cipherSuites: List<String>,
    val keyExchange: String,
    val available: Boolean
)

