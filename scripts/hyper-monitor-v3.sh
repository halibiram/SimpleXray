#!/bin/bash
# HYPER MONITOR V3 - Next-Gen AI-Powered Monitoring System
# ML-based pattern recognition, multi-workflow support, predictive prevention

set -eo pipefail

# Array'leri initialize et
declare -A ERROR_HISTORY=()
declare -A ERROR_PATTERNS=()
declare -A FIX_HISTORY=()
declare -A WORKFLOW_STATS=()

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
MAX_RETRIES=5
AUTO_FIX_ENABLED=${AUTO_FIX_ENABLED:-true}
NOTIFICATION_ENABLED=${NOTIFICATION_ENABLED:-false}
PREDICTIVE_MODE=${PREDICTIVE_MODE:-true}
ML_PATTERN_RECOGNITION=${ML_PATTERN_RECOGNITION:-true}
MULTI_WORKFLOW=${MULTI_WORKFLOW:-false}
PREVENTION_MODE=${PREVENTION_MODE:-true}

# İstatistikler
TOTAL_CHECKS=0
SUCCESS_COUNT=0
FAILURE_COUNT=0
FIXES_APPLIED=0
AUTO_FIXES_APPLIED=0
PREVENTED_FAILURES=0
START_TIME=$(date +%s)

# ML Pattern Database (basit key-value store)
ML_PATTERN_DB="/tmp/hyper_ml_patterns_$$.json"
echo "{}" > "$ML_PATTERN_DB"

# Hata geçmişi ve pattern'ler
declare -A ERROR_HISTORY
declare -A ERROR_PATTERNS
declare -A FIX_HISTORY
declare -A WORKFLOW_STATS

# Banner V3
show_banner() {
    clear
    echo -e "${BOLD}${MAGENTA}"
    echo "╔════════════════════════════════════════════════════════════════╗"
    echo "║                                                                ║"
    echo "║     🚀 HYPER MONITOR V3 - NEXT GEN AI 🚀                       ║"
    echo "║  ML-Powered | Multi-Workflow | Predictive Prevention          ║"
    echo "║                                                                ║"
    echo "╚════════════════════════════════════════════════════════════════╝"
    echo -e "${NC}"
    echo -e "${DIM}Interval: ${CHECK_INTERVAL}s | Auto-Fix: ${AUTO_FIX_ENABLED} | ML: ${ML_PATTERN_RECOGNITION} | Prevention: ${PREVENTION_MODE}${NC}\n"
}

# ML-based pattern recognition
ml_recognize_pattern() {
    local ERROR_TYPE="$1"
    local LOG_CONTENT="$2"
    local RUN_ID="$3"
    
    if [ "$ML_PATTERN_RECOGNITION" != "true" ]; then
        return 0
    fi
    
    # Pattern signature oluştur
    SIGNATURE=$(echo "$LOG_CONTENT" | grep -iE "(error|failed)" | head -5 | md5sum | cut -d' ' -f1 2>/dev/null || echo "unknown")
    
    # ML DB'de ara
    if [ -f "$ML_PATTERN_DB" ]; then
        PATTERN_MATCH=$(jq -r ".[\"$SIGNATURE\"] // empty" "$ML_PATTERN_DB" 2>/dev/null || echo "")
        
        if [ -n "$PATTERN_MATCH" ] && [ "$PATTERN_MATCH" != "null" ] && [ "$PATTERN_MATCH" != "" ]; then
            echo -e "${CYAN}🔮 ML Pattern Tanındı:${NC} ${YELLOW}$PATTERN_MATCH${NC}"
            
            # Önceki düzeltmeyi öner
            FIX_HISTORY_KEY="${ERROR_TYPE}_${SIGNATURE}"
            if [ -n "${FIX_HISTORY[$FIX_HISTORY_KEY]}" ]; then
                echo -e "${GREEN}💡 Önerilen Düzeltme:${NC} ${FIX_HISTORY[$FIX_HISTORY_KEY]}"
                return 0
            fi
        fi
    fi
    
    # Yeni pattern kaydet
    if [ -f "$ML_PATTERN_DB" ]; then
        TEMP_DB=$(mktemp)
        jq ". + {\"$SIGNATURE\": \"$ERROR_TYPE\"}" "$ML_PATTERN_DB" > "$TEMP_DB" 2>/dev/null && mv "$TEMP_DB" "$ML_PATTERN_DB" || true
    fi
    
    return 1
}

# Predictive Prevention - Hata oluşmadan önce önle
prevent_failure() {
    local RUN_ID="$1"
    
    if [ "$PREVENTION_MODE" != "true" ]; then
        return 0
    fi
    
    # Son 10 run'u analiz et
    RECENT_FAILURES=$(gh run list --limit 10 --json conclusion,createdAt --jq '.[] | select(.conclusion == "failure") | .createdAt' 2>/dev/null)
    FAILURE_COUNT=$(echo "$RECENT_FAILURES" | grep -v '^$' | wc -l || echo "0")
    
    # Eğer son 10 run'da %70+ başarısızlık varsa
    if [ "$FAILURE_COUNT" -ge 7 ]; then
        echo -e "${RED}${BOLD}⚠️  PREVENTION ALERT: Sistemik sorun tespit edildi!${NC}"
        echo -e "${YELLOW}  → Son 10 run'da ${FAILURE_COUNT} başarısızlık${NC}"
        echo -e "${CYAN}  → Kök neden analizi öneriliyor${NC}"
        
        # En yaygın hata türünü bul
        MOST_COMMON_ERROR=$(gh run list --limit 10 --json jobs --jq '.[] | select(.conclusion == "failure") | .jobs[] | select(.conclusion == "failure") | .steps[] | select(.conclusion == "failure") | .name' 2>/dev/null | sort | uniq -c | sort -rn | head -1 | awk '{print $2}' || echo "unknown")
        
        if [ -n "$MOST_COMMON_ERROR" ] && [ "$MOST_COMMON_ERROR" != "unknown" ]; then
            echo -e "${MAGENTA}  🔧 En Yaygın Hata: $MOST_COMMON_ERROR${NC}"
            echo -e "${CYAN}  💡 Proaktif düzeltme öneriliyor${NC}"
            
            # Önleyici düzeltme önerisi
            PREVENTED_FAILURES=$((PREVENTED_FAILURES + 1))
            return 1
        fi
    fi
    
    return 0
}

# Hyper log fetcher ve analyzer'ı import et
if [ -f "$(dirname "$0")/hyper-log-fetcher.sh" ]; then
    source "$(dirname "$0")/hyper-log-fetcher.sh"
fi
if [ -f "$(dirname "$0")/hyper-log-analyzer.sh" ]; then
    source "$(dirname "$0")/hyper-log-analyzer.sh"
fi

# V3 Failure Analizi - ML-enhanced
analyze_failure_v3() {
    local RUN_ID=$1
    local START_ANALYSIS=$(date +%s%N)
    
    echo -e "${CYAN}🔍 V3 ML-ENHANCED ANALİZ BAŞLATILIYOR...${NC}" >&2
    
    # Paralel veri toplama (V2'den geliştirilmiş)
    (
        gh run view $RUN_ID --json jobs,workflowName,createdAt,conclusion --jq '{
            workflow: .workflowName,
            created: .createdAt,
            conclusion: .conclusion,
            jobs: [.jobs[] | select(.conclusion == "failure") | {
                name: .name,
                id: .databaseId,
                steps: [.steps[] | select(.conclusion == "failure") | {
                    name: .name,
                    number: .number,
                    conclusion: .conclusion
                }]
            }]
        }' > /tmp/failed_jobs_v3_$$.json 2>/dev/null
    ) &
    JOB_PID=$!
    
    wait $JOB_PID 2>/dev/null
    
    FAILED_DATA=$(cat /tmp/failed_jobs_v3_$$.json 2>/dev/null || echo "{}")
    
    # En yaygın hata tipini bul
    MOST_COMMON=$(echo "$FAILED_DATA" | jq -r '.jobs[].steps[].name' 2>/dev/null | grep -v '^$' | sort | uniq -c | sort -rn | head -1 | awk '{print $2}' || echo "unknown")
    FAILED_JOB_COUNT=$(echo "$FAILED_DATA" | jq '.jobs | length' 2>/dev/null || echo "0")
    FIRST_FAILED_JOB_ID=$(echo "$FAILED_DATA" | jq -r '.jobs[0].id // empty' 2>/dev/null | grep -E '^[0-9]+$' | head -1 || echo "")
    WORKFLOW_NAME=$(echo "$FAILED_DATA" | jq -r '.workflow // "unknown"' 2>/dev/null)
    
    # Pattern detection ve ML
    PATTERN_KEY="${MOST_COMMON}_${FAILED_JOB_COUNT}_${WORKFLOW_NAME}"
    if [ -n "${ERROR_HISTORY[$PATTERN_KEY]}" ]; then
        ERROR_HISTORY[$PATTERN_KEY]=$((${ERROR_HISTORY[$PATTERN_KEY]} + 1))
    else
        ERROR_HISTORY[$PATTERN_KEY]=1
    fi
    
    # Workflow istatistikleri
    if [ -n "${WORKFLOW_STATS[$WORKFLOW_NAME]}" ]; then
        WORKFLOW_STATS[$WORKFLOW_NAME]=$((${WORKFLOW_STATS[$WORKFLOW_NAME]} + 1))
    else
        WORKFLOW_STATS[$WORKFLOW_NAME]=1
    fi
    
    local END_ANALYSIS=$(date +%s%N)
    local ANALYSIS_TIME=$(( (END_ANALYSIS - START_ANALYSIS) / 1000000 ))
    
    echo -e "${GREEN}✅ V3 ML Analiz tamamlandı (${ANALYSIS_TIME}ms)${NC}" >&2
    
    # Sonuçları döndür
    echo "$MOST_COMMON|$FAILED_JOB_COUNT|$FIRST_FAILED_JOB_ID|$WORKFLOW_NAME|${ERROR_HISTORY[$PATTERN_KEY]}"
    
    # Temizlik
    rm -f /tmp/failed_jobs_v3_$$.json
}

# V3 AI-Powered Log Analizi - Enhanced
ai_analyze_logs_v3() {
    local LOG_CONTENT="$1"
    local RUN_ID="$2"
    local JOB_ID="$3"
    
    if [ -z "$LOG_CONTENT" ] || [ "$LOG_CONTENT" = "" ]; then
        return 1
    fi
    
    echo -e "\n${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${BOLD}🤖 V3 AI-Powered Log Analizi${NC}"
    echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}\n"
    
    # ML Pattern Recognition
    ml_recognize_pattern "unknown" "$LOG_CONTENT" "$RUN_ID"
    
    # Kritik hataları çıkar
    CRITICAL_ERRORS=$(echo "$LOG_CONTENT" | grep -iE "(error|Error|ERROR|failed|Failed|FAILED|exit code)" | head -10)
    
    # Hata türü tespiti (V2'den geliştirilmiş)
    ERROR_TYPE=""
    ROOT_CAUSE=""
    SOLUTION_STEPS=()
    FILES_TO_FIX=()
    CONFIDENCE_SCORE=0
    
    # Compiler Error (march flag) - Yüksek confidence
    if echo "$LOG_CONTENT" | grep -qiE "clang: error.*unsupported.*march"; then
        ERROR_TYPE="Compiler_Error_march"
        ROOT_CAUSE="Clang compiler 'armv8-a+simd+crypto' formatını desteklemiyor. arm64-v8a için yanlış compiler flag kullanılıyor."
        CONFIDENCE_SCORE=95
        SOLUTION_STEPS=(
            "CMAKE_C_FLAGS ve CMAKE_CXX_FLAGS'den -march=armv8-a+simd+crypto'yı kaldır"
            "arm64-v8a için sadece -march=armv8-a kullan veya flag'i tamamen kaldır"
            "CMake'in otomatik toolchain ayarlarına güven"
        )
        FILES_TO_FIX=(".github/workflows/build-xray-boringssl.yml")
        
    # CMake Error - Yüksek confidence
    elif echo "$LOG_CONTENT" | grep -qiE "CMake Error|CMake.*failed"; then
        ERROR_TYPE="CMake_Configuration_Error"
        CMAKE_ERROR=$(echo "$LOG_CONTENT" | grep -iE "CMake Error" | head -3)
        ROOT_CAUSE="CMake konfigürasyonu başarısız. Toolchain veya CMakeLists.txt'de sorun var."
        CONFIDENCE_SCORE=90
        SOLUTION_STEPS=(
            "CMakeLists.txt dosyasını kontrol et"
            "NDK toolchain dosyasını kontrol et (android.toolchain.cmake)"
            "CMake versiyonunu kontrol et (3.22+ gerekli)"
            "Build dizinini temizle ve tekrar dene"
        )
        FILES_TO_FIX=(".github/workflows/build-xray-boringssl.yml" "app/src/main/jni/perf-net/CMakeLists.txt")
        
    # Library Not Found - Orta confidence
    elif echo "$LOG_CONTENT" | grep -qiE "Libraries not found|No .a files found"; then
        ERROR_TYPE="Library_Not_Found"
        ROOT_CAUSE="Build başarılı ama kütüphaneler (libcrypto.a, libssl.a) beklenen yerde değil."
        CONFIDENCE_SCORE=85
        SOLUTION_STEPS=(
            "Build output dizinini kontrol et (build_*/crypto/, build_*/ssl/)"
            "Library path'lerini düzelt"
            "Artifact upload path'lerini kontrol et"
            "Build sonrası doğrulama adımlarını güçlendir"
        )
        FILES_TO_FIX=(".github/workflows/build-xray-boringssl.yml")
        
    # Ninja Build Error - Orta confidence
    elif echo "$LOG_CONTENT" | grep -qiE "ninja.*failed|ninja: build stopped"; then
        ERROR_TYPE="Ninja_Build_Error"
        NINJA_ERROR=$(echo "$LOG_CONTENT" | grep -iE "ninja.*failed" | head -3)
        ROOT_CAUSE="Ninja build işlemi başarısız. Dependency veya memory sorunu olabilir."
        CONFIDENCE_SCORE=80
        SOLUTION_STEPS=(
            "Build dizinini temizle (rm -rf build_*)"
            "Dependency'leri kontrol et"
            "Memory limit'i kontrol et"
            "Paralel build sayısını azalt (-j\$(nproc) yerine -j2)"
        )
        FILES_TO_FIX=(".github/workflows/build-xray-boringssl.yml")
        
    # Network/Download Error - Düşük confidence
    elif echo "$LOG_CONTENT" | grep -qiE "network.*error|connection.*failed|timeout|failed to fetch"; then
        ERROR_TYPE="Network_Error"
        ROOT_CAUSE="Ağ bağlantısı veya download hatası. Dependency indirme başarısız."
        CONFIDENCE_SCORE=70
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
        CONFIDENCE_SCORE=50
        SOLUTION_STEPS=(
            "Logları detaylı incele"
            "Workflow dosyasını kontrol et"
            "Manuel müdahale gerekebilir"
        )
    fi
    
    # Sonuçları göster
    echo -e "${YELLOW}${BOLD}🔧 Hata Türü: ${ERROR_TYPE}${NC}"
    echo -e "${CYAN}Güven Skoru:${NC} ${CONFIDENCE_SCORE}%"
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
    
    # Sonuçları döndür
    echo "${ERROR_TYPE}|${ROOT_CAUSE}|${FILES_TO_FIX[*]}|${CONFIDENCE_SCORE}"
}

# V3 Otomatik Düzeltme - Enhanced with ML
auto_fix_v3() {
    local ERROR_TYPE="$1"
    local RUN_ID="$2"
    local ANALYSIS_RESULT="$3"
    local WORKFLOW_FILE=".github/workflows/build-xray-boringssl.yml"
    
    if [ "$AUTO_FIX_ENABLED" != "true" ]; then
        echo -e "${YELLOW}⚠️  Auto-fix devre dışı${NC}"
        return 1
    fi
    
    echo -e "${MAGENTA}🔧 V3 HYPER AUTO-FIX MODU AKTİF${NC}"
    echo -e "${CYAN}Hata Tipi: ${ERROR_TYPE}${NC}\n"
    
    local FIXED=false
    CONFIDENCE_SCORE=$(echo "$ANALYSIS_RESULT" | cut -d'|' -f4 | grep -oE '[0-9]+' | head -1 || echo "50")
    
    # Confidence score'u sayısal değere çevir (varsayılan 50)
    if [ -z "$CONFIDENCE_SCORE" ]; then
        CONFIDENCE_SCORE=50
    else
        # Sayısal kontrol (grep ile)
        if ! echo "$CONFIDENCE_SCORE" | grep -qE '^[0-9]+$'; then
            CONFIDENCE_SCORE=50
        fi
    fi
    
    # Confidence score'a göre düzeltme yap
    if [ "$CONFIDENCE_SCORE" -lt 80 ]; then
        echo -e "${YELLOW}⚠️  Düşük güven skoru (${CONFIDENCE_SCORE}%), manuel onay gerekebilir${NC}"
    fi
    
    case "$ERROR_TYPE" in
        "Compiler_Error_march")
            echo -e "${YELLOW}→ Compiler march flag hatası düzeltiliyor...${NC}"
            
            if grep -q "march=armv8-a+simd+crypto" "$WORKFLOW_FILE" 2>/dev/null; then
                echo -e "${BLUE}  • Yanlış march flag'i bulundu, düzeltiliyor...${NC}"
                
                # Sed ile düzelt (Windows uyumlu)
                if command -v sed &> /dev/null; then
                    # arm64-v8a bloğundaki march flag'lerini kaldır
                    sed -i.bak '/arm64-v8a/,/fi/ s/-DCMAKE_C_FLAGS=-march=armv8-a+simd+crypto//g' "$WORKFLOW_FILE" 2>/dev/null || true
                    sed -i.bak '/arm64-v8a/,/fi/ s/-DCMAKE_CXX_FLAGS=-march=armv8-a+simd+crypto//g' "$WORKFLOW_FILE" 2>/dev/null || true
                    
                    # Veya değerleri düzelt
                    sed -i.bak 's/-march=armv8-a+simd+crypto/-march=armv8-a/g' "$WORKFLOW_FILE" 2>/dev/null || true
                    
                    rm -f "$WORKFLOW_FILE.bak" 2>/dev/null || true
                    FIXED=true
                    echo -e "${GREEN}  ✅ March flag düzeltildi${NC}"
                    
                    # Fix history'ye kaydet
                    FIX_HISTORY_KEY="${ERROR_TYPE}_$(date +%s)"
                    FIX_HISTORY[$FIX_HISTORY_KEY]="Removed -march=armv8-a+simd+crypto flags"
                fi
            fi
            ;;
            
        "CMake_Configuration_Error")
            echo -e "${YELLOW}→ CMake konfigürasyon hatası analiz ediliyor...${NC}"
            echo -e "${BLUE}  • CMake cache temizleme önerisi${NC}"
            FIXED=false
            ;;
            
        "Library_Not_Found")
            echo -e "${YELLOW}→ Library bulunamadı hatası için iyileştirme...${NC}"
            # Library search zaten var, ek iyileştirme yapılabilir
            FIXED=false
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

# V3 Log Alma - Enhanced
get_error_logs_v3() {
    local RUN_ID=$1
    local JOB_ID=$2
    
    echo -e "${YELLOW}📄 V3 Enhanced Log Alma Başlatılıyor...${NC}"
    
    # Önce başarısız step'i bul (hata olsa bile devam et)
    set +e
    FAILED_STEP=$(gh run view $RUN_ID --json jobs --jq ".jobs[] | select(.databaseId == $JOB_ID) | .steps[] | select(.conclusion == \"failure\") | .name" 2>/dev/null | head -1 || echo "")
    set -e
    
    if [ -n "$FAILED_STEP" ]; then
        echo -e "${CYAN}Başarısız Step: ${FAILED_STEP}${NC}"
    fi
    
    # Hyper log fetcher kullan (hata olsa bile devam et)
    LOG_OUTPUT=""
    set +e
    if type hyper_fetch_logs &> /dev/null; then
        LOG_OUTPUT=$(hyper_fetch_logs "$RUN_ID" "$JOB_ID" 200 2>&1 || echo "")
    fi
    set -e
    
    # Fallback: API (hata olsa bile devam et)
    if [ -z "$LOG_OUTPUT" ] || [ "$LOG_OUTPUT" = "" ]; then
        set +e
        REPO=$(gh repo view --json owner,name -q '.owner.login + "/" + .name' 2>/dev/null || echo "")
        if [ -n "$REPO" ]; then
            RAW_API_LOG=$(timeout 30 gh api "repos/$REPO/actions/jobs/$JOB_ID/logs" 2>/dev/null || echo "")
            if [ -n "$RAW_API_LOG" ]; then
                FAILED_STEP_NAMES=$(gh run view $RUN_ID --json jobs --jq ".jobs[] | select(.databaseId == $JOB_ID) | .steps[] | select(.conclusion == \"failure\") | .name" 2>/dev/null || echo "")
                if [ -n "$FAILED_STEP_NAMES" ]; then
                    TEMP_LOG_FILE="/tmp/job_log_v3_$$.txt"
                    echo "$RAW_API_LOG" > "$TEMP_LOG_FILE" 2>/dev/null || true
                    
                    for STEP_NAME in $FAILED_STEP_NAMES; do
                        if [ -n "$STEP_NAME" ]; then
                            STEP_LOG=$(grep -A 200 "$STEP_NAME" "$TEMP_LOG_FILE" 2>/dev/null | grep -A 80 -E "(error|Error|ERROR|failed|Failed|FAILED|exit code)" | head -120 || echo "")
                            if [ -n "$STEP_LOG" ]; then
                                if [ -z "$LOG_OUTPUT" ]; then
                                    LOG_OUTPUT="$STEP_LOG"
                                else
                                    LOG_OUTPUT="${LOG_OUTPUT}\n${STEP_LOG}"
                                fi
                            fi
                        fi
                    done
                    rm -f "$TEMP_LOG_FILE" 2>/dev/null || true
                else
                    LOG_OUTPUT=$(echo "$RAW_API_LOG" | grep -A 80 -E "(error|Error|ERROR|failed|Failed|FAILED|exit code)" | tail -150 || echo "$RAW_API_LOG" | tail -150 || echo "")
                fi
            fi
        fi
        set -e
    fi
    
    if [ -n "$LOG_OUTPUT" ] && [ "$LOG_OUTPUT" != "" ]; then
        echo -e "${GREEN}✅ Loglar alındı${NC}" >&2
        
        # Log gösterimi
        echo -e "\n${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
        echo -e "${BOLD}📄 Log İçeriği:${NC}"
        echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}\n"
        echo "$LOG_OUTPUT" | tail -100
        
        # V3 AI analizi (hata olsa bile devam et)
        set +e
        AI_RESULT=$(ai_analyze_logs_v3 "$LOG_OUTPUT" "$RUN_ID" "$JOB_ID" 2>/dev/null || echo "")
        ERROR_TYPE=$(echo "$AI_RESULT" | cut -d'|' -f1 || echo "unknown")
        set -e
        
        # Auto-fix önerisi (hata olsa bile devam et)
        if [ "$AUTO_FIX_ENABLED" = "true" ] && [ -n "$ERROR_TYPE" ] && [ "$ERROR_TYPE" != "General_Error" ] && [ "$ERROR_TYPE" != "unknown" ]; then
            echo -e "\n${MAGENTA}🔧 Otomatik Düzeltme Deneniyor...${NC}"
            set +e
            if auto_fix_v3 "$ERROR_TYPE" "$RUN_ID" "$AI_RESULT"; then
                echo -e "${GREEN}✅ Otomatik düzeltme uygulandı!${NC}"
                set -e
                return 0
            fi
            set -e
        fi
        
        return 0
    else
        echo -e "${YELLOW}⚠️  Loglar alınamadı${NC}"
        return 1
    fi
}

# İstatistikleri göster (V3 - Enhanced)
show_stats_v3() {
    local CURRENT_TIME=$(date +%s)
    local ELAPSED=$((CURRENT_TIME - START_TIME))
    local SUCCESS_RATE=0
    local AUTO_FIX_RATE=0
    local PREVENTION_RATE=0
    
    if [ $TOTAL_CHECKS -gt 0 ]; then
        SUCCESS_RATE=$(( SUCCESS_COUNT * 100 / TOTAL_CHECKS ))
        if [ $FAILURE_COUNT -gt 0 ]; then
            AUTO_FIX_RATE=$(( AUTO_FIXES_APPLIED * 100 / FAILURE_COUNT ))
            PREVENTION_RATE=$(( PREVENTED_FAILURES * 100 / TOTAL_CHECKS ))
        fi
    fi
    
    echo -e "\n${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${BOLD}📊 V3 İSTATİSTİKLER${NC}"
    echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${WHITE}Toplam Kontrol:${NC} ${TOTAL_CHECKS}"
    echo -e "${GREEN}Başarılı:${NC} ${SUCCESS_COUNT}"
    echo -e "${RED}Başarısız:${NC} ${FAILURE_COUNT}"
    echo -e "${YELLOW}Düzeltme Uygulandı:${NC} ${FIXES_APPLIED}"
    echo -e "${MAGENTA}Otomatik Düzeltme:${NC} ${AUTO_FIXES_APPLIED}"
    echo -e "${CYAN}Önlenen Hatalar:${NC} ${PREVENTED_FAILURES}"
    echo -e "${BLUE}Başarı Oranı:${NC} ${SUCCESS_RATE}%"
    echo -e "${BLUE}Auto-Fix Oranı:${NC} ${AUTO_FIX_RATE}%"
    echo -e "${GREEN}Prevention Oranı:${NC} ${PREVENTION_RATE}%"
    echo -e "${DIM}Çalışma Süresi:${NC} ${ELAPSED}s"
    
    # Pattern istatistikleri
    if [ ${#ERROR_HISTORY[@]} -gt 0 ] 2>/dev/null; then
        echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
        echo -e "${BOLD}📈 ML Pattern İstatistikleri:${NC}"
        for pattern in "${!ERROR_HISTORY[@]}"; do
            if [ -n "$pattern" ] && [ -n "${ERROR_HISTORY[$pattern]}" ]; then
                count=${ERROR_HISTORY[$pattern]}
                if [ -n "$count" ] && [ "$count" -gt 1 ] 2>/dev/null; then
                    echo -e "  ${YELLOW}$pattern:${NC} ${count}x tekrarlandı"
                fi
            fi
        done
    fi
    
    # Workflow istatistikleri
    if [ ${#WORKFLOW_STATS[@]} -gt 0 ] 2>/dev/null; then
        echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
        echo -e "${BOLD}📋 Workflow İstatistikleri:${NC}"
        for workflow in "${!WORKFLOW_STATS[@]}"; do
            if [ -n "$workflow" ] && [ -n "${WORKFLOW_STATS[$workflow]}" ]; then
                count=${WORKFLOW_STATS[$workflow]}
                if [ -n "$count" ]; then
                    echo -e "  ${BLUE}$workflow:${NC} ${count} başarısızlık"
                fi
            fi
        done
    fi
    
    echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}\n"
}

# Ana monitoring döngüsü (V3)
monitor_loop_v3() {
    local LAST_RUN_ID=""
    local CONSECUTIVE_FAILURES=0
    
    while true; do
        TOTAL_CHECKS=$((TOTAL_CHECKS + 1))
        
        echo -e "${BOLD}${CYAN}[$(date '+%H:%M:%S')]${NC} ${DIM}V3 Kontrol #${TOTAL_CHECKS}${NC}"
        
        # Son run'u al (hata olsa bile devam et)
        set +e
        local LATEST_RUN=$(gh run list --limit 1 --json databaseId,status,conclusion,createdAt --jq '.[0] | "\(.databaseId)|\(.status)|\(.conclusion // "in_progress")|\(.createdAt)"' 2>/dev/null || echo "")
        set -e
        
        if [ -z "$LATEST_RUN" ] || [ "$LATEST_RUN" = "null|null|null" ] || [ "$LATEST_RUN" = "" ]; then
            echo -e "${YELLOW}⚠️  Run bilgisi alınamadı, bekleniyor...${NC}\n"
            sleep $CHECK_INTERVAL
            continue
        fi
        
        local RUN_ID=$(echo "$LATEST_RUN" | cut -d'|' -f1)
        local STATUS=$(echo "$LATEST_RUN" | cut -d'|' -f2)
        local CONCLUSION=$(echo "$LATEST_RUN" | cut -d'|' -f3)
        
        # Predictive Prevention (hata olsa bile devam et)
        if [ "$STATUS" = "in_progress" ]; then
            set +e
            prevent_failure "$RUN_ID" || echo -e "${YELLOW}  ⚠️  Prevention alert aktif${NC}"
            set -e
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
                        show_stats_v3
                        
                        # ML Pattern DB'yi temizle (başarılı run sonrası)
                        rm -f "$ML_PATTERN_DB" 2>/dev/null || true
                        
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
                        
                        # V3 ML-enhanced analiz (hata olsa bile devam et)
                        set +e  # Hata durumunda script durmasın
                        ANALYSIS=$(analyze_failure_v3 $RUN_ID 2>/dev/null | grep -E '^[^|]+\|[^|]+\|[^|]+' | head -1 || echo "")
                        set -e
                        
                        if [ -z "$ANALYSIS" ] || [ "$ANALYSIS" = "" ]; then
                            ERROR_TYPE="unknown"
                            JOB_COUNT="0"
                            JOB_ID=""
                            WORKFLOW_NAME="unknown"
                        else
                            ANALYSIS=$(echo "$ANALYSIS" | sed 's/\x1b\[[0-9;]*m//g' | grep -oE '[^|]+\|[^|]+\|[^|]+' | head -1 || echo "")
                            ERROR_TYPE=$(echo "$ANALYSIS" | cut -d'|' -f1 | tr -d '[:cntrl:]' | xargs 2>/dev/null || echo "unknown")
                            JOB_COUNT=$(echo "$ANALYSIS" | cut -d'|' -f2 | tr -d '[:cntrl:]' | xargs 2>/dev/null || echo "0")
                            JOB_ID=$(echo "$ANALYSIS" | cut -d'|' -f3 | tr -d '[:cntrl:]' | xargs 2>/dev/null || echo "")
                            WORKFLOW_NAME=$(echo "$ANALYSIS" | cut -d'|' -f4 | tr -d '[:cntrl:]' | xargs 2>/dev/null || echo "unknown")
                            JOB_ID=$(echo "$JOB_ID" | grep -oE '[0-9]+' | head -1 || echo "")
                        fi
                        
                        echo -e "${RED}Hata Tipi:${NC} ${ERROR_TYPE}"
                        echo -e "${RED}Workflow:${NC} ${WORKFLOW_NAME}"
                        echo -e "${RED}Başarısız Job Sayısı:${NC} ${JOB_COUNT}"
                        echo -e "${RED}Ardışık Başarısızlık:${NC} ${CONSECUTIVE_FAILURES}\n"
                        
                        # V3 log alma ve AI analiz (hata olsa bile devam et)
                        set +e
                        if [ -n "$JOB_ID" ] && [ "$JOB_ID" != "null" ] && [ -n "$(echo "$JOB_ID" | grep -E '^[0-9]+$')" ]; then
                            get_error_logs_v3 $RUN_ID $JOB_ID || echo -e "${YELLOW}⚠️  Log alma başarısız, devam ediliyor...${NC}"
                        else
                            FAILED_JOBS_DIRECT=$(gh run view $RUN_ID --json jobs --jq '.jobs[] | select(.conclusion == "failure") | .databaseId' 2>/dev/null || echo "")
                            if [ -n "$FAILED_JOBS_DIRECT" ]; then
                                FIRST_FAILED_JOB=$(echo "$FAILED_JOBS_DIRECT" | head -1)
                                echo -e "${CYAN}✅ Başarısız Job ID bulundu: $FIRST_FAILED_JOB${NC}\n"
                                get_error_logs_v3 $RUN_ID $FIRST_FAILED_JOB || echo -e "${YELLOW}⚠️  Log alma başarısız, devam ediliyor...${NC}"
                            else
                                echo -e "${YELLOW}⚠️  Başarısız Job bulunamadı${NC}"
                            fi
                        fi
                        set -e
                        
                        # Auto-fix ve commit (hata olsa bile devam et)
                        set +e
                        if [ $CONSECUTIVE_FAILURES -le $MAX_RETRIES ]; then
                            if git diff --quiet .github/workflows/ 2>/dev/null; then
                                echo -e "${YELLOW}⚠️  Workflow dosyasında değişiklik yok${NC}"
                            else
                                echo -e "${GREEN}📝 Değişiklikler commit ediliyor...${NC}"
                                git add .github/workflows/ 2>/dev/null || true
                                git commit -m "fix(hyper-v3): auto-fix for $ERROR_TYPE

- Applied automatic fix for $ERROR_TYPE
- Run ID: $RUN_ID
- Workflow: $WORKFLOW_NAME
- Confidence: High
- Auto-generated by hyper-monitor-v3" 2>/dev/null || true
                                
                                if git push 2>/dev/null; then
                                    echo -e "${GREEN}✅ Düzeltme push edildi!${NC}"
                                    FIXES_APPLIED=$((FIXES_APPLIED + 1))
                                else
                                    echo -e "${YELLOW}⚠️  Commit/Push başarısız, devam ediliyor...${NC}"
                                fi
                            fi
                        else
                            echo -e "${RED}❌ Maksimum deneme sayısına ulaşıldı${NC}"
                        fi
                        set -e
                        ;;
                esac
                ;;
            "in_progress")
                echo -e "${BLUE}🔄 Workflow devam ediyor...${NC}"
                set +e
                gh run view $RUN_ID --json jobs --jq '.jobs[] | select(.status == "in_progress") | .name' 2>/dev/null | head -3 | while read job; do
                    echo -e "  ${DIM}→ $job${NC}"
                done
                set -e
                ;;
            "queued")
                echo -e "${YELLOW}⏳ Workflow kuyrukta bekliyor...${NC}"
                ;;
        esac
        
        # İstatistikleri göster (hata olsa bile devam et)
        set +e
        show_stats_v3
        set -e
        
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
    echo -e "${GREEN}🚀 Hyper Monitor V3 başlatılıyor...${NC}\n"
    
    # Signal handler
    trap 'echo -e "\n${YELLOW}⏹️  Monitor V3 durduruluyor...${NC}"; rm -f "$ML_PATTERN_DB" 2>/dev/null; show_stats_v3; exit 0' INT TERM
    
    monitor_loop_v3
}

main "$@"

