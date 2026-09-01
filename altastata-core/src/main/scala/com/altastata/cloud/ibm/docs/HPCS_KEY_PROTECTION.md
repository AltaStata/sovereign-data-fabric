# 🔐 IBM HPCS Key Protection for AltaStata

## Overview

AltaStata supports protecting RSA private keys using IBM Hyper Protect Crypto Services (HPCS). This provides hardware-level security for private keys while maintaining the same encryption workflow.

## Property Structure

```properties
# Algorithm (what format/algorithm to use)
metadata-encryption=RSA

# Where private key is protected
key-protection=local    # Current: password-encrypted PEM file
key-protection=HPCS     # New: key in HSM via GREP11 (gRPC)
```

**Key insights**:
- `metadata-encryption` defines the algorithm (RSA, PQC, HSM)
- `key-protection` defines where the private key lives (local file or HPCS)
- Credentials are always encrypted with user's public key (admin encrypts before sending)

## Backward Compatibility

The `key-protection` property is **NEW** - existing property files don't have it:

```properties
# Existing IBM RSA file (no key-protection property)
metadata-encryption=RSA
accounttype=ibm-cos-secure
ibm-cos-hmac-access-key-id=<encrypted>
ibm-cos-hmac-secret-access-key=<encrypted>
```

**If `key-protection` is missing or null, it defaults to `local`** - no changes needed for existing users.

### Property File Examples

| Account Type | metadata-encryption | key-protection | Credentials |
|:-------------|:--------------------|:---------------|:------------|
| AWS HSM | `HSM` | N/A | Plain text (KMS handles) |
| IBM RSA (local key) | `RSA` | `local` (default) | Encrypted with public key |
| IBM RSA + HPCS | `RSA` | `HPCS` | Encrypted with public key |

### Current IBM RSA File (Local Key Protection)

```properties
#alice222 account IBM
ibm-cos-hmac-secret-access-key=<encrypted-base64>
ibm-cos-service-instance-id=crn:v1:bluemix:public:cloud-object-storage:...
ibm-cos-endpoint=https://s3.us.cloud-object-storage.appdomain.cloud
myuser=alice222
accounttype=ibm-cos-secure
ibm-cos-hmac-access-key-id=<encrypted-base64>
metadata-encryption=RSA
acccontainer-prefix=altastata-myorgrsa444-
# No key-protection property = defaults to "local"
```

### New IBM RSA + HPCS File (GREP11)

```properties
#alice222 account IBM with HPCS (GREP11)
ibm-cos-hmac-secret-access-key=<encrypted-base64>
ibm-cos-service-instance-id=crn:v1:bluemix:public:cloud-object-storage:...
ibm-cos-endpoint=https://s3.us.cloud-object-storage.appdomain.cloud
myuser=alice222
accounttype=ibm-cos-secure
ibm-cos-hmac-access-key-id=<encrypted-base64>
metadata-encryption=RSA
key-protection=HPCS
hpcs-yaml-path=/etc/ep11client/grep11client.yaml
hpcs-priv-key-blob-path=/path/to/account/hpcs-privkey.blob
hpcs-rsa-modulus-bits=4096
acccontainer-prefix=altastata-myorgrsa444-
```

## Key Protection Modes

| Mode | Property Value | Private Key Location | Security Level |
|:-----|:---------------|:---------------------|:---------------|
| **Local** | `key-protection=local` | Password-encrypted PEM file | Software |
| **HPCS** | `key-protection=HPCS` | HSM-encrypted token in HPCS | Hardware (FIPS 140-2 L4) |

Both modes use `metadata-encryption=RSA` - the encryption algorithm stays the same, only the private key protection changes.

## Properties Configuration

### Local Key Protection (Default)

```properties
# Account configuration
accounttype=ibm-cos-secure
myuser=bob
acccontainer-prefix=altastata-org-

# Encryption settings
metadata-encryption=RSA
key-protection=local                    # Private key in password-encrypted PEM file

# IBM COS credentials (encrypted with user's public key by admin)
ibm-cos-endpoint=https://s3.us-south.cloud-object-storage.appdomain.cloud
ibm-cos-service-instance-id=crn:v1:bluemix:public:cloud-object-storage:...
ibm-cos-hmac-access-key-id=<encrypted-base64>
ibm-cos-hmac-secret-access-key=<encrypted-base64>
```

### HPCS Key Protection (GREP11)

```properties
# Account configuration
accounttype=ibm-cos-secure
myuser=bob
acccontainer-prefix=altastata-org-

# Encryption settings
metadata-encryption=RSA
key-protection=HPCS

# GREP11: YAML path (or set GREP11_YAML env); API key and endpoint come from YAML
hpcs-yaml-path=/etc/ep11client/grep11client.yaml
hpcs-priv-key-blob-path=/path/to/account/dir/hpcs-privkey.blob
hpcs-rsa-modulus-bits=4096

# IBM COS credentials (encrypted with user's public key by admin)
ibm-cos-endpoint=https://s3.us-south.cloud-object-storage.appdomain.cloud
ibm-cos-service-instance-id=crn:v1:bluemix:public:cloud-object-storage:...
ibm-cos-hmac-access-key-id=<encrypted-base64>
ibm-cos-hmac-secret-access-key=<encrypted-base64>
```

## Architecture Comparison

### Local Key Protection

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Local Key Protection (key-protection=local)                           │
│                                                                         │
│  Private Key Storage:                                                   │
│  [RSA Private Key] → encrypt(password) → private.key file              │
│                                                                         │
│  On Login:                                                              │
│  private.key file → decrypt(password) → private key in memory          │
│                                                                         │
│  Decrypt Operation:                                                     │
│  encrypted data → decrypt with private key (in memory) → plaintext     │
└─────────────────────────────────────────────────────────────────────────┘
```

### HPCS Key Protection

```
┌─────────────────────────────────────────────────────────────────────────┐
│  HPCS Key Protection (key-protection=HPCS)                             │
│                                                                         │
│  Private Key Storage:                                                   │
│  [RSA Private Key] → generated IN HPCS → encrypted token returned      │
│  Token stored in: properties file (hpcs-private-key-token)             │
│                                                                         │
│  On Login:                                                              │
│  Load token from properties file (token is HSM-encrypted, safe)        │
│                                                                         │
│  Decrypt Operation:                                                     │
│  encrypted data + token → send to HPCS → HPCS decrypts → plaintext     │
│  (Private key NEVER leaves the HSM)                                    │
└─────────────────────────────────────────────────────────────────────────┘
```

## Encryption/Decryption Flow

Both modes use the same encryption flow - only decryption differs:

| Operation | Local | HPCS |
|:----------|:------|:-----|
| **Encrypt** | Use public key (local) | Use public key (local) ✅ Same |
| **Decrypt** | Use private key in memory | Send token to HPCS, get result |

**Encryption is always local** - no HSM call needed. The public key is retrieved from the Users bucket.

**Decryption dispatches** based on `key-protection` property:

### How decryptArrayWithRSA Would Work

```scala
def decryptArrayWithRSA(encrypted: Array[Byte], transformation: String)
                       (implicit account: Account): Array[Byte] = {
  
  account.userProps.getProperty("key-protection") match {
    case "HPCS" => 
      // NEW: Send to HPCS for unwrapping
      // The private key token is retrieved from storage (Users bucket or local)
      hpcsKeyManager.unwrap(encrypted, account.hpcsPrivateKeyToken)
      
    case _ => // "local" or null (default - current behavior)
      // CURRENT: Use local private key from memory
      val cipher = Cipher.getInstance(transformation)
      cipher.init(Cipher.DECRYPT_MODE, rsaPrivateKey)
      cipher.doFinal(encrypted)
  }
}
```

**Key points:**
- The calling code doesn't change - same `decryptArrayWithRSA` method
- Dispatch happens inside the method based on `key-protection` property
- For HPCS: encrypted data + token sent to HPCS, plaintext returned
- For local: standard Java Cipher with private key in memory

### encryptArrayWithRSA - No Change Needed

```scala
def encryptArrayWithRSA(plaintext: Array[Byte], publicKey: PublicKey, 
                        transformation: String): Array[Byte] = {
  // Always local - uses PUBLIC key from parameter
  // No dispatch needed, works the same for local and HPCS accounts
  val cipher = Cipher.getInstance(transformation)
  cipher.init(Cipher.ENCRYPT_MODE, publicKey)
  cipher.doFinal(plaintext)
}
```

**Encryption always uses the public key locally** - no HSM call needed. The public key is retrieved from the Users bucket (same as current flow).

### How signStringWithRSA Would Work

The `signString` method in `SecureCloudOperations` currently dispatches based on `metadata-encryption`:

```scala
// Current implementation
def signString(str: String)(implicit account: Account): String = {
  val signed = account.userProps.getProperty("metadata-encryption") match {
    case "RSA" => signStringWithRSA(str.getBytes("UTF-8"))
    case "PQC" => signWithDilithium(str.getBytes("UTF-8"))
    case "HSM" => account.cloudHSMHandler.encryptObjectWithHSM(...)
  }
  Base64.getEncoder().encodeToString(signed)
}
```

For HPCS, `signStringWithRSA` needs to dispatch based on `key-protection`:

```scala
def signStringWithRSA(data: Array[Byte])(implicit account: Account): Array[Byte] = {
  
  account.userProps.getProperty("key-protection") match {
    case "HPCS" => 
      // NEW: Send to HPCS for signing
      // The private key token is used for signing inside the HSM
      hpcsKeyManager.sign(data, account.hpcsPrivateKeyToken)
      
    case _ => // "local" or null (default - current behavior)
      // CURRENT: Use local private key from memory
      val signature = Signature.getInstance("SHA256withRSA")
      signature.initSign(loadRSAPrivateKey())
      signature.update(data)
      signature.sign()
  }
}
```

**Key points:**
- Signing uses the **private key** - same dispatch pattern as decryption
- For HPCS: data + token sent to HPCS, signature returned
- For local: standard Java Signature with private key in memory

### verifySignatureWithRSA - No Change Needed

```scala
def verifySignatureWithRSA(key: PublicKey, data: Array[Byte], signature: Array[Byte]): Boolean = {
  // Always local - uses PUBLIC key from parameter
  // No dispatch needed, works the same for local and HPCS accounts
  val sig = Signature.getInstance("SHA256withRSA")
  sig.initVerify(key)
  sig.update(data)
  sig.verify(signature)
}
```

**Signature verification always uses the public key locally** - no HSM call needed.

### Summary: Private Key Operations

All private key operations need HPCS dispatch:

| Operation | Method | Private Key Used | Needs HPCS Dispatch |
|:----------|:-------|:-----------------|:--------------------|
| **Decrypt** | `decryptArrayWithRSA` | Yes | ✅ Yes |
| **Sign** | `signStringWithRSA` | Yes | ✅ Yes |
| **Encrypt** | `encryptArrayWithRSA` | No (public key) | ❌ No |
| **Verify** | `verifySignatureWithRSA` | No (public key) | ❌ No |

## User Setup Flow

**Admin generates keys in HPCS on behalf of the user.** The private key never leaves the HSM - even admin cannot extract it.

### Step 1: Organization Sets Up Shared HPCS (One-time)

1. Provision IBM HPCS instance (~$1,500/month shared by all users)
2. Configure admin access to HPCS (API key, grep11client.yaml)

### Step 2: Admin Creates User with HPCS Keys

Admin performs all steps (using GREP11 over gRPC; no .so library):

1. **Generate RSA key pair in HPCS** for the user (or `HPCSKeyGeneratorCLI`) with `GREP11_YAML` or `hpcs-yaml-path` set:

```bash
./gradlew :altastata-core:runHPCSCreateKey \
  -PaccountDir="$HOME/.altastata/accounts/<account-dir>" \
  -PhpcsUser=<username>
```

Without `-PaccountDir` / `-PhpcsUser` the task writes the sandbox `amazon.rsa.hpcs.hpcsdev`. This writes `public.key` and `hpcs-privkey.blob` to the account dir.

2. **Create Service ID** for user: `AltaStata-{org}-{username}-service-id`

3. **Grant Service ID access to HPCS** (for decrypt/sign operations)

4. **Create API key** for user's Service ID

5. **Create buckets and IAM policies** for bucket access

6. **Encrypt HMAC credentials** with user's public key

7. **Create properties file** with encrypted credentials + GREP11 config (hpcs-yaml-path or GREP11_YAML, hpcs-priv-key-blob-path, hpcs-rsa-modulus-bits)

8. **Upload UserMetadata** (with public key) to users-all bucket

9. **Send to user**: properties file and HPCS API key (for their Service ID). For GREP11, the user puts the API key in **grep11client.yaml** (`tokens.0.users.<n>.iamauth.apikey`); no env var is required.

**Admin generates this properties file (GREP11):**

```properties
accounttype=ibm-cos-secure
myuser=bob
acccontainer-prefix=altastata-org-
metadata-encryption=RSA
key-protection=HPCS
hpcs-yaml-path=/etc/ep11client/grep11client.yaml
hpcs-priv-key-blob-path=/path/to/account/hpcs-privkey.blob
hpcs-rsa-modulus-bits=4096

# IBM COS configuration
ibm-cos-endpoint=https://s3.us-south.cloud-object-storage.appdomain.cloud
ibm-cos-service-instance-id=crn:v1:bluemix:public:cloud-object-storage:...
ibm-cos-hmac-access-key-id=<encrypted-base64>
ibm-cos-hmac-secret-access-key=<encrypted-base64>
```

### Step 3: User Configures and Logs In

User receives properties file and HPCS API key from admin:

1. **Configure `grep11client.yaml`** with HPCS endpoint, instance ID, and API key (see [GREP11 (gRPC) – YAML Configuration](#grep11-grpc--yaml-configuration)); set `GREP11_YAML` to the path to that file (or `hpcs-yaml-path` in properties).
2. **Log in to AltaStata** – credentials are decrypted using HPCS via GREP11.

### Step 4: Custodian Processes UserMetadata

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Custodian's handleUserMetadata()                                      │
│                                                                         │
│  1. Receives UserMetadata with publicKey                               │
│  2. Calls AltaStata CA → gets certificate for public key              │
│  3. Stores publicKeyCert in UserMetadata (removes publicKey)          │
│  4. Stores UserMetadata to Users bucket                                │
└─────────────────────────────────────────────────────────────────────────┘
```

### Security Guarantees

| Party | Can Do | Cannot Do |
|:------|:-------|:----------|
| **Admin** | Generate keys, grant access | Extract private key from HSM |
| **User** | Use key (decrypt/sign) | Extract private key from HSM |
| **HSM** | All crypto operations | Export private key (hardware enforced) |

## Complete Flow Diagram

```
┌─────────────────────────────────────────────────────────────────────────┐
│  HPCS Key Protection Flow (Admin Generates Keys)                        │
│                                                                         │
│  Organization (One-time):                                               │
│  1. Provisions shared HPCS instance (~$1,500/month)                     │
│  2. Configures admin HPCS access for key generation                    │
│                                                                         │
│  Admin (Does Everything):                                               │
│  3. Generates RSA key pair in HPCS (HPCSCreateKey / HPCSKeyGeneratorCLI)│
│  4. Creates Service ID, grants HPCS access, API key for user             │
│  5. Creates buckets, encrypts credentials, properties file (GREP11)    │
│  6. Uploads UserMetadata, sends properties file + API key to user       │
│                                                                         │
│  User (Receives and Configures):                                        │
│  7. Configures grep11client.yaml with endpoint and API key              │
│  8. Logs in – credentials decrypted using HPCS via GREP11               │
│                                                                         │
│  Custodian:                                                             │
│  9. handleUserMetadata() → gets certificate from AltaStata CA           │
│  10. Stores UserMetadata to Users bucket                               │
└─────────────────────────────────────────────────────────────────────────┘
```

## Security Benefits

| Aspect | Local | HPCS |
|:-------|:------|:-----|
| **Private key exposure** | In memory during use | Never leaves HSM |
| **Password protection** | Software encryption | Hardware protection |
| **FIPS 140-2 Level 4** | ❌ No | ✅ Yes |
| **Key extraction** | Possible if memory compromised | Impossible |
| **IBM access** | N/A | ❌ Cannot access (HSM-protected) |

## Key Differences from AWS KMS (HSM)

| Aspect | AWS KMS (HSM) | HPCS (RSA in HSM) |
|:-------|:--------------|:------------------|
| **Key type** | Symmetric (AES) | Asymmetric (RSA) |
| **Key storage** | AWS manages keys | Key in HSM, referenced by label |
| **Who generates** | Custodian | User |
| **Property** | `metadata-encryption=HSM` | `metadata-encryption=RSA` + `key-protection=HPCS` |
| **API** | AWS KMS API | GREP11 over gRPC |

## GREP11 (gRPC) – YAML Configuration

When using **GREP11 over gRPC** (no PKCS#11 .so), the application loads endpoint, port, instance ID, and API key from `grep11client.yaml`. The YAML path is resolved as follows:

| Source | Description | Example |
|:-------|:------------|:--------|
| **`hpcs-yaml-path`** | User property in `.user.properties` (per-account) | `hpcs-yaml-path=/etc/ep11client/grep11client.yaml` |
| **`GREP11_YAML`** | Environment variable (for S3 gateway, services, Docker) | `export GREP11_YAML=/etc/ep11client/grep11client.yaml` |

**Resolution order:** `hpcs-yaml-path` (if set) → `GREP11_YAML` env. No default path is assumed.

**Deployment examples:**
- **LinuxONE / S3 gateway:** Set `GREP11_YAML=/etc/ep11client/grep11client.yaml` (standard location).
- **Docker / systemd:** Pass `-e GREP11_YAML=/path/to/grep11client.yaml` or configure in the service environment.
- **Per-user:** Add `hpcs-yaml-path=/path/to/grep11client.yaml` to the user's properties file.

When GREP11 YAML is configured, `hpcs-user-pin`, `hpcs-key-label`, and `hpcs-token-space` are **not** required in properties (API key comes from the YAML; key label defaults to username).

**`hpcs-rsa-modulus-bits`** (GREP11): With GREP11 there is no local public key, so the application cannot infer the RSA key size. Set this to the modulus size in bits so the correct block size is used when decrypting credentials (1024→128 bytes, 2048→256 bytes, 4096→512 bytes). Allowed values: `1024`, `2048`, `4096`. If unset, it defaults to 256 bytes (2048-bit); for 4096-bit keys you must set `hpcs-rsa-modulus-bits=4096` or decryption will fail with `CKR_ENCRYPTED_DATA_LEN_RANGE`. Admin sets this automatically from the user's public key when creating user properties.

### HPCS Endpoints by Region

| Region | Endpoint |
|:-------|:---------|
| US South | `ep11.us-south.hs-crypto.cloud.ibm.com` |
| US East | `ep11.us-east.hs-crypto.cloud.ibm.com` |
| EU Germany | `ep11.eu-de.hs-crypto.cloud.ibm.com` |
| EU Great Britain | `ep11.eu-gb.hs-crypto.cloud.ibm.com` |
| AP Tokyo | `ep11.jp-tok.hs-crypto.cloud.ibm.com` |

### Example Properties File (GREP11)

```properties
# Account configuration
accounttype=ibm-cos-secure
myuser=bob
acccontainer-prefix=altastata-org-

# Encryption settings
metadata-encryption=RSA
key-protection=HPCS

# GREP11: YAML (endpoint, instance ID, API key); private key blob from HPCSCreateKey/HPCSKeyGeneratorCLI
hpcs-yaml-path=/etc/ep11client/grep11client.yaml
hpcs-priv-key-blob-path=/path/to/account/hpcs-privkey.blob
hpcs-rsa-modulus-bits=4096

# IBM COS credentials (encrypted with user's public key by admin)
ibm-cos-endpoint=https://s3.us-south.cloud-object-storage.appdomain.cloud
ibm-cos-service-instance-id=crn:v1:bluemix:public:cloud-object-storage:...
ibm-cos-hmac-access-key-id=ABC123...
ibm-cos-hmac-secret-access-key=XYZ789...
```

### User properties reference (GREP11 / S3 Gateway)

| Property | Meaning |
|----------|--------|
| **key-protection** | `HPCS` = private key in IBM HPCS; decrypt/sign via GREP11. |
| **hpcs-yaml-path** | Path to **grep11client.yaml** (endpoint, instance ID, API key). Omit if `GREP11_YAML` env is set. |
| **hpcs-priv-key-blob-path** | Path to **hpcs-privkey.blob** (see below). In Docker use container path (e.g. `/app/data/accounts/<user>/hpcs-privkey.blob`). |
| **hpcs-rsa-modulus-bits** | RSA modulus in bits (`1024`, `2048`, `4096`). Required for correct decrypt block size; use `4096` for 4096-bit keys. |
| **metadata-encryption** | `RSA` = credentials encrypted with this account’s RSA key. |
| **myuser** | Account username. |
| **accounttype** | e.g. `amazon-s3-secure` (S3 Gateway) or `ibm-cos-secure` (COS). |
| **AWSAccessKeyId** / **AWSSecretKey** (S3) | Encrypted credentials; decrypted at runtime in HPCS using the key referenced by the blob. |

### About hpcs-privkey.blob

**hpcs-privkey.blob** is the GREP11 **private key blob** for the account: an opaque handle (KeyBlob) returned by HPCS when the key was created. The actual private key never leaves the HSM.

- **Created by:** `runGrep11CreateKey` / `HPCSCreateKey` (writes `public.key`, `hpcs-privkey.blob`, `hpcs.marker` to the account dir).
- **Used for:** Decrypting encrypted credentials (e.g. AWSAccessKeyId, AWSSecretKey) and signing; the application passes the blob to GREP11, which performs the operation inside the HSM.
- **Security:** Store with your account data; protect the file and **grep11client.yaml** (which holds the API key to access HPCS).
- **Docker mount:** When running in Docker (e.g. Jupyter on LinuxONE), mount the blob file into the container. The source file **must exist** on the host; if it does not, Docker creates an empty directory instead, causing `IOException: Is a directory`. Example: `-v ~/hpcs-privkey.blob:/home/jovyan/hpcs-privkey.blob:ro`

## Implementation Classes

### New Classes

| Class | Purpose |
|:------|:--------|
| `IBMHPCSKeyManager` | GREP11 (gRPC) integration for key generation, decryption, and signing |

### Modified Classes

| Class | Method | Change |
|:------|:-------|:-------|
| `AsymmetricCryptoHandler` | `decryptRSA` | Add `key-protection` dispatch to HPCS |
| `AsymmetricCryptoHandler` | `signStringWithRSA` | Add `key-protection` dispatch to HPCS |

**Note**: The Admin Tool generates HPCS keys on behalf of the user via `IBMHPCSKeyManager.generateKeyPairInHPCS()`. The private key never leaves the HSM — even admin cannot extract it.

## Testing with IBM Cloud HPCS (GREP11)

Use GREP11 over gRPC (no .so library). Create the key with `HPCSCreateKey` or `HPCSKeyGeneratorCLI`; configure `grep11client.yaml` with endpoint, instance ID, and API key.

### Provision HPCS and configure grep11client.yaml

1. Provision an HPCS instance in IBM Cloud and create an API key (service credentials).
2. Create `grep11client.yaml`:

```yaml
iamCredentialType: apikey
iamAPIKey: <your-api-key>
ep11Server: ep11.<region>.hs-crypto.cloud.ibm.com:443
instanceID: <your-instance-id>
```

3. Create key and blob (set `GREP11_YAML` or `hpcs-yaml-path`):

```bash
./gradlew :altastata-core:runHPCSCreateKey \
  -PaccountDir="$HOME/.altastata/accounts/<account-dir>" \
  -PhpcsUser=<username>
```

This writes `public.key` and `hpcs-privkey.blob` to the account dir.
4. Run tests: `./gradlew :altastata-core:runHPCSAltaStataCryptoTest -PaccountDir=<dir>`

### Troubleshooting

| Error | Solution |
|:------|:---------|
| `CKR_ENCRYPTED_DATA_LEN_RANGE` | Set `hpcs-rsa-modulus-bits=4096` (or 2048) to match key size |
| `Connection refused` | Check HPCS instance and endpoint in grep11client.yaml |
| `Invalid API key` | Use correct IAM API key in grep11client.yaml |

### Architecture (GREP11)

```
┌─────────────────────────────────────────────────────────────────────────┐
│  AltaStata Application                                                  │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  IBMHPCSKeyManager  →  GREP11 gRPC client                        │   │
│  │  (reads grep11client.yaml, hpcs-privkey.blob)                     │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                              │  gRPC (GREP11)                            │
└──────────────────────────────│──────────────────────────────────────────┘
                               ▼
┌─────────────────────────────────────────────────────────────────────────┐
│  IBM Cloud HPCS  (ep11.<region>.hs-crypto.cloud.ibm.com:443)            │
│  HSM – FIPS 140-2 L4; private keys never leave HSM                      │
└─────────────────────────────────────────────────────────────────────────┘
```

### Cost Considerations

| Resource | Pricing |
|:---------|:--------|
| HPCS Standard Plan | ~$1,545/month per crypto unit |
| HPCS API Calls | Included in monthly fee |
| COS Storage | Pay per GB stored |

**Note**: For testing, consider using a single crypto unit in a non-production region.

## References

- [IBM Hyper Protect Crypto Services](https://cloud.ibm.com/docs/hs-crypto)
- [GREP11 API Documentation](https://cloud.ibm.com/docs/hs-crypto?topic=hs-crypto-grep11-api-ref)
- [GREP11 Signing Server Reference](./GREP11_SIGNINGSERVER_REFERENCE.md)


