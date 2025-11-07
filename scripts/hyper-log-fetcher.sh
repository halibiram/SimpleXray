#!/bin/bash

# Hyper Log Fetcher - GitHub Actions loglarını almak için gelişmiş fonksiyon
# Tüm yöntemleri dener: CLI, API, Web scraping, Tarayıcı

set -euo pipefail

# Renk kodları
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
MAGENTA='\033[0;35m'
DIM='\033[0;2m'
NC='\033[0m' # No Color
BOLD='\033[1m'

# Hyper log alma fonksiyonu - Tüm yöntemleri dener
hyper_fetch_logs() {
    local RUN_ID=$1
    local JOB_ID=$2
    local MAX_LINES=${3:-100}
    
    echo -e "${YELLOW}📄 Hyper Log Fetcher başlatılıyor...${NC}"
    echo -e "${DIM}Run ID: $RUN_ID | Job ID: $JOB_ID${NC}\n"
    
    LOG_OUTPUT=""
    
    # Yöntem 1: GitHub CLI --log-failed
    if [ -z "$LOG_OUTPUT" ] || [ "$LOG_OUTPUT" = "" ]; then
        echo -e "${DIM}[1/6] GitHub CLI --log-failed deneniyor...${NC}" >&2
        LOG_OUTPUT=$(timeout 20 gh run view "$RUN_ID" --log-failed --job "$JOB_ID" 2>&1 | grep -v "^$" | tail -"$MAX_LINES" || echo "")
    fi
    
    # Yöntem 2: GitHub CLI --log (tüm loglar)
    if [ -z "$LOG_OUTPUT" ] || [ "$LOG_OUTPUT" = "" ]; then
        echo -e "${DIM}[2/6] GitHub CLI --log deneniyor...${NC}" >&2
        RAW_LOG=$(timeout 20 gh run view "$RUN_ID" --log --job "$JOB_ID" 2>&1 || echo "")
        if [ -n "$RAW_LOG" ] && [ "$RAW_LOG" != "" ]; then
            # Hata mesajlarını filtrele
            LOG_OUTPUT=$(echo "$RAW_LOG" | grep -A 30 -E "(error|Error|ERROR|failed|Failed|FAILED|exit code|CMake Error)" | tail -"$MAX_LINES" || echo "$RAW_LOG" | tail -"$MAX_LINES")
        fi
    fi
    
    # Yöntem 3: GitHub API (direkt log indirme)
    if [ -z "$LOG_OUTPUT" ] || [ "$LOG_OUTPUT" = "" ]; then
        echo -e "${DIM}[3/6] GitHub API deneniyor...${NC}" >&2
        REPO=$(gh repo view --json owner,name -q '.owner.login + "/" + .name' 2>/dev/null || echo "")
        if [ -n "$REPO" ] && [ "$REPO" != "" ]; then
            RAW_API_LOG=$(timeout 30 gh api "repos/$REPO/actions/jobs/$JOB_ID/logs" 2>/dev/null || echo "")
            if [ -n "$RAW_API_LOG" ] && [ "$RAW_API_LOG" != "" ]; then
                # Başarısız step'leri bul
                FAILED_STEPS=$(gh run view "$RUN_ID" --json jobs --jq ".jobs[] | select(.databaseId == $JOB_ID) | .steps[] | select(.conclusion == \"failure\") | .name" 2>/dev/null || echo "")
                
                if [ -n "$FAILED_STEPS" ] && [ "$FAILED_STEPS" != "" ]; then
                    TEMP_LOG_FILE="/tmp/hyper_log_$$.txt"
                    echo "$RAW_API_LOG" > "$TEMP_LOG_FILE"
                    
                    for STEP_NAME in $FAILED_STEPS; do
                        if [ -n "$STEP_NAME" ]; then
                            STEP_LOG=$(grep -A 100 "##\[group\]$STEP_NAME" "$TEMP_LOG_FILE" 2>/dev/null || \
                                      grep -A 100 "Step: $STEP_NAME" "$TEMP_LOG_FILE" 2>/dev/null || \
                                      grep -A 100 "$STEP_NAME" "$TEMP_LOG_FILE" 2>/dev/null || echo "")
                            if [ -n "$STEP_LOG" ]; then
                                ERROR_LOG=$(echo "$STEP_LOG" | grep -A 50 -E "(error|Error|ERROR|failed|Failed|FAILED|exit code|CMake Error)" | head -80 || echo "$STEP_LOG" | tail -50)
                                if [ -n "$ERROR_LOG" ]; then
                                    if [ -z "$LOG_OUTPUT" ]; then
                                        LOG_OUTPUT="$ERROR_LOG"
                                    else
                                        LOG_OUTPUT="${LOG_OUTPUT}\n${ERROR_LOG}"
                                    fi
                                fi
                            fi
                        fi
                    done
                    rm -f "$TEMP_LOG_FILE"
                else
                    LOG_OUTPUT=$(echo "$RAW_API_LOG" | grep -A 30 -E "(error|Error|ERROR|failed|Failed|FAILED|exit code)" | tail -"$MAX_LINES" || echo "$RAW_API_LOG" | tail -"$MAX_LINES")
                fi
            fi
        fi
    fi
    
    # Yöntem 4: Web scraping (curl + HTML parsing)
    if [ -z "$LOG_OUTPUT" ] || [ "$LOG_OUTPUT" = "" ]; then
        echo -e "${DIM}[4/6] Web scraping deneniyor...${NC}" >&2
        REPO=$(gh repo view --json owner,name -q '.owner.login + "/" + .name' 2>/dev/null || echo "")
        if [ -n "$REPO" ]; then
            WEB_URL="https://github.com/$REPO/actions/runs/$RUN_ID/job/$JOB_ID"
            # GitHub'un log HTML'ini parse et (basit regex)
            HTML_LOG=$(timeout 30 curl -sL "$WEB_URL" 2>&1 | grep -oP '(?<=<pre class="log-line">)[^<]+' | head -"$MAX_LINES" || echo "")
            if [ -n "$HTML_LOG" ] && [ "$HTML_LOG" != "" ]; then
                # Hata satırlarını filtrele
                LOG_OUTPUT=$(echo "$HTML_LOG" | grep -E "(error|Error|ERROR|failed|Failed|FAILED|exit code)" | head -"$MAX_LINES" || echo "$HTML_LOG" | tail -"$MAX_LINES")
            fi
        fi
    fi
    
    # Yöntem 5: Headless tarayıcı (puppeteer/playwright benzeri)
    if [ -z "$LOG_OUTPUT" ] || [ "$LOG_OUTPUT" = "" ]; then
        echo -e "${DIM}[5/6] Headless tarayıcı kontrolü...${NC}" >&2
        # Node.js ve puppeteer kontrolü
        if command -v node &> /dev/null && command -v npm &> /dev/null; then
            REPO=$(gh repo view --json owner,name -q '.owner.login + "/" + .name' 2>/dev/null || echo "")
            if [ -n "$REPO" ]; then
                WEB_URL="https://github.com/$REPO/actions/runs/$RUN_ID/job/$JOB_ID"
                LOG_OUTPUT=$(node -e "
                    const puppeteer = require('puppeteer-core');
                    (async () => {
                        try {
                            const browser = await puppeteer.launch({headless: true, executablePath: process.env.CHROME_PATH || 'google-chrome'});
                            const page = await browser.newPage();
                            await page.goto('$WEB_URL', {waitUntil: 'networkidle2', timeout: 30000});
                            const logs = await page.evaluate(() => {
                                const logElements = Array.from(document.querySelectorAll('pre.log-line, .log-line, code'));
                                return logElements.map(el => el.textContent).filter(text => 
                                    text.includes('error') || text.includes('Error') || text.includes('failed') || text.includes('Failed')
                                ).slice(0, $MAX_LINES).join('\n');
                            });
                            await browser.close();
                            console.log(logs);
                        } catch(e) {
                            console.error('');
                        }
                    })();
                " 2>/dev/null || echo "")
            fi
        fi
    fi
    
    # Yöntem 6: Python + Selenium (fallback)
    if [ -z "$LOG_OUTPUT" ] || [ "$LOG_OUTPUT" = "" ]; then
        echo -e "${DIM}[6/6] Python Selenium kontrolü...${NC}" >&2
        if command -v python3 &> /dev/null && python3 -c "import selenium" 2>/dev/null; then
            REPO=$(gh repo view --json owner,name -q '.owner.login + "/" + .name' 2>/dev/null || echo "")
            if [ -n "$REPO" ]; then
                WEB_URL="https://github.com/$REPO/actions/runs/$RUN_ID/job/$JOB_ID"
                LOG_OUTPUT=$(python3 << EOF 2>/dev/null || echo ""
from selenium import webdriver
from selenium.webdriver.chrome.options import Options
from selenium.webdriver.common.by import By
import sys

try:
    options = Options()
    options.add_argument('--headless')
    options.add_argument('--no-sandbox')
    options.add_argument('--disable-dev-shm-usage')
    driver = webdriver.Chrome(options=options)
    driver.get('$WEB_URL')
    driver.implicitly_wait(10)
    
    log_elements = driver.find_elements(By.CSS_SELECTOR, 'pre.log-line, .log-line, code')
    logs = []
    for el in log_elements:
        text = el.text
        if any(keyword in text.lower() for keyword in ['error', 'failed', 'exit code']):
            logs.append(text)
            if len(logs) >= $MAX_LINES:
                break
    
    driver.quit()
    print('\n'.join(logs))
except:
    pass
EOF
)
            fi
        fi
    fi
    
    # Sonuç
    if [ -n "$LOG_OUTPUT" ] && [ "$LOG_OUTPUT" != "" ] && [ "$LOG_OUTPUT" != "null" ]; then
        echo -e "${GREEN}✅ Loglar başarıyla alındı!${NC}" >&2
        echo "$LOG_OUTPUT"
        return 0
    else
        echo -e "${YELLOW}⚠️  Tüm yöntemler denendi, loglar alınamadı${NC}" >&2
        REPO=$(gh repo view --json owner,name -q '.owner.login + "/" + .name' 2>/dev/null || echo "")
        if [ -n "$REPO" ]; then
            echo -e "${CYAN}Web'den manuel kontrol:${NC}" >&2
            echo -e "${BLUE}  https://github.com/$REPO/actions/runs/$RUN_ID/job/$JOB_ID${NC}" >&2
        fi
        return 1
    fi
}

# Ana fonksiyon
main() {
    if [ $# -lt 2 ]; then
        echo "Kullanım: $0 <RUN_ID> <JOB_ID> [MAX_LINES]"
        echo "Örnek: $0 19119393884 54636302509 100"
        exit 1
    fi
    
    hyper_fetch_logs "$1" "$2" "${3:-100}"
}

# Script doğrudan çalıştırılırsa
if [ "${BASH_SOURCE[0]}" = "${0}" ]; then
    main "$@"
fi






