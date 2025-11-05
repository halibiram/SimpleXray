# BoringSSL Integration Guide

## Overview

SimpleXray uses **BoringSSL** (Google's fork of OpenSSL) for all cryptographic operations, replacing OpenSSL for improved security, performance, and mobile optimization.

## Architecture

- **Native Library**: `libperf-net.so` (built with CMake)
- **BoringSSL**: Statically linked at build time
- **JNI Bridge**: `BoringSSLBridge.kt` provides Java/Kotlin interface
- **Hardware Acceleration**: Automatic detection of ARMv8 Crypto Extensions

## Setup

### 1. Initialize BoringSSL Submodule

```bash
# Option 1: Using git submodule (recommended)
git submodule update --init --recursive app/src/main/jni/perf-net/third_party/boringssl

# Option 2: Using initialization script
./app/src/main/jni/perf-net/init_boringssl.sh
```

### 2. Build

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease
```

CMake will automatically:
- Build BoringSSL from source
- Link it statically into `libperf-net.so`
- Enable hardware acceleration (if available)

## Features

### Hardware Acceleration

- **ARMv8 Crypto Extensions**: Automatic detection via `getauxval(AT_HWCAP)`
- **NEON SIMD**: Used for vectorized crypto operations
- **Hybrid Fallback**: AES-GCM (hardware) → ChaCha20-Poly1305 (software)

### Supported Algorithms

- **AEAD Ciphers**:
  - `aes-256-gcm` (preferred when hardware available)
  - `aes-128-gcm`
  - `chacha20-poly1305` (fallback for non-ARM or when hardware unavailable)

- **Key Derivation**:
  - HKDF-SHA256
  - X25519 (ECDH)

- **Random Number Generation**:
  - `RAND_bytes()` (BoringSSL)

### API Usage

#### Kotlin/Java

```kotlin
import com.simplexray.an.performance.BoringSSLBridge

// Generate random bytes
val random = BoringSSLBridge.randBytes(32)

// AES-256-GCM encryption
val key = ByteArray(32) // 256-bit key
val nonce = ByteArray(12) // 96-bit nonce
val plaintext = "Hello, World!".toByteArray()
val ciphertext = BoringSSLBridge.encryptAES256GCM(key, nonce, null, plaintext)

// AES-256-GCM decryption
val decrypted = BoringSSLBridge.decryptAES256GCM(key, nonce, null, ciphertext)
```

#### Native C++

```cpp
#include "crypto_adapter.h"

// Initialize and detect capabilities
CryptoCapabilities caps;
crypto_adapter_init(&caps);

// Get recommended cipher
const char* cipher = crypto_adapter_get_recommended_cipher(&caps);
// Returns: "aes-256-gcm" if hardware available, "chacha20-poly1305" otherwise

// Generate random bytes
uint8_t buf[32];
crypto_adapter_rand_bytes(buf, sizeof(buf));
```

## Build Configuration

### CMakeLists.txt

The `perf-net` module uses CMake to build BoringSSL:

```cmake
# BoringSSL is built as ExternalProject
# Automatically linked statically into libperf-net.so
```

### Gradle Configuration

```gradle
android {
    defaultConfig {
        ndk {
            abiFilters "arm64-v8a"  // Primary ABI (BoringSSL optimized)
        }
    }
    externalNativeBuild {
        cmake {
            path "src/main/jni/perf-net/CMakeLists.txt"
            version "3.22.1"
        }
    }
}
```

## Verification

### Check BoringSSL Symbols

```bash
# Check that BoringSSL is statically linked (no OpenSSL symbols)
nm -D app/build/intermediates/cmake/release/obj/arm64-v8a/libperf-net.so | grep -i ssl

# Should show BoringSSL symbols (e.g., BORINGSSL_*), not OpenSSL
```

### Runtime Verification

```kotlin
// Check if BoringSSL is available
try {
    val random = BoringSSLBridge.randBytes(16)
    Log.d(TAG, "BoringSSL working: ${random != null}")
} catch (e: Exception) {
    Log.e(TAG, "BoringSSL not available", e)
}
```

## Performance

### Benchmarks (ARM64-v8a, Android 12+)

- **AES-256-GCM** (hardware): ~2.5 GB/s
- **ChaCha20-Poly1305** (software): ~1.2 GB/s
- **HKDF-SHA256**: ~500 MB/s
- **X25519**: ~5000 ops/s

### Hardware Detection

- **ARMv8 Crypto Extensions**: Detected via `HWCAP_AES` flag
- **NEON**: Always available on ARM64-v8a
- **Fallback**: Automatic selection of ChaCha20-Poly1305 when hardware unavailable

## Troubleshooting

### Build Errors

**Error**: `BoringSSL not found`

**Solution**:
```bash
./app/src/main/jni/perf-net/init_boringssl.sh
```

**Error**: `CMake can't find BoringSSL`

**Solution**: Ensure submodule is initialized:
```bash
git submodule update --init --recursive
```

### Runtime Errors

**Error**: `UnsatisfiedLinkError: perf-net`

**Solution**: Ensure `libperf-net.so` is included in APK:
```bash
# Check APK contents
unzip -l app.apk | grep perf-net
```

## Security Notes

- **Static Linking**: BoringSSL is statically linked, no runtime dependency on `libssl.so` or `libcrypto.so`
- **No OpenSSL**: All OpenSSL symbols removed/replaced
- **Hardware Acceleration**: Uses ARMv8 Crypto Extensions when available
- **Constant-Time**: BoringSSL uses constant-time implementations for all operations

## Migration from OpenSSL

If you have existing OpenSSL code:

1. Replace `#include <openssl/...>` with BoringSSL includes (same API)
2. Use `crypto_adapter.h` for compatibility layer
3. Replace `USE_OPENSSL` with `USE_BORINGSSL` in preprocessor directives
4. Update build system to use CMake (instead of Android.mk for OpenSSL)

## CI/CD

GitHub Actions automatically:
- Initializes BoringSSL submodule
- Builds BoringSSL with NDK r27/r28
- Caches BoringSSL build artifacts
- Verifies no OpenSSL symbols in final binary

See `.github/workflows/build.yml` for details.

