# BoringSSL Migration Rollback Plan

## Quick Rollback (5 minutes)

### Option 1: Runtime Switch (No Rebuild)
```bash
export SXR_SSL_MODE=openssl
# App will use OpenSSL at next launch
```

### Option 2: Gradle Property (Rebuild Required)
```bash
./gradlew clean
./gradlew assembleRelease -PuseBoringssl=false
adb install -r app/build/outputs/apk/release/app-release.apk
```

## Full Rollback (Revert Git Changes)

### Step 1: Identify Migration Commits
```bash
git log --oneline --grep="boringssl"
```

### Step 2: Revert Commits
```bash
# Revert all migration commits
git revert <commit-sha>
```

### Step 3: Clean and Rebuild
```bash
./gradlew clean
rm -rf app/build app/.cxx
rm -rf external/boringssl/lib external/boringssl/src
./gradlew assembleRelease
```

### Step 4: Verify Rollback
```bash
# Check native libraries
unzip -l app/build/outputs/apk/release/app-release.apk | grep "\.so$"

# Install and test
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n com.simplexray.an/.MainActivity
```

## Automated Rollback Script

Save as `scripts/rollback_boringssl.sh`:

```bash
#!/bin/bash
set -e

echo "╔════════════════════════════════════════╗"
echo "║  BoringSSL Migration Rollback Script   ║"
echo "╚════════════════════════════════════════╝"

read -p "This will roll back to OpenSSL. Continue? (y/N) " -n 1 -r
echo
if [[ ! $REPLY =~ ^[Yy]$ ]]; then exit 1; fi

echo "[1/5] Cleaning build artifacts..."
./gradlew clean && rm -rf app/build app/.cxx

echo "[2/5] Setting OpenSSL mode..."
echo "USE_BORINGSSL=false" >> gradle.properties

echo "[3/5] Removing BoringSSL artifacts..."
rm -rf external/boringssl/lib external/boringssl/build

echo "[4/5] Rebuilding with OpenSSL..."
./gradlew assembleDebug -PuseBoringssl=false

echo "[5/5] Verifying build..."
if [ -f "app/build/outputs/apk/debug/app-debug.apk" ]; then
    echo "✓ Build successful"
    echo "APK: app/build/outputs/apk/debug/app-debug.apk"
else
    echo "✗ Build failed"
    exit 1
fi
```

Usage:
```bash
chmod +x scripts/rollback_boringssl.sh
./scripts/rollback_boringssl.sh
```

## Validation Checklist

After rollback, verify:
- [ ] App builds without errors
- [ ] Native libraries are linked (check with `nm`)
- [ ] Crypto functions work (run CryptoTest)
- [ ] Network connections succeed
- [ ] Performance is acceptable
- [ ] No crashes on all target devices

## Emergency Hotfix (Production)

Deploy last known good APK:
```bash
# Download from GitHub Releases
wget https://github.com/halibiram/SimpleXray/releases/download/v1.10.128/app-release.apk

# Sign and deploy
apksigner sign --ks keystore.jks app-release.apk
adb install -r app-release.apk
```

## Recovery Scenarios

### Scenario 1: Build Failures
**Symptoms**: `libcrypto.a not found`, linker errors
**Solution**: Build BoringSSL or switch to OpenSSL

### Scenario 2: Runtime Crashes
**Symptoms**: SIGSEGV in crypto functions, `dlopen` failures
**Solution**: Enable OpenSSL mode or rebuild

### Scenario 3: Performance Degradation
**Symptoms**: Slow encryption, high CPU usage
**Solution**: Check hardware acceleration, force OpenSSL

### Scenario 4: Compatibility Issues
**Symptoms**: Works on some devices, fails on others
**Solution**: Build with hybrid mode

## Contact

For rollback assistance:
- GitHub Issues: https://github.com/halibiram/SimpleXray/issues

---

**Last Updated**: 2025-11-05
