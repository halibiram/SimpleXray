#!/bin/bash
# Hızlı status kontrolü - Hyper hızlı

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

LATEST=$(gh run list --limit 1 --json databaseId,status,conclusion,createdAt --jq '.[0] | "\(.databaseId)|\(.status)|\(.conclusion // "in_progress")|\(.createdAt)"' 2>/dev/null)

if [ -z "$LATEST" ]; then
    echo -e "${YELLOW}⚠️  Run bulunamadı${NC}"
    exit 1
fi

RUN_ID=$(echo "$LATEST" | cut -d'|' -f1)
STATUS=$(echo "$LATEST" | cut -d'|' -f2)
CONCLUSION=$(echo "$LATEST" | cut -d'|' -f3)

case "$CONCLUSION" in
    "success") echo -e "${GREEN}✅ BAŞARILI${NC} - Run: $RUN_ID" ;;
    "failure") echo -e "${RED}❌ BAŞARISIZ${NC} - Run: $RUN_ID" ;;
    *) echo -e "${BLUE}🔄 $STATUS${NC} - Run: $RUN_ID" ;;
esac



