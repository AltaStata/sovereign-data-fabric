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

#
# HPCS Test Setup and Run Script for LinuxONE (s390x)
#
# This script:
# 1. Installs Java if not present
# 2. Downloads and installs IBM HPCS PKCS#11 library
# 3. Creates grep11client.yaml configuration
# 4. Compiles and runs the test
#
# Usage:
#   chmod +x setup-and-run.sh
#   ./setup-and-run.sh <api-key>
#

set -e

API_KEY="${1:-}"

echo "========================================"
echo "HPCS PKCS#11 Test Setup for LinuxONE"
echo "========================================"
echo ""

if [ -z "$API_KEY" ]; then
    echo "Usage: $0 <api-key>"
    echo "  api-key: Your HPCS API key"
    exit 1
fi

# Check architecture
ARCH=$(uname -m)
if [ "$ARCH" != "s390x" ]; then
    echo "Warning: This script is designed for s390x (LinuxONE)."
    echo "Current architecture: $ARCH"
    echo "Continuing anyway..."
fi

# Step 1: Install Java
echo "Step 1: Checking Java installation..."
if ! command -v java &> /dev/null; then
    echo "  Installing OpenJDK 17..."
    sudo apt-get update
    sudo apt-get install -y openjdk-17-jdk
else
    echo "  Java already installed: $(java -version 2>&1 | head -1)"
fi
echo ""

# Step 2: Download HPCS PKCS#11 library
echo "Step 2: Setting up HPCS PKCS#11 library..."
PKCS11_LIB="/opt/hpcs/pkcs11-grep11-s390x.so"

if [ -f "$PKCS11_LIB" ]; then
    echo "  Library already exists at $PKCS11_LIB"
else
    echo "  Creating directory /opt/hpcs..."
    sudo mkdir -p /opt/hpcs
    
    echo "  Downloading PKCS#11 library from IBM..."
    # Get latest release version
    LATEST_VERSION=$(curl -s https://api.github.com/repos/IBM-Cloud/hpcs-pkcs11/releases/latest | grep '"tag_name":' | sed -E 's/.*"v([^"]+)".*/\1/')
    
    if [ -z "$LATEST_VERSION" ]; then
        LATEST_VERSION="2.6.8"  # Fallback version
    fi
    
    echo "  Latest version: $LATEST_VERSION"
    
    DOWNLOAD_URL="https://github.com/IBM-Cloud/hpcs-pkcs11/releases/download/v${LATEST_VERSION}/pkcs11-grep11-s390x.so.${LATEST_VERSION}"
    
    sudo curl -L -o "$PKCS11_LIB" "$DOWNLOAD_URL"
    sudo chmod 755 "$PKCS11_LIB"
    
    echo "  Library installed at $PKCS11_LIB"
fi
echo ""

# Step 3: Create grep11client.yaml
echo "Step 3: Creating grep11client.yaml..."
sudo mkdir -p /etc/ep11client

# NOTE: Update these values with your actual HPCS instance details!
cat << 'EOF' | sudo tee /etc/ep11client/grep11client.yaml > /dev/null
iamcredentialtemplate: &defaultiamcredential
  enabled: true
  endpoint: "https://iam.cloud.ibm.com"
  # UPDATE: Your HPCS instance ID
  instance: "<your-instance-id>"

tokens:
  0:
    grep11connection:
      # UPDATE: Your HPCS endpoint
      address: ep11.<region>.hs-crypto.cloud.ibm.com
      port: 443
      tls:
        enabled: true
        cacert:        # leave empty to use system default
    storage:
      remotestore:
        enabled: true
    users:
      0: # SO User
        name: "admin"
        iamauth: *defaultiamcredential
      1: # User
        name: "admin"
        # UPDATE: Your tokenspaceID
        tokenspaceID: aaabbbcc-XXXXX
        keystorePassword:
        iamauth: *defaultiamcredential
        sessionauth:
          enabled: false
          tokenspaceIDPassword:
      2: # Anonymous user
        name: "Anonymous"
        # UPDATE: Your Anonymous tokenspaceID
        tokenspaceID: aaabbbcc-EEEEEE
        keystorePassword:
        iamauth:
          <<: *defaultiamcredential
          # UPDATE: Your API key
          apikey: REPLACE_WITH_API_KEY
        sessionauth:
          enabled: false
          tokenspaceIDPassword:

logging:
  loglevel: trace
  logpath: /tmp/grep11-pkcs11.log
EOF

# Replace API key placeholder
sudo sed -i "s/REPLACE_WITH_API_KEY/$API_KEY/" /etc/ep11client/grep11client.yaml

echo "  Created /etc/ep11client/grep11client.yaml"
echo "  IMPORTANT: Edit this file to add your actual HPCS instance details!"
echo ""

# Step 4: Compile Java test
echo "Step 4: Compiling Java test..."
javac HPCSKeyTest.java
echo "  Compiled HPCSKeyTest.class"
echo ""

# Step 5: Run the test
echo "Step 5: Running HPCS test..."
echo ""
java -Dpkcs11.library="$PKCS11_LIB" HPCSKeyTest "$API_KEY"
