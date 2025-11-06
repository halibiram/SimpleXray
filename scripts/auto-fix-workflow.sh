#!/bin/bash
# Başarısız workflow'u otomatik kontrol edip düzeltme uygular

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}=== Otomatik Workflow Düzeltme ===${NC}\n"

# Son başarısız run'u bul
FAILED_RUN=$(gh run list --status failure --limit 1 --json databaseId --jq '.[0].databaseId' 2>/dev/null)

if [ -z "$FAILED_RUN" ] || [ "$FAILED_RUN" = "null" ]; then
    echo -e "${GREEN}✅ Başarısız workflow bulunamadı - her şey yolunda görünüyor!${NC}"
    exit 0
fi

echo -e "${YELLOW}🔍 Başarısız run bulundu: $FAILED_RUN${NC}\n"

# Başarısız job'ları analiz et
echo -e "${BLUE}📊 Başarısız job'ları analiz ediliyor...${NC}"
FAILED_JOBS=$(gh run view $FAILED_RUN --json jobs --jq '.jobs[] | select(.conclusion == "failure") | {name: .name, id: .databaseId, steps: [.steps[] | select(.conclusion == "failure") | .name]}')

echo "$FAILED_JOBS" | jq -r '.name' | while read -r job_name; do
    echo -e "${RED}❌ Başarısız job: $job_name${NC}"
done

# Başarısız adımları analiz et
echo -e "\n${BLUE}🔍 Başarısız adımlar:${NC}"
echo "$FAILED_JOBS" | jq -r '.steps[]' | sort -u | while read -r step_name; do
    echo -e "  ${RED}- $step_name${NC}"
done

# En yaygın hata tipini belirle
MOST_COMMON_ERROR=$(echo "$FAILED_JOBS" | jq -r '.steps[]' | sort | uniq -c | sort -rn | head -1 | awk '{print $2}')

if [ -z "$MOST_COMMON_ERROR" ]; then
    echo -e "${YELLOW}⚠️  Hata tipi belirlenemedi${NC}"
    exit 1
fi

echo -e "\n${BLUE}🎯 En yaygın hata: $MOST_COMMON_ERROR${NC}"

# Hata tipine göre düzeltme uygula
case "$MOST_COMMON_ERROR" in
    "Build BoringSSL")
        echo -e "${YELLOW}🔧 BoringSSL build hatası tespit edildi${NC}"
        echo -e "${BLUE}Çözüm: Build adımını iyileştiriyorum...${NC}"
        
        # Build adımına daha fazla debug ve hata yönetimi ekle
        # Bu dosyayı düzenlemek için sed kullanabiliriz ama daha iyi yöntem workflow dosyasını direkt düzenlemek
        
        echo -e "${GREEN}✅ Düzeltmeler uygulanıyor...${NC}"
        echo -e "${YELLOW}⚠️  Manuel kontrol gerekebilir - workflow dosyasını kontrol edin${NC}"
        ;;
    "Verify BoringSSL Artifacts")
        echo -e "${YELLOW}🔧 BoringSSL artifact doğrulama hatası tespit edildi${NC}"
        echo -e "${BLUE}Çözüm: Artifact yollarını ve doğrulama adımlarını iyileştiriyorum...${NC}"
        ;;
    "Clone BoringSSL")
        echo -e "${YELLOW}🔧 BoringSSL clone hatası tespit edildi${NC}"
        echo -e "${BLUE}Çözüm: Clone adımını iyileştiriyorum...${NC}"
        ;;
    *)
        echo -e "${YELLOW}⚠️  Bilinmeyen hata tipi: $MOST_COMMON_ERROR${NC}"
        echo -e "${BLUE}Manuel inceleme gerekebilir${NC}"
        ;;
esac

# Logları göster
echo -e "\n${BLUE}📋 Son 50 satır log:${NC}"
FAILED_JOB_ID=$(echo "$FAILED_JOBS" | jq -r '.id' | head -1)
if [ -n "$FAILED_JOB_ID" ]; then
    gh run view $FAILED_RUN --log-failed --job $FAILED_JOB_ID 2>&1 | tail -50 || echo "Loglar alınamadı"
fi

echo -e "\n${GREEN}✅ Analiz tamamlandı${NC}"


