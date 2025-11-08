#!/bin/bash
#
# Build QUICHE for Android with MAXIMUM PERFORMANCE optimizations
# This script cross-compiles QUICHE Rust library for Android targets
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
QUICHE_DIR="$SCRIPT_DIR/third_party/quiche"
OUTPUT_DIR="$SCRIPT_DIR/libs"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}🚀 Building QUICHE for Android - MAXIMUM PERFORMANCE MODE${NC}"

# Check if Rust is installed
if ! command -v cargo &> /dev/null; then
    echo -e "${RED}❌ Rust/Cargo not found. Install from: https://rustup.rs/${NC}"
    exit 1
fi

# Check if cargo-ndk is installed
if ! command -v cargo-ndk &> /dev/null; then
    echo -e "${YELLOW}⚠️  cargo-ndk not found. Installing...${NC}"
    cargo install cargo-ndk
fi

# Android targets
TARGETS=(
    "aarch64-linux-android"   # arm64-v8a
    "x86_64-linux-android"    # x86_64
)

# Add Rust targets if not already added
echo -e "${GREEN}📦 Adding Rust targets...${NC}"
for target in "${TARGETS[@]}"; do
    rustup target add "$target" || true
done

# Create output directory
mkdir -p "$OUTPUT_DIR"

cd "$QUICHE_DIR"

# Build for each target with MAXIMUM PERFORMANCE
for target in "${TARGETS[@]}"; do
    echo -e "${GREEN}🔨 Building QUICHE for $target...${NC}"

    # Map Rust target to Android ABI
    case "$target" in
        "aarch64-linux-android")
            ABI="arm64-v8a"
            ;;
        "x86_64-linux-android")
            ABI="x86_64"
            ;;
        *)
            ABI="unknown"
            ;;
    esac

    # Aggressive optimization flags via RUSTFLAGS
    # LTO removed - incompatible with embed-bitcode=no in cross-compilation
    # Removed target-cpu=native as it's incompatible with cross-compilation
    export RUSTFLAGS="
        -C opt-level=3
        -C codegen-units=1
        -C panic=abort
        -C overflow-checks=off
        -C debug-assertions=off
    "

    # Build with cargo-ndk
    cargo ndk \
        --target "$target" \
        --platform 29 \
        build \
        --release \
        --manifest-path quiche/Cargo.toml \
        --features ffi,qlog

    # Copy library to output directory
    mkdir -p "$OUTPUT_DIR/$ABI"
    cp "target/$target/release/libquiche.a" "$OUTPUT_DIR/$ABI/"
    cp "target/$target/release/libquiche.so" "$OUTPUT_DIR/$ABI/" 2>/dev/null || true

    echo -e "${GREEN}✅ Built QUICHE for $ABI${NC}"
done

echo -e "${GREEN}🎉 QUICHE build completed successfully!${NC}"
echo -e "${YELLOW}📁 Libraries are in: $OUTPUT_DIR${NC}"

# List built libraries
echo -e "${GREEN}📦 Built libraries:${NC}"
find "$OUTPUT_DIR" -name "libquiche.*" -type f
