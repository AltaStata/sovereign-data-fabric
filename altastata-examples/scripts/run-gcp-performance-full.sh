#!/bin/bash
# Full GCP (GCS) performance benchmark including 1GB and 5GB (long run).
# GCP only — not representative of AWS or Azure throughput.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=perf-common.sh
source "$SCRIPT_DIR/perf-common.sh"
perf_export_profile full
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
LOG_DIR="$ROOT_DIR/altastata-examples/build/performance-logs"
mkdir -p "$LOG_DIR"

CREDS="${GOOGLE_APPLICATION_CREDENTIALS:-$ROOT_DIR/altastata-admin/altastata_googlestoragecheck.json}"
if [[ ! -f "$CREDS" ]]; then
  echo "ERROR: GCP credentials not found. Set GOOGLE_APPLICATION_CREDENTIALS" >&2
  exit 1
fi
export GOOGLE_APPLICATION_CREDENTIALS="$CREDS"

STAMP="$(date +%Y%m%d-%H%M%S)"
LOG_FILE="$LOG_DIR/combined-gcp-full-$STAMP.log"

cd "$ROOT_DIR"
export PERF_PROFILE=full
perf_echo_heap full
./gradlew :altastata-examples:runExample \
  -PmainClass=com.altastata.performance.gcp.PerformanceTestCombinedGCP \
  -PappArgs=full \
  -PmaxHeap="$(perf_max_heap full)" \
  2>&1 | tee "$LOG_FILE"

perf_refresh_live_table
echo "Done. Review: $LOG_FILE"

# Free ~12GB: large test files are only needed for this benchmark
TEST_FILES="$HOME/.altastata/test-files"
for f in text-1GB.txt text-5GB.txt binary-1GB.bin binary-5GB.bin; do
  if [[ -f "$TEST_FILES/$f" ]]; then
    rm -f "$TEST_FILES/$f"
    echo "Removed $TEST_FILES/$f"
  fi
done
