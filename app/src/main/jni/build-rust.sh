#!/bin/bash
# Build script for Rust native modules

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Android NDK configuration
ANDROID_NDK="${ANDROID_NDK:-$HOME/Android/Sdk/ndk/25.2.9519653}"
ANDROID_PLATFORM="${ANDROID_PLATFORM:-29}"

# Rust targets
TARGETS=(
    "aarch64-linux-android"
    "armv7-linux-androideabi"
    "x86_64-linux-android"
    "i686-linux-android"
)

# Modules to build
MODULES=(
    "xray-signal-handler"
    "pepper-shaper"
    "perf-net"
    "quiche-client"
)

echo "Building Rust modules for Android..."
echo "NDK: $ANDROID_NDK"
echo "Platform: $ANDROID_PLATFORM"

# Check if cargo-ndk is installed
if ! command -v cargo-ndk &> /dev/null; then
    echo "Installing cargo-ndk..."
    cargo install cargo-ndk
fi

# Build each module for each target
for module in "${MODULES[@]}"; do
    echo ""
    echo "Building $module..."
    cd "$module"
    
    for target in "${TARGETS[@]}"; do
        echo "  Building for $target..."
        cargo ndk \
            --target "$target" \
            --platform "$ANDROID_PLATFORM" \
            build \
            --release
        
        # Copy library to libs directory
        mkdir -p "libs/${target}"
        # Try different library name patterns
        cp "target/${target}/release/lib${module//-/_}.so" "libs/${target}/" 2>/dev/null || \
        cp "target/${target}/release/lib${module}.so" "libs/${target}/" 2>/dev/null || true
    done
    
    cd ..
done

echo ""
echo "Build complete!"

