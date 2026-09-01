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

# Run the actual IBMHPCSKeyManager Scala test inside Docker container
#
# This script:
# 1. Builds the project and exports classpath
# 2. Starts the SoftHSM2 container
# 3. Runs the real SoftHSM2Test.scala test inside the container
#
# Usage: ./run-scala-test.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
WORKSPACE_ROOT="$(cd "$PROJECT_ROOT/.." && pwd)"

echo "=== AltaStata SoftHSM2 Docker Test (Scala) ==="
echo ""
echo "Project: $PROJECT_ROOT"
echo ""

# Build the project first (on host)
echo "Building project..."
cd "$WORKSPACE_ROOT"
./gradlew :altastata-core:compileTestScala --quiet

# Generate classpath file
echo "Generating classpath..."
HOST_CLASSPATH=$(./gradlew :altastata-core:printTestClasspath -q 2>/dev/null)

cd "$SCRIPT_DIR"

# Build and start container
echo "Building and starting SoftHSM2 container..."
docker-compose up -d --build 2>/dev/null

# Wait for container to be ready
sleep 2

# Initialize token
echo ""
echo "Initializing token..."
docker-compose exec -T softhsm2 /usr/local/bin/init-token.sh

# Convert classpath to container paths
echo ""
echo "Preparing classpath for container..."

# Replace host paths with container paths
CONTAINER_CLASSPATH=$(echo "$HOST_CLASSPATH" | \
    sed "s|$PROJECT_ROOT/build|/project/build|g" | \
    sed "s|$HOME/.gradle/caches|/root/.gradle/caches|g")

echo "Classpath entries: $(echo "$CONTAINER_CLASSPATH" | tr ':' '\n' | wc -l | tr -d ' ')"

# Run the actual Scala test
echo ""
echo "Running SoftHSM2Test.scala inside container..."
echo ""

docker-compose exec -T softhsm2 java \
    --add-opens jdk.crypto.cryptoki/sun.security.pkcs11=ALL-UNNAMED \
    --add-opens jdk.crypto.cryptoki/sun.security.pkcs11.wrapper=ALL-UNNAMED \
    -Dhpcs.pkcs11.library=/usr/lib/softhsm/libsofthsm2.so \
    -cp "$CONTAINER_CLASSPATH" \
    com.altastata.cloud.ibm.SoftHSM2Test

echo ""
echo "=== Test Complete ==="

