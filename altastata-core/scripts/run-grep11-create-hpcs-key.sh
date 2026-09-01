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

# Run GREP11 create-key + sign/decrypt (no LinuxONE, no PKCS#11 .so).
# Uses gRPC + IAM API key from grep11client.yaml.
#
# Usage (from project root):
#   ./altastata-core/scripts/run-grep11-create-hpcs-key.sh
#   ./altastata-core/scripts/run-grep11-create-hpcs-key.sh /path/to/grep11client.yaml
#   GREP11_YAML=/path/to/grep11client.yaml ./altastata-core/scripts/run-grep11-create-hpcs-key.sh
#
# YAML: set iamcredentialtemplate.instance, tokens.0.grep11connection.address (e.g. ep11.<region>.hs-crypto.cloud.ibm.com),
#       port 443, and tokens.0.users.2.iamauth.apikey (or 1/0) to your IBM Cloud API key.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ROOT_DIR="$(cd "$CORE_DIR/.." && pwd)"

if [ -n "$1" ]; then
  "$ROOT_DIR/gradlew" -p "$ROOT_DIR" :altastata-core:runGrep11CreateKey -PyamlPath="$1" --no-daemon
elif [ -n "$GREP11_YAML" ]; then
  "$ROOT_DIR/gradlew" -p "$ROOT_DIR" :altastata-core:runGrep11CreateKey -PyamlPath="$GREP11_YAML" --no-daemon
else
  "$ROOT_DIR/gradlew" -p "$ROOT_DIR" :altastata-core:runGrep11CreateKey --no-daemon
fi
