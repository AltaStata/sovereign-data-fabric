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

# Create HPCS GREP11 key files for the sandbox account.
#
# Account dir:  ~/.altastata/accounts/amazon.rsa.hpcs.hpcsdev
# Key label:   hpcsdev
# Writes:       public.key, hpcs-privkey.blob, hpcs.marker
#
# Requires GREP11_YAML (populated grep11client.yaml — see workspace rules).
#
# Usage (from repo root):
#   export GREP11_YAML=/path/to/grep11client.yaml
#   ./altastata-core/scripts/run-hpcs-create-sandbox-key.sh
#
# Verify sign/decrypt:
#   GREP11_YAML=... ./gradlew :altastata-core:runHPCSGrep11EncryptDecryptSignVerifyTest \
#     -PaccountDir="$HOME/.altastata/accounts/amazon.rsa.hpcs.hpcsdev"

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

SANDBOX_DIR="${ALTASTATA_HPCS_SANDBOX_DIR:-$HOME/.altastata/accounts/amazon.rsa.hpcs.hpcsdev}"
SANDBOX_USER="${ALTASTATA_HPCS_SANDBOX_USER:-hpcsdev}"

if [[ -z "${GREP11_YAML:-}" ]]; then
  while IFS= read -r candidate; do
    if [[ -f "$candidate" ]] && ! rg -q '<your-' "$candidate" 2>/dev/null; then
      export GREP11_YAML="$candidate"
      break
    fi
  done < <(find "$ROOT_DIR" -name grep11client.yaml -not -path '*/build/*' -not -path '*/altastata-core/*' 2>/dev/null)
fi

if [[ -z "${GREP11_YAML:-}" || ! -f "${GREP11_YAML}" ]]; then
  echo "Set GREP11_YAML to a populated grep11client.yaml (no <your- placeholders)." >&2
  exit 1
fi

echo "Sandbox account: $SANDBOX_DIR"
echo "HPCS key label:  $SANDBOX_USER"
echo "GREP11_YAML:     $GREP11_YAML"

mkdir -p "$SANDBOX_DIR"

"$ROOT_DIR/gradlew" -p "$ROOT_DIR" :altastata-core:runHPCSCreateKey \
  -PaccountDir="$SANDBOX_DIR" \
  -PhpcsUser="$SANDBOX_USER" \
  --no-daemon

echo ""
echo "Next: add *user.properties for your cloud (copy from admin), set:"
echo "  myuser=$SANDBOX_USER"
echo "  key-protection=HPCS"
echo "  hpcs-key-label=$SANDBOX_USER"
echo "  hpcs-yaml-path=$GREP11_YAML"
echo "  hpcs-priv-key-blob-path=$SANDBOX_DIR/hpcs-privkey.blob"
