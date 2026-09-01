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

# AltaStata Dataproc Initialization Action
# This script copies AltaStata JARs from GCS to Spark jars directory

set -e

echo "🚀 Starting AltaStata initialization action..."

# Create backup of existing JARs (optional)
if [ -d "/usr/lib/spark/jars" ]; then
    echo "📁 Backing up existing Spark JARs..."
    sudo cp -r /usr/lib/spark/jars /usr/lib/spark/jars.backup.$(date +%Y%m%d_%H%M%S)
fi

# Copy AltaStata JARs from GCS to Spark jars directory
echo "📥 Downloading AltaStata JARs from GCS..."
gsutil cp gs://altastata-spark-files/altastata-jars/altastata-hadoop-*-uber.jar /usr/lib/spark/jars/
gsutil cp gs://altastata-spark-files/altastata-jars/bcpkix-jdk18on-1.80.jar /usr/lib/spark/jars/
gsutil cp gs://altastata-spark-files/altastata-jars/bcprov-jdk18on-1.80.jar /usr/lib/spark/jars/
gsutil cp gs://altastata-spark-files/altastata-jars/bcutil-jdk18on-1.80.jar /usr/lib/spark/jars/

# Set proper permissions
echo "🔐 Setting permissions..."
sudo chown root:root /usr/lib/spark/jars/altastata-*.jar
sudo chown root:root /usr/lib/spark/jars/bc*.jar
sudo chmod 644 /usr/lib/spark/jars/altastata-*.jar
sudo chmod 644 /usr/lib/spark/jars/bc*.jar

# Verify JARs are in place
echo "✅ Verifying JARs..."
ls -la /usr/lib/spark/jars/altastata-*.jar
ls -la /usr/lib/spark/jars/bc*.jar

echo "🎉 AltaStata initialization completed successfully!"
