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

# Build altastata-hadoop uber JAR and upload it to the LinuxONE host.
#
# Required: LINUXONE_HOST (same as deploy/configure scripts)
# Optional: LINUXONE_USER (default ubuntu), LINUXONE_SSH_KEY
#
# Usage (from project root):
#   export LINUXONE_HOST=<your-linuxone-host>
#   export LINUXONE_SSH_KEY=~/.ssh/id_rsa   # optional
#   ./altastata-hadoop/scripts/build-and-upload-hadoop-jar-linuxone.sh
#
# See: altastata-hadoop/LINUXONE_PIP_ALTSTATA.md

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

if [ -z "$LINUXONE_HOST" ]; then
  echo "Error: LINUXONE_HOST is not set."
  echo "Example: export LINUXONE_HOST=<your-linuxone-host>"
  echo "Then run: $0"
  exit 1
fi

LINUXONE_USER="${LINUXONE_USER:-ubuntu}"
REMOTE="${LINUXONE_USER}@${LINUXONE_HOST}"
VER=$(tr -d '[:space:]' < "$ROOT_DIR/VERSION")
JAR_NAME="altastata-hadoop-${VER}-uber.jar"
JAR="$ROOT_DIR/altastata-hadoop/build/libs/$JAR_NAME"

if [ -n "$LINUXONE_SSH_KEY" ]; then
  if [ ! -f "$LINUXONE_SSH_KEY" ]; then
    echo "Error: LINUXONE_SSH_KEY is set but file not found: $LINUXONE_SSH_KEY"
    echo "Set it to your actual key path, e.g.: export LINUXONE_SSH_KEY=~/.ssh/id_rsa"
    echo "Or leave it unset to use default SSH keys (~/.ssh/id_rsa etc.)."
    exit 1
  fi
  SCP_OPTS=(-i "$LINUXONE_SSH_KEY")
  SSH_OPTS=(-i "$LINUXONE_SSH_KEY")
else
  SCP_OPTS=()
  SSH_OPTS=()
fi

echo "Building altastata-hadoop uber JAR for LinuxONE..."
cd "$ROOT_DIR"
"$SCRIPT_DIR/build-uber-jar-for-linuxone.sh"

if [ ! -f "$JAR" ]; then
  echo "Error: JAR not found at $JAR"
  exit 1
fi

echo "Uploading $JAR_NAME to $REMOTE:~/ ..."
scp "${SCP_OPTS[@]}" "$JAR" "$REMOTE:~/"

echo "Replacing JAR in pip-installed altastata package on host..."
ssh "${SSH_OPTS[@]}" "$REMOTE" 'bash -s' << REMOTE_SCRIPT
set -e
JAR_NAME=$JAR_NAME
if [ ! -f "$HOME/$JAR_NAME" ]; then
  echo "Error: $HOME/$JAR_NAME not found on host (upload may have failed)."
  exit 1
fi
# Trim newline from path (python print adds newline)
ALTastata_DIR=$(python3 -c "import altastata, os; print(os.path.dirname(altastata.__file__))" 2>/dev/null | tr -d '\n\r') || true
if [ -z "$ALTastata_DIR" ]; then
  echo "Warning: altastata package not found (pip install altastata?). JAR is in ~/$JAR_NAME; replace manually (see LINUXONE_PIP_ALTSTATA.md)."
  exit 0
fi
echo "Package dir: $ALTastata_DIR"
ls -la "$ALTastata_DIR" 2>/dev/null || true
# Replace in both lib/ and jars/ so the runtime gets the new JAR whichever path it uses
REPLACED=
for sub in lib jars; do
  if [ -d "$ALTastata_DIR/$sub" ]; then
    JAR_DEST="$ALTastata_DIR/$sub/$JAR_NAME"
    cp -f "$HOME/$JAR_NAME" "$JAR_DEST"
    echo "Replaced: $JAR_DEST ($(ls -l "$JAR_DEST" | awk '{print $5}') bytes)"
    REPLACED=1
  fi
done
if [ -z "$REPLACED" ]; then
  # Neither dir exists: create both and copy so we cover either layout
  for sub in lib jars; do
    JAR_DEST="$ALTastata_DIR/$sub/$JAR_NAME"
    mkdir -p "$(dirname "$JAR_DEST")"
    cp -f "$HOME/$JAR_NAME" "$JAR_DEST"
    echo "Replaced: $JAR_DEST ($(ls -l "$JAR_DEST" | awk '{print $5}') bytes)"
  done
fi
REMOTE_SCRIPT

echo "Done. JAR built, uploaded, and replaced in pip altastata package on $REMOTE."
