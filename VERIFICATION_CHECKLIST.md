# ✅ BoringSSL Integration - Verification Checklist

Use this checklist to verify everything is correctly configured.

## 📋 Pre-Build Verification

### 1. Submodule Initialization
- [ ] Run: `git submodule update --init --recursive`
- [ ] Verify: `app/src/main/jni/perf-net/third_party/boringssl/CMakeLists.txt` exists
- [ ] Check: BoringSSL directory is not empty

### 2. File Structure
- [ ] `app/src/main/jni/perf-net/CMakeLists.txt` exists
- [ ] `app/src/main/jni/perf-net/src/perf_crypto_neon.cpp` exists (BoringSSL version)
- [ ] `app/src/main/jni/perf-net/src/perf_tls_handshake.cpp` exists
- [ ] `app/src/main/jni/perf-net/src/perf_quic_handshake.cpp` exists
- [ ] `app/src/main/jni/perf-net/src/perf_tls_evasion.cpp` exists
- [ ] `app/src/main/jni/perf-net/src/perf_cert_verifier.cpp` exists
- [ ] `app/src/main/jni/perf-net/src/perf_tls_keylog.cpp` exists

### 3. Build Configuration
- [ ] `app/build.gradle` has CMake configuration
- [ ] `app/build.gradle` has `abiFilters "arm64-v8a"`
- [ ] `app/src/main/jni/perf-net/Android.mk` has `DISABLE_OPENSSL=1`
- [ ] `app/src/main/jni/perf-net/Android.mk` has `USE_BORINGSSL=1`

### 4. CI/CD
- [ ] `.github/workflows/build.yml` has BoringSSL build step
- [ ] `.github/workflows/auto-release.yml` has BoringSSL build step
- [ ] Both workflows install `ninja-build`, `clang`, `lld`, `cmake`

---

## 🔨 Build Verification

### 5. Clean Build
- [ ] Run: `./gradlew clean` (or `gradlew.bat clean` on Windows)
- [ ] No errors during clean

### 6. Debug Build
- [ ] Run: `./gradlew assembleDebug`
- [ ] CMake finds BoringSSL submodule
- [ ] BoringSSL compiles successfully
- [ ] perf-net library links against BoringSSL
- [ ] APK builds successfully
- [ ] No OpenSSL-related errors

### 7. Build Output Check
- [ ] Look for: "BoringSSL found, linking against submodule"
- [ ] Look for: "Building perf-net with BoringSSL"
- [ ] No errors about missing OpenSSL
- [ ] No errors about missing BoringSSL symbols

---

## 📱 Runtime Verification

### 8. APK Installation
- [ ] APK installs on device/emulator
- [ ] No installation errors

### 9. Logcat Check
- [ ] Run: `adb logcat | grep -E "PerfCrypto|BoringSSL|PerfTLS"`
- [ ] No "OpenSSL not found" errors
- [ ] No "SECURITY ERROR" messages
- [ ] Crypto extensions detected (if hardware supported)
- [ ] BoringSSL initialization successful

### 10. Function Testing
- [ ] `hasCryptoExtensions()` returns correct value
- [ ] `hasNEON()` returns true (on ARM devices)
- [ ] `aes128Encrypt()` works (returns positive result)
- [ ] `nativeChaCha20NEON()` works
- [ ] `nativeRandomBytes()` works

---

## 🧪 Feature Testing

### 11. TLS Handshake
- [ ] `nativeCreateChromeMobileSSLContext()` returns non-zero
- [ ] `nativeCreateChromeMobileSSL()` returns non-zero
- [ ] `nativeSetSNI()` succeeds
- [ ] No TLS-related crashes

### 12. QUIC/HTTP3
- [ ] `nativeCreateQUICContext()` returns non-zero
- [ ] No QUIC-related errors

### 13. Certificate Verifier
- [ ] `nativeCreateCertVerifier()` returns non-zero
- [ ] `nativeSetCertVerifyCallback()` succeeds
- [ ] No certificate verification errors

### 14. Operator Evasion
- [ ] `nativeGeneratePadding()` generates random data
- [ ] `nativeGetHandshakePacingDelay()` returns 0-50ms
- [ ] `nativeApplyRecordJitter()` modifies size
- [ ] `nativeGenerateECHGREASE()` generates value

### 15. TLS Keylog
- [ ] `nativeEnableTLSKeylog()` succeeds
- [ ] Keylog file created (if enabled)
- [ ] `nativeRecordHandshakeStart()` works
- [ ] `nativeRecordHandshakeEnd()` returns timing

---

## 📊 Performance Verification

### 16. Hardware Acceleration
- [ ] Crypto extensions detected on supported devices
- [ ] AES-GCM uses hardware when available
- [ ] ChaCha20-Poly1305 fallback works
- [ ] Performance improvement noticeable

### 17. TLS Performance
- [ ] TLS handshake completes successfully
- [ ] Handshake latency acceptable (< 200ms)
- [ ] No connection timeouts

---

## 🔍 Code Quality Checks

### 18. No OpenSSL References
- [ ] No `#include <openssl/...>` (except BoringSSL's openssl/ compatibility headers)
- [ ] No `USE_OPENSSL` defines (except disabled)
- [ ] No OpenSSL library links
- [ ] No OpenSSL directory in project

### 19. BoringSSL References Present
- [ ] `USE_BORINGSSL` defined
- [ ] BoringSSL includes present
- [ ] BoringSSL libraries linked
- [ ] CMake finds BoringSSL

---

## 🚀 CI/CD Verification

### 20. GitHub Actions
- [ ] Push to GitHub
- [ ] Actions workflow runs
- [ ] BoringSSL build step succeeds
- [ ] No OpenSSL build step
- [ ] APK artifact created
- [ ] No workflow errors

### 21. Auto-Release
- [ ] Auto-release workflow triggers
- [ ] BoringSSL builds in release
- [ ] Release created with APK
- [ ] Release notes mention BoringSSL

---

## ✅ Final Checklist

Before marking as complete:

- [ ] All pre-build checks pass
- [ ] Build succeeds without errors
- [ ] Runtime verification passes
- [ ] All features tested
- [ ] Performance acceptable
- [ ] No OpenSSL references remain
- [ ] CI/CD works correctly
- [ ] Documentation updated

---

## 🎯 Quick Verification Script

Run the automated verification script:

**Linux/macOS:**
```bash
./apply_boringssl_steps.sh
```

**Windows:**
```powershell
.\apply_boringssl_steps.ps1
```

---

## 📝 Notes

- Mark items as complete when verified
- If any item fails, check the troubleshooting section in `NEXT_STEPS_BORINGSSL.md`
- Keep this checklist updated as you verify each step

---

**Status:** ⏳ Ready for Verification

