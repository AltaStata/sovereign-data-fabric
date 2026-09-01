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

# AltaStata Dataproc Cluster Rebuild Script
# This script deletes the existing Dataproc cluster, uploads the latest JAR, and creates a new cluster

set -e

# Configuration
CLUSTER_NAME="altastata-full-cluster"
REGION="us-central1"
ZONE="us-central1-a"
PROJECT_ID="altastata-dataproc"
STORAGE_BUCKET="altastata-spark-files"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VER=$(tr -d '[:space:]' < "$SCRIPT_DIR/../../VERSION")
JAR_PATH="$SCRIPT_DIR/../build/libs/altastata-hadoop-${VER}-uber.jar"
GCS_JAR_PATH="gs://${STORAGE_BUCKET}/altastata-jars/altastata-hadoop-${VER}-uber.jar"

echo "🚀 AltaStata Dataproc Cluster Rebuild Script"
echo "=============================================="
echo ""

# Check if JAR file exists
if [ ! -f "$JAR_PATH" ]; then
    echo "❌ JAR file not found: $JAR_PATH"
    echo "Please build the JAR first using: ./build-dataproc-jar.sh"
    exit 1
fi

echo "📦 JAR file found: $JAR_PATH"
echo "📊 Size: $(ls -lh $JAR_PATH | awk '{print $5}')"
echo ""

# Set the active project
echo "🔧 Setting active project to: $PROJECT_ID"
gcloud config set project $PROJECT_ID

# Check if cluster exists and delete it
echo "🔍 Checking if cluster '$CLUSTER_NAME' exists..."
if gcloud dataproc clusters describe $CLUSTER_NAME --region=$REGION --quiet 2>/dev/null; then
    echo "🗑️  Deleting existing cluster '$CLUSTER_NAME'..."
    gcloud dataproc clusters delete $CLUSTER_NAME \
        --region=$REGION \
        --quiet
    
    echo "⏳ Waiting for cluster deletion to complete..."
    sleep 30
else
    echo "ℹ️  Cluster '$CLUSTER_NAME' does not exist, proceeding with creation..."
fi

# Upload the JAR file to GCS
echo "📤 Uploading JAR file to GCS..."
gsutil cp $JAR_PATH $GCS_JAR_PATH

echo "✅ JAR uploaded successfully!"
echo "📊 GCS Size: $(gsutil ls -lh $GCS_JAR_PATH | awk '{print $5}')"
echo ""

# Create new Dataproc cluster
echo "🏗️  Creating new Dataproc cluster '$CLUSTER_NAME'..."
gcloud dataproc clusters create $CLUSTER_NAME \
    --region=$REGION \
    --zone=$ZONE \
    --master-machine-type=n1-standard-4 \
    --master-boot-disk-size=100GB \
    --num-workers=2 \
    --worker-machine-type=n1-standard-4 \
    --worker-boot-disk-size=100GB \
    --image-version=2.1-debian11 \
    --enable-component-gateway \
    --optional-components=JUPYTER,ZEPPELIN \
    --service-account=altastata-dataproc-admin@$PROJECT_ID.iam.gserviceaccount.com \
    --initialization-actions=gs://${STORAGE_BUCKET}/init-scripts/altastata-dataproc-init.sh

echo ""
echo "🎉 Dataproc cluster creation completed!"
echo ""
echo "📋 Cluster Information:"
echo "   Name: $CLUSTER_NAME"
echo "   Region: $REGION"
echo "   Zone: $ZONE"
echo "   Project: $PROJECT_ID"
echo ""
echo "🌐 Access URLs:"
echo "   Console: https://console.cloud.google.com/dataproc/clusters?project=$PROJECT_ID"
echo ""
echo "📚 Next Steps:"
echo "1. Wait for cluster to be fully ready (check console)"
echo "2. Access Jupyter/Zeppelin notebooks via web interfaces"
echo "3. Test AltaStata functionality in notebooks"
echo ""
echo "💰 Cost Information:"
echo "   Estimated cost: ~$0.57/hour (~$410/month if running 24/7)"
echo "   Remember to delete cluster when not in use to save costs"
echo ""
echo "🛑 To delete cluster later:"
echo "   gcloud dataproc clusters delete $CLUSTER_NAME --region=$REGION"
