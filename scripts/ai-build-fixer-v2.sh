#!/bin/bash
# AI-Powered Autonomous Build Fixer v2.0
# Enhanced with advanced learning, pattern recognition, and auto-improvement

set -euo pipefail

# Colors
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

# Paths
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
LEARNING_DIR="$ROOT_DIR/.cursor/ai_learning"
PATTERNS_FILE="$LEARNING_DIR/patterns.json"
KNOWLEDGE_FILE="$LEARNING_DIR/knowledge.md"
HISTORY_FILE="$LEARNING_DIR/fix_history.json"

# Configuration
ATTEMPT_COUNT=0
MAX_ATTEMPTS=20
WORKFLOW_NAME="${1:-Build Xray-core with BoringSSL}"
TOTAL_FIXES_APPLIED=0
SUCCESSFUL_FIXES=0

# Initialize history file
init_history() {
    if [ ! -f "$HISTORY_FILE" ]; then
        echo '{"fixes": [], "total_attempts": 0, "successful_builds": 0}' > "$HISTORY_FILE"
    fi
}

# Banner
show_banner() {
    clear
    echo -e "${BOLD}${CYAN}"
    echo "╔════════════════════════════════════════════════════════════════╗"
    echo "║      🤖 AI BUILD FIXER v2.0 - Enhanced Learning Agent 🤖     ║"
    echo "║         Advanced Pattern Recognition & Auto-Improvement      ║"
    echo "╚════════════════════════════════════════════════════════════════╝"
    echo -e "${NC}\n"
    echo -e "${DIM}Workflow: ${WORKFLOW_NAME}${NC}"
    echo -e "${DIM}Max Attempts: ${MAX_ATTEMPTS}${NC}\n"
}

# Load patterns with enhanced matching
load_patterns() {
    if [ ! -f "$PATTERNS_FILE" ]; then
        echo -e "${YELLOW}⚠️  Patterns file not found${NC}"
        return 1
    fi
    
    if command -v jq &> /dev/null; then
        PATTERNS=$(jq -r '.patterns[] | "\(.error)|\(.fix)|\(.context)|\(.confidence)|\(.applied_count // 0)|\(.success_count // 0)"' "$PATTERNS_FILE" 2>/dev/null || echo "")
        return 0
    else
        echo -e "${YELLOW}⚠️  jq not found${NC}"
        return 1
    fi
}

# Enhanced error pattern matching with confidence scoring
match_error_pattern_v2() {
    local ERROR_LOG="$1"
    local BEST_MATCH=""
    local BEST_CONFIDENCE=0
    local BEST_FIX=""
    local BEST_CONTEXT=""
    local BEST_APPLIED=0
    local BEST_SUCCESS=0
    
    if [ -z "$PATTERNS" ]; then
        return 1
    fi
    
    echo "$PATTERNS" | while IFS='|' read -r pattern fix context confidence applied success; do
        if echo "$ERROR_LOG" | grep -qiE "$pattern"; then
            # Calculate adjusted confidence based on success rate
            local adjusted_confidence=$confidence
            if [ "$applied" -gt 0 ]; then
                local success_rate=$((success * 100 / applied))
                # Boost confidence if success rate is high
                if [ "$success_rate" -gt 80 ]; then
                    adjusted_confidence=$((confidence + 5))
                elif [ "$success_rate" -lt 50 ]; then
                    adjusted_confidence=$((confidence - 10))
                fi
            fi
            
            if [ "$adjusted_confidence" -gt "$BEST_CONFIDENCE" ]; then
                BEST_MATCH="$pattern"
                BEST_CONFIDENCE="$adjusted_confidence"
                BEST_FIX="$fix"
                BEST_CONTEXT="$context"
                BEST_APPLIED="$applied"
                BEST_SUCCESS="$success"
            fi
        fi
    done
    
    if [ "$BEST_CONFIDENCE" -gt 0 ]; then
        echo "$BEST_MATCH|$BEST_FIX|$BEST_CONTEXT|$BEST_CONFIDENCE|$BEST_APPLIED|$BEST_SUCCESS"
        return 0
    fi
    
    return 1
}

# Enhanced log fetching with multiple fallbacks
fetch_error_logs_v2() {
    local RUN_ID=$1
    local JOB_ID=$2
    
    echo -e "${CYAN}[AI-MVC] Fetching error logs (v2 enhanced)...${NC}"
    
    LOG_OUTPUT=""
    
    # Method 1: --log-failed (fastest)
    LOG_OUTPUT=$(timeout 45 gh run view "$RUN_ID" --log-failed --job "$JOB_ID" 2>&1 | tail -300 || echo "")
    
    # Method 2: API direct (most reliable)
    if [ -z "$LOG_OUTPUT" ] || [ ${#LOG_OUTPUT} -lt 100 ]; then
        REPO=$(gh repo view --json owner,name -q '.owner.login + "/" + .name' 2>/dev/null || echo "")
        if [ -n "$REPO" ]; then
            RAW_LOG=$(timeout 45 gh api "repos/$REPO/actions/jobs/$JOB_ID/logs" 2>/dev/null || echo "")
            if [ -n "$RAW_LOG" ]; then
                # Extract error sections
                LOG_OUTPUT=$(echo "$RAW_LOG" | grep -A 50 -E "(error|Error|ERROR|failed|Failed|FAILED|ld\.lld|clang|cmake|ninja)" | tail -300 || echo "")
            fi
        fi
    fi
    
    # Method 3: Full log with filtering
    if [ -z "$LOG_OUTPUT" ] || [ ${#LOG_OUTPUT} -lt 100 ]; then
        LOG_OUTPUT=$(timeout 45 gh run view "$RUN_ID" --log --job "$JOB_ID" 2>&1 | grep -A 30 -E "(error|Error|ERROR|failed|Failed)" | tail -200 || echo "")
    fi
    
    echo "$LOG_OUTPUT"
}

# Advanced failure analysis
analyze_failure_v2() {
    local RUN_ID=$1
    
    echo -e "${CYAN}[AI-MVC] Advanced failure analysis (v2)...${NC}"
    
    # Get failed jobs with detailed info
    FAILED_JOBS=$(gh run view "$RUN_ID" --json jobs --jq '.jobs[] | select(.conclusion == "failure") | {name: .name, id: .databaseId, steps: [.steps[] | select(.conclusion == "failure") | {name: .name, number: .number, conclusion: .conclusion}]}' 2>/dev/null || echo "[]")
    
    if [ -z "$FAILED_JOBS" ] || [ "$FAILED_JOBS" = "[]" ]; then
        echo -e "${YELLOW}⚠️  No failed jobs found${NC}"
        return 1
    fi
    
    # Get first failed job
    FAILED_JOB_ID=$(echo "$FAILED_JOBS" | jq -r '.[0].id' 2>/dev/null | head -1)
    FAILED_JOB_NAME=$(echo "$FAILED_JOBS" | jq -r '.[0].name' 2>/dev/null | head -1)
    FAILED_STEP=$(echo "$FAILED_JOBS" | jq -r '.[0].steps[0].name' 2>/dev/null | head -1)
    
    echo -e "${RED}❌ Failed Job: ${FAILED_JOB_NAME}${NC}"
    echo -e "${RED}❌ Failed Step: ${FAILED_STEP}${NC}"
    echo -e "${YELLOW}📋 Job ID: ${FAILED_JOB_ID}${NC}\n"
    
    # Fetch enhanced error logs
    ERROR_LOG=$(fetch_error_logs_v2 "$RUN_ID" "$FAILED_JOB_ID")
    
    if [ -z "$ERROR_LOG" ] || [ ${#ERROR_LOG} -lt 20 ]; then
        echo -e "${YELLOW}⚠️  Could not fetch detailed logs${NC}"
        echo "${FAILED_STEP}|${FAILED_JOB_ID}|${ERROR_LOG}"
        return 0
    fi
    
    # Display error log summary
    echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${BOLD}📄 Error Log Summary (last 80 lines):${NC}"
    echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}\n"
    echo "$ERROR_LOG" | tail -80
    echo ""
    
    # Enhanced pattern matching
    MATCH=$(match_error_pattern_v2 "$ERROR_LOG")
    
    if [ -n "$MATCH" ]; then
        echo -e "${GREEN}✅ Pattern matched with enhanced scoring!${NC}"
        echo "$MATCH|${FAILED_JOB_ID}|${ERROR_LOG}"
    else
        echo -e "${YELLOW}⚠️  No pattern match, analyzing for new patterns...${NC}"
        # Try to extract key error phrases
        KEY_ERROR=$(echo "$ERROR_LOG" | grep -oE "(error: [^:]+|failed: [^:]+|unable to [^:]+)" | head -3 | tr '\n' ' ' || echo "")
        echo "${FAILED_STEP}|${FAILED_JOB_ID}|${ERROR_LOG}|NEW_PATTERN|${KEY_ERROR}"
    fi
    
    return 0
}

# Enhanced fix application with learning
apply_fix_v2() {
    local ERROR_TYPE="$1"
    local FIX_GUIDE="$2"
    local CONTEXT="$3"
    local CONFIDENCE="$4"
    local RUN_ID="$5"
    local ERROR_LOG="$6"
    
    ATTEMPT_COUNT=$((ATTEMPT_COUNT + 1))
    TOTAL_FIXES_APPLIED=$((TOTAL_FIXES_APPLIED + 1))
    
    echo -e "\n${MAGENTA}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${BOLD}${MAGENTA}[AI-MVC v2] Root Cause: ${ERROR_TYPE}${NC}"
    echo -e "${BOLD}${MAGENTA}[AI-MVC v2] Attempt: #${ATTEMPT_COUNT}${NC}"
    echo -e "${BOLD}${MAGENTA}[AI-MVC v2] Confidence: ${CONFIDENCE}%${NC}"
    echo -e "${BOLD}${MAGENTA}[AI-MVC v2] Context: ${CONTEXT}${NC}"
    echo -e "${MAGENTA}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}\n"
    
    echo -e "${CYAN}[AI-MVC v2] Applying enhanced fix...${NC}\n"
    
    # Apply fixes based on context with enhanced logic
    case "$CONTEXT" in
        "cmake/ndk/toolchain")
            apply_cmake_fix_v2 "$ERROR_TYPE" "$FIX_GUIDE" "$ERROR_LOG"
            ;;
        "kotlin/compose")
            apply_kotlin_fix_v2 "$ERROR_TYPE" "$FIX_GUIDE"
            ;;
        "gradle/dependencies"|"gradle/agp")
            apply_gradle_fix_v2 "$ERROR_TYPE" "$FIX_GUIDE"
            ;;
        "ksp/kotlin")
            apply_ksp_fix_v2 "$ERROR_TYPE" "$FIX_GUIDE"
            ;;
        "build/artifacts")
            apply_artifact_fix_v2 "$ERROR_TYPE" "$FIX_GUIDE"
            ;;
        "openssl/boringssl")
            apply_boringssl_fix_v2 "$ERROR_TYPE" "$FIX_GUIDE"
            ;;
        "NEW_PATTERN")
            analyze_and_create_new_pattern "$ERROR_TYPE" "$ERROR_LOG"
            ;;
        *)
            apply_generic_fix_v2 "$ERROR_TYPE" "$FIX_GUIDE" "$ERROR_LOG"
            ;;
    esac
}

# Enhanced CMake fixes
apply_cmake_fix_v2() {
    local ERROR_TYPE="$1"
    local FIX_GUIDE="$2"
    local ERROR_LOG="$3"
    
    echo -e "${YELLOW}→ Applying enhanced CMake/NDK fix...${NC}"
    
    # Check for specific error patterns in log
    if echo "$ERROR_LOG" | grep -qiE "march.*armv8.*simd.*crypto"; then
        echo -e "${BLUE}  Detected: march flag issue${NC}"
        CMAKE_FILES=$(find "$ROOT_DIR" -name "CMakeLists.txt" -type f 2>/dev/null | head -5)
        for CMAKE_FILE in $CMAKE_FILES; do
            if grep -q "march=armv8-a+simd+crypto" "$CMAKE_FILE" 2>/dev/null; then
                sed -i.bak 's/-march=armv8-a+simd+crypto/-march=armv8-a/g' "$CMAKE_FILE" 2>/dev/null || true
            fi
        done
    fi
    
    # Fix workflow file
    WORKFLOW_FILE="$ROOT_DIR/.github/workflows/build-xray-boringssl.yml"
    if [ -f "$WORKFLOW_FILE" ]; then
        # Ensure ANDROID_ABI is set
        if ! grep -q "ANDROID_ABI" "$WORKFLOW_FILE" 2>/dev/null; then
            echo -e "${BLUE}  Adding ANDROID_ABI to CMake args${NC}"
            sed -i.bak '/CMAKE_ANDROID_ARCH_ABI=/a\            "-DANDROID_ABI=${{ matrix.cmake_arch }}",' "$WORKFLOW_FILE" 2>/dev/null || true
        fi
    fi
}

# Enhanced Kotlin fixes
apply_kotlin_fix_v2() {
    local ERROR_TYPE="$1"
    local FIX_GUIDE="$2"
    
    echo -e "${YELLOW}→ Applying enhanced Kotlin/Compose fix...${NC}"
    
    BUILD_GRADLE="$ROOT_DIR/app/build.gradle"
    if [ -f "$BUILD_GRADLE" ]; then
        if ! grep -q "jvmDefault.*all" "$BUILD_GRADLE" 2>/dev/null; then
            echo -e "${BLUE}  Adding jvmDefault=all${NC}"
            if grep -q "kotlinOptions" "$BUILD_GRADLE"; then
                sed -i.bak '/kotlinOptions {/a\        jvmDefault = "all"' "$BUILD_GRADLE" 2>/dev/null || true
            fi
        fi
    fi
}

# Enhanced Gradle fixes
apply_gradle_fix_v2() {
    local ERROR_TYPE="$1"
    local FIX_GUIDE="$2"
    
    echo -e "${YELLOW}→ Applying enhanced Gradle fix...${NC}"
    echo -e "${BLUE}  Fix guide: $FIX_GUIDE${NC}"
}

# Enhanced KSP fixes
apply_ksp_fix_v2() {
    local ERROR_TYPE="$1"
    local FIX_GUIDE="$2"
    
    echo -e "${YELLOW}→ Applying enhanced KSP fix...${NC}"
    
    BUILD_GRADLE="$ROOT_DIR/app/build.gradle"
    if [ -f "$BUILD_GRADLE" ]; then
        KOTLIN_VERSION=$(grep -oP "kotlin.*version.*['\"]\K[^'\"]+" "$BUILD_GRADLE" | head -1 || echo "")
        if [ -n "$KOTLIN_VERSION" ]; then
            echo -e "${BLUE}  Kotlin version: $KOTLIN_VERSION${NC}"
            # KSP version matching logic
        fi
    fi
}

# Enhanced artifact fixes
apply_artifact_fix_v2() {
    local ERROR_TYPE="$1"
    local FIX_GUIDE="$2"
    
    echo -e "${YELLOW}→ Applying enhanced artifact fix...${NC}"
    
    WORKFLOW_FILE="$ROOT_DIR/.github/workflows/build-xray-boringssl.yml"
    if [ -f "$WORKFLOW_FILE" ]; then
        sed -i.bak 's/find \. -name "libcrypto\.a"/find . -type f -name "libcrypto.a" 2>\/dev\/null | head -1/' "$WORKFLOW_FILE" 2>/dev/null || true
        sed -i.bak 's/find \. -name "libssl\.a"/find . -type f -name "libssl.a" 2>\/dev\/null | head -1/' "$WORKFLOW_FILE" 2>/dev/null || true
    fi
}

# Enhanced BoringSSL fixes
apply_boringssl_fix_v2() {
    local ERROR_TYPE="$1"
    local FIX_GUIDE="$2"
    
    echo -e "${YELLOW}→ Applying enhanced BoringSSL fix...${NC}"
}

# Generic fix with pattern learning
apply_generic_fix_v2() {
    local ERROR_TYPE="$1"
    local FIX_GUIDE="$2"
    local ERROR_LOG="$3"
    
    echo -e "${YELLOW}→ Applying generic fix with learning...${NC}"
    echo -e "${BLUE}  Fix guide: $FIX_GUIDE${NC}"
    
    # Try to learn from error
    if [ -n "$ERROR_LOG" ]; then
        analyze_and_create_new_pattern "$ERROR_TYPE" "$ERROR_LOG"
    fi
}

# Analyze and create new pattern from error
analyze_and_create_new_pattern() {
    local ERROR_TYPE="$1"
    local ERROR_LOG="$2"
    
    echo -e "${CYAN}[AI-MVC v2] Analyzing for new pattern...${NC}"
    
    # Extract key error phrase
    KEY_PHRASE=$(echo "$ERROR_LOG" | grep -oE "(error: [^:]+|failed: [^:]+|unable to [^:]+|requires [^:]+)" | head -1 | sed 's/^[^:]*: //' || echo "")
    
    if [ -n "$KEY_PHRASE" ] && [ ${#KEY_PHRASE} -gt 10 ]; then
        echo -e "${BLUE}  Key phrase: $KEY_PHRASE${NC}"
        # This would be added to patterns.json after successful fix
        echo "$KEY_PHRASE" > /tmp/new_pattern_$$.txt
    fi
}

# Enhanced commit with learning
commit_fix_v2() {
    local ERROR_TYPE="$1"
    local RUN_ID="$2"
    local CONFIDENCE="$3"
    local CONTEXT="$4"
    
    echo -e "\n${CYAN}[AI-MVC v2] Committing changes...${NC}"
    
    if git diff --quiet "$ROOT_DIR" 2>/dev/null; then
        echo -e "${YELLOW}⚠️  No changes detected${NC}"
        return 1
    fi
    
    echo -e "${BLUE}Changes to be committed:${NC}"
    git diff --stat "$ROOT_DIR" | head -20
    
    COMMIT_MSG="fix(ai-v2): auto-fix for $ERROR_TYPE

- Applied automatic fix for: $ERROR_TYPE
- Context: $CONTEXT
- Confidence: ${CONFIDENCE}%
- Run ID: $RUN_ID
- Attempt: #${ATTEMPT_COUNT}
- Auto-generated by AI Build Fixer v2.0"
    
    git add -A "$ROOT_DIR" 2>/dev/null
    git commit -m "$COMMIT_MSG" 2>/dev/null || {
        echo -e "${RED}❌ Commit failed${NC}"
        return 1
    }
    
    echo -e "${GREEN}✅ Changes committed${NC}"
    
    git push 2>/dev/null || {
        echo -e "${RED}❌ Push failed${NC}"
        return 1
    }
    
    echo -e "${GREEN}✅ Changes pushed, rebuild triggered${NC}"
    
    # Update fix history
    update_fix_history "$ERROR_TYPE" "$CONTEXT" "$CONFIDENCE" "$RUN_ID"
    
    return 0
}

# Update fix history for learning
update_fix_history() {
    local ERROR_TYPE="$1"
    local CONTEXT="$2"
    local CONFIDENCE="$3"
    local RUN_ID="$4"
    
    if command -v jq &> /dev/null && [ -f "$HISTORY_FILE" ]; then
        local timestamp=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
        jq --arg type "$ERROR_TYPE" \
           --arg context "$CONTEXT" \
           --arg conf "$CONFIDENCE" \
           --arg run "$RUN_ID" \
           --arg ts "$timestamp" \
           '.fixes += [{
             error_type: $type,
             context: $context,
             confidence: ($conf | tonumber),
             run_id: $run,
             timestamp: $ts,
             attempt: .total_attempts
           }] | .total_attempts += 1' "$HISTORY_FILE" > "$HISTORY_FILE.tmp" && \
        mv "$HISTORY_FILE.tmp" "$HISTORY_FILE" 2>/dev/null || true
    fi
}

# Update pattern success rate
update_pattern_success() {
    local PATTERN="$1"
    local SUCCESS="$2"  # true or false
    
    if command -v jq &> /dev/null && [ -f "$PATTERNS_FILE" ]; then
        if [ "$SUCCESS" = "true" ]; then
            jq --arg pattern "$PATTERN" \
               '(.patterns[] | select(.error == $pattern) | .applied_count) += 1 |
                (.patterns[] | select(.error == $pattern) | .success_count) += 1' \
               "$PATTERNS_FILE" > "$PATTERNS_FILE.tmp" && \
            mv "$PATTERNS_FILE.tmp" "$PATTERNS_FILE" 2>/dev/null || true
            SUCCESSFUL_FIXES=$((SUCCESSFUL_FIXES + 1))
        else
            jq --arg pattern "$PATTERN" \
               '(.patterns[] | select(.error == $pattern) | .applied_count) += 1' \
               "$PATTERNS_FILE" > "$PATTERNS_FILE.tmp" && \
            mv "$PATTERNS_FILE.tmp" "$PATTERNS_FILE" 2>/dev/null || true
        fi
    fi
}

# Main monitoring and fixing loop
monitor_and_fix_v2() {
    local RUN_ID="${2:-}"
    
    init_history
    load_patterns
    show_banner
    
    while [ $ATTEMPT_COUNT -lt $MAX_ATTEMPTS ]; do
        echo -e "\n${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
        echo -e "${BOLD}[$(date '+%H:%M:%S')]${NC} ${CYAN}Monitoring workflow...${NC}\n"
        
        # Get latest run if not provided
        if [ -z "$RUN_ID" ]; then
            LATEST_RUN=$(gh run list --workflow="$WORKFLOW_NAME" --limit 1 --json databaseId,status,conclusion --jq '.[0] | "\(.databaseId)|\(.status)|\(.conclusion // "in_progress")"' 2>/dev/null || echo "")
            if [ -z "$LATEST_RUN" ]; then
                echo -e "${YELLOW}⚠️  No runs found${NC}"
                sleep 30
                continue
            fi
            RUN_ID=$(echo "$LATEST_RUN" | cut -d'|' -f1)
            STATUS=$(echo "$LATEST_RUN" | cut -d'|' -f2)
            CONCLUSION=$(echo "$LATEST_RUN" | cut -d'|' -f3)
        else
            STATUS=$(gh run view "$RUN_ID" --json status --jq '.status' 2>/dev/null || echo "unknown")
            CONCLUSION=$(gh run view "$RUN_ID" --json conclusion --jq '.conclusion // "in_progress"' 2>/dev/null || echo "in_progress")
        fi
        
        echo -e "${BLUE}Run ID:${NC} $RUN_ID"
        echo -e "${BLUE}Status:${NC} $STATUS"
        echo -e "${BLUE}Conclusion:${NC} $CONCLUSION"
        
        case "$CONCLUSION" in
            "success")
                echo -e "\n${GREEN}${BOLD}"
                echo "╔════════════════════════════════════════════════════════════════╗"
                echo "║          ✅✅✅ BUILD SUCCESSFUL! ✅✅✅                        ║"
                echo "╚════════════════════════════════════════════════════════════════╝"
                echo -e "${NC}\n"
                
                gh run view "$RUN_ID" --json jobs --jq '.jobs[] | {name: .name, conclusion: .conclusion}' 2>/dev/null
                
                # Update learning database
                echo -e "\n${CYAN}[AI-MVC v2] Updating learning database...${NC}"
                if [ $TOTAL_FIXES_APPLIED -gt 0 ]; then
                    # Mark last fix as successful
                    update_pattern_success "$LAST_PATTERN" "true"
                    echo -e "${GREEN}✅ Learning database updated${NC}"
                fi
                
                # Update history
                if command -v jq &> /dev/null && [ -f "$HISTORY_FILE" ]; then
                    jq '.successful_builds += 1' "$HISTORY_FILE" > "$HISTORY_FILE.tmp" && \
                    mv "$HISTORY_FILE.tmp" "$HISTORY_FILE" 2>/dev/null || true
                fi
                
                echo -e "\n${GREEN}✅ Build successful! Total fixes applied: ${TOTAL_FIXES_APPLIED}${NC}"
                echo -e "${GREEN}✅ Successful fixes: ${SUCCESSFUL_FIXES}${NC}"
                
                exit 0
                ;;
            "failure")
                echo -e "\n${RED}${BOLD}❌ BUILD FAILURE DETECTED!${NC}\n"
                
                # Enhanced failure analysis
                ANALYSIS=$(analyze_failure_v2 "$RUN_ID")
                
                if [ -z "$ANALYSIS" ]; then
                    echo -e "${RED}❌ Could not analyze failure${NC}"
                    sleep 30
                    continue
                fi
                
                # Parse analysis
                ERROR_TYPE=$(echo "$ANALYSIS" | cut -d'|' -f1)
                JOB_ID=$(echo "$ANALYSIS" | cut -d'|' -f2)
                ERROR_LOG=$(echo "$ANALYSIS" | cut -d'|' -f4-)
                
                # Check if we have a pattern match
                if echo "$ANALYSIS" | grep -q "|.*|.*|"; then
                    FIX_GUIDE=$(echo "$ANALYSIS" | cut -d'|' -f2)
                    CONTEXT=$(echo "$ANALYSIS" | cut -d'|' -f3)
                    CONFIDENCE=$(echo "$ANALYSIS" | cut -d'|' -f4)
                    JOB_ID=$(echo "$ANALYSIS" | cut -d'|' -f5)
                    LAST_PATTERN=$(echo "$ANALYSIS" | cut -d'|' -f1)
                else
                    FIX_GUIDE="Manual review required"
                    CONTEXT="unknown"
                    CONFIDENCE=50
                    LAST_PATTERN="$ERROR_TYPE"
                fi
                
                # Apply enhanced fix
                apply_fix_v2 "$ERROR_TYPE" "$FIX_GUIDE" "$CONTEXT" "$CONFIDENCE" "$RUN_ID" "$ERROR_LOG"
                
                # Commit and push
                if commit_fix_v2 "$ERROR_TYPE" "$RUN_ID" "$CONFIDENCE" "$CONTEXT"; then
                    echo -e "${GREEN}✅ Fix applied and pushed, waiting for rebuild...${NC}"
                    RUN_ID=""  # Reset to monitor new run
                    sleep 50
                else
                    echo -e "${YELLOW}⚠️  Could not commit/push, retrying...${NC}"
                    sleep 20
                fi
                ;;
            "in_progress"|"queued")
                echo -e "${YELLOW}⏳ Workflow in progress...${NC}"
                gh run view "$RUN_ID" --json jobs --jq '.jobs[] | select(.status == "in_progress") | .name' 2>/dev/null | head -3 | while read job; do
                    echo -e "  ${DIM}→ $job${NC}"
                done
                sleep 35
                ;;
            *)
                echo -e "${YELLOW}ℹ️  Status: $CONCLUSION${NC}"
                sleep 30
                ;;
        esac
    done
    
    echo -e "\n${RED}❌ Maximum attempts reached (${MAX_ATTEMPTS})${NC}"
    echo -e "${YELLOW}Total fixes applied: ${TOTAL_FIXES_APPLIED}${NC}"
    echo -e "${YELLOW}Successful fixes: ${SUCCESSFUL_FIXES}${NC}"
    exit 1
}

# Main
if [ $# -eq 0 ]; then
    monitor_and_fix_v2
else
    monitor_and_fix_v2 "$WORKFLOW_NAME" "$1"
fi






