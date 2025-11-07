# Xray-core Patches for BoringSSL Integration

This directory contains patches to modify Xray-core for BoringSSL integration.

## Current Status

**BoringSSL is linked via CGO flags in the build workflow** - no code patches are required.

The build workflow (`build-xray-boringssl.yml`) configures:

- `CGO_CFLAGS`: BoringSSL include path
- `CGO_LDFLAGS`: BoringSSL crypto and ssl libraries (static linking)

## Patch Files

- **001-boringssl-bridge.patch** - CGO bridge for BoringSSL crypto functions

  - Adds `crypto/boringssl_bridge.go` with BoringSSL wrapper functions
  - Implements AES-GCM, ChaCha20-Poly1305, SHA256/SHA512, RandomBytes
  - Provides `BoringSSLGCM` type implementing `cipher.AEAD` interface

- **002-crypto-boringssl.patch** - Crypto wrapper with build tags for CGO/non-CGO

  - Creates `common/crypto/cipher.go` (CGO enabled) - BoringSSL wrapper functions
  - Creates `common/crypto/cipher_nocgo.go` (CGO disabled) - Go stdlib fallback
  - Provides `NewGCM`, `NewGCMWithNonceSize`, `NewGCMWithTagSize` functions
  - Automatically selects BoringSSL or Go crypto based on CGO availability

- **003-tls-boringssl.patch** - TLS wrapper with build tags for CGO/non-CGO

  - Creates `common/crypto/tls.go` (CGO enabled) - BoringSSL TLS wrapper
  - Creates `common/crypto/tls_nocgo.go` (CGO disabled) - Go stdlib fallback
  - Provides `TLSConfig`, `Client`, `Server`, `Dial` wrapper functions
  - Automatically selects BoringSSL or Go TLS based on CGO availability

- **004-x509-boringssl.patch** - X.509 wrapper with build tags for CGO/non-CGO

  - Creates `common/crypto/x509.go` (CGO enabled) - BoringSSL cert pool wrapper
  - Creates `common/crypto/x509_nocgo.go` (CGO disabled) - Go stdlib fallback
  - Provides `CertPool`, `NewCertPool`, `SystemCertPool` wrapper functions
  - Automatically selects BoringSSL or Go x509 based on CGO availability

- **005-boringssl-tls-bridge.patch** - TLS connection bridge using BoringSSL

  - Adds `crypto/tls/boringssl_tls_bridge.go` with BoringSSL TLS wrapper
  - Implements `BoringSSLConn` type for TLS connections
  - Provides TLS 1.3 handshake, read, write operations

- **boringssl_tests.go** - Unit tests and benchmarks for BoringSSL bridge
  - Test functions for all crypto operations
  - Benchmark functions for performance measurement
  - Integration tests for TLS operations

## How It Works

1. **BoringSSL Libraries**: Built separately and downloaded as artifacts
2. **CGO Linking**: Configured in build workflow via environment variables
3. **Static Linking**: BoringSSL libraries are statically linked to Xray-core binary
4. **Code Patches**: Optional patches to enable BoringSSL usage in code (not just linking)

## Build Process

1. BoringSSL is built for each ABI (arm64-v8a, x86_64)
2. Xray-core is cloned
3. Patches are attempted (optional, can fail)
4. Xray-core is built with CGO flags linking BoringSSL
5. Binary is verified for BoringSSL symbols

## Patch Application

Patches are applied in order (001, 002, 003, ...).

**All patches (MUST succeed):**
- 001-boringssl-bridge.patch - CGO bridge for BoringSSL crypto primitives
- 002-crypto-boringssl.patch - Crypto wrapper layer (CGO/non-CGO build tags)
- 003-tls-boringssl.patch - TLS wrapper layer (CGO/non-CGO build tags)
- 004-x509-boringssl.patch - X.509 wrapper layer (CGO/non-CGO build tags)
- 005-boringssl-tls-bridge.patch - TLS connection bridge using BoringSSL
- 007-boringssl-handshake-trace.patch - Handshake tracing and debugging

**Build Tag Strategy:**
- When `CGO_ENABLED=1`: Uses BoringSSL implementations (hardware acceleration)
- When `CGO_ENABLED=0`: Falls back to Go stdlib (pure Go, portable)
- Build tags automatically select correct implementation at compile time

**Why Wrapper Approach?**
- ❌ Cannot modify Go stdlib directly (Go issue #35283)
- ✅ Create wrappers in `common/crypto/` package instead
- ✅ Use build tags (`//go:build cgo` vs `//go:build !cgo`)
- ✅ Xray-core code imports `common/crypto` instead of `crypto/*`

If a patch fails:
- Build will fail with error
- Check patch compatibility with Xray-core version
- Verify Xray-core has `common/` directory structure

## Verification

After build, the binary is checked for BoringSSL symbols:

```bash
strings libxray.so | grep -i "BoringSSL\|boringssl"
```

## Future Improvements

For full BoringSSL integration (using BoringSSL in code, not just linking):

1. Create CGO bridge in Xray-core crypto package ✅ (001-boringssl-bridge.patch)
2. Replace Go crypto/tls with BoringSSL calls (requires additional patches)
3. Enable hardware acceleration (AES-NI/NEON)

See `PATCH_STRATEGY.md` for detailed strategy.

## Full Integration TODO

For complete BoringSSL integration roadmap, see:

- `../BORINGSSL_FULL_INTEGRATION_TODO.md` - Comprehensive TODO list with all required steps for full BoringSSL integration

## Testing Patches

To test if patches can be applied to a specific Xray-core version:

```bash
# Clone Xray-core
git clone https://github.com/XTLS/Xray-core.git
cd Xray-core
git checkout v25.10.15  # or your version

# Test patch application
git apply --check ../SimpleXray/xray-patches/001-boringssl-bridge.patch
```

Or use the GitHub Actions workflow:

- `test-boringssl-patches.yml` - Automatically tests patches on PR or manual trigger
