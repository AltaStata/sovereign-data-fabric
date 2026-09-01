#!/bin/bash
# Full AWS S3 performance: all sizes including 1GB/5GB — AltaStata (amazon.rsa.bob123) vs native S3.
# AWS only. Do not generalize to GCP/Azure.
# Full run: use -PappArgs=full with PerformanceTestCombinedAWS

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=perf-common.sh
source "$SCRIPT_DIR/perf-common.sh"
perf_export_profile full
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
LOG_DIR="$ROOT_DIR/altastata-examples/build/performance-logs"
mkdir -p "$LOG_DIR"

# Uses DefaultAWSCredentialsProviderChain (AWS CLI / env / instance profile)
if [[ -n "${AWS_REGION:-}" ]]; then
  echo "Using AWS_REGION=$AWS_REGION"
elif [[ -n "${AWS_DEFAULT_REGION:-}" ]]; then
  echo "Using AWS_DEFAULT_REGION=$AWS_DEFAULT_REGION"
else
  echo "AWS_REGION unset; PerformanceTestCombinedAWS will use DefaultAwsRegionProviderChain or us-east-1"
fi

if [[ ! -d "$HOME/.altastata/accounts/amazon.rsa.bob123" ]]; then
  echo "ERROR: missing account ~/.altastata/accounts/amazon.rsa.bob123" >&2
  exit 1
fi

STAMP="$(date +%Y%m%d-%H%M%S)"
LOG_FILE="$LOG_DIR/combined-aws-full-$STAMP.log"

cd "$ROOT_DIR"
export PERF_PROFILE=full
perf_echo_heap full
./gradlew :altastata-examples:runExample \
  -PmainClass=com.altastata.performance.aws.PerformanceTestCombinedAWS \
  -PappArgs=full \
  -PmaxHeap="$(perf_max_heap full)" \
  2>&1 | tee "$LOG_FILE"

perf_refresh_live_table
echo "Done. Review: $LOG_FILE"
