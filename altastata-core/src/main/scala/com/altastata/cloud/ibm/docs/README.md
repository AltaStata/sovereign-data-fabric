## Access Control Model

IBM COS doesn't support fine-grained path-based policies like AWS S3, so AltaStata uses individual buckets per user with IBM Cloud IAM groups for access control.

### Bucket Structure
Each user gets their own set of buckets:
- `altastata-{org}-catalog-{username}` - User's file catalog 
- `altastata-{org}-chunks-{username}` - User's file chunks
- `altastata-{org}-messages-{username}` - User's messages
- `altastata-{org}-dataattributes-{username}` - User's data attributes

Plus shared buckets:
- `altastata-{org}-users-all` - Shared user metadata (read-only for all)

### Access Control Design

#### Regular Users
Each user gets:
1. **Own buckets**: Full access (read/write/list/delete) to their personal buckets
2. **Cross-user access**: Via "AltaStata Users Group" membership
   - Chunks buckets: **Read/Write access only** (no list permission for privacy)
   - Messages buckets: **Write access only** (to send messages to other users)
   - DataAttributes buckets: **Read access only** (for collaboration)
   - Users bucket: **Read access only** (for user discovery)

#### Custodian Users
Custodian users (usernames ending with "custodian") get **specific limited access** to other users' buckets:
1. **Own buckets**: Full access (read/write/list/delete) to their personal buckets
2. **Cross-user access**: Direct individual policies with specific permissions:
   - **Catalog buckets**: Read and List (no Write access) - can view other users' catalogs
   - **Chunks buckets**: No access - custodians cannot access other users' chunks
   - **Messages buckets**: Write access only - can send messages to other users
   - **DataAttributes buckets**: Read and Write (no list permission) - for collaboration
   - **Users bucket**: Manager access - full control over shared user metadata

**Important**: Custodian users get **targeted permissions** for specific administrative workflows:
- Can browse user catalogs for oversight (read/list catalog buckets)
- Cannot access private user chunks (maintains data privacy)
- Can send administrative messages (write to messages buckets)
- Can collaborate on data attributes (read/write but no listing)
- Can manage user metadata (full control of users bucket)

### IBM Cloud IAM Components

#### Service IDs
- Each AltaStata user gets a dedicated IBM Cloud Service ID
- Service IDs are used for programmatic access (vs. human users)
- Service ID naming: `AltaStata-{org}-{username}-service-id`

#### HMAC Credentials
- **Regular users (RSA/PQC)**: Admin creates and encrypts HMAC credentials with user's public key
- **HSM users (HPCS)**: User creates their own HMAC credentials from their Service ID
  - Admin never knows HSM user's credentials (enhanced security)
  - User goes to IBM Cloud Console → IAM → Service IDs → Create credentials

#### Access Groups  
- **AltaStata Users Group**: Contains all regular users (non-custodian)
  - Provides cross-user read access for collaboration
  - Allows users to read others' dataattributes, send messages
  - Gives read/write access to chunks (but not list permission)

#### Individual Policies
- **Own Bucket Access**: Each user gets Manager access to their personal buckets
- **Custodian Admin Access**: Custodian users get Manager access to ALL buckets
- **Shared Bucket Access**: All users get appropriate access to shared resources

### Individual User Permissions
Each user has specific permissions on their own buckets:

| Bucket Type | User Access | Description |
|-------------|-------------|-------------|
| `catalog-{user}` | **Manager** | Full read-write access to personal catalog |
| `chunks-{user}` | **Object Reader/Writer** | Read-write access to chunks (no list permission) |
| `messages-{user}` | **Reader** | Read access to receive messages |  
| `dataattributes-{user}` | **Manager** | Full read-write access to data attributes | 

### HSM (HPCS) User Setup

HSM users use IBM Hyper Protect Crypto Services (HPCS) for hardware-protected RSA keys. **Admin generates keys in HPCS on behalf of the user** - the private key never leaves the HSM.

#### Prerequisites (Organization):
1. **Provision shared HPCS instance** (~$1,500/month shared by all users)
2. **Configure admin access** to HPCS for key generation

#### Admin Does Everything (Automatic via Admin UI):
1. **Generates RSA key pair in HPCS** via `IBMHPCSKeyManager.generateKeyPairInHPCS()` ✅
2. **Exports public key** (private key stays in HSM forever) ✅
3. **Creates Service ID**: `AltaStata-{org}-{username}-service-id`
4. **Grants Service ID access to HPCS** (decrypt/sign only)
5. **Creates API key** for user's Service ID
6. **Creates buckets and IAM policies** for bucket access
7. **Encrypts HMAC credentials** with user's public key ✅
8. **Creates properties file** with encrypted credentials + HPCS config ✅
9. **Uploads UserMetadata** to users-all bucket ✅
10. **Sends to user**: properties file + HPCS API key

#### User Receives and Configures:
1. **Configures `grep11client.yaml`** with HPCS endpoint and API key (or sets `GREP11_YAML` / `hpcs-yaml-path`)
2. **Uses account dir** with `hpcs-privkey.blob` and `public.key` (from HPCSCreateKey/HPCSKeyGeneratorCLI)
3. **Logs in** – credentials decrypted using HPCS via GREP11 (gRPC; no .so library)

#### Security Benefits:
- **Private RSA key never leaves the HSM** (FIPS 140-2 Level 4)
- **Even admin cannot extract private key** - only generate and grant access
- **Hardware-level protection** for all decrypt/sign operations

See [HPCS_KEY_PROTECTION.md](./HPCS_KEY_PROTECTION.md) for detailed HPCS setup instructions.

## Configuration

### Required Properties

When configuring IBM Cloud Object Storage in AltaStata Admin, provide these properties:

```properties
# IBM Cloud API Key (for IAM management)
ibm-api-key=your_ibm_cloud_api_key_here

# IBM COS S3-compatible endpoint
ibm-cos-endpoint=https://s3.us-south.cloud-object-storage.appdomain.cloud

# HMAC credentials for S3-compatible access
ibm-cos-hmac-access-key-id=your_hmac_access_key
ibm-cos-hmac-secret-access-key=your_hmac_secret_key

# IBM COS service instance ID
ibm-cos-service-instance-id=crn:v1:bluemix:public:cloud-object-storage:global:a/account:instance:instance-id
```

### Where to Get Configuration Values

#### 1. **`ibm-api-key`**
**Source**: IBM Cloud Console → Manage → Access (IAM) → API keys
- Click "Create an IBM Cloud API key"
- Give it a name like "AltaStata-API-Key"
- Copy the generated API key
- **Used for**: Creating Service IDs, Access Groups, and IAM policies

#### 2. **`ibm-cos-endpoint`**
**Source**: IBM Cloud Console → Storage → Object Storage → Your Instance → Configuration
- Select your region-specific endpoint
- **Used for**: S3-compatible API access to IBM COS

#### 3. **`ibm-cos-hmac-access-key-id`** + **`ibm-cos-hmac-secret-access-key`**
**Source**: IBM Cloud Console → Storage → Object Storage → Your Instance → Service credentials
- Click "New credential"
- **Advanced options** → Check "Include HMAC Credential" ✅
- Click "Add"
- In the generated JSON, find:
  ```json
  "cos_hmac_keys": {
    "access_key_id": "your-hmac-access-key-id",
    "secret_access_key": "your-hmac-secret-key"
  }
  ```
- **Used for**: S3-compatible authentication

#### 4. **`ibm-cos-service-instance-id`**
**Source**: IBM Cloud Console → Storage → Object Storage → Your Instance → Details
- Look for "CRN" (Cloud Resource Name)
- **Format**: `crn:v1:bluemix:public:cloud-object-storage:global:a/{account-id}:{instance-id}`
- **Used for**: Identifying the specific COS instance for IAM policies

### Example Values

#### COS Endpoints by Region:
- **US South**: `https://s3.us-south.cloud-object-storage.appdomain.cloud`
- **US East**: `https://s3.us-east.cloud-object-storage.appdomain.cloud`
- **EU Great Britain**: `https://s3.eu-gb.cloud-object-storage.appdomain.cloud`
- **EU Germany**: `https://s3.eu-de.cloud-object-storage.appdomain.cloud`
- **AP Tokyo**: `https://s3.jp-tok.cloud-object-storage.appdomain.cloud`

### Account Type
When creating user properties, AltaStata will use:
```properties
accounttype=ibm-cos-secure
```

### Setup Steps
1. **Create IBM COS instance** in IBM Cloud Console
2. **Generate HMAC credentials** in COS service credentials with "Include HMAC Credential" enabled
3. **Create API key** in IBM Cloud IAM for administrative operations
4. **Copy endpoint** for your region from COS configuration
5. **Extract CRN** from COS instance details
6. **Configure properties** in AltaStata Admin UI


## Grant IAM Permissions to Your API Key

Service: IAM Access Groups Service
Roles: Administrator or Editor
Resources: All resources in account

Service: IAM Identity Service  
Roles: Administrator or Editor
Resources: All resources in account

Service: Cloud Object Storage
Roles: Administrator or Manager
Resources: All resources in account 

## Troubleshooting

### HMAC Credentials Cannot Access Buckets

**Symptoms:**
- AltaStata setup completes successfully with "Successfully uploaded user metadata to users-all bucket"
- But when checking IBM Cloud Console or using AWS CLI, buckets appear empty
- Error messages like: `Access denied checking bucket: altastata-{org}-users-all (HMAC credentials insufficient)`

**Root Cause:**
HMAC credentials are associated with a **user account** (not a Service ID), but the user account lacks the necessary IAM policies to access the AltaStata buckets. The setup process creates policies for Service IDs but doesn't automatically create policies for the user account that owns the HMAC credentials.

**Diagnosis:**
1. **Check HMAC credential owner:**
   ```bash
   ibmcloud iam api-keys --output json | jq '.[] | select(.name | contains("HMAC") or contains("Storage") or contains("Admin")) | {name: .name, iam_id: .iam_id}'
   ```

2. **Verify current user:**
   ```bash
   ibmcloud account show
   ```

3. **Test bucket access:**
   ```bash
   aws s3 ls s3://altastata-{org}-users-all/ --endpoint-url=https://s3.{region}.cloud-object-storage.appdomain.cloud
   ```

**Resolution:**
Create an IAM policy granting your user account access to the AltaStata buckets:

```bash
# For users-all bucket (most common case)
ibmcloud iam user-policy-create your.email@domain.com \
  --roles Manager \
  --service-name cloud-object-storage \
  --resource-type bucket \
  --resource altastata-{org}-users-all

# For all AltaStata buckets (comprehensive fix)
ibmcloud iam user-policy-create your.email@domain.com \
  --roles Manager \
  --service-name cloud-object-storage \
  --attributes "serviceName=cloud-object-storage,resourceType=bucket,resource=altastata-{org}-*"
```

**Prevention:**
The AltaStata admin application should automatically detect and create these policies. If this issue persists, it indicates the admin application's HMAC credential detection logic needs enhancement.

### Users Cannot Access Their Own Buckets

**Symptoms:**
- Individual users report they cannot access their personal buckets
- User Access Report shows "❌ No individual bucket policies found"
- Users are not members of the access group

**Diagnosis:**
1. **Run User Access Report** from AltaStata Admin GUI
2. **Check Service ID policies:**
   ```bash
   ibmcloud iam service-policies {service-id} --output json
   ```
3. **Verify group membership:**
   ```bash
   ibmcloud iam access-group-members AltaStataUsersGroup_{org}
   ```

**Resolution:**
Re-run the AltaStata setup process, which should:
1. Create missing Service IDs
2. Generate appropriate IAM policies
3. Add users to the access group
4. Create API keys for authentication

### Access Group Not Found

**Symptoms:**
- User Access Report shows "❌ AltaStata access group not found!"
- Users cannot collaborate (no cross-user access)

**Resolution:**
Re-run the IBM setup in AltaStata Admin to recreate the access group and associated policies.

### Policy Conflicts

**Symptoms:**
- Error: "The policy wasn't created because an access policy with identical attributes already exists"

**Resolution:**
This is usually **not an error** - it indicates the policy already exists and is working correctly. If you need to modify the policy:

```bash
# List existing policies
ibmcloud iam user-policies your.email@domain.com

# Update policy if needed
ibmcloud iam user-policy-update your.email@domain.com {policy-id} --roles Manager,Reader
``` 