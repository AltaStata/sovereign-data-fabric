#!/bin/bash
# GCP (GCS) 1GB / 5GB only (full run counts: 5×1GB, 3×5GB).
# JVM heap capped via perf-common.sh (default 10g; override PERF_MAX_HEAP).
# Writes combined-gcp-large-*.log so summarize-performance-logs.py can pick it up.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=perf-common.sh
source "$SCRIPT_DIR/perf-common.sh"
perf_export_profile large
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

if [[ ! -d "$HOME/.altastata/accounts/google.rsa.bob123" ]]; then
  echo "ERROR: missing account ~/.altastata/accounts/google.rsa.bob123" >&2
  exit 1
fi

STAMP="$(date +%Y%m%d-%H%M%S)"
LOG_FILE="$LOG_DIR/combined-gcp-large-$STAMP.log"

cd "$ROOT_DIR"
export PERF_PROFILE=large
perf_echo_heap large
echo "Log: $LOG_FILE"
caffeinate -dims ./gradlew :altastata-examples:runExample \
  -PmainClass=com.altastata.performance.gcp.PerformanceTestCombinedGCP \
  -PappArgs=large \
  -PmaxHeap="$(perf_max_heap large)" \
  2>&1 | tee "$LOG_FILE"

perf_refresh_live_table
echo "Done. Review: $LOG_FILE"
