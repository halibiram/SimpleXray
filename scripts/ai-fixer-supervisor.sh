#!/bin/bash
# AI Fixer Supervisor - Sürekli çalışan supervisor script
# Script durursa otomatik olarak yeniden başlatır

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
FIXER_SCRIPT="$SCRIPT_DIR/ai-build-fixer-v3.1.sh"
LOG_FILE="/tmp/ai-fixer-supervisor.log"
PID_FILE="/tmp/ai-fixer-supervisor.pid"
FIXER_LOG="/tmp/ai-fixer-v3.1.log"

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'
BOLD='\033[1m'

# Cleanup on exit
cleanup() {
    echo -e "${YELLOW}[Supervisor] Stopping...${NC}"
    pkill -f "ai-build-fixer-v3.1" 2>/dev/null || true
    rm -f "$PID_FILE"
    exit 0
}

trap cleanup SIGINT SIGTERM

# Check if already running
if [ -f "$PID_FILE" ]; then
    OLD_PID=$(cat "$PID_FILE" 2>/dev/null || echo "")
    if [ -n "$OLD_PID" ] && ps -p "$OLD_PID" > /dev/null 2>&1; then
        echo -e "${YELLOW}[Supervisor] Already running (PID: $OLD_PID)${NC}"
        exit 0
    fi
fi

echo $$ > "$PID_FILE"

echo -e "${BOLD}${GREEN}╔════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}${GREEN}║  🤖 AI FIXER SUPERVISOR - Otomatik Restart & Monitoring    ║${NC}"
echo -e "${BOLD}${GREEN}╚════════════════════════════════════════════════════════════════╝${NC}"
echo ""
echo -e "${GREEN}[Supervisor] Başlatılıyor...${NC}"
echo -e "${GREEN}[Supervisor] Fixer Script: $FIXER_SCRIPT${NC}"
echo -e "${GREEN}[Supervisor] Log: $LOG_FILE${NC}"
echo ""

RESTART_COUNT=0
MAX_RESTARTS=1000
CHECK_INTERVAL=10

while true; do
    # Check if fixer is running
    if ! pgrep -f "ai-build-fixer-v3.1.sh" > /dev/null 2>&1; then
        RESTART_COUNT=$((RESTART_COUNT + 1))
        TIMESTAMP=$(date '+%Y-%m-%d %H:%M:%S')
        
        echo -e "${YELLOW}[$TIMESTAMP] [Supervisor] Fixer durdu, yeniden başlatılıyor... (Restart #$RESTART_COUNT)${NC}" | tee -a "$LOG_FILE"
        
        # Start fixer in background
        cd "$ROOT_DIR"
        nohup bash "$FIXER_SCRIPT" >> "$FIXER_LOG" 2>&1 &
        FIXER_PID=$!
        
        sleep 3
        
        if ps -p "$FIXER_PID" > /dev/null 2>&1; then
            echo -e "${GREEN}[$TIMESTAMP] [Supervisor] ✅ Fixer başlatıldı (PID: $FIXER_PID)${NC}" | tee -a "$LOG_FILE"
        else
            echo -e "${RED}[$TIMESTAMP] [Supervisor] ❌ Fixer başlatılamadı!${NC}" | tee -a "$LOG_FILE"
            # Check for errors
            if [ -f "$FIXER_LOG" ]; then
                echo -e "${YELLOW}[Supervisor] Son hata mesajları:${NC}" | tee -a "$LOG_FILE"
                tail -10 "$FIXER_LOG" | grep -E "error|Error|ERROR|syntax|failed" | tail -5 | tee -a "$LOG_FILE" || true
            fi
        fi
    else
        # Fixer is running, just log status periodically
        if [ $((RESTART_COUNT % 60)) -eq 0 ] && [ $RESTART_COUNT -gt 0 ]; then
            TIMESTAMP=$(date '+%Y-%m-%d %H:%M:%S')
            FIXER_PID=$(pgrep -f "ai-build-fixer-v3.1.sh" | head -1)
            echo -e "${GREEN}[$TIMESTAMP] [Supervisor] ✅ Fixer çalışıyor (PID: $FIXER_PID, Uptime: ${RESTART_COUNT} checks)${NC}" | tee -a "$LOG_FILE"
        fi
    fi
    
    # Safety check
    if [ $RESTART_COUNT -ge $MAX_RESTARTS ]; then
        echo -e "${RED}[Supervisor] ⚠️  Maximum restart limit reached ($MAX_RESTARTS)${NC}" | tee -a "$LOG_FILE"
        echo -e "${YELLOW}[Supervisor] Resetting counter and continuing...${NC}" | tee -a "$LOG_FILE"
        RESTART_COUNT=0
    fi
    
    sleep $CHECK_INTERVAL
done






