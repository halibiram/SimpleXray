# BoringSSL Integration - Apply All Steps Script (PowerShell)
# This script automates the setup and verification process for Windows

Write-Host "🚀 BoringSSL Integration - Applying All Steps" -ForegroundColor Cyan
Write-Host "==============================================" -ForegroundColor Cyan
Write-Host ""

$ErrorActionPreference = "Stop"

# Step 1: Initialize BoringSSL Submodule
Write-Host "📦 Step 1: Initializing BoringSSL Submodule..." -ForegroundColor Yellow
$boringsslPath = "app\src\main\jni\perf-net\third_party\boringssl\CMakeLists.txt"
if (-not (Test-Path $boringsslPath)) {
    Write-Host "⚠️  BoringSSL submodule not found, initializing..." -ForegroundColor Yellow
    git submodule update --init --recursive
    Write-Host "✅ Submodule initialized" -ForegroundColor Green
} else {
    Write-Host "✅ BoringSSL submodule already initialized" -ForegroundColor Green
}

# Step 2: Verify BoringSSL exists
Write-Host ""
Write-Host "🔍 Step 2: Verifying BoringSSL..." -ForegroundColor Yellow
if (Test-Path $boringsslPath) {
    Write-Host "✅ BoringSSL found" -ForegroundColor Green
} else {
    Write-Host "❌ BoringSSL not found!" -ForegroundColor Red
    Write-Host "   Please run: git submodule update --init --recursive" -ForegroundColor Red
    exit 1
}

# Step 3: Verify CMakeLists.txt exists
Write-Host ""
Write-Host "📝 Step 3: Verifying CMakeLists.txt..." -ForegroundColor Yellow
$cmakePath = "app\src\main\jni\perf-net\CMakeLists.txt"
if (Test-Path $cmakePath) {
    Write-Host "✅ CMakeLists.txt found" -ForegroundColor Green
} else {
    Write-Host "❌ CMakeLists.txt not found!" -ForegroundColor Red
    exit 1
}

# Step 4: Verify all source files exist
Write-Host ""
Write-Host "📁 Step 4: Verifying source files..." -ForegroundColor Yellow
$sourceFiles = @(
    "app\src\main\jni\perf-net\src\perf_crypto_neon.cpp",
    "app\src\main\jni\perf-net\src\perf_tls_handshake.cpp",
    "app\src\main\jni\perf-net\src\perf_quic_handshake.cpp",
    "app\src\main\jni\perf-net\src\perf_tls_evasion.cpp",
    "app\src\main\jni\perf-net\src\perf_cert_verifier.cpp",
    "app\src\main\jni\perf-net\src\perf_tls_keylog.cpp"
)

$allExist = $true
foreach ($file in $sourceFiles) {
    if (Test-Path $file) {
        Write-Host "✅ $file" -ForegroundColor Green
    } else {
        Write-Host "❌ $file - MISSING!" -ForegroundColor Red
        $allExist = $false
    }
}

if (-not $allExist) {
    Write-Host "❌ Some source files are missing!" -ForegroundColor Red
    exit 1
}

# Step 5: Clean previous build artifacts
Write-Host ""
Write-Host "🧹 Step 5: Cleaning previous build artifacts..." -ForegroundColor Yellow
if (Test-Path "app\build") {
    Remove-Item -Recurse -Force "app\build"
    Write-Host "✅ Build directory cleaned" -ForegroundColor Green
} else {
    Write-Host "⚠️  No build directory to clean" -ForegroundColor Yellow
}

# Remove OpenSSL artifacts if they exist
if (Test-Path "app\src\main\jni\openssl") {
    Write-Host "⚠️  Old OpenSSL directory found, removing..." -ForegroundColor Yellow
    Remove-Item -Recurse -Force "app\src\main\jni\openssl"
    Write-Host "✅ OpenSSL artifacts removed" -ForegroundColor Green
}

# Step 6: Verify Gradle configuration
Write-Host ""
Write-Host "⚙️  Step 6: Verifying Gradle configuration..." -ForegroundColor Yellow
$gradleContent = Get-Content "app\build.gradle" -Raw
if ($gradleContent -match "BORINGSSL") {
    Write-Host "✅ BoringSSL referenced in build.gradle" -ForegroundColor Green
} else {
    Write-Host "⚠️  BoringSSL not found in build.gradle (may be OK if using CMake)" -ForegroundColor Yellow
}

if ($gradleContent -match "cmake") {
    Write-Host "✅ CMake configuration found in build.gradle" -ForegroundColor Green
} else {
    Write-Host "❌ CMake configuration missing in build.gradle!" -ForegroundColor Red
    exit 1
}

# Step 7: Verify Android.mk has OpenSSL disabled
Write-Host ""
Write-Host "🔧 Step 7: Verifying Android.mk..." -ForegroundColor Yellow
$androidMkContent = Get-Content "app\src\main\jni\perf-net\Android.mk" -Raw
if ($androidMkContent -match "DISABLE_OPENSSL") {
    Write-Host "✅ OpenSSL disabled in Android.mk" -ForegroundColor Green
} else {
    Write-Host "⚠️  DISABLE_OPENSSL flag not found in Android.mk" -ForegroundColor Yellow
}

# Step 8: Check GitHub Actions workflows
Write-Host ""
Write-Host "🔄 Step 8: Verifying GitHub Actions workflows..." -ForegroundColor Yellow
$buildYmlContent = Get-Content ".github\workflows\build.yml" -Raw
if ($buildYmlContent -match "BoringSSL") {
    Write-Host "✅ BoringSSL build step found in build.yml" -ForegroundColor Green
} else {
    Write-Host "❌ BoringSSL build step missing in build.yml!" -ForegroundColor Red
    exit 1
}

$autoReleaseYmlContent = Get-Content ".github\workflows\auto-release.yml" -Raw
if ($autoReleaseYmlContent -match "BoringSSL") {
    Write-Host "✅ BoringSSL build step found in auto-release.yml" -ForegroundColor Green
} else {
    Write-Host "❌ BoringSSL build step missing in auto-release.yml!" -ForegroundColor Red
    exit 1
}

# Step 9: Summary
Write-Host ""
Write-Host "==============================================" -ForegroundColor Cyan
Write-Host "📊 Summary" -ForegroundColor Cyan
Write-Host "==============================================" -ForegroundColor Cyan
Write-Host "✅ All verification steps passed!" -ForegroundColor Green
Write-Host ""
Write-Host "Next steps:"
Write-Host "1. Build the project: .\gradlew.bat clean assembleDebug"
Write-Host "2. Test on device: adb install app\build\outputs\apk\debug\simplexray-arm64-v8a-debug.apk"
Write-Host "3. Check logs: adb logcat | Select-String -Pattern 'PerfCrypto|BoringSSL'"
Write-Host ""
Write-Host "📚 Documentation:"
Write-Host "  - Quick Start: QUICK_START_BORINGSSL.md"
Write-Host "  - Detailed Steps: NEXT_STEPS_BORINGSSL.md"
Write-Host "  - Integration Summary: INTEGRATION_SUMMARY.md"
Write-Host ""

# Step 10: Check if gradlew is executable (Windows doesn't need this, but check file exists)
Write-Host "🔨 Step 10: Checking Gradle wrapper..." -ForegroundColor Yellow
if (Test-Path "gradlew.bat") {
    Write-Host "✅ Gradle wrapper found" -ForegroundColor Green
} else {
    Write-Host "⚠️  gradlew.bat not found" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "🎉 All steps applied successfully!" -ForegroundColor Green
Write-Host ""
Write-Host "Ready to build! Run: .\gradlew.bat clean assembleDebug" -ForegroundColor Cyan

