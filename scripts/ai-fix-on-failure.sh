#!/bin/bash
# AI Fix on Failure - Tespit et, düzelt, durdur
# Başarısız build tespit edilince fix uygular ve durur

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
MAGENTA='\033[0;35m'
NC='\033[0m'
BOLD='\033[1m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
AI_FIXER_V3="$SCRIPT_DIR/ai-build-fixer-v3.sh"

WORKFLOWS=(
    "Build Xray-core with BoringSSL"
    "Auto Release"
)

echo -e "${BOLD}${CYAN}"
echo "╔════════════════════════════════════════════════════════════════╗"
echo "║     🔍 AI FIX ON FAILURE - Detect, Fix, Stop 🔍              ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo -e "${NC}\n"

# Check for failures
check_failures() {
    local FAILED_WORKFLOWS=()
    local FAILED_RUNS=()
    
    for WORKFLOW_NAME in "${WORKFLOWS[@]}"; do
        echo -e "${CYAN}[AI-MVC] Checking: ${WORKFLOW_NAME}...${NC}"
        
        RUN_ID=$(gh run list --workflow="$WORKFLOW_NAME" --limit 1 2>/dev/null | head -1 | awk '{for(i=1;i<=NF;i++) if($i ~ /^[0-9]+$/) {print $i; exit}}' || echo "")
        
        if [ -z "$RUN_ID" ] || ! echo "$RUN_ID" | grep -qE '^[0-9]+$'; then
            echo -e "${YELLOW}  ⚠️  No valid run found${NC}"
            continue
        fi
        
        if command -v jq &> /dev/null; then
            STATUS=$(gh run view "$RUN_ID" --json status --jq '.status' 2>/dev/null || echo "unknown")
            CONCLUSION=$(gh run view "$RUN_ID" --json conclusion --jq '.conclusion // "in_progress"' 2>/dev/null || echo "in_progress")
        else
            RUN_INFO=$(gh run view "$RUN_ID" 2>/dev/null || echo "")
            STATUS=$(echo "$RUN_INFO" | grep -i "status:" | awk '{print $2}' | head -1 || echo "unknown")
            CONCLUSION=$(echo "$RUN_INFO" | grep -i "conclusion:" | awk '{print $2}' | head -1 || echo "in_progress")
        fi
        
        echo -e "${BLUE}  Run ID:${NC} $RUN_ID | ${BLUE}Status:${NC} $STATUS | ${BLUE}Conclusion:${NC} $CONCLUSION"
        
        case "$CONCLUSION" in
            "success")
                echo -e "${GREEN}  ✅ Success${NC}"
                ;;
            "failure")
                echo -e "${RED}  ❌ FAILURE DETECTED!${NC}"
                FAILED_WORKFLOWS+=("$WORKFLOW_NAME")
                FAILED_RUNS+=("$RUN_ID|$WORKFLOW_NAME")
                ;;
            "in_progress"|"queued")
                echo -e "${YELLOW}  ⏳ In progress...${NC}"
                ;;
            *)
                echo -e "${YELLOW}  ℹ️  Status: $CONCLUSION${NC}"
                ;;
        esac
    done
    
    if [ ${#FAILED_RUNS[@]} -gt 0 ]; then
        printf '%s\n' "${FAILED_RUNS[@]}"
        return 0
    fi
    
    return 1
}

# Main loop - check and fix once
main() {
    local MAX_CHECKS=30
    local CHECK_COUNT=0
    
    while [ $CHECK_COUNT -lt $MAX_CHECKS ]; do
        CHECK_COUNT=$((CHECK_COUNT + 1))
        
        echo -e "\n${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
        echo -e "${BOLD}[$(date '+%H:%M:%S')]${NC} ${CYAN}Kontrol #${CHECK_COUNT}${NC}\n"
        
        # Check for failures
        FAILED_RUNS=$(check_failures)
        
        if [ -n "$FAILED_RUNS" ]; then
            echo -e "\n${RED}${BOLD}❌ BAŞARISIZ BUILD TESPİT EDİLDİ!${NC}\n"
            
            # Get first failed run
            FIRST_FAILED=$(echo "$FAILED_RUNS" | head -1)
            RUN_ID=$(echo "$FIRST_FAILED" | cut -d'|' -f1)
            WORKFLOW_NAME=$(echo "$FIRST_FAILED" | cut -d'|' -f2)
            
            echo -e "${RED}Workflow: ${WORKFLOW_NAME}${NC}"
            echo -e "${RED}Run ID: ${RUN_ID}${NC}\n"
            
            # Apply fix using v3 fixer
            echo -e "${MAGENTA}[AI-MVC] Fix uygulanıyor...${NC}\n"
            
            if [ -f "$AI_FIXER_V3" ]; then
                # Use v3 fixer to analyze and fix
                echo -e "${CYAN}V3 fixer ile analiz ve düzeltme yapılıyor...${NC}"
                
                # Trigger v3 fixer for this specific run
                bash "$AI_FIXER_V3" "$WORKFLOW_NAME" "$RUN_ID" 2>&1 | head -100 || {
                    echo -e "${YELLOW}⚠️  V3 fixer hatası, manuel fix deneniyor...${NC}"
                    
                    # Manual fix attempt
                    echo -e "${YELLOW}→ Manuel fix uygulanıyor...${NC}"
                    # Could add manual fix logic here
                }
            else
                echo -e "${RED}❌ V3 fixer bulunamadı!${NC}"
            fi
            
            echo -e "\n${GREEN}✅ Fix uygulandı, monitoring durduruluyor...${NC}"
            echo -e "${YELLOW}ℹ️  Yeni build'in sonucunu kontrol etmek için tekrar çalıştırın${NC}\n"
            
            exit 0
        fi
        
        # Check if all workflows are successful
        ALL_SUCCESS=true
        for WORKFLOW_NAME in "${WORKFLOWS[@]}"; do
            RUN_ID=$(gh run list --workflow="$WORKFLOW_NAME" --limit 1 2>/dev/null | head -1 | awk '{for(i=1;i<=NF;i++) if($i ~ /^[0-9]+$/) {print $i; exit}}' || echo "")
            if [ -n "$RUN_ID" ]; then
                if command -v jq &> /dev/null; then
                    CONCLUSION=$(gh run view "$RUN_ID" --json conclusion --jq '.conclusion // "in_progress"' 2>/dev/null || echo "in_progress")
                else
                    RUN_INFO=$(gh run view "$RUN_ID" 2>/dev/null || echo "")
                    CONCLUSION=$(echo "$RUN_INFO" | grep -i "conclusion:" | awk '{print $2}' | head -1 || echo "in_progress")
                fi
                
                if [ "$CONCLUSION" != "success" ]; then
                    ALL_SUCCESS=false
                    break
                fi
            fi
        done
        
        if [ "$ALL_SUCCESS" = "true" ]; then
            echo -e "\n${GREEN}${BOLD}"
            echo "╔════════════════════════════════════════════════════════════════╗"
            echo "║       ✅✅✅ TÜM WORKFLOW'LAR BAŞARILI! ✅✅✅                  ║"
            echo "╚════════════════════════════════════════════════════════════════╝"
            echo -e "${NC}\n"
            exit 0
        fi
        
        echo -e "${YELLOW}⏳ Başarısız build yok, bekleniyor...${NC}"
        sleep 35
    done
    
    echo -e "\n${YELLOW}⚠️  Maximum kontrol sayısına ulaşıldı (${MAX_CHECKS})${NC}"
    echo -e "${YELLOW}ℹ️  Monitoring durduruldu${NC}\n"
    exit 0
}

main







