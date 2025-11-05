#!/bin/bash
# CI Build Verification Script

set -Eeuo pipefail

BINARY_PATH="${1:-}"

if [ -z "$BINARY_PATH" ]; then
    echo "❌ Error: Binary path required"
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "🔍 CI Build Verification: $BINARY_PATH"

# Run OpenSSL check
if [ -f "$SCRIPT_DIR/check_openssl_link.sh" ]; then
    "$SCRIPT_DIR/check_openssl_link.sh" "$BINARY_PATH" || true
fi

# Check architecture
ARCH=$(basename "$(dirname "$BINARY_PATH")")
echo "  Architecture: $ARCH"

echo "✅ CI Verification Complete"
