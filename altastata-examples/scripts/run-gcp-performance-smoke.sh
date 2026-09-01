#!/bin/bash
# Smoke GCP (GCS) performance benchmark: 1MB / 10MB / 100MB — AltaStata vs native GCS.
# GCP only. AWS S3 / Azure Blob numbers can differ (often better); do not generalize.
# Full run (1GB / 5GB): ./scripts/run-gcp-performance-full.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=perf-common.sh
source "$SCRIPT_DIR/perf-common.sh"
perf_export_profile smoke
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
LOG_DIR="$ROOT_DIR/altastata-examples/build/performance-logs"
mkdir -p "$LOG_DIR"

CREDS="${GOOGLE_APPLICATION_CREDENTIALS:-$ROOT_DIR/altastata-admin/altastata_googlestoragecheck.json}"
if [[ ! -f "$CREDS" ]]; then
  echo "ERROR: GCP credentials not found. Set GOOGLE_APPLICATION_CREDENTIALS or place key at altastata-admin/altastata_googlestoragecheck.json" >&2
  exit 1
fi
export GOOGLE_APPLICATION_CREDENTIALS="$CREDS"

TEST_FILES="$HOME/.altastata/test-files"
for f in text-1MB.txt text-10MB.txt text-100MB.txt binary-1MB.bin binary-10MB.bin binary-100MB.bin; do
  if [[ ! -f "$TEST_FILES/$f" ]]; then
    echo "ERROR: missing $TEST_FILES/$f — generate with TestFileGenerator first" >&2
    exit 1
  fi
done

if [[ ! -d "$HOME/.altastata/accounts/google.rsa.bob123" ]]; then
  echo "ERROR: missing account ~/.altastata/accounts/google.rsa.bob123" >&2
  exit 1
fi

STAMP="$(date +%Y%m%d-%H%M%S)"
LOG_FILE="$LOG_DIR/combined-gcp-smoke-$STAMP.log"

echo "Log: $LOG_FILE"
echo "Credentials: $CREDS"

cd "$ROOT_DIR"
export PERF_PROFILE=smoke
perf_echo_heap smoke
./gradlew :altastata-examples:runExample \
  -PmainClass=com.altastata.performance.gcp.PerformanceTestCombinedGCP \
  -PappArgs=smoke \
  -PmaxHeap="$(perf_max_heap smoke)" \
  2>&1 | tee "$LOG_FILE"

echo ""
perf_refresh_live_table
echo "Done. Review: $LOG_FILE"
