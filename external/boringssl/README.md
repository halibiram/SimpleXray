# BoringSSL Integration for SimpleXray

## Overview
This directory contains BoringSSL as a cryptographic library replacement for OpenSSL.

BoringSSL is Google's fork of OpenSSL that is designed to meet Google's needs.
It provides modern cryptographic primitives with better performance characteristics.

## Build Instructions

### Prerequisites
- Android NDK r23 or later (r28c recommended)
- CMake 3.19+
- Python 3.7+
- Go 1.20+ (for BoringSSL's build system)

### Building for Android

```bash
cd external/boringssl
python3 build_all_abis.py
```

This will build static libraries for all supported ABIs:
- arm64-v8a
- armeabi-v7a
- x86_64
- x86

Output libraries will be placed in:
- `lib/<abi>/libcrypto.a`
- `lib/<abi>/libssl.a`

## Integration Notes

### Security Considerations
- BoringSSL removes deprecated algorithms (MD5, RC4, 3DES)
- Only modern TLS 1.2+ is supported
- FIPS mode is NOT enabled by default
- **DO NOT** enable experimental features without security review

### API Differences from OpenSSL 3.3.0

1. **Removed APIs:**
   - `SSL_CTX_set_ecdh_auto()` - Always enabled in BoringSSL
   - `SSL_CTX_set_tmp_ecdh()` - Use `SSL_CTX_set1_curves_list()`
   - Legacy digest APIs - Use EVP interface

2. **Modified APIs:**
   - `EVP_PKEY` structures are opaque (no direct member access)
   - `SSL_METHOD` structures are const
   - `EVP_MD_CTX_init()` → `EVP_MD_CTX_new()`

3. **New APIs:**
   - `EVP_AEAD_*` - Authenticated encryption interface
   - `CRYPTO_library_init()` - Explicit initialization (optional)

### Performance Features

- **Hardware Acceleration:**
  - ARM: NEON, AES, SHA extensions auto-detected
  - x86: AES-NI, SSSE3, AVX/AVX2 auto-detected

- **Zero-Copy Operations:**
  - SSL_read/write can use application buffers directly
  - Reduced memory allocations in hot paths

## Testing

Run the test suite:
```bash
cd external/boringssl
python3 run_tests.py
```

## Version Information

BoringSSL does not have version numbers. Instead, track by commit hash:
- Current commit: (will be set by build_all_abis.py)
- Last updated: (will be set by build script)

## License

BoringSSL is licensed under OpenSSL and SSLeay licenses (permissive).
See LICENSE file in the BoringSSL repository.

## References

- BoringSSL Repository: https://boringssl.googlesource.com/boringssl/
- API Documentation: https://commondatastorage.googleapis.com/chromium-boringssl-docs/headers.html
- Porting Guide: https://github.com/google/boringssl/blob/master/PORTING.md
