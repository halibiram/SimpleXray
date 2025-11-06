# AI Build Fixer Knowledge Base

## Version History
- **v1.0.0** (2024-01-01): Initial knowledge base created

## Error Classification System

### Build Stage Detection
The agent monitors these stages:
1. **Environment Setup**: JDK, NDK, CMake installation
2. **Dependency Resolution**: Gradle dependency downloads
3. **Kotlin Compilation**: Source code compilation
4. **KSP Processing**: Annotation processing
5. **CMake Toolchain Detection**: NDK toolchain configuration
6. **NDK Packaging**: Native library compilation
7. **Artifact Signing**: APK signing and verification

## Common Error Patterns

### CMake/NDK Errors
- **Symptom**: `clang: error: unsupported argument '-march=armv8-a+simd+crypto'`
- **Root Cause**: Clang doesn't support the combined march flag format
- **Fix**: Use `-march=armv8-a` or remove explicit march flags, let CMake auto-detect
- **Files Modified**: `CMakeLists.txt`, `build.gradle` (cppFlags)

### Library Not Found
- **Symptom**: `Libraries not found` after successful build
- **Root Cause**: Build succeeds but artifacts aren't in expected location
- **Fix**: Verify artifact upload paths, check ABI filtering, ensure build output directories are correct
- **Files Modified**: `.github/workflows/*.yml` (artifact paths)

### Kotlin/Compose Compatibility
- **Symptom**: `JVM default` warnings or compilation errors
- **Root Cause**: Kotlin language level mismatch with Compose compiler
- **Fix**: Enable `jvmDefault=all`, ensure Kotlin version matches Compose compiler version
- **Files Modified**: `app/build.gradle` (kotlinOptions)

### Dependency Resolution
- **Symptom**: `Could not resolve dependency` or version conflicts
- **Root Cause**: Version incompatibilities or repository issues
- **Fix**: Update dependency versions, check version catalogs, verify repository configurations
- **Files Modified**: `build.gradle`, `settings.gradle`, `gradle.properties`

## Compatibility Matrix Priorities

1. **Kotlin**: Prefer LTS versions (currently 2.0.x)
2. **Compose Compiler**: Must match Kotlin minor version
3. **AGP**: Must be compatible with Gradle version
4. **NDK**: Use versions recommended by AGP
5. **BoringSSL**: Use stable versions for Android

## Patch Strategy

### Escalation Levels
1. **Level 1**: Minimal targeted fix (single file, single change)
2. **Level 2**: Multi-file coordination (version updates across files)
3. **Level 3**: Configuration restructuring (gradle.properties, settings.gradle)
4. **Level 4**: Fallback mechanisms (BoringSSL replacement, version pinning)

### Safety Rules
- ✅ Always test minimal changes first
- ✅ Never delete critical modules
- ✅ Never disable security hardening
- ✅ Never downgrade blindly
- ✅ Always verify compatibility matrix

## Learning Process

After each successful fix:
1. Extract error pattern
2. Document fix applied
3. Update patterns.json
4. Append to knowledge.md
5. Update agent logic if needed

## Self-Improvement

The agent evolves by:
- Tracking success rates of patterns
- Generalizing fixes to broader categories
- Building decision trees for complex errors
- Creating proactive prevention rules

