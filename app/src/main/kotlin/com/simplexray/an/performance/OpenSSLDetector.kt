package com.simplexray.an.performance

import android.util.Log
import timber.log.Timber
import kotlin.system.measureTimeMillis

/**
 * OpenSSL Runtime Detection and Validation
 *
 * Detects if OpenSSL crypto acceleration is available and benchmarks
 * performance compared to software fallback.
 *
 * Usage:
 * ```kotlin
 * val detector = OpenSSLDetector()
 * if (detector.isOpenSSLAvailable()) {
 *     Log.i(TAG, "OpenSSL Version: ${detector.getOpenSSLVersion()}")
 *     detector.runBenchmark()
 * }
 * ```
 */
class OpenSSLDetector {

    companion object {
        private const val TAG = "OpenSSLDetector"
        private const val BENCHMARK_ITERATIONS = 1000
        private const val BENCHMARK_DATA_SIZE = 16384  // 16KB

        init {
            try {
                System.loadLibrary("perf-net")
                Timber.d("perf-net library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Timber.w(e, "Failed to load perf-net library")
            }
        }
    }

    /**
     * Native methods for OpenSSL detection and benchmarking
     */
    private external fun nativeHasOpenSSL(): Boolean
    private external fun nativeGetOpenSSLVersion(): String?
    private external fun nativeGetOpenSSLBuildInfo(): String?
    private external fun nativeBenchmarkAESEncrypt(iterations: Int, dataSize: Int): Long
    private external fun nativeBenchmarkChaChaPoly(iterations: Int, dataSize: Int): Long

    /**
     * Data class for OpenSSL information
     */
    data class OpenSSLInfo(
        val available: Boolean,
        val version: String?,
        val buildInfo: String?,
        val hasNEON: Boolean,
        val hasCryptoExtensions: Boolean
    )

    /**
     * Data class for benchmark results
     */
    data class BenchmarkResult(
        val algorithm: String,
        val iterations: Int,
        val dataSize: Int,
        val totalTimeMs: Long,
        val avgTimePerOpUs: Double,
        val throughputMBps: Double
    ) {
        override fun toString(): String {
            return """
                |$algorithm Benchmark:
                |  Iterations: $iterations
                |  Data size: ${dataSize / 1024}KB
                |  Total time: ${totalTimeMs}ms
                |  Avg per operation: ${"%.2f".format(avgTimePerOpUs)}μs
                |  Throughput: ${"%.2f".format(throughputMBps)}MB/s
            """.trimMargin()
        }
    }

    /**
     * Check if OpenSSL is available in the native library
     */
    fun isOpenSSLAvailable(): Boolean {
        return try {
            nativeHasOpenSSL()
        } catch (e: UnsatisfiedLinkError) {
            Timber.w(e, "OpenSSL check failed - native method not found")
            false
        } catch (e: Exception) {
            Timber.e(e, "Error checking OpenSSL availability")
            false
        }
    }

    /**
     * Get OpenSSL version string (e.g., "OpenSSL 3.3.0")
     */
    fun getOpenSSLVersion(): String? {
        return try {
            nativeGetOpenSSLVersion()
        } catch (e: UnsatisfiedLinkError) {
            Timber.w("OpenSSL version check failed - native method not found")
            null
        } catch (e: Exception) {
            Timber.e(e, "Error getting OpenSSL version")
            null
        }
    }

    /**
     * Get OpenSSL build information
     */
    fun getOpenSSLBuildInfo(): String? {
        return try {
            nativeGetOpenSSLBuildInfo()
        } catch (e: UnsatisfiedLinkError) {
            Timber.w("OpenSSL build info check failed - native method not found")
            null
        } catch (e: Exception) {
            Timber.e(e, "Error getting OpenSSL build info")
            null
        }
    }

    /**
     * Get comprehensive OpenSSL information
     */
    fun getOpenSSLInfo(): OpenSSLInfo {
        val hasOpenSSL = isOpenSSLAvailable()
        val hasNEON = try {
            PerformanceManager.nativeHasNEON()
        } catch (e: Exception) {
            false
        }
        val hasCryptoExt = try {
            PerformanceManager.nativeHasCryptoExtensions()
        } catch (e: Exception) {
            false
        }

        return OpenSSLInfo(
            available = hasOpenSSL,
            version = if (hasOpenSSL) getOpenSSLVersion() else null,
            buildInfo = if (hasOpenSSL) getOpenSSLBuildInfo() else null,
            hasNEON = hasNEON,
            hasCryptoExtensions = hasCryptoExt
        )
    }

    /**
     * Run AES encryption benchmark
     */
    fun benchmarkAES(
        iterations: Int = BENCHMARK_ITERATIONS,
        dataSize: Int = BENCHMARK_DATA_SIZE
    ): BenchmarkResult? {
        if (!isOpenSSLAvailable()) {
            Timber.w("OpenSSL not available, skipping AES benchmark")
            return null
        }

        return try {
            val totalTimeMs = nativeBenchmarkAESEncrypt(iterations, dataSize)
            val avgTimePerOpUs = (totalTimeMs * 1000.0) / iterations
            val totalDataMB = (iterations.toLong() * dataSize) / (1024.0 * 1024.0)
            val throughputMBps = totalDataMB / (totalTimeMs / 1000.0)

            BenchmarkResult(
                algorithm = "AES-128-GCM",
                iterations = iterations,
                dataSize = dataSize,
                totalTimeMs = totalTimeMs,
                avgTimePerOpUs = avgTimePerOpUs,
                throughputMBps = throughputMBps
            )
        } catch (e: UnsatisfiedLinkError) {
            Timber.w("AES benchmark failed - native method not found")
            null
        } catch (e: Exception) {
            Timber.e(e, "Error running AES benchmark")
            null
        }
    }

    /**
     * Run ChaCha20-Poly1305 benchmark
     */
    fun benchmarkChaCha20Poly1305(
        iterations: Int = BENCHMARK_ITERATIONS,
        dataSize: Int = BENCHMARK_DATA_SIZE
    ): BenchmarkResult? {
        if (!isOpenSSLAvailable()) {
            Timber.w("OpenSSL not available, skipping ChaCha20 benchmark")
            return null
        }

        return try {
            val totalTimeMs = nativeBenchmarkChaChaPoly(iterations, dataSize)
            val avgTimePerOpUs = (totalTimeMs * 1000.0) / iterations
            val totalDataMB = (iterations.toLong() * dataSize) / (1024.0 * 1024.0)
            val throughputMBps = totalDataMB / (totalTimeMs / 1000.0)

            BenchmarkResult(
                algorithm = "ChaCha20-Poly1305",
                iterations = iterations,
                dataSize = dataSize,
                totalTimeMs = totalTimeMs,
                avgTimePerOpUs = avgTimePerOpUs,
                throughputMBps = throughputMBps
            )
        } catch (e: UnsatisfiedLinkError) {
            Timber.w("ChaCha20 benchmark failed - native method not found")
            null
        } catch (e: Exception) {
            Timber.e(e, "Error running ChaCha20 benchmark")
            null
        }
    }

    /**
     * Run comprehensive benchmark suite
     */
    fun runBenchmarkSuite(): Map<String, BenchmarkResult> {
        Timber.i("Starting OpenSSL benchmark suite...")

        val results = mutableMapOf<String, BenchmarkResult>()

        // Benchmark AES
        benchmarkAES()?.let { result ->
            results["AES"] = result
            Timber.i(result.toString())
        }

        // Benchmark ChaCha20-Poly1305
        benchmarkChaCha20Poly1305()?.let { result ->
            results["ChaCha20"] = result
            Timber.i(result.toString())
        }

        return results
    }

    /**
     * Print comprehensive OpenSSL status report
     */
    fun printStatusReport() {
        val info = getOpenSSLInfo()

        val report = buildString {
            appendLine("╔════════════════════════════════════════════════╗")
            appendLine("║      OpenSSL Crypto Acceleration Status       ║")
            appendLine("╠════════════════════════════════════════════════╣")
            appendLine("║ OpenSSL Available: ${if (info.available) "✅ YES" else "❌ NO "}")
            if (info.version != null) {
                appendLine("║ Version: ${info.version.padEnd(38)}║")
            }
            if (info.buildInfo != null) {
                appendLine("║ Build: ${info.buildInfo.take(40).padEnd(40)}║")
            }
            appendLine("║ NEON Support: ${if (info.hasNEON) "✅ YES" else "❌ NO "}")
            appendLine("║ Crypto Extensions: ${if (info.hasCryptoExtensions) "✅ YES" else "❌ NO "}")
            appendLine("╚════════════════════════════════════════════════╝")
        }

        Timber.i(report)
        Log.i(TAG, report)
    }

    /**
     * Compare OpenSSL performance vs software fallback
     *
     * Note: This requires a software-only implementation for comparison.
     * If not available, only OpenSSL performance is measured.
     */
    fun compareSoftwareVsHardware(): String {
        val info = getOpenSSLInfo()

        if (!info.available) {
            return "OpenSSL not available - using software fallback only"
        }

        val aesBenchmark = benchmarkAES()
        val chachaBenchmark = benchmarkChaCha20Poly1305()

        return buildString {
            appendLine("Performance Comparison:")
            appendLine("─────────────────────────")

            if (aesBenchmark != null) {
                appendLine("AES-128-GCM:")
                appendLine("  Hardware: ${"%.2f".format(aesBenchmark.throughputMBps)} MB/s")
                if (info.hasCryptoExtensions) {
                    appendLine("  Using: ARM Crypto Extensions (AES-NI)")
                } else if (info.hasNEON) {
                    appendLine("  Using: NEON SIMD")
                }
            }

            if (chachaBenchmark != null) {
                appendLine("ChaCha20-Poly1305:")
                appendLine("  Hardware: ${"%.2f".format(chachaBenchmark.throughputMBps)} MB/s")
                if (info.hasNEON) {
                    appendLine("  Using: NEON SIMD")
                }
            }

            if (aesBenchmark != null && chachaBenchmark != null) {
                val speedup = if (aesBenchmark.throughputMBps > chachaBenchmark.throughputMBps) {
                    aesBenchmark.throughputMBps / chachaBenchmark.throughputMBps
                } else {
                    chachaBenchmark.throughputMBps / aesBenchmark.throughputMBps
                }

                appendLine()
                appendLine("Recommendation:")
                if (info.hasCryptoExtensions && aesBenchmark.throughputMBps > chachaBenchmark.throughputMBps) {
                    appendLine("  ✅ Use AES-GCM (${"%.1f".format(speedup)}x faster)")
                } else {
                    appendLine("  ✅ Use ChaCha20-Poly1305 (better performance)")
                }
            }
        }
    }
}

/**
 * Extension function for easy integration
 */
fun PerformanceManager.Companion.detectOpenSSL(): OpenSSLDetector.OpenSSLInfo {
    return OpenSSLDetector().getOpenSSLInfo()
}

/**
 * Extension function for quick status check
 */
fun PerformanceManager.Companion.hasOpenSSL(): Boolean {
    return OpenSSLDetector().isOpenSSLAvailable()
}
