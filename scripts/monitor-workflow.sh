#!/bin/bash
# GitHub Actions workflow'larını sürekli izlemek için script

set -e

# Renkler
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Parametreler
INTERVAL=${1:-30}  # Varsayılan 30 saniye
WORKFLOW_NAME=${2:-""}

echo -e "${CYAN}=== GitHub Actions Workflow Monitor ===${NC}"
echo -e "${BLUE}Check interval:${NC} $INTERVAL seconds"
if [ -n "$WORKFLOW_NAME" ]; then
    echo -e "${BLUE}Workflow filter:${NC} $WORKFLOW_NAME"
fi
echo ""

# Son kontrol edilen run ID
LAST_RUN_ID=""

while true; do
    # Tarih/saat
    echo -e "${CYAN}[$(date '+%Y-%m-%d %H:%M:%S')]${NC} Checking workflow runs..."
    
    # Son run'ları al
    if [ -n "$WORKFLOW_NAME" ]; then
        RUNS=$(gh run list --workflow="$WORKFLOW_NAME" --limit 5 --json databaseId,status,conclusion,name,createdAt 2>/dev/null)
    else
        RUNS=$(gh run list --limit 5 --json databaseId,status,conclusion,name,createdAt 2>/dev/null)
    fi
    
    if [ -z "$RUNS" ] || [ "$RUNS" == "[]" ]; then
        echo -e "${YELLOW}⚠️  No workflow runs found${NC}"
        sleep $INTERVAL
        continue
    fi
    
    # İlk run'u kontrol et
    CURRENT_RUN_ID=$(echo "$RUNS" | jq -r '.[0].databaseId')
    CURRENT_STATUS=$(echo "$RUNS" | jq -r '.[0].status')
    CURRENT_CONCLUSION=$(echo "$RUNS" | jq -r '.[0].conclusion // "in_progress"')
    CURRENT_NAME=$(echo "$RUNS" | jq -r '.[0].name')
    
    # Yeni run varsa bildir
    if [ "$CURRENT_RUN_ID" != "$LAST_RUN_ID" ]; then
        echo -e "${GREEN}🆕 New workflow run detected:${NC} $CURRENT_RUN_ID"
        echo -e "   Name: $CURRENT_NAME"
        echo -e "   Status: $CURRENT_STATUS"
        LAST_RUN_ID=$CURRENT_RUN_ID
    fi
    
    # Status değişikliğini kontrol et
    case "$CURRENT_STATUS" in
        "completed")
            case "$CURRENT_CONCLUSION" in
                "success")
                    echo -e "${GREEN}✅ Workflow completed successfully:${NC} $CURRENT_NAME ($CURRENT_RUN_ID)"
                    ;;
                "failure")
                    echo -e "${RED}❌ Workflow failed:${NC} $CURRENT_NAME ($CURRENT_RUN_ID)"
                    echo -e "${YELLOW}   View details:${NC} gh run view $CURRENT_RUN_ID"
                    echo -e "${YELLOW}   View logs:${NC} gh run view $CURRENT_RUN_ID --log-failed"
                    ;;
                "cancelled")
                    echo -e "${YELLOW}⚠️  Workflow cancelled:${NC} $CURRENT_NAME ($CURRENT_RUN_ID)"
                    ;;
                *)
                    echo -e "${YELLOW}ℹ️  Workflow completed:${NC} $CURRENT_NAME ($CURRENT_RUN_ID) - $CURRENT_CONCLUSION"
                    ;;
            esac
            ;;
        "in_progress")
            echo -e "${BLUE}🔄 Workflow in progress:${NC} $CURRENT_NAME ($CURRENT_RUN_ID)"
            ;;
        "queued")
            echo -e "${YELLOW}⏳ Workflow queued:${NC} $CURRENT_NAME ($CURRENT_RUN_ID)"
            ;;
    esac
    
    # Tüm run'ları listele
    echo ""
    echo -e "${CYAN}Recent runs:${NC}"
    echo "$RUNS" | jq -r '.[] | "  \(.databaseId) | \(.status) | \(.conclusion // "in_progress") | \(.name)"'
    
    echo ""
    echo -e "${BLUE}Sleeping for $INTERVAL seconds...${NC}"
    echo "----------------------------------------"
    sleep $INTERVAL
done









