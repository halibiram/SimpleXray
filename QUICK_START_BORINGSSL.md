# ⚡ Quick Start - BoringSSL Integration

## 🚀 Fast Track (5 Minutes)

### 1. Initialize Submodule
```bash
cd SimpleXray
git submodule update --init --recursive
```

### 2. Clean Build
```bash
./gradlew clean
```

### 3. Build Debug APK
```bash
./gradlew assembleDebug
```

### 4. Check Logs
```bash
# Look for BoringSSL messages
./gradlew assembleDebug 2>&1 | grep -i boringssl
```

### 5. Install & Test
```bash
adb install app/build/outputs/apk/debug/simplexray-arm64-v8a-debug.apk
adb logcat | grep -E "PerfCrypto|BoringSSL"
```

---

## ✅ Verification Checklist

### Build Verification
- [ ] Submodule initialized
- [ ] Clean build succeeds
- [ ] No OpenSSL errors
- [ ] BoringSSL found in CMake output

### Runtime Verification
- [ ] APK installs successfully
- [ ] No "OpenSSL not found" errors
- [ ] Crypto functions work
- [ ] Hardware acceleration detected (if available)

---

## 🔧 Common Issues & Quick Fixes

### "BoringSSL submodule not found"
```bash
git submodule update --init --recursive
cd app/src/main/jni/perf-net/third_party/boringssl
ls CMakeLists.txt  # Should exist
```

### "CMake version too old"
```bash
# Update CMake to 3.22+
# Or use Android Studio's bundled CMake
```

### "Link errors"
```bash
./gradlew clean
rm -rf app/build
./gradlew assembleDebug
```

---

## 📱 Quick Test Commands

### Test Crypto
```kotlin
val perf = PerformanceManager()
Log.d("Test", "Crypto extensions: ${perf.hasCryptoExtensions()}")
Log.d("Test", "NEON available: ${perf.hasNEON()}")
```

### Test TLS
```kotlin
val ctx = perf.nativeCreateChromeMobileSSLContext()
Log.d("Test", "TLS context: $ctx")  // Should be non-zero
```

---

## 📊 Expected Results

**Build Output:**
```
✅ BoringSSL found, linking against submodule
✅ Building perf-net with BoringSSL
✅ Linking libcrypto.a and libssl.a
```

**Runtime Logcat:**
```
PerfCryptoBoringSSL: Crypto extensions: yes
PerfCryptoBoringSSL: AES hardware: yes
PerfTLSHandshake: Created Chrome mobile SSL context
```

---

## 🎯 Next Steps After Quick Start

See `NEXT_STEPS_BORINGSSL.md` for detailed testing and verification.

---

**Time to complete: ~5 minutes** ⏱️

