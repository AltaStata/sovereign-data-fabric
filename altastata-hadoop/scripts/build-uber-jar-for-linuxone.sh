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

# Build altastata-hadoop uber JAR for LinuxONE (or any host).
# Use this JAR to: replace the JAR in pip-installed altastata, or add to Spark/jupyter classpath.
#
# Run from project root:
#   ./altastata-hadoop/scripts/build-uber-jar-for-linuxone.sh
#
# Output: altastata-hadoop/build/libs/altastata-hadoop-<VERSION>-uber.jar

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

cd "$ROOT_DIR"

echo "Building altastata-core and altastata-hadoop (clean build shadowJar copyDeps) for LinuxONE..."
# Build core first so the uber JAR contains the latest (e.g. HPCS proxy, HPCSProxyClient).
./gradlew :altastata-core:build :altastata-hadoop:clean :altastata-hadoop:build :altastata-hadoop:shadowJar :altastata-hadoop:copyDeps \
  -PexcludeBouncyCastle=true -PminimalBuild=true

VER=$(tr -d '[:space:]' < "$ROOT_DIR/VERSION")
JAR="$ROOT_DIR/altastata-hadoop/build/libs/altastata-hadoop-${VER}-uber.jar"
if [ -f "$JAR" ]; then
  echo "Done. JAR: $JAR"
  ls -la "$JAR"
else
  echo "Error: JAR not found at $JAR"
  exit 1
fi
