#!/bin/bash
# Monitor Workflow v3 - Enhanced with ML and predictive features

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HYPER_MONITOR_V3="$SCRIPT_DIR/hyper-monitor-v3.sh"

if [ -f "$HYPER_MONITOR_V3" ]; then
    "$HYPER_MONITOR_V3" "$@"
else
    echo "❌ hyper-monitor-v3.sh not found!"
    exit 1
fi


