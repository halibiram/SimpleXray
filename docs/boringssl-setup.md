# BoringSSL Setup Guide

This guide explains how to set up BoringSSL for local development with SimpleXray.

## Why BoringSSL?

BoringSSL is Google's fork of OpenSSL, optimized for performance and security. SimpleXray can optionally build Xray-core with BoringSSL support for:

- **Better Performance**: Hardware crypto acceleration (AES-NI, ARMv8 Crypto)
- **Modern TLS**: TLS 1.3 optimizations
- **Reduced Size**: Smaller binary footprint

## Quick Setup

For local development, you need BoringSSL source code (headers) before building:

```bash
./scripts/setup-boringssl.sh
```

This will:
1. Clone BoringSSL source from Google's repository
2. Verify headers are present
3. Prepare for local builds

## What Gets Installed?

The script clones BoringSSL to:
```
app/src/main/jni/perf-net/third_party/boringssl/
```

This provides:
- **Headers** (`include/openssl/*.h`) - Required for CGO compilation
- **Source** - Required for building BoringSSL libraries
- **CMake files** - Build system configuration

## Why Do I Need This?

When building Xray-core locally with BoringSSL patches applied, the Go compiler (CGO) needs to find OpenSSL-compatible headers. Without these headers, you'll see errors like:

```
Error: crypto/boringssl_bridge.go:7:10: fatal error: 'openssl/ssl.h' file not found
```

BoringSSL is configured as a git submodule, so it should be automatically initialized when you clone with `--recursive`. However, if you cloned without the `--recursive` flag, you need to manually initialize the submodule.

## Building After Setup

### Option 1: Build Everything Locally

```bash
# 1. Setup BoringSSL source (one-time)
./scripts/setup-boringssl.sh

# 2. Build BoringSSL libraries
./scripts/build-boringssl.sh arm64-v8a

# 3. Build Xray-core with BoringSSL
./scripts/build-xray.sh arm64-v8a \
    app/src/main/jni/perf-net/third_party/boringssl/build_arm64-v8a/crypto/libcrypto.a \
    app/src/main/jni/perf-net/third_party/boringssl/build_arm64-v8a/ssl/libssl.a \
    app/src/main/jni/perf-net/third_party/boringssl/include
```

### Option 2: Use CI/CD Artifacts

If you don't want to build locally, use pre-built binaries from GitHub Actions:

1. Download artifacts from the latest [successful workflow run](https://github.com/halibiram/SimpleXray/actions)
2. Extract to `app/src/main/jniLibs/`
3. Build APK normally: `./gradlew assembleDebug`

## CI/CD Builds

BoringSSL is automatically built and integrated in CI/CD:

- **Workflow**: `.github/workflows/build-xray-boringssl.yml`
- **Artifacts**: `xray-arm64-v8a`, `xray-x86_64`
- **Usage**: Automatic in release builds

The CI/CD workflow:
1. Clones BoringSSL
2. Builds static libraries for each ABI
3. Builds Xray-core with BoringSSL linked
4. Packages into APK

## Troubleshooting

### Error: "openssl/ssl.h file not found"

**Solution**: Run `./scripts/setup-boringssl.sh`

This error means BoringSSL headers aren't available. The setup script will clone the source.

### Error: "BoringSSL directory not found"

**Solution**:
```bash
./scripts/setup-boringssl.sh
```

### Error: "CMakeLists.txt not found"

The BoringSSL clone may be incomplete. Remove and re-clone:

```bash
rm -rf app/src/main/jni/perf-net/third_party/boringssl
./scripts/setup-boringssl.sh
```

### Clone is very slow

Try using the GitHub mirror:

```bash
git clone --depth=1 https://github.com/google/boringssl.git \
    app/src/main/jni/perf-net/third_party/boringssl
```

## Directory Structure

After setup:

```
app/src/main/jni/perf-net/third_party/boringssl/
├── include/
│   └── openssl/          # Headers (ssl.h, crypto.h, etc.)
├── crypto/               # Crypto source files
├── ssl/                  # SSL/TLS source files
├── CMakeLists.txt        # Build configuration
└── build_arm64-v8a/      # Build output (after building)
    ├── crypto/
    │   └── libcrypto.a
    └── ssl/
        └── libssl.a
```

## Build Prerequisites

To build BoringSSL locally, you need:

- **CMake** (3.0+)
- **Ninja** or Make
- **Android NDK** (r28c recommended)
- **Go** (1.23+ for Xray-core)

## Environment Variables

The build scripts use these variables:

- `ANDROID_NDK_HOME` - Path to Android NDK
- `CGO_CFLAGS` - Include path for headers
- `CGO_LDFLAGS` - Library paths for linking

Example:
```bash
export ANDROID_NDK_HOME=/path/to/ndk
export CGO_CFLAGS="-I$(pwd)/app/src/main/jni/perf-net/third_party/boringssl/include"
export CGO_LDFLAGS="-L$(pwd)/boringssl-arm64-v8a/crypto -lcrypto -lssl"
```

## .gitignore

BoringSSL source is excluded from version control:

```gitignore
app/src/main/jni/perf-net/third_party/boringssl/
```

Each developer needs to run `./scripts/setup-boringssl.sh` locally.

## References

- [BoringSSL Official](https://boringssl.googlesource.com/boringssl/)
- [BoringSSL on GitHub](https://github.com/google/boringssl)
- [Xray-core](https://github.com/XTLS/Xray-core)
- [CGO Documentation](https://pkg.go.dev/cmd/cgo)

## FAQ

### Q: Is BoringSSL required for SimpleXray?

**A**: No. SimpleXray works fine with standard Go crypto. BoringSSL is an optional optimization for better performance.

### Q: Can I use OpenSSL instead?

**A**: The patches are designed for BoringSSL's API. OpenSSL may work with modifications, but it's not tested.

### Q: How much space does BoringSSL take?

**A**:
- Source: ~50MB
- Built libraries: ~30MB per ABI
- Total: ~100-150MB for development

### Q: Should I commit BoringSSL to git?

**A**: No. It's excluded via `.gitignore`. Each developer runs the setup script.

### Q: Does this affect APK size?

**A**: Yes, BoringSSL adds ~5-10MB per ABI to the final APK. However, the performance benefits often outweigh the size increase.

## Support

For issues or questions:

- [GitHub Issues](https://github.com/halibiram/SimpleXray/issues)
- Check [CI/CD workflow logs](https://github.com/halibiram/SimpleXray/actions)
