#!/bin/bash
# Azure Blob 1GB / 5GB only (full run counts: 5×1GB, 3×5GB).
# JVM heap capped via perf-common.sh (default 10g; override PERF_MAX_HEAP).
# Generate files first: ./gradlew :altastata-examples:runExample \
#   -PmainClass=com.altastata.performance.utils.TestFileGenerator -PappArgs=large

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=perf-common.sh
source "$SCRIPT_DIR/perf-common.sh"
perf_export_profile large
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
LOG_DIR="$ROOT_DIR/altastata-examples/build/performance-logs"
mkdir -p "$LOG_DIR"

TEST_FILES="$HOME/.altastata/test-files"
for f in text-1GB.txt text-5GB.txt binary-1GB.bin binary-5GB.bin; do
  if [[ ! -f "$TEST_FILES/$f" ]]; then
    echo "Missing $TEST_FILES/$f — generating 1GB/5GB test files..."
    cd "$ROOT_DIR"
    ./gradlew :altastata-examples:runExample \
      -PmainClass=com.altastata.performance.utils.TestFileGenerator \
      -PappArgs=large
    break
  fi
done

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
LOG_FILE="$LOG_DIR/combined-azure-large-$STAMP.log"

cd "$ROOT_DIR"
export PERF_PROFILE=large
perf_echo_heap large
caffeinate -dims ./gradlew :altastata-examples:runExample \
  -PmainClass=com.altastata.performance.azure.PerformanceTestCombinedAzure \
  -PappArgs=large \
  -PmaxHeap="$(perf_max_heap large)" \
  2>&1 | tee "$LOG_FILE"

perf_refresh_live_table
echo "Done. Review: $LOG_FILE"

for f in text-1GB.txt text-5GB.txt binary-1GB.bin binary-5GB.bin; do
  if [[ -f "$TEST_FILES/$f" ]]; then
    rm -f "$TEST_FILES/$f"
    echo "Removed $TEST_FILES/$f"
  fi
done
