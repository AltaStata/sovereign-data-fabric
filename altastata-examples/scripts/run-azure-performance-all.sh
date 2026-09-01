#!/bin/bash
# Azure Blob perf: smoke → full (1MB–100MB) → large (1GB / 5GB).
# Runs sequentially with JVM heap caps (perf-common.sh). One log per phase.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
LOG_DIR="$ROOT_DIR/altastata-examples/build/performance-logs"
mkdir -p "$LOG_DIR"

STAMP="$(date +%Y%m%d-%H%M%S)"
MASTER_LOG="$LOG_DIR/combined-azure-all-$STAMP.log"

echo "Azure performance suite — $STAMP" | tee "$MASTER_LOG"
echo "Phases: smoke → full → large (heap caps via perf-common.sh)" | tee -a "$MASTER_LOG"
echo "Master log: $MASTER_LOG" | tee -a "$MASTER_LOG"

cd "$ROOT_DIR"
./gradlew --stop 2>/dev/null || true

for phase in smoke full large; do
  echo "" | tee -a "$MASTER_LOG"
  echo "========== PHASE: $phase $(date) ==========" | tee -a "$MASTER_LOG"
  "$SCRIPT_DIR/run-azure-performance-${phase}.sh" 2>&1 | tee -a "$MASTER_LOG"
done

echo "" | tee -a "$MASTER_LOG"
echo "All Azure phases complete — $(date)" | tee -a "$MASTER_LOG"
