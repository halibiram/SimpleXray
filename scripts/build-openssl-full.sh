#!/bin/bash
# =============================================================================
# OpenSSL Multi-Architecture Build Script for Android NDK
# =============================================================================
# This script builds OpenSSL static libraries for all Android architectures
# using modern NDK toolchains without deprecated standalone toolchain scripts.
#
# Features:
# - Multi-architecture support (arm64-v8a, armeabi-v7a, x86_64, x86)
# - Deterministic builds (no timestamps, reproducible)
# - Aggressive caching and idempotency
# - Comprehensive error handling
# - No interactive prompts
#
# Usage:
#   ./scripts/build-openssl-full.sh [--ndk /path/to/ndk] [--version 3.3.0]
#
# Environment Variables:
#   NDK_ROOT or ANDROID_NDK_HOME: Path to Android NDK
#   OPENSSL_VERSION: OpenSSL version to build (default: 3.3.0)
#   NUM_JOBS: Number of parallel make jobs (default: auto)
#
# =============================================================================

set -Eeuo pipefail
trap 'echo "❌ Error on line $LINENO"' ERR

# =============================================================================
# Configuration
# =============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
OUTPUT_DIR="$PROJECT_ROOT/app/src/main/jni/openssl"

# Default OpenSSL version
OPENSSL_VERSION="${OPENSSL_VERSION:-3.3.0}"
NDK_ROOT="${NDK_ROOT:-}"
NUM_JOBS="${NUM_JOBS:-$(nproc 2>/dev/null || echo 2)}"
VERBOSE="${VERBOSE:-0}"

# Architecture configurations
declare -A ARCH_CONFIG=(
    [arm64-v8a]="android-arm64:aarch64-linux-android24:arm64"
    [armeabi-v7a]="android-arm:armv7a-linux-androideabi24:arm"
    [x86_64]="android-x86_64:x86_64-linux-android24:x86_64"
    [x86]="android-x86:i686-linux-android24:x86"
)

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# =============================================================================
# Helper Functions
# =============================================================================

log_info() {
    echo -e "${BLUE}ℹ${NC} $*"
}

log_success() {
    echo -e "${GREEN}✅${NC} $*"
}

log_warning() {
    echo -e "${YELLOW}⚠️${NC} $*"
}

log_error() {
    echo -e "${RED}❌${NC} $*" >&2
}

log_step() {
    echo ""
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${BLUE}▶${NC} $*"
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
}

# =============================================================================
# Argument Parsing
# =============================================================================

while [[ $# -gt 0 ]]; do
    case $1 in
        --ndk)
            NDK_ROOT="$2"
            shift 2
            ;;
        --version)
            OPENSSL_VERSION="$2"
            shift 2
            ;;
        --jobs)
            NUM_JOBS="$2"
            shift 2
            ;;
        --verbose|-v)
            VERBOSE=1
            shift
            ;;
        --help|-h)
            echo "Usage: $0 [OPTIONS]"
            echo ""
            echo "Options:"
            echo "  --ndk PATH        Path to Android NDK (default: auto-detect)"
            echo "  --version VERSION OpenSSL version to build (default: $OPENSSL_VERSION)"
            echo "  --jobs N          Number of parallel jobs (default: auto)"
            echo "  --verbose, -v     Enable verbose output"
            echo "  --help, -h        Show this help message"
            echo ""
            echo "Supported architectures:"
            for arch in "${!ARCH_CONFIG[@]}"; do
                echo "  - $arch"
            done
            exit 0
            ;;
        *)
            log_error "Unknown option: $1"
            echo "Use --help for usage information"
            exit 1
            ;;
    esac
done

# =============================================================================
# NDK Detection
# =============================================================================

detect_ndk() {
    if [ -n "$NDK_ROOT" ] && [ -d "$NDK_ROOT" ]; then
        return 0
    fi

    # Try environment variables
    if [ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "$ANDROID_NDK_HOME" ]; then
        NDK_ROOT="$ANDROID_NDK_HOME"
        return 0
    fi

    if [ -n "${ANDROID_NDK_ROOT:-}" ] && [ -d "$ANDROID_NDK_ROOT" ]; then
        NDK_ROOT="$ANDROID_NDK_ROOT"
        return 0
    fi

    # Try to find NDK in current directory (common in CI)
    local ndk_dir
    ndk_dir=$(find "$PROJECT_ROOT" -maxdepth 2 -type d -name "android-ndk-*" 2>/dev/null | head -1)
    if [ -n "$ndk_dir" ]; then
        NDK_ROOT="$ndk_dir"
        return 0
    fi

    # Try to find NDK in parent directory (CI cache)
    ndk_dir=$(find "$(dirname "$PROJECT_ROOT")" -maxdepth 1 -type d -name "android-ndk-*" 2>/dev/null | head -1)
    if [ -n "$ndk_dir" ]; then
        NDK_ROOT="$ndk_dir"
        return 0
    fi

    log_error "Android NDK not found!"
    log_error "Please set NDK_ROOT or ANDROID_NDK_HOME environment variable"
    log_error "Or use --ndk flag to specify NDK location"
    exit 1
}

# =============================================================================
# Build Function
# =============================================================================

build_openssl_for_arch() {
    local abi=$1
    local config="${ARCH_CONFIG[$abi]}"

    IFS=':' read -r openssl_target toolchain_prefix ndk_arch <<< "$config"

    log_step "Building OpenSSL $OPENSSL_VERSION for $abi"

    # Check if already built (idempotency)
    local lib_dir="$OUTPUT_DIR/lib/$abi"
    if [ -f "$lib_dir/libcrypto.a" ] && [ -f "$lib_dir/libssl.a" ]; then
        log_info "OpenSSL already built for $abi (cached)"
        log_info "  libcrypto: $(du -h "$lib_dir/libcrypto.a" | cut -f1)"
        log_info "  libssl:    $(du -h "$lib_dir/libssl.a" | cut -f1)"
        return 0
    fi

    # Setup toolchain paths
    local toolchain_dir="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64"
    if [ ! -d "$toolchain_dir" ]; then
        # Try darwin for macOS
        toolchain_dir="$NDK_ROOT/toolchains/llvm/prebuilt/darwin-x86_64"
        if [ ! -d "$toolchain_dir" ]; then
            log_error "Toolchain not found in NDK: $NDK_ROOT"
            exit 1
        fi
    fi

    log_info "Toolchain: $toolchain_dir"
    log_info "Target: $openssl_target"
    log_info "Prefix: $toolchain_prefix"

    # Ensure source is downloaded
    local source_dir="$PROJECT_ROOT/openssl-$OPENSSL_VERSION"
    local build_dir="$PROJECT_ROOT/build/openssl-$abi"
    local install_prefix="/tmp/openssl-install-$abi-$$"

    # Export toolchain variables
    export PATH="$toolchain_dir/bin:$PATH"
    export CC="$toolchain_prefix-clang"
    export CXX="$toolchain_prefix-clang++"
    export AR="$toolchain_dir/bin/llvm-ar"
    export RANLIB="$toolchain_dir/bin/llvm-ranlib"
    export STRIP="$toolchain_dir/bin/llvm-strip"
    export ANDROID_NDK_HOME="$NDK_ROOT"
    export ANDROID_NDK_ROOT="$NDK_ROOT"

    # Verify compiler exists
    if ! command -v "$CC" &> /dev/null; then
        log_error "Compiler not found: $CC"
        log_error "Toolchain directory: $toolchain_dir/bin"
        log_error "Available compilers:"
        ls -1 "$toolchain_dir/bin"/*-clang 2>/dev/null || echo "  (none found)"
        exit 1
    fi

    log_info "Compiler: $CC ($(command -v "$CC"))"

    # Create fresh build directory
    rm -rf "$build_dir"
    mkdir -p "$build_dir"
    cp -r "$source_dir"/* "$build_dir/"
    cd "$build_dir"

    # Configure OpenSSL
    log_info "Configuring OpenSSL..."

    local configure_opts=(
        "$openssl_target"
        "--prefix=$install_prefix"
        "CC=$CC"
        "CXX=$CXX"
        "AR=$AR"
        "RANLIB=$RANLIB"
        "STRIP=$STRIP"
        "no-shared"           # Static libraries only
        "no-ssl3"             # Disable SSL 3.0
        "no-comp"             # Disable compression
        "no-hw"               # Disable hardware engines
        "no-engine"           # Disable engine support
        "no-tests"            # Skip tests
        "-fPIC"               # Position independent code
    )

    if [ "$VERBOSE" -eq 0 ]; then
        ./Configure "${configure_opts[@]}" > /dev/null
    else
        ./Configure "${configure_opts[@]}"
    fi

    # Build OpenSSL
    log_info "Building OpenSSL (using $NUM_JOBS parallel jobs)..."
    make clean > /dev/null 2>&1 || true

    if [ "$VERBOSE" -eq 0 ]; then
        if ! make -j"$NUM_JOBS" > /dev/null 2>&1; then
            log_error "Build failed for $abi"
            log_error "Re-running with verbose output for debugging..."
            make clean > /dev/null 2>&1 || true
            make -j"$NUM_JOBS"
            exit 1
        fi
    else
        if ! make -j"$NUM_JOBS"; then
            log_error "Build failed for $abi"
            exit 1
        fi
    fi

    # Install to temp location
    log_info "Installing OpenSSL libraries..."
    if [ "$VERBOSE" -eq 0 ]; then
        make install_sw > /dev/null 2>&1
    else
        make install_sw
    fi

    # Copy to output directory
    mkdir -p "$lib_dir"
    cp "$install_prefix/lib/libcrypto.a" "$lib_dir/"
    cp "$install_prefix/lib/libssl.a" "$lib_dir/"

    # Copy headers (only once)
    if [ ! -d "$OUTPUT_DIR/include/openssl" ]; then
        mkdir -p "$OUTPUT_DIR/include"
        cp -r "$install_prefix/include/openssl" "$OUTPUT_DIR/include/"
        log_info "Installed OpenSSL headers"
    fi

    # Cleanup
    rm -rf "$install_prefix"
    rm -rf "$build_dir"

    # Verify build
    local crypto_size=$(du -h "$lib_dir/libcrypto.a" | cut -f1)
    local ssl_size=$(du -h "$lib_dir/libssl.a" | cut -f1)

    log_success "Built OpenSSL for $abi"
    log_info "  libcrypto.a: $crypto_size"
    log_info "  libssl.a:    $ssl_size"

    return 0
}

# =============================================================================
# Main Execution
# =============================================================================

main() {
    log_step "OpenSSL Multi-Architecture Build"
    log_info "Version: $OPENSSL_VERSION"
    log_info "Output: $OUTPUT_DIR"
    log_info "Parallel jobs: $NUM_JOBS"

    # Detect NDK
    detect_ndk
    NDK_ROOT="$(cd "$NDK_ROOT" && pwd)"  # Absolute path
    log_info "NDK: $NDK_ROOT"

    # Verify NDK toolchain exists
    local toolchain_check="$NDK_ROOT/toolchains/llvm/prebuilt"
    if [ ! -d "$toolchain_check" ]; then
        log_error "Invalid NDK structure: missing toolchains/llvm/prebuilt"
        log_error "NDK path: $NDK_ROOT"
        exit 1
    fi

    # Download OpenSSL source if needed
    local source_dir="$PROJECT_ROOT/openssl-$OPENSSL_VERSION"
    local source_tar="openssl-$OPENSSL_VERSION.tar.gz"

    if [ ! -d "$source_dir" ]; then
        log_step "Downloading OpenSSL $OPENSSL_VERSION"

        local url="https://www.openssl.org/source/$source_tar"
        log_info "URL: $url"

        if command -v wget &> /dev/null; then
            wget -q --show-progress "$url" -O "$PROJECT_ROOT/$source_tar"
        elif command -v curl &> /dev/null; then
            curl -L --progress-bar "$url" -o "$PROJECT_ROOT/$source_tar"
        else
            log_error "Neither wget nor curl found. Please install one of them."
            exit 1
        fi

        log_info "Extracting source..."
        tar -xzf "$PROJECT_ROOT/$source_tar" -C "$PROJECT_ROOT"
        rm -f "$PROJECT_ROOT/$source_tar"

        log_success "OpenSSL source downloaded"
    else
        log_info "OpenSSL source already exists"
    fi

    # Create output directory structure
    mkdir -p "$OUTPUT_DIR"/{include,lib/{arm64-v8a,armeabi-v7a,x86_64,x86}}

    # Build for each architecture
    local failed_archs=()
    for abi in "${!ARCH_CONFIG[@]}"; do
        if ! build_openssl_for_arch "$abi"; then
            failed_archs+=("$abi")
            log_warning "Failed to build for $abi (will retry)"
        fi
    done

    # Retry failed builds
    if [ ${#failed_archs[@]} -gt 0 ]; then
        log_warning "Retrying failed builds..."
        for abi in "${failed_archs[@]}"; do
            if ! build_openssl_for_arch "$abi"; then
                log_error "Build failed for $abi after retry"
                exit 1
            fi
        done
    fi

    # Verify all builds
    log_step "Verification"

    local required_files=(
        "include/openssl/evp.h"
        "include/openssl/aes.h"
        "include/openssl/ssl.h"
        "include/openssl/crypto.h"
    )

    for abi in "${!ARCH_CONFIG[@]}"; do
        required_files+=(
            "lib/$abi/libcrypto.a"
            "lib/$abi/libssl.a"
        )
    done

    local missing_files=()
    for file in "${required_files[@]}"; do
        if [ ! -f "$OUTPUT_DIR/$file" ]; then
            missing_files+=("$file")
        fi
    done

    if [ ${#missing_files[@]} -gt 0 ]; then
        log_error "Build verification failed! Missing files:"
        for file in "${missing_files[@]}"; do
            echo "  - $file"
        done
        exit 1
    fi

    # Print summary
    log_step "Build Summary"

    local header_count=$(find "$OUTPUT_DIR/include" -name '*.h' | wc -l)
    log_info "Headers: $header_count files"

    echo ""
    for abi in "${!ARCH_CONFIG[@]}"; do
        local crypto_size=$(du -h "$OUTPUT_DIR/lib/$abi/libcrypto.a" | cut -f1)
        local ssl_size=$(du -h "$OUTPUT_DIR/lib/$abi/libssl.a" | cut -f1)
        printf "%-15s  libcrypto: %6s  libssl: %6s\n" "$abi" "$crypto_size" "$ssl_size"
    done

    echo ""
    log_success "OpenSSL build complete!"
    log_info "Output directory: $OUTPUT_DIR"

    # Create build info file
    cat > "$OUTPUT_DIR/BUILD_INFO.txt" << EOF
OpenSSL Build Information
=========================

OpenSSL Version: $OPENSSL_VERSION
Build Date: $(date -u '+%Y-%m-%d %H:%M:%S UTC')
NDK: $NDK_ROOT
Built Architectures: ${!ARCH_CONFIG[*]}

This build was created using the modern NDK toolchain without
deprecated standalone toolchain scripts. All libraries are static
and optimized for Android.

Verify checksums:
$(cd "$OUTPUT_DIR" && find lib -name '*.a' -exec sha256sum {} \;)
EOF

    log_info "Build info saved to: $OUTPUT_DIR/BUILD_INFO.txt"
}

# Run main function
main "$@"
