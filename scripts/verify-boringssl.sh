#!/bin/bash
# Verify BoringSSL integration in Xray binaries
# Usage: ./verify-boringssl.sh <path_to_libxray.so>

set -e

BINARY_PATH=${1:-app/src/main/jniLibs/*/libxray.so}

if [ ! -f "$BINARY_PATH" ] && [ -z "$(ls $BINARY_PATH 2>/dev/null)" ]; then
    echo "❌ Error: Binary not found: $BINARY_PATH"
    echo "Usage: $0 <path_to_libxray.so>"
    exit 1
fi

echo "🔍 Verifying BoringSSL integration..."
echo ""

# Check each ABI if multiple binaries
for binary in $BINARY_PATH; do
    if [ ! -f "$binary" ]; then
        continue
    fi
    
    ABI=$(echo "$binary" | grep -oE '(arm64-v8a|armeabi-v7a|x86_64|x86)' || echo "unknown")
    echo "📦 Checking: $binary (ABI: $ABI)"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    
    # Check for BoringSSL symbols
    echo "🔍 Checking for BoringSSL symbols..."
    if strings "$binary" | grep -qi "BoringSSL\|boringssl"; then
        echo "  ✅ BoringSSL symbols found"
        strings "$binary" | grep -i "BoringSSL\|boringssl" | head -3
    else
        echo "  ❌ BoringSSL symbols NOT found"
    fi
    
    # Check for crypto functions
    echo ""
    echo "🔍 Checking for crypto functions..."
    if strings "$binary" | grep -qi "aes.*gcm\|EVP_aes"; then
        echo "  ✅ AES-GCM functions found"
        strings "$binary" | grep -i "aes.*gcm\|EVP_aes" | head -3
    else
        echo "  ⚠️  AES-GCM functions not found"
    fi
    
    # Check for hardware acceleration
    echo ""
    echo "🔍 Checking for hardware acceleration..."
    if strings "$binary" | grep -qi "aesni\|neon\|armv8"; then
        echo "  ✅ Hardware acceleration symbols found"
        strings "$binary" | grep -i "aesni\|neon\|armv8" | head -3
    else
        echo "  ⚠️  Hardware acceleration symbols not found"
    fi
    
    # Check for TLS functions
    echo ""
    echo "🔍 Checking for TLS functions..."
    if strings "$binary" | grep -qi "tls.*1\.3\|SSL_"; then
        echo "  ✅ TLS functions found"
        strings "$binary" | grep -i "tls.*1\.3\|SSL_" | head -3
    else
        echo "  ⚠️  TLS functions not found"
    fi
    
    # File size and architecture
    echo ""
    echo "📊 Binary information:"
    ls -lh "$binary"
    if command -v file &> /dev/null; then
        file "$binary"
    fi
    
    # Check for C++ standard library (needed for BoringSSL)
    echo ""
    echo "🔍 Checking for C++ standard library..."
    if strings "$binary" | grep -qi "libc\+\+"; then
        echo "  ✅ C++ standard library linked"
    else
        echo "  ⚠️  C++ standard library not found"
    fi
    
    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""
done

# Generate report
echo "📋 Verification Summary:"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

VERIFICATION_PASSED=true
for binary in $BINARY_PATH; do
    if [ ! -f "$binary" ]; then
        continue
    fi
    
    ABI=$(echo "$binary" | grep -oE '(arm64-v8a|armeabi-v7a|x86_64|x86)' || echo "unknown")
    
    if ! strings "$binary" | grep -qi "BoringSSL\|boringssl"; then
        echo "❌ $ABI: BoringSSL symbols missing"
        VERIFICATION_PASSED=false
    else
        echo "✅ $ABI: BoringSSL integration verified"
    fi
done

echo ""
if [ "$VERIFICATION_PASSED" = true ]; then
    echo "✅ All verifications passed!"
    exit 0
else
    echo "❌ Some verifications failed"
    exit 1
fi











