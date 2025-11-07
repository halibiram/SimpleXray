#!/bin/bash
# GitHub Actions workflow run'larını takip etmek için script

set -e

# Renkler
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}=== GitHub Actions Workflow Monitor ===${NC}\n"

# GitHub CLI authentication kontrolü
if ! gh auth status &>/dev/null; then
    echo -e "${YELLOW}⚠️  GitHub CLI authentication gerekli${NC}"
    echo "Çalıştırın: gh auth login"
    exit 1
fi

# Repository bilgisi
REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null || echo "halibiram/SimpleXray")
echo -e "${GREEN}Repository:${NC} $REPO\n"

# Son workflow run'ları listele
echo -e "${BLUE}📋 Son Workflow Run'ları:${NC}\n"
gh run list --limit 10 --json databaseId,status,conclusion,name,headBranch,createdAt \
    --jq '.[] | "\(.databaseId) | \(.status) | \(.conclusion // "in_progress") | \(.name) | \(.headBranch)"' || {
    echo -e "${RED}❌ Workflow run'ları alınamadı${NC}"
    exit 1
}

echo -e "\n${BLUE}🔍 Hangi workflow'u detaylı takip etmek istersiniz?${NC}"
echo "1. Build Xray-core with BoringSSL"
echo "2. Auto Release"
echo "3. Tüm workflow'lar"
echo "4. Son çalışan workflow'u takip et"
read -p "Seçiminiz (1-4): " choice

case $choice in
    1)
        WORKFLOW="Build Xray-core with BoringSSL"
        ;;
    2)
        WORKFLOW="Auto Release"
        ;;
    3)
        WORKFLOW=""
        ;;
    4)
        echo -e "\n${GREEN}🔄 Son workflow run'u takip ediliyor...${NC}"
        gh run watch
        exit 0
        ;;
    *)
        echo -e "${RED}Geçersiz seçim${NC}"
        exit 1
        ;;
esac

if [ -n "$WORKFLOW" ]; then
    echo -e "\n${BLUE}📊 Workflow: $WORKFLOW${NC}\n"
    gh run list --workflow="$WORKFLOW" --limit 5
    echo -e "\n${YELLOW}Son run'un detaylarını görmek için ID'yi girin (veya Enter'a basın):${NC}"
    read run_id
    
    if [ -n "$run_id" ]; then
        echo -e "\n${GREEN}📝 Run Detayları:${NC}\n"
        gh run view "$run_id" --log || gh run view "$run_id"
    fi
else
    echo -e "\n${BLUE}📊 Tüm Workflow'lar:${NC}\n"
    gh workflow list
fi

echo -e "\n${GREEN}✅ Tamamlandı${NC}"






