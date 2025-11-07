#!/bin/bash
# Activation script for AI Build Fixer
# Can be triggered manually or by GitHub Actions

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AI_FIXER="$SCRIPT_DIR/ai-build-fixer.sh"

echo "🤖 Activating AI Build Fixer..."
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

# Check if jq is available (recommended)
if ! command -v jq &> /dev/null; then
    echo "⚠️  jq not found. Pattern matching will be limited."
    echo "   Install jq for better error pattern matching: sudo apt install jq"
    echo ""
fi

# Run the AI fixer
if [ $# -eq 0 ]; then
    echo "📊 Monitoring latest workflow runs..."
    "$AI_FIXER"
else
    echo "🔍 Analyzing specific run: $1"
    "$AI_FIXER" "$1"
fi






