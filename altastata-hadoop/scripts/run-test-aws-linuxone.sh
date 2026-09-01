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
# copy the AWS Python test and local (not-in-git) account files, then run it there.
#
# Required: LINUXONE_HOST. Optional: LINUXONE_SSH_KEY, LINUXONE_USER.
# Optional: SKIP_JAR_BUILD=1 to skip build/upload (use existing JAR on host).
# Optional: PROPERTIES_FILE, PRIVATE_KEY_FILE, ALTASTATA_PASSWORD (defaults:
#   ~/.altastata/accounts/amazon.rsa.bob123/*.user.properties and private.key).
#
# Usage (from project root):
#   export LINUXONE_HOST=<your-linuxone-host>
#   export LINUXONE_SSH_KEY=~/.ssh/id_rsa   # optional
#   ./altastata-hadoop/scripts/run-test-aws-linuxone.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
AWS_TEST_PY="$SCRIPT_DIR/test_aws_simple.py"

if [ -z "$LINUXONE_HOST" ]; then
  echo "Error: LINUXONE_HOST is not set." >&2
  echo "Example: export LINUXONE_HOST=<your-linuxone-host>" >&2
  exit 1
fi

if [ ! -f "$AWS_TEST_PY" ]; then
  echo "Error: AWS test script not found: $AWS_TEST_PY" >&2
  exit 1
fi

LINUXONE_USER="${LINUXONE_USER:-ubuntu}"
REMOTE="${LINUXONE_USER}@${LINUXONE_HOST}"

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

ACCOUNT_DIR="${HOME}/.altastata/accounts/amazon.rsa.bob123"
PROPERTIES_FILE="${PROPERTIES_FILE:-}"
if [ -z "$PROPERTIES_FILE" ]; then
  if [ -d "$ACCOUNT_DIR" ]; then
    PROPERTIES_FILE="$(ls -1 "$ACCOUNT_DIR"/*.user.properties 2>/dev/null | head -n 1 || true)"
  fi
fi
PRIVATE_KEY_FILE="${PRIVATE_KEY_FILE:-$ACCOUNT_DIR/private.key}"

if [ -z "$PROPERTIES_FILE" ] || [ ! -f "$PROPERTIES_FILE" ]; then
  echo "Error: account properties not found. Set PROPERTIES_FILE to a local *.user.properties (not in git)." >&2
  exit 1
fi
if [ ! -f "$PRIVATE_KEY_FILE" ]; then
  echo "Error: private key not found. Set PRIVATE_KEY_FILE (not in git)." >&2
  exit 1
fi

echo "Copying test_aws_simple.py and local account files to $REMOTE..." >&2
scp "${SCP_OPTS[@]}" "$AWS_TEST_PY" "$REMOTE:~/test_aws_simple.py"
scp "${SCP_OPTS[@]}" "$PROPERTIES_FILE" "$REMOTE:~/aws-test.user.properties"
scp "${SCP_OPTS[@]}" "$PRIVATE_KEY_FILE" "$REMOTE:~/aws-test.private.key"

echo "Running AWS test on host..." >&2
ssh "${SSH_OPTS[@]}" "$REMOTE" 'bash -s' << REMOTE_SCRIPT
set -e
# Free port 25333 so a fresh JVM starts
if command -v fuser >/dev/null 2>&1; then fuser -k 25333/tcp 2>/dev/null || true; fi
if command -v lsof >/dev/null 2>&1; then pid=\$(lsof -t -i:25333 2>/dev/null); [ -n "\$pid" ] && kill "\$pid" 2>/dev/null || true; fi
sleep 1
export PYTHONUNBUFFERED=1
export ALTASTATA_PASSWORD="${ALTASTATA_PASSWORD:-123}"
echo "[host] Starting python3 ~/test_aws_simple.py ..."
python3 -u ~/test_aws_simple.py ~/aws-test.user.properties ~/aws-test.private.key
echo "[host] python3 exited with code \$?"
REMOTE_SCRIPT

echo "Done."
