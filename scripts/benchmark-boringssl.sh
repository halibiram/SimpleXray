#!/bin/bash
# Benchmark script for BoringSSL vs Go crypto performance
# This script compares encryption/decryption performance

set -e

echo "📊 BoringSSL Performance Benchmark"
echo "=================================="

# Check if Xray-core directory exists
XRAY_DIR="Xray-core"
if [ ! -d "$XRAY_DIR" ]; then
    echo "❌ Error: Xray-core directory not found"
    echo "   Please run this script from the SimpleXray root directory"
    echo "   or ensure Xray-core is cloned"
    exit 1
fi

cd "$XRAY_DIR"

# Check if Go is available
if ! command -v go &> /dev/null; then
    echo "❌ Error: Go is not installed or not in PATH"
    exit 1
fi

echo "🔧 Running BoringSSL crypto benchmarks..."
echo ""

# Run crypto benchmarks if test file exists
if [ -f "crypto/boringssl_tests.go" ]; then
    echo "📋 Running BoringSSL crypto benchmarks..."
    go test -bench=BenchmarkAES -benchmem -v ./crypto 2>&1 | tee ../boringssl-benchmark.log || {
        echo "⚠️  Benchmark failed - this is OK if patches aren't applied"
    }
    
    echo ""
    echo "📊 Benchmark Results:"
    if [ -f "../boringssl-benchmark.log" ]; then
        grep -E "Benchmark|ns/op|B/op|allocs/op" ../boringssl-benchmark.log | head -20 || true
    fi
else
    echo "ℹ️  BoringSSL test file not found (patches may not be applied)"
    echo ""
    echo "📋 Planned benchmarks:"
    echo "   1. AES-128-GCM encryption/decryption throughput"
    echo "   2. AES-256-GCM encryption/decryption throughput"
    echo "   3. ChaCha20-Poly1305 encryption/decryption throughput"
    echo "   4. TLS 1.3 handshake latency"
    echo "   5. Memory usage comparison"
    echo ""
    echo "💡 To run benchmarks:"
    echo "   1. Apply BoringSSL patches to Xray-core"
    echo "   2. Run: go test -bench=. -benchmem ./crypto"
fi

echo ""
echo "🔍 Hardware Acceleration Detection:"
echo "   - AES-NI (x86_64): Check CPU flags"
echo "   - NEON (ARM64): Check CPU features"
echo "   - BoringSSL automatically uses hardware acceleration when available"

# Check CPU features if on Linux
if [ "$(uname)" = "Linux" ]; then
    echo ""
    echo "📋 CPU Features:"
    if [ -f /proc/cpuinfo ]; then
        if grep -q "aes" /proc/cpuinfo 2>/dev/null; then
            echo "   ✅ AES-NI support detected"
        else
            echo "   ℹ️  AES-NI not detected (may be ARM or older CPU)"
        fi
        
        if grep -q "neon\|asimd" /proc/cpuinfo 2>/dev/null; then
            echo "   ✅ NEON/ASIMD support detected"
        else
            echo "   ℹ️  NEON not detected (may be x86_64 or older CPU)"
        fi
    fi
fi

echo ""
echo "✅ Benchmark script complete"

