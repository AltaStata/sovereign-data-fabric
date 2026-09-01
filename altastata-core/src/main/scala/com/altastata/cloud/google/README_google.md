# Google Cloud KMS and Storage Configuration

AltaStata supports Google Cloud Storage (GCS) and Google Cloud KMS for data storage and key management.

## Prerequisites
1. **Google Cloud Project**: You need a Google Cloud Project with Billing enabled.
2. **Service Account Credentials**: The AltaStata Admin tool needs a Service Account with the following roles to provision users and buckets:
   - `roles/storage.admin` (to create buckets and assign ACLs)
   - `roles/iam.serviceAccountAdmin` (to create per-user Service Accounts)
   - `roles/iam.serviceAccountKeyAdmin` (to generate keys for those SAs)
   - `roles/resourcemanager.projectIamAdmin` (prefix-based cross-bucket IAM conditions)
   - `roles/cloudkms.admin` (to create KMS KeyRings, Keys, and assign IAM policies)

   Set the `GOOGLE_APPLICATION_CREDENTIALS` environment variable pointing to the JSON key of this admin Service Account.

3. **Enable GCP APIs** on the project (one-time):
   - `iam.googleapis.com` (Service Account management)
   - `cloudresourcemanager.googleapis.com` (project-level IAM conditions)

   ```bash
   gcloud services enable iam.googleapis.com cloudresourcemanager.googleapis.com --project=YOUR_PROJECT
   ```

   The admin Service Account must hold the roles above **on the project** — not only your personal `gcloud` user.

## User Provisioning
When you run the AltaStata Admin Tool (see [ADMIN_TOOL_GUIDE.md](../../../../../../../../docs/guides/ADMIN_TOOL_GUIDE.md)), it will:
1. Create a dedicated Google Cloud Service Account for each user (e.g. `as-myorgrsa444-alice222@...`).
2. Generate a JSON key for this Service Account and store it in `credentials=`:
   - **HSM users**: plaintext JSON (no local private key to encrypt with)
   - **RSA/PQC users**: encrypted with the user's public key
3. On every admin run, refresh properties files for **all** users in `account.json` (rotates SA keys if needed).
4. Create 4 specific GCS buckets for each **new** user:
   - `altastata-{org}-chunks-{username}`
   - `altastata-{org}-dataattributes-{username}`
   - `altastata-{org}-catalog-{username}`
   - `altastata-{org}-changes-{username}`
5. Grant IAM in two layers (verified against live GCP):
   - **Bucket-level** (direct binding — own buckets only):
     - Regular users: `roles/storage.objectAdmin` on their 4 buckets (`chunks`, `dataattributes`, `catalog`, `changes`)
     - Custodian: `objectAdmin` on own `dataattributes`, `catalog`, and `changes` only — **no chunks bucket grant**
     - All users: `objectViewer` on `{prefix}users-all`; custodian gets `objectAdmin` on `users-all`
   - **Project-level** (prefix conditions — cross-user, AWS parity):
     - Regular users: `objectCreator` on `{prefix}changes-*`; `objectViewer` on `{prefix}chunks-*`, `dataattributes-*`, `catalog-*`
     - Custodian: `objectAdmin` on `{prefix}catalog-*`, `{prefix}dataattributes-*`, and `{prefix}changes-*`
     - Prefix grants cover future users automatically — no admin re-run needed when a new user is added
6. Per-user SA account IDs are truncated to **30 characters** (GCP limit). Long usernames may differ from the bucket suffix, e.g. user `myorgrsa444custodian` → SA `as-myorgrsa444-myorgrsa444cust@...`.
7. Pub/Sub topics are **not** created (not used for Google at this time).

## Provisioning

Provision GCS buckets, per-user service accounts, and optional Cloud KMS keys with the **Admin Tool** — see [ADMIN_TOOL_GUIDE.md](../../../../../../../../docs/guides/ADMIN_TOOL_GUIDE.md).

Set `GOOGLE_APPLICATION_CREDENTIALS` to your admin service-account JSON before you run Admin. Optional: `KMS_LOCATION` (default `europe-west1`), `KMS_KEY_RING` (default `altastata`), `GOOGLE_PROJECT_ID`.

Admin runs as the **admin SA** (`GOOGLE_APPLICATION_CREDENTIALS`, e.g. `altastata-storage-admin@...`).
Each user receives a **dedicated SA** (`as-{org}-{user}@...`) stored in their `credentials=` property.

| Resource | Tool / API | When it runs |
|----------|-----------|--------------|
| **Service Account** | Java SDK `google-iam-admin` → `IAMClient.createServiceAccount()` | Every admin run (all users) |
| **SA JSON key** | Java SDK `IAMClient.createServiceAccountKey()` | Every admin run |
| **GCS buckets** (4 per user + `users-all`) | Java SDK `google-cloud-storage` → `storage.create()` | **New users only** |
| **Bucket IAM** (user SA → bucket) | Java SDK `Storage.getIamPolicy()` / `setIamPolicy()` | Every admin run |
| **Project IAM** (prefix conditions) | Java SDK `google-cloud-resourcemanager` → `ProjectsClient.setIamPolicy()` | Every admin run |
| **KMS KeyRing** | Java SDK `google-cloud-kms` | HSM user create / refresh |
| **KMS keys** (`-encrypt`, `-sign`) | Java SDK `kms.createCryptoKey()` | HSM user create / refresh |
| **KMS IAM** | Java SDK `kms.setIamPolicy()` | HSM user create / refresh |
| **User metadata** in `users-all` | AltaStata core `SecureCloudFileSystemModel.storeUserdata()` | **New users only** |
| **Properties files** | Local Java `Properties.store()` | Every admin run (`~/.altastata/admin/properties.google/`) |

All provisioning runs via the **Java SDK** — the `gcloud` CLI is **not** required for the Admin Tool.

**Credentials flow:**

```
GOOGLE_APPLICATION_CREDENTIALS = admin SA (altastata-storage-admin)
         ↓
   Admin Tool provisions resources
         ↓
   Per-user SA + JSON key → credentials= in user properties
         ↓
   User runtime authenticates with their own SA
```

**New vs existing users:**

- **Existing users** (already in `users-all`): admin refreshes SA keys, properties, bucket IAM, and KMS IAM; does **not** recreate buckets or re-upload metadata.
- **New users**: Admin creates the SA and buckets, writes properties, applies IAM and KMS keys, then uploads metadata (including HSM key ids).
- Bucket/project IAM is applied after the per-user SA exists and has propagated (retries handle GCP lag).

## HSM (Cloud KMS) Integration
If a user is configured to use `HSM` as their metadata encryption type (instead of RSA or PQC), the system will use **Google Cloud KMS**.
The `GoogleKmsManager` will:
1. Verify/create a KeyRing specified in `kms-key-ring` and `kms-location`.
2. Create two Symmetric Encryption keys:
   - `{username}-encrypt`: Used for encrypting data.
   - `{username}-sign`: Used for signing data.
3. Grant **Separation of Duties** IAM roles:
   - The user's Service Account gets `roles/cloudkms.cryptoKeyEncrypterDecrypter` on both keys.
   - `allAuthenticatedUsers` gets `roles/cloudkms.cryptoKeyEncrypter` on the `encrypt` key (so others can share data with the user).
   - `allAuthenticatedUsers` gets `roles/cloudkms.cryptoKeyDecrypter` on the `sign` key (so others can verify the user's signature).

## User Properties File
The generated `altastata-{org}-{username}.user.properties` file will be stored in `~/.altastata/admin/properties.google/`.
For HSM users, this file will contain the unencrypted `credentials` JSON (since there is no RSA/PQC key to encrypt it) and the `kms-location` / `kms-key-ring` properties.

**Every admin run rotates per-user SA keys** and rewrites these files. To use an account in the UI, tests, or client apps, **copy the fresh `.user.properties`** from `properties.google/` into the account folder under `~/.altastata/accounts/`:

```bash
# Example: bob123 RSA account
cp ~/.altastata/admin/properties.google/altastata-myorgrsa444-bob123.user.properties \
   ~/.altastata/accounts/google.rsa.bob123/
```

Copy only the `.user.properties` file — the `private.key` in the account folder is unchanged. Stale credentials after teardown/recreate cause `Invalid JWT Signature` at runtime.

Account folder naming: `google.{rsa|pqc}.{hsm.}{username}` (e.g. `google.rsa.hsm.bob123` for HSM).

## Teardown (optional)

To tear down a test org, delete the GCS buckets, service accounts, and KMS keys with `gcloud`, then re-run the Admin Tool and copy fresh `.user.properties` from `~/.altastata/admin/properties.google/` into each account folder under `~/.altastata/accounts/`.

### Example Configuration Properties
```properties
accounttype=google-secure
myuser=bob123
google-project=my-gcp-project-123
kms-location=europe-west1
kms-key-ring=altastata
credentials={ "type": "service_account", "project_id": "...", ... }
```
