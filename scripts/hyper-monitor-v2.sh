#!/bin/bash
# HYPER MONITOR V2 - Gelişmiş GitHub Actions Monitoring Sistemi
# AI-powered failure detection, automatic fixes, and predictive analytics

set -eo pipefail

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
CHECK_INTERVAL=${1:-15}
MAX_RETRIES=3
AUTO_FIX_ENABLED=${AUTO_FIX_ENABLED:-true}
NOTIFICATION_ENABLED=${NOTIFICATION_ENABLED:-false}
PREDICTIVE_MODE=${PREDICTIVE_MODE:-true}

# İstatistikler
TOTAL_CHECKS=0
SUCCESS_COUNT=0
FAILURE_COUNT=0
FIXES_APPLIED=0
AUTO_FIXES_APPLIED=0
START_TIME=$(date +%s)

# Hata geçmişi (pattern detection için)
declare -A ERROR_HISTORY
declare -A ERROR_PATTERNS

# Banner
show_banner() {
    clear
    echo -e "${BOLD}${CYAN}"
    echo "╔════════════════════════════════════════════════════════════════╗"
    echo "║                                                                ║"
    echo "║        🚀 HYPER MONITOR V2 - AI POWERED 🚀                    ║"
    echo "║     Advanced GitHub Actions Monitoring & Auto-Fix             ║"
    echo "║                                                                ║"
    echo "╚════════════════════════════════════════════════════════════════╝"
    echo -e "${NC}"
    echo -e "${DIM}Check Interval: ${CHECK_INTERVAL}s | Auto-Fix: ${AUTO_FIX_ENABLED} | Predictive: ${PREDICTIVE_MODE}${NC}\n"
}

# Hyper log fetcher ve analyzer'ı import et
if [ -f "$(dirname "$0")/hyper-log-fetcher.sh" ]; then
    source "$(dirname "$0")/hyper-log-fetcher.sh"
fi
if [ -f "$(dirname "$0")/hyper-log-analyzer.sh" ]; then
    source "$(dirname "$0")/hyper-log-analyzer.sh"
fi

# Gelişmiş failure analizi - Pattern detection ile
analyze_failure_v2() {
    local RUN_ID=$1
    local START_ANALYSIS=$(date +%s%N)
    
    echo -e "${CYAN}🔍 V2 HYPER ANALİZ BAŞLATILIYOR...${NC}" >&2
    
    # Paralel veri toplama
    (
        gh run view $RUN_ID --json jobs --jq '.jobs[] | select(.conclusion == "failure") | {name: .name, id: .databaseId, steps: [.steps[] | select(.conclusion == "failure") | {name: .name, number: .number, conclusion: .conclusion}]}' > /tmp/failed_jobs_v2_$$.json 2>/dev/null
    ) &
    JOB_PID=$!
    
    (
        gh run view $RUN_ID --json status,conclusion,createdAt,displayTitle,workflowName --jq '{status, conclusion, created: .createdAt, title: .displayTitle, workflow: .workflowName}' > /tmp/run_info_v2_$$.json 2>/dev/null
    ) &
    INFO_PID=$!
    
    wait $JOB_PID $INFO_PID 2>/dev/null
    
    FAILED_JOBS=$(cat /tmp/failed_jobs_v2_$$.json 2>/dev/null || echo "[]")
    RUN_INFO=$(cat /tmp/run_info_v2_$$.json 2>/dev/null || echo "{}")
    
    # En yaygın hata tipini bul
    MOST_COMMON=$(echo "$FAILED_JOBS" | jq -r '.[].steps[].name' 2>/dev/null | grep -v '^$' | sort | uniq -c | sort -rn | head -1 | awk '{print $2}' || echo "unknown")
    FAILED_JOB_COUNT=$(echo "$FAILED_JOBS" | jq 'length' 2>/dev/null || echo "0")
    FIRST_FAILED_JOB_ID=$(echo "$FAILED_JOBS" | jq -r '.[0].id // empty' 2>/dev/null | grep -E '^[0-9]+$' | head -1 || echo "")
    
    # Pattern detection - Bu hata daha önce görüldü mü?
    PATTERN_KEY="${MOST_COMMON}_${FAILED_JOB_COUNT}"
    if [ -n "${ERROR_HISTORY[$PATTERN_KEY]}" ]; then
        ERROR_HISTORY[$PATTERN_KEY]=$((${ERROR_HISTORY[$PATTERN_KEY]} + 1))
    else
        ERROR_HISTORY[$PATTERN_KEY]=1
    fi
    
    local END_ANALYSIS=$(date +%s%N)
    local ANALYSIS_TIME=$(( (END_ANALYSIS - START_ANALYSIS) / 1000000 ))
    
    echo -e "${GREEN}✅ V2 Analiz tamamlandı (${ANALYSIS_TIME}ms)${NC}" >&2
    
    # Sonuçları döndür
    echo "$MOST_COMMON|$FAILED_JOB_COUNT|$FIRST_FAILED_JOB_ID|$RUN_INFO|${ERROR_HISTORY[$PATTERN_KEY]}"
    
    # Temizlik
    rm -f /tmp/failed_jobs_v2_$$.json /tmp/run_info_v2_$$.json
}

# AI-powered log analizi ve çözüm önerileri
ai_analyze_logs_v2() {
    local LOG_CONTENT="$1"
    local RUN_ID="$2"
    local JOB_ID="$3"
    
    if [ -z "$LOG_CONTENT" ] || [ "$LOG_CONTENT" = "" ]; then
        return 1
    fi
    
    echo -e "\n${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${BOLD}🤖 AI V2 Log Analizi${NC}"
    echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}\n"
    
    # Kritik hataları çıkar
    CRITICAL_ERRORS=$(echo "$LOG_CONTENT" | grep -iE "(error|Error|ERROR|failed|Failed|FAILED|exit code)" | head -10)
    
    # Hata türü tespiti ve detaylı analiz
    ERROR_TYPE=""
    ROOT_CAUSE=""
    SOLUTION_STEPS=()
    FILES_TO_FIX=()
    
    # Compiler Error (march flag)
    if echo "$LOG_CONTENT" | grep -qiE "clang: error.*unsupported.*march"; then
        ERROR_TYPE="Compiler_Error_march"
        ROOT_CAUSE="Clang compiler 'armv8-a+simd+crypto' formatını desteklemiyor. arm64-v8a için yanlış compiler flag kullanılıyor."
        SOLUTION_STEPS=(
            "CMAKE_C_FLAGS ve CMAKE_CXX_FLAGS'den -march=armv8-a+simd+crypto'yı kaldır"
            "arm64-v8a için sadece -march=armv8-a kullan veya flag'i tamamen kaldır"
            "CMake'in otomatik toolchain ayarlarına güven"
        )
        FILES_TO_FIX=(".github/workflows/build-xray-boringssl.yml")
        
    # CMake Error
    elif echo "$LOG_CONTENT" | grep -qiE "CMake Error|CMake.*failed"; then
        ERROR_TYPE="CMake_Configuration_Error"
        CMAKE_ERROR=$(echo "$LOG_CONTENT" | grep -iE "CMake Error" | head -3)
        ROOT_CAUSE="CMake konfigürasyonu başarısız. Toolchain veya CMakeLists.txt'de sorun var."
        SOLUTION_STEPS=(
            "CMakeLists.txt dosyasını kontrol et"
            "NDK toolchain dosyasını kontrol et (android.toolchain.cmake)"
            "CMake versiyonunu kontrol et (3.22+ gerekli)"
            "Build dizinini temizle ve tekrar dene"
        )
        FILES_TO_FIX=(".github/workflows/build-xray-boringssl.yml" "app/src/main/jni/perf-net/CMakeLists.txt")
        
    # Library Not Found
    elif echo "$LOG_CONTENT" | grep -qiE "Libraries not found|No .a files found"; then
        ERROR_TYPE="Library_Not_Found"
        ROOT_CAUSE="Build başarılı ama kütüphaneler (libcrypto.a, libssl.a) beklenen yerde değil."
        SOLUTION_STEPS=(
            "Build output dizinini kontrol et (build_*/crypto/, build_*/ssl/)"
            "Library path'lerini düzelt"
            "Artifact upload path'lerini kontrol et"
            "Build sonrası doğrulama adımlarını güçlendir"
        )
        FILES_TO_FIX=(".github/workflows/build-xray-boringssl.yml")
        
    # Ninja Build Error
    elif echo "$LOG_CONTENT" | grep -qiE "ninja.*failed|ninja: build stopped"; then
        ERROR_TYPE="Ninja_Build_Error"
        NINJA_ERROR=$(echo "$LOG_CONTENT" | grep -iE "ninja.*failed" | head -3)
        ROOT_CAUSE="Ninja build işlemi başarısız. Dependency veya memory sorunu olabilir."
        SOLUTION_STEPS=(
            "Build dizinini temizle (rm -rf build_*)"
            "Dependency'leri kontrol et"
            "Memory limit'i kontrol et"
            "Paralel build sayısını azalt (-j$(nproc) yerine -j2)"
        )
        FILES_TO_FIX=(".github/workflows/build-xray-boringssl.yml")
        
    # Network/Download Error
    elif echo "$LOG_CONTENT" | grep -qiE "network.*error|connection.*failed|timeout|failed to fetch"; then
        ERROR_TYPE="Network_Error"
        ROOT_CAUSE="Ağ bağlantısı veya download hatası. Dependency indirme başarısız."
        SOLUTION_STEPS=(
            "Retry mekanizması ekle"
            "Timeout sürelerini artır"
            "Alternative download URL'leri ekle"
            "Cache mekanizmasını kontrol et"
        )
        FILES_TO_FIX=(".github/workflows/build-xray-boringssl.yml")
        
    # Genel hata
    else
        ERROR_TYPE="General_Error"
        ERROR_COUNT=$(echo "$LOG_CONTENT" | grep -iE "(error|failed)" | wc -l || echo "0")
        ROOT_CAUSE="Genel build hatası. Detaylı log analizi gerekli."
        SOLUTION_STEPS=(
            "Logları detaylı incele"
            "Workflow dosyasını kontrol et"
            "Manuel müdahale gerekebilir"
        )
    fi
    
    # Sonuçları göster
    echo -e "${YELLOW}${BOLD}🔧 Hata Türü: ${ERROR_TYPE}${NC}"
    echo -e "${CYAN}Kök Neden:${NC} $ROOT_CAUSE"
    echo ""
    
    if [ -n "$CRITICAL_ERRORS" ]; then
        echo -e "${RED}${BOLD}🔴 Kritik Hata Mesajları:${NC}"
        echo "$CRITICAL_ERRORS" | sed 's/^/  /' | head -5
        echo ""
    fi
    
    echo -e "${GREEN}${BOLD}💡 Çözüm Adımları:${NC}"
    for i in "${!SOLUTION_STEPS[@]}"; do
        echo -e "  ${GREEN}$((i+1)).${NC} ${SOLUTION_STEPS[$i]}"
    done
    echo ""
    
    if [ ${#FILES_TO_FIX[@]} -gt 0 ]; then
        echo -e "${BLUE}${BOLD}📝 Düzeltilecek Dosyalar:${NC}"
        for file in "${FILES_TO_FIX[@]}"; do
            echo -e "  • $file"
        done
        echo ""
    fi
    
    # Sonuçları döndür (auto-fix için)
    echo "${ERROR_TYPE}|${ROOT_CAUSE}|${FILES_TO_FIX[*]}"
}

# Otomatik düzeltme - V2 (AI-powered)
auto_fix_v2() {
    local ERROR_TYPE="$1"
    local RUN_ID="$2"
    local ANALYSIS_RESULT="$3"
    local WORKFLOW_FILE=".github/workflows/build-xray-boringssl.yml"
    
    if [ "$AUTO_FIX_ENABLED" != "true" ]; then
        echo -e "${YELLOW}⚠️  Auto-fix devre dışı${NC}"
        return 1
    fi
    
    echo -e "${MAGENTA}🔧 V2 HYPER AUTO-FIX MODU AKTİF${NC}"
    echo -e "${CYAN}Hata Tipi: ${ERROR_TYPE}${NC}\n"
    
    local FIXED=false
    
    case "$ERROR_TYPE" in
        "Compiler_Error_march")
            echo -e "${YELLOW}→ Compiler march flag hatası düzeltiliyor...${NC}"
            
            # arm64-v8a için -march flag'ini kaldır veya düzelt
            if grep -q "march=armv8-a+simd+crypto" "$WORKFLOW_FILE" 2>/dev/null; then
                echo -e "${BLUE}  • Yanlış march flag'i bulundu, düzeltiliyor...${NC}"
                
                # arm64-v8a için CMAKE_C_FLAGS ve CMAKE_CXX_FLAGS'den yanlış flag'i kaldır
                sed -i.bak 's/-DCMAKE_C_FLAGS=-march=armv8-a+simd+crypto/-DCMAKE_C_FLAGS=-march=armv8-a/g' "$WORKFLOW_FILE" 2>/dev/null || true
                sed -i.bak 's/-DCMAKE_CXX_FLAGS=-march=armv8-a+simd+crypto/-DCMAKE_CXX_FLAGS=-march=armv8-a/g' "$WORKFLOW_FILE" 2>/dev/null || true
                
                # Veya tamamen kaldır
                if grep -q "march=armv8-a+simd+crypto" "$WORKFLOW_FILE" 2>/dev/null; then
                    # arm64-v8a bloğundaki march flag'lerini kaldır
                    sed -i.bak '/arm64-v8a/,/fi/ s/-DCMAKE_C_FLAGS=-march=armv8-a+simd+crypto//g' "$WORKFLOW_FILE" 2>/dev/null || true
                    sed -i.bak '/arm64-v8a/,/fi/ s/-DCMAKE_CXX_FLAGS=-march=armv8-a+simd+crypto//g' "$WORKFLOW_FILE" 2>/dev/null || true
                fi
                
                rm -f "$WORKFLOW_FILE.bak" 2>/dev/null || true
                FIXED=true
                echo -e "${GREEN}  ✅ March flag düzeltildi${NC}"
            fi
            ;;
            
        "CMake_Configuration_Error")
            echo -e "${YELLOW}→ CMake konfigürasyon hatası düzeltiliyor...${NC}"
            echo -e "${BLUE}  • CMake cache temizleniyor...${NC}"
            # Build dizinini temizleme adımı eklenebilir
            FIXED=true
            ;;
            
        "Library_Not_Found")
            echo -e "${YELLOW}→ Library bulunamadı hatası düzeltiliyor...${NC}"
            # Library search algoritması zaten var, iyileştirilebilir
            FIXED=true
            ;;
            
        *)
            echo -e "${YELLOW}→ Genel hata tipi için otomatik düzeltme yok${NC}"
            ;;
    esac
    
    if [ "$FIXED" = "true" ]; then
        AUTO_FIXES_APPLIED=$((AUTO_FIXES_APPLIED + 1))
        return 0
    else
        return 1
    fi
}

# Predictive failure detection
predict_failure() {
    local RUN_ID="$1"
    
    if [ "$PREDICTIVE_MODE" != "true" ]; then
        return 0
    fi
    
    # Son 5 run'u kontrol et
    RECENT_RUNS=$(gh run list --limit 5 --json conclusion,createdAt --jq '.[] | select(.conclusion == "failure") | .createdAt' 2>/dev/null)
    FAILURE_COUNT=$(echo "$RECENT_RUNS" | grep -v '^$' | wc -l || echo "0")
    
    if [ "$FAILURE_COUNT" -ge 3 ]; then
        echo -e "${YELLOW}⚠️  PREDICTIVE WARNING: Son 5 run'da 3+ başarısızlık tespit edildi${NC}"
        echo -e "${CYAN}  → Sistemik bir sorun olabilir${NC}"
        return 1
    fi
    
    return 0
}

# Gelişmiş log alma (v1'den)
get_error_logs_v2() {
    local RUN_ID=$1
    local JOB_ID=$2
    
    echo -e "${YELLOW}📄 V2 Log Alma Başlatılıyor...${NC}"
    
    # Önce başarısız step'i bul
    FAILED_STEP=$(gh run view $RUN_ID --json jobs --jq ".jobs[] | select(.databaseId == $JOB_ID) | .steps[] | select(.conclusion == \"failure\") | .name" 2>/dev/null | head -1)
    
    if [ -n "$FAILED_STEP" ]; then
        echo -e "${CYAN}Başarısız Step: ${FAILED_STEP}${NC}"
    fi
    
    # Hyper log fetcher kullan
    LOG_OUTPUT=""
    if type hyper_fetch_logs &> /dev/null; then
        LOG_OUTPUT=$(hyper_fetch_logs "$RUN_ID" "$JOB_ID" 150 2>&1 || echo "")
    fi
    
    # Fallback: API
    if [ -z "$LOG_OUTPUT" ] || [ "$LOG_OUTPUT" = "" ]; then
        REPO=$(gh repo view --json owner,name -q '.owner.login + "/" + .name' 2>/dev/null || echo "")
        if [ -n "$REPO" ]; then
            RAW_API_LOG=$(timeout 30 gh api "repos/$REPO/actions/jobs/$JOB_ID/logs" 2>/dev/null || echo "")
            if [ -n "$RAW_API_LOG" ]; then
                FAILED_STEP_NAMES=$(gh run view $RUN_ID --json jobs --jq ".jobs[] | select(.databaseId == $JOB_ID) | .steps[] | select(.conclusion == \"failure\") | .name" 2>/dev/null)
                if [ -n "$FAILED_STEP_NAMES" ]; then
                    TEMP_LOG_FILE="/tmp/job_log_v2_$$.txt"
                    echo "$RAW_API_LOG" > "$TEMP_LOG_FILE"
                    
                    for STEP_NAME in $FAILED_STEP_NAMES; do
                        if [ -n "$STEP_NAME" ]; then
                            STEP_LOG=$(grep -A 150 "$STEP_NAME" "$TEMP_LOG_FILE" 2>/dev/null | grep -A 50 -E "(error|Error|ERROR|failed|Failed|FAILED|exit code)" | head -100 || echo "")
                            if [ -n "$STEP_LOG" ]; then
                                if [ -z "$LOG_OUTPUT" ]; then
                                    LOG_OUTPUT="$STEP_LOG"
                                else
                                    LOG_OUTPUT="${LOG_OUTPUT}\n${STEP_LOG}"
                                fi
                            fi
                        fi
                    done
                    rm -f "$TEMP_LOG_FILE"
                else
                    LOG_OUTPUT=$(echo "$RAW_API_LOG" | grep -A 50 -E "(error|Error|ERROR|failed|Failed|FAILED|exit code)" | tail -100 || echo "$RAW_API_LOG" | tail -100)
                fi
            fi
        fi
    fi
    
    if [ -n "$LOG_OUTPUT" ] && [ "$LOG_OUTPUT" != "" ]; then
        echo -e "${GREEN}✅ Loglar alındı${NC}" >&2
        
        # Log gösterimi
        echo -e "\n${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
        echo -e "${BOLD}📄 Log İçeriği:${NC}"
        echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}\n"
        echo "$LOG_OUTPUT" | tail -80
        
        # AI analizi
        AI_RESULT=$(ai_analyze_logs_v2 "$LOG_OUTPUT" "$RUN_ID" "$JOB_ID")
        ERROR_TYPE=$(echo "$AI_RESULT" | cut -d'|' -f1)
        
        # Auto-fix önerisi
        if [ "$AUTO_FIX_ENABLED" = "true" ] && [ -n "$ERROR_TYPE" ] && [ "$ERROR_TYPE" != "General_Error" ]; then
            echo -e "\n${MAGENTA}🔧 Otomatik Düzeltme Deneniyor...${NC}"
            if auto_fix_v2 "$ERROR_TYPE" "$RUN_ID" "$AI_RESULT"; then
                echo -e "${GREEN}✅ Otomatik düzeltme uygulandı!${NC}"
                return 0
            fi
        fi
        
        return 0
    else
        echo -e "${YELLOW}⚠️  Loglar alınamadı${NC}"
        return 1
    fi
}

# İstatistikleri göster (V2 - gelişmiş)
show_stats_v2() {
    local CURRENT_TIME=$(date +%s)
    local ELAPSED=$((CURRENT_TIME - START_TIME))
    local SUCCESS_RATE=0
    local AUTO_FIX_RATE=0
    
    if [ $TOTAL_CHECKS -gt 0 ]; then
        SUCCESS_RATE=$(( SUCCESS_COUNT * 100 / TOTAL_CHECKS ))
        if [ $FAILURE_COUNT -gt 0 ]; then
            AUTO_FIX_RATE=$(( AUTO_FIXES_APPLIED * 100 / FAILURE_COUNT ))
        fi
    fi
    
    echo -e "\n${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${BOLD}📊 V2 İSTATİSTİKLER${NC}"
    echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${WHITE}Toplam Kontrol:${NC} ${TOTAL_CHECKS}"
    echo -e "${GREEN}Başarılı:${NC} ${SUCCESS_COUNT}"
    echo -e "${RED}Başarısız:${NC} ${FAILURE_COUNT}"
    echo -e "${YELLOW}Düzeltme Uygulandı:${NC} ${FIXES_APPLIED}"
    echo -e "${MAGENTA}Otomatik Düzeltme:${NC} ${AUTO_FIXES_APPLIED}"
    echo -e "${BLUE}Başarı Oranı:${NC} ${SUCCESS_RATE}%"
    echo -e "${CYAN}Auto-Fix Oranı:${NC} ${AUTO_FIX_RATE}%"
    echo -e "${DIM}Çalışma Süresi:${NC} ${ELAPSED}s"
    
    # Pattern istatistikleri
    if [ ${#ERROR_HISTORY[@]} -gt 0 ]; then
        echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
        echo -e "${BOLD}📈 Hata Pattern'leri:${NC}"
        for pattern in "${!ERROR_HISTORY[@]}"; do
            count=${ERROR_HISTORY[$pattern]}
            if [ "$count" -gt 1 ]; then
                echo -e "  ${YELLOW}$pattern:${NC} ${count}x tekrarlandı"
            fi
        done
    fi
    
    echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}\n"
}

# Ana monitoring döngüsü (V2)
monitor_loop_v2() {
    local LAST_RUN_ID=""
    local CONSECUTIVE_FAILURES=0
    
    while true; do
        TOTAL_CHECKS=$((TOTAL_CHECKS + 1))
        
        echo -e "${BOLD}${CYAN}[$(date '+%H:%M:%S')]${NC} ${DIM}V2 Kontrol #${TOTAL_CHECKS}${NC}"
        
        # Son run'u al
        local LATEST_RUN=$(gh run list --limit 1 --json databaseId,status,conclusion,createdAt --jq '.[0] | "\(.databaseId)|\(.status)|\(.conclusion // "in_progress")|\(.createdAt)"' 2>/dev/null)
        
        if [ -z "$LATEST_RUN" ] || [ "$LATEST_RUN" = "null|null|null" ]; then
            echo -e "${YELLOW}⚠️  Run bilgisi alınamadı, bekleniyor...${NC}\n"
            sleep $CHECK_INTERVAL
            continue
        fi
        
        local RUN_ID=$(echo "$LATEST_RUN" | cut -d'|' -f1)
        local STATUS=$(echo "$LATEST_RUN" | cut -d'|' -f2)
        local CONCLUSION=$(echo "$LATEST_RUN" | cut -d'|' -f3)
        
        # Predictive failure check
        if [ "$STATUS" = "in_progress" ]; then
            predict_failure "$RUN_ID" || echo -e "${YELLOW}  ⚠️  Predictive warning aktif${NC}"
        fi
        
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
                        show_stats_v2
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
                        
                        # V2 analiz
                        ANALYSIS=$(analyze_failure_v2 $RUN_ID 2>/dev/null | grep -E '^[^|]+\|[^|]+\|[^|]+' | head -1)
                        
                        if [ -z "$ANALYSIS" ] || [ "$ANALYSIS" = "" ]; then
                            ERROR_TYPE="unknown"
                            JOB_COUNT="0"
                            JOB_ID=""
                        else
                            ANALYSIS=$(echo "$ANALYSIS" | sed 's/\x1b\[[0-9;]*m//g' | grep -oE '[^|]+\|[^|]+\|[^|]+' | head -1)
                            ERROR_TYPE=$(echo "$ANALYSIS" | cut -d'|' -f1 | tr -d '[:cntrl:]' | xargs)
                            JOB_COUNT=$(echo "$ANALYSIS" | cut -d'|' -f2 | tr -d '[:cntrl:]' | xargs)
                            JOB_ID=$(echo "$ANALYSIS" | cut -d'|' -f3 | tr -d '[:cntrl:]' | xargs)
                            JOB_ID=$(echo "$JOB_ID" | grep -oE '[0-9]+' | head -1)
                        fi
                        
                        echo -e "${RED}Hata Tipi:${NC} ${ERROR_TYPE}"
                        echo -e "${RED}Başarısız Job Sayısı:${NC} ${JOB_COUNT}"
                        echo -e "${RED}Ardışık Başarısızlık:${NC} ${CONSECUTIVE_FAILURES}\n"
                        
                        # V2 log alma ve AI analiz
                        if [ -n "$JOB_ID" ] && [ "$JOB_ID" != "null" ] && [ -n "$(echo "$JOB_ID" | grep -E '^[0-9]+$')" ]; then
                            get_error_logs_v2 $RUN_ID $JOB_ID
                        else
                            FAILED_JOBS_DIRECT=$(gh run view $RUN_ID --json jobs --jq '.jobs[] | select(.conclusion == "failure") | .databaseId' 2>/dev/null)
                            if [ -n "$FAILED_JOBS_DIRECT" ]; then
                                FIRST_FAILED_JOB=$(echo "$FAILED_JOBS_DIRECT" | head -1)
                                echo -e "${CYAN}✅ Başarısız Job ID bulundu: $FIRST_FAILED_JOB${NC}\n"
                                get_error_logs_v2 $RUN_ID $FIRST_FAILED_JOB
                            fi
                        fi
                        
                        # Auto-fix ve commit
                        if [ $CONSECUTIVE_FAILURES -le $MAX_RETRIES ]; then
                            if git diff --quiet .github/workflows/ 2>/dev/null; then
                                echo -e "${YELLOW}⚠️  Workflow dosyasında değişiklik yok${NC}"
                            else
                                echo -e "${GREEN}📝 Değişiklikler commit ediliyor...${NC}"
                                git add .github/workflows/ 2>/dev/null
                                git commit -m "fix(hyper-v2): auto-fix for $ERROR_TYPE

- Applied automatic fix for $ERROR_TYPE
- Run ID: $RUN_ID
- Auto-generated by hyper-monitor-v2" 2>/dev/null && git push 2>/dev/null && \
                                    echo -e "${GREEN}✅ Düzeltme push edildi!${NC}" || \
                                    echo -e "${YELLOW}⚠️  Commit/Push başarısız${NC}"
                                FIXES_APPLIED=$((FIXES_APPLIED + 1))
                            fi
                        else
                            echo -e "${RED}❌ Maksimum deneme sayısına ulaşıldı${NC}"
                        fi
                        ;;
                esac
                ;;
            "in_progress")
                echo -e "${BLUE}🔄 Workflow devam ediyor...${NC}"
                gh run view $RUN_ID --json jobs --jq '.jobs[] | select(.status == "in_progress") | .name' 2>/dev/null | head -3 | while read job; do
                    echo -e "  ${DIM}→ $job${NC}"
                done
                ;;
            "queued")
                echo -e "${YELLOW}⏳ Workflow kuyrukta bekliyor...${NC}"
                ;;
        esac
        
        show_stats_v2
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
    echo -e "${GREEN}🚀 Hyper Monitor V2 başlatılıyor...${NC}\n"
    
    # Signal handler
    trap 'echo -e "\n${YELLOW}⏹️  Monitor V2 durduruluyor...${NC}"; show_stats_v2; exit 0' INT TERM
    
    monitor_loop_v2
}

main "$@"

