#!/bin/bash

# Script to download necessary files from last successful build for local debug
# Downloads: Xray libraries, Hysteria2 binaries, geoip.dat, geosite.dat

set -euo pipefail

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color
BOLD='\033[1m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$PROJECT_ROOT"

echo -e "${BOLD}${CYAN}╔═══════════════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}${CYAN}║  SimpleXray Debug Files Downloader                   ║${NC}"
echo -e "${BOLD}${CYAN}╚═══════════════════════════════════════════════════════╝${NC}"
echo ""

# Check GitHub CLI
if ! command -v gh &> /dev/null; then
    echo -e "${RED}❌ GitHub CLI (gh) bulunamadı${NC}"
    echo -e "${YELLOW}Yükleyin: https://cli.github.com/${NC}"
    exit 1
fi

# Check authentication
if ! gh auth status &> /dev/null; then
    echo -e "${RED}❌ GitHub CLI authentication gerekli${NC}"
    echo -e "${YELLOW}Çalıştırın: gh auth login${NC}"
    exit 1
fi

# Get repository info
REPO=$(gh repo view --json owner,name -q '.owner.login + "/" + .name' 2>/dev/null || echo "")
if [ -z "$REPO" ]; then
    echo -e "${RED}❌ Repository bilgisi alınamadı${NC}"
    exit 1
fi

echo -e "${BLUE}📦 Repository: $REPO${NC}"
echo ""

# Read versions
if [ ! -f "version.properties" ]; then
    echo -e "${RED}❌ version.properties bulunamadı${NC}"
    exit 1
fi

GEOIP_VERSION=$(grep 'GEOIP_VERSION' version.properties | cut -d '=' -f 2)
GEOSITE_VERSION=$(grep 'GEOSITE_VERSION' version.properties | cut -d '=' -f 2)

echo -e "${BLUE}📋 Versions:${NC}"
echo -e "   GEOIP_VERSION: $GEOIP_VERSION"
echo -e "   GEOSITE_VERSION: $GEOSITE_VERSION"
echo ""

# Create directories
mkdir -p app/src/main/jniLibs/arm64-v8a
mkdir -p app/src/main/jniLibs/armeabi-v7a
mkdir -p app/src/main/jniLibs/x86_64
mkdir -p app/src/main/assets
mkdir -p .debug-artifacts

# Step 1: Download Xray libraries from last successful build
echo -e "${YELLOW}[1/4] Xray libraries indiriliyor...${NC}"
XRAY_WORKFLOW="build-xray-boringssl.yml"

# Find last successful run
XRAY_RUN=$(gh run list --workflow="$XRAY_WORKFLOW" --status=success --limit=1 --json databaseId -q '.[0].databaseId' 2>/dev/null || echo "")

if [ -z "$XRAY_RUN" ]; then
    echo -e "${YELLOW}⚠️  Başarılı Xray build bulunamadı, atlanıyor...${NC}"
else
    echo -e "${BLUE}   Run ID: $XRAY_RUN${NC}"
    
    # Download artifacts
    gh run download "$XRAY_RUN" --pattern "xray-*" --dir ".debug-artifacts/xray-libs" 2>/dev/null || {
        echo -e "${YELLOW}⚠️  Xray artifacts indirilemedi, atlanıyor...${NC}"
    }
    
    # Copy libraries to jniLibs
    if [ -d ".debug-artifacts/xray-libs" ]; then
        for abi in arm64-v8a armeabi-v7a x86_64; do
            if [ -f ".debug-artifacts/xray-libs/xray-$abi/libxray.so" ]; then
                cp ".debug-artifacts/xray-libs/xray-$abi/libxray.so" "app/src/main/jniLibs/$abi/libxray.so"
                echo -e "${GREEN}   ✅ Xray library copied for $abi${NC}"
            fi
        done
    fi
fi

echo ""

# Step 2: Download Hysteria2 binaries from last successful build
echo -e "${YELLOW}[2/4] Hysteria2 binaries indiriliyor...${NC}"
HYSTERIA2_WORKFLOW="build-hysteria2.yml"

# Find last successful run
HYSTERIA2_RUN=$(gh run list --workflow="$HYSTERIA2_WORKFLOW" --status=success --limit=1 --json databaseId -q '.[0].databaseId' 2>/dev/null || echo "")

if [ -z "$HYSTERIA2_RUN" ]; then
    echo -e "${YELLOW}⚠️  Başarılı Hysteria2 build bulunamadı, atlanıyor...${NC}"
else
    echo -e "${BLUE}   Run ID: $HYSTERIA2_RUN${NC}"
    
    # Download artifacts
    gh run download "$HYSTERIA2_RUN" --pattern "hysteria2-*" --dir ".debug-artifacts/hysteria2-libs" 2>/dev/null || {
        echo -e "${YELLOW}⚠️  Hysteria2 artifacts indirilemedi, atlanıyor...${NC}"
    }
    
    # Copy libraries to jniLibs
    if [ -d ".debug-artifacts/hysteria2-libs" ]; then
        for abi in arm64-v8a armeabi-v7a x86_64; do
            if [ -f ".debug-artifacts/hysteria2-libs/hysteria2-$abi/libhysteria2.so" ]; then
                cp ".debug-artifacts/hysteria2-libs/hysteria2-$abi/libhysteria2.so" "app/src/main/jniLibs/$abi/libhysteria2.so"
                echo -e "${GREEN}   ✅ Hysteria2 binary copied for $abi${NC}"
            fi
        done
    fi
fi

echo ""

# Step 3: Download geoip.dat and geosite.dat
echo -e "${YELLOW}[3/4] GeoIP ve GeoSite dosyaları indiriliyor...${NC}"

if [ -n "$GEOIP_VERSION" ]; then
    echo -e "${BLUE}   Downloading geoip.dat (version: $GEOIP_VERSION)...${NC}"
    wget -q "https://github.com/lhear/v2ray-rules-dat/releases/download/$GEOIP_VERSION/geoip.dat" \
        -O "app/src/main/assets/geoip.dat" && \
        echo -e "${GREEN}   ✅ geoip.dat downloaded${NC}" || \
        echo -e "${YELLOW}   ⚠️  geoip.dat download failed${NC}"
else
    echo -e "${YELLOW}   ⚠️  GEOIP_VERSION not set${NC}"
fi

if [ -n "$GEOSITE_VERSION" ]; then
    echo -e "${BLUE}   Downloading geosite.dat (version: $GEOSITE_VERSION)...${NC}"
    wget -q "https://github.com/lhear/v2ray-rules-dat/releases/download/$GEOSITE_VERSION/geosite.dat" \
        -O "app/src/main/assets/geosite.dat" && \
        echo -e "${GREEN}   ✅ geosite.dat downloaded${NC}" || \
        echo -e "${YELLOW}   ⚠️  geosite.dat download failed${NC}"
else
    echo -e "${YELLOW}   ⚠️  GEOSITE_VERSION not set${NC}"
fi

echo ""

# Step 4: Verify files
echo -e "${YELLOW}[4/4] Dosyalar doğrulanıyor...${NC}"

VERIFIED=true

# Check Xray libraries
for abi in arm64-v8a armeabi-v7a x86_64; do
    if [ -f "app/src/main/jniLibs/$abi/libxray.so" ]; then
        SIZE=$(stat -f%z "app/src/main/jniLibs/$abi/libxray.so" 2>/dev/null || stat -c%s "app/src/main/jniLibs/$abi/libxray.so" 2>/dev/null || echo "0")
        echo -e "${GREEN}   ✅ libxray.so ($abi): ${SIZE} bytes${NC}"
    else
        echo -e "${YELLOW}   ⚠️  libxray.so ($abi): not found${NC}"
    fi
done

# Check Hysteria2 binaries
for abi in arm64-v8a armeabi-v7a x86_64; do
    if [ -f "app/src/main/jniLibs/$abi/libhysteria2.so" ]; then
        SIZE=$(stat -f%z "app/src/main/jniLibs/$abi/libhysteria2.so" 2>/dev/null || stat -c%s "app/src/main/jniLibs/$abi/libhysteria2.so" 2>/dev/null || echo "0")
        echo -e "${GREEN}   ✅ libhysteria2.so ($abi): ${SIZE} bytes${NC}"
    else
        echo -e "${YELLOW}   ⚠️  libhysteria2.so ($abi): not found${NC}"
    fi
done

# Check geoip.dat
if [ -f "app/src/main/assets/geoip.dat" ]; then
    SIZE=$(stat -f%z "app/src/main/assets/geoip.dat" 2>/dev/null || stat -c%s "app/src/main/assets/geoip.dat" 2>/dev/null || echo "0")
    echo -e "${GREEN}   ✅ geoip.dat: ${SIZE} bytes${NC}"
else
    echo -e "${YELLOW}   ⚠️  geoip.dat: not found${NC}"
    VERIFIED=false
fi

# Check geosite.dat
if [ -f "app/src/main/assets/geosite.dat" ]; then
    SIZE=$(stat -f%z "app/src/main/assets/geosite.dat" 2>/dev/null || stat -c%s "app/src/main/assets/geosite.dat" 2>/dev/null || echo "0")
    echo -e "${GREEN}   ✅ geosite.dat: ${SIZE} bytes${NC}"
else
    echo -e "${YELLOW}   ⚠️  geosite.dat: not found${NC}"
    VERIFIED=false
fi

echo ""

# Summary
if [ "$VERIFIED" = true ]; then
    echo -e "${BOLD}${GREEN}╔═══════════════════════════════════════════════════════╗${NC}"
    echo -e "${BOLD}${GREEN}║  ✅ Debug dosyaları başarıyla indirildi!             ║${NC}"
    echo -e "${BOLD}${GREEN}╚═══════════════════════════════════════════════════════╝${NC}"
    echo ""
    echo -e "${CYAN}Artık yerel debug build yapabilirsiniz:${NC}"
    echo -e "${BLUE}  ./gradlew assembleDebug${NC}"
    echo ""
else
    echo -e "${YELLOW}⚠️  Bazı dosyalar eksik, ancak mevcut dosyalarla build yapabilirsiniz${NC}"
fi

# Cleanup temporary artifacts directory (optional - comment out if you want to keep them)
# rm -rf .debug-artifacts

echo -e "${DIM}Not: İndirilen dosyalar .gitignore'da zaten tanımlı${NC}"



