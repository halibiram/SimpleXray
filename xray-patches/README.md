# Xray-core Patches for BoringSSL Integration

This directory contains patches to modify Xray-core for BoringSSL integration.

## Current Status

**BoringSSL is linked via CGO flags in the build workflow** - no code patches are required.

The build workflow (`build-xray-boringssl.yml`) configures:
- `CGO_CFLAGS`: BoringSSL include path
- `CGO_LDFLAGS`: BoringSSL crypto and ssl libraries (static linking)

## Patch Files

- **001-boringssl-bridge.patch** - Placeholder (optional, not required)

## How It Works

1. **BoringSSL Libraries**: Built separately and downloaded as artifacts
2. **CGO Linking**: Configured in build workflow via environment variables
3. **Static Linking**: BoringSSL libraries are statically linked to Xray-core binary
4. **No Code Changes**: Xray-core code remains unchanged

## Build Process

1. BoringSSL is built for each ABI (arm64-v8a, x86_64)
2. Xray-core is cloned
3. Patches are attempted (optional, can fail)
4. Xray-core is built with CGO flags linking BoringSSL
5. Binary is verified for BoringSSL symbols

## Verification

After build, the binary is checked for BoringSSL symbols:
```bash
strings libxray.so | grep -i "BoringSSL\|boringssl"
```

## Future Improvements

For full BoringSSL integration (using BoringSSL in code, not just linking):
1. Create CGO bridge in Xray-core crypto package
2. Replace Go crypto/tls with BoringSSL calls
3. Enable hardware acceleration (AES-NI/NEON)

See `PATCH_STRATEGY.md` for detailed strategy.

## Full Integration TODO

For complete BoringSSL integration roadmap, see:
- `../BORINGSSL_FULL_INTEGRATION_TODO.md` - Comprehensive TODO list with all required steps for full BoringSSL integration
