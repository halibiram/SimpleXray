#!/bin/bash
# Setup BoringSSL source for local development
# This script initializes the BoringSSL submodule to provide headers needed for CGO compilation

set -e

BORINGSSL_DIR="app/src/main/jni/perf-net/third_party/boringssl"

echo "🔧 Setting up BoringSSL for local development..."

# Check if BoringSSL is already initialized
if [ -d "$BORINGSSL_DIR" ] && [ -f "$BORINGSSL_DIR/CMakeLists.txt" ]; then
    echo "✅ BoringSSL source already exists at $BORINGSSL_DIR"

    # Verify headers are present
    if [ -f "$BORINGSSL_DIR/include/openssl/ssl.h" ]; then
        echo "✅ BoringSSL headers verified"

        # Get commit hash for reference
        cd "$BORINGSSL_DIR"
        COMMIT=$(git rev-parse HEAD | cut -c 1-12)
        echo "📌 BoringSSL commit: $COMMIT"
        cd - > /dev/null

        echo ""
        echo "BoringSSL is ready for use!"
        echo "You can now build Xray-core with BoringSSL support."
        exit 0
    else
        echo "⚠️  BoringSSL source exists but headers are missing"
        echo "   Reinitializing submodule..."
    fi
fi

# Initialize BoringSSL as a git submodule
echo "📦 Initializing BoringSSL submodule..."
echo "   This may take a few minutes..."

# First, make sure submodule is registered
if ! git config -f .gitmodules --get submodule.app/src/main/jni/perf-net/third_party/boringssl.url > /dev/null 2>&1; then
    echo "⚠️  BoringSSL submodule not found in .gitmodules"
    echo "   Adding BoringSSL as a submodule..."

    # Remove directory if it exists but is not a proper submodule
    if [ -d "$BORINGSSL_DIR" ]; then
        rm -rf "$BORINGSSL_DIR"
    fi

    # Add as submodule
    git submodule add --force https://boringssl.googlesource.com/boringssl "$BORINGSSL_DIR" || {
        echo "⚠️  Failed to add from googlesource, trying GitHub mirror..."
        git submodule add --force https://github.com/google/boringssl.git "$BORINGSSL_DIR" || {
            echo "❌ Failed to add BoringSSL submodule"
            exit 1
        }
    }
fi

# Initialize and update the submodule
if git submodule update --init --depth 1 "$BORINGSSL_DIR" 2>&1; then
    echo "✅ BoringSSL submodule initialized successfully"
else
    echo "⚠️  Submodule init failed, trying alternative method..."

    # Try without depth limitation
    if git submodule update --init "$BORINGSSL_DIR" 2>&1; then
        echo "✅ BoringSSL submodule initialized successfully"
    else
        echo "❌ Failed to initialize BoringSSL submodule"
        echo "   Please check your internet connection and try again"
        exit 1
    fi
fi

# Verify CMakeLists.txt exists
if [ ! -f "$BORINGSSL_DIR/CMakeLists.txt" ]; then
    echo "❌ BoringSSL CMakeLists.txt not found after initialization!"
    echo "   This indicates an incomplete or corrupted clone"
    exit 1
fi

# Verify headers exist
if [ ! -f "$BORINGSSL_DIR/include/openssl/ssl.h" ]; then
    echo "❌ BoringSSL headers not found after initialization!"
    echo "   This indicates an incomplete or corrupted clone"
    exit 1
fi

# Get commit hash for reference
cd "$BORINGSSL_DIR"
COMMIT=$(git rev-parse HEAD | cut -c 1-12)
echo "✅ BoringSSL pinned to commit: $COMMIT"
cd - > /dev/null

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "✅ BoringSSL Setup Complete!"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "📁 Location: $BORINGSSL_DIR"
echo "📌 Commit: $COMMIT"
echo ""
echo "📝 Next Steps:"
echo "   1. To build BoringSSL libraries:"
echo "      ./scripts/build-boringssl.sh arm64-v8a"
echo ""
echo "   2. To build Xray-core with BoringSSL:"
echo "      ./scripts/build-xray.sh arm64-v8a <crypto_lib> <ssl_lib> <include_dir>"
echo ""
echo "   3. Or simply build the APK:"
echo "      ./gradlew assembleDebug"
echo ""
echo "ℹ️  Note: BoringSSL source is now available for local builds."
echo "   The headers will be used during CGO compilation."
