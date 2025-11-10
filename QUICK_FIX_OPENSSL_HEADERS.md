# Quick Fix: OpenSSL Headers Missing

## Problem

You're seeing this error when building:

```
github.com/xtls/xray-core/crypto
Error: crypto/boringssl_bridge.go:7:10: fatal error: 'openssl/ssl.h' file not found
```

## Root Cause

The BoringSSL patches create Go files that use CGO to call OpenSSL/BoringSSL C functions. BoringSSL is configured as a **git submodule**, which means:

- It's included in the repository, but not automatically downloaded
- You need to initialize it with `git clone --recursive` or manually

Most likely, you cloned the repository without the `--recursive` flag, so the BoringSSL submodule wasn't initialized.

## Solution

**Option 1: Use the setup script** (Recommended)

```bash
./scripts/setup-boringssl.sh
```

This will:
1. Initialize the BoringSSL git submodule
2. Download BoringSSL source from Google's repository
3. Place it in `app/src/main/jni/perf-net/third_party/boringssl/`
4. Verify the headers are present

**Option 2: Manual git submodule initialization**

```bash
git submodule update --init app/src/main/jni/perf-net/third_party/boringssl
```

## Verification

After running the setup script, verify it worked:

```bash
ls -la app/src/main/jni/perf-net/third_party/boringssl/include/openssl/ssl.h
```

You should see the file exists.

## After Setup

Now you can build normally:

```bash
./gradlew assembleDebug
```

## Why Is It a Submodule?

BoringSSL is a large project (~50MB) that changes frequently. By making it a git submodule:

- We keep the main repository size small
- You can update BoringSSL independently
- The correct version is automatically tracked

## Preventing This in the Future

Always clone with `--recursive`:

```bash
git clone --recursive https://github.com/halibiram/SimpleXray
```

This automatically initializes all submodules.

## CI/CD

In GitHub Actions, the workflow automatically initializes submodules, so this issue only affects local development builds.

## Full Documentation

For more details, see:
- [BoringSSL Setup Guide](docs/boringssl-setup.md)
- [Build Guide in README](README.md#build-guide)

## Still Having Issues?

1. **Check internet connection**: The script needs to download from Google's servers
2. **Verify you're in the right directory**: Run `pwd` - you should be in the repository root
3. **Check git submodule status**:
   ```bash
   git submodule status
   ```
   Look for `app/src/main/jni/perf-net/third_party/boringssl`
4. **Try manual initialization**:
   ```bash
   git submodule sync
   git submodule update --init --recursive
   ```
5. **Verify disk space**: BoringSSL needs ~50MB
6. **Check permissions**: Make sure you have write access to `app/src/main/jni/perf-net/third_party/`

## Alternative: Use Pre-built Binaries

If you don't want to build with BoringSSL locally:

1. Download pre-built libraries from [GitHub Actions artifacts](https://github.com/halibiram/SimpleXray/actions)
2. Extract to `app/src/main/jniLibs/`
3. Build: `./gradlew assembleDebug`

This bypasses the need for BoringSSL source code locally.
