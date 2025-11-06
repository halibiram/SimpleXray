#!/bin/bash
# Activation script for AI Build Fixer v2.0

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AI_FIXER_V2="$SCRIPT_DIR/ai-build-fixer-v2.sh"

echo "🤖 Activating AI Build Fixer v2.0..."
echo ""

# Check prerequisites
if ! command -v gh &> /dev/null; then
    echo "❌ GitHub CLI (gh) not found. Please install it first."
    exit 1
fi

if ! gh auth status &>/dev/null; then
    echo "❌ GitHub CLI not authenticated. Run: gh auth login"
    exit 1
fi

if ! command -v jq &> /dev/null; then
    echo "⚠️  jq not found. Enhanced pattern matching will be limited."
    echo "   Install jq for better error pattern matching: sudo apt install jq"
    echo ""
fi

# Run the AI fixer v2
if [ $# -eq 0 ]; then
    echo "📊 Monitoring latest workflow runs with v2 enhanced learning..."
    "$AI_FIXER_V2"
else
    echo "🔍 Analyzing specific run: $1"
    "$AI_FIXER_V2" "Build Xray-core with BoringSSL" "$1"
fi

