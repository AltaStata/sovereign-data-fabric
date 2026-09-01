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

# Copy Hadoop + Bouncy Castle JARs from DBFS onto the node Spark classpath.
set -euo pipefail

SRC="/dbfs/FileStore/altastata-jars"
DEST="/databricks/jars"

if [[ ! -d "$SRC" ]]; then
  echo "altastata init: missing $SRC" >&2
  exit 1
fi

mkdir -p "$DEST"
cp -f "$SRC"/*.jar "$DEST/"
echo "altastata init: copied JARs from $SRC to $DEST"
