#!/bin/bash
# Build BoringSSL for Android
# Usage: ./build-boringssl.sh <abi> [ndk_path]
# Example: ./build-boringssl.sh arm64-v8a /path/to/android-ndk

set -e

ABI=${1:-arm64-v8a}
NDK_HOME=${2:-${ANDROID_NDK_HOME}}

if [ -z "$NDK_HOME" ]; then
    echo "❌ Error: NDK_HOME not set and not provided as argument"
    echo "Usage: $0 <abi> [ndk_path]"
    exit 1
fi

# ABI configuration
case "$ABI" in
    arm64-v8a)
        TOOLCHAIN="aarch64-linux-android24"
        CMAKE_ARCH="arm64-v8a"
        ;;
    armeabi-v7a)
        TOOLCHAIN="armv7a-linux-androideabi24"
        CMAKE_ARCH="armeabi-v7a"
        ;;
    x86_64)
        TOOLCHAIN="x86_64-linux-android24"
        CMAKE_ARCH="x86_64"
        ;;
    *)
        echo "❌ Error: Unsupported ABI: $ABI"
        echo "Supported ABIs: arm64-v8a, armeabi-v7a, x86_64"
        exit 1
        ;;
esac

echo "🔧 Building BoringSSL for $ABI"
echo "NDK: $NDK_HOME"
echo "Toolchain: $TOOLCHAIN"

# Detect CPU features (for optimization)
if [ "$ABI" = "arm64-v8a" ]; then
    echo "✅ Detected ARM64 with crypto extensions support"
    CMAKE_FLAGS="-march=armv8-a+simd+crypto"
elif [ "$ABI" = "armeabi-v7a" ]; then
    echo "✅ Detected ARMv7 with NEON support"
    CMAKE_FLAGS="-march=armv7-a -mfpu=neon"
fi

# Find BoringSSL directory
BORINGSSL_DIR="app/src/main/jni/perf-net/third_party/boringssl"
if [ ! -d "$BORINGSSL_DIR" ]; then
    echo "❌ BoringSSL directory not found: $BORINGSSL_DIR"
    exit 1
fi

cd "$BORINGSSL_DIR"

# Setup toolchain
TOOLCHAIN_DIR="$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
export PATH=$TOOLCHAIN_DIR/bin:$PATH
export CC=$TOOLCHAIN_DIR/bin/$TOOLCHAIN-clang
export CXX=$TOOLCHAIN_DIR/bin/$TOOLCHAIN-clang++
export AR=$TOOLCHAIN_DIR/bin/llvm-ar
export RANLIB=$TOOLCHAIN_DIR/bin/llvm-ranlib
export STRIP=$TOOLCHAIN_DIR/bin/llvm-strip
export ANDROID_NDK_HOME=$NDK_HOME
export ANDROID_NDK_ROOT=$NDK_HOME

# Create build directory
BUILD_DIR="build_$ABI"
mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"

# Configure BoringSSL with CMake
echo "📦 Configuring BoringSSL with CMake..."
cmake .. \
    -DCMAKE_SYSTEM_NAME=Android \
    -DCMAKE_SYSTEM_VERSION=24 \
    -DCMAKE_ANDROID_ARCH_ABI=$CMAKE_ARCH \
    -DCMAKE_ANDROID_NDK=$NDK_HOME \
    -DCMAKE_TOOLCHAIN_FILE=$NDK_HOME/build/cmake/android.toolchain.cmake \
    -DCMAKE_BUILD_TYPE=Release \
    -DOPENSSL_SMALL=1 \
    -DOPENSSL_NO_DEPRECATED=1 \
    -DOPENSSL_NO_ASM=0 \
    -DBUILD_SHARED_LIBS=OFF \
    -GNinja

# Build
echo "🔨 Building BoringSSL..."
ninja -j$(nproc)

# Verify build
if [ -f "crypto/libcrypto.a" ] && [ -f "ssl/libssl.a" ]; then
    echo "✅ BoringSSL build complete for $ABI"
    echo "📦 Libraries:"
    ls -lh crypto/libcrypto.a ssl/libssl.a
else
    echo "❌ Build failed - libraries not found"
    exit 1
fi

# Export library paths
export BORINGSSL_CRYPTO=$(realpath crypto/libcrypto.a)
export BORINGSSL_SSL=$(realpath ssl/libssl.a)
export BORINGSSL_INCLUDE=$(realpath ../include)

echo "✅ BoringSSL libraries exported:"
echo "   CRYPTO: $BORINGSSL_CRYPTO"
echo "   SSL: $BORINGSSL_SSL"
echo "   INCLUDE: $BORINGSSL_INCLUDE"

