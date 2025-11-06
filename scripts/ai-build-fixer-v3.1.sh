#!/bin/bash
# AI-Powered Autonomous Build Fixer v3.1
# Enhanced: Stop monitoring on failure, apply fix immediately, then resume

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
ERROR_LOGS_DIR="$LEARNING_DIR/logs"

# Multi-Workflow Configuration
WORKFLOWS=(
    "Build Xray-core with BoringSSL"
    "Auto Release"
)

# Configuration
ATTEMPT_COUNT=0
MAX_ATTEMPTS=30
TOTAL_FIXES_APPLIED=0
SUCCESSFUL_FIXES=0
MONITORING_ACTIVE=true

# ML Configuration
ML_ENABLED=true
PREDICTIVE_MODE=true
MULTI_STRATEGY=true
ADAPTIVE_LEARNING=true

# Initialize directories
init_directories() {
    mkdir -p "$ERROR_LOGS_DIR"
    if [ ! -f "$ML_PATTERNS_DB" ]; then
        echo '{"patterns": {}, "signatures": {}, "success_rates": {}}' > "$ML_PATTERNS_DB"
    fi
    if [ ! -f "$HISTORY_FILE" ]; then
        echo '{"fixes": [], "total_attempts": 0, "successful_builds": 0, "prevented_failures": 0}' > "$HISTORY_FILE"
    fi
}

# Banner V3.1
show_banner() {
    clear
    echo -e "${BOLD}${MAGENTA}"
    echo "╔════════════════════════════════════════════════════════════════╗"
    echo "║  🤖 AI BUILD FIXER v3.1 - Enhanced Stop-on-Failure 🤖        ║"
    echo "║  Stop → Fix → Resume | ML Pattern Recognition | Auto-Learn   ║"
    echo "╚════════════════════════════════════════════════════════════════╝"
    echo -e "${NC}\n"
    echo -e "${DIM}Tracked Workflows:${NC}"
    for wf in "${WORKFLOWS[@]}"; do
        echo -e "  ${CYAN}→${NC} $wf"
    done
    echo -e "${DIM}ML: ${ML_ENABLED} | Predictive: ${PREDICTIVE_MODE} | Multi-Strategy: ${MULTI_STRATEGY}${NC}\n"
}

# Stop all monitoring processes
stop_monitoring() {
    echo -e "${YELLOW}[AI-MVC v3.1] Stopping monitoring processes...${NC}"
    pkill -f "ai-build-fixer-v3" 2>/dev/null || true
    pkill -f "ai-fix-until-success" 2>/dev/null || true
    pkill -f "ai-fix-on-failure" 2>/dev/null || true
    pkill -f "hyper-auto-fix" 2>/dev/null || true
    sleep 2
    echo -e "${GREEN}✅ Monitoring stopped${NC}\n"
}

# Check for failures - returns failed run info
check_failures() {
    local FAILED_RUNS=()
    local FOUND_FAILURE=false
    
    for WORKFLOW_NAME in "${WORKFLOWS[@]}"; do
        RUN_ID=$(gh run list --workflow="$WORKFLOW_NAME" --limit 1 2>/dev/null | head -1 | awk '{for(i=1;i<=NF;i++) if($i ~ /^[0-9]+$/) {print $i; exit}}' || echo "")
        
        if [ -z "$RUN_ID" ] || ! echo "$RUN_ID" | grep -qE '^[0-9]+$'; then
            echo -e "${DIM}[DEBUG] No valid RUN_ID for $WORKFLOW_NAME${NC}" >&2
            continue
        fi
        
        if command -v jq &> /dev/null; then
            STATUS=$(gh run view "$RUN_ID" --json status --jq '.status' 2>/dev/null || echo "unknown")
            CONCLUSION=$(gh run view "$RUN_ID" --json conclusion --jq '.conclusion // "in_progress"' 2>/dev/null || echo "in_progress")
        else
            RUN_INFO=$(gh run view "$RUN_ID" 2>/dev/null || echo "")
            STATUS=$(echo "$RUN_INFO" | grep -i "status:" | awk '{print $2}' | head -1 || echo "unknown")
            CONCLUSION=$(echo "$RUN_INFO" | grep -i "conclusion:" | awk '{print $2}' | head -1 || echo "in_progress")
        fi
        
        echo -e "${DIM}[DEBUG] $WORKFLOW_NAME: Run $RUN_ID, Status=$STATUS, Conclusion=$CONCLUSION${NC}" >&2
        
        if [ "$CONCLUSION" = "failure" ]; then
            FAILED_RUNS+=("$RUN_ID|$WORKFLOW_NAME")
            FOUND_FAILURE=true
            echo -e "${RED}[DEBUG] ⚠️  FAILURE FOUND: $WORKFLOW_NAME (Run $RUN_ID)${NC}" >&2
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

# Log error to file
log_error() {
    local RUN_ID=$1
    local WORKFLOW_NAME=$2
    local ERROR_LOG="$3"
    
    local LOG_FILE="$ERROR_LOGS_DIR/error_${RUN_ID}.log"
    echo "=== Error Log for Run $RUN_ID ===" > "$LOG_FILE"
    echo "Workflow: $WORKFLOW_NAME" >> "$LOG_FILE"
    echo "Timestamp: $(date -u +"%Y-%m-%dT%H:%M:%SZ")" >> "$LOG_FILE"
    echo "=================================" >> "$LOG_FILE"
    echo "" >> "$LOG_FILE"
    echo "$ERROR_LOG" >> "$LOG_FILE"
    
    echo -e "${BLUE}✅ Error logged: ${LOG_FILE}${NC}"
}

# Analyze error and determine pattern
analyze_error_pattern() {
    local ERROR_LOG="$1"
    local PATTERN="unknown"
    
    if echo "$ERROR_LOG" | grep -qiE "Could not find method arguments\(\)"; then
        PATTERN="gradle_arguments_method"
    elif echo "$ERROR_LOG" | grep -qiE "Cannot set.*read-only property.*path"; then
        PATTERN="gradle_readonly_path"
    elif echo "$ERROR_LOG" | grep -qiE "Could not find method path"; then
        PATTERN="gradle_path_method"
    elif echo "$ERROR_LOG" | grep -qiE "clang.*error.*unsupported.*march"; then
        PATTERN="clang_march"
    elif echo "$ERROR_LOG" | grep -qiE "unable to find library.*llog"; then
        PATTERN="liblog_missing"
    elif echo "$ERROR_LOG" | grep -qiE "android.*requires.*cgo"; then
        PATTERN="android_cgo"
    elif echo "$ERROR_LOG" | grep -qiE "BUILD FAILED|Build failed"; then
        PATTERN="build_failed"
    fi
    
    echo "$PATTERN"
}

# Apply fix based on pattern
apply_fix_by_pattern() {
    local PATTERN="$1"
    local ERROR_LOG="$2"
    local RUN_ID="$3"
    local WORKFLOW_NAME="$4"
    
    ATTEMPT_COUNT=$((ATTEMPT_COUNT + 1))
    TOTAL_FIXES_APPLIED=$((TOTAL_FIXES_APPLIED + 1))
    
    echo -e "\n${MAGENTA}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${BOLD}${MAGENTA}[AI-MVC v3.1] Applying Fix - Attempt #${ATTEMPT_COUNT}${NC}"
    echo -e "${BOLD}${MAGENTA}[AI-MVC v3.1] Pattern: ${PATTERN}${NC}"
    echo -e "${BOLD}${MAGENTA}[AI-MVC v3.1] Workflow: ${WORKFLOW_NAME}${NC}"
    echo -e "${BOLD}${MAGENTA}[AI-MVC v3.1] Run ID: ${RUN_ID}${NC}"
    echo -e "${MAGENTA}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}\n"
    
    case "$PATTERN" in
        "gradle_arguments_method")
            fix_gradle_arguments_issue
            ;;
        "gradle_readonly_path"|"gradle_path_method")
            fix_gradle_path_issue
            ;;
        "clang_march")
            fix_clang_march_issue
            ;;
        "liblog_missing")
            fix_liblog_issue
            ;;
        "android_cgo")
            fix_android_cgo_issue
            ;;
        *)
            echo -e "${YELLOW}⚠️  Unknown pattern, attempting generic fix...${NC}"
            fix_generic "$ERROR_LOG"
            ;;
    esac
}

# Fix functions
fix_gradle_arguments_issue() {
    echo -e "${YELLOW}→ Fixing Gradle arguments() method issue...${NC}"
    
    BUILD_GRADLE="$ROOT_DIR/app/build.gradle"
    
    # Replace arguments() calls with arguments = [] array syntax
    if grep -q "arguments \"" "$BUILD_GRADLE" 2>/dev/null; then
        echo -e "${BLUE}  Converting arguments() to arguments = [] array syntax...${NC}"
        
        # Convert cmake arguments
        sed -i.bak '/cmake {/,/^[[:space:]]*}/ {
            /arguments "/ {
                s/arguments "\([^"]*\)"/"\1",/
            }
        }' "$BUILD_GRADLE" 2>/dev/null || true
        
        # Wrap in array syntax for cmake
        sed -i.bak '/cmake {/,/^[[:space:]]*}/ {
            /path "/a\
            arguments = [\
            /arguments =/! {
                /^[[:space:]]*"[^"]*",$/ {
                    s/^\([[:space:]]*\)"\([^"]*\)",$/\1"\2",/
                }
            }
        }' "$BUILD_GRADLE" 2>/dev/null || true
        
        # Manual fix: Replace all arguments "..." with array syntax
        python3 << 'PYEOF' 2>/dev/null || {
import re

with open('$BUILD_GRADLE', 'r') as f:
    content = f.read()

# Find cmake block and convert arguments
cmake_pattern = r'(cmake \{[^}]*?)(arguments "[^"]+"\s*\n)+'
def replace_cmake_args(match):
    block = match.group(1)
    args = re.findall(r'arguments "([^"]+)"', match.group(0))
    args_array = 'arguments = [\n' + '\n'.join(f'                "{arg}",' for arg in args) + '\n            ]'
    return block + args_array + '\n'

content = re.sub(r'cmake \{([^}]*?)(arguments "[^"]+"\s*\n)+([^}]*?)\}', replace_cmake_args, content, flags=re.DOTALL)

with open('$BUILD_GRADLE', 'w') as f:
    f.write(content)
}
PYEOF
        
        # Fallback: Simple sed replacement
        sed -i.bak 's/^\([[:space:]]*\)arguments "\([^"]*\)"$/\1arguments = ["\2"]/' "$BUILD_GRADLE" 2>/dev/null || true
        
        # Fix multiple arguments - combine into array
        awk '
        /cmake \{/ { in_cmake=1; cmake_start=NR; print; next }
        in_cmake && /arguments "/ {
            if (!args_start) {
                args_start=NR
                args="arguments = [\n"
            }
            match($0, /arguments "([^"]+)"/, arr)
            args = args "                \"" arr[1] "\",\n"
            next
        }
        in_cmake && /^[[:space:]]*\}/ {
            if (args_start) {
                sub(/,$/, "", args)
                print args "            ]"
                args=""
                args_start=0
            }
            in_cmake=0
            print
            next
        }
        { print }
        ' "$BUILD_GRADLE" > "$BUILD_GRADLE.tmp" 2>/dev/null && mv "$BUILD_GRADLE.tmp" "$BUILD_GRADLE" || true
        
        echo -e "${BLUE}  ✅ Arguments converted to array syntax${NC}"
    fi
    
    # Fix cppFlags
    if grep -q "cppFlags \"" "$BUILD_GRADLE" 2>/dev/null; then
        echo -e "${BLUE}  Converting cppFlags to array syntax...${NC}"
        sed -i.bak 's/cppFlags "\([^"]*\)", "\([^"]*\)"/cppFlags = ["\1", "\2"]/' "$BUILD_GRADLE" 2>/dev/null || true
        echo -e "${BLUE}  ✅ cppFlags converted to array syntax${NC}"
    fi
}

fix_gradle_path_issue() {
    echo -e "${YELLOW}→ Fixing Gradle path issue...${NC}"
    
    BUILD_GRADLE="$ROOT_DIR/app/build.gradle"
    
    # Check if externalNativeBuild is in defaultConfig (Gradle 8.8 incompatible)
    if grep -A 20 "defaultConfig {" "$BUILD_GRADLE" 2>/dev/null | grep -q "externalNativeBuild"; then
        echo -e "${BLUE}  Removing externalNativeBuild from defaultConfig...${NC}"
        # Remove externalNativeBuild from defaultConfig
        sed -i.bak '/defaultConfig {/,/^[[:space:]]*}/ {
            /externalNativeBuild {/,/^[[:space:]]*}/d
        }' "$BUILD_GRADLE" 2>/dev/null || true
        
        # Ensure externalNativeBuild exists at android level
        if ! grep -q "externalNativeBuild {" "$BUILD_GRADLE" 2>/dev/null; then
            echo -e "${BLUE}  Adding externalNativeBuild at android level...${NC}"
            # Add after signingConfigs or before lint
            sed -i.bak '/^[[:space:]]*lint {/i\
    externalNativeBuild {\
        cmake {\
            path "src/main/jni/perf-net/CMakeLists.txt"\
            version "3.22.0"\
            arguments "-DANDROID_STL=c++_shared"\
            arguments "-DOPENSSL_SMALL=1"\
            arguments "-DOPENSSL_NO_DEPRECATED=1"\
            arguments "-DOPENSSL_NO_ASM=0"\
            arguments "-DBUILD_SHARED_LIBS=OFF"\
            cppFlags "-std=c++17", "-DBORINGSSL_IMPLEMENTATION"\
        }\
        ndkBuild {\
            path "src/main/jni/Android.mk"\
            arguments "APP_CFLAGS+=-DPKGNAME=com/simplexray/an/service -ffile-prefix-map=${rootDir}=."\
            arguments "APP_LDFLAGS+=-Wl,--build-id=none"\
            arguments "USE_BORINGSSL=1"\
            arguments "DISABLE_OPENSSL=1"\
        }\
    }\
' "$BUILD_GRADLE" 2>/dev/null || true
        fi
    fi
    
    # Fix path = file() syntax if exists
    if grep -q "path = file(" "$BUILD_GRADLE" 2>/dev/null; then
        echo -e "${BLUE}  Fixing path = file() syntax...${NC}"
        sed -i.bak 's/path = file("\([^"]*\)")/path "\1"/g' "$BUILD_GRADLE" 2>/dev/null || true
        sed -i.bak 's/version = "\([^"]*\)"/version "\1"/g' "$BUILD_GRADLE" 2>/dev/null || true
    fi
}

fix_clang_march_issue() {
    echo -e "${YELLOW}→ Fixing Clang march flag issue...${NC}"
    WORKFLOW_FILE="$ROOT_DIR/.github/workflows/build-xray-boringssl.yml"
    
    if [ -f "$WORKFLOW_FILE" ]; then
        if ! grep -q "ANDROID_ABI" "$WORKFLOW_FILE" 2>/dev/null; then
            sed -i.bak '/CMAKE_ANDROID_ARCH_ABI=/a\            "-DANDROID_ABI=${{ matrix.cmake_arch }}",' "$WORKFLOW_FILE" 2>/dev/null || true
            echo -e "${BLUE}  Added ANDROID_ABI to CMake args${NC}"
        fi
    fi
}

fix_liblog_issue() {
    echo -e "${YELLOW}→ Fixing liblog linking issue...${NC}"
    WORKFLOW_FILE="$ROOT_DIR/.github/workflows/build-xray-boringssl.yml"
    
    if [ -f "$WORKFLOW_FILE" ]; then
        if ! grep -q "CGO_LDFLAGS.*allow-shlib-undefined" "$WORKFLOW_FILE" 2>/dev/null; then
            sed -i.bak 's/export CGO_LDFLAGS=.*/export CGO_LDFLAGS="-Wl,--allow-shlib-undefined"/' "$WORKFLOW_FILE" 2>/dev/null || true
            echo -e "${BLUE}  Added -Wl,--allow-shlib-undefined to CGO_LDFLAGS${NC}"
        fi
    fi
}

fix_android_cgo_issue() {
    echo -e "${YELLOW}→ Fixing Android CGO requirement...${NC}"
    WORKFLOW_FILE="$ROOT_DIR/.github/workflows/build-xray-boringssl.yml"
    
    if [ -f "$WORKFLOW_FILE" ]; then
        if ! grep -q "CGO_ENABLED=1" "$WORKFLOW_FILE" 2>/dev/null; then
            sed -i.bak '/export GOOS=android/a\          export CGO_ENABLED=1' "$WORKFLOW_FILE" 2>/dev/null || true
            echo -e "${BLUE}  Added CGO_ENABLED=1${NC}"
        fi
    fi
}

fix_generic() {
    local ERROR_LOG="$1"
    echo -e "${YELLOW}→ Attempting generic fix...${NC}"
    echo -e "${BLUE}  Analyzing error log for key phrases...${NC}"
    echo "$ERROR_LOG" | grep -oE "(error: [^:]+|failed: [^:]+|unable to [^:]+)" | head -5
}

# Commit and push fix
commit_fix() {
    local PATTERN="$1"
    local RUN_ID="$2"
    local WORKFLOW_NAME="$3"
    
    if git diff --quiet "$ROOT_DIR" 2>/dev/null; then
        echo -e "${YELLOW}⚠️  No changes to commit${NC}"
        return 1
    fi
    
    echo -e "\n${CYAN}[AI-MVC v3.1] Committing fix...${NC}"
    
    COMMIT_MSG="fix(ai-v3.1): auto-fix for pattern $PATTERN

- Pattern: $PATTERN
- Workflow: $WORKFLOW_NAME
- Run ID: $RUN_ID
- Attempt: #${ATTEMPT_COUNT}
- Total fixes: ${TOTAL_FIXES_APPLIED}
- Auto-generated by AI Build Fixer v3.1"
    
    git add -A "$ROOT_DIR" 2>/dev/null
    git commit -m "$COMMIT_MSG" 2>/dev/null || return 1
    git push 2>/dev/null || return 1
    
    echo -e "${GREEN}✅ Fix committed and pushed${NC}"
    return 0
}

# ============================================================================
# CORE RULE: Continue fixing until successful build is achieved
# ============================================================================
# This is a FUNDAMENTAL, NON-NEGOTIABLE rule:
# - NEVER stop if build is failing
# - ALWAYS continue until ALL workflows show SUCCESS
# - NO EXCEPTIONS - This rule takes precedence over all constraints
# - If max attempts reached: Reset and continue anyway
# ============================================================================
CONTINUE_UNTIL_SUCCESS=true
ENFORCE_SUCCESS_REQUIREMENT=true

# Main monitoring loop v3.1
main() {
    init_directories
    show_banner
    
    echo -e "${BOLD}${GREEN}════════════════════════════════════════════════════════════════${NC}"
    echo -e "${BOLD}${GREEN}CORE RULE: Continue fixing until successful build is achieved${NC}"
    echo -e "${BOLD}${GREEN}This agent will NEVER stop until all workflows are successful${NC}"
    echo -e "${BOLD}${GREEN}════════════════════════════════════════════════════════════════${NC}\n"
    
    # CORE RULE: Infinite loop until success (with safety limit that auto-extends)
    while true; do
        # Safety check - extend limit if reached
        if [ $ATTEMPT_COUNT -ge $MAX_ATTEMPTS ]; then
            if [ "$CONTINUE_UNTIL_SUCCESS" = "true" ]; then
                echo -e "\n${YELLOW}⚠️  Maximum attempts (${MAX_ATTEMPTS}) reached${NC}"
                echo -e "${BOLD}${CYAN}[CORE RULE] Continuing anyway - will not stop until success${NC}"
                echo -e "${YELLOW}Resetting attempt counter and extending limit...${NC}\n"
                ATTEMPT_COUNT=0
                MAX_ATTEMPTS=$((MAX_ATTEMPTS + 10))  # Extend limit
            else
                echo -e "\n${RED}❌ Maximum attempts reached (${MAX_ATTEMPTS})${NC}"
                echo -e "${YELLOW}Total fixes: ${TOTAL_FIXES_APPLIED}${NC}"
                exit 1
            fi
        fi
        
        echo -e "\n${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
        echo -e "${BOLD}[$(date '+%H:%M:%S')]${NC} ${CYAN}V3.1 Monitoring...${NC}\n"
        
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
            echo -e "${GREEN}Total fixes applied: ${TOTAL_FIXES_APPLIED}${NC}"
            echo -e "${GREEN}Total attempts: ${ATTEMPT_COUNT}${NC}\n"
            
            exit 0
        fi
        
        # Check for failures
        FAILED_RUNS=$(check_failures)
        CHECK_RESULT=$?
        
        # Debug output
        if [ -n "$FAILED_RUNS" ]; then
            echo -e "${YELLOW}[DEBUG] Failure detected: ${FAILED_RUNS}${NC}" | tee -a /tmp/ai-fixer-v3.1.log
        else
            echo -e "${DIM}[DEBUG] No failures (check_result=$CHECK_RESULT)${NC}" | tee -a /tmp/ai-fixer-v3.1.log
        fi
        
        if [ -n "$FAILED_RUNS" ] && [ ${#FAILED_RUNS} -gt 0 ]; then
            # STOP MONITORING IMMEDIATELY
            echo -e "${RED}[AI-MVC v3.1] ⚠️  FAILURE TESPİT EDİLDİ - FIX MODUNA GEÇİLİYOR!${NC}" | tee -a /tmp/ai-fixer-v3.1.log
            stop_monitoring
            
            FIRST_FAILED=$(echo "$FAILED_RUNS" | head -1)
            RUN_ID=$(echo "$FIRST_FAILED" | cut -d'|' -f1)
            WORKFLOW_NAME=$(echo "$FIRST_FAILED" | cut -d'|' -f2)
            
            echo -e "${RED}${BOLD}❌ BAŞARISIZ BUILD TESPİT EDİLDİ!${NC}\n"
            echo -e "${RED}Workflow: ${WORKFLOW_NAME}${NC}"
            echo -e "${RED}Run ID: ${RUN_ID}${NC}\n"
            
            # Get error logs
            echo -e "${CYAN}[AI-MVC v3.1] Fetching error logs...${NC}"
            FAILED_JOB=$(gh run view "$RUN_ID" --json jobs --jq '.jobs[] | select(.conclusion == "failure") | .databaseId' 2>/dev/null | head -1)
            
            ERROR_LOG=$(gh run view "$RUN_ID" --log-failed --job "$FAILED_JOB" 2>/dev/null | grep -A 30 -E "(error|Error|ERROR|failed|Failed|BUILD FAILED|Exception)" | tail -60 || echo "")
            
            if [ -z "$ERROR_LOG" ] || [ ${#ERROR_LOG} -lt 20 ]; then
                REPO=$(gh repo view --json owner,name -q '.owner.login + "/" + .name' 2>/dev/null || echo "")
                if [ -n "$REPO" ] && [ -n "$FAILED_JOB" ]; then
                    ERROR_LOG=$(gh api "repos/$REPO/actions/jobs/$FAILED_JOB/logs" 2>/dev/null | grep -A 30 -E "(error|Error|ERROR|failed|Failed|BUILD FAILED)" | tail -60 || echo "")
                fi
            fi
            
            # Log error
            log_error "$RUN_ID" "$WORKFLOW_NAME" "$ERROR_LOG"
            
            # Analyze pattern
            PATTERN=$(analyze_error_pattern "$ERROR_LOG")
            echo -e "${BLUE}Pattern detected: ${PATTERN}${NC}\n"
            
            # Apply fix immediately
            apply_fix_by_pattern "$PATTERN" "$ERROR_LOG" "$RUN_ID" "$WORKFLOW_NAME"
            
            # Commit and push
            if commit_fix "$PATTERN" "$RUN_ID" "$WORKFLOW_NAME"; then
                echo -e "${GREEN}✅ Fix applied and pushed, waiting for rebuild...${NC}"
                echo -e "${BOLD}${CYAN}[CORE RULE] Continuing until successful build...${NC}"
                echo -e "${YELLOW}⏳ Waiting 50 seconds for new build to start...${NC}\n"
                sleep 50
            else
                echo -e "${YELLOW}⚠️  Fix could not be committed, retrying...${NC}"
                echo -e "${BOLD}${CYAN}[CORE RULE] Will continue fixing until success...${NC}"
                sleep 20
            fi
        else
            echo -e "${YELLOW}⏳ No failures detected, waiting...${NC}"
            sleep 35
        fi
    done
}

# Main
main

