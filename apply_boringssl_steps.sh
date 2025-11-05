#!/bin/bash

# BoringSSL Integration - Apply All Steps Script
# This script automates the setup and verification process

set -e  # Exit on error

echo "🚀 BoringSSL Integration - Applying All Steps"
echo "=============================================="
echo ""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Step 1: Initialize BoringSSL Submodule
echo "📦 Step 1: Initializing BoringSSL Submodule..."
if [ ! -d "app/src/main/jni/perf-net/third_party/boringssl/CMakeLists.txt" ]; then
    echo -e "${YELLOW}⚠️  BoringSSL submodule not found, initializing...${NC}"
    git submodule update --init --recursive
    echo -e "${GREEN}✅ Submodule initialized${NC}"
else
    echo -e "${GREEN}✅ BoringSSL submodule already initialized${NC}"
fi

# Step 2: Verify BoringSSL exists
echo ""
echo "🔍 Step 2: Verifying BoringSSL..."
if [ -f "app/src/main/jni/perf-net/third_party/boringssl/CMakeLists.txt" ]; then
    echo -e "${GREEN}✅ BoringSSL found at: app/src/main/jni/perf-net/third_party/boringssl/${NC}"
else
    echo -e "${RED}❌ BoringSSL not found!${NC}"
    echo "   Please run: git submodule update --init --recursive"
    exit 1
fi

# Step 3: Verify CMakeLists.txt exists
echo ""
echo "📝 Step 3: Verifying CMakeLists.txt..."
if [ -f "app/src/main/jni/perf-net/CMakeLists.txt" ]; then
    echo -e "${GREEN}✅ CMakeLists.txt found${NC}"
else
    echo -e "${RED}❌ CMakeLists.txt not found!${NC}"
    exit 1
fi

# Step 4: Verify all source files exist
echo ""
echo "📁 Step 4: Verifying source files..."
SOURCE_FILES=(
    "app/src/main/jni/perf-net/src/perf_crypto_neon.cpp"
    "app/src/main/jni/perf-net/src/perf_tls_handshake.cpp"
    "app/src/main/jni/perf-net/src/perf_quic_handshake.cpp"
    "app/src/main/jni/perf-net/src/perf_tls_evasion.cpp"
    "app/src/main/jni/perf-net/src/perf_cert_verifier.cpp"
    "app/src/main/jni/perf-net/src/perf_tls_keylog.cpp"
)

ALL_EXIST=true
for file in "${SOURCE_FILES[@]}"; do
    if [ -f "$file" ]; then
        echo -e "${GREEN}✅ $file${NC}"
    else
        echo -e "${RED}❌ $file - MISSING!${NC}"
        ALL_EXIST=false
    fi
done

if [ "$ALL_EXIST" = false ]; then
    echo -e "${RED}❌ Some source files are missing!${NC}"
    exit 1
fi

# Step 5: Clean previous build artifacts
echo ""
echo "🧹 Step 5: Cleaning previous build artifacts..."
if [ -d "app/build" ]; then
    rm -rf app/build
    echo -e "${GREEN}✅ Build directory cleaned${NC}"
else
    echo -e "${YELLOW}⚠️  No build directory to clean${NC}"
fi

# Remove OpenSSL artifacts if they exist
if [ -d "app/src/main/jni/openssl" ]; then
    echo -e "${YELLOW}⚠️  Old OpenSSL directory found, removing...${NC}"
    rm -rf app/src/main/jni/openssl
    echo -e "${GREEN}✅ OpenSSL artifacts removed${NC}"
fi

# Step 6: Verify Gradle configuration
echo ""
echo "⚙️  Step 6: Verifying Gradle configuration..."
if grep -q "BORINGSSL" app/build.gradle; then
    echo -e "${GREEN}✅ BoringSSL referenced in build.gradle${NC}"
else
    echo -e "${YELLOW}⚠️  BoringSSL not found in build.gradle (may be OK if using CMake)${NC}"
fi

if grep -q "cmake" app/build.gradle; then
    echo -e "${GREEN}✅ CMake configuration found in build.gradle${NC}"
else
    echo -e "${RED}❌ CMake configuration missing in build.gradle!${NC}"
    exit 1
fi

# Step 7: Verify Android.mk has OpenSSL disabled
echo ""
echo "🔧 Step 7: Verifying Android.mk..."
if grep -q "DISABLE_OPENSSL" app/src/main/jni/perf-net/Android.mk; then
    echo -e "${GREEN}✅ OpenSSL disabled in Android.mk${NC}"
else
    echo -e "${YELLOW}⚠️  DISABLE_OPENSSL flag not found in Android.mk${NC}"
fi

# Step 8: Check GitHub Actions workflows
echo ""
echo "🔄 Step 8: Verifying GitHub Actions workflows..."
if grep -q "BoringSSL" .github/workflows/build.yml; then
    echo -e "${GREEN}✅ BoringSSL build step found in build.yml${NC}"
else
    echo -e "${RED}❌ BoringSSL build step missing in build.yml!${NC}"
    exit 1
fi

if grep -q "BoringSSL" .github/workflows/auto-release.yml; then
    echo -e "${GREEN}✅ BoringSSL build step found in auto-release.yml${NC}"
else
    echo -e "${RED}❌ BoringSSL build step missing in auto-release.yml!${NC}"
    exit 1
fi

# Step 9: Summary
echo ""
echo "=============================================="
echo "📊 Summary"
echo "=============================================="
echo -e "${GREEN}✅ All verification steps passed!${NC}"
echo ""
echo "Next steps:"
echo "1. Build the project: ./gradlew clean assembleDebug"
echo "2. Test on device: adb install app/build/outputs/apk/debug/simplexray-arm64-v8a-debug.apk"
echo "3. Check logs: adb logcat | grep -E 'PerfCrypto|BoringSSL'"
echo ""
echo "📚 Documentation:"
echo "  - Quick Start: QUICK_START_BORINGSSL.md"
echo "  - Detailed Steps: NEXT_STEPS_BORINGSSL.md"
echo "  - Integration Summary: INTEGRATION_SUMMARY.md"
echo ""

# Step 10: Optional - Check if gradlew is executable
echo "🔨 Step 10: Checking Gradle wrapper..."
if [ -f "gradlew" ]; then
    chmod +x gradlew
    echo -e "${GREEN}✅ Gradle wrapper is executable${NC}"
else
    echo -e "${YELLOW}⚠️  gradlew not found${NC}"
fi

echo ""
echo -e "${GREEN}🎉 All steps applied successfully!${NC}"
echo ""
echo "Ready to build! Run: ./gradlew clean assembleDebug"

