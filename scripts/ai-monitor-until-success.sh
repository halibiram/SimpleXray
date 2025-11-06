#!/bin/bash
# AI Monitor - Başarılı build alana kadar devam eder

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
MAGENTA='\033[0;35m'
NC='\033[0m'
BOLD='\033[1m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
AI_FIXER="$SCRIPT_DIR/ai-build-fixer.sh"

echo -e "${BOLD}${CYAN}"
echo "╔════════════════════════════════════════════════════════════════╗"
echo "║     🤖 AI BUILD FIXER - Başarılı Build Alana Kadar 🤖        ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo -e "${NC}\n"

WORKFLOW_NAME="${1:-Build Xray-core with BoringSSL}"
MAX_ITERATIONS=20
ITERATION=0

while [ $ITERATION -lt $MAX_ITERATIONS ]; do
    ITERATION=$((ITERATION + 1))
    
    echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${BOLD}[$(date '+%H:%M:%S')]${NC} ${CYAN}İterasyon #${ITERATION}${NC}\n"
    
    # En son run'u al
    LATEST_RUN=$(gh run list --workflow="$WORKFLOW_NAME" --limit 1 --json databaseId,status,conclusion,createdAt --jq '.[0] | "\(.databaseId)|\(.status)|\(.conclusion // "in_progress")|\(.createdAt)"' 2>/dev/null || echo "")
    
    if [ -z "$LATEST_RUN" ] || [ "$LATEST_RUN" = "null|null|null" ]; then
        echo -e "${YELLOW}⚠️  Run bulunamadı, bekleniyor...${NC}\n"
        sleep 30
        continue
    fi
    
    RUN_ID=$(echo "$LATEST_RUN" | cut -d'|' -f1)
    STATUS=$(echo "$LATEST_RUN" | cut -d'|' -f2)
    CONCLUSION=$(echo "$LATEST_RUN" | cut -d'|' -f3)
    CREATED=$(echo "$LATEST_RUN" | cut -d'|' -f4)
    
    echo -e "${BLUE}Run ID:${NC} $RUN_ID"
    echo -e "${BLUE}Status:${NC} $STATUS"
    echo -e "${BLUE}Conclusion:${NC} $CONCLUSION"
    echo -e "${DIM}Created:${NC} $CREATED\n"
    
    case "$CONCLUSION" in
        "success")
            echo -e "\n${GREEN}${BOLD}"
            echo "╔════════════════════════════════════════════════════════════════╗"
            echo "║          ✅✅✅ BAŞARILI BUILD! ✅✅✅                        ║"
            echo "╚════════════════════════════════════════════════════════════════╝"
            echo -e "${NC}\n"
            
            # Job detaylarını göster
            gh run view "$RUN_ID" --json jobs --jq '.jobs[] | {name: .name, status: .conclusion}' 2>/dev/null | head -20
            
            echo -e "\n${GREEN}✅ Build başarılı! İşlem tamamlandı.${NC}"
            
            # Öğrenme veritabanını güncelle
            echo -e "${CYAN}[AI-MVC] Öğrenme veritabanı güncelleniyor...${NC}"
            
            exit 0
            ;;
        "failure")
            echo -e "\n${RED}${BOLD}❌ BUILD BAŞARISIZ!${NC}\n"
            
            # AI Fixer'ı çalıştır
            echo -e "${MAGENTA}[AI-MVC] AI Fixer aktif ediliyor...${NC}\n"
            
            if [ -f "$AI_FIXER" ]; then
                # AI Fixer'ı bu run için çalıştır (tek seferlik)
                timeout 300 bash "$AI_FIXER" "$RUN_ID" || {
                    echo -e "${YELLOW}⚠️  AI Fixer timeout veya hata, manuel kontrol gerekebilir${NC}"
                }
            else
                echo -e "${YELLOW}⚠️  AI Fixer script bulunamadı: $AI_FIXER${NC}"
            fi
            
            echo -e "\n${CYAN}[AI-MVC] Yeni build bekleniyor...${NC}\n"
            sleep 45
            ;;
        "in_progress"|"queued")
            echo -e "${YELLOW}⏳ Workflow devam ediyor...${NC}"
            
            # İlerleme bilgisi
            gh run view "$RUN_ID" --json jobs --jq '.jobs[] | select(.status == "in_progress") | .name' 2>/dev/null | head -3 | while read job; do
                echo -e "  ${DIM}→ $job${NC}"
            done
            
            sleep 30
            ;;
        "cancelled")
            echo -e "${YELLOW}⚠️  Workflow iptal edildi${NC}"
            sleep 15
            ;;
        *)
            echo -e "${YELLOW}ℹ️  Durum: $CONCLUSION${NC}"
            sleep 30
            ;;
    esac
done

echo -e "\n${RED}❌ Maksimum iterasyon sayısına ulaşıldı (${MAX_ITERATIONS})${NC}"
echo -e "${YELLOW}Manuel kontrol gerekebilir!${NC}"
exit 1

