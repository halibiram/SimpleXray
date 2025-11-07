# Script to download necessary files from last successful build for local debug
# Downloads: Xray libraries, Hysteria2 binaries, geoip.dat, geosite.dat

$ErrorActionPreference = "Continue"

$SCRIPT_DIR = Split-Path -Parent $MyInvocation.MyCommand.Path
$PROJECT_ROOT = Split-Path -Parent $SCRIPT_DIR

Set-Location $PROJECT_ROOT

Write-Host "===============================================" -ForegroundColor Cyan
Write-Host "  SimpleXray Debug Files Downloader" -ForegroundColor Cyan
Write-Host "===============================================" -ForegroundColor Cyan
Write-Host ""

# Check GitHub CLI
$ghCheck = Get-Command gh -ErrorAction SilentlyContinue
if (-not $ghCheck) {
    Write-Host "[HATA] GitHub CLI (gh) bulunamadi" -ForegroundColor Red
    Write-Host "Yukleyin: https://cli.github.com/" -ForegroundColor Yellow
    exit 1
}

# Check authentication
$authResult = gh auth status 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "[HATA] GitHub CLI authentication gerekli" -ForegroundColor Red
    Write-Host "Calistirin: gh auth login" -ForegroundColor Yellow
    exit 1
}

# Get repository info
try {
    $repoJson = gh repo view --json owner,name 2>&1 | Out-String
    if ($LASTEXITCODE -eq 0 -and $repoJson) {
        $repoObj = $repoJson | ConvertFrom-Json
        $REPO = "$($repoObj.owner.login)/$($repoObj.name)"
    } else {
        $REPO = $null
    }
} catch {
    $REPO = $null
}

if (-not $REPO) {
    Write-Host "[HATA] Repository bilgisi alinamadi" -ForegroundColor Red
    exit 1
}

Write-Host "Repository: $REPO" -ForegroundColor Blue
Write-Host ""

# Read versions
if (-not (Test-Path "version.properties")) {
    Write-Host "[HATA] version.properties bulunamadi" -ForegroundColor Red
    exit 1
}

$versionContent = Get-Content "version.properties"
$GEOIP_VERSION = ($versionContent | Select-String "GEOIP_VERSION").ToString().Split("=")[1].Trim()
$GEOSITE_VERSION = ($versionContent | Select-String "GEOSITE_VERSION").ToString().Split("=")[1].Trim()

Write-Host "Versions:" -ForegroundColor Blue
Write-Host "   GEOIP_VERSION: $GEOIP_VERSION"
Write-Host "   GEOSITE_VERSION: $GEOSITE_VERSION"
Write-Host ""

# Create directories
$null = New-Item -ItemType Directory -Force -Path "app/src/main/jniLibs/arm64-v8a"
$null = New-Item -ItemType Directory -Force -Path "app/src/main/jniLibs/armeabi-v7a"
$null = New-Item -ItemType Directory -Force -Path "app/src/main/jniLibs/x86_64"
$null = New-Item -ItemType Directory -Force -Path "app/src/main/assets"
$null = New-Item -ItemType Directory -Force -Path ".debug-artifacts"

# Step 1: Download Xray libraries from last successful build
Write-Host "[1/4] Xray libraries indiriliyor..." -ForegroundColor Yellow
$XRAY_WORKFLOW = "build-xray-boringssl.yml"

# Find last successful run
$XRAY_RUN_JSON = gh run list --workflow="$XRAY_WORKFLOW" --status=success --limit=1 --json databaseId 2>&1
if ($LASTEXITCODE -eq 0 -and $XRAY_RUN_JSON) {
    $XRAY_RUN = ($XRAY_RUN_JSON | ConvertFrom-Json).databaseId
} else {
    $XRAY_RUN = $null
}

if (-not $XRAY_RUN) {
    Write-Host "[UYARI] Basarili Xray build bulunamadi, atlaniyor..." -ForegroundColor Yellow
} else {
    Write-Host "   Run ID: $XRAY_RUN" -ForegroundColor Blue
    
    # Download artifacts
    gh run download "$XRAY_RUN" --pattern "xray-*" --dir ".debug-artifacts/xray-libs" *>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[UYARI] Xray artifacts indirilemedi, atlaniyor..." -ForegroundColor Yellow
    }
    
    # Copy libraries to jniLibs
    if (Test-Path ".debug-artifacts/xray-libs") {
        $abis = @("arm64-v8a", "armeabi-v7a", "x86_64")
        foreach ($abi in $abis) {
            $sourcePath = ".debug-artifacts/xray-libs/xray-$abi/libxray.so"
            if (Test-Path $sourcePath) {
                Copy-Item $sourcePath "app/src/main/jniLibs/$abi/libxray.so" -Force
                Write-Host "   [OK] Xray library copied for $abi" -ForegroundColor Green
            }
        }
    }
}

Write-Host ""

# Step 2: Download Hysteria2 binaries from last successful build
Write-Host "[2/4] Hysteria2 binaries indiriliyor..." -ForegroundColor Yellow
$HYSTERIA2_WORKFLOW = "build-hysteria2.yml"

# Find last successful run
$HYSTERIA2_RUN_JSON = gh run list --workflow="$HYSTERIA2_WORKFLOW" --status=success --limit=1 --json databaseId 2>&1
if ($LASTEXITCODE -eq 0 -and $HYSTERIA2_RUN_JSON) {
    $HYSTERIA2_RUN = ($HYSTERIA2_RUN_JSON | ConvertFrom-Json).databaseId
} else {
    $HYSTERIA2_RUN = $null
}

if (-not $HYSTERIA2_RUN) {
    Write-Host "[UYARI] Basarili Hysteria2 build bulunamadi, atlaniyor..." -ForegroundColor Yellow
} else {
    Write-Host "   Run ID: $HYSTERIA2_RUN" -ForegroundColor Blue
    
    # Download artifacts
    gh run download "$HYSTERIA2_RUN" --pattern "hysteria2-*" --dir ".debug-artifacts/hysteria2-libs" *>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[UYARI] Hysteria2 artifacts indirilemedi, atlaniyor..." -ForegroundColor Yellow
    }
    
    # Copy libraries to jniLibs
    if (Test-Path ".debug-artifacts/hysteria2-libs") {
        $abis = @("arm64-v8a", "armeabi-v7a", "x86_64")
        foreach ($abi in $abis) {
            $sourcePath = ".debug-artifacts/hysteria2-libs/hysteria2-$abi/libhysteria2.so"
            if (Test-Path $sourcePath) {
                Copy-Item $sourcePath "app/src/main/jniLibs/$abi/libhysteria2.so" -Force
                Write-Host "   [OK] Hysteria2 binary copied for $abi" -ForegroundColor Green
            }
        }
    }
}

Write-Host ""

# Step 3: Download geoip.dat and geosite.dat
Write-Host "[3/4] GeoIP ve GeoSite dosyalari indiriliyor..." -ForegroundColor Yellow

if ($GEOIP_VERSION) {
    Write-Host "   Downloading geoip.dat (version: $GEOIP_VERSION)..." -ForegroundColor Blue
    try {
        $geoipUrl = "https://github.com/lhear/v2ray-rules-dat/releases/download/$GEOIP_VERSION/geoip.dat"
        Invoke-WebRequest -Uri $geoipUrl -OutFile "app/src/main/assets/geoip.dat" -UseBasicParsing -ErrorAction Stop
        Write-Host "   [OK] geoip.dat downloaded" -ForegroundColor Green
    } catch {
        Write-Host "   [UYARI] geoip.dat download failed: $_" -ForegroundColor Yellow
    }
} else {
    Write-Host "   [UYARI] GEOIP_VERSION not set" -ForegroundColor Yellow
}

if ($GEOSITE_VERSION) {
    Write-Host "   Downloading geosite.dat (version: $GEOSITE_VERSION)..." -ForegroundColor Blue
    try {
        $geositeUrl = "https://github.com/lhear/v2ray-rules-dat/releases/download/$GEOSITE_VERSION/geosite.dat"
        Invoke-WebRequest -Uri $geositeUrl -OutFile "app/src/main/assets/geosite.dat" -UseBasicParsing -ErrorAction Stop
        Write-Host "   [OK] geosite.dat downloaded" -ForegroundColor Green
    } catch {
        Write-Host "   [UYARI] geosite.dat download failed: $_" -ForegroundColor Yellow
    }
} else {
    Write-Host "   [UYARI] GEOSITE_VERSION not set" -ForegroundColor Yellow
}

Write-Host ""

# Step 4: Verify files
Write-Host "[4/4] Dosyalar dogrulaniyor..." -ForegroundColor Yellow

$VERIFIED = $true

# Check Xray libraries
$abis = @("arm64-v8a", "armeabi-v7a", "x86_64")
foreach ($abi in $abis) {
    $libPath = "app/src/main/jniLibs/$abi/libxray.so"
    if (Test-Path $libPath) {
        $size = (Get-Item $libPath).Length
        Write-Host "   [OK] libxray.so ($abi): $size bytes" -ForegroundColor Green
    } else {
        Write-Host "   [UYARI] libxray.so ($abi): not found" -ForegroundColor Yellow
    }
}

# Check Hysteria2 binaries
foreach ($abi in $abis) {
    $libPath = "app/src/main/jniLibs/$abi/libhysteria2.so"
    if (Test-Path $libPath) {
        $size = (Get-Item $libPath).Length
        Write-Host "   [OK] libhysteria2.so ($abi): $size bytes" -ForegroundColor Green
    } else {
        Write-Host "   [UYARI] libhysteria2.so ($abi): not found" -ForegroundColor Yellow
    }
}

# Check geoip.dat
$geoipPath = "app/src/main/assets/geoip.dat"
if (Test-Path $geoipPath) {
    $size = (Get-Item $geoipPath).Length
    Write-Host "   [OK] geoip.dat: $size bytes" -ForegroundColor Green
} else {
    Write-Host "   [UYARI] geoip.dat: not found" -ForegroundColor Yellow
    $VERIFIED = $false
}

# Check geosite.dat
$geositePath = "app/src/main/assets/geosite.dat"
if (Test-Path $geositePath) {
    $size = (Get-Item $geositePath).Length
    Write-Host "   [OK] geosite.dat: $size bytes" -ForegroundColor Green
} else {
    Write-Host "   [UYARI] geosite.dat: not found" -ForegroundColor Yellow
    $VERIFIED = $false
}

Write-Host ""

# Summary
if ($VERIFIED) {
    Write-Host "===============================================" -ForegroundColor Green
    Write-Host "  Debug dosyalari basariyla indirildi!" -ForegroundColor Green
    Write-Host "===============================================" -ForegroundColor Green
    Write-Host ""
    Write-Host "Artik yerel debug build yapabilirsiniz:" -ForegroundColor Cyan
    Write-Host "  .\gradlew.bat assembleDebug" -ForegroundColor Blue
    Write-Host ""
} else {
    Write-Host "UYARI: Bazi dosyalar eksik, ancak mevcut dosyalarla build yapabilirsiniz" -ForegroundColor Yellow
}
