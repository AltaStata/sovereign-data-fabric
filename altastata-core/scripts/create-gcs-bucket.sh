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

# Script to create Google Cloud Storage bucket for performance tests
# Uses the cloudstoragecheck.json service account credentials

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}=== Creating Google Cloud Storage Bucket ===${NC}"

# Check if gcloud is installed
if ! command -v gcloud &> /dev/null; then
    echo -e "${RED}Error: gcloud CLI is not installed. Please install it first.${NC}"
    echo "Visit: https://cloud.google.com/sdk/docs/install"
    exit 1
fi

# Set the bucket name
BUCKET_NAME="altastata-performance-test"
PROJECT_ID="cloudstoragecheck"

echo -e "${YELLOW}Using project: ${PROJECT_ID}${NC}"
echo -e "${YELLOW}Creating bucket: ${BUCKET_NAME}${NC}"

if [ -z "${GOOGLE_APPLICATION_CREDENTIALS:-}" ] || [ ! -f "$GOOGLE_APPLICATION_CREDENTIALS" ]; then
    echo -e "${RED}Error: Set GOOGLE_APPLICATION_CREDENTIALS to a service-account JSON key file.${NC}"
    exit 1
fi

echo -e "${YELLOW}Using credentials from: ${GOOGLE_APPLICATION_CREDENTIALS}${NC}"

# Activate the service account
echo -e "${YELLOW}Activating service account...${NC}"
gcloud auth activate-service-account --key-file="${GOOGLE_APPLICATION_CREDENTIALS}"

# Create the bucket
echo -e "${YELLOW}Creating bucket...${NC}"
gcloud storage buckets create gs://${BUCKET_NAME} \
    --project=${PROJECT_ID} \
    --location=US \
    --uniform-bucket-level-access

if [ $? -eq 0 ]; then
    echo -e "${GREEN}✅ Bucket created successfully!${NC}"
    echo -e "${GREEN}Bucket name: ${BUCKET_NAME}${NC}"
    echo -e "${GREEN}Project: ${PROJECT_ID}${NC}"
    echo -e "${GREEN}Location: US${NC}"
    echo ""
    echo -e "${YELLOW}You can now run the performance tests.${NC}"
else
    echo -e "${RED}❌ Failed to create bucket.${NC}"
    echo -e "${YELLOW}The bucket might already exist or there might be permission issues.${NC}"
    exit 1
fi

# Verify the bucket was created
echo -e "${YELLOW}Verifying bucket exists...${NC}"
if gcloud storage ls gs://${BUCKET_NAME} &> /dev/null; then
    echo -e "${GREEN}✅ Bucket verification successful!${NC}"
else
    echo -e "${RED}❌ Bucket verification failed.${NC}"
    exit 1
fi

echo ""
echo -e "${GREEN}=== Bucket Creation Complete ===${NC}"
echo -e "${YELLOW}Next steps:${NC}"
echo "1. Run the performance tests: ./gradlew :altastata-core:test --tests \"com.altastata.performance.FilePerformanceTest\""
echo "2. The tests will now be able to upload and download files to/from the bucket" 
