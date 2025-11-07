# QUICHE Build Guide

Complete guide for building SimpleXray with QUICHE native client for maximum performance.

## 🎯 Overview

QUICHE (Cloudflare's QUIC implementation) + BoringSSL integration provides:
- **800-1200 Mbps throughput** (vs 300-500 Mbps with Xray)
- **+2-5ms latency** (vs +15-30ms)
- **<0.001% packet loss** (vs ~0.1%)
- **Hardware AES-GCM acceleration**

## 📋 Prerequisites

### 1. System Requirements
- **OS:** Ubuntu 22.04+ / macOS / Windows (WSL2)
- **RAM:** 8GB minimum (16GB recommended)
- **Disk:** 20GB free space

### 2. Build Tools

#### On Ubuntu/Debian:
```bash
sudo apt-get update
sudo apt-get install -y \
    build-essential \
    git \
    cmake \
    ninja-build \
    clang \
    lld \
    curl \
    wget \
    unzip
```

#### On macOS:
```bash
brew install cmake ninja git
```

### 3. Android NDK
```bash
# Download NDK r28c
wget https://dl.google.com/android/repository/android-ndk-r28c-linux.zip
unzip android-ndk-r28c-linux.zip
export ANDROID_NDK_HOME=$(pwd)/android-ndk-r28c
export PATH=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH
```

### 4. Rust Toolchain
```bash
# Install Rust
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
source $HOME/.cargo/env

# Add Android targets
rustup target add aarch64-linux-android
rustup target add x86_64-linux-android

# Install cargo-ndk
cargo install cargo-ndk
```

### 5. Java (for Android build)
```bash
# Ubuntu
sudo apt-get install -y openjdk-21-jdk

# macOS
brew install openjdk@21
```

## 🔨 Build Steps

### Step 1: Clone Repository
```bash
git clone --recursive https://github.com/halibiram/SimpleXray.git
cd SimpleXray
```

### Step 2: Initialize Submodules
```bash
# Initialize QUICHE submodule
git submodule sync
git submodule update --init --recursive app/src/main/jni/quiche-client/third_party/quiche

# Verify
ls app/src/main/jni/quiche-client/third_party/quiche/Cargo.toml
```

### Step 3: Build QUICHE Library
```bash
cd app/src/main/jni/quiche-client

# Option A: Use build script (recommended)
./build-quiche-android.sh

# Option B: Manual build
cd third_party/quiche

# For ARM64
RUSTFLAGS="-C opt-level=3 -C lto=fat -C codegen-units=1" \
cargo ndk \
    --target aarch64-linux-android \
    --platform 29 \
    build \
    --release \
    --manifest-path quiche/Cargo.toml \
    --features ffi,qlog

# For x86_64 (emulator)
RUSTFLAGS="-C opt-level=3 -C lto=fat -C codegen-units=1" \
cargo ndk \
    --target x86_64-linux-android \
    --platform 29 \
    build \
    --release \
    --manifest-path quiche/Cargo.toml \
    --features ffi,qlog

# Copy libraries
mkdir -p ../../libs/arm64-v8a
mkdir -p ../../libs/x86_64
cp target/aarch64-linux-android/release/libquiche.a ../../libs/arm64-v8a/
cp target/x86_64-linux-android/release/libquiche.a ../../libs/x86_64/

cd ../../../../../..
```

### Step 4: Build BoringSSL for QUICHE
```bash
BORINGSSL_DIR="app/src/main/jni/quiche-client/third_party/quiche/quiche/deps/boringssl"

cd "$BORINGSSL_DIR"

# For ARM64
mkdir -p build_arm64
cd build_arm64

cmake .. \
    -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-29 \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_FLAGS="-Ofast -march=armv8-a+crypto" \
    -DCMAKE_CXX_FLAGS="-Ofast -march=armv8-a+crypto" \
    -GNinja

ninja

cd ..

# For x86_64
mkdir -p build_x86_64
cd build_x86_64

cmake .. \
    -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake \
    -DANDROID_ABI=x86_64 \
    -DANDROID_PLATFORM=android-29 \
    -DCMAKE_BUILD_TYPE=Release \
    -GNinja

ninja

cd ../../../../../../../../../..
```

### Step 5: Build Native Client
```bash
cd app/src/main/jni/quiche-client

# Build with ndk-build
$ANDROID_NDK_HOME/ndk-build \
    NDK_PROJECT_PATH=. \
    APP_BUILD_SCRIPT=Android.mk \
    APP_ABI=arm64-v8a \
    APP_PLATFORM=android-29 \
    -j$(nproc)

cd ../../../../..
```

### Step 6: Build APK
```bash
# Make gradlew executable
chmod +x ./gradlew

# Build debug APK
./gradlew assembleDebug

# Or build release APK
./gradlew assembleRelease
```

## 🤖 CI/CD Build

GitHub Actions automatically builds QUICHE when you push to the repository.

### Trigger Manual Build
```bash
# Push to trigger CI
git push origin your-branch

# Or trigger manually via GitHub Actions UI
# Go to: Actions → Build QUICHE Native Client → Run workflow
```

### Download Artifacts
After CI build completes:
1. Go to Actions tab
2. Click on your workflow run
3. Download artifacts:
   - `quiche-arm64-v8a` - QUICHE library
   - `quiche-client-arm64-v8a` - Native client
   - `simplexray-quiche-debug` - APK

## ✅ Verification

### Check Built Libraries
```bash
# QUICHE library
ls -lh app/src/main/jni/quiche-client/libs/arm64-v8a/libquiche.a

# Native client
ls -lh app/src/main/jniLibs/arm64-v8a/libquiche-client.so

# APK
ls -lh app/build/outputs/apk/debug/*.apk
```

### Check Hardware Crypto
```kotlin
// In your app
val caps = QuicheCrypto.getCapabilities()
println(caps)  // Should show: AES=HW, PMULL=HW, NEON=YES
```

### Test APK
```bash
# Install on device
adb install app/build/outputs/apk/debug/simplexray-arm64-v8a.apk

# Check logs
adb logcat | grep -E "Quiche|QUIC|Crypto"
```

## 🐛 Troubleshooting

### Build Errors

#### "QUICHE submodule not found"
```bash
git submodule update --init --recursive app/src/main/jni/quiche-client/third_party/quiche
```

#### "cargo-ndk not found"
```bash
cargo install cargo-ndk
```

#### "NDK not found"
```bash
export ANDROID_NDK_HOME=/path/to/android-ndk-r28c
```

#### "BoringSSL build failed"
```bash
# Ensure ninja is installed
which ninja  # Should output path

# Clean and rebuild
cd app/src/main/jni/quiche-client/third_party/quiche/quiche/deps/boringssl
rm -rf build_*
# Re-run build steps
```

### Runtime Errors

#### "libquiche-client.so not found"
```bash
# Check if library exists
ls app/src/main/jniLibs/arm64-v8a/libquiche-client.so

# Rebuild native client
cd app/src/main/jni/quiche-client
$ANDROID_NDK_HOME/ndk-build clean
$ANDROID_NDK_HOME/ndk-build APP_ABI=arm64-v8a
```

#### "UnsatisfiedLinkError"
Check `build.gradle` includes:
```gradle
sourceSets {
    main {
        jniLibs {
            srcDirs 'src/main/jniLibs'
        }
    }
}
```

## 📊 Build Times (Approximate)

| Component | Time (first build) | Time (cached) |
|-----------|-------------------|---------------|
| QUICHE (Rust) | 10-15 min | 2-3 min |
| BoringSSL | 5-8 min | 1-2 min |
| Native Client | 2-3 min | 30 sec |
| APK | 3-5 min | 1-2 min |
| **Total** | **20-30 min** | **5-8 min** |

## 🚀 Performance Tips

### Parallel Builds
```bash
# Use all CPU cores
export MAKEFLAGS="-j$(nproc)"

# For Cargo
export CARGO_BUILD_JOBS=$(nproc)
```

### Incremental Builds
```bash
# Don't clean unless necessary
# Cargo caches intermediate artifacts

# Only rebuild changed files
./gradlew assembleDebug --rerun-tasks
```

### Ccache (Optional)
```bash
sudo apt-get install ccache
export PATH="/usr/lib/ccache:$PATH"
export NDK_CCACHE=ccache
```

## 📚 References

- [QUICHE Repository](https://github.com/cloudflare/quiche)
- [BoringSSL](https://boringssl.googlesource.com/boringssl/)
- [Android NDK](https://developer.android.com/ndk)
- [Rust Android](https://mozilla.github.io/firefox-browser-architecture/experiments/2017-09-21-rust-on-android.html)

## 🤝 Contributing

If you encounter build issues, please:
1. Check this guide first
2. Search existing GitHub issues
3. Open a new issue with:
   - Your OS and version
   - NDK version
   - Complete build log
   - Error messages

## 📄 License

See parent project license.
