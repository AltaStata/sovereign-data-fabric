# GREP11 reimplementation reference (IBM signingserver)

Reference for reimplementing HPCS support using **Enterprise PKCS#11 over gRPC (GREP11)** instead of the native `pkcs11-grep11-s390x.so`, following [IBM/signingserver](https://github.com/IBM/signingserver).

## Why

- **No native .so in process** – no JNI, no SIGSEGV from PKCS#11 library; all crypto over gRPC.
- **Same HSM** – still uses IBM Cloud HPCS or a GREP11 server; only the client stack changes.

## What signingserver does

- **Build**: Maven; downloads GREP11 proto, runs `protoc` to generate Java (`CryptoGrpc`, `KeyBlob`, requests/responses).
- **Config** (env):
  - **IBM Cloud HPCS**: `API_KEY`, `HPCS_ENDPOINT`, `HPCS_INSTANCEID`, `HPCS_PORT`, `KEYSTORE_PATH`.
  - **GREP11 server (mTLS)**: `CLIENT_KEY`, `CLIENT_CERT`, `CA_CERT` (base64), `HPCS_ENDPOINT`, `HPCS_PORT`.
- **gRPC channel**:
  - HPCS: `ManagedChannelBuilder.forAddress(endpoint, port).intercept(HeadersAddingInterceptor(apiKey, instanceId)).build()`
  - Interceptor adds headers: `authorization` (IAM token from `API_KEY` via `https://iam.cloud.ibm.com/identity/token`) and `bluemix-instance`.
  - GREP11 server: `Grpc.newChannelBuilder(endpoint + ":" + port, TlsChannelCredentials.newBuilder().trustManager(caCert).keyManager(clientCert, clientKey).build()).build()`
- **Stub**: `CryptoGrpc.newBlockingStub(channel)` (generated from proto).

## Key API usage (from signingserver)

### Create key pair (EC / Dilithium in example; we need RSA)

```java
GenerateKeyPairRequest request = GenerateKeyPairRequest.newBuilder()
  .setMech(mechanism)
  .putPubKeyTemplate(CKA_VERIFY, aTF(true))
  .putPrivKeyTemplate(CKA_SIGN, aTF(true))
  .putPrivKeyTemplate(CKA_EXTRACTABLE, aTF(false))
  // + curve/params for EC, etc.
  .build();
GenerateKeyPairResponse response = stub.generateKeyPair(request);
KeyPair kp = new KeyPair(response.getPubKey(), response.getPrivKey(), keyType);
```

### Sign

```java
SignSingleRequest request = SignSingleRequest.newBuilder()
  .setMech(mechanism)
  .setPrivKey(privKey)
  .setData(data)
  .build();
SignSingleResponse response = stub.signSingle(request);
ByteString signature = response.getSignature();
```

### Verify

```java
VerifySingleRequest request = VerifySingleRequest.newBuilder()
  .setMech(mechanism)
  .setPubKey(pubKey)
  .setData(data)
  .setSignature(signature)
  .build();
stub.verifySingle(request);
```

Keys are **KeyBlob** (opaque handles from GREP11); no private key material in our process.

## What we need for AltaStata

| Current (PKCS#11 .so)     | GREP11 reimplementation |
|--------------------------|-------------------------|
| `IBMHPCSKeyManager`      | New client using gRPC stub (RSA: generateKeyPair, sign, decrypt/unwrap) |
| `HPCSKeyGeneratorCLI`    | Use new client; same CLI args, no .so path |
| `HPCSUserAccountSetupHandler` | Use new client; same flow |
| Config: `grep11client.yaml` + .so path | Config: `HPCS_ENDPOINT`, `HPCS_PORT`, `API_KEY`, `HPCS_INSTANCEID` (or mTLS) |

**RSA in GREP11**: Proto has mechanisms for RSA; we need `GenerateKeyPairRequest` with RSA mechanism and CKA_DECRYPT/CKA_SIGN, and the decrypt/unwrap RPC (e.g. `DecryptSingle` or similar) for `unwrap()`.

## Files to clone for full reference

```bash
git clone https://github.com/IBM/signingserver
```

Relevant paths:

- `webapp/src/main/java/com/ibm/example/signingserver/utils/Config.java` – env-based config.
- `webapp/src/main/java/com/ibm/example/signingserver/cryptoclient/CryptoClient.java` – gRPC channel, IAM login, `createKeyPair`, `sign`, `verify`.
- `webapp/src/main/java/com/ibm/example/signingserver/cryptoclient/KeyPair.java` – holds `KeyBlob` pub/priv.
- `webapp/src/main/java/com/ibm/example/signingserver/api/KeysResource.java` – create key.
- `webapp/src/main/java/com/ibm/example/signingserver/api/SignatureResource.java` – sign.
- `webapp/pom.xml` – deps: `grpc-*`, `protobuf-java`; antrun downloads proto and runs `protoc`.
- Generated: `com/ibm/crypto/grep11/grpc/CryptoGrpc.java` (from GREP11 proto).

## Dependencies (Gradle equivalent)

- `io.grpc:grpc-netty` (or grpc-netty-shaded)
- `io.grpc:grpc-stub`
- `io.grpc:grpc-protobuf`
- `com.google.protobuf:protobuf-java`
- GREP11 proto → generate Java (build step with protoc).

## Build step (proto)

Signingserver’s pom: download GREP11 proto file, run gRPC compiler to generate Java. We need the same for our module (e.g. in `altastata-core` or a small `altastata-hpcs-grpc` module).

## YAML (.so) vs Java implementation config

The **grep11client.yaml** is used by the **native PKCS#11 library** (the .so). For the **Java (GREP11 gRPC) implementation**, we *also* support reading the same YAML via `Grep11ConfigFromYaml`.

**How the application finds the YAML** (see [HPCS_KEY_PROTECTION.md](./HPCS_KEY_PROTECTION.md)):

| Source | Description |
|:-------|:------------|
| `hpcs-yaml-path` | User property in `.user.properties` |
| `GREP11_YAML` | Environment variable (for deployment) |

When the YAML path is set, endpoint, port, instance ID, and API key are loaded from the YAML. Otherwise, fall back to properties/env:

| grep11client.yaml (.so) | Java implementation (YAML or properties / env) |
|-------------------------|----------------------------------------|
| `tokens.0.grep11connection.address` | YAML, or `hpcs-endpoint` or `HPCS_ENDPOINT` (host) |
| `tokens.0.grep11connection.port` | YAML, or `hpcs-port` or `HPCS_PORT` (e.g. 443) |
| `iamcredentialtemplate.instance` | YAML, or `HPCS_INSTANCEID` |
| `users.1.tokenspaceID` / `users.2.tokenspaceID` | `hpcs-token-space` (user props) or `HPCS_TOKENSPACE_ID` |
| `users.2.iamauth.apikey` | YAML, or `hpcs-user-pin` (user props) or `HPCS_API_KEY` (env) |

## build.gradle (altastata-core) and Java HPCS

- **Current state**: No change needed for the existing .so-based flow. The .so reads the YAML itself; the JVM does not need YAML or gRPC.
- **When adding the Java GREP11 client**: add gRPC deps (`io.grpc:grpc-netty-shaded`, `grpc-stub`, `grpc-protobuf`, `protobuf-java`), a build step to generate Java from the GREP11 proto, and optionally `org.yaml:snakeyaml` if we read `grep11client.yaml` from Java.

---

## Implementation plan (what to do to match signingserver)

To implement the Java GREP11 path like [IBM/signingserver](https://github.com/IBM/signingserver), do the following.

### 1. Build and proto (Gradle)

- **Add dependencies** in `altastata-core/build.gradle`:
  - `io.grpc:grpc-netty-shaded` (or `grpc-netty`) – transport
  - `io.grpc:grpc-stub` – stub base
  - `io.grpc:grpc-protobuf` – protobuf service stub
  - `com.google.protobuf:protobuf-java` – protobuf runtime
- **Obtain GREP11 proto**: Clone or download the [GREP11 proto definition](https://github.com/IBM-Cloud/hpcs-grep11) (or the proto file used by signingserver’s build).
- **Generate Java from proto**: Add a Gradle task that:
  - Runs `protoc` with the gRPC Java plugin on the GREP11 `.proto` file(s).
  - Writes generated Java (e.g. `CryptoGrpc.java`, request/response types) into `build/generated/source/proto` or `src/main/java` (and add that to the compile classpath).
- **Optional**: Depend on a prebuilt artifact that already contains the generated GREP11 Java stubs, if one exists, to avoid managing `protoc` in the build.

### 2. Configuration

- **Source**: User properties and/or environment (no YAML in JVM unless you add SnakeYAML).
- **Properties / env** (same as signingserver for IBM Cloud HPCS):
  - `HPCS_ENDPOINT` or `hpcs-endpoint` – EP11 host (e.g. `ep11.<region>.hs-crypto.cloud.ibm.com`)
  - `HPCS_PORT` or `hpcs-port` – e.g. `443`
  - `HPCS_INSTANCEID` or `hpcs-instance-id` – instance UUID
  - `API_KEY` / `HPCS_API_KEY` or `hpcs-user-pin` – IBM Cloud API key (used to obtain IAM token)
  - `hpcs-token-space` – tokenspace name/UUID (already in our user props)
  - `hpcs-key-label` / username – key label (we default to username)
- **IAM**: Resolve a Bearer token from the API key via `https://iam.cloud.ibm.com/identity/token` and send it (and `bluemix-instance`) on every gRPC call (see signingserver’s interceptor).

### 3. Code (Java or Scala)

- **Channel**: `ManagedChannel channel = ManagedChannelBuilder.forAddress(endpoint, port).useTransportSecurity().build()` (TLS). Add an interceptor that attaches:
  - Header `authorization: Bearer <IAM token>`
  - Header `bluemix-instance: <HPCS_INSTANCEID>`
  - Optionally tokenspace if required by the EP11 API.
- **Stub**: `CryptoGrpc.newBlockingStub(channel)` (from generated code).
- **RSA key generation**: Build `GenerateKeyPairRequest` with RSA mechanism and attributes:
  - Public: `CKA_VERIFY`, `CKA_ENCRYPT`
  - Private: `CKA_SIGN`, `CKA_DECRYPT`, `CKA_EXTRACTABLE = false`
  - Key size (e.g. 4096). Call `stub.generateKeyPair(request)`; store returned `KeyBlob`(s) and map to our key label (username).
- **Sign**: `SignSingleRequest` with mechanism (e.g. RSA PKCS#1 or PSS), private key `KeyBlob`, and data; call `stub.signSingle(request)`; return `response.getSignature()`.
- **Decrypt (unwrap)**: Use the GREP11 decrypt RPC (e.g. `DecryptSingle` or equivalent in the proto) with the private key `KeyBlob` and ciphertext; return decrypted bytes. This replaces the current PKCS#11 `Cipher.doFinal` unwrap path.
- **Public key**: From `GenerateKeyPairResponse` or a “get public key” RPC if available; encode as PEM for `getPublicKeyPEM()` and `getRSABlockSize()` (modulus bit length / 8).

### 4. Integration in altastata-core

- **Option A – New implementation**: Add a class (e.g. `IBMHPCSKeyManagerGrpc` or `GREP11KeyManager`) that implements the same interface/contract as the current `IBMHPCSKeyManager` (e.g. `generateKeyPairInHPCS()`, `unwrap()`, `sign()`, `getPublicKey()`, `getRSABlockSize()`), but uses the gRPC stub instead of PKCS#11. Select at runtime: if `hpcs-endpoint` (or `HPCS_ENDPOINT`) is set, use GREP11; else use existing .so path.
- **Option B – Replace**: Refactor `IBMHPCSKeyManager` so that behind a small abstraction it either loads the .so (current code) or builds the gRPC channel and uses the new GREP11 client; same public API.

### 5. References

- [IBM/signingserver](https://github.com/IBM/signingserver): Build (Maven + proto), `Config.java`, `CryptoClient.java`, IAM interceptor, key create/sign/verify.
- Signingserver env for HPCS: `API_KEY`, `HPCS_ENDPOINT`, `HPCS_INSTANCEID`, `HPCS_PORT`, `KEYSTORE_PATH`.
- Our doc: `GREP11_SIGNINGSERVER_REFERENCE.md` (this file).

---

*Fetched from https://github.com/IBM/signingserver (Apache-2.0). Use as reference only; implement our own client and config.*
