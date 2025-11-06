#!/bin/bash
# Build Xray-core with BoringSSL integration
# Usage: ./build-xray.sh <abi> <boringssl_crypto> <boringssl_ssl> <boringssl_include> [ndk_path]
# Example: ./build-xray.sh arm64-v8a /path/to/libcrypto.a /path/to/libssl.a /path/to/include

set -e

ABI=${1:-arm64-v8a}
BORINGSSL_CRYPTO=${2}
BORINGSSL_SSL=${3}
BORINGSSL_INCLUDE=${4}
NDK_HOME=${5:-${ANDROID_NDK_HOME}}

if [ -z "$BORINGSSL_CRYPTO" ] || [ -z "$BORINGSSL_SSL" ] || [ -z "$BORINGSSL_INCLUDE" ]; then
    echo "❌ Error: BoringSSL paths not provided"
    echo "Usage: $0 <abi> <boringssl_crypto> <boringssl_ssl> <boringssl_include> [ndk_path]"
    exit 1
fi

if [ -z "$NDK_HOME" ]; then
    echo "❌ Error: NDK_HOME not set and not provided as argument"
    exit 1
fi

# Verify BoringSSL libraries exist
if [ ! -f "$BORINGSSL_CRYPTO" ]; then
    echo "❌ Error: BoringSSL crypto library not found: $BORINGSSL_CRYPTO"
    exit 1
fi

if [ ! -f "$BORINGSSL_SSL" ]; then
    echo "❌ Error: BoringSSL ssl library not found: $BORINGSSL_SSL"
    exit 1
fi

# ABI configuration
case "$ABI" in
    arm64-v8a)
        GOARCH="arm64"
        TOOLCHAIN="aarch64-linux-android24"
        ;;
    armeabi-v7a)
        GOARCH="arm"
        TOOLCHAIN="armv7a-linux-androideabi24"
        ;;
    x86_64)
        GOARCH="amd64"
        TOOLCHAIN="x86_64-linux-android24"
        ;;
    *)
        echo "❌ Error: Unsupported ABI: $ABI"
        exit 1
        ;;
esac

echo "🔧 Building Xray-core for $ABI with BoringSSL"
echo "NDK: $NDK_HOME"
echo "Go Arch: $GOARCH"
echo "Toolchain: $TOOLCHAIN"

# Check if Xray-core directory exists
XRAY_DIR="Xray-core"
if [ ! -d "$XRAY_DIR" ]; then
    echo "📥 Cloning Xray-core..."
    git clone --depth=1 https://github.com/XTLS/Xray-core.git
fi

cd "$XRAY_DIR"

# Apply patches if available
if [ -d "../xray-patches" ]; then
    echo "📝 Applying patches..."
    for patch in ../xray-patches/*.patch; do
        if [ -f "$patch" ]; then
            echo "  Applying: $(basename $patch)"
            git apply "$patch" || echo "  ⚠️  Patch $(basename $patch) failed or already applied"
        fi
    done
fi

# Get commit hash
COMMIT=$(git rev-parse HEAD | cut -c 1-7)
echo "📌 Xray-core commit: $COMMIT"

# Setup Go cross-compile environment
export GOOS=android
export CGO_ENABLED=1
export GOARCH=$GOARCH

# Setup NDK toolchain
TOOLCHAIN_DIR="$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
export CC=$TOOLCHAIN_DIR/bin/$TOOLCHAIN-clang
export CXX=$TOOLCHAIN_DIR/bin/$TOOLCHAIN-clang++
export AR=$TOOLCHAIN_DIR/bin/llvm-ar
export RANLIB=$TOOLCHAIN_DIR/bin/llvm-ranlib
export STRIP=$TOOLCHAIN_DIR/bin/llvm-strip

# Set CGO flags for BoringSSL
BORINGSSL_LIB_DIR=$(dirname "$BORINGSSL_CRYPTO")
export CGO_CFLAGS="-I$BORINGSSL_INCLUDE"
export CGO_LDFLAGS="-L$BORINGSSL_LIB_DIR -lcrypto -lssl -static"

echo "🔗 Linking BoringSSL:"
echo "   CGO_CFLAGS: $CGO_CFLAGS"
echo "   CGO_LDFLAGS: $CGO_LDFLAGS"

# Build with CGO
echo "🔨 Building Xray-core..."
go build \
    -buildmode=c-shared \
    -o libxray.so \
    -trimpath \
    -buildvcs=false \
    -ldflags="-X github.com/xtls/xray-core/core.build=${COMMIT} -s -w -buildid=" \
    -v \
    ./main

# Strip binary
echo "✂️  Stripping binary..."
$STRIP --strip-all libxray.so

# Verify BoringSSL linkage
echo "🔍 Verifying BoringSSL integration..."
if strings libxray.so | grep -qi "BoringSSL\|boringssl"; then
    echo "✅ BoringSSL symbols found in binary"
else
    echo "⚠️  Warning: BoringSSL symbols not found in binary"
fi

# Check for crypto functions
if strings libxray.so | grep -qi "aes\|gcm"; then
    echo "✅ AES-GCM symbols found"
fi

# Output binary info
echo "📦 Binary info:"
ls -lh libxray.so
file libxray.so

echo "✅ Xray-core build complete for $ABI"



