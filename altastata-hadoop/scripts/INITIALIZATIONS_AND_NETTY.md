# Initializations and Netty: Python/pip vs S3 Gateway

## Python/pip path (`transport="py4j"`)

**What runs in the JVM:**

1. **Process start**
   Python’s `base_gateway.py` starts the JVM with:
   - **Main class:** `py4j.GatewayServer` (only Py4J’s socket gateway).
   - **Classpath:** `py4j*.jar`, `bcprov*.jar`, `bcutil*.jar`,
     `altastata-services-YYYY.MM.DD-uber.jar`, `bcpkix*.jar`
     (the wheel ships this jar under `altastata/lib/`; there is no Hadoop jar
     on this path).

2. **At JVM startup**
   - No AltaStata main, no HTTP server, no Netty.
   - Only Py4J’s `GatewayServer` runs and listens on port 25333.

3. **When Python calls in**
   - `AltaStataFunctions.from_credentials(...)` → `gateway.jvm.com.altastata.api.AltaStataFileSystem(...)`
     → Java constructs `AltaStataFileSystem` via `AccountRegistry` (see
     `ALTASTATA_SERVICES_UBER_DESIGN.md` §4). Still no Netty.
   - `set_password("")` → Java `Account.setPassword`. On s390x: HPCS proxy is used (no .so in JVM). On other arches: `validatePrivateKeyAccess` → `keyStore.load`, `getKey` on the Py4J thread.

**Netty in this path:** **No.** The JVM does not start Netty or any HTTP server. The only “server” is Py4J’s socket listener. On s390x, HPCS runs in a separate proxy process; the main JVM never loads the .so.

The same wheel can instead launch `com.altastata.services.AltaStataServicesApplication`
(`transport="grpc"`). That path **does** start the Services HTTP/gRPC stack
and is not the Py4J-only case described here.

---

## S3 Gateway path (Docker or `java -jar`)

**What runs in the JVM:**

1. **Process start**
   - **Main:** `com.altastata.services.AltaStataServicesApplication`
     (there is no `S3GatewayApplication` class).
   - **Server:** Micronaut HTTP server → **Netty** (e.g. `micronaut-http-server-netty`, `micronaut-http-netty`).

2. **At startup**
   - Netty starts and creates its event loop and worker threads.
   - On s390x, HPCS uses a proxy process (started by core on first use); no .so in the main process.

3. **When S3 requests arrive**
   - Handled by **Netty worker threads**. Any call into AltaStata (e.g. HPCS) from a request runs on those threads. Using the HPCS PKCS#11 .so from multiple Netty threads has been observed to cause crashes (SIGFPE/SIGSEGV) on LinuxONE.

**Netty in this path:** **Yes.** Netty is the HTTP server; HPCS is (or can be) invoked from Netty threads, which is why the S3 gateway path is sensitive to HPCS threading issues.

---

## Summary

| Path              | Netty runs? | Who calls HPCS?        | Typical crash context        |
|-------------------|-------------|------------------------|-----------------------------|
| Python/pip (Py4J) | **No**      | Single Py4J thread     | PKCS#11 .so on one thread   |
| S3 Gateway        | **Yes**     | Netty worker threads   | PKCS#11 .so from many threads |

So: **we do not have Netty (or any HTTP server) in the Python/pip Py4J path.** On s390x, the JVM uses the HPCS proxy (started on first decrypt/sign). GREP11 credentials (endpoint, instance ID, API key) come from **grep11client.yaml**; set `GREP11_YAML` to the path to that file (or `hpcs-yaml-path` in account properties) so the proxy can connect to HPCS.
