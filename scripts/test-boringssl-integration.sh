#!/bin/bash
# Test script for BoringSSL integration
# This script verifies that BoringSSL is properly integrated into Xray-core

set -e

echo "🧪 Testing BoringSSL Integration"
echo "================================"

# Check if binary exists
BINARY="${1:-libxray.so}"
if [ ! -f "$BINARY" ]; then
    echo "❌ Error: Binary not found: $BINARY"
    exit 1
fi

echo "📦 Binary: $BINARY"
BINARY_SIZE=$(stat -f%z "$BINARY" 2>/dev/null || stat -c%s "$BINARY" 2>/dev/null || echo "0")
BINARY_SIZE_MB=$((BINARY_SIZE / 1024 / 1024))
echo "   Size: ${BINARY_SIZE_MB}MB"

# Test 1: Check for BoringSSL strings
echo ""
echo "🔍 Test 1: Checking for BoringSSL strings..."
BORINGSSL_STRINGS=$(strings "$BINARY" | grep -iE "BoringSSL|boringssl|OPENSSL_VERSION" | head -10 || true)
if [ -n "$BORINGSSL_STRINGS" ]; then
    echo "✅ BoringSSL strings found:"
    echo "$BORINGSSL_STRINGS" | head -5
else
    echo "⚠️  Warning: BoringSSL strings not found"
fi

# Test 2: Check for BoringSSL symbols (if nm is available)
if command -v nm &> /dev/null; then
    echo ""
    echo "🔍 Test 2: Checking for BoringSSL symbols with nm..."
    SYMBOLS=$(nm -D "$BINARY" 2>/dev/null | grep -iE "ssl_|crypto_|EVP_|BIO_|X509_" | head -20 || true)
    if [ -n "$SYMBOLS" ]; then
        SYMBOL_COUNT=$(echo "$SYMBOLS" | wc -l | tr -d ' \n')
        echo "✅ Found $SYMBOL_COUNT BoringSSL-related symbols"
        echo "$SYMBOLS" | head -5
    else
        echo "ℹ️  No BoringSSL symbols found via nm (may be stripped or not used)"
    fi
else
    echo "⚠️  nm not available, skipping symbol check"
fi

# Test 3: Check for crypto-related strings
echo ""
echo "🔍 Test 3: Checking for crypto-related strings..."
CRYPTO_STRINGS=$(strings "$BINARY" | grep -iE "aes.*gcm|EVP_aes|EVP_encrypt|SSL_|TLS_|BIO_|X509_" | head -10 || true)
if [ -n "$CRYPTO_STRINGS" ]; then
    echo "✅ Crypto-related strings found:"
    echo "$CRYPTO_STRINGS" | head -5
else
    echo "ℹ️  No crypto-related strings found"
fi

# Test 4: Check binary size (BoringSSL should increase size)
echo ""
echo "🔍 Test 4: Binary size check..."
if [ "$BINARY_SIZE_MB" -gt 20 ]; then
    echo "✅ Binary size (${BINARY_SIZE_MB}MB) indicates BoringSSL is likely linked"
else
    echo "⚠️  Binary size (${BINARY_SIZE_MB}MB) is smaller than expected for BoringSSL"
fi

# Test 5: Check linker dependencies (if readelf is available)
if command -v readelf &> /dev/null; then
    echo ""
    echo "🔍 Test 5: Checking linker dependencies..."
    DEPS=$(readelf -d "$BINARY" 2>/dev/null | grep -i "NEEDED" || true)
    if [ -n "$DEPS" ]; then
        echo "📋 Dynamic dependencies:"
        echo "$DEPS"
    else
        echo "ℹ️  No dynamic dependencies found (static linking)"
    fi
else
    echo "⚠️  readelf not available, skipping dependency check"
fi

# Test 6: Check for CGO bridge functions (if strings can find them)
echo ""
echo "🔍 Test 6: Checking for CGO bridge function names..."
BRIDGE_FUNCS=$(strings "$BINARY" | grep -iE "AES.*GCM|ChaCha.*Poly|SHA.*Hash|RandomBytes" | head -10 || true)
if [ -n "$BRIDGE_FUNCS" ]; then
    echo "✅ CGO bridge function names found:"
    echo "$BRIDGE_FUNCS"
else
    echo "ℹ️  CGO bridge function names not found (may be stripped)"
fi

echo ""
echo "📊 Test Summary"
echo "=============="
echo "✅ All basic checks completed"
echo "ℹ️  Note: Full verification requires runtime testing"
echo ""
echo "💡 To fully verify BoringSSL integration:"
echo "   1. Run Xray-core with BoringSSL-enabled binary"
echo "   2. Monitor performance improvements"
echo "   3. Check for hardware acceleration (AES-NI/NEON)"
echo "   4. Verify TLS 1.3 handshake uses BoringSSL"



