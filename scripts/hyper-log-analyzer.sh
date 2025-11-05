#!/bin/bash

# Hyper Log Analyzer - GitHub Actions loglarını analiz eder ve hata türlerini tespit eder

set -euo pipefail

# Renk kodları
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
MAGENTA='\033[0;35m'
DIM='\033[0;2m'
NC='\033[0m'
BOLD='\033[1m'

# Log analizi fonksiyonu
analyze_logs() {
    local LOG_CONTENT="$1"
    
    if [ -z "$LOG_CONTENT" ] || [ "$LOG_CONTENT" = "" ]; then
        echo "NO_LOGS|0|0|"
        return 1
    fi
    
    # Hata türleri ve pattern'leri
    declare -A ERROR_PATTERNS=(
        ["CMake_Error"]="CMake Error|CMake.*failed|cmake.*error"
        ["Compiler_Error"]="clang: error|gcc: error|compiler.*error|unsupported argument"
        ["Build_Error"]="ninja.*failed|build.*failed|make.*error|linker.*error"
        ["Library_Not_Found"]="Libraries not found|No .a files found|lib.*not found"
        ["Permission_Error"]="Permission denied|access denied|EACCES"
        ["Network_Error"]="network.*error|connection.*failed|timeout|failed to fetch"
        ["Submodule_Error"]="submodule.*failed|git submodule.*error|No url found for submodule"
        ["Artifact_Error"]="artifact.*not found|upload.*failed|download.*failed"
        ["Memory_Error"]="out of memory|killed|SIGKILL|allocation.*failed"
        ["Configuration_Error"]="configuration.*failed|invalid.*configuration|CMAKE.*error"
    )
    
    # Hata sayıları
    declare -A ERROR_COUNTS
    
    # Her hata türü için say
    for ERROR_TYPE in "${!ERROR_PATTERNS[@]}"; do
        PATTERN="${ERROR_PATTERNS[$ERROR_TYPE]}"
        COUNT=$(echo "$LOG_CONTENT" | grep -iE "$PATTERN" | wc -l || echo "0")
        ERROR_COUNTS["$ERROR_TYPE"]=$COUNT
    done
    
    # En yaygın hata türünü bul
    MOST_COMMON=""
    MAX_COUNT=0
    for ERROR_TYPE in "${!ERROR_COUNTS[@]}"; do
        COUNT=${ERROR_COUNTS[$ERROR_TYPE]}
        if [ "$COUNT" -gt "$MAX_COUNT" ]; then
            MAX_COUNT=$COUNT
            MOST_COMMON="$ERROR_TYPE"
        fi
    done
    
    # Toplam hata sayısı
    TOTAL_ERRORS=$(echo "$LOG_CONTENT" | grep -iE "(error|Error|ERROR|failed|Failed|FAILED)" | wc -l || echo "0")
    
    # Kritik hata mesajlarını çıkar
    CRITICAL_ERRORS=$(echo "$LOG_CONTENT" | grep -iE "(clang: error|CMake Error|ninja.*failed|exit code [1-9])" | head -5 | tr '\n' '; ' || echo "")
    
    # Sonuç formatı: ERROR_TYPE|TOTAL_ERRORS|MAX_COUNT|CRITICAL_ERRORS
    echo "${MOST_COMMON}|${TOTAL_ERRORS}|${MAX_COUNT}|${CRITICAL_ERRORS}"
}

# Detaylı log analizi ve öneriler
analyze_logs_detailed() {
    local LOG_CONTENT="$1"
    local RUN_ID="$2"
    local JOB_ID="$3"
    
    echo -e "${CYAN}🔍 Hyper Log Analizi Başlatılıyor...${NC}\n"
    
    if [ -z "$LOG_CONTENT" ] || [ "$LOG_CONTENT" = "" ]; then
        echo -e "${YELLOW}⚠️  Log içeriği boş${NC}"
        return 1
    fi
    
    # Analiz sonuçları
    ANALYSIS=$(analyze_logs "$LOG_CONTENT")
    ERROR_TYPE=$(echo "$ANALYSIS" | cut -d'|' -f1)
    TOTAL_ERRORS=$(echo "$ANALYSIS" | cut -d'|' -f2)
    MAX_COUNT=$(echo "$ANALYSIS" | cut -d'|' -f3)
    CRITICAL_ERRORS=$(echo "$ANALYSIS" | cut -d'|' -f4)
    
    # Sonuçları göster
    echo -e "${BOLD}📊 Analiz Sonuçları:${NC}"
    echo -e "${DIM}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${BLUE}En Yaygın Hata Türü:${NC} ${RED}$ERROR_TYPE${NC}"
    echo -e "${BLUE}Toplam Hata Sayısı:${NC} ${RED}$TOTAL_ERRORS${NC}"
    echo -e "${BLUE}En Yaygın Hata Tekrarı:${NC} ${RED}$MAX_COUNT${NC}"
    echo -e "${DIM}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}\n"
    
    # Hata türüne göre detaylı analiz
    case "$ERROR_TYPE" in
        "CMake_Error")
            echo -e "${YELLOW}🔧 CMake Hatası Tespit Edildi${NC}"
            CMake_Error=$(echo "$LOG_CONTENT" | grep -iE "CMake Error|cmake.*failed" | head -3)
            echo -e "${RED}Hata Detayları:${NC}"
            echo "$CMake_Error" | sed 's/^/  /'
            echo ""
            echo -e "${CYAN}💡 Öneriler:${NC}"
            echo "  1. CMake versiyonunu kontrol et"
            echo "  2. CMakeLists.txt dosyasını kontrol et"
            echo "  3. NDK toolchain dosyasını kontrol et"
            ;;
        "Compiler_Error")
            echo -e "${YELLOW}🔧 Compiler Hatası Tespit Edildi${NC}"
            COMPILER_ERROR=$(echo "$LOG_CONTENT" | grep -iE "clang: error|gcc: error|unsupported argument" | head -3)
            echo -e "${RED}Hata Detayları:${NC}"
            echo "$COMPILER_ERROR" | sed 's/^/  /'
            echo ""
            echo -e "${CYAN}💡 Öneriler:${NC}"
            echo "  1. Compiler flag'lerini kontrol et"
            echo "  2. ABI-specific flag'leri düzelt"
            echo "  3. Toolchain uyumluluğunu kontrol et"
            ;;
        "Build_Error")
            echo -e "${YELLOW}🔧 Build Hatası Tespit Edildi${NC}"
            BUILD_ERROR=$(echo "$LOG_CONTENT" | grep -iE "ninja.*failed|build.*failed" | head -3)
            echo -e "${RED}Hata Detayları:${NC}"
            echo "$BUILD_ERROR" | sed 's/^/  /'
            echo ""
            echo -e "${CYAN}💡 Öneriler:${NC}"
            echo "  1. Build dizinini temizle"
            echo "  2. Dependency'leri kontrol et"
            echo "  3. Memory limit'i kontrol et"
            ;;
        "Library_Not_Found")
            echo -e "${YELLOW}🔧 Kütüphane Bulunamadı Hatası${NC}"
            LIB_ERROR=$(echo "$LOG_CONTENT" | grep -iE "Libraries not found|No .a files" | head -3)
            echo -e "${RED}Hata Detayları:${NC}"
            echo "$LIB_ERROR" | sed 's/^/  /'
            echo ""
            echo -e "${CYAN}💡 Öneriler:${NC}"
            echo "  1. Build'in başarıyla tamamlandığını kontrol et"
            echo "  2. Library path'lerini kontrol et"
            echo "  3. Build output dizinini kontrol et"
            ;;
        *)
            if [ -n "$CRITICAL_ERRORS" ] && [ "$CRITICAL_ERRORS" != "" ]; then
                echo -e "${YELLOW}🔧 Kritik Hatalar:${NC}"
                echo "$CRITICAL_ERRORS" | tr ';' '\n' | sed 's/^/  /'
            fi
            ;;
    esac
    
    echo ""
    echo -e "${DIM}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    
    # Sonuçları döndür
    echo "$ERROR_TYPE|$TOTAL_ERRORS|$MAX_COUNT|$CRITICAL_ERRORS"
}

# Hızlı log analizi (sadece sonuç)
quick_analyze_logs() {
    local LOG_CONTENT="$1"
    
    if [ -z "$LOG_CONTENT" ] || [ "$LOG_CONTENT" = "" ]; then
        echo "UNKNOWN|0|0|"
        return 1
    fi
    
    # En kritik hatayı bul
    if echo "$LOG_CONTENT" | grep -qiE "clang: error.*unsupported.*march"; then
        echo "Compiler_Error|1|1|clang: unsupported march argument"
    elif echo "$LOG_CONTENT" | grep -qiE "CMake Error"; then
        echo "CMake_Error|1|1|CMake configuration failed"
    elif echo "$LOG_CONTENT" | grep -qiE "Libraries not found"; then
        echo "Library_Not_Found|1|1|Libraries not found"
    elif echo "$LOG_CONTENT" | grep -qiE "ninja.*failed"; then
        echo "Build_Error|1|1|ninja build failed"
    else
        ERROR_COUNT=$(echo "$LOG_CONTENT" | grep -iE "(error|failed)" | wc -l || echo "0")
        echo "General_Error|${ERROR_COUNT}|1|"
    fi
}

# Ana fonksiyon
main() {
    if [ $# -lt 1 ]; then
        echo "Kullanım: $0 <LOG_CONTENT> [RUN_ID] [JOB_ID]"
        echo "Örnek: $0 \"\$(cat log.txt)\" 19119465358 54636518577"
        exit 1
    fi
    
    if [ $# -eq 3 ]; then
        analyze_logs_detailed "$1" "$2" "$3"
    else
        analyze_logs "$1"
    fi
}

# Script doğrudan çalıştırılırsa
if [ "${BASH_SOURCE[0]}" = "${0}" ]; then
    main "$@"
fi

