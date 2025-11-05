#!/bin/bash
# Initialize BoringSSL submodule for SimpleXray

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BORINGSSL_DIR="${SCRIPT_DIR}/third_party/boringssl"

echo "🔐 Initializing BoringSSL submodule..."

# Check if we're in a git repository
if [ ! -d ".git" ] && [ ! -f ".git" ]; then
    echo "⚠️  Warning: Not in a git repository. Submodule commands may fail."
    echo "   Creating BoringSSL directory manually..."
    mkdir -p "${BORINGSSL_DIR}"
    
    # Clone BoringSSL if directory is empty
    if [ ! -d "${BORINGSSL_DIR}/.git" ]; then
        echo "📥 Cloning BoringSSL..."
        git clone https://boringssl.googlesource.com/boringssl "${BORINGSSL_DIR}"
    else
        echo "✅ BoringSSL already cloned"
    fi
else
    # Initialize submodule if .gitmodules exists
    if [ -f "${SCRIPT_DIR}/../../../../.gitmodules" ]; then
        echo "📥 Initializing git submodule..."
        cd "${SCRIPT_DIR}/../../../../"
        git submodule update --init --recursive app/src/main/jni/perf-net/third_party/boringssl || {
            echo "⚠️  Submodule init failed, cloning manually..."
            mkdir -p "${BORINGSSL_DIR}"
            if [ ! -d "${BORINGSSL_DIR}/.git" ]; then
                git clone https://boringssl.googlesource.com/boringssl "${BORINGSSL_DIR}"
            fi
        }
    else
        # No .gitmodules, clone directly
        echo "📥 Cloning BoringSSL (no .gitmodules found)..."
        mkdir -p "${BORINGSSL_DIR}"
        if [ ! -d "${BORINGSSL_DIR}/.git" ]; then
            git clone https://boringssl.googlesource.com/boringssl "${BORINGSSL_DIR}"
        else
            echo "✅ BoringSSL already cloned"
        fi
    fi
fi

# Verify BoringSSL is present
if [ ! -f "${BORINGSSL_DIR}/CMakeLists.txt" ]; then
    echo "❌ ERROR: BoringSSL CMakeLists.txt not found at ${BORINGSSL_DIR}"
    exit 1
fi

echo "✅ BoringSSL initialized successfully"
echo "   Location: ${BORINGSSL_DIR}"

