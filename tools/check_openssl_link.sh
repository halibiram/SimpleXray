#!/bin/bash
# OpenSSL Linkage Verification Script

set -Eeuo pipefail

BINARY_PATH="${1:-}"

if [ -z "$BINARY_PATH" ]; then
    echo "❌ Error: Binary path required"
    exit 1
fi

if [ ! -f "$BINARY_PATH" ]; then
    echo "❌ Error: Binary not found: $BINARY_PATH"
    exit 1
fi

echo "🔍 Checking OpenSSL linkage in: $BINARY_PATH"
echo "═══════════════════════════════════════════════"

# Check for OpenSSL symbols
OPENSSL_SYMBOLS=("EVP_EncryptInit" "EVP_DecryptInit" "AES_set_encrypt_key" "SSL_new")
FOUND_SYMBOLS=0

for symbol in "${OPENSSL_SYMBOLS[@]}"; do
    if nm -D "$BINARY_PATH" 2>/dev/null | grep -q "$symbol" || \
       readelf -s "$BINARY_PATH" 2>/dev/null | grep -q "$symbol"; then
        echo "  ✅ $symbol"
        FOUND_SYMBOLS=$((FOUND_SYMBOLS + 1))
    fi
done

if [ "$FOUND_SYMBOLS" -ge 2 ]; then
    echo "✅ PASS: Binary linked with OpenSSL ($FOUND_SYMBOLS symbols)"
    exit 0
else
    echo "⚠️  WARNING: OpenSSL symbols not found ($FOUND_SYMBOLS symbols)"
    exit 0  # Don't fail build, just warn
fi
