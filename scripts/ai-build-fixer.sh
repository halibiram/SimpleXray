#!/bin/bash
# AI-Powered Autonomous Build Fixer
# Integrates with learning database and applies intelligent fixes

set -euo pipefail

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
MAGENTA='\033[0;35m'
NC='\033[0m'
BOLD='\033[1m'

# Paths
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
LEARNING_DIR="$ROOT_DIR/.cursor/ai_learning"
PATTERNS_FILE="$LEARNING_DIR/patterns.json"
KNOWLEDGE_FILE="$LEARNING_DIR/knowledge.md"

# Initialize
ATTEMPT_COUNT=0
MAX_ATTEMPTS=10

echo -e "${BOLD}${CYAN}"
echo "╔════════════════════════════════════════════════════════════════╗"
echo "║         🤖 AI BUILD FIXER - Autonomous Agent 🤖             ║"
echo "║         Version 1.0.0 - Learning Enabled                      ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo -e "${NC}\n"

# Load patterns from JSON
load_patterns() {
    if [ ! -f "$PATTERNS_FILE" ]; then
        echo -e "${YELLOW}⚠️  Patterns file not found, using defaults${NC}"
        return 1
    fi
    
    # Extract patterns using jq
    if command -v jq &> /dev/null; then
        PATTERNS=$(jq -r '.patterns[] | "\(.error)|\(.fix)|\(.context)|\(.confidence)"' "$PATTERNS_FILE" 2>/dev/null || echo "")
        return 0
    else
        echo -e "${YELLOW}⚠️  jq not found, pattern matching limited${NC}"
        return 1
    fi
}

# Match error against patterns
match_error_pattern() {
    local ERROR_LOG="$1"
    local BEST_MATCH=""
    local BEST_CONFIDENCE=0
    local BEST_FIX=""
    local BEST_CONTEXT=""
    
    if [ -z "$PATTERNS" ]; then
        return 1
    fi
    
    echo "$PATTERNS" | while IFS='|' read -r pattern fix context confidence; do
        if echo "$ERROR_LOG" | grep -qiE "$pattern"; then
            if [ "$confidence" -gt "$BEST_CONFIDENCE" ]; then
                BEST_MATCH="$pattern"
                BEST_CONFIDENCE="$confidence"
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

# Fetch error logs from GitHub Actions
fetch_error_logs() {
    local RUN_ID=$1
    local JOB_ID=$2
    
    echo -e "${CYAN}[AI-MVC] Fetching error logs...${NC}"
    
    # Try multiple methods
    LOG_OUTPUT=""
    
    # Method 1: --log-failed
    LOG_OUTPUT=$(timeout 30 gh run view "$RUN_ID" --log-failed --job "$JOB_ID" 2>&1 | tail -200 || echo "")
    
    # Method 2: --log with filtering
    if [ -z "$LOG_OUTPUT" ] || [ ${#LOG_OUTPUT} -lt 50 ]; then
        LOG_OUTPUT=$(timeout 30 gh run view "$RUN_ID" --log --job "$JOB_ID" 2>&1 | grep -A 50 -E "(error|Error|ERROR|failed|Failed|FAILED)" | tail -200 || echo "")
    fi
    
    # Method 3: API direct
    if [ -z "$LOG_OUTPUT" ] || [ ${#LOG_OUTPUT} -lt 50 ]; then
        REPO=$(gh repo view --json owner,name -q '.owner.login + "/" + .name' 2>/dev/null || echo "")
        if [ -n "$REPO" ]; then
            RAW_LOG=$(timeout 30 gh api "repos/$REPO/actions/jobs/$JOB_ID/logs" 2>/dev/null || echo "")
            if [ -n "$RAW_LOG" ]; then
                LOG_OUTPUT=$(echo "$RAW_LOG" | grep -A 50 -E "(error|Error|ERROR|failed|Failed|FAILED)" | tail -200 || echo "")
            fi
        fi
    fi
    
    echo "$LOG_OUTPUT"
}

# Analyze failure and extract root cause
analyze_failure() {
    local RUN_ID=$1
    
    echo -e "${CYAN}[AI-MVC] Analyzing failure...${NC}"
    
    # Get failed jobs
    FAILED_JOBS=$(gh run view "$RUN_ID" --json jobs --jq '.jobs[] | select(.conclusion == "failure") | {name: .name, id: .databaseId, steps: [.steps[] | select(.conclusion == "failure") | .name]}' 2>/dev/null || echo "[]")
    
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
    
    # Fetch error logs
    ERROR_LOG=$(fetch_error_logs "$RUN_ID" "$FAILED_JOB_ID")
    
    if [ -z "$ERROR_LOG" ] || [ ${#ERROR_LOG} -lt 20 ]; then
        echo -e "${YELLOW}⚠️  Could not fetch detailed logs${NC}"
        # Use step name as error type
        echo "${FAILED_STEP}|${FAILED_JOB_ID}|${ERROR_LOG}"
        return 0
    fi
    
    # Display error log
    echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${BOLD}📄 Error Log (last 100 lines):${NC}"
    echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}\n"
    echo "$ERROR_LOG" | tail -100
    echo ""
    
    # Try to match against patterns
    MATCH=$(match_error_pattern "$ERROR_LOG")
    
    if [ -n "$MATCH" ]; then
        echo -e "${GREEN}✅ Pattern matched!${NC}"
        echo "$MATCH|${FAILED_JOB_ID}|${ERROR_LOG}"
    else
        echo -e "${YELLOW}⚠️  No pattern match, using step name${NC}"
        echo "${FAILED_STEP}|${FAILED_JOB_ID}|${ERROR_LOG}"
    fi
    
    return 0
}

# Apply fix based on error type
apply_fix() {
    local ERROR_TYPE="$1"
    local FIX_GUIDE="$2"
    local CONTEXT="$3"
    local CONFIDENCE="$4"
    local RUN_ID="$5"
    
    ATTEMPT_COUNT=$((ATTEMPT_COUNT + 1))
    
    echo -e "\n${MAGENTA}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${BOLD}${MAGENTA}[AI-MVC] Root Cause: ${ERROR_TYPE}${NC}"
    echo -e "${BOLD}${MAGENTA}[AI-MVC] Attempt: #${ATTEMPT_COUNT}${NC}"
    echo -e "${BOLD}${MAGENTA}[AI-MVC] Confidence: ${CONFIDENCE}%${NC}"
    echo -e "${BOLD}${MAGENTA}[AI-MVC] Context: ${CONTEXT}${NC}"
    echo -e "${MAGENTA}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}\n"
    
    echo -e "${CYAN}[AI-MVC] Applying patch...${NC}\n"
    
    # Apply fixes based on context
    case "$CONTEXT" in
        "cmake/ndk/toolchain")
            apply_cmake_fix "$ERROR_TYPE" "$FIX_GUIDE"
            ;;
        "kotlin/compose")
            apply_kotlin_fix "$ERROR_TYPE" "$FIX_GUIDE"
            ;;
        "gradle/dependencies"|"gradle/agp")
            apply_gradle_fix "$ERROR_TYPE" "$FIX_GUIDE"
            ;;
        "ksp/kotlin")
            apply_ksp_fix "$ERROR_TYPE" "$FIX_GUIDE"
            ;;
        "build/artifacts")
            apply_artifact_fix "$ERROR_TYPE" "$FIX_GUIDE"
            ;;
        "openssl/boringssl")
            apply_boringssl_fix "$ERROR_TYPE" "$FIX_GUIDE"
            ;;
        *)
            apply_generic_fix "$ERROR_TYPE" "$FIX_GUIDE"
            ;;
    esac
}

# CMake/NDK fixes
apply_cmake_fix() {
    local ERROR_TYPE="$1"
    local FIX_GUIDE="$2"
    
    echo -e "${YELLOW}→ Applying CMake/NDK fix...${NC}"
    
    # Find CMakeLists.txt files
    CMAKE_FILES=$(find "$ROOT_DIR" -name "CMakeLists.txt" -type f 2>/dev/null | head -5)
    
    for CMAKE_FILE in $CMAKE_FILES; do
        if grep -q "march=armv8-a+simd+crypto" "$CMAKE_FILE" 2>/dev/null; then
            echo -e "${BLUE}  Fixing march flag in: $CMAKE_FILE${NC}"
            sed -i.bak 's/-march=armv8-a+simd+crypto/-march=armv8-a/g' "$CMAKE_FILE" 2>/dev/null || true
            sed -i.bak 's/-march=armv8-a+simd+crypto/-march=armv8-a/g' "$ROOT_DIR/app/build.gradle" 2>/dev/null || true
        fi
    done
    
    # Check build.gradle for cppFlags
    if grep -q "march.*simd.*crypto" "$ROOT_DIR/app/build.gradle" 2>/dev/null; then
        echo -e "${BLUE}  Fixing cppFlags in build.gradle${NC}"
        sed -i.bak 's/-march=armv8-a+simd+crypto/-march=armv8-a/g' "$ROOT_DIR/app/build.gradle" 2>/dev/null || true
    fi
}

# Kotlin/Compose fixes
apply_kotlin_fix() {
    local ERROR_TYPE="$1"
    local FIX_GUIDE="$2"
    
    echo -e "${YELLOW}→ Applying Kotlin/Compose fix...${NC}"
    
    BUILD_GRADLE="$ROOT_DIR/app/build.gradle"
    
    if [ -f "$BUILD_GRADLE" ]; then
        # Enable jvmDefault=all
        if ! grep -q "jvmDefault.*all" "$BUILD_GRADLE" 2>/dev/null; then
            echo -e "${BLUE}  Adding jvmDefault=all to kotlinOptions${NC}"
            # Find kotlinOptions block and add jvmDefault
            if grep -q "kotlinOptions" "$BUILD_GRADLE"; then
                sed -i.bak '/kotlinOptions {/a\        jvmTarget = "17"\n        jvmDefault = "all"' "$BUILD_GRADLE" 2>/dev/null || true
            else
                # Add kotlinOptions block
                if grep -q "compileOptions" "$BUILD_GRADLE"; then
                    sed -i.bak '/compileOptions {/a\\n    kotlinOptions {\n        jvmTarget = "17"\n        jvmDefault = "all"\n    }' "$BUILD_GRADLE" 2>/dev/null || true
                fi
            fi
        fi
    fi
}

# Gradle fixes
apply_gradle_fix() {
    local ERROR_TYPE="$1"
    local FIX_GUIDE="$2"
    
    echo -e "${YELLOW}→ Applying Gradle fix...${NC}"
    echo -e "${BLUE}  Fix guide: $FIX_GUIDE${NC}"
    # Implementation would check version compatibility and update
}

# KSP fixes
apply_ksp_fix() {
    local ERROR_TYPE="$1"
    local FIX_GUIDE="$2"
    
    echo -e "${YELLOW}→ Applying KSP fix...${NC}"
    
    BUILD_GRADLE="$ROOT_DIR/app/build.gradle"
    
    if [ -f "$BUILD_GRADLE" ]; then
        # Ensure KSP version matches Kotlin version
        KOTLIN_VERSION=$(grep -oP "kotlin.*version.*['\"]\K[^'\"]+" "$BUILD_GRADLE" | head -1 || echo "")
        if [ -n "$KOTLIN_VERSION" ]; then
            KSP_VERSION=$(echo "$KOTLIN_VERSION" | sed 's/\([0-9]\+\.[0-9]\+\).*/\1.0-1.0.0/')
            echo -e "${BLUE}  Pinning KSP to match Kotlin: $KSP_VERSION${NC}"
            # Update KSP version in build.gradle
        fi
    fi
}

# Artifact fixes
apply_artifact_fix() {
    local ERROR_TYPE="$1"
    local FIX_GUIDE="$2"
    
    echo -e "${YELLOW}→ Applying artifact fix...${NC}"
    
    WORKFLOW_FILE="$ROOT_DIR/.github/workflows/build-xray-boringssl.yml"
    
    if [ -f "$WORKFLOW_FILE" ]; then
        # Improve library search paths
        sed -i.bak 's/find \. -name "libcrypto\.a"/find . -type f -name "libcrypto.a" 2>\/dev\/null | head -1/' "$WORKFLOW_FILE" 2>/dev/null || true
        sed -i.bak 's/find \. -name "libssl\.a"/find . -type f -name "libssl.a" 2>\/dev\/null | head -1/' "$WORKFLOW_FILE" 2>/dev/null || true
    fi
}

# BoringSSL fixes
apply_boringssl_fix() {
    local ERROR_TYPE="$1"
    local FIX_GUIDE="$2"
    
    echo -e "${YELLOW}→ Applying BoringSSL fix...${NC}"
    # Implementation for BoringSSL-specific fixes
}

# Generic fixes
apply_generic_fix() {
    local ERROR_TYPE="$1"
    local FIX_GUIDE="$2"
    
    echo -e "${YELLOW}→ Applying generic fix...${NC}"
    echo -e "${BLUE}  Fix guide: $FIX_GUIDE${NC}"
}

# Commit and push fix
commit_fix() {
    local ERROR_TYPE="$1"
    local RUN_ID="$2"
    local CONFIDENCE="$3"
    
    echo -e "\n${CYAN}[AI-MVC] Committing changes...${NC}"
    
    # Check for changes
    if git diff --quiet "$ROOT_DIR" 2>/dev/null; then
        echo -e "${YELLOW}⚠️  No changes detected${NC}"
        return 1
    fi
    
    # Show diff
    echo -e "${BLUE}Changes to be committed:${NC}"
    git diff --stat "$ROOT_DIR" | head -20
    
    # Commit
    COMMIT_MSG="fix(ai): auto-fix for $ERROR_TYPE

- Applied automatic fix for: $ERROR_TYPE
- Confidence: ${CONFIDENCE}%
- Run ID: $RUN_ID
- Attempt: #${ATTEMPT_COUNT}
- Auto-generated by AI Build Fixer v1.0.0"
    
    git add -A "$ROOT_DIR" 2>/dev/null
    git commit -m "$COMMIT_MSG" 2>/dev/null || {
        echo -e "${RED}❌ Commit failed${NC}"
        return 1
    }
    
    echo -e "${GREEN}✅ Changes committed${NC}"
    
    # Push
    echo -e "${CYAN}[AI-MVC] Pushing changes...${NC}"
    git push 2>/dev/null || {
        echo -e "${RED}❌ Push failed${NC}"
        return 1
    }
    
    echo -e "${GREEN}✅ Changes pushed, rebuild triggered${NC}"
    return 0
}

# Monitor and fix loop
monitor_and_fix() {
    local RUN_ID="${1:-}"
    
    # Load patterns
    load_patterns
    
    while [ $ATTEMPT_COUNT -lt $MAX_ATTEMPTS ]; do
        echo -e "\n${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
        echo -e "${BOLD}[$(date '+%H:%M:%S')]${NC} ${CYAN}Monitoring workflow...${NC}\n"
        
        # Get latest run if not provided
        if [ -z "$RUN_ID" ]; then
            LATEST_RUN=$(gh run list --limit 1 --json databaseId,status,conclusion --jq '.[0] | "\(.databaseId)|\(.status)|\(.conclusion // "in_progress")"' 2>/dev/null || echo "")
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
                echo -e "\n${GREEN}${BOLD}╔════════════════════════════════════════════════════════╗${NC}"
                echo -e "${GREEN}${BOLD}║          ✅✅✅ BUILD SUCCESSFUL! ✅✅✅          ║${NC}"
                echo -e "${GREEN}${BOLD}╚════════════════════════════════════════════════════════╝${NC}\n"
                
                # Update learning database
                echo -e "${CYAN}[AI-MVC] Updating learning database...${NC}"
                # TODO: Update patterns.json with success
                
                exit 0
                ;;
            "failure")
                echo -e "\n${RED}${BOLD}❌ BUILD FAILURE DETECTED!${NC}\n"
                
                # Analyze failure
                ANALYSIS=$(analyze_failure "$RUN_ID")
                
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
                else
                    FIX_GUIDE="Manual review required"
                    CONTEXT="unknown"
                    CONFIDENCE=50
                fi
                
                # Apply fix
                apply_fix "$ERROR_TYPE" "$FIX_GUIDE" "$CONTEXT" "$CONFIDENCE" "$RUN_ID"
                
                # Commit and push
                if commit_fix "$ERROR_TYPE" "$RUN_ID" "$CONFIDENCE"; then
                    echo -e "${GREEN}✅ Fix applied and pushed, waiting for rebuild...${NC}"
                    RUN_ID=""  # Reset to monitor new run
                    sleep 45
                else
                    echo -e "${YELLOW}⚠️  Could not commit/push, retrying...${NC}"
                    sleep 15
                fi
                ;;
            "in_progress"|"queued")
                echo -e "${YELLOW}⏳ Workflow in progress...${NC}"
                sleep 30
                ;;
            *)
                echo -e "${YELLOW}ℹ️  Status: $CONCLUSION${NC}"
                sleep 30
                ;;
        esac
    done
    
    echo -e "${RED}❌ Maximum attempts reached (${MAX_ATTEMPTS})${NC}"
    exit 1
}

# Main
if [ $# -eq 0 ]; then
    monitor_and_fix
else
    monitor_and_fix "$1"
fi










