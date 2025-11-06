# Xray-core Patches for BoringSSL Integration

This directory contains patches to modify Xray-core for BoringSSL integration.

## Patch Files

- **001-boringssl-bridge.patch** - Adds CGO bridge to enable BoringSSL crypto functions
- **002-tls-optimization.patch** - Optimizes TLS implementation with BoringSSL
- **003-xtls-splice.patch** - Enhances XTLS Vision flow with BoringSSL zero-copy

## Important Notes

⚠️ **These are template patches** and need to be customized based on:
- The actual Xray-core codebase structure
- The specific version of Xray-core being built
- The changes required to integrate BoringSSL

## How to Create Proper Patches

1. Clone Xray-core repository
2. Make your changes to enable BoringSSL
3. Generate patches using: `git diff > ../xray-patches/001-boringssl-bridge.patch`
4. Test patches apply cleanly: `git apply --check 001-boringssl-bridge.patch`

## Applying Patches

Patches are automatically applied during the GitHub Actions build process. If patches fail to apply, the build will continue with vanilla Xray-core.



