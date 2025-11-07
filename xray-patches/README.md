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

- **002-crypto-boringssl.patch** - Build tag for crypto/cipher when CGO is disabled
  - Adds `//go:build !cgo` to `crypto/cipher/gcm.go`
  - Allows fallback to Go crypto when CGO is disabled

- **003-tls-boringssl.patch** - Build tag for crypto/tls when CGO is disabled
  - Adds `//go:build !cgo` to `crypto/tls/conn.go`
  - Allows fallback to Go TLS when CGO is disabled

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

Patches are applied in order (001, 002, 003, ...). If a patch fails:
- Build continues with vanilla Xray-core
- BoringSSL is still linked via CGO flags
- Warning is logged but build doesn't fail

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
