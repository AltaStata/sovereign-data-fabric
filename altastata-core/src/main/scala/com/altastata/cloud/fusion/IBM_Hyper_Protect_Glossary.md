# IBM Hyper Protect Terminology & Java Libraries

**Purpose:** Clarify IBM Hyper Protect naming conventions and provide Java library references for integration.

---

## 1. The "Hyper Protect" Hierarchy

Think of "Hyper Protect" as the **Brand Name** for anything running on LinuxONE/Z that uses **Secure Execution (Hardware Encryption)** technology.

### Hyper Protect Platform
*   **What it is:** The umbrella term (marketing category). Not a specific product.
*   **Meaning:** The ecosystem of services protected by LinuxONE hardware root of trust.

---

## 2. Compute Components (Where Code Runs)

### HPVS - Hyper Protect Virtual Servers
*   **What it is:** A **Secure Virtual Machine (VM)**.
*   **The Tech:** KVM guest on LinuxONE with **encrypted memory**.
*   **Use Case:** Running AltaStata with software-only key protection.
*   **FIPS 140-2 L4:** ❌ No (Confidential Computing, not HSM)

### HPCR - Hyper Protect Container Runtime
*   **What it is:** The **Engine** that runs containers securely.
*   **The Tech:** Stripped-down HPVS designed only for OCI (Docker) images.
*   **How it works:** Spins up a secure VM, pulls encrypted image, runs container.

### HPCC - Hyper Protect Confidential Containers
*   **What it is:** The **Kubernetes/OpenShift Integration**.
*   **The Tech:** Connects **Red Hat OpenShift** to the **HPCR** engine via "Peer Pods".
*   **Your Target:** This is where AltaStata runs in production.

---

## 3. Security Components (Where Keys Live)

### HPCS - Hyper Protect Crypto Services
*   **What it is:** The **Cloud HSM** (full HSM, not just key storage).
*   **Under the hood:** Logical partition of physical Crypto Express Card.
*   **Certification:** FIPS 140-2 **Level 4**.
*   **Use Case:** Storing Bob's RSA-4096 Private Key (generated inside HSM).
*   **Key Features:**
    - Generate RSA key pairs **inside HSM**
    - **Extract public key** for distribution
    - Private key **never leaves** HSM
    - Decrypt/Sign operations run **inside HSM**
*   **API:** GREP11 (EP11 over gRPC) for asymmetric operations, Key Protect REST for symmetric.

### CEX - Crypto Express Card
*   **What it is:** The **Physical HSM Card** (PCIe).
*   **Location:** Plugged directly into LinuxONE server.
*   **Certification:** FIPS 140-2 **Level 4**.
*   **Use Case:** Low-latency key operations (~0.5-2ms).
*   **Key Features:**
    - Generate RSA key pairs **inside HSM**
    - **Extract public key** for distribution
    - Private key **never leaves** HSM
    - Decrypt/Sign operations run **inside HSM**
*   **API:** PKCS#11 via IBM JCE Provider (`IBMPKCS11Impl`).

| Component | Access Method | Latency | Best For |
|:----------|:--------------|:--------|:---------|
| **HPCS** | GREP11 (gRPC) | 10-50ms | Multi-node, cloud deployment |
| **CEX** | PKCS#11 (local) | 0.5-2ms | Single node, speed priority |

---

## 4. Java Libraries

### For HPCS (Cloud HSM) - GREP11

**Note:** For RSA asymmetric operations, use GREP11 (EP11 over gRPC). Key Protect REST API is for symmetric keys.

```xml
<!-- GREP11 client (gRPC) -->
<dependency>
    <groupId>io.grpc</groupId>
    <artifactId>grpc-netty-shaded</artifactId>
    <version>1.59.0</version>
</dependency>
```

**Java Code Example:**

```java
import com.ibm.cloud.hpcs.ep11.*;

// Connect to HPCS GREP11 endpoint
ManagedChannel channel = ManagedChannelBuilder
    .forAddress("ep11.us-south.hs-crypto.cloud.ibm.com", 13412)
    .useTransportSecurity()
    .build();
CryptoGrpc.CryptoBlockingStub ep11 = CryptoGrpc.newBlockingStub(channel);

// Unwrap DEK using Bob's Private Key in HPCS (RSA decryption inside HSM)
DecryptSingleRequest unwrapReq = DecryptSingleRequest.newBuilder()
    .setMech(Mechanism.newBuilder().setMechanism(CKM_RSA_PKCS_OAEP))
    .setPrivKey(ByteString.copyFrom(privateKeyBlob))  // Handle, not actual key
    .setCiphertext(ByteString.copyFrom(wrappedDek))
    .build();
DecryptSingleResponse resp = ep11.decryptSingle(unwrapReq);
byte[] dekBytes = resp.getPlain().toByteArray();  // DEK returned

// Decrypt data with DEK (client-side AES)
SecretKey dek = new SecretKeySpec(dekBytes, "AES");
Cipher aesCipher = Cipher.getInstance("AES/GCM/NoPadding");
aesCipher.init(Cipher.DECRYPT_MODE, dek, new GCMParameterSpec(128, iv));
byte[] plaintext = aesCipher.doFinal(encryptedData);
```

### For CEX (On-Prem HSM) - PKCS#11

**No Maven dependency** — uses IBM Java SDK built-in providers:
- `IBMPKCS11Impl` - PKCS#11 interface
- `IBMJCECCA` - CCA (Common Cryptographic Architecture)

**Requires:** IBM Java SDK (not OpenJDK) — download from [IBM Java SDK](https://www.ibm.com/support/pages/java-sdk-downloads)

**Note:** RSA has a size limit (~446 bytes for RSA-4096 with OAEP). Use **hybrid encryption**: wrap/unwrap DEK with RSA, encrypt data with AES.

**Java Code Example:**

```java
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;

// Load private key handle from CEX via PKCS#11
KeyStore ks = KeyStore.getInstance("PKCS11", "IBMPKCS11Impl");
ks.load(null, null);
PrivateKey bobPrivateKey = (PrivateKey) ks.getKey("bob-rsa-4096", null);

// Unwrap DEK (RSA decryption happens INSIDE CEX, private key never leaves)
Cipher rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
rsaCipher.init(Cipher.UNWRAP_MODE, bobPrivateKey);
SecretKey dek = (SecretKey) rsaCipher.unwrap(wrappedDek, "AES", Cipher.SECRET_KEY);

// Decrypt data with DEK (client-side AES)
Cipher aesCipher = Cipher.getInstance("AES/GCM/NoPadding");
aesCipher.init(Cipher.DECRYPT_MODE, dek, new GCMParameterSpec(128, iv));
byte[] plaintext = aesCipher.doFinal(encryptedData);
```

### For IBM Fusion (Storage) - S3 API

```xml
<!-- AWS S3 SDK (works with Fusion S3 Gateway) -->
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>s3</artifactId>
    <version>2.25.0</version>
</dependency>
```

---

## 5. Summary: How Components Fit Together

```
┌──────────────────────────────────────────────────────────────────────────┐
│  AltaStata on IBM Hyper Protect                                          │
│                                                                          │
│  ┌────────────────────────────────────────────────────────────────────┐  │
│  │  COMPUTE: HPCC (OpenShift) or HPVS                                 │  │
│  │  • AltaStata Library runs here                                     │  │
│  │  • Memory encrypted by Secure Execution                            │  │
│  └────────────────────────────────────────────────────────────────────┘  │
│                              │                                           │
│              ┌───────────────┴───────────────┐                          │
│              ▼                               ▼                          │
│  ┌─────────────────────────┐    ┌─────────────────────────┐            │
│  │  STORAGE: IBM Fusion    │    │  KEYS: HPCS or CEX      │            │
│  │  • S3 API               │    │  • RSA-4096 Private Key │            │
│  │  • Encrypted blobs      │    │  • FIPS 140-2 Level 4   │            │
│  │  • Java: AWS S3 SDK     │    │  • Java: Key Protect SDK│            │
│  │                         │    │    or PKCS#11           │            │
│  └─────────────────────────┘    └─────────────────────────┘            │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 6. Quick Reference

| Term | What Is It? | Java Library |
|:-----|:------------|:-------------|
| **HPVS** | Secure VM (encrypted memory) | N/A (runtime) |
| **HPCR** | Secure Container Engine | N/A (runtime) |
| **HPCC** | OpenShift + Peer Pods | N/A (runtime) |
| **HPCS** | Cloud HSM (REST API) | `ibm-key-protect-api` |
| **CEX** | Physical HSM Card (PKCS#11) | `IBMPKCS11Impl` (IBM JDK) |
| **Fusion** | S3-compatible Storage | `software.amazon.awssdk:s3` |

---

## 7. Related Documents

- **Key Management Architecture:** See `HSM_Key_Management.md`
- **Storage Integration:** See `README.md` (this package)
