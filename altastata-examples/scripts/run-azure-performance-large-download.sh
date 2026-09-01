#!/bin/bash
# Re-run one large file.
# Usage:
#   ./run-azure-performance-large-download.sh binary-5GB.bin [runs]
#   MODE=download-only ./run-azure-performance-large-download.sh binary-5GB.bin [runs]
#   MODE=altastata-only ./run-azure-performance-large-download.sh binary-5GB.bin [runs]
# Modes: file-only (default, Azure+AltaStata upload+download),
#         download-only (both downloads; blobs must exist),
#         altastata-only (AltaStata upload+download + Azure download; skip Azure upload)

set -euo pipefail

FILE_NAME="${1:?Usage: $0 <file-name> [runs]}"
RUNS="${2:-3}"
MODE="${MODE:-file-only}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=perf-common.sh
source "$SCRIPT_DIR/perf-common.sh"
perf_export_profile large
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
LOG_DIR="$ROOT_DIR/altastata-examples/build/performance-logs"
mkdir -p "$LOG_DIR"

TEST_FILES="$HOME/.altastata/test-files"
if [[ ! -f "$TEST_FILES/$FILE_NAME" ]]; then
  echo "ERROR: Missing $TEST_FILES/$FILE_NAME" >&2
  exit 1
fi

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
LOG_FILE="$LOG_DIR/combined-azure-${MODE}-${FILE_NAME//\//-}-$STAMP.log"

cd "$ROOT_DIR"
perf_echo_heap large
echo "Mode=$MODE: $FILE_NAME ($RUNS runs)"
caffeinate -dims ./gradlew :altastata-examples:runExample \
  -PmainClass=com.altastata.performance.azure.PerformanceTestCombinedAzure \
  -PappArgs="$MODE $FILE_NAME $RUNS" \
  -PmaxHeap="$(perf_max_heap large)" \
  2>&1 | tee "$LOG_FILE"

perf_refresh_live_table
echo "Done. Review: $LOG_FILE"
