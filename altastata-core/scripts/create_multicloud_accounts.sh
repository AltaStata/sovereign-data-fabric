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

# Base directories
BASE_DIR="$HOME/.altastata/accounts"

# Arrays of users
USERS=("bob123" "alice222" "myorgrsa444custodian")
DIR_USERS=("bob123" "alice222" "custodian")

for i in "${!USERS[@]}"; do
    USER="${USERS[$i]}"
    DIR_USER="${DIR_USERS[$i]}"
    
    NEW_DIR="$BASE_DIR/multicloud.rsa.$DIR_USER"
    mkdir -p "$NEW_DIR"
    
    # Files
    AZURE_FILE="$BASE_DIR/azure.rsa.$DIR_USER/altastata-myorgrsa444-$USER.user.properties"
    GOOGLE_FILE="$BASE_DIR/google.rsa.$DIR_USER/altastata-myorgrsa444-$USER.user.properties"
    NEW_FILE="$NEW_DIR/altastata-myorgrsa444-$USER.user.properties"
    
    # 1. Copy Azure file as base
    cp "$AZURE_FILE" "$NEW_FILE"
    
    # 2. Modify accounttype and add multicloud properties
    sed -i '' 's/accounttype=azure-secure/accounttype=multicloud-secure/g' "$NEW_FILE"
    
    echo "" >> "$NEW_FILE"
    echo "# Multicloud Configuration" >> "$NEW_FILE"
    echo "multicloud.metadata.provider=azure-secure" >> "$NEW_FILE"
    echo "multicloud.data.provider=google-secure" >> "$NEW_FILE"
    
    # 3. Append Google credentials (grep out google specific keys: credentials, google-project)
    echo "" >> "$NEW_FILE"
    echo "# Google Credentials" >> "$NEW_FILE"
    grep "^credentials=" "$GOOGLE_FILE" >> "$NEW_FILE"
    grep "^google-project=" "$GOOGLE_FILE" >> "$NEW_FILE"
    
    # 4. Copy the keys (Azure keys and Google keys are the same RSA keys for the same user)
    cp "$BASE_DIR/azure.rsa.$DIR_USER"/*.pem "$NEW_DIR/" 2>/dev/null || true
    cp "$BASE_DIR/azure.rsa.$DIR_USER"/*.key "$NEW_DIR/" 2>/dev/null || true
    
    echo "Created multicloud account for $USER at $NEW_DIR"
done
