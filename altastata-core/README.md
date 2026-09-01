# AltaStata Core

`altastata-core` is the foundational Java/Scala library providing client-side zero-trust encrypted filesystem operations across any cloud or local storage.

## Overview

AltaStata Core transforms standard object storage and block storage into a secure, sovereign data fabric where every file is cryptographically isolated and signed with zero-trust key management.

### Key Capabilities

- **Unified File System API**: `AltaStataFileSystem` provides standard POSIX-like hierarchical filesystem operations (`open`, `create`, `delete`, `rename`, `listStatus`, `mkdir`) transparently layered over cloud object stores.
- **Client-Side Cryptography**:
  - **Symmetric Cipher**: AES-256-GCM authenticated per-file and per-chunk encryption.
  - **Asymmetric Key Exchange**: RSA-OAEP (2048/4096-bit).
  - **Post-Quantum Cryptography (PQC)**: NIST-standardized ML-KEM (Kyber) for quantum-resistant key encapsulation and ML-DSA (Dilithium) for digital signatures.
  - **Hardware Security Modules (HSM)**: IBM Hyper Protect Crypto Services (HPCS) via GREP11 EP11 over gRPC, and cloud KMS integration.
- **Supported Storage Providers**:
  - AWS S3
  - Google Cloud Storage (GCS)
  - Azure Blob Storage & Azure Data Lake Storage (ADLS Gen2)
  - IBM Cloud Object Storage (COS)
  - MinIO & S3-compatible endpoints
  - Red Hat Ceph / NooBaa (Fusion)
  - POSIX / Local filesystem
- **High-Performance Streaming & Caching**: Multi-threaded chunk transfers with client-side caching, adaptive rate control, and fast integrity verification.

## Architecture

```
+-------------------------------------------------------------+
|               Applications / Spark / Python / S3             |
+-------------------------------------------------------------+
                              |
                              v
+-------------------------------------------------------------+
|              AltaStataFileSystem (Core Engine)               |
|                                                             |
|  +--------------------+  +-------------------------------+  |
|  | Encryption Service |  | Stream & Chunk Transfer Engine|  |
|  | (AES/RSA/PQC/HSM)  |  | (Parallel I/O, Caching)       |  |
|  +--------------------+  +-------------------------------+  |
+-------------------------------------------------------------+
                              |
                              v
+-------------------------------------------------------------+
|                     Storage Adapters                        |
|  (AWS S3 | Azure Blob | GCP GCS | IBM COS | MinIO | POSIX)  |
+-------------------------------------------------------------+
```

## Basic Usage (Java / Scala)

Scala is the low-level API: `Account` plus `CloudFile` on
`fileSystemModel`. Java `AltaStataFileSystem` is syntactic sugar over that
(path strings instead of `CloudFile` lists; construct it through
`AccountRegistry` — the constructor is package-private). See
[HOWTO.md](../docs/guides/HOWTO.md) (Java / UI / S3),
[Low-level-Scala-API.md](../docs/guides/Low-level-Scala-API.md), and
[altastata-examples](../altastata-examples/README.md).
For Spark / Hadoop `altastata://` paths, use the Hadoop uber JAR —
[UBER_JARS.md](../docs/guides/UBER_JARS.md).

```java
import com.altastata.api.AccountRegistry;
import com.altastata.api.AltaStataFileSystem;

AltaStataFileSystem fs = AccountRegistry.getOrCreateFromDir(
    System.getProperty("user.home") + "/.altastata/accounts/amazon.rsa.bob123"
);
fs.setPassword("your_password");

byte[] dataBytes = java.nio.file.Files.readAllBytes(
    java.nio.file.Path.of("dataset.parquet"));
fs.createFile("Public/dataset.parquet", dataBytes);

try (java.io.InputStream in = fs.getFileInputStream("Public/dataset.parquet", null, 0L, 4)) {
    byte[] buffer = in.readAllBytes();
}
```

```scala
import com.altastata.utils.Account
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.{Files, Paths}
import scala.collection.JavaConverters._

val account = new Account()
account.loadAccountProperties(
  Account.ALTASTATA_ACCOUNTS_HOME + File.separator + "amazon.rsa.bob123")
account.setPassword("your_password".toCharArray)

val dataBytes = Files.readAllBytes(Paths.get("dataset.parquet"))
val cloudFile = account.getFileSystemHandler()
  .createCloudFileVersion("Public/dataset.parquet", false, System.currentTimeMillis)
account.secureCloudFileSystemModel.storeByteBufferToCloudFile(
  ByteBuffer.wrap(dataBytes), cloudFile)

account.fileSystemModel.listCloudFiles("Public/", true).asScala.foreach(println)
```

## Testing

```bash
# Fast local unit tests
./gradlew :altastata-core:test --tests "com.altastata.filesystem.securecloud.*"

# Run integration tests against cloud providers
RUN_AZURE_IT=1 ./gradlew :altastata-core:test --tests TransferSchedulingAzureITSpec
```
