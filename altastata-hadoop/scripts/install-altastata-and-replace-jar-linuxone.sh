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

# Build Hadoop uber JAR on Mac, copy to LinuxONE, pip install altastata + replace JAR,
# kill Py4J gateway on host, then run Python example with HPCS user properties.
# Run from project root. Requires: LINUXONE_HOST; optional: LINUXONE_SSH_KEY, REMOTE_PROPERTIES_FILE.
#
# On host: hpcs-user-hpcs.properties at /home/ubuntu/hpcs-user-hpcs.properties (or set REMOTE_PROPERTIES_FILE).
#
# Optional: BASE_GATEWAY_PY path to copy to host (default: ../altastata-python-package/altastata/base_gateway.py).
#
# Usage:
#   export LINUXONE_HOST=<your-linuxone-host>
#   ./altastata-hadoop/scripts/install-altastata-and-replace-jar-linuxone.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
VER=$(tr -d '[:space:]' < "$ROOT_DIR/VERSION")
JAR_NAME="altastata-hadoop-${VER}-uber.jar"
JAR_LOCAL="$ROOT_DIR/altastata-hadoop/build/libs/$JAR_NAME"

if [ -z "$LINUXONE_HOST" ]; then
  echo "Error: LINUXONE_HOST is not set (e.g. export LINUXONE_HOST=<your-linuxone-host>)"
  exit 1
fi

echo "=== 0. Build Hadoop uber JAR on Mac ==="
"$SCRIPT_DIR/build-uber-jar-for-linuxone.sh"
echo ""

LINUXONE_USER="${LINUXONE_USER:-ubuntu}"
REMOTE_PROPERTIES_FILE="${REMOTE_PROPERTIES_FILE:-/home/${LINUXONE_USER}/hpcs-user-hpcs.properties}"
if [ -n "$LINUXONE_SSH_KEY" ] && [ -f "$LINUXONE_SSH_KEY" ]; then
  SSH_OPTS=(-o "StrictHostKeyChecking=accept-new" -i "$LINUXONE_SSH_KEY")
  SCP_OPTS=(-o "StrictHostKeyChecking=accept-new" -i "$LINUXONE_SSH_KEY")
else
  SSH_OPTS=(-o "StrictHostKeyChecking=accept-new")
  SCP_OPTS=(-o "StrictHostKeyChecking=accept-new")
fi
REMOTE="${LINUXONE_USER}@${LINUXONE_HOST}"

# Optional: base_gateway.py with HPCS ~/.hpcs-api-key support (default: parent repo altastata-python-package)
BASE_GATEWAY_PY="${BASE_GATEWAY_PY:-$ROOT_DIR/../altastata-python-package/altastata/base_gateway.py}"

echo "=== 1. Copy JAR to LinuxONE host ==="
scp "${SCP_OPTS[@]}" "$JAR_LOCAL" "$REMOTE:~/$JAR_NAME"
if [ -f "$BASE_GATEWAY_PY" ]; then
  echo "Copying base_gateway.py (HPCS_API_KEY from ~/.hpcs-api-key) to host..."
  scp "${SCP_OPTS[@]}" "$BASE_GATEWAY_PY" "$REMOTE:~/base_gateway.py"
fi
echo ""

echo "=== 2. Install altastata and replace JAR on host ==="
ssh "${SSH_OPTS[@]}" "$REMOTE" "JAR_NAME='$JAR_NAME' bash -s" << 'REMOTE'
set -e
  echo "Ensuring pip is available..."
  if ! python3 -m pip --version 2>/dev/null; then
    echo "Bootstrapping pip for user..."
    curl -sS https://bootstrap.pypa.io/get-pip.py -o /tmp/get-pip.py
    python3 /tmp/get-pip.py --user
    export PATH="$HOME/.local/bin:$PATH"
  fi
  echo "Installing altastata..."
  python3 -m pip install --user altastata
  ALTastata_DIR=$(python3 -c "import altastata, os; print(os.path.dirname(altastata.__file__))")
  echo "Package dir: $ALTastata_DIR"
  ls -la "$ALTastata_DIR" || true
  # Common locations for the JAR
  for sub in jars lib; do
    if [ -d "$ALTastata_DIR/$sub" ]; then
      JAR_DEST="$ALTastata_DIR/$sub/$JAR_NAME"
      break
    fi
  done
  if [ -z "$JAR_DEST" ]; then
    JAR_DEST="$ALTastata_DIR/jars/$JAR_NAME"
    mkdir -p "$(dirname "$JAR_DEST")"
  fi
  cp ~/"$JAR_NAME" "$JAR_DEST"
  echo "Replaced: $JAR_DEST"
  if [ -f ~/base_gateway.py ]; then
    cp ~/base_gateway.py "$ALTastata_DIR/base_gateway.py"
    echo "Replaced: $ALTastata_DIR/base_gateway.py"
  fi
REMOTE
echo ""

echo "=== 3. Copy Python example and run with HPCS properties ==="
scp "${SCP_OPTS[@]}" "$SCRIPT_DIR/example_hpcs_user.py" "$REMOTE:~/example_hpcs_user.py"
# HPCS: JVM needs HPCS_API_KEY in env (for proxy on s390x, or direct PKCS#11 elsewhere). Kill existing gateway so the new JVM gets env.
ssh "${SSH_OPTS[@]}" "$REMOTE" '
  if [ ! -f ~/.hpcs-api-key ] || [ ! -s ~/.hpcs-api-key ]; then
    echo "ERROR: ~/.hpcs-api-key missing or empty on host. Create it with your HPCS API key, then re-run this script."
    echo "  On host: echo -n \"YOUR_HPCS_API_KEY\" > ~/.hpcs-api-key && chmod 600 ~/.hpcs-api-key"
    exit 1
  fi
  export HPCS_API_KEY="$(cat ~/.hpcs-api-key)"
  # Free port 25333 so this run starts a new JVM with HPCS_API_KEY (old gateway would not have it)
  if command -v fuser >/dev/null 2>&1; then fuser -k 25333/tcp 2>/dev/null || true; fi
  if command -v lsof >/dev/null 2>&1; then pid=$(lsof -t -i:25333 2>/dev/null); [ -n "$pid" ] && kill "$pid" 2>/dev/null || true; fi
  sleep 1
  python3 ~/example_hpcs_user.py "'"$REMOTE_PROPERTIES_FILE"'"'
echo ""
echo "Done."
