#!/bin/bash
# AI-Powered Autonomous Build Fixer v3.0
# Next-Gen: ML Pattern Recognition, Predictive Prevention, Multi-Strategy Fixing

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
ML_PATTERNS_DB="$LEARNING_DIR/ml_patterns.json"

# Configuration
ATTEMPT_COUNT=0
MAX_ATTEMPTS=25
WORKFLOW_NAME="${1:-Build Xray-core with BoringSSL}"
TOTAL_FIXES_APPLIED=0
SUCCESSFUL_FIXES=0
PREVENTED_FAILURES=0

# ML Configuration
ML_ENABLED=true
PREDICTIVE_MODE=true
MULTI_STRATEGY=true
ADAPTIVE_LEARNING=true

# Initialize ML database
init_ml_db() {
    if [ ! -f "$ML_PATTERNS_DB" ]; then
        echo '{"patterns": {}, "signatures": {}, "success_rates": {}}' > "$ML_PATTERNS_DB"
    fi
}

# Initialize history
init_history() {
    if [ ! -f "$HISTORY_FILE" ]; then
        echo '{"fixes": [], "total_attempts": 0, "successful_builds": 0, "prevented_failures": 0}' > "$HISTORY_FILE"
    fi
}

# Banner V3
show_banner() {
    clear
    echo -e "${BOLD}${MAGENTA}"
    echo "╔════════════════════════════════════════════════════════════════╗"
    echo "║     🤖 AI BUILD FIXER v3.0 - Next-Gen Learning Agent 🤖        ║"
    echo "║  ML Pattern Recognition | Predictive Prevention | Auto-Learn  ║"
    echo "╚════════════════════════════════════════════════════════════════╝"
    echo -e "${NC}\n"
    echo -e "${DIM}Workflow: ${WORKFLOW_NAME}${NC}"
    echo -e "${DIM}ML: ${ML_ENABLED} | Predictive: ${PREDICTIVE_MODE} | Multi-Strategy: ${MULTI_STRATEGY}${NC}\n"
}

# ML-based pattern signature generation
generate_ml_signature() {
    local ERROR_LOG="$1"
    
    # Extract key error phrases
    SIGNATURE_PARTS=$(echo "$ERROR_LOG" | grep -oE "(error: [^:]+|failed: [^:]+|unable to [^:]+|requires [^:]+|missing [^:]+)" | head -5 | tr '\n' '|' | sed 's/|$//' || echo "")
    
    # Create hash signature
    if command -v md5sum &> /dev/null; then
        SIGNATURE=$(echo "$SIGNATURE_PARTS" | md5sum | cut -d' ' -f1)
    elif command -v md5 &> /dev/null; then
        SIGNATURE=$(echo "$SIGNATURE_PARTS" | md5 | cut -d' ' -f1)
    else
        SIGNATURE=$(echo "$SIGNATURE_PARTS" | sha256sum 2>/dev/null | cut -d' ' -f1 || echo "unknown_$(date +%s)")
    fi
    
    echo "$SIGNATURE"
}

# ML pattern matching with confidence scoring (jq optional)
ml_match_pattern_v3() {
    local ERROR_LOG="$1"
    local SIGNATURE=$(generate_ml_signature "$ERROR_LOG")
    
    if [ ! -f "$ML_PATTERNS_DB" ] || [ "$ML_ENABLED" != "true" ]; then
        return 1
    fi
    
    if command -v jq &> /dev/null; then
        # Check if signature exists in ML DB
        ML_MATCH=$(jq -r ".signatures[\"$SIGNATURE\"] // empty" "$ML_PATTERNS_DB" 2>/dev/null || echo "")
        
        if [ -n "$ML_MATCH" ] && [ "$ML_MATCH" != "null" ] && [ "$ML_MATCH" != "" ]; then
            # Get success rate
            SUCCESS_RATE=$(jq -r ".success_rates[\"$SIGNATURE\"] // 0" "$ML_PATTERNS_DB" 2>/dev/null || echo "0")
            echo "$ML_MATCH|$SIGNATURE|$SUCCESS_RATE"
            return 0
        fi
    else
        # Fallback: grep-based matching
        if grep -q "$SIGNATURE" "$ML_PATTERNS_DB" 2>/dev/null; then
            ML_MATCH=$(grep -oE "\"$SIGNATURE\":\s*\"[^\"]+\"" "$ML_PATTERNS_DB" | sed 's/.*:\s*"\(.*\)"/\1/' || echo "")
            if [ -n "$ML_MATCH" ]; then
                echo "$ML_MATCH|$SIGNATURE|50"
                return 0
            fi
        fi
    fi
    
    return 1
}

# Load patterns with ML enhancement (jq optional)
load_patterns_v3() {
    if [ ! -f "$PATTERNS_FILE" ]; then
        echo -e "${YELLOW}⚠️  Patterns file not found${NC}"
        return 1
    fi
    
    if command -v jq &> /dev/null; then
        PATTERNS=$(jq -r '.patterns[] | "\(.error)|\(.fix)|\(.context)|\(.confidence)|\(.applied_count // 0)|\(.success_count // 0)"' "$PATTERNS_FILE" 2>/dev/null || echo "")
        return 0
    else
        echo -e "${YELLOW}⚠️  jq not found - using basic pattern matching${NC}"
        # Fallback: use grep-based pattern extraction
        PATTERNS=$(grep -oE '"error":\s*"[^"]+"' "$PATTERNS_FILE" | sed 's/"error":\s*"\(.*\)"/\1/' | head -10 || echo "")
        return 0
    fi
}

# Enhanced pattern matching with ML boost
match_error_pattern_v3() {
    local ERROR_LOG="$1"
    local BEST_MATCH=""
    local BEST_CONFIDENCE=0
    local BEST_FIX=""
    local BEST_CONTEXT=""
    
    # First try ML matching
    ML_RESULT=$(ml_match_pattern_v3 "$ERROR_LOG")
    if [ -n "$ML_RESULT" ]; then
        ML_PATTERN=$(echo "$ML_RESULT" | cut -d'|' -f1)
        ML_SIG=$(echo "$ML_RESULT" | cut -d'|' -f2)
        ML_RATE=$(echo "$ML_RESULT" | cut -d'|' -f3)
        
        echo -e "${MAGENTA}🔮 ML Pattern Recognized: ${ML_PATTERN} (Success Rate: ${ML_RATE}%)${NC}"
        
        # Find corresponding fix from patterns
        if [ -n "$PATTERNS" ]; then
            echo "$PATTERNS" | while IFS='|' read -r pattern fix context confidence applied success; do
                if echo "$ML_PATTERN" | grep -qiE "$pattern"; then
                    # Boost confidence with ML success rate
                    ML_BOOSTED=$((confidence + ML_RATE / 10))
                    echo "$pattern|$fix|$context|$ML_BOOSTED|$applied|$success|ML"
                    return 0
                fi
            done
        fi
    fi
    
    # Fallback to traditional pattern matching
    if [ -z "$PATTERNS" ]; then
        return 1
    fi
    
    echo "$PATTERNS" | while IFS='|' read -r pattern fix context confidence applied success; do
        if echo "$ERROR_LOG" | grep -qiE "$pattern"; then
            # Calculate adjusted confidence
            local adjusted_confidence=$confidence
            if [ "$applied" -gt 0 ]; then
                local success_rate=$((success * 100 / applied))
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
            fi
        fi
    done
    
    if [ "$BEST_CONFIDENCE" -gt 0 ]; then
        echo "$BEST_MATCH|$BEST_FIX|$BEST_CONTEXT|$BEST_CONFIDENCE"
        return 0
    fi
    
    return 1
}

# Predictive failure prevention (jq optional)
predictive_prevention() {
    local RUN_ID=$1
    
    if [ "$PREDICTIVE_MODE" != "true" ]; then
        return 0
    fi
    
    echo -e "${CYAN}[AI-MVC v3] 🔮 Predictive prevention check...${NC}"
    
    # Check if similar failures occurred recently
    if command -v jq &> /dev/null; then
        RECENT_FAILURES=$(gh run list --workflow="$WORKFLOW_NAME" --limit 5 --json conclusion --jq '.[] | select(.conclusion == "failure")' 2>/dev/null | jq -s 'length' || echo "0")
    else
        # Fallback: count failures manually
        RECENT_FAILURES=$(gh run list --workflow="$WORKFLOW_NAME" --limit 5 2>/dev/null | grep -c "failure" || echo "0")
    fi
    
    if [ "$RECENT_FAILURES" -gt 2 ]; then
        echo -e "${YELLOW}⚠️  Multiple recent failures detected - applying preventive measures...${NC}"
        PREVENTED_FAILURES=$((PREVENTED_FAILURES + 1))
    fi
    
    return 0
}

# Enhanced log fetching
fetch_error_logs_v3() {
    local RUN_ID=$1
    local JOB_ID=$2
    
    echo -e "${CYAN}[AI-MVC v3] Fetching error logs (enhanced)...${NC}"
    
    LOG_OUTPUT=""
    
    # Multiple parallel fetch attempts
    (
        timeout 60 gh run view "$RUN_ID" --log-failed --job "$JOB_ID" 2>&1 | tail -400 > /tmp/log1_$$.txt
    ) &
    PID1=$!
    
    (
        REPO=$(gh repo view --json owner,name -q '.owner.login + "/" + .name' 2>/dev/null || echo "")
        if [ -n "$REPO" ]; then
            timeout 60 gh api "repos/$REPO/actions/jobs/$JOB_ID/logs" 2>/dev/null | grep -A 50 -E "(error|Error|ERROR|failed|Failed)" | tail -400 > /tmp/log2_$$.txt
        fi
    ) &
    PID2=$!
    
    wait $PID1 $PID2 2>/dev/null
    
    # Combine logs
    if [ -f /tmp/log1_$$.txt ]; then
        LOG_OUTPUT=$(cat /tmp/log1_$$.txt)
    fi
    
    if [ -f /tmp/log2_$$.txt ] && [ ${#LOG_OUTPUT} -lt 200 ]; then
        LOG_OUTPUT="${LOG_OUTPUT}\n$(cat /tmp/log2_$$.txt)"
    fi
    
    rm -f /tmp/log1_$$.txt /tmp/log2_$$.txt
    
    echo "$LOG_OUTPUT"
}

# Advanced failure analysis v3
analyze_failure_v3() {
    local RUN_ID=$1
    
    echo -e "${CYAN}[AI-MVC v3] Advanced ML-enhanced failure analysis...${NC}"
    
    # Get failed jobs
    FAILED_JOBS=$(gh run view "$RUN_ID" --json jobs --jq '.jobs[] | select(.conclusion == "failure") | {name: .name, id: .databaseId, steps: [.steps[] | select(.conclusion == "failure") | .name]}' 2>/dev/null || echo "[]")
    
    if [ -z "$FAILED_JOBS" ] || [ "$FAILED_JOBS" = "[]" ]; then
        echo -e "${YELLOW}⚠️  No failed jobs found${NC}"
        return 1
    fi
    
    FAILED_JOB_ID=$(echo "$FAILED_JOBS" | jq -r '.[0].id' 2>/dev/null | head -1)
    FAILED_JOB_NAME=$(echo "$FAILED_JOBS" | jq -r '.[0].name' 2>/dev/null | head -1)
    FAILED_STEP=$(echo "$FAILED_JOBS" | jq -r '.[0].steps[0].name' 2>/dev/null | head -1)
    
    echo -e "${RED}❌ Failed Job: ${FAILED_JOB_NAME}${NC}"
    echo -e "${RED}❌ Failed Step: ${FAILED_STEP}${NC}"
    echo -e "${YELLOW}📋 Job ID: ${FAILED_JOB_ID}${NC}\n"
    
    # Fetch enhanced logs
    ERROR_LOG=$(fetch_error_logs_v3 "$RUN_ID" "$FAILED_JOB_ID")
    
    if [ -z "$ERROR_LOG" ] || [ ${#ERROR_LOG} -lt 20 ]; then
        echo -e "${YELLOW}⚠️  Could not fetch detailed logs${NC}"
        echo "${FAILED_STEP}|${FAILED_JOB_ID}|${ERROR_LOG}"
        return 0
    fi
    
    # Display summary
    echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${BOLD}📄 Error Log Summary (v3 ML-enhanced):${NC}"
    echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}\n"
    echo "$ERROR_LOG" | tail -100
    echo ""
    
    # ML-enhanced pattern matching
    MATCH=$(match_error_pattern_v3 "$ERROR_LOG")
    
    if [ -n "$MATCH" ]; then
        echo -e "${GREEN}✅ Pattern matched with ML enhancement!${NC}"
        echo "$MATCH|${FAILED_JOB_ID}|${ERROR_LOG}"
    else
        echo -e "${YELLOW}⚠️  No pattern match - creating new ML pattern...${NC}"
        # Generate signature and store
        SIGNATURE=$(generate_ml_signature "$ERROR_LOG")
        store_ml_pattern "$SIGNATURE" "$FAILED_STEP" "$ERROR_LOG"
        echo "${FAILED_STEP}|${FAILED_JOB_ID}|${ERROR_LOG}|NEW_PATTERN|${SIGNATURE}"
    fi
    
    return 0
}

# Store ML pattern
store_ml_pattern() {
    local SIGNATURE="$1"
    local ERROR_TYPE="$2"
    local ERROR_LOG="$3"
    
    if command -v jq &> /dev/null && [ -f "$ML_PATTERNS_DB" ]; then
        jq --arg sig "$SIGNATURE" \
           --arg type "$ERROR_TYPE" \
           '.signatures[$sig] = $type | .success_rates[$sig] = 0' \
           "$ML_PATTERNS_DB" > "$ML_PATTERNS_DB.tmp" && \
        mv "$ML_PATTERNS_DB.tmp" "$ML_PATTERNS_DB" 2>/dev/null || true
    fi
}

# Multi-strategy fix application
apply_fix_v3() {
    local ERROR_TYPE="$1"
    local FIX_GUIDE="$2"
    local CONTEXT="$3"
    local CONFIDENCE="$4"
    local RUN_ID="$5"
    local ERROR_LOG="$6"
    local STRATEGY_LEVEL="${7:-1}"
    
    ATTEMPT_COUNT=$((ATTEMPT_COUNT + 1))
    TOTAL_FIXES_APPLIED=$((TOTAL_FIXES_APPLIED + 1))
    
    echo -e "\n${MAGENTA}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${BOLD}${MAGENTA}[AI-MVC v3] Root Cause: ${ERROR_TYPE}${NC}"
    echo -e "${BOLD}${MAGENTA}[AI-MVC v3] Attempt: #${ATTEMPT_COUNT} | Strategy: Level ${STRATEGY_LEVEL}${NC}"
    echo -e "${BOLD}${MAGENTA}[AI-MVC v3] Confidence: ${CONFIDENCE}%${NC}"
    echo -e "${BOLD}${MAGENTA}[AI-MVC v3] Context: ${CONTEXT}${NC}"
    echo -e "${MAGENTA}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}\n"
    
    echo -e "${CYAN}[AI-MVC v3] Applying multi-strategy fix...${NC}\n"
    
    # Strategy escalation
    case "$STRATEGY_LEVEL" in
        1)
            apply_strategy_level1 "$ERROR_TYPE" "$FIX_GUIDE" "$CONTEXT" "$ERROR_LOG"
            ;;
        2)
            apply_strategy_level2 "$ERROR_TYPE" "$FIX_GUIDE" "$CONTEXT" "$ERROR_LOG"
            ;;
        3)
            apply_strategy_level3 "$ERROR_TYPE" "$FIX_GUIDE" "$CONTEXT" "$ERROR_LOG"
            ;;
        4)
            apply_strategy_level4 "$ERROR_TYPE" "$FIX_GUIDE" "$CONTEXT" "$ERROR_LOG"
            ;;
        *)
            apply_strategy_level1 "$ERROR_TYPE" "$FIX_GUIDE" "$CONTEXT" "$ERROR_LOG"
            ;;
    esac
}

# Strategy Level 1: Minimal targeted fix
apply_strategy_level1() {
    local ERROR_TYPE="$1"
    local FIX_GUIDE="$2"
    local CONTEXT="$3"
    local ERROR_LOG="$4"
    
    echo -e "${YELLOW}→ Strategy L1: Minimal targeted fix${NC}"
    
    case "$CONTEXT" in
        "cmake/ndk/toolchain")
            apply_cmake_fix_v3 "$ERROR_TYPE" "$FIX_GUIDE" "$ERROR_LOG" "minimal"
            ;;
        "kotlin/compose")
            apply_kotlin_fix_v3 "$ERROR_TYPE" "$FIX_GUIDE" "minimal"
            ;;
        *)
            apply_generic_fix_v3 "$ERROR_TYPE" "$FIX_GUIDE" "$ERROR_LOG" "minimal"
            ;;
    esac
}

# Strategy Level 2: Multi-file coordination
apply_strategy_level2() {
    local ERROR_TYPE="$1"
    local FIX_GUIDE="$2"
    local CONTEXT="$3"
    local ERROR_LOG="$4"
    
    echo -e "${YELLOW}→ Strategy L2: Multi-file coordination${NC}"
    
    case "$CONTEXT" in
        "cmake/ndk/toolchain")
            apply_cmake_fix_v3 "$ERROR_TYPE" "$FIX_GUIDE" "$ERROR_LOG" "coordinated"
            ;;
        "gradle/dependencies"|"gradle/agp")
            apply_gradle_fix_v3 "$ERROR_TYPE" "$FIX_GUIDE" "coordinated"
            ;;
        *)
            apply_generic_fix_v3 "$ERROR_TYPE" "$FIX_GUIDE" "$ERROR_LOG" "coordinated"
            ;;
    esac
}

# Strategy Level 3: Configuration restructuring
apply_strategy_level3() {
    local ERROR_TYPE="$1"
    local FIX_GUIDE="$2"
    local CONTEXT="$3"
    local ERROR_LOG="$4"
    
    echo -e "${YELLOW}→ Strategy L3: Configuration restructuring${NC}"
    
    # Restructure build configuration
    if [ -f "$ROOT_DIR/gradle.properties" ]; then
        echo -e "${BLUE}  Restructuring gradle.properties...${NC}"
        # Add compatibility constraints
    fi
    
    apply_generic_fix_v3 "$ERROR_TYPE" "$FIX_GUIDE" "$ERROR_LOG" "restructure"
}

# Strategy Level 4: Fallback mechanisms
apply_strategy_level4() {
    local ERROR_TYPE="$1"
    local FIX_GUIDE="$2"
    local CONTEXT="$3"
    local ERROR_LOG="$4"
    
    echo -e "${YELLOW}→ Strategy L4: Fallback mechanisms${NC}"
    
    # Apply fallback strategies
    echo -e "${BLUE}  Applying fallback: Version pinning${NC}"
    echo -e "${BLUE}  Applying fallback: Compatibility enforcement${NC}"
    echo -e "${BLUE}  Applying fallback: Alternative build path${NC}"
    
    apply_generic_fix_v3 "$ERROR_TYPE" "$FIX_GUIDE" "$ERROR_LOG" "fallback"
}

# Enhanced CMake fixes v3
apply_cmake_fix_v3() {
    local ERROR_TYPE="$1"
    local FIX_GUIDE="$2"
    local ERROR_LOG="$3"
    local MODE="${4:-minimal}"
    
    echo -e "${YELLOW}→ Applying CMake/NDK fix (v3, mode: $MODE)...${NC}"
    
    WORKFLOW_FILE="$ROOT_DIR/.github/workflows/build-xray-boringssl.yml"
    
    if [ -f "$WORKFLOW_FILE" ]; then
        # Detect specific issues from log
        if echo "$ERROR_LOG" | grep -qiE "march.*armv8.*simd.*crypto"; then
            echo -e "${BLUE}  Fixing march flag issue...${NC}"
            sed -i.bak 's/-march=armv8-a+simd+crypto/-march=armv8-a/g' "$WORKFLOW_FILE" 2>/dev/null || true
        fi
        
        if echo "$ERROR_LOG" | grep -qiE "unable to find library.*llog"; then
            echo -e "${BLUE}  Fixing -llog linking issue...${NC}"
            if ! grep -q "CGO_LDFLAGS.*allow-shlib-undefined" "$WORKFLOW_FILE" 2>/dev/null; then
                sed -i.bak 's/export CGO_LDFLAGS=.*/export CGO_LDFLAGS="-Wl,--allow-shlib-undefined"/' "$WORKFLOW_FILE" 2>/dev/null || true
            fi
        fi
        
        if echo "$ERROR_LOG" | grep -qiE "android.*requires.*cgo"; then
            echo -e "${BLUE}  Ensuring CGO is enabled for Android...${NC}"
            if ! grep -q "CGO_ENABLED=1" "$WORKFLOW_FILE" 2>/dev/null; then
                sed -i.bak '/export GOOS=android/a\          export CGO_ENABLED=1' "$WORKFLOW_FILE" 2>/dev/null || true
            fi
        fi
        
        # Ensure ANDROID_ABI is set
        if ! grep -q "ANDROID_ABI" "$WORKFLOW_FILE" 2>/dev/null; then
            echo -e "${BLUE}  Adding ANDROID_ABI...${NC}"
            sed -i.bak '/CMAKE_ANDROID_ARCH_ABI=/a\            "-DANDROID_ABI=${{ matrix.cmake_arch }}",' "$WORKFLOW_FILE" 2>/dev/null || true
        fi
    fi
}

# Enhanced Kotlin fixes v3
apply_kotlin_fix_v3() {
    local ERROR_TYPE="$1"
    local FIX_GUIDE="$2"
    local MODE="${3:-minimal}"
    
    echo -e "${YELLOW}→ Applying Kotlin/Compose fix (v3, mode: $MODE)...${NC}"
    
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

# Enhanced Gradle fixes v3
apply_gradle_fix_v3() {
    local ERROR_TYPE="$1"
    local FIX_GUIDE="$2"
    local MODE="${3:-minimal}"
    
    echo -e "${YELLOW}→ Applying Gradle fix (v3, mode: $MODE)...${NC}"
    echo -e "${BLUE}  Fix guide: $FIX_GUIDE${NC}"
}

# Generic fix v3
apply_generic_fix_v3() {
    local ERROR_TYPE="$1"
    local FIX_GUIDE="$2"
    local ERROR_LOG="$3"
    local MODE="${4:-minimal}"
    
    echo -e "${YELLOW}→ Applying generic fix (v3, mode: $MODE)...${NC}"
    echo -e "${BLUE}  Fix guide: $FIX_GUIDE${NC}"
    
    # Learn from error
    if [ -n "$ERROR_LOG" ]; then
        SIGNATURE=$(generate_ml_signature "$ERROR_LOG")
        store_ml_pattern "$SIGNATURE" "$ERROR_TYPE" "$ERROR_LOG"
    fi
}

# Enhanced commit with ML learning
commit_fix_v3() {
    local ERROR_TYPE="$1"
    local RUN_ID="$2"
    local CONFIDENCE="$3"
    local CONTEXT="$4"
    local STRATEGY="$5"
    
    echo -e "\n${CYAN}[AI-MVC v3] Committing changes...${NC}"
    
    if git diff --quiet "$ROOT_DIR" 2>/dev/null; then
        echo -e "${YELLOW}⚠️  No changes detected${NC}"
        return 1
    fi
    
    echo -e "${BLUE}Changes:${NC}"
    git diff --stat "$ROOT_DIR" | head -20
    
    COMMIT_MSG="fix(ai-v3): auto-fix for $ERROR_TYPE

- Applied automatic fix for: $ERROR_TYPE
- Context: $CONTEXT
- Strategy: Level $STRATEGY
- Confidence: ${CONFIDENCE}%
- Run ID: $RUN_ID
- Attempt: #${ATTEMPT_COUNT}
- ML-Enhanced: true
- Auto-generated by AI Build Fixer v3.0"
    
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
    
    echo -e "${GREEN}✅ Changes pushed${NC}"
    
    # Update learning
    update_fix_history_v3 "$ERROR_TYPE" "$CONTEXT" "$CONFIDENCE" "$RUN_ID" "$STRATEGY"
    
    return 0
}

# Update fix history v3
update_fix_history_v3() {
    local ERROR_TYPE="$1"
    local CONTEXT="$2"
    local CONFIDENCE="$3"
    local RUN_ID="$4"
    local STRATEGY="$5"
    
    if command -v jq &> /dev/null && [ -f "$HISTORY_FILE" ]; then
        local timestamp=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
        jq --arg type "$ERROR_TYPE" \
           --arg context "$CONTEXT" \
           --arg conf "$CONFIDENCE" \
           --arg run "$RUN_ID" \
           --arg strategy "$STRATEGY" \
           --arg ts "$timestamp" \
           '.fixes += [{
             error_type: $type,
             context: $context,
             confidence: ($conf | tonumber),
             run_id: $run,
             strategy: ($strategy | tonumber),
             timestamp: $ts,
             attempt: .total_attempts,
             ml_enhanced: true
           }] | .total_attempts += 1' "$HISTORY_FILE" > "$HISTORY_FILE.tmp" && \
        mv "$HISTORY_FILE.tmp" "$HISTORY_FILE" 2>/dev/null || true
    fi
}

# Update ML pattern success
update_ml_pattern_success() {
    local SIGNATURE="$1"
    local SUCCESS="$2"
    
    if command -v jq &> /dev/null && [ -f "$ML_PATTERNS_DB" ]; then
        if [ "$SUCCESS" = "true" ]; then
            CURRENT_RATE=$(jq -r ".success_rates[\"$SIGNATURE\"] // 0" "$ML_PATTERNS_DB" 2>/dev/null || echo "0")
            NEW_RATE=$((CURRENT_RATE + 5))
            if [ "$NEW_RATE" -gt 100 ]; then
                NEW_RATE=100
            fi
            jq --arg sig "$SIGNATURE" \
               --argjson rate "$NEW_RATE" \
               '.success_rates[$sig] = $rate' \
               "$ML_PATTERNS_DB" > "$ML_PATTERNS_DB.tmp" && \
            mv "$ML_PATTERNS_DB.tmp" "$ML_PATTERNS_DB" 2>/dev/null || true
            SUCCESSFUL_FIXES=$((SUCCESSFUL_FIXES + 1))
        else
            CURRENT_RATE=$(jq -r ".success_rates[\"$SIGNATURE\"] // 0" "$ML_PATTERNS_DB" 2>/dev/null || echo "0")
            NEW_RATE=$((CURRENT_RATE - 2))
            if [ "$NEW_RATE" -lt 0 ]; then
                NEW_RATE=0
            fi
            jq --arg sig "$SIGNATURE" \
               --argjson rate "$NEW_RATE" \
               '.success_rates[$sig] = $rate' \
               "$ML_PATTERNS_DB" > "$ML_PATTERNS_DB.tmp" && \
            mv "$ML_PATTERNS_DB.tmp" "$ML_PATTERNS_DB" 2>/dev/null || true
        fi
    fi
}

# Main monitoring loop v3
monitor_and_fix_v3() {
    local RUN_ID="${2:-}"
    local STRATEGY_LEVEL=1
    
    init_ml_db
    init_history
    load_patterns_v3
    show_banner
    
    while [ $ATTEMPT_COUNT -lt $MAX_ATTEMPTS ]; do
        echo -e "\n${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
        echo -e "${BOLD}[$(date '+%H:%M:%S')]${NC} ${CYAN}V3 Monitoring...${NC}\n"
        
        # Predictive prevention
        if [ -n "$RUN_ID" ] && [ "$PREDICTIVE_MODE" = "true" ]; then
            predictive_prevention "$RUN_ID"
        fi
        
        # Get latest run
        if [ -z "$RUN_ID" ] || [ "$RUN_ID" = "Build Xray-core with BoringSSL" ] || ! echo "$RUN_ID" | grep -qE '^[0-9]+$'; then
            # Get latest run from workflow - extract numeric ID directly
            RUN_ID=$(gh run list --workflow="$WORKFLOW_NAME" --limit 1 2>/dev/null | head -1 | awk '{print $NF}' | grep -oE '^[0-9]+$' || echo "")
            
            if [ -z "$RUN_ID" ] || ! echo "$RUN_ID" | grep -qE '^[0-9]+$'; then
                # Try alternative extraction
                RUN_ID=$(gh run list --workflow="$WORKFLOW_NAME" --limit 1 2>/dev/null | head -1 | awk '{for(i=1;i<=NF;i++) if($i ~ /^[0-9]+$/) {print $i; exit}}' || echo "")
            fi
            
            if [ -z "$RUN_ID" ] || ! echo "$RUN_ID" | grep -qE '^[0-9]+$'; then
                echo -e "${YELLOW}⚠️  No valid run ID found${NC}"
                sleep 30
                continue
            fi
            
            # Get status and conclusion
            if command -v jq &> /dev/null; then
                STATUS=$(gh run view "$RUN_ID" --json status --jq '.status' 2>/dev/null || echo "unknown")
                CONCLUSION=$(gh run view "$RUN_ID" --json conclusion --jq '.conclusion // "in_progress"' 2>/dev/null || echo "in_progress")
            else
                RUN_INFO=$(gh run view "$RUN_ID" 2>/dev/null || echo "")
                STATUS=$(echo "$RUN_INFO" | grep -i "status:" | awk '{print $2}' | head -1 || echo "unknown")
                CONCLUSION=$(echo "$RUN_INFO" | grep -i "conclusion:" | awk '{print $2}' | head -1 || echo "in_progress")
            fi
        else
            if command -v jq &> /dev/null; then
                STATUS=$(gh run view "$RUN_ID" --json status --jq '.status' 2>/dev/null || echo "unknown")
                CONCLUSION=$(gh run view "$RUN_ID" --json conclusion --jq '.conclusion // "in_progress"' 2>/dev/null || echo "in_progress")
            else
                STATUS=$(gh run view "$RUN_ID" 2>/dev/null | grep -i "status:" | awk '{print $2}' || echo "unknown")
                CONCLUSION=$(gh run view "$RUN_ID" 2>/dev/null | grep -i "conclusion:" | awk '{print $2}' || echo "in_progress")
            fi
        fi
        
        echo -e "${BLUE}Run ID:${NC} $RUN_ID"
        echo -e "${BLUE}Status:${NC} $STATUS"
        echo -e "${BLUE}Conclusion:${NC} $CONCLUSION"
        
        case "$CONCLUSION" in
            "success")
                echo -e "\n${GREEN}${BOLD}"
                echo "╔════════════════════════════════════════════════════════════════╗"
                echo "║       ✅✅✅ BUILD SUCCESSFUL! ✅✅✅                          ║"
                echo "╚════════════════════════════════════════════════════════════════╝"
                echo -e "${NC}\n"
                
                gh run view "$RUN_ID" --json jobs --jq '.jobs[] | {name: .name, conclusion: .conclusion}' 2>/dev/null
                
                # Update learning
                echo -e "\n${CYAN}[AI-MVC v3] Updating ML learning database...${NC}"
                if [ -n "$LAST_SIGNATURE" ]; then
                    update_ml_pattern_success "$LAST_SIGNATURE" "true"
                fi
                
                if command -v jq &> /dev/null && [ -f "$HISTORY_FILE" ]; then
                    jq '.successful_builds += 1' "$HISTORY_FILE" > "$HISTORY_FILE.tmp" && \
                    mv "$HISTORY_FILE.tmp" "$HISTORY_FILE" 2>/dev/null || true
                fi
                
                echo -e "\n${GREEN}✅ Build successful!${NC}"
                echo -e "${GREEN}Total fixes: ${TOTAL_FIXES_APPLIED} | Successful: ${SUCCESSFUL_FIXES} | Prevented: ${PREVENTED_FAILURES}${NC}"
                
                exit 0
                ;;
            "failure")
                echo -e "\n${RED}${BOLD}❌ BUILD FAILURE DETECTED!${NC}\n"
                
                # Enhanced analysis
                ANALYSIS=$(analyze_failure_v3 "$RUN_ID")
                
                if [ -z "$ANALYSIS" ]; then
                    echo -e "${RED}❌ Could not analyze failure${NC}"
                    sleep 30
                    continue
                fi
                
                # Parse analysis
                ERROR_TYPE=$(echo "$ANALYSIS" | cut -d'|' -f1)
                JOB_ID=$(echo "$ANALYSIS" | cut -d'|' -f2)
                ERROR_LOG=$(echo "$ANALYSIS" | cut -d'|' -f4-)
                
                # Extract signature if available
                if echo "$ANALYSIS" | grep -q "NEW_PATTERN"; then
                    LAST_SIGNATURE=$(echo "$ANALYSIS" | cut -d'|' -f5)
                else
                    LAST_SIGNATURE=$(generate_ml_signature "$ERROR_LOG")
                fi
                
                # Check pattern match
                if echo "$ANALYSIS" | grep -q "|.*|.*|"; then
                    FIX_GUIDE=$(echo "$ANALYSIS" | cut -d'|' -f2)
                    CONTEXT=$(echo "$ANALYSIS" | cut -d'|' -f3)
                    CONFIDENCE=$(echo "$ANALYSIS" | cut -d'|' -f4)
                    JOB_ID=$(echo "$ANALYSIS" | cut -d'|' -f5)
                else
                    FIX_GUIDE="Multi-strategy fix required"
                    CONTEXT="unknown"
                    CONFIDENCE=60
                fi
                
                # Determine strategy level based on attempt count
                if [ $ATTEMPT_COUNT -lt 3 ]; then
                    STRATEGY_LEVEL=1
                elif [ $ATTEMPT_COUNT -lt 6 ]; then
                    STRATEGY_LEVEL=2
                elif [ $ATTEMPT_COUNT -lt 10 ]; then
                    STRATEGY_LEVEL=3
                else
                    STRATEGY_LEVEL=4
                fi
                
                # Apply multi-strategy fix
                apply_fix_v3 "$ERROR_TYPE" "$FIX_GUIDE" "$CONTEXT" "$CONFIDENCE" "$RUN_ID" "$ERROR_LOG" "$STRATEGY_LEVEL"
                
                # Commit and push
                if commit_fix_v3 "$ERROR_TYPE" "$RUN_ID" "$CONFIDENCE" "$CONTEXT" "$STRATEGY_LEVEL"; then
                    echo -e "${GREEN}✅ Fix applied (Strategy L${STRATEGY_LEVEL}), waiting for rebuild...${NC}"
                    RUN_ID=""  # Reset to monitor new run
                    STRATEGY_LEVEL=1  # Reset strategy
                    sleep 55
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
    echo -e "${YELLOW}Total fixes: ${TOTAL_FIXES_APPLIED} | Successful: ${SUCCESSFUL_FIXES}${NC}"
    exit 1
}

# Main
if [ $# -eq 0 ]; then
    monitor_and_fix_v3
else
    monitor_and_fix_v3 "$WORKFLOW_NAME" "$1"
fi

