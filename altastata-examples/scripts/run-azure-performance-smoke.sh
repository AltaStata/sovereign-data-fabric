#!/bin/bash
# Smoke Azure Blob performance: 1MB / 10MB / 100MB — AltaStata (azure.rsa.bob123) vs native Blob.
# Azure only. Do not generalize to GCP/AWS.
# Full run: ./scripts/run-azure-performance-full.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=perf-common.sh
source "$SCRIPT_DIR/perf-common.sh"
perf_export_profile smoke
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
LOG_DIR="$ROOT_DIR/altastata-examples/build/performance-logs"
mkdir -p "$LOG_DIR"

# Prefer env; otherwise PerformanceTestCombinedAzure loads ~/.altastata/admin/azure_admin.properties
if [[ -n "${AZURE_STORAGE_CONNECTION_STRING:-}" && ${#AZURE_STORAGE_CONNECTION_STRING} -gt 50 ]]; then
  echo "Using AZURE_STORAGE_CONNECTION_STRING from environment"
else
  ADMIN_PROPS="$HOME/.altastata/admin/azure_admin.properties"
  if [[ ! -f "$ADMIN_PROPS" ]]; then
    echo "ERROR: Set AZURE_STORAGE_CONNECTION_STRING or provide $ADMIN_PROPS" >&2
    exit 1
  fi
  echo "Will use adminStorageConnectionString from $ADMIN_PROPS"
fi

STAMP="$(date +%Y%m%d-%H%M%S)"
LOG_FILE="$LOG_DIR/combined-azure-smoke-$STAMP.log"

cd "$ROOT_DIR"
export PERF_PROFILE=smoke
perf_echo_heap smoke
./gradlew :altastata-examples:runExample \
  -PmainClass=com.altastata.performance.azure.PerformanceTestCombinedAzure \
  -PappArgs=smoke \
  -PmaxHeap="$(perf_max_heap smoke)" \
  2>&1 | tee "$LOG_FILE"

perf_refresh_live_table
echo "Done. Review: $LOG_FILE"
