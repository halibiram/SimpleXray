#!/bin/bash
# AI Fix Until Success - Başarılı build alana kadar fix yapmaya devam et
# Sürekli monitoring, başarısız build'de fix uygula, başarılı olana kadar devam et

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
MAGENTA='\033[0;35m'
NC='\033[0m'
BOLD='\033[1m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
AI_FIXER_V3="$SCRIPT_DIR/ai-build-fixer-v3.sh"

WORKFLOWS=(
    "Build Xray-core with BoringSSL"
    "Auto Release"
)

ATTEMPT_COUNT=0
MAX_ATTEMPTS=30
TOTAL_FIXES=0

echo -e "${BOLD}${CYAN}"
echo "╔════════════════════════════════════════════════════════════════╗"
echo "║  🔄 AI FIX UNTIL SUCCESS - Başarılı Build Alana Kadar 🔄       ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo -e "${NC}\n"

# Check for failures
check_failures() {
    local FAILED_RUNS=()
    
    for WORKFLOW_NAME in "${WORKFLOWS[@]}"; do
        RUN_ID=$(gh run list --workflow="$WORKFLOW_NAME" --limit 1 2>/dev/null | head -1 | awk '{for(i=1;i<=NF;i++) if($i ~ /^[0-9]+$/) {print $i; exit}}' || echo "")
        
        if [ -z "$RUN_ID" ] || ! echo "$RUN_ID" | grep -qE '^[0-9]+$'; then
            continue
        fi
        
        if command -v jq &> /dev/null; then
            CONCLUSION=$(gh run view "$RUN_ID" --json conclusion --jq '.conclusion // "in_progress"' 2>/dev/null || echo "in_progress")
        else
            RUN_INFO=$(gh run view "$RUN_ID" 2>/dev/null || echo "")
            CONCLUSION=$(echo "$RUN_INFO" | grep -i "conclusion:" | awk '{print $2}' | head -1 || echo "in_progress")
        fi
        
        if [ "$CONCLUSION" = "failure" ]; then
            FAILED_RUNS+=("$RUN_ID|$WORKFLOW_NAME")
        fi
    done
    
    if [ ${#FAILED_RUNS[@]} -gt 0 ]; then
        printf '%s\n' "${FAILED_RUNS[@]}"
        return 0
    fi
    
    return 1
}

# Check if all workflows are successful
check_all_success() {
    for WORKFLOW_NAME in "${WORKFLOWS[@]}"; do
        RUN_ID=$(gh run list --workflow="$WORKFLOW_NAME" --limit 1 2>/dev/null | head -1 | awk '{for(i=1;i<=NF;i++) if($i ~ /^[0-9]+$/) {print $i; exit}}' || echo "")
        
        if [ -z "$RUN_ID" ]; then
            return 1
        fi
        
        if command -v jq &> /dev/null; then
            CONCLUSION=$(gh run view "$RUN_ID" --json conclusion --jq '.conclusion // "in_progress"' 2>/dev/null || echo "in_progress")
        else
            RUN_INFO=$(gh run view "$RUN_ID" 2>/dev/null || echo "")
            CONCLUSION=$(echo "$RUN_INFO" | grep -i "conclusion:" | awk '{print $2}' | head -1 || echo "in_progress")
        fi
        
        if [ "$CONCLUSION" != "success" ]; then
            return 1
        fi
    done
    
    return 0
}

# Apply fix using v3 fixer
apply_fix() {
    local RUN_ID=$1
    local WORKFLOW_NAME=$2
    
    ATTEMPT_COUNT=$((ATTEMPT_COUNT + 1))
    TOTAL_FIXES=$((TOTAL_FIXES + 1))
    
    echo -e "\n${MAGENTA}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${BOLD}${MAGENTA}[AI-MVC] Fix Uygulanıyor - Attempt #${ATTEMPT_COUNT}${NC}"
    echo -e "${BOLD}${MAGENTA}[AI-MVC] Workflow: ${WORKFLOW_NAME}${NC}"
    echo -e "${BOLD}${MAGENTA}[AI-MVC] Run ID: ${RUN_ID}${NC}"
    echo -e "${MAGENTA}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}\n"
    
    if [ -f "$AI_FIXER_V3" ]; then
        # Use v3 fixer to analyze and fix
        echo -e "${CYAN}[AI-MVC] V3 fixer ile analiz ve düzeltme...${NC}\n"
        
        # Run v3 fixer in analysis mode (one-time fix)
        timeout 300 bash "$AI_FIXER_V3" "$WORKFLOW_NAME" "$RUN_ID" 2>&1 | head -150 || {
            echo -e "${YELLOW}⚠️  V3 fixer timeout veya hata, manuel analiz deneniyor...${NC}"
            
            # Manual analysis and fix
            analyze_and_fix_manual "$RUN_ID" "$WORKFLOW_NAME"
        }
    else
        echo -e "${YELLOW}⚠️  V3 fixer bulunamadı, manuel fix uygulanıyor...${NC}"
        analyze_and_fix_manual "$RUN_ID" "$WORKFLOW_NAME"
    fi
}

# Manual analysis and fix
analyze_and_fix_manual() {
    local RUN_ID=$1
    local WORKFLOW_NAME=$2
    
    echo -e "${CYAN}[AI-MVC] Manuel analiz başlatılıyor...${NC}"
    
    # Get failed job
    FAILED_JOB=$(gh run view "$RUN_ID" --json jobs --jq '.jobs[] | select(.conclusion == "failure") | .databaseId' 2>/dev/null | head -1)
    
    if [ -z "$FAILED_JOB" ]; then
        echo -e "${RED}❌ Failed job bulunamadı${NC}"
        return 1
    fi
    
    echo -e "${BLUE}Failed Job ID: ${FAILED_JOB}${NC}"
    
    # Get error logs
    ERROR_LOG=$(gh run view "$RUN_ID" --log-failed --job "$FAILED_JOB" 2>/dev/null | grep -A 30 -E "(error|Error|ERROR|failed|Failed|BUILD FAILED)" | tail -50 || echo "")
    
    if [ -z "$ERROR_LOG" ]; then
        # Try API method
        REPO=$(gh repo view --json owner,name -q '.owner.login + "/" + .name' 2>/dev/null || echo "")
        if [ -n "$REPO" ]; then
            ERROR_LOG=$(gh api "repos/$REPO/actions/jobs/$FAILED_JOB/logs" 2>/dev/null | grep -A 30 -E "(error|Error|ERROR|failed|Failed|BUILD FAILED)" | tail -50 || echo "")
        fi
    fi
    
    echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${BOLD}📄 Error Log:${NC}"
    echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}\n"
    echo "$ERROR_LOG" | head -40
    echo ""
    
    # Pattern matching and fix
    if echo "$ERROR_LOG" | grep -qiE "Cannot set.*read-only property.*path"; then
        echo -e "${YELLOW}→ Pattern: Gradle read-only path property${NC}"
        fix_gradle_path_issue
    elif echo "$ERROR_LOG" | grep -qiE "Could not find method path"; then
        echo -e "${YELLOW}→ Pattern: Gradle path method not found${NC}"
        fix_gradle_path_method
    elif echo "$ERROR_LOG" | grep -qiE "clang.*error.*unsupported.*march"; then
        echo -e "${YELLOW}→ Pattern: Clang march flag issue${NC}"
        fix_clang_march_issue
    elif echo "$ERROR_LOG" | grep -qiE "unable to find library.*llog"; then
        echo -e "${YELLOW}→ Pattern: Missing liblog library${NC}"
        fix_liblog_issue
    elif echo "$ERROR_LOG" | grep -qiE "android.*requires.*cgo"; then
        echo -e "${YELLOW}→ Pattern: Android CGO requirement${NC}"
        fix_android_cgo_issue
    else
        echo -e "${YELLOW}→ Pattern: Generic error - analyzing...${NC}"
        echo -e "${BLUE}  Key error phrases:${NC}"
        echo "$ERROR_LOG" | grep -oE "(error: [^:]+|failed: [^:]+|unable to [^:]+)" | head -5
    fi
}

# Fix functions
fix_gradle_path_issue() {
    echo -e "${BLUE}  Fixing Gradle path read-only property...${NC}"
    
    WORKFLOW_FILE="$ROOT_DIR/.github/workflows/auto-release.yml"
    BUILD_GRADLE="$ROOT_DIR/app/build.gradle"
    
    # Check if path = file() is used
    if grep -q "path = file(" "$BUILD_GRADLE" 2>/dev/null; then
        echo -e "${BLUE}  Replacing path = file() with path \"...\"${NC}"
        sed -i.bak 's/path = file("\([^"]*\)")/path "\1"/g' "$BUILD_GRADLE" 2>/dev/null || true
        sed -i.bak 's/version = "\([^"]*\)"/version "\1"/g' "$BUILD_GRADLE" 2>/dev/null || true
    fi
}

fix_gradle_path_method() {
    echo -e "${BLUE}  Fixing Gradle path method...${NC}"
    fix_gradle_path_issue
}

fix_clang_march_issue() {
    echo -e "${BLUE}  Fixing Clang march flag issue...${NC}"
    WORKFLOW_FILE="$ROOT_DIR/.github/workflows/build-xray-boringssl.yml"
    
    if [ -f "$WORKFLOW_FILE" ]; then
        if ! grep -q "ANDROID_ABI" "$WORKFLOW_FILE" 2>/dev/null; then
            sed -i.bak '/CMAKE_ANDROID_ARCH_ABI=/a\            "-DANDROID_ABI=${{ matrix.cmake_arch }}",' "$WORKFLOW_FILE" 2>/dev/null || true
        fi
    fi
}

fix_liblog_issue() {
    echo -e "${BLUE}  Fixing liblog linking issue...${NC}"
    WORKFLOW_FILE="$ROOT_DIR/.github/workflows/build-xray-boringssl.yml"
    
    if [ -f "$WORKFLOW_FILE" ]; then
        if ! grep -q "CGO_LDFLAGS.*allow-shlib-undefined" "$WORKFLOW_FILE" 2>/dev/null; then
            sed -i.bak 's/export CGO_LDFLAGS=.*/export CGO_LDFLAGS="-Wl,--allow-shlib-undefined"/' "$WORKFLOW_FILE" 2>/dev/null || true
        fi
    fi
}

fix_android_cgo_issue() {
    echo -e "${BLUE}  Fixing Android CGO requirement...${NC}"
    WORKFLOW_FILE="$ROOT_DIR/.github/workflows/build-xray-boringssl.yml"
    
    if [ -f "$WORKFLOW_FILE" ]; then
        if ! grep -q "CGO_ENABLED=1" "$WORKFLOW_FILE" 2>/dev/null; then
            sed -i.bak '/export GOOS=android/a\          export CGO_ENABLED=1' "$WORKFLOW_FILE" 2>/dev/null || true
        fi
    fi
}

# Commit and push fix
commit_fix() {
    if git diff --quiet "$ROOT_DIR" 2>/dev/null; then
        echo -e "${YELLOW}⚠️  No changes to commit${NC}"
        return 1
    fi
    
    echo -e "\n${CYAN}[AI-MVC] Committing fix...${NC}"
    
    COMMIT_MSG="fix(ai-auto): auto-fix attempt #${ATTEMPT_COUNT}

- Applied automatic fix for workflow failure
- Attempt: #${ATTEMPT_COUNT}
- Total fixes: ${TOTAL_FIXES}
- Auto-generated by AI Build Fixer v3.0"
    
    git add -A "$ROOT_DIR" 2>/dev/null
    git commit -m "$COMMIT_MSG" 2>/dev/null || return 1
    git push 2>/dev/null || return 1
    
    echo -e "${GREEN}✅ Fix committed and pushed${NC}"
    return 0
}

# Main loop
main() {
    while [ $ATTEMPT_COUNT -lt $MAX_ATTEMPTS ]; do
        echo -e "\n${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
        echo -e "${BOLD}[$(date '+%H:%M:%S')]${NC} ${CYAN}Kontrol #$((ATTEMPT_COUNT + 1))${NC}\n"
        
        # Check if all workflows are successful
        if check_all_success; then
            echo -e "\n${GREEN}${BOLD}"
            echo "╔════════════════════════════════════════════════════════════════╗"
            echo "║     ✅✅✅ TÜM WORKFLOW'LAR BAŞARILI! ✅✅✅                      ║"
            echo "╚════════════════════════════════════════════════════════════════╝"
            echo -e "${NC}\n"
            
            for WORKFLOW_NAME in "${WORKFLOWS[@]}"; do
                RUN_ID=$(gh run list --workflow="$WORKFLOW_NAME" --limit 1 2>/dev/null | head -1 | awk '{for(i=1;i<=NF;i++) if($i ~ /^[0-9]+$/) {print $i; exit}}' || echo "")
                echo -e "${GREEN}✅ ${WORKFLOW_NAME}: Run ${RUN_ID} - SUCCESS${NC}"
            done
            
            echo -e "\n${GREEN}✅ Başarılı build alındı!${NC}"
            echo -e "${GREEN}Total fixes applied: ${TOTAL_FIXES}${NC}"
            echo -e "${GREEN}Total attempts: ${ATTEMPT_COUNT}${NC}\n"
            
            exit 0
        fi
        
        # Check for failures
        FAILED_RUNS=$(check_failures)
        
        if [ -n "$FAILED_RUNS" ]; then
            FIRST_FAILED=$(echo "$FAILED_RUNS" | head -1)
            RUN_ID=$(echo "$FIRST_FAILED" | cut -d'|' -f1)
            WORKFLOW_NAME=$(echo "$FIRST_FAILED" | cut -d'|' -f2)
            
            echo -e "${RED}${BOLD}❌ BAŞARISIZ BUILD TESPİT EDİLDİ!${NC}\n"
            echo -e "${RED}Workflow: ${WORKFLOW_NAME}${NC}"
            echo -e "${RED}Run ID: ${RUN_ID}${NC}\n"
            
            # Apply fix
            apply_fix "$RUN_ID" "$WORKFLOW_NAME"
            
            # Commit and push
            if commit_fix; then
                echo -e "${GREEN}✅ Fix uygulandı, yeni build bekleniyor...${NC}"
                sleep 50
            else
                echo -e "${YELLOW}⚠️  Fix commit edilemedi, bekleniyor...${NC}"
                sleep 30
            fi
        else
            echo -e "${YELLOW}⏳ Başarısız build yok, bekleniyor...${NC}"
            sleep 35
        fi
    done
    
    echo -e "\n${RED}❌ Maximum attempts reached (${MAX_ATTEMPTS})${NC}"
    echo -e "${YELLOW}Total fixes: ${TOTAL_FIXES}${NC}"
    exit 1
}

main






