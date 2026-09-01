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

# AltaStata Dataproc JAR Build Script
# This script builds the AltaStata Hadoop JAR with Dataproc-specific exclusions

set -e

echo "🚀 Building AltaStata Hadoop JAR for Dataproc..."

# Change to project root directory
cd ../..
VER=$(tr -d '[:space:]' < VERSION)
JAR="altastata-hadoop/build/libs/altastata-hadoop-${VER}-uber.jar"

# Clean and build with Dataproc-specific exclusions
echo "📦 Building with Netty and BouncyCastle exclusions for Dataproc compatibility..."
./gradlew :altastata-hadoop:clean :altastata-hadoop:shadowJar -PdataprocBuild=true -PexcludeBouncyCastle=true

# Check if build was successful
if [ -f "$JAR" ]; then
    echo "✅ JAR built successfully!"
    echo "📁 Location: $JAR"
    echo "📊 Size: $(ls -lh "$JAR" | awk '{print $5}')"
    echo ""
    echo "🔄 Next steps:"
    echo "1. Upload to GCS: gsutil cp $JAR gs://altastata-spark-files/altastata-jars/"
    echo "2. Recreate Dataproc cluster to use the new JAR"
else
    echo "❌ JAR build failed!"
    exit 1
fi
