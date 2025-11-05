# OpenSSL Crypto Acceleration for Android

## Overview

SimpleXray supports hardware-accelerated cryptography using OpenSSL 3.3.0 on Android. This provides significant performance improvements for TLS handshakes and data encryption/decryption operations.

## Performance Benefits

| Algorithm | Software | OpenSSL (NEON) | OpenSSL (Crypto Ext) | Improvement |
|-----------|----------|----------------|----------------------|-------------|
| AES-128-GCM | ~50 MB/s | ~200 MB/s | ~800 MB/s | **4-16x faster** |
| ChaCha20-Poly1305 | ~100 MB/s | ~350 MB/s | ~350 MB/s | **3-4x faster** |
| TLS Handshake | ~100ms | ~40ms | ~20ms | **2-5x faster** |

### Binary Size Impact

- **Without OpenSSL**: ~12 MB per ABI
- **With OpenSSL**: ~14.5 MB per ABI (+2.5 MB)
- **Total APK increase**: ~10 MB (all ABIs)

The performance gain typically outweighs the size increase for most use cases.

## Building with OpenSSL

### Quick Start

```bash
# 1. Build OpenSSL for all Android architectures
./scripts/build-openssl-full.sh

# 2. Build the app (OpenSSL will be automatically detected)
./gradlew assembleRelease
```

### Manual Build

```bash
# Build OpenSSL from source (all architectures)
./scripts/build-openssl-full.sh --ndk $ANDROID_NDK_HOME --version 3.3.0

# Or build specific architecture
./scripts/build-openssl-android.sh --arch arm64 --api 24 --ndk $ANDROID_NDK_HOME

# Supported architectures:
#   - arm64-v8a (arm64)
#   - armeabi-v7a (arm)
#   - x86_64
#   - x86
```

### Build Script Options

```bash
./scripts/build-openssl-full.sh [OPTIONS]

Options:
  --ndk PATH        Path to Android NDK (default: auto-detect)
  --version VERSION OpenSSL version to build (default: 3.3.0)
  --jobs N          Number of parallel jobs (default: auto)
  --verbose, -v     Enable verbose output
  --help, -h        Show help message
```

### Build Output

OpenSSL libraries are installed to:
```
app/src/main/jni/openssl/
├── include/
│   └── openssl/
│       ├── evp.h
│       ├── aes.h
│       ├── ssl.h
│       └── ... (all headers)
└── lib/
    ├── arm64-v8a/
    │   ├── libcrypto.a
    │   └── libssl.a
    ├── armeabi-v7a/
    │   ├── libcrypto.a
    │   └── libssl.a
    ├── x86_64/
    │   ├── libcrypto.a
    │   └── libssl.a
    └── x86/
        ├── libcrypto.a
        └── libssl.a
```

## Runtime Detection

OpenSSL availability can be detected at runtime using the `OpenSSLDetector` class:

### Kotlin Example

```kotlin
import com.simplexray.an.performance.OpenSSLDetector

// Check if OpenSSL is available
val detector = OpenSSLDetector()

if (detector.isOpenSSLAvailable()) {
    // Get OpenSSL version
    val version = detector.getOpenSSLVersion()
    Log.i(TAG, "OpenSSL Version: $version")

    // Get detailed info
    val info = detector.getOpenSSLInfo()
    Log.i(TAG, "Has NEON: ${info.hasNEON}")
    Log.i(TAG, "Has Crypto Extensions: ${info.hasCryptoExtensions}")

    // Print status report
    detector.printStatusReport()

    // Run benchmarks
    val aesResult = detector.benchmarkAES()
    Log.i(TAG, "AES Throughput: ${aesResult?.throughputMBps} MB/s")

    val comparison = detector.compareSoftwareVsHardware()
    Log.i(TAG, comparison)
} else {
    Log.w(TAG, "OpenSSL not available, using software fallback")
}
```

### Quick Check

```kotlin
// Simple availability check
import com.simplexray.an.performance.hasOpenSSL

if (PerformanceManager.hasOpenSSL()) {
    // Use hardware-accelerated crypto
} else {
    // Use software fallback
}
```

### Benchmark Example

```kotlin
val detector = OpenSSLDetector()

// Run comprehensive benchmark suite
val results = detector.runBenchmarkSuite()

for ((algorithm, result) in results) {
    println("""
        $algorithm:
          Throughput: ${result.throughputMBps} MB/s
          Avg time: ${result.avgTimePerOpUs} μs/op
    """.trimIndent())
}
```

## CI/CD Integration

### GitHub Actions

The build workflow automatically:
1. Caches OpenSSL builds between runs
2. Builds OpenSSL from source if not cached
3. Verifies OpenSSL linkage in binaries
4. Uploads artifacts with OpenSSL info

```yaml
# Excerpt from .github/workflows/build.yml

- name: Cache OpenSSL
  uses: actions/cache@v4
  with:
    path: app/src/main/jni/openssl
    key: openssl-android-${{ env.OPENSSL_VERSION }}-ndk-r28c-v1

- name: Build OpenSSL from Source
  if: steps.cache-openssl.outputs.cache-hit != 'true'
  run: ./scripts/build-openssl-full.sh

- name: Verify OpenSSL Linkage
  run: ./tools/ci_verify.sh app/src/main/jniLibs/arm64-v8a/libxray.so
```

### Verification Scripts

```bash
# Check if a binary is linked with OpenSSL
./tools/check_openssl_link.sh app/src/main/jniLibs/arm64-v8a/libxray.so

# Comprehensive CI verification
./tools/ci_verify.sh app/src/main/jniLibs/arm64-v8a/libxray.so
```

## Gradle Integration

### Build Configuration

OpenSSL is automatically detected during the build:

```groovy
// In app/build.gradle
defaultConfig {
    externalNativeBuild {
        ndkBuild {
            // OpenSSL support (automatically detected by Android.mk)
            // To force disable: arguments "-DUSE_OPENSSL=0"
        }
    }

    // BuildConfig field for OpenSSL status
    def opensslAvailable = file("src/main/jni/openssl/lib/arm64-v8a/libcrypto.a").exists()
    buildConfigField "boolean", "OPENSSL_AVAILABLE_AT_BUILD", "${opensslAvailable}"
}
```

### Pre-build Check

The build will automatically check for OpenSSL and print warnings if not found:

```
⚠️  WARNING: OpenSSL not found!
   To install OpenSSL:
   1. Run: ./scripts/build-openssl-full.sh
   2. Or:  ./scripts/download-openssl.sh

   Note: App will use software crypto fallback without OpenSSL.
```

## Native Build System (Android.mk)

OpenSSL is automatically detected in `Android.mk`:

```makefile
# OpenSSL auto-detection
OPENSSL_DIR := $(LOCAL_PATH)/../../openssl

ifneq ($(wildcard $(OPENSSL_DIR)/include/openssl/evp.h),)
    # OpenSSL found - enable acceleration
    LOCAL_C_INCLUDES += $(OPENSSL_DIR)/include
    LOCAL_CPPFLAGS += -DUSE_OPENSSL=1
    LOCAL_LDLIBS += $(OPENSSL_DIR)/lib/$(TARGET_ARCH_ABI)/libssl.a
    LOCAL_LDLIBS += $(OPENSSL_DIR)/lib/$(TARGET_ARCH_ABI)/libcrypto.a
endif
```

Build output will show:
```
✅ OpenSSL enabled: /path/to/openssl
   - Headers: /path/to/openssl/include/openssl/evp.h
   - libcrypto: /path/to/openssl/lib/arm64-v8a/libcrypto.a
   - libssl: /path/to/openssl/lib/arm64-v8a/libssl.a
```

## Fallback Behavior

If OpenSSL is not available:

1. **Build-time**: No errors, just warnings
2. **Runtime**: Software-only crypto (slower but functional)
3. **Detection**: `OpenSSLDetector.isOpenSSLAvailable()` returns `false`

The app will **always work**, with or without OpenSSL. OpenSSL only provides performance improvements.

## Troubleshooting

### Build Fails with "OpenSSL headers not found"

This is just a warning, not an error. The build will continue with software fallback.

To enable OpenSSL:
```bash
./scripts/build-openssl-full.sh
```

### Binary Size Too Large

If APK size is critical, you can:

1. **Build without OpenSSL** (default if not built)
2. **Use fewer ABIs**: Edit `app/build.gradle`
   ```groovy
   ndk {
       abiFilters "arm64-v8a"  // Only 64-bit ARM
   }
   ```

### Performance Not Improved

1. Check if OpenSSL is actually being used:
   ```kotlin
   val detector = OpenSSLDetector()
   detector.printStatusReport()
   ```

2. Verify binary linkage:
   ```bash
   ./tools/check_openssl_link.sh path/to/binary.so
   ```

3. Check device capabilities:
   ```kotlin
   val info = detector.getOpenSSLInfo()
   println("NEON: ${info.hasNEON}")
   println("Crypto Extensions: ${info.hasCryptoExtensions}")
   ```

### NDK Toolchain Issues

If you see "deprecated standalone toolchain" warnings, you're using an old NDK or script. Use:

```bash
# Modern NDK toolchain (recommended)
./scripts/build-openssl-full.sh

# Uses direct clang invocation:
#   aarch64-linux-android24-clang
#   armv7a-linux-androideabi24-clang
#   x86_64-linux-android24-clang
#   i686-linux-android24-clang
```

## Advanced Usage

### Custom OpenSSL Version

```bash
# Build OpenSSL 3.4.0
OPENSSL_VERSION=3.4.0 ./scripts/build-openssl-full.sh
```

### Cross-compilation

```bash
# Build for specific architecture only
./scripts/build-openssl-android.sh --arch arm64 --api 29

# Build with custom NDK
./scripts/build-openssl-full.sh --ndk /path/to/custom/ndk
```

### Build Verification

```bash
# Verify all architectures
for abi in arm64-v8a armeabi-v7a x86_64 x86; do
    echo "Checking $abi..."
    ls -lh app/src/main/jni/openssl/lib/$abi/
done

# Check build info
cat app/src/main/jni/openssl/BUILD_INFO.txt
```

## Security Considerations

1. **Statically Linked**: OpenSSL is statically linked, no shared library dependencies
2. **No System OpenSSL**: Does not use potentially outdated system OpenSSL
3. **Reproducible Builds**: Same OpenSSL version across all builds
4. **Up-to-date**: Uses OpenSSL 3.3.0 (latest stable)

## Performance Tips

1. **Enable Hardware Crypto**: Build with OpenSSL for best performance
2. **Use AES-GCM on ARM Crypto**: Fastest on modern ARM devices
3. **Use ChaCha20 on Older Devices**: Better on devices without Crypto Extensions
4. **Benchmark**: Use `OpenSSLDetector` to find the best algorithm for your device

## Development Workflow

```bash
# 1. Clone repository
git clone https://github.com/halibiram/SimpleXray.git
cd SimpleXray

# 2. Build OpenSSL
./scripts/build-openssl-full.sh

# 3. Build app
./gradlew assembleDebug

# 4. Verify OpenSSL in app
adb install app/build/outputs/apk/debug/app-debug.apk

# 5. Check runtime status
adb shell run-as com.simplexray.an logcat | grep OpenSSL
```

## References

- OpenSSL: https://www.openssl.org/
- Android NDK: https://developer.android.com/ndk
- ARM Crypto Extensions: https://developer.arm.com/documentation/ddi0595/latest/

## Support

For issues with OpenSSL integration:

1. Check [GitHub Issues](https://github.com/halibiram/SimpleXray/issues)
2. Run diagnostics: `OpenSSLDetector().printStatusReport()`
3. Verify build: `./tools/check_openssl_link.sh <binary>`

---

**Last Updated**: 2025-11-05
**OpenSSL Version**: 3.3.0
**Supported ABIs**: arm64-v8a, armeabi-v7a, x86_64, x86
