#!/bin/bash
# Build Hysteria2 binaries for Android
# Usage: ./build-hysteria2.sh [version]

set -e

HYSTERIA2_VERSION="${1:-latest}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
JNI_LIBS_DIR="$PROJECT_ROOT/app/src/main/jniLibs"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}Building Hysteria2 binaries for Android${NC}"
echo "Version: $HYSTERIA2_VERSION"

# Check if NDK is available
if [ -z "$ANDROID_NDK_HOME" ] && [ -z "$NDK_HOME" ]; then
    echo -e "${YELLOW}Warning: ANDROID_NDK_HOME or NDK_HOME not set${NC}"
    echo "Downloading NDK r28c..."
    
    NDK_DIR="$PROJECT_ROOT/android-ndk-r28c"
    if [ ! -d "$NDK_DIR" ]; then
        wget -q https://dl.google.com/android/repository/android-ndk-r28c-linux.zip -O /tmp/android-ndk.zip
        unzip -q /tmp/android-ndk.zip -d "$PROJECT_ROOT"
        rm /tmp/android-ndk.zip
    fi
    NDK_HOME="$NDK_DIR"
fi

NDK_HOME="${NDK_HOME:-$ANDROID_NDK_HOME}"
if [ ! -d "$NDK_HOME" ]; then
    echo -e "${RED}Error: NDK not found at $NDK_HOME${NC}"
    exit 1
fi

echo "Using NDK: $NDK_HOME"

# Check Go
if ! command -v go &> /dev/null; then
    echo -e "${RED}Error: Go is not installed${NC}"
    exit 1
fi

echo "Go version: $(go version)"

# ABIs to build
ABIS=("arm64-v8a" "armeabi-v7a" "x86_64")

# Create jniLibs directory structure
mkdir -p "$JNI_LIBS_DIR"

# Build for each ABI
for ABI in "${ABIS[@]}"; do
    case $ABI in
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
            echo -e "${RED}Unknown ABI: $ABI${NC}"
            continue
            ;;
    esac
    
    echo -e "\n${GREEN}Building for $ABI (GOARCH=$GOARCH)${NC}"
    
    # Create temp directory
    TEMP_DIR=$(mktemp -d)
    cd "$TEMP_DIR"
    
    # Clone Hysteria2
    echo "Cloning Hysteria2 repository..."
    git clone --depth=1 https://github.com/apernet/hysteria.git
    cd hysteria
    
    # Checkout version if specified
    if [ "$HYSTERIA2_VERSION" != "latest" ]; then
        echo "Checking out version: $HYSTERIA2_VERSION"
        git fetch --tags
        git checkout "$HYSTERIA2_VERSION" 2>/dev/null || {
            echo -e "${YELLOW}Warning: Version $HYSTERIA2_VERSION not found, using latest${NC}"
        }
    fi
    
    # Setup Android build environment
    export GOOS=android
    export CGO_ENABLED=1
    export GOARCH=$GOARCH
    export CC="$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/$TOOLCHAIN-clang"
    export CXX="$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/$TOOLCHAIN-clang++"
    
    # Android API level
    ANDROID_API=24
    
    # CGO flags
    export CGO_CFLAGS="--sysroot=$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
    export CGO_LDFLAGS="-L$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/$GOARCH/$ANDROID_API -llog -landroid"
    
    echo "  CC: $CC"
    echo "  GOARCH: $GOARCH"
    echo "  Building..."
    
    # Build Hysteria2
    go build -o hysteria2 \
        -trimpath \
        -buildvcs=false \
        -ldflags="-s -w" \
        ./cmd/hysteria2
    
    if [ ! -f "hysteria2" ]; then
        echo -e "${RED}Error: Build failed for $ABI${NC}"
        cd "$PROJECT_ROOT"
        rm -rf "$TEMP_DIR"
        continue
    fi
    
    # Copy to jniLibs
    ABI_DIR="$JNI_LIBS_DIR/$ABI"
    mkdir -p "$ABI_DIR"
    cp hysteria2 "$ABI_DIR/libhysteria2.so"
    
    # Verify
    if [ -f "$ABI_DIR/libhysteria2.so" ]; then
        SIZE=$(stat -c%s "$ABI_DIR/libhysteria2.so" 2>/dev/null || stat -f%z "$ABI_DIR/libhysteria2.so" 2>/dev/null)
        SIZE_MB=$((SIZE / 1024 / 1024))
        echo -e "${GREEN}✅ Built $ABI: $ABI_DIR/libhysteria2.so (${SIZE_MB}MB)${NC}"
    else
        echo -e "${RED}❌ Failed to copy binary for $ABI${NC}"
    fi
    
    # Cleanup
    cd "$PROJECT_ROOT"
    rm -rf "$TEMP_DIR"
done

echo -e "\n${GREEN}✅ Hysteria2 build complete!${NC}"
echo "Binaries location: $JNI_LIBS_DIR"
ls -lh "$JNI_LIBS_DIR"/*/libhysteria2.so 2>/dev/null || echo "No binaries found"



