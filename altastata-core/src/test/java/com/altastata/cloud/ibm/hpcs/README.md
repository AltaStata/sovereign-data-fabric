# HPCS PKCS#11 Test for LinuxONE

Standalone Java application to test IBM HPCS integration on LinuxONE (s390x).

**Status: TESTED AND WORKING** (2026-02-02)

**GREP11 (Mac/any host):** To create the key via Java and write public key + private blob to the account dir, see [HPCS_CREATE_KEY_AND_SIGN_CERT.md](HPCS_CREATE_KEY_AND_SIGN_CERT.md). Run the encrypt/decrypt and sign/verify test with:

```bash
GREP11_YAML=/path/to/grep11client.yaml ./gradlew :altastata-core:runHPCSGrep11EncryptDecryptSignVerifyTest --no-daemon
```

## Prerequisites

- IBM Cloud account with LinuxONE VPC access
- HPCS instance provisioned
- grep11client.yaml configuration from IBM

## Quick Start (After VM Setup)

```bash
# 1. Install required packages
sudo apt update && sudo apt install -y openjdk-17-jdk opensc gnutls-bin

# 2. Set permissions
sudo chmod 755 /opt/hpcs /etc/ep11client
sudo chmod 644 /opt/hpcs/pkcs11-grep11-s390x.so /etc/ep11client/grep11client.yaml

# 3. Create key pair WITH ID (critical for Java)
pkcs11-tool --module /opt/hpcs/pkcs11-grep11-s390x.so \
  --login --pin <API_KEY> \
  --keypairgen --key-type RSA:2048 \
  --label <username> --id 01 \
  --usage-sign --usage-decrypt

# 4. Export public key
pkcs11-tool --module /opt/hpcs/pkcs11-grep11-s390x.so \
  --login --pin <API_KEY> \
  --read-object --type pubkey --id 01 -o /tmp/pubkey.der
openssl rsa -pubin -inform DER -in /tmp/pubkey.der -outform PEM -out /tmp/pubkey.pem

# 5. Create certificate template
cat > /tmp/cert.cfg << 'EOF'
cn = <username>
organization = AltaStata
country = US
expiration_days = 365
signing_key
encryption_key
EOF

# 6. Generate certificate (signing happens in HSM)
export GNUTLS_PIN=<API_KEY>
certtool --generate-self-signed \
  --load-privkey "pkcs11:model=GREP11;token=<token-name>;object=<username>;type=private" \
  --provider=/opt/hpcs/pkcs11-grep11-s390x.so \
  --outfile /tmp/cert.pem \
  --template /tmp/cert.cfg

# 7. Import certificate WITH MATCHING ID
pkcs11-tool --module /opt/hpcs/pkcs11-grep11-s390x.so \
  --login --pin <API_KEY> \
  --write-object /tmp/cert.pem --type cert \
  --label <username> --id 01

# 8. Run Java test
javac HPCSKeyTest.java
java -Dpkcs11.library=/opt/hpcs/pkcs11-grep11-s390x.so HPCSKeyTest <API_KEY> /tmp/pubkey.pem
```

## Step 1: Create LinuxONE VM

Provision an IBM Cloud LinuxONE (s390x) virtual server instance:

1. Log into **IBM Cloud Console** (https://cloud.ibm.com)
2. Navigate to **VPC Infrastructure** → **Virtual server instances**
3. Click **Create**
4. Configure:
   - **Location**: Toronto (ca-tor) - required for LinuxONE
   - **Image**: Ubuntu 22.04 for s390x
   - **Profile**: `bz2-2x8` (2 vCPU, 8GB RAM)
   - **SSH Key**: Upload your SSH public key
5. Create and attach **Floating IP** for SSH access

## Step 2: SSH to the VM

```bash
ssh -i <your-key>.prv ubuntu@<floating-ip>
```

## Step 3: Install Dependencies

```bash
sudo apt update
sudo apt install -y openjdk-17-jdk opensc gnutls-bin
```

## Step 4: Setup PKCS#11 Library

```bash
sudo mkdir -p /opt/hpcs
sudo curl -L -o /opt/hpcs/pkcs11-grep11-s390x.so \
  https://github.com/IBM-Cloud/hpcs-pkcs11/releases/download/v2.6.8/pkcs11-grep11-s390x.so.2.6.8
sudo chmod 755 /opt/hpcs
sudo chmod 644 /opt/hpcs/pkcs11-grep11-s390x.so
```

## Step 5: Configure grep11client.yaml

Create `/etc/ep11client/grep11client.yaml` with your HPCS details (provided by IBM).

```bash
sudo mkdir -p /etc/ep11client
sudo chmod 755 /etc/ep11client
# Copy your grep11client.yaml here
sudo chmod 644 /etc/ep11client/grep11client.yaml
```

## Step 6: Create Key Pair

**CRITICAL: Use `--id 01` flag - Java requires this to find the key!**

```bash
pkcs11-tool --module /opt/hpcs/pkcs11-grep11-s390x.so \
  --login --pin <API_KEY> \
  --keypairgen --key-type RSA:2048 \
  --label <username> --id 01 \
  --usage-sign --usage-decrypt
```

## Step 7: Create and Import Certificate

Java's SunPKCS11 only exposes private keys that have an associated certificate with the same CKA_ID.

```bash
# Export public key
pkcs11-tool --module /opt/hpcs/pkcs11-grep11-s390x.so \
  --login --pin <API_KEY> \
  --read-object --type pubkey --id 01 -o /tmp/pubkey.der
openssl rsa -pubin -inform DER -in /tmp/pubkey.der -outform PEM -out /tmp/pubkey.pem

# Create certificate template
cat > /tmp/cert.cfg << 'EOF'
cn = <username>
organization = AltaStata
country = US
expiration_days = 365
signing_key
encryption_key
EOF

# Generate certificate
export GNUTLS_PIN=<API_KEY>
certtool --generate-self-signed \
  --load-privkey "pkcs11:model=GREP11;token=<token-name>;object=<username>;type=private" \
  --provider=/opt/hpcs/pkcs11-grep11-s390x.so \
  --outfile /tmp/cert.pem \
  --template /tmp/cert.cfg

# Import certificate with SAME ID as key
pkcs11-tool --module /opt/hpcs/pkcs11-grep11-s390x.so \
  --login --pin <API_KEY> \
  --write-object /tmp/cert.pem --type cert \
  --label <username> --id 01
```

## Step 8: Verify Setup

```bash
pkcs11-tool --module /opt/hpcs/pkcs11-grep11-s390x.so \
  --login --pin <API_KEY> --list-objects
```

Expected output (all have ID: 01):
```
Private Key Object; RSA 
  label:      <username>
  ID:         01
  Usage:      decrypt, sign

Public Key Object; RSA 2048 bits
  label:      <username>
  ID:         01

Certificate Object; type = X.509 cert
  label:      <username>
  ID:         01
```

## Step 9: Run Java Test

```bash
javac HPCSKeyTest.java
java -Dpkcs11.library=/opt/hpcs/pkcs11-grep11-s390x.so HPCSKeyTest <API_KEY> /tmp/pubkey.pem
```

## Expected Output

```
======================================================================
HPCS PKCS#11 Sign/Verify and Encrypt/Decrypt Test
======================================================================

Configuration:
  Library: /opt/hpcs/pkcs11-grep11-s390x.so
  Key Label: <username>
  Public Key File: /tmp/pubkey.pem

1. Checking PKCS#11 library... OK
2. Loading public key from /tmp/pubkey.pem... OK
   Algorithm: RSA
   Format: X.509
3. Initializing PKCS#11 provider... OK (SunPKCS11-HPCS-Test)
4. Loading PKCS#11 keystore... OK
5. Listing keys in keystore...
   Found: <username>
   -> Got private key handle for <username>
6. Private key already found in step 5

7. Testing SIGN/VERIFY:
   Message: Hello from AltaStata HPCS Test!
   Signing (in HPCS)... OK (256 bytes)
   Signature: FnC1X7e2kYM1rhBLzK9WbO40tcStGebYnK4NZh5HzwfY...
   Verifying (local)... OK - SIGNATURE VALID

8. Testing ENCRYPT/DECRYPT:
   Original: Hello from AltaStata HPCS Test!
   Encrypting (local)... OK (256 bytes)
   Decrypting (in HPCS)... OK
   Decrypted: Hello from AltaStata HPCS Test!

======================================================================
SUCCESS! Both sign/verify AND encrypt/decrypt work!
Private key operations happened IN THE HSM (never left HPCS).
======================================================================
```

## Troubleshooting

| Problem | Cause | Solution |
|:--------|:------|:---------|
| KeyStore shows no keys | Missing certificate or mismatched CKA_ID | Import cert with same `--id` as key |
| `CKR_KEY_FUNCTION_NOT_PERMITTED` | Key missing usage attributes | Recreate key with `--usage-sign --usage-decrypt` |
| `Config File "grep11client" Not Found` | yaml not in expected location | Place in `/etc/ep11client/grep11client.yaml` |
| Permission denied | Wrong file permissions | `chmod 755` directories, `chmod 644` files |
| SIGSEGV with keytool | Compatibility issue | Use `certtool` instead |

## Files

| File | Description |
|:-----|:------------|
| `HPCSKeyTest.java` | Java test application |
| `setup-and-run.sh` | Automated setup script |
| `grep11client.yaml.template` | Template for HPCS config |
| `README.md` | This file |

## Next Steps

After successful test:
1. Save the public key (`/tmp/pubkey.pem`) for PKI registration
2. In production, replace self-signed cert with AltaStata-issued certificate
3. Configure user's properties file with `key-protection=HPCS`
4. Integrate with AltaStata using `IBMHPCSKeyManager`
