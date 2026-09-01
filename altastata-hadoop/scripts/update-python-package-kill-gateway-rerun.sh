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

# Update the altastata Python package (pip install -e from local dir), kill the Py4J gateway on port 25333, then rerun a Python script.
# Use this when you change the Python package and want a fresh JVM/gateway with the new code.
#
# Optional env:
#   PYTHON_PACKAGE_DIR  - Path to altastata-python-package (default: ../altastata-python-package from repo root)
#   PROPERTIES_FILE     - If running example_hpcs_user.py, path to properties file
#
# Usage:
#   ./altastata-hadoop/scripts/update-python-package-kill-gateway-rerun.sh
#   ./altastata-hadoop/scripts/update-python-package-kill-gateway-rerun.sh [path/to/your_script.py [args...]]
#   PROPERTIES_FILE=/path/to/hpcs-user-hpcs.properties ./altastata-hadoop/scripts/update-python-package-kill-gateway-rerun.sh
#
# If no script is given, runs example_hpcs_user.py with PROPERTIES_FILE.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

PYTHON_PACKAGE_DIR="${PYTHON_PACKAGE_DIR:-$ROOT_DIR/../altastata-python-package}"
PROPERTIES_FILE="${PROPERTIES_FILE:-$REMOTE_PROPERTIES_FILE}"
PROPERTIES_FILE="${PROPERTIES_FILE:-$HOME/hpcs-user-hpcs.properties}"

echo "=== 1. Update altastata Python package ==="
if [ -d "$PYTHON_PACKAGE_DIR" ] && [ -f "$PYTHON_PACKAGE_DIR/setup.py" ] || [ -f "$PYTHON_PACKAGE_DIR/pyproject.toml" ]; then
  echo "Installing from $PYTHON_PACKAGE_DIR (editable)..."
  python3 -m pip install -e "$PYTHON_PACKAGE_DIR"
  echo "Done."
else
  echo "No local package at $PYTHON_PACKAGE_DIR (or no setup.py/pyproject.toml). Skipping. Use pip install --user --upgrade altastata if needed."
fi
echo ""

echo "=== 2. Kill Py4J gateway on port 25333 ==="
if command -v fuser >/dev/null 2>&1; then
  fuser -k 25333/tcp 2>/dev/null || true
fi
if command -v lsof >/dev/null 2>&1; then
  for pid in $(lsof -t -i:25333 2>/dev/null); do
    kill "$pid" 2>/dev/null || true
  done
fi
sleep 1
echo "Done."
echo ""

echo "=== 3. Rerun ==="
if [ $# -ge 1 ]; then
  exec python3 "$@"
fi
# Default: run example_hpcs_user.py with PROPERTIES_FILE
if [ -f "$PROPERTIES_FILE" ]; then
  exec python3 "$SCRIPT_DIR/example_hpcs_user.py" "$PROPERTIES_FILE"
fi
echo "No script given and PROPERTIES_FILE not found: $PROPERTIES_FILE"
echo "Usage: $0 [script.py [args...]]   or set PROPERTIES_FILE for example_hpcs_user.py"
exit 1
