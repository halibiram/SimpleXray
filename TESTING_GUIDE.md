# Testing Guide: Xray-core + BoringSSL Workflow

## Quick Start

### 1. Test Workflow Locally (Recommended First Step)

```bash
# Test BoringSSL build
./scripts/build-boringssl.sh arm64-v8a

# If successful, test Xray build (you'll need to provide paths)
# First, note where BoringSSL was built:
BORINGSSL_DIR="app/src/main/jni/perf-net/third_party/boringssl"
BORINGSSL_BUILD="$BORINGSSL_DIR/build_arm64-v8a"

# Then build Xray (this will need NDK path too)
NDK_HOME="/path/to/android-ndk"
./scripts/build-xray.sh arm64-v8a \
  "$BORINGSSL_BUILD/crypto/libcrypto.a" \
  "$BORINGSSL_BUILD/ssl/libssl.a" \
  "$BORINGSSL_DIR/include" \
  "$NDK_HOME"

# Verify
./scripts/verify-boringssl.sh app/src/main/jniLibs/arm64-v8a/libxray.so
```

### 2. Test Workflow on GitHub (Test Branch)

**Create test branch:**
```bash
git checkout -b test/boringssl-workflow
git push origin test/boringssl-workflow
```

**Or trigger manually:**
1. Go to GitHub Actions tab
2. Click "Build Xray-core with BoringSSL" workflow
3. Click "Run workflow"
4. Select branch: `test/boringssl-workflow`
5. Set `with_boringssl: true`
6. Click "Run workflow"

**Monitor:**
- Check each job's logs
- Verify artifacts are uploaded
- Check for errors

### 3. Download and Test Artifacts

**Download artifacts:**
1. Go to workflow run
2. Click on "Artifacts" section
3. Download `xray-arm64-v8a`, `xray-armeabi-v7a`, `xray-x86_64`

**Test locally:**
```bash
# Extract artifact
unzip xray-arm64-v8a.zip

# Verify BoringSSL symbols
strings libxray.so | grep -i "BoringSSL\|boringssl"

# Check file type
file libxray.so
```

## Expected Issues & Solutions

### Issue 1: CGO Build Fails

**Symptom:**
```
# runtime/cgo
runtime/cgo: C compiler not found
```

**Solution:**
- Ensure NDK is properly set up
- Check CC environment variable points to correct toolchain
- Verify CGO_ENABLED=1 is set

**Workaround:**
- Build vanilla Xray-core (CGO_ENABLED=0)
- BoringSSL is still available via perf-net module

### Issue 2: Patches Don't Apply

**Symptom:**
```
git apply: patch does not apply
```

**Solution:**
- Current patches are templates
- Either remove patch application step
- Or create real patches based on Xray-core codebase

**Quick Fix:**
Remove patch application from workflow:
```yaml
- name: Apply Patches
  run: |
    echo "⚠️  Skipping patch application (templates only)"
    # Patches are templates, skip for now
```

### Issue 3: BoringSSL Libraries Not Found

**Symptom:**
```
❌ BoringSSL libraries not found!
```

**Solution:**
- Check artifact download step
- Verify artifact names match (boringssl-arm64-v8a)
- Check paths in workflow

### Issue 4: Build Mode Error

**Symptom:**
```
-buildmode=c-shared: not supported
```

**Solution:**
- Xray-core may not support c-shared mode
- Change to regular build mode
- Remove `-buildmode=c-shared` flag

## Testing Checklist

### Pre-Push Testing
- [ ] Scripts are executable (`chmod +x scripts/*.sh`)
- [ ] Workflow syntax is valid (use GitHub Actions validator)
- [ ] All paths are correct
- [ ] Environment variables are set

### Post-Push Testing
- [ ] Workflow runs without errors
- [ ] BoringSSL builds for all ABIs
- [ ] Xray-core builds (vanilla or CGO)
- [ ] Artifacts are uploaded
- [ ] Artifacts can be downloaded
- [ ] Binaries are valid

### Integration Testing
- [ ] Artifacts integrate with auto-release workflow
- [ ] APK builds successfully
- [ ] APK installs on device
- [ ] App runs without crashes
- [ ] BoringSSL is working (check logs)

## Performance Verification

### Check BoringSSL Integration
```bash
# In Android app logs
adb logcat | grep -i boringssl

# Should see:
# BoringSSL initialized
# Using hardware crypto acceleration
```

### Benchmark (if CGO bridge implemented)
- Test AES-GCM encryption speed
- Compare with vanilla Xray-core
- Expected: 3-40x improvement

## Rollback Plan

If workflow causes issues:

1. **Disable BoringSSL build:**
   ```yaml
   # In auto-release.yml
   build-xray-libs:
     if: false  # Disable
   ```

2. **Use vanilla Xray:**
   - The fallback step will build vanilla Xray
   - No changes needed

3. **Revert commit:**
   ```bash
   git revert <commit-hash>
   git push
   ```

## Next Steps After Testing

1. **If tests pass:**
   - Merge to main
   - Monitor first production build
   - Verify release artifacts

2. **If tests fail:**
   - Review error logs
   - Fix issues
   - Re-test on branch

3. **If CGO needed:**
   - Create real patches
   - Test CGO build locally first
   - Then integrate into workflow

## Support Resources

- Workflow logs: GitHub Actions tab
- Build artifacts: Workflow run artifacts section
- Local testing: Use scripts in `scripts/`
- Documentation: See `XRAY_BORINGSSL_WORKFLOW.md`
