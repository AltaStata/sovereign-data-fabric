# AltaStata Release Notes

## 2026.09.01

### Core: cloud HTTP pools and in-flight chunk memory

- Split **chunk** vs **system** SDK HTTP pools (160 / 500) across AWS, Azure, GCS,
  Fusion, and S3-compatible backends so bulk 8 MiB transfers no longer starve
  catalog, attributes, and change-queue traffic.
- Reuse a **ThreadLocal 8 MiB** read buffer for chunk uploads (`heapBytesExact` for
  partial last chunks) to stop G1 humongous `OutOfMemoryError` on multi-GB stores
  with heap still free.
- Stream Azure/AWS chunk PUT bodies (avoid extra `fromBytes` copies); GCS treats
  `NoHttpResponseException` as transient and validates idle keep-alive sockets.
- Benchmark harness: `PerformanceMemoryProbe`, GCP `file-only` mode, optional
  `PERF_*` run-count env overrides.

### Public export

- Tag **`v2026.09.01`**. Wheel **`1.0.20260901.1`** (PyPI). Prior **`v2026.08.25`**
  release removed (no downloads).

## 2026.08.25

### Core: store must fail on chunk Fatal/OOM

- `ChunkPriorityDispatcher` now records **Fatal** errors (including `OutOfMemoryError`)
  so a file store Future fails instead of succeeding with missing chunks; workers stay alive.

### Performance harness (GCP / Azure / AWS)

- Fair text vs binary fixtures; object keys keep `.txt` / `.bin` so AltaStata compression applies.
- Trimmed download tables (ms, T/B paired) in `altastata-examples/docs/README-performance.md`.
- Download vs-native chart (1.0× = the cloud SDK) for GCP / Azure / AWS.
- AWS combined harness, all-clouds runner, LIVE summarizer; large heap default **10g**.
- Azure native large uploads use parallel `uploadFromFile` (2m HTTP timeout).

### Public export

- Tag **`v2026.08.25`** (prior date tags and releases remain available).

## 2026.08.23

### Batch share / revoke / delete by explicit path lists

- **Java** (`AltaStataFileSystem`): `sharePaths`, `revokePaths`, `deletePaths` resolve
  explicit cloud paths and call `shareCloudFiles` / `revokeReaderAccess` /
  `deleteCloudFiles` **once** on the combined `CloudFile` set (no per-path Scala loop).
- **gRPC**: new `FileOpsService.DeleteByPaths`; `SharingService.Share` / `Revoke` use
  batch path resolution server-side. Prefix APIs (`Delete`, `ShareByQuery`,
  `RevokeByQuery`) unchanged.
- **Python**: `share_paths`, `revoke_paths`, `delete_files_by_paths`; fsspec routes
  single-file share/revoke through path-list RPCs when `including_subdirectories=False`.
- **Web Console**: multi-select file delete uses `DeleteByPaths`; share/revoke already
  used path-list RPCs.

### Public export

- Tag **`v2026.08.23`** (prior **`v2026.08.19`** tag and release remain available).
- Wheel **`1.0.20260823.1`** (PyPI). GHCR Jupyter tags **`20260823.1`** alongside
  **`20260819.1`**.

## 2026.08.19

### PQC migration (Bouncy Castle 1.85)

Production post-quantum crypto now uses NIST algorithm names on the standard **`BC`** provider: **ML-KEM-1024** (was Kyber-1024 under `BCPQC`) and **ML-DSA-87** (was Dilithium5). Hybrid user identity certificates embed Kyber/Dilithium public key PEMs under AltaStata enterprise extension OIDs **`1.3.6.1.4.1.66133.1.1`** and **`.1.2`** (provisional PEN until IANA assigns the official number).

**Breaking — existing PQC accounts must migrate:**

1. Regenerate local Kyber/Dilithium key files (BC 1.85 ML-KEM/ML-DSA encoding; legacy `BCPQC` key OIDs are not loaded).
2. Update `account.json` `publicKeyPEM` fields and re-sign userdata (old demo cert extensions `1.2.3.4.1/2` are not read).
3. Re-provision cloud IAM/properties and upload fresh userdata to the org `users` bucket (copying `.user.properties` alone is not enough).

**Admin tooling:**

- `./gradlew :altastata-admin:runRegeneratePqcKeys` — regen PQC keys and refresh `account.json` (`PQC_KEY_PASSWORD`, `ACCOUNT_JSON_FILE`).
- `FORCE_REGENERATE_USERS=true ./gradlew :altastata-admin:runAmazonAdmin` — re-provision all users after key rotation.
- `./altastata-admin/scripts/teardown-aws-org.sh <org>` — tear down buckets/IAM before a clean reprovision.

PEM labels (`PUBLIC KYBER`, `PRIVATE DILITHIUM`, …) are unchanged (cosmetic only).

### Public export

- Tag **`v2026.08.19`**. Wheel **`1.0.20260819.15`** (PyPI). GHCR container tags aligned with this BSL cut.
- README / guides: Python docs under `docs/guides/`; cross-links to python package; Getting started **Who** column and **Where files live**.
- Desktop installers: macOS **Apple Silicon** `.dmg` and Windows **x64** `.exe` on [Releases](https://github.com/AltaStata/sovereign-data-fabric/releases) for this tag.

## 2026.08.16

### Security

- S3 gateway: `deleteObjects` requires a real SigV4 signature (removed the access-key bypass). The literal `test-signature` shortcut is off unless `ALTASTATA_S3_ACCEPT_TEST_SIGNATURES` / `altastata.s3.accept-test-signatures` is set.
- LocalFS (`localfs-secure`): object paths are resolved under `root-prefix`; `..` and similar escapes are rejected.

### Licensing / Community

- Community Tier allows one organization custodian identity outside the 5-user seat limit. FAQ wording lives in `LICENSE_FAQ.md`.

### Desktop UI

- Select Account **New / Config / Go** buttons are centered in the footer.

### Python package

- Wheel version scheme is now `1.0.YYYYMMDD.N` (this cut: **`1.0.20260816.1`**), matching Java tag `v2026.08.16`. Last `1.0.6.x` wheel was `1.0.6.16`.
- `pip install -U altastata==1.0.20260816.1`

## 2026.08.11

### Hadoop / HBase / JanusGraph

- AltaStata HDFS implements Hadoop `Syncable` so HBase WAL durability works on AltaStata storage.
- `AltaStataHadoopFileSystem` can load account config from `altastata.account.home`.
- Docker stack under `altastata-hadoop/docker-hbase-janusgraph` for HBase + JanusGraph on AltaStata HDFS, with Gremlin Server on host `localhost:8182`, large-graph test scripts, and `PERFORMANCE_TUNING.md`.

## 2026.08.08


### Windows metadata signature (charset)

- `verifySignature` now hashes authority strings as UTF-8 (same as `signString`). On Windows JVMs the platform default is often Cp1252, which mangled the version separator `✹` and caused `GetBuffer` / metadata signature failures after a successful create.
- Related UTF-8 alignment: encrypted object-path segments, userdata / authority-attrs serialization, AWS SigV4 body bytes, PEM store.
- Python package **1.0.6.16** ships a rebuilt `altastata-services` uber jar and still passes `-Dfile.encoding=UTF-8` when auto-starting the gateway (defense in depth; Desktop UI already did this).
- Verified on Windows: create/read smoke for `amazon.rsa.bob123` succeeds with and without the JVM encoding flag.

## 2026.07.25

### Dependency security

- Bouncy Castle → **1.85**; gRPC → **1.75.0**; Netty forced to **4.1.135.Final**; Jackson databind **2.18.8**.
- commons-lang3 **3.18.0**, commons-io **2.17.0**, Tika **3.2.2**, Gson **2.13.1**; remove stale Admin `netty-all:4.1.24`.

### MCP server (Model Context Protocol)

- New BSL module `altastata-mcp`, embedded in the `altastata-services` uber jar.
- Stdio MCP transport for Desktop agents (`--mcp-stdio`); tools call in-process `AltaStataFileSystem`.
- Default tools are read-only; `grant_access` / `revoke_access` remain off unless explicitly enabled.
- Python package exposes `altastata mcp` against the bundled services jar.

### CLI / account bootstrap

- Optional `altastata account change-password` (gRPC) for directory bootstrap and session-based Console flows.
- `altastata help` lists CLI commands.

### Docs

- Spark/Hadoop + Bouncy Castle JAR classpath notes clarified in the Python README appendix.
- Public BSL whitelist includes `altastata-mcp`.

## 2026.07.21

### Google Cloud Storage performance

- Wider Apache HTTP connection pool for GCS (parity with AWS maxConnections) so parallel tiny object PUT/GET is not limited to ~20 connections per host.
- Downloads use a single `readAllBytes` GET instead of metadata get + download.
- Softened layered retries under bulk fan-out.
- Opt-in live microbench: `GoogleStoragePerfBenchSpec` (not CI). Measured ~5× faster parallel small GETs vs prior client.


## 2026.07.20

### Enterprise Custodian mode

- Admin Tool checkbox and JWT gating for Enterprise Custodian mode; runtime stamp `enterprise-custodian-mode` on provisioned accounts (including Cognito/HSM roles).
- Custodian-managed SHARE/DELETE/revoke routed by signed `accessManager`; peer SHARE/DELETE rejected when the mode is on.

### Reliability

- Upload no longer shares to the custodian until `size`/`readers`/content are durable (fixes false “unexisting file” / Blob not found races on share).
- Already-consumed `REMOVEREADER` list/get races are treated as benign (no Strange noise).
- Azure list keeps hierarchy prefixes and skips only `hdi_isfolder` markers; document non-HNS storage accounts.
- Stop directory refresh on account switch to avoid null-password races.


### Security hardening

- Strengthened certificate and metadata-signature validation to fail closed.
- Switched RSA metadata encryption to OAEP with SHA-256.
- Generate a fresh AES-GCM nonce for every encrypted chunk and attribute.
- Require an incoming catalog `DELETE` event to be authorized by the data owner or custodian.
- Bound GZIP decompression to the maximum plaintext chunk size to prevent memory-exhaustion attacks.
- Prevent downloads from escaping the selected destination directory.
- Removed logging of credentials, tokens, private material, and sensitive user metadata.
- Removed the unused digital-hash verification layer; content integrity is enforced by AES-GCM authentication and signed metadata.
- Made string-to-SHA-256 conversion explicitly UTF-8 for consistent behavior across platforms.

### Administration and account setup

- Added support for password-encrypted organization CA private keys (`org-ca-private.key.enc`) in the Admin Tool.
- The Admin Tool prefers the encrypted organization key and prompts for its password when required.
- Azure SAS validity is configurable with `sasValidityYears` in `azure_admin.properties` (default: 10 years).
- Improved end-user account setup documentation for the Desktop UI and CLI.
- Refreshed Admin Tool and Desktop UI screenshots.

### Reliability

- Preserve transiently failed change events for retry instead of deleting them.
- Improved reader-revocation ordering so catalog access is removed before ACL bookkeeping completes.
- Cloud object deletion now fails closed except when the object is confirmed absent.

### Compatibility

- No migration is required for deployments that do not use encrypted paths.
- Legacy metadata signatures that do not cover the expanded authority fields are intentionally rejected.
- Existing plaintext `org-ca-private.key` files remain supported when no `.enc` file is present.
