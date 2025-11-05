# Next Steps: Xray-core + BoringSSL Workflow Integration

## ✅ What's Been Completed

1. **Workflow Infrastructure** - Created reusable workflow for building Xray with BoringSSL
2. **Build Scripts** - Added local testing scripts
3. **Patch Templates** - Created placeholder patches (need customization)
4. **Integration Points** - Modified auto-release.yml to use BoringSSL-enabled binaries

## ⚠️ Critical Next Steps

### 1. **Understand the Integration Approach**

**Current Situation:**
- BoringSSL is already integrated in the `perf-net` native module (see `BORINGSSL_INTEGRATION_COMPLETE.md`)
- Xray-core currently builds without CGO linking to BoringSSL
- The workflow attempts to link BoringSSL directly to Xray-core via CGO

**Decision Needed:**
- **Option A**: Build Xray-core with CGO bridge to BoringSSL (requires patches)
- **Option B**: Use existing BoringSSL in perf-net, build vanilla Xray-core (simpler)
- **Option C**: Hybrid approach - build Xray with minimal CGO hooks for perf-net

### 2. **Fix Workflow Issues**

**Issue 1: Build Mode**
The workflow uses `-buildmode=c-shared` but Xray-core may need `-buildmode=c-archive` or regular build mode. Check what Xray-core expects.

**Issue 2: CGO Linking**
Without actual CGO code in Xray-core, linking BoringSSL won't make it use BoringSSL. Need to either:
- Create real CGO bridge code
- Or modify approach to use existing perf-net integration

**Issue 3: Patch Application**
Current patches are templates. Need to:
- Clone Xray-core repository
- Study the codebase structure
- Create actual patches that add CGO bridges

### 3. **Test the Workflow**

**Before pushing to main:**
1. Test locally with scripts:
   ```bash
   ./scripts/build-boringssl.sh arm64-v8a
   ./scripts/build-xray.sh arm64-v8a <paths>
   ./scripts/verify-boringssl.sh
   ```

2. Test workflow manually:
   - Push to a test branch
   - Trigger workflow_dispatch manually
   - Monitor build logs
   - Check for errors

3. Verify artifacts:
   - Download built Xray binaries
   - Check for BoringSSL symbols
   - Test on device if possible

### 4. **Customize Patches (If Using CGO Approach)**

**Steps to create real patches:**

1. **Clone Xray-core:**
   ```bash
   git clone https://github.com/XTLS/Xray-core.git
   cd Xray-core
   git checkout v25.10.15  # or your version
   ```

2. **Study the codebase:**
   - Find where crypto operations happen
   - Identify TLS/SSL implementation
   - Look for existing CGO usage

3. **Create CGO bridge:**
   - Add `crypto/boringssl_bridge.go` with actual CGO code
   - Wrap BoringSSL functions
   - Integrate with Xray's crypto package

4. **Generate patches:**
   ```bash
   git add .
   git commit -m "WIP: Add BoringSSL CGO bridge"
   git format-patch -1 HEAD
   cp 0001-*.patch ../xray-patches/001-boringssl-bridge.patch
   ```

### 5. **Alternative: Simplified Approach**

If CGO integration is too complex, consider:

**Simplified Workflow:**
1. Build vanilla Xray-core (as current)
2. Build BoringSSL for perf-net (already done)
3. Use existing perf-net BoringSSL integration
4. Skip Xray-core CGO linking

**Benefits:**
- Less complexity
- Faster builds
- Uses existing BoringSSL integration
- Lower risk of build failures

**Modify workflow:**
- Remove CGO linking from Xray build
- Keep BoringSSL build for perf-net
- Remove patch application step

### 6. **Immediate Action Items**

**Priority 1 (Before First Run):**
- [ ] Decide on integration approach (CGO vs perf-net only)
- [ ] Fix workflow build mode if needed
- [ ] Test BoringSSL build step works
- [ ] Verify artifact paths are correct

**Priority 2 (For Full Integration):**
- [ ] Create real patches or remove patch step
- [ ] Test workflow on test branch
- [ ] Verify binaries have BoringSSL symbols
- [ ] Test APK on device

**Priority 3 (Optimization):**
- [ ] Add caching for BoringSSL builds
- [ ] Optimize build times
- [ ] Add better error handling
- [ ] Improve verification steps

## 🔍 Testing Checklist

Before merging to main:

- [ ] Workflow runs without errors
- [ ] BoringSSL builds successfully for all ABIs
- [ ] Xray-core builds (with or without CGO)
- [ ] Artifacts are uploaded correctly
- [ ] Artifacts can be downloaded
- [ ] Binaries are valid (file command)
- [ ] APK builds successfully
- [ ] APK installs on device
- [ ] App runs without crashes
- [ ] Performance improvements verified (if applicable)

## 📝 Recommended Next Action

**Start with Option B (Simplified):**
1. Modify workflow to build vanilla Xray-core
2. Keep BoringSSL build for perf-net
3. Test end-to-end workflow
4. Verify perf-net BoringSSL is working
5. Later, if needed, add CGO integration

This reduces risk and gets you a working workflow faster.

## 🚨 Known Issues

1. **Build mode**: `-buildmode=c-shared` may not be correct for Xray-core
2. **CGO linking**: Without actual CGO code, linking won't help
3. **Patches**: Templates won't apply to real Xray-core
4. **Path issues**: Verify all paths in workflow are correct

## 📚 Resources

- [Xray-core Source](https://github.com/XTLS/Xray-core)
- [BoringSSL Docs](https://boringssl.googlesource.com/boringssl/)
- [Go CGO Guide](https://pkg.go.dev/cmd/cgo)
- [GitHub Actions Workflows](https://docs.github.com/en/actions/using-workflows)

