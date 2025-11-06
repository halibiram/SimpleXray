#!/bin/bash
# Activation script for AI Build Fixer v3.0 Multi-Workflow

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AI_FIXER_V3_MULTI="$SCRIPT_DIR/ai-build-fixer-v3-multi.sh"

echo "🤖 Activating AI Build Fixer v3.0 Multi-Workflow..."
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

echo "📊 Monitoring workflows:"
echo "  → Build Xray-core with BoringSSL"
echo "  → Auto Release"
echo ""

# Run the AI fixer v3 multi
"$AI_FIXER_V3_MULTI"





