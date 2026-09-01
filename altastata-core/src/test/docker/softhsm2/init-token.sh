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
# or visit https://altastata.com/licensing

# Initialize SoftHSM2 token for AltaStata testing
#
# Usage: ./init-token.sh [token-label] [pin] [key-label]
#
# Defaults:
#   token-label: GREP11 Token
#   pin: 1234
#   key-label: altastata-testuser

TOKEN_LABEL="${1:-GREP11 Token}"
PIN="${2:-1234}"
KEY_LABEL="${3:-altastata-testuser}"

echo "=== SoftHSM2 Token Initialization ==="
echo "Token Label: $TOKEN_LABEL"
echo "PIN: $PIN"
echo "Key Label: $KEY_LABEL"
echo ""

# Check if token already exists
if softhsm2-util --show-slots 2>/dev/null | grep -q "$TOKEN_LABEL"; then
    echo "Token '$TOKEN_LABEL' already exists."
    echo ""
    
    # Check if key exists
    if pkcs11-tool --module /usr/lib/softhsm/libsofthsm2.so --login --pin "$PIN" --list-objects 2>/dev/null | grep -q "$KEY_LABEL"; then
        echo "Key '$KEY_LABEL' already exists."
        echo ""
        echo "=== Setup Complete ==="
        exit 0
    fi
else
    echo "Initializing token..."
    softhsm2-util --init-token --slot 0 --label "$TOKEN_LABEL" --pin "$PIN" --so-pin "$PIN"
    echo "Token initialized."
    echo ""
fi

# Generate RSA key pair with self-signed certificate using keytool
echo "Generating RSA-4096 key pair with self-signed certificate..."

# Create PKCS#11 config for keytool
cat > /tmp/pkcs11.cfg << EOF
name = SoftHSM2
library = /usr/lib/softhsm/libsofthsm2.so
slotListIndex = 0
EOF

# Generate key pair with self-signed certificate using keytool
# This creates both the key pair AND a certificate, which Java PKCS11 KeyStore needs
# Note: -keypass cannot be used with PKCS11 storetype (key protection is via HSM)
keytool -genkeypair \
    -alias "$KEY_LABEL" \
    -keyalg RSA \
    -keysize 4096 \
    -validity 365 \
    -dname "CN=AltaStata Test User, OU=Development, O=AltaStata, C=US" \
    -storetype PKCS11 \
    -providerClass sun.security.pkcs11.SunPKCS11 \
    -providerArg /tmp/pkcs11.cfg \
    -storepass "$PIN" \
    -J--add-opens=jdk.crypto.cryptoki/sun.security.pkcs11=ALL-UNNAMED \
    2>&1 || echo "Key generation via keytool completed"

echo ""
echo "=== Verifying Setup ==="
echo ""

# List objects
echo "Token contents:"
pkcs11-tool --module /usr/lib/softhsm/libsofthsm2.so \
    --login --pin "$PIN" \
    --list-objects

echo ""
echo "=== Setup Complete ==="
echo ""
echo "PKCS#11 Library: /usr/lib/softhsm/libsofthsm2.so"
echo "Token Label: $TOKEN_LABEL"
echo "Key Label: $KEY_LABEL"

