#!/usr/bin/env bash
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

# Check where the altastata pip package and its JAR are on the LinuxONE host.
# Required: LINUXONE_HOST. Optional: LINUXONE_SSH_KEY, LINUXONE_USER.
#
# Usage (from project root):
#   export LINUXONE_HOST=<your-linuxone-host>
#   export LINUXONE_SSH_KEY=~/.ssh/id_rsa   # optional
#   ./altastata-hadoop/scripts/check-package-location-linuxone.sh

set -e

if [ -z "$LINUXONE_HOST" ]; then
  echo "Error: LINUXONE_HOST is not set."
  echo "Example: export LINUXONE_HOST=<your-linuxone-host>"
  exit 1
fi

LINUXONE_USER="${LINUXONE_USER:-ubuntu}"
REMOTE="${LINUXONE_USER}@${LINUXONE_HOST}"
if [ -n "$LINUXONE_SSH_KEY" ] && [ -f "$LINUXONE_SSH_KEY" ]; then
  SSH_OPTS=(-i "$LINUXONE_SSH_KEY")
else
  SSH_OPTS=()
fi

ssh "${SSH_OPTS[@]}" "$REMOTE" bash -s << 'REMOTE'
echo "=== altastata package location ==="
python3 -c "import altastata, os; d=os.path.dirname(altastata.__file__); print('Package dir:', d)"
echo ""
echo "=== package dir contents ==="
ALTastata_DIR=$(python3 -c "import altastata, os; print(os.path.dirname(altastata.__file__))" 2>/dev/null | tr -d '\n\r')
ls -la "$ALTastata_DIR" 2>/dev/null
echo ""
echo "=== lib/ (if present) ==="
ls -la "$ALTastata_DIR/lib" 2>/dev/null || echo "(no lib/)"
echo ""
echo "=== jars/ (if present) ==="
ls -la "$ALTastata_DIR/jars" 2>/dev/null || echo "(no jars/)"
echo ""
echo "=== JAR locations ==="
find "$ALTastata_DIR" -name "altastata-hadoop*.jar" 2>/dev/null || true
REMOTE
