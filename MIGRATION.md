# BoringSSL Migration Guide - SimpleXray

## Quick Start

```bash
# 1. Build BoringSSL libraries
cd external/boringssl
python3 build_all_abis.py

# 2. Build SimpleXray with BoringSSL
cd ../..
./gradlew assembleDebug -PuseBoringssl=true

# 3. Verify
./gradlew testDebugUnitTest
```

## Architecture Changes

### Before (OpenSSL 3.3.0)
```
JNI Code → OpenSSL Headers → libssl.a/libcrypto.a
```

### After (BoringSSL + Wrapper)
```
JNI Code → crypto_wrapper → BoringSSL/OpenSSL → libs
```

## Runtime Configuration

Set `SXR_SSL_MODE` environment variable:
- `boringssl` - Use BoringSSL (default)
- `openssl` - Use OpenSSL (fallback)
- `hybrid` - Auto-select best available

```bash
export SXR_SSL_MODE=boringssl
./gradlew assembleDebug
```

## API Migration Table

| Old (OpenSSL) | New (Crypto Wrapper) |
|---------------|----------------------|
| `RAND_bytes(buf, len)` | `sxr_rand_bytes(buf, len)` |
| `EVP_aes_128_gcm()` | `sxr_aes_gcm_encrypt(...)` |
| `EVP_chacha20_poly1305()` | `sxr_chacha20_poly1305_encrypt(...)` |
| `OpenSSL_version()` | `sxr_crypto_get_version()` |

## Performance Optimizations

### Hardware Acceleration Flags

| ABI | Optimization Flags |
|-----|-------------------|
| arm64-v8a | `-march=armv8-a+crypto+simd` |
| armeabi-v7a | `-mfpu=neon` |
| x86_64 | `-maes -mssse3` |
| x86 | `-maes -msse3` |

### Expected Performance (Snapdragon 888)
- AES-GCM with HW: 500-1000 MB/s
- ChaCha20-Poly1305: 300-600 MB/s

## Testing

```bash
# Unit tests
./gradlew testDebugUnitTest

# Crypto-specific tests
./gradlew testDebugUnitTest --tests CryptoTest

# Performance benchmarks
./gradlew connectedDebugAndroidTest
```

## Rollback Plan

### Option 1: Runtime Switch (Immediate)
```bash
export SXR_SSL_MODE=openssl
```

### Option 2: Rebuild with OpenSSL
```bash
./gradlew clean
./gradlew assembleRelease -PuseBoringssl=false
```

### Option 3: Git Revert
```bash
git revert <migration-commit-sha>
./gradlew clean assembleRelease
```

## Troubleshooting

### "BoringSSL libraries not found"
```bash
cd external/boringssl
python3 build_all_abis.py
```

### "NDK not found"
```bash
export ANDROID_NDK_HOME=/path/to/ndk
export NDK_HOME=/path/to/ndk
```

### Crypto functions return -1
Check backend:
```kotlin
val backend = sxr_crypto_get_backend()
println("Backend: $backend")
```

## Security Considerations

### BoringSSL Advantages
1. Modern crypto only (no MD5, RC4, 3DES)
2. Reduced attack surface
3. Active development (used by Chrome, Android)
4. Constant-time operations

## References

- BoringSSL Documentation: https://commondatastorage.googleapis.com/chromium-boringssl-docs/headers.html
- BoringSSL Porting Guide: https://github.com/google/boringssl/blob/master/PORTING.md
- Android NDK Crypto Guide: https://developer.android.com/ndk/guides/security

---

**Migration Status**: ✅ Complete
**Version**: 1.10.129+boringssl
**Date**: 2025-11-05
