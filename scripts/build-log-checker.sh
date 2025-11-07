#!/bin/bash

# Build Log Checker - Build log'larını analiz eder ve warning/error'ları raporlar
# GitHub Actions workflow'larında kullanım için tasarlanmıştır

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

# Log dosyası yolu (opsiyonel)
LOG_FILE="${1:-}"

# Analiz sonuçları
declare -A WARNING_COUNTS
declare -A ERROR_COUNTS
declare -A WARNING_TYPES
declare -A ERROR_TYPES

# Pattern'ler
WARNING_PATTERNS=(
    "warning:"
    "Warning:"
    "WARNING:"
    "deprecated"
    "Deprecated"
    "DEPRECATED"
    "unused"
    "Unused"
    "UNUSED"
    "format.*warning"
    "unknown escape sequence"
    "always.*true"
    "always.*false"
)

ERROR_PATTERNS=(
    "error:"
    "Error:"
    "ERROR:"
    "failed"
    "Failed"
    "FAILED"
    "fatal"
    "Fatal"
    "FATAL"
    "exit code [1-9]"
    "Build.*failed"
    "ninja.*failed"
    "cmake.*failed"
)

# Log içeriğini oku
if [ -n "$LOG_FILE" ] && [ -f "$LOG_FILE" ]; then
    LOG_CONTENT=$(cat "$LOG_FILE")
elif [ -n "$LOG_FILE" ]; then
    echo -e "${YELLOW}⚠️  Log dosyası bulunamadı: $LOG_FILE${NC}"
    echo -e "${CYAN}📋 Build log'larını stdin'den okuyorum...${NC}\n"
    LOG_CONTENT=$(cat)
else
    echo -e "${CYAN}📋 Build log'larını stdin'den okuyorum...${NC}\n"
    LOG_CONTENT=$(cat)
fi

if [ -z "$LOG_CONTENT" ] || [ "$LOG_CONTENT" = "" ]; then
    echo -e "${RED}❌ Log içeriği boş!${NC}"
    exit 1
fi

echo -e "${CYAN}${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${CYAN}${BOLD}🔍 Build Log Checker - Analiz Başlatılıyor...${NC}"
echo -e "${CYAN}${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}\n"

# Warning'leri analiz et
echo -e "${BLUE}📊 Warning Analizi...${NC}"
for pattern in "${WARNING_PATTERNS[@]}"; do
    COUNT=$(echo "$LOG_CONTENT" | grep -iE "$pattern" | wc -l 2>/dev/null | tr -d ' \n' || echo "0")
    COUNT=${COUNT:-0}
    if [ "$COUNT" -gt 0 ] 2>/dev/null; then
        WARNING_COUNTS["$pattern"]=$COUNT
        # İlk birkaç örneği kaydet
        EXAMPLES=$(echo "$LOG_CONTENT" | grep -iE "$pattern" | head -3 | tr '\n' '; ')
        WARNING_TYPES["$pattern"]="$EXAMPLES"
    fi
done

# Error'ları analiz et
echo -e "${BLUE}📊 Error Analizi...${NC}"
for pattern in "${ERROR_PATTERNS[@]}"; do
    COUNT=$(echo "$LOG_CONTENT" | grep -iE "$pattern" | wc -l 2>/dev/null | tr -d ' \n' || echo "0")
    COUNT=${COUNT:-0}
    if [ "$COUNT" -gt 0 ] 2>/dev/null; then
        ERROR_COUNTS["$pattern"]=$COUNT
        # İlk birkaç örneği kaydet
        EXAMPLES=$(echo "$LOG_CONTENT" | grep -iE "$pattern" | head -3 | tr '\n' '; ')
        ERROR_TYPES["$pattern"]="$EXAMPLES"
    fi
done

# Toplam sayıları hesapla
TOTAL_WARNINGS=0
TOTAL_ERRORS=0

for count in "${WARNING_COUNTS[@]}"; do
    TOTAL_WARNINGS=$((TOTAL_WARNINGS + count))
done

for count in "${ERROR_COUNTS[@]}"; do
    TOTAL_ERRORS=$((TOTAL_ERRORS + count))
done

# Sonuçları göster
echo -e "\n${CYAN}${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${CYAN}${BOLD}📈 Analiz Sonuçları${NC}"
echo -e "${CYAN}${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}\n"

# Warning özeti
if [ "$TOTAL_WARNINGS" -gt 0 ]; then
    echo -e "${YELLOW}${BOLD}⚠️  Toplam Warning: $TOTAL_WARNINGS${NC}"
    echo -e "${DIM}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    for pattern in "${!WARNING_COUNTS[@]}"; do
        count=${WARNING_COUNTS[$pattern]}
        echo -e "${YELLOW}  • $pattern: $count${NC}"
        if [ -n "${WARNING_TYPES[$pattern]}" ]; then
            echo -e "${DIM}    Örnek: ${WARNING_TYPES[$pattern]}${NC}" | sed 's/; /\n    /g' | head -1
        fi
    done
    echo ""
else
    echo -e "${GREEN}✅ Warning bulunamadı${NC}\n"
fi

# Error özeti
if [ "$TOTAL_ERRORS" -gt 0 ]; then
    echo -e "${RED}${BOLD}❌ Toplam Error: $TOTAL_ERRORS${NC}"
    echo -e "${DIM}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    for pattern in "${!ERROR_COUNTS[@]}"; do
        count=${ERROR_COUNTS[$pattern]}
        echo -e "${RED}  • $pattern: $count${NC}"
        if [ -n "${ERROR_TYPES[$pattern]}" ]; then
            echo -e "${DIM}    Örnek: ${ERROR_TYPES[$pattern]}${NC}" | sed 's/; /\n    /g' | head -1
        fi
    done
    echo ""
else
    echo -e "${GREEN}✅ Error bulunamadı${NC}\n"
fi

# Özel pattern kontrolleri
echo -e "${CYAN}${BOLD}🔍 Özel Kontroller${NC}"
echo -e "${DIM}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}\n"

# C++ escape sequence warning
ESCAPE_WARNINGS=$(echo "$LOG_CONTENT" | grep -iE "unknown escape sequence" | wc -l 2>/dev/null | tr -d ' \n' || echo "0")
ESCAPE_WARNINGS=${ESCAPE_WARNINGS:-0}
if [ "$ESCAPE_WARNINGS" -gt 0 ] 2>/dev/null; then
    echo -e "${YELLOW}⚠️  C++ Escape Sequence Warning: $ESCAPE_WARNINGS${NC}"
    echo "$LOG_CONTENT" | grep -iE "unknown escape sequence" | head -3 | sed 's/^/    /'
    echo ""
fi

# Unused parameter warning
UNUSED_WARNINGS=$(echo "$LOG_CONTENT" | grep -iE "unused.*parameter" | wc -l 2>/dev/null | tr -d ' \n' || echo "0")
UNUSED_WARNINGS=${UNUSED_WARNINGS:-0}
if [ "$UNUSED_WARNINGS" -gt 0 ] 2>/dev/null; then
    echo -e "${YELLOW}⚠️  Unused Parameter Warning: $UNUSED_WARNINGS${NC}"
    echo "$LOG_CONTENT" | grep -iE "unused.*parameter" | head -3 | sed 's/^/    /'
    echo ""
fi

# Format specifier warning
FORMAT_WARNINGS=$(echo "$LOG_CONTENT" | grep -iE "format.*specifier|format.*warning" | wc -l 2>/dev/null | tr -d ' \n' || echo "0")
FORMAT_WARNINGS=${FORMAT_WARNINGS:-0}
if [ "$FORMAT_WARNINGS" -gt 0 ] 2>/dev/null; then
    echo -e "${YELLOW}⚠️  Format Specifier Warning: $FORMAT_WARNINGS${NC}"
    echo "$LOG_CONTENT" | grep -iE "format.*specifier|format.*warning" | head -3 | sed 's/^/    /'
    echo ""
fi

# Deprecation warning
DEPRECATION_WARNINGS=$(echo "$LOG_CONTENT" | grep -iE "deprecated|Deprecated" | wc -l 2>/dev/null | tr -d ' \n' || echo "0")
DEPRECATION_WARNINGS=${DEPRECATION_WARNINGS:-0}
if [ "$DEPRECATION_WARNINGS" -gt 0 ] 2>/dev/null; then
    echo -e "${YELLOW}⚠️  Deprecation Warning: $DEPRECATION_WARNINGS${NC}"
    echo "$LOG_CONTENT" | grep -iE "deprecated|Deprecated" | head -3 | sed 's/^/    /'
    echo ""
fi

# Build başarı kontrolü
BUILD_SUCCESS=$(echo "$LOG_CONTENT" | grep -iE "Build.*success|✅.*build|build.*complete" | wc -l 2>/dev/null | tr -d ' \n' || echo "0")
BUILD_SUCCESS=${BUILD_SUCCESS:-0}
BUILD_FAILED=$(echo "$LOG_CONTENT" | grep -iE "Build.*failed|❌.*build|build.*error" | wc -l 2>/dev/null | tr -d ' \n' || echo "0")
BUILD_FAILED=${BUILD_FAILED:-0}

echo -e "${CYAN}${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${CYAN}${BOLD}📋 Build Durumu${NC}"
echo -e "${CYAN}${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}\n"

if [ "$BUILD_FAILED" -gt 0 ] 2>/dev/null || [ "$TOTAL_ERRORS" -gt 0 ] 2>/dev/null; then
    echo -e "${RED}${BOLD}❌ Build Başarısız!${NC}\n"
    EXIT_CODE=1
elif [ "$BUILD_SUCCESS" -gt 0 ] 2>/dev/null; then
    echo -e "${GREEN}${BOLD}✅ Build Başarılı${NC}\n"
    if [ "$TOTAL_WARNINGS" -gt 0 ]; then
        echo -e "${YELLOW}⚠️  Ancak $TOTAL_WARNINGS warning bulundu${NC}\n"
        EXIT_CODE=0
    else
        echo -e "${GREEN}✨ Warning yok - Temiz build!${NC}\n"
        EXIT_CODE=0
    fi
else
    echo -e "${YELLOW}⚠️  Build durumu belirsiz${NC}\n"
    EXIT_CODE=0
fi

# Özet
echo -e "${CYAN}${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${CYAN}${BOLD}📊 Özet${NC}"
echo -e "${CYAN}${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}\n"

echo -e "  ${BLUE}Toplam Warning:${NC} $TOTAL_WARNINGS"
echo -e "  ${BLUE}Toplam Error:${NC} $TOTAL_ERRORS"
echo -e "  ${BLUE}Build Durumu:${NC} $([ "$EXIT_CODE" -eq 0 ] && echo -e "${GREEN}Başarılı${NC}" || echo -e "${RED}Başarısız${NC}")"
echo ""

# GitHub Actions için output
if [ -n "${GITHUB_OUTPUT:-}" ]; then
    echo "warnings=$TOTAL_WARNINGS" >> "$GITHUB_OUTPUT"
    echo "errors=$TOTAL_ERRORS" >> "$GITHUB_OUTPUT"
    echo "build_status=$([ "$EXIT_CODE" -eq 0 ] && echo "success" || echo "failed")" >> "$GITHUB_OUTPUT"
fi

exit ${EXIT_CODE:-0}

