#!/bin/bash
# Auto-Fix Workflow v3 - Next-gen autonomous fixing

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HYPER_AUTO_FIX_V3="$SCRIPT_DIR/hyper-auto-fix-v3.sh"

if [ -f "$HYPER_AUTO_FIX_V3" ]; then
    "$HYPER_AUTO_FIX_V3" "$@"
else
    echo "❌ hyper-auto-fix-v3.sh not found!"
    exit 1
fi


