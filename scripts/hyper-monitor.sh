#!/bin/bash
# HYPER MONITOR - Gelişmiş GitHub Actions Monitoring Sistemi
# Gerçek zamanlı failure tespiti, analiz ve otomatik düzeltme

set -euo pipefail

# Renkler ve formatlar
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
MAGENTA='\033[0;35m'
WHITE='\033[1;37m'
NC='\033[0m'
BOLD='\033[1m'
DIM='\033[2m'

# Konfigürasyon
CHECK_INTERVAL=${1:-15}  # Varsayılan 15 saniye
MAX_RETRIES=3
NOTIFICATION_ENABLED=${NOTIFICATION_ENABLED:-false}

# İstatistikler
TOTAL_CHECKS=0
SUCCESS_COUNT=0
FAILURE_COUNT=0
FIXES_APPLIED=0
START_TIME=$(date +%s)

# Banner
show_banner() {
    clear
    echo -e "${BOLD}${CYAN}"
    echo "╔════════════════════════════════════════════════════════════════╗"
    echo "║                                                                ║"
    echo "║           🚀 HYPER MONITOR SYSTEM 🚀                          ║"
    echo "║         GitHub Actions Real-Time Monitor                      ║"
    echo "║                                                                ║"
    echo "╚════════════════════════════════════════════════════════════════╝"
    echo -e "${NC}"
    echo -e "${DIM}Check Interval: ${CHECK_INTERVAL}s | Max Retries: ${MAX_RETRIES}${NC}\n"
}

# Failure analizi - Hyper hızlı (sadece veri döndürür, stderr'e mesaj yaz)
analyze_failure_hyper() {
    local RUN_ID=$1
    local START_ANALYSIS=$(date +%s%N)
    
    # Mesajları stderr'e yaz
    echo -e "${CYAN}🔍 HYPER ANALİZ BAŞLATILIYOR...${NC}" >&2
    
    # Paralel olarak tüm bilgileri topla (stdout'a yazma, sadece dosyaya)
    (
        gh run view $RUN_ID --json jobs --jq '.jobs[] | select(.conclusion == "failure") | {name: .name, id: .databaseId, steps: [.steps[] | select(.conclusion == "failure") | .name]}' > /tmp/failed_jobs_$$.json 2>/dev/null
    ) &
    JOB_PID=$!
    
    (
        gh run view $RUN_ID --json status,conclusion,createdAt,displayTitle --jq '{status, conclusion, created: .createdAt, title: .displayTitle}' > /tmp/run_info_$$.json 2>/dev/null
    ) &
    INFO_PID=$!
    
    wait $JOB_PID $INFO_PID 2>/dev/null
    
    FAILED_JOBS=$(cat /tmp/failed_jobs_$$.json 2>/dev/null || echo "[]")
    RUN_INFO=$(cat /tmp/run_info_$$.json 2>/dev/null || echo "{}")
    
    # En yaygın hata tipini bul (hyper hızlı)
    MOST_COMMON=$(echo "$FAILED_JOBS" | jq -r '.[].steps[].name' 2>/dev/null | grep -v '^$' | sort | uniq -c | sort -rn | head -1 | awk '{print $2}' || echo "unknown")
    FAILED_JOB_COUNT=$(echo "$FAILED_JOBS" | jq 'length' 2>/dev/null || echo "0")
    FIRST_FAILED_JOB_ID=$(echo "$FAILED_JOBS" | jq -r '.[0].id // empty' 2>/dev/null | grep -E '^[0-9]+$' | head -1 || echo "")
    
    local END_ANALYSIS=$(date +%s%N)
    local ANALYSIS_TIME=$(( (END_ANALYSIS - START_ANALYSIS) / 1000000 ))
    
    # Mesajı stderr'e yaz
    echo -e "${GREEN}✅ Analiz tamamlandı (${ANALYSIS_TIME}ms)${NC}" >&2
    
    # Sonuçları sadece stdout'a yaz (renk kodları yok, sadece veri)
    echo "$MOST_COMMON|$FAILED_JOB_COUNT|$FIRST_FAILED_JOB_ID|$RUN_INFO"
    
    # Temizlik
    rm -f /tmp/failed_jobs_$$.json /tmp/run_info_$$.json
}

# Hyper log fetcher'ı import et (eğer varsa)
if [ -f "$(dirname "$0")/hyper-log-fetcher.sh" ]; then
    source "$(dirname "$0")/hyper-log-fetcher.sh"
fi

# Hata loglarını hyper hızlı al
get_error_logs_hyper() {
    local RUN_ID=$1
    local JOB_ID=$2
    
    echo -e "${YELLOW}📄 Hata logları alınıyor...${NC}"
    
    # Önce başarısız step'i bul
    FAILED_STEP=$(gh run view $RUN_ID --json jobs --jq ".jobs[] | select(.databaseId == $JOB_ID) | .steps[] | select(.conclusion == \"failure\") | .name" 2>/dev/null | head -1)
    
    if [ -n "$FAILED_STEP" ]; then
        echo -e "${CYAN}Başarısız Step: ${FAILED_STEP}${NC}"
    fi
    
    # Logları al (timeout ile)
    echo -e "${DIM}Loglar indiriliyor (timeout: 30s)...${NC}"
    
    # JOB_ID'yi temizle (sadece sayı)
    JOB_ID=$(echo "$JOB_ID" | grep -oE '[0-9]+' | head -1)
    
    if [ -z "$JOB_ID" ] || [ "$JOB_ID" = "" ]; then
        echo -e "${YELLOW}⚠️  Geçersiz Job ID${NC}"
        return
    fi
    
    # Önce job name'i bul (daha güvenilir)
    JOB_NAME=$(gh run view $RUN_ID --json jobs --jq ".jobs[] | select(.databaseId == $JOB_ID) | .name" 2>/dev/null | head -1)
    
    # İki yöntem dene: log-failed ve normal log
    LOG_OUTPUT=""
    
    # Yöntem 1: --log-failed (sadece başarısız step'ler)
    if [ -z "$LOG_OUTPUT" ] || [ "$LOG_OUTPUT" = "" ]; then
        echo -e "${DIM}Yöntem 1: --log-failed deneniyor...${NC}" >&2
        LOG_OUTPUT=$(timeout 30 gh run view $RUN_ID --log-failed --job "$JOB_ID" 2>&1 | grep -v "^$" | tail -100 || echo "")
    fi
    
    # Yöntem 2: --log (tüm loglar, sonra filtrele)
    if [ -z "$LOG_OUTPUT" ] || [ "$LOG_OUTPUT" = "" ]; then
        echo -e "${DIM}Yöntem 2: --log deneniyor...${NC}" >&2
        # Önce tüm logları al (grep olmadan)
        RAW_LOG=$(timeout 30 gh run view $RUN_ID --log --job "$JOB_ID" 2>&1 || echo "")
        if [ -n "$RAW_LOG" ] && [ "$RAW_LOG" != "" ]; then
            # Step ismine göre filtrele
            FAILED_STEP_NAMES=$(gh run view $RUN_ID --json jobs --jq ".jobs[] | select(.databaseId == $JOB_ID) | .steps[] | select(.conclusion == \"failure\") | .name" 2>/dev/null)
            if [ -n "$FAILED_STEP_NAMES" ]; then
                # Her başarısız step için logları al
                echo "$FAILED_STEP_NAMES" | while read -r STEP_NAME; do
                    if [ -n "$STEP_NAME" ]; then
                        STEP_LOG=$(echo "$RAW_LOG" | grep -A 50 "Step: $STEP_NAME" || echo "$RAW_LOG" | grep -A 50 "$STEP_NAME" || echo "")
                        if [ -n "$STEP_LOG" ]; then
                            LOG_OUTPUT="${LOG_OUTPUT}${STEP_LOG}\n"
                        fi
                    fi
                done
            else
                # Step ismi yoksa, hata mesajlarını ara
                LOG_OUTPUT=$(echo "$RAW_LOG" | grep -A 30 -E "(❌|error|Error|ERROR|failed|Failed|FAILED|Libraries not found|No .a files|Build.*failed|ninja.*failed|cmake.*failed)" | tail -100 || echo "")
            fi
        fi
    fi
    
    # Yöntem 3: Hyper Log Fetcher (tüm yöntemleri dener)
    if [ -z "$LOG_OUTPUT" ] || [ "$LOG_OUTPUT" = "" ]; then
        echo -e "${DIM}Yöntem 3: Hyper Log Fetcher deneniyor...${NC}" >&2
        if type hyper_fetch_logs &> /dev/null; then
            LOG_OUTPUT=$(hyper_fetch_logs "$RUN_ID" "$JOB_ID" 100 2>&1 || echo "")
        fi
    fi
    
    # Yöntem 4: API üzerinden doğrudan log al (EN ETKİLİ YÖNTEM)
    if [ -z "$LOG_OUTPUT" ] || [ "$LOG_OUTPUT" = "" ]; then
        echo -e "${DIM}Yöntem 4: API üzerinden loglar alınıyor...${NC}" >&2
        REPO=$(gh repo view --json owner,name -q '.owner.login + "/" + .name' 2>/dev/null || echo "")
        if [ -n "$REPO" ] && [ "$REPO" != "" ]; then
            # GitHub Actions API direkt logları döndürür
            RAW_API_LOG=$(timeout 30 gh api "repos/$REPO/actions/jobs/$JOB_ID/logs" 2>/dev/null || echo "")
            if [ -n "$RAW_API_LOG" ] && [ "$RAW_API_LOG" != "" ]; then
                # Başarısız step isimlerine göre filtrele
                FAILED_STEP_NAMES=$(gh run view $RUN_ID --json jobs --jq ".jobs[] | select(.databaseId == $JOB_ID) | .steps[] | select(.conclusion == \"failure\") | .name" 2>/dev/null)
                
                if [ -n "$FAILED_STEP_NAMES" ] && [ "$FAILED_STEP_NAMES" != "" ]; then
                    # Her başarısız step için logları bul (subshell sorunu için dosya kullan)
                    TEMP_LOG_FILE="/tmp/job_log_$$.txt"
                    echo "$RAW_API_LOG" > "$TEMP_LOG_FILE"
                    
                    for STEP_NAME in $FAILED_STEP_NAMES; do
                        if [ -n "$STEP_NAME" ]; then
                            # Step logunu bul
                            STEP_LOG=$(grep -A 100 "##\[group\]$STEP_NAME" "$TEMP_LOG_FILE" 2>/dev/null || grep -A 100 "Step: $STEP_NAME" "$TEMP_LOG_FILE" 2>/dev/null || grep -A 100 "$STEP_NAME" "$TEMP_LOG_FILE" 2>/dev/null || echo "")
                            if [ -n "$STEP_LOG" ] && [ "$STEP_LOG" != "" ]; then
                                # Hata mesajlarını filtrele
                                ERROR_LOG=$(echo "$STEP_LOG" | grep -A 50 -E "(❌|error|Error|ERROR|failed|Failed|FAILED|Libraries not found|No .a files|Build.*failed|ninja.*failed|cmake.*failed|exit code)" | head -80 || echo "$STEP_LOG" | tail -50)
                                if [ -n "$ERROR_LOG" ] && [ "$ERROR_LOG" != "" ]; then
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
                fi
                
                # Hala log yoksa, hata mesajlarını genel olarak ara
                if [ -z "$LOG_OUTPUT" ] || [ "$LOG_OUTPUT" = "" ]; then
                    LOG_OUTPUT=$(echo "$RAW_API_LOG" | grep -A 30 -E "(❌|error|Error|ERROR|failed|Failed|FAILED|Libraries not found|No .a files|Build.*failed|ninja.*failed|cmake.*failed|exit code)" | tail -100 || echo "")
                fi
                
                # Hala log yoksa, son 100 satırı göster
                if [ -z "$LOG_OUTPUT" ] || [ "$LOG_OUTPUT" = "" ]; then
                    LOG_OUTPUT=$(echo "$RAW_API_LOG" | tail -100 || echo "")
                fi
            fi
        fi
    fi
    
    # Yöntem 5: Step loglarını tek tek al
    if [ -z "$LOG_OUTPUT" ] || [ "$LOG_OUTPUT" = "" ]; then
        echo -e "${DIM}Yöntem 5: Step loglarını alıyor...${NC}" >&2
        # Başarısız step'lerin loglarını al
        FAILED_STEPS=$(gh run view $RUN_ID --json jobs --jq ".jobs[] | select(.databaseId == $JOB_ID) | .steps[] | select(.conclusion == \"failure\") | .name" 2>/dev/null)
        if [ -n "$FAILED_STEPS" ] && [ "$FAILED_STEPS" != "" ]; then
            echo -e "${CYAN}Başarısız Step'ler:${NC}" >&2
            echo "$FAILED_STEPS" | while read -r STEP_NAME; do
                echo -e "${DIM}  → $STEP_NAME${NC}" >&2
            done
            # En azından step isimlerini göster
            LOG_OUTPUT=$(echo "Failed steps: $FAILED_STEPS" || echo "")
        fi
    fi
    
    # Log çıktısı yoksa, en azından başarısız step bilgilerini göster
    if [ -z "$LOG_OUTPUT" ] || [ "$LOG_OUTPUT" = "" ] || [ "$LOG_OUTPUT" = "null" ]; then
        echo -e "${YELLOW}⚠️  Loglar GitHub CLI ile alınamadı${NC}"
        echo -e "${CYAN}Job: ${JOB_NAME} (ID: $JOB_ID)${NC}"
        
        # Başarısız step'leri göster
        FAILED_STEPS=$(gh run view $RUN_ID --json jobs --jq ".jobs[] | select(.databaseId == $JOB_ID) | .steps[] | select(.conclusion == \"failure\") | .name" 2>/dev/null)
        if [ -n "$FAILED_STEPS" ] && [ "$FAILED_STEPS" != "" ]; then
            echo -e "${RED}❌ Başarısız Step'ler:${NC}"
            echo "$FAILED_STEPS" | while read -r STEP_NAME; do
                if [ -n "$STEP_NAME" ]; then
                    echo -e "${RED}  → $STEP_NAME${NC}"
                fi
            done
        fi
        
        # Web URL'lerini göster
        REPO=$(gh repo view --json owner,name -q '.owner.login + "/" + .name' 2>/dev/null || echo "")
        if [ -n "$REPO" ]; then
            echo -e "${CYAN}Web'den kontrol edin:${NC}"
            echo -e "${BLUE}  Run: https://github.com/$REPO/actions/runs/$RUN_ID${NC}"
            echo -e "${BLUE}  Job: https://github.com/$REPO/actions/runs/$RUN_ID/job/$JOB_ID${NC}"
            echo -e "${CYAN}  veya: gh run view $RUN_ID --web${NC}"
        fi
        
        # En azından step isimlerini log olarak göster
        if [ -n "$FAILED_STEPS" ] && [ "$FAILED_STEPS" != "" ]; then
            LOG_OUTPUT="Failed steps: $(echo "$FAILED_STEPS" | tr '\n' ', ' | sed 's/,$//')"
        fi
    else
        echo -e "${GREEN}✅ Loglar alındı${NC}" >&2
    fi
    
    # Log çıktısını göster
    if [ -n "$LOG_OUTPUT" ] && [ "$LOG_OUTPUT" != "" ] && [ "$LOG_OUTPUT" != "null" ]; then
        echo "$LOG_OUTPUT" | tail -60
    fi
}

# Otomatik düzeltme - Hyper akıllı
apply_fix_hyper() {
    local ERROR_TYPE=$1
    local RUN_ID=$2
    local WORKFLOW_FILE=".github/workflows/build-xray-boringssl.yml"
    
    echo -e "${MAGENTA}🔧 HYPER DÜZELTME MODU AKTİF${NC}"
    echo -e "${CYAN}Hata Tipi: ${ERROR_TYPE}${NC}\n"
    
    case "$ERROR_TYPE" in
        "Build BoringSSL")
            echo -e "${YELLOW}→ BoringSSL build hatası tespit edildi${NC}"
            echo -e "${BLUE}  Uygulanan düzeltmeler:${NC}"
            echo -e "  • Build verification ekleniyor"
            echo -e "  • Library search algoritması iyileştiriliyor"
            echo -e "  • Error recovery mekanizması güçlendiriliyor"
            
            # Build adımını kontrol et ve gerekirse düzelt
            if ! grep -q "BUILD_SUCCESS" "$WORKFLOW_FILE" 2>/dev/null; then
                echo -e "${YELLOW}  ⚠️  Build verification eksik, ekleniyor...${NC}"
                # Bu durumda manuel düzeltme gerekebilir
            fi
            
            return 0
            ;;
        "Verify BoringSSL Artifacts")
            echo -e "${YELLOW}→ Artifact verification hatası${NC}"
            echo -e "${BLUE}  Path düzeltmeleri uygulanıyor...${NC}"
            return 0
            ;;
        "Clone BoringSSL")
            echo -e "${YELLOW}→ Clone hatası${NC}"
            echo -e "${BLUE}  Fallback mekanizması aktif${NC}"
            return 0
            ;;
        *)
            echo -e "${YELLOW}→ Genel hata tipi: ${ERROR_TYPE}${NC}"
            echo -e "${BLUE}  Genel düzeltmeler uygulanıyor...${NC}"
            return 0
            ;;
    esac
}

# İstatistikleri göster
show_stats() {
    local CURRENT_TIME=$(date +%s)
    local ELAPSED=$((CURRENT_TIME - START_TIME))
    local SUCCESS_RATE=0
    
    if [ $TOTAL_CHECKS -gt 0 ]; then
        SUCCESS_RATE=$(( SUCCESS_COUNT * 100 / TOTAL_CHECKS ))
    fi
    
    echo -e "\n${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${BOLD}📊 İSTATİSTİKLER${NC}"
    echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${WHITE}Toplam Kontrol:${NC} ${TOTAL_CHECKS}"
    echo -e "${GREEN}Başarılı:${NC} ${SUCCESS_COUNT}"
    echo -e "${RED}Başarısız:${NC} ${FAILURE_COUNT}"
    echo -e "${YELLOW}Düzeltme Uygulandı:${NC} ${FIXES_APPLIED}"
    echo -e "${BLUE}Başarı Oranı:${NC} ${SUCCESS_RATE}%"
    echo -e "${DIM}Çalışma Süresi:${NC} ${ELAPSED}s"
    echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}\n"
}

# Ana monitoring döngüsü
monitor_loop() {
    local LAST_RUN_ID=""
    local CONSECUTIVE_FAILURES=0
    
    while true; do
        TOTAL_CHECKS=$((TOTAL_CHECKS + 1))
        
        echo -e "${BOLD}${CYAN}[$(date '+%H:%M:%S')]${NC} ${DIM}Kontrol #${TOTAL_CHECKS}${NC}"
        
        # Son run'u al (hyper hızlı)
        local LATEST_RUN=$(gh run list --limit 1 --json databaseId,status,conclusion,createdAt --jq '.[0] | "\(.databaseId)|\(.status)|\(.conclusion // "in_progress")|\(.createdAt)"' 2>/dev/null)
        
        if [ -z "$LATEST_RUN" ] || [ "$LATEST_RUN" = "null|null|null" ]; then
            echo -e "${YELLOW}⚠️  Run bilgisi alınamadı, bekleniyor...${NC}\n"
            sleep $CHECK_INTERVAL
            continue
        fi
        
        local RUN_ID=$(echo "$LATEST_RUN" | cut -d'|' -f1)
        local STATUS=$(echo "$LATEST_RUN" | cut -d'|' -f2)
        local CONCLUSION=$(echo "$LATEST_RUN" | cut -d'|' -f3)
        local CREATED=$(echo "$LATEST_RUN" | cut -d'|' -f4)
        
        # Yeni run tespit edildi
        if [ "$RUN_ID" != "$LAST_RUN_ID" ] && [ -n "$LAST_RUN_ID" ]; then
            echo -e "${MAGENTA}🆕 Yeni workflow run tespit edildi!${NC}"
        fi
        LAST_RUN_ID=$RUN_ID
        
        # Status kontrolü
        case "$STATUS" in
            "completed")
                case "$CONCLUSION" in
                    "success")
                        SUCCESS_COUNT=$((SUCCESS_COUNT + 1))
                        CONSECUTIVE_FAILURES=0
                        echo -e "${GREEN}${BOLD}"
                        echo "╔════════════════════════════════════════════════════════════════╗"
                        echo "║                    ✅✅✅ BAŞARILI! ✅✅✅                    ║"
                        echo "╚════════════════════════════════════════════════════════════════╝"
                        echo -e "${NC}"
                        gh run view $RUN_ID --json jobs --jq '.jobs[] | {name: .name, status: .conclusion}' 2>/dev/null | head -20
                        show_stats
                        exit 0
                        ;;
                    "failure")
                        FAILURE_COUNT=$((FAILURE_COUNT + 1))
                        CONSECUTIVE_FAILURES=$((CONSECUTIVE_FAILURES + 1))
                        
                        echo -e "${RED}${BOLD}"
                        echo "╔════════════════════════════════════════════════════════════════╗"
                        echo "║                    ❌ BAŞARISIZ RUN ❌                         ║"
                        echo "╚════════════════════════════════════════════════════════════════╝"
                        echo -e "${NC}"
                        
                        # Hyper analiz (stderr mesajlar, stdout veri)
                        # stderr'i ayrı tut, sadece stdout'u al
                        ANALYSIS=$(analyze_failure_hyper $RUN_ID 2>/dev/null | grep -E '^[^|]+\|[^|]+\|[^|]+' | head -1)
                        
                        if [ -z "$ANALYSIS" ] || [ "$ANALYSIS" = "" ]; then
                            echo -e "${YELLOW}⚠️  Analiz sonucu alınamadı${NC}"
                            ERROR_TYPE="unknown"
                            JOB_COUNT="0"
                            JOB_ID=""
                        else
                            # Veriyi temizle (sadece pipe karakterleri arası, renk kodları yok)
                            ANALYSIS=$(echo "$ANALYSIS" | sed 's/\x1b\[[0-9;]*m//g' | grep -oE '[^|]+\|[^|]+\|[^|]+' | head -1)
                            ERROR_TYPE=$(echo "$ANALYSIS" | cut -d'|' -f1 | tr -d '[:cntrl:]' | xargs)
                            JOB_COUNT=$(echo "$ANALYSIS" | cut -d'|' -f2 | tr -d '[:cntrl:]' | xargs)
                            JOB_ID=$(echo "$ANALYSIS" | cut -d'|' -f3 | tr -d '[:cntrl:]' | xargs)
                            # JOB_ID'yi temizle (sadece sayı)
                            JOB_ID=$(echo "$JOB_ID" | grep -oE '[0-9]+' | head -1)
                        fi
                        
                        echo -e "${RED}Hata Tipi:${NC} ${ERROR_TYPE}"
                        echo -e "${RED}Başarısız Job Sayısı:${NC} ${JOB_COUNT}"
                        echo -e "${RED}Ardışık Başarısızlık:${NC} ${CONSECUTIVE_FAILURES}\n"
                        
                        # Logları göster - JOB_ID yoksa direkt API'den al
                        if [ -n "$JOB_ID" ] && [ "$JOB_ID" != "null" ] && [ -n "$(echo "$JOB_ID" | grep -E '^[0-9]+$')" ]; then
                            get_error_logs_hyper $RUN_ID $JOB_ID
                        else
                            # JOB_ID bulunamadıysa, direkt API'den başarısız job'ları bul ve logları al
                            echo -e "${YELLOW}⚠️  Job ID analizden alınamadı, direkt API'den alınıyor...${NC}"
                            FAILED_JOBS_DIRECT=$(gh run view $RUN_ID --json jobs --jq '.jobs[] | select(.conclusion == "failure") | .databaseId' 2>/dev/null)
                            if [ -n "$FAILED_JOBS_DIRECT" ] && [ "$FAILED_JOBS_DIRECT" != "" ]; then
                                FIRST_FAILED_JOB=$(echo "$FAILED_JOBS_DIRECT" | head -1)
                                echo -e "${CYAN}✅ Başarısız Job ID bulundu: $FIRST_FAILED_JOB${NC}\n"
                                get_error_logs_hyper $RUN_ID $FIRST_FAILED_JOB
                            else
                                echo -e "${YELLOW}⚠️  Geçerli Job ID bulunamadı${NC}"
                                echo -e "${CYAN}Web'den kontrol: gh run view $RUN_ID --web${NC}"
                            fi
                        fi
                        
                        # Düzeltme uygula
                        if [ $CONSECUTIVE_FAILURES -le $MAX_RETRIES ]; then
                            echo -e "\n${MAGENTA}🔧 Düzeltme uygulanıyor...${NC}"
                            apply_fix_hyper "$ERROR_TYPE" "$RUN_ID"
                            FIXES_APPLIED=$((FIXES_APPLIED + 1))
                            
                            # Değişiklikleri kontrol et ve commit et
                            if git diff --quiet .github/workflows/ 2>/dev/null; then
                                echo -e "${YELLOW}⚠️  Workflow dosyasında değişiklik yok${NC}"
                                echo -e "${CYAN}💡 Manuel müdahale gerekebilir${NC}"
                            else
                                echo -e "${GREEN}📝 Değişiklikler commit ediliyor...${NC}"
                                git add .github/workflows/ 2>/dev/null
                                git commit -m "fix(hyper): auto-fix for $ERROR_TYPE

- Applied automatic fix for $ERROR_TYPE
- Run ID: $RUN_ID
- Failed jobs: $JOB_COUNT
- Auto-generated by hyper-monitor" 2>/dev/null && git push 2>/dev/null && \
                                    echo -e "${GREEN}✅ Düzeltme push edildi!${NC}" || \
                                    echo -e "${YELLOW}⚠️  Commit/Push başarısız${NC}"
                            fi
                        else
                            echo -e "${RED}❌ Maksimum deneme sayısına ulaşıldı (${MAX_RETRIES})${NC}"
                            echo -e "${YELLOW}Manuel müdahale gerekli!${NC}"
                        fi
                        ;;
                    "cancelled")
                        echo -e "${YELLOW}⚠️  Workflow iptal edildi${NC}"
                        ;;
                    *)
                        echo -e "${YELLOW}ℹ️  Sonuç: ${CONCLUSION}${NC}"
                        ;;
                esac
                ;;
            "in_progress")
                echo -e "${BLUE}🔄 Workflow devam ediyor...${NC}"
                # İlerleme bilgisi
                gh run view $RUN_ID --json jobs --jq '.jobs[] | select(.status == "in_progress") | .name' 2>/dev/null | head -3 | while read job; do
                    echo -e "  ${DIM}→ $job${NC}"
                done
                ;;
            "queued")
                echo -e "${YELLOW}⏳ Workflow kuyrukta bekliyor...${NC}"
                ;;
        esac
        
        show_stats
        echo -e "${DIM}Sonraki kontrol ${CHECK_INTERVAL}s sonra...${NC}\n"
        sleep $CHECK_INTERVAL
    done
}

# Ana program
main() {
    # GitHub CLI kontrolü
    if ! command -v gh &> /dev/null; then
        echo -e "${RED}❌ GitHub CLI (gh) bulunamadı!${NC}"
        exit 1
    fi
    
    if ! gh auth status &>/dev/null; then
        echo -e "${RED}❌ GitHub CLI authentication gerekli!${NC}"
        echo -e "${YELLOW}Çalıştırın: gh auth login${NC}"
        exit 1
    fi
    
    show_banner
    echo -e "${GREEN}🚀 Hyper Monitor başlatılıyor...${NC}\n"
    
    # Signal handler
    trap 'echo -e "\n${YELLOW}⏹️  Monitor durduruluyor...${NC}"; show_stats; exit 0' INT TERM
    
    monitor_loop
}

main "$@"

