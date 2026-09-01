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

# Build hadoop JAR, upload and replace in pip-installed altastata on the LinuxONE host,
# copy the HPCS Python test (test_python_hpcs_user.py) and run it there.
#
# Required: LINUXONE_HOST. Optional: LINUXONE_SSH_KEY, LINUXONE_USER, REMOTE_PROPERTIES_FILE.
# On host: ~/.hpcs-api-key and HPCS user properties file (e.g. ~/hpcs-user-hpcs.properties).
# S3 credentials for the HPCS user must already exist (run configure-hpcs-user-linuxone.sh once).
#
# Optional: SKIP_JAR_BUILD=1 to skip build/upload (use existing JAR on host).
#
# Usage (from project root):
#   export LINUXONE_HOST=<your-linuxone-host>
#   export LINUXONE_SSH_KEY=~/.ssh/id_rsa   # optional
#   ./altastata-hadoop/scripts/run-test-hpcs-linuxone.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
HPCS_TEST_PY="$SCRIPT_DIR/test_python_hpcs_user.py"

if [ -z "$LINUXONE_HOST" ]; then
  echo "Error: LINUXONE_HOST is not set." >&2
  echo "Example: export LINUXONE_HOST=<your-linuxone-host>" >&2
  exit 1
fi

if [ ! -f "$HPCS_TEST_PY" ]; then
  echo "Error: HPCS test script not found: $HPCS_TEST_PY" >&2
  exit 1
fi

LINUXONE_USER="${LINUXONE_USER:-ubuntu}"
REMOTE="${LINUXONE_USER}@${LINUXONE_HOST}"
REMOTE_PROPS="${REMOTE_PROPERTIES_FILE:-/home/${LINUXONE_USER}/hpcs-user-hpcs.properties}"

if [ -n "$LINUXONE_SSH_KEY" ]; then
  [ -f "$LINUXONE_SSH_KEY" ] || { echo "Error: LINUXONE_SSH_KEY file not found: $LINUXONE_SSH_KEY" >&2; exit 1; }
  SCP_OPTS=(-i "$LINUXONE_SSH_KEY")
  SSH_OPTS=(-i "$LINUXONE_SSH_KEY")
else
  SCP_OPTS=()
  SSH_OPTS=()
fi

if [ "$SKIP_JAR_BUILD" != "1" ]; then
  echo "=== Build hadoop JAR, upload and replace in pip package on host ===" >&2
  "$SCRIPT_DIR/build-and-upload-hadoop-jar-linuxone.sh"
  echo ""
fi

echo "Copying test_python_hpcs_user.py to $REMOTE..." >&2
scp "${SCP_OPTS[@]}" "$HPCS_TEST_PY" "$REMOTE:~/test_python_hpcs_user.py"

echo "Running HPCS test on host (properties: $REMOTE_PROPS)..." >&2
ssh "${SSH_OPTS[@]}" "$REMOTE" "bash -s" << REMOTE_SCRIPT
set -e
if [ ! -f ~/.hpcs-api-key ] || [ ! -s ~/.hpcs-api-key ]; then
  echo "ERROR: ~/.hpcs-api-key missing or empty on host. Create it with your HPCS API key." >&2
  exit 1
fi
export HPCS_API_KEY=\$(cat ~/.hpcs-api-key)
# Free port 25333 so a fresh JVM starts with HPCS_API_KEY
if command -v fuser >/dev/null 2>&1; then fuser -k 25333/tcp 2>/dev/null || true; fi
if command -v lsof >/dev/null 2>&1; then pid=\$(lsof -t -i:25333 2>/dev/null); [ -n "\$pid" ] && kill "\$pid" 2>/dev/null || true; fi
sleep 1
export PYTHONUNBUFFERED=1
echo "[host] Starting python3 ~/test_python_hpcs_user.py $REMOTE_PROPS ..."
python3 -u ~/test_python_hpcs_user.py "$REMOTE_PROPS"
echo "[host] python3 exited with code \$?"
REMOTE_SCRIPT

echo "Done."
