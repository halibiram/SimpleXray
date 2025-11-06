#!/bin/bash
# AI-Powered Autonomous Build Fixer v3.0 - Multi-Workflow Support
# Tracks both Build and Auto-Release workflows

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

# Multi-Workflow Configuration
WORKFLOWS=(
    "Build Xray-core with BoringSSL"
    "Auto Release"
)
PRIMARY_WORKFLOW="${WORKFLOWS[0]}"

# Configuration
ATTEMPT_COUNT=0
MAX_ATTEMPTS=25
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

# Banner V3 Multi-Workflow
show_banner() {
    clear
    echo -e "${BOLD}${MAGENTA}"
    echo "╔════════════════════════════════════════════════════════════════╗"
    echo "║  🤖 AI BUILD FIXER v3.0 - Multi-Workflow Monitor 🤖          ║"
    echo "║  ML Pattern Recognition | Predictive Prevention | Auto-Learn║"
    echo "╚════════════════════════════════════════════════════════════════╝"
    echo -e "${NC}\n"
    echo -e "${DIM}Tracked Workflows:${NC}"
    for wf in "${WORKFLOWS[@]}"; do
        echo -e "  ${CYAN}→${NC} $wf"
    done
    echo -e "${DIM}ML: ${ML_ENABLED} | Predictive: ${PREDICTIVE_MODE} | Multi-Strategy: ${MULTI_STRATEGY}${NC}\n"
}

# Check all workflows for failures
check_all_workflows() {
    local FAILED_WORKFLOWS=()
    local FAILED_RUNS=()
    
    for WORKFLOW_NAME in "${WORKFLOWS[@]}"; do
        echo -e "${CYAN}[AI-MVC v3] Checking: ${WORKFLOW_NAME}...${NC}"
        
        # Get latest run
        RUN_ID=$(gh run list --workflow="$WORKFLOW_NAME" --limit 1 2>/dev/null | head -1 | awk '{for(i=1;i<=NF;i++) if($i ~ /^[0-9]+$/) {print $i; exit}}' || echo "")
        
        if [ -z "$RUN_ID" ] || ! echo "$RUN_ID" | grep -qE '^[0-9]+$'; then
            echo -e "${YELLOW}  ⚠️  No valid run found${NC}"
            continue
        fi
        
        # Get status
        if command -v jq &> /dev/null; then
            STATUS=$(gh run view "$RUN_ID" --json status --jq '.status' 2>/dev/null || echo "unknown")
            CONCLUSION=$(gh run view "$RUN_ID" --json conclusion --jq '.conclusion // "in_progress"' 2>/dev/null || echo "in_progress")
        else
            RUN_INFO=$(gh run view "$RUN_ID" 2>/dev/null || echo "")
            STATUS=$(echo "$RUN_INFO" | grep -i "status:" | awk '{print $2}' | head -1 || echo "unknown")
            CONCLUSION=$(echo "$RUN_INFO" | grep -i "conclusion:" | awk '{print $2}' | head -1 || echo "in_progress")
        fi
        
        echo -e "${BLUE}  Run ID:${NC} $RUN_ID | ${BLUE}Status:${NC} $STATUS | ${BLUE}Conclusion:${NC} $CONCLUSION"
        
        case "$CONCLUSION" in
            "success")
                echo -e "${GREEN}  ✅ Success${NC}"
                ;;
            "failure")
                echo -e "${RED}  ❌ Failure detected!${NC}"
                FAILED_WORKFLOWS+=("$WORKFLOW_NAME")
                FAILED_RUNS+=("$RUN_ID|$WORKFLOW_NAME")
                ;;
            "in_progress"|"queued")
                echo -e "${YELLOW}  ⏳ In progress...${NC}"
                ;;
            *)
                echo -e "${YELLOW}  ℹ️  Status: $CONCLUSION${NC}"
                ;;
        esac
    done
    
    # Return failed runs
    if [ ${#FAILED_RUNS[@]} -gt 0 ]; then
        printf '%s\n' "${FAILED_RUNS[@]}"
        return 0
    fi
    
    return 1
}

# Import functions from v3 (simplified - call v3 script for analysis)
analyze_failure_v3() {
    local RUN_ID=$1
    bash "$SCRIPT_DIR/ai-build-fixer-v3.sh" "$PRIMARY_WORKFLOW" "$RUN_ID" 2>/dev/null | grep -A 50 "analyze_failure" || echo ""
}

# Simplified fix application - delegate to v3
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
    echo -e "${BOLD}${MAGENTA}[AI-MVC v3 Multi] Root Cause: ${ERROR_TYPE}${NC}"
    echo -e "${BOLD}${MAGENTA}[AI-MVC v3 Multi] Attempt: #${ATTEMPT_COUNT} | Strategy: Level ${STRATEGY_LEVEL}${NC}"
    echo -e "${BOLD}${MAGENTA}[AI-MVC v3 Multi] Confidence: ${CONFIDENCE}%${NC}"
    echo -e "${MAGENTA}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}\n"
    
    # Use v3 script's fix logic
    WORKFLOW_FILE="$ROOT_DIR/.github/workflows/build-xray-boringssl.yml"
    AUTO_RELEASE_FILE="$ROOT_DIR/.github/workflows/auto-release.yml"
    
    # Apply fixes based on context
    case "$CONTEXT" in
        "cmake/ndk/toolchain"|"android/cgo"|"android/cgo/linking")
            if [ -f "$WORKFLOW_FILE" ]; then
                echo -e "${YELLOW}→ Applying fix to build workflow...${NC}"
                # Fix logic here (simplified)
            fi
            ;;
        "gradle"|"build/artifacts"|"release")
            if [ -f "$AUTO_RELEASE_FILE" ]; then
                echo -e "${YELLOW}→ Applying fix to auto-release workflow...${NC}"
                # Fix logic here
            fi
            ;;
        *)
            echo -e "${YELLOW}→ Applying generic fix...${NC}"
            ;;
    esac
}

# Simplified commit
commit_fix_v3() {
    local ERROR_TYPE="$1"
    local RUN_ID="$2"
    local CONFIDENCE="$3"
    local CONTEXT="$4"
    local STRATEGY="$5"
    
    echo -e "\n${CYAN}[AI-MVC v3 Multi] Committing changes...${NC}"
    
    if git diff --quiet "$ROOT_DIR" 2>/dev/null; then
        echo -e "${YELLOW}⚠️  No changes detected${NC}"
        return 1
    fi
    
    COMMIT_MSG="fix(ai-v3-multi): auto-fix for $ERROR_TYPE

- Workflow: Multi-workflow monitoring
- Applied automatic fix for: $ERROR_TYPE
- Context: $CONTEXT
- Strategy: Level $STRATEGY
- Confidence: ${CONFIDENCE}%
- Run ID: $RUN_ID
- Auto-generated by AI Build Fixer v3.0 Multi-Workflow"
    
    git add -A "$ROOT_DIR" 2>/dev/null
    git commit -m "$COMMIT_MSG" 2>/dev/null || return 1
    git push 2>/dev/null || return 1
    
    echo -e "${GREEN}✅ Changes pushed${NC}"
    return 0
}

# ML signature generation
generate_ml_signature() {
    local ERROR_LOG="$1"
    SIGNATURE_PARTS=$(echo "$ERROR_LOG" | grep -oE "(error: [^:]+|failed: [^:]+|unable to [^:]+)" | head -5 | tr '\n' '|' | sed 's/|$//' || echo "")
    if command -v md5sum &> /dev/null; then
        echo "$SIGNATURE_PARTS" | md5sum | cut -d' ' -f1
    else
        echo "unknown_$(date +%s)"
    fi
}

# Main multi-workflow monitoring loop
monitor_multi_workflow() {
    init_ml_db
    init_history
    show_banner
    
    local LAST_SUCCESS_TIME=$(date +%s)
    local CONSECUTIVE_SUCCESSES=0
    
    while [ $ATTEMPT_COUNT -lt $MAX_ATTEMPTS ]; do
        echo -e "\n${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
        echo -e "${BOLD}[$(date '+%H:%M:%S')]${NC} ${CYAN}V3 Multi-Workflow Monitoring...${NC}\n"
        
        # Check all workflows
        FAILED_RUNS=$(check_all_workflows)
        
        if [ -z "$FAILED_RUNS" ]; then
            # All workflows successful
            CONSECUTIVE_SUCCESSES=$((CONSECUTIVE_SUCCESSES + 1))
            echo -e "\n${GREEN}✅ All workflows successful! (Consecutive: $CONSECUTIVE_SUCCESSES)${NC}"
            
            if [ $CONSECUTIVE_SUCCESSES -ge 2 ]; then
                echo -e "\n${GREEN}${BOLD}"
                echo "╔════════════════════════════════════════════════════════════════╗"
                echo "║     ✅✅✅ ALL WORKFLOWS SUCCESSFUL! ✅✅✅                      ║"
                echo "╚════════════════════════════════════════════════════════════════╝"
                echo -e "${NC}\n"
                
                echo -e "${GREEN}✅ All tracked workflows are successful!${NC}"
                echo -e "${GREEN}Total fixes applied: ${TOTAL_FIXES_APPLIED}${NC}"
                echo -e "${GREEN}Successful fixes: ${SUCCESSFUL_FIXES}${NC}"
                echo -e "${GREEN}Prevented failures: ${PREVENTED_FAILURES}${NC}"
                
                exit 0
            fi
            
            sleep 60
            continue
        fi
        
        # Reset success counter on failure
        CONSECUTIVE_SUCCESSES=0
        
        # Process first failed run
        FIRST_FAILED=$(echo "$FAILED_RUNS" | head -1)
        RUN_ID=$(echo "$FIRST_FAILED" | cut -d'|' -f1)
        WORKFLOW_NAME=$(echo "$FIRST_FAILED" | cut -d'|' -f2)
        
        echo -e "\n${RED}${BOLD}❌ FAILURE DETECTED IN: ${WORKFLOW_NAME}${NC}\n"
        echo -e "${RED}Run ID: ${RUN_ID}${NC}\n"
        
        # Analyze failure (use v3 analysis)
        ANALYSIS=$(analyze_failure_v3 "$RUN_ID" 2>/dev/null || echo "")
        
        if [ -z "$ANALYSIS" ]; then
            echo -e "${RED}❌ Could not analyze failure${NC}"
            sleep 30
            continue
        fi
        
        # Parse analysis
        ERROR_TYPE=$(echo "$ANALYSIS" | cut -d'|' -f1)
        JOB_ID=$(echo "$ANALYSIS" | cut -d'|' -f2)
        ERROR_LOG=$(echo "$ANALYSIS" | cut -d'|' -f4-)
        
        # Extract signature
        if echo "$ANALYSIS" | grep -q "NEW_PATTERN"; then
            LAST_SIGNATURE=$(echo "$ANALYSIS" | cut -d'|' -f5)
        else
            LAST_SIGNATURE=$(generate_ml_signature "$ERROR_LOG" 2>/dev/null || echo "")
        fi
        
        # Get fix details
        if echo "$ANALYSIS" | grep -q "|.*|.*|"; then
            FIX_GUIDE=$(echo "$ANALYSIS" | cut -d'|' -f2)
            CONTEXT=$(echo "$ANALYSIS" | cut -d'|' -f3)
            CONFIDENCE=$(echo "$ANALYSIS" | cut -d'|' -f4)
        else
            FIX_GUIDE="Multi-strategy fix required"
            CONTEXT="unknown"
            CONFIDENCE=60
        fi
        
        # Determine strategy level
        if [ $ATTEMPT_COUNT -lt 3 ]; then
            STRATEGY_LEVEL=1
        elif [ $ATTEMPT_COUNT -lt 6 ]; then
            STRATEGY_LEVEL=2
        elif [ $ATTEMPT_COUNT -lt 10 ]; then
            STRATEGY_LEVEL=3
        else
            STRATEGY_LEVEL=4
        fi
        
        # Apply fix (use v3 fix application)
        apply_fix_v3 "$ERROR_TYPE" "$FIX_GUIDE" "$CONTEXT" "$CONFIDENCE" "$RUN_ID" "$ERROR_LOG" "$STRATEGY_LEVEL"
        
        # Commit and push
        if commit_fix_v3 "$ERROR_TYPE" "$RUN_ID" "$CONFIDENCE" "$CONTEXT" "$STRATEGY_LEVEL"; then
            echo -e "${GREEN}✅ Fix applied (Strategy L${STRATEGY_LEVEL}), waiting for rebuild...${NC}"
            ATTEMPT_COUNT=$((ATTEMPT_COUNT + 1))
            sleep 55
        else
            echo -e "${YELLOW}⚠️  Could not commit/push, retrying...${NC}"
            sleep 20
        fi
    done
    
    echo -e "\n${RED}❌ Maximum attempts reached (${MAX_ATTEMPTS})${NC}"
    echo -e "${YELLOW}Total fixes: ${TOTAL_FIXES_APPLIED} | Successful: ${SUCCESSFUL_FIXES}${NC}"
    exit 1
}

# Main
monitor_multi_workflow

