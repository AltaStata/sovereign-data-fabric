#!/bin/bash
# Clean leftover Azure / GCP / AWS objects from combined performance harnesses.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

GCP_CREDS_CANDIDATE="$ROOT_DIR/altastata-admin/altastata_googlestoragecheck.json"
if [[ -z "${GOOGLE_APPLICATION_CREDENTIALS:-}" && -f "$GCP_CREDS_CANDIDATE" ]]; then
  export GOOGLE_APPLICATION_CREDENTIALS="$GCP_CREDS_CANDIDATE"
  echo "Set GOOGLE_APPLICATION_CREDENTIALS=$GOOGLE_APPLICATION_CREDENTIALS"
elif [[ -n "${GOOGLE_APPLICATION_CREDENTIALS:-}" ]]; then
  echo "Using GOOGLE_APPLICATION_CREDENTIALS=$GOOGLE_APPLICATION_CREDENTIALS"
else
  echo "GOOGLE_APPLICATION_CREDENTIALS unset and $GCP_CREDS_CANDIDATE missing — GCP direct cleanup will skip"
fi

cd "$ROOT_DIR"
./gradlew :altastata-examples:runExample \
  -PmainClass=com.altastata.performance.PerformanceCleanupClouds

echo "Done."
