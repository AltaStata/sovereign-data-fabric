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

# AltaStata Dataproc Cluster Status Check Script

set -e

# Configuration
CLUSTER_NAME="altastata-full-cluster"
REGION="us-central1"
PROJECT_ID="altastata-dataproc"

echo "🔍 AltaStata Dataproc Cluster Status Check"
echo "=========================================="
echo ""

# Set the active project
echo "🔧 Active project: $PROJECT_ID"
gcloud config set project $PROJECT_ID

# Check cluster status
echo "📊 Checking cluster status..."
if gcloud dataproc clusters describe $CLUSTER_NAME --region=$REGION --quiet 2>/dev/null; then
    echo "✅ Cluster '$CLUSTER_NAME' exists"
    
    # Get cluster status
    STATUS=$(gcloud dataproc clusters describe $CLUSTER_NAME --region=$REGION --format="value(status.state)")
    echo "📈 Status: $STATUS"
    
    # Get cluster details
    echo ""
    echo "📋 Cluster Details:"
    gcloud dataproc clusters describe $CLUSTER_NAME --region=$REGION --format="table(
        config.gceClusterConfig.zoneUri.basename(),
        config.masterConfig.numInstances,
        config.workerConfig.numInstances,
        config.masterConfig.machineTypeUri.basename(),
        config.workerConfig.machineTypeUri.basename()
    )"
    
    # Get web interface URLs
    echo ""
    echo "🌐 Web Interfaces:"
    gcloud dataproc clusters describe $CLUSTER_NAME --region=$REGION --format="value(config.gceClusterConfig.metadata.items[0].value)"
    
else
    echo "❌ Cluster '$CLUSTER_NAME' does not exist"
fi

echo ""
echo "📚 Console URL: https://console.cloud.google.com/dataproc/clusters?project=$PROJECT_ID"
