#!/bin/bash
# Copyright (c) 2026 AltaStata Inc. All rights reserved.
#
# This software is dual-licensed. It is licensed under the Business Source License 1.1
# (BSL) for open use and evaluation, with an eventual transition to the Apache 2.0
# license on the Change Date.
#
# PATENT NOTICE: Protected by US Patent No. 10,693,660.
#
# For the full license text, see the LICENSE.md file in the root of the repository,
# or https://github.com/AltaStata/sovereign-data-fabric/blob/main/LICENSE.md

# Local-only integration test runner (azure, amazon, google, ibm).
# Set ALTASTATA_IT_SOURCE_DIR and ALTASTATA_IT_PASSWORD before running.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
cd "${REPO_ROOT}"

export RUN_REAL_UPLOAD_IT=1
: "${ALTASTATA_IT_SOURCE_DIR:?Set ALTASTATA_IT_SOURCE_DIR to a folder of test files}"
: "${ALTASTATA_IT_PASSWORD:?Set ALTASTATA_IT_PASSWORD}"

for ACC in azure amazon google ibm; do
  export ALTASTATA_IT_ACCOUNT="${ACC}.rsa.bob123"
  echo "=== Running ${ALTASTATA_IT_ACCOUNT} ==="

  ./gradlew :altastata-core:cleanTest :altastata-core:test \
    --tests "com.altastata.filesystem.securecloud.RealFolderUploadAzureITSpec" \
    --no-daemon > "/tmp/real-folder-v3-${ACC}.log" 2>&1 || true

  grep "REAL_TEST SUMMARY" "/tmp/real-folder-v3-${ACC}.log" || echo "No summary for ${ACC}"
  echo "Done ${ACC} at $(date)"
done

echo ""
echo "=== All summaries ==="
grep -h "REAL_TEST SUMMARY" /tmp/real-folder-v3-*.log 2>/dev/null || true
