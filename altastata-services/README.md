# AltaStata Services

One JVM (`com.altastata.services.AltaStataServicesLauncher`) that hosts the S3
gateway, the gRPC gateway, the Web Console, the Python bridge, and the MCP
server as **opt-in libraries**. `altastata-grpc`, `altastata-s3-gateway`, and
`altastata-mcp` are libraries with no `main()` of their own — this module is how
you run them.

Sharing one process is deliberate: per-account caches
(`com.altastata.api.AccountRegistry`), one authenticated BouncyCastle provider,
and one set of Netty event loops. S3 also *requires* it — session credentials
are issued by gRPC `LoginV2` → `IssueCredentials` inside this JVM, so a separate
S3 process could not resolve them.

## Run it

### From a release (no build)

Download `altastata-services-YYYY.MM.DD-uber.zip` from
[Releases](https://github.com/AltaStata/sovereign-data-fabric/releases) — it
contains the JAR and the signed Bouncy Castle `lib/` it needs:

```bash
unzip altastata-services-YYYY.MM.DD-uber.zip
cd <extracted-dir>          # must contain the jar and lib/bc*.jar
java -jar altastata-services-YYYY.MM.DD-uber.jar
```

Full details, including why `lib/` must stay next to the JAR:
[UBER_JARS.md](../docs/guides/UBER_JARS.md).

### From source

```bash
# gRPC + py4j (defaults; Console needs ALTASTATA_WEB_UI_DIR)
./gradlew :altastata-services:run

# add the S3 gateway
./gradlew :altastata-services:run -Daltastata.services.s3gateway.enabled=true

# serve the Web Console SPA from the same port as gRPC
ALTASTATA_WEB_UI_DIR=/path/to/console/dist ./gradlew :altastata-services:run
```

The `run` task forwards `-D` system properties from the Gradle command line, so
any gate below can be flipped without editing `application.yml`.

Build the uber JAR yourself:

```bash
./gradlew :altastata-services:clean :altastata-services:shadowJar
# jar:      altastata-services/build/libs/altastata-services-<version>-uber.jar
# BC jars:  altastata-services/build/libs/lib/bc*-jdk18on-*.jar
```

Bouncy Castle stays **outside** the uber JAR on purpose. Shading strips the JCE
signature, after which every private-key operation fails with
`JCE cannot authenticate the provider BC`. The manifest `Class-Path` points at
`lib/`, so run the JAR from the directory that contains it.

## Ports and feature gates

Only a service whose gate is on binds its port.

| Service | Port | Gate property | Environment variable | Default |
|---------|------|---------------|----------------------|---------|
| gRPC + gRPC-Web (and Web Console when a UI dir is set) | `9877` | `altastata.services.grpc.enabled` | `ALTASTATA_SERVICES_GRPC_ENABLED` | on |
| S3-compatible REST (Micronaut HTTP) | `9876` | `altastata.services.s3gateway.enabled` | `ALTASTATA_SERVICES_S3GATEWAY_ENABLED` | **off** |
| py4j bridge for the Python wheel | `25333` (`py4j.port`) | `altastata.services.py4j.enabled` | `ALTASTATA_SERVICES_PY4J_ENABLED` | on |
| MCP JSON-RPC (stdio, no port) | — | `altastata.services.mcp.enabled` | `ALTASTATA_SERVICES_MCP_ENABLED` | **off** |

Other settings in `src/main/resources/application.yml`:

- `grpcgateway.bind-address` (`ALTASTATA_GRPC_BIND_ADDRESS`, default
  `127.0.0.1`) — TLS auto-enables on a non-loopback bind.
- `grpcgateway.web-ui-dir` or `ALTASTATA_WEB_UI_DIR` — directory with the
  Console SPA (`index.html`); when unset, only gRPC is served on `9877`.
- `py4j.port` — py4j listener port, set with `-Dpy4j.port=<port>`; default
  `25333`.
- `micronaut.server.max-request-size` / multipart limits — `6GB`, sized for S3
  multipart uploads.
- `altastata.mcp.*` — MCP account binding and read limits, see
  [altastata-mcp/README.md](../altastata-mcp/README.md).

## The three deployments this jar covers

| Deployment | Command | What runs |
|------------|---------|-----------|
| Python wheel / Jupyter | default | gRPC + py4j |
| Release / co-hosted server | `-Daltastata.services.s3gateway.enabled=true` | gRPC + S3; Console when `ALTASTATA_WEB_UI_DIR` is set |
| Claude Desktop / Cursor | `java -jar …-uber.jar --mcp-stdio` | MCP only |

`--mcp-stdio` is handled in `AltaStataServicesLauncher` *before* Micronaut and
Logback start: it turns off gRPC, S3, py4j, and the HTTP server, and moves logs
to stderr, because stdout is the MCP JSON-RPC wire.

## Accounts

No account is bound at startup in the default deployment. The Console and the
Python wheel's gRPC transport call `LoginV2`; active accounts then live in the
shared `AccountRegistry`, and the S3 gateway accepts SigV4 credentials issued
for those sessions. The wheel's py4j transport instead calls
`AccountRegistry.getOrCreateFromDir` in-process and does not create a gRPC
session merely to access files. Create an account first:
[USER_SETUP_GUIDE.md](../docs/guides/USER_SETUP_GUIDE.md).

MCP stdio is the exception: it binds one account up front via
`ALTASTATA_MCP_ACCOUNT_DIR` / `ALTASTATA_MCP_PASSWORD`.

## Verify

Each enabled service announces itself in the log:

```text
gRPC gateway started on http://127.0.0.1:9877
Serving static UI from <dir> on the gRPC gateway port
py4j gateway listening on port 25333
AltaStata MCP server ready on stdio
```

Logs go to the console and to
`~/.altastata/services/logs/logfile-<yyyyMMdd>.log` (MCP stdio writes
`mcp-stdio-<yyyyMMdd>.log` instead, since stdout carries JSON-RPC).

There are no `/health` or `/ready` endpoints. Check the sockets directly:

```bash
nc -z 127.0.0.1 9877 && echo "gRPC / Console listening"
nc -z 127.0.0.1 9876 && echo "S3 listening"
nc -z 127.0.0.1 25333 && echo "py4j listening"
```

With `ALTASTATA_WEB_UI_DIR` set, open `http://127.0.0.1:9877` in a browser and
log in with your account directory and passphrase.

## Related

- [UBER_JARS.md](../docs/guides/UBER_JARS.md) — which JAR to use where
- [altastata-s3-gateway/README.md](../altastata-s3-gateway/README.md) — S3 clients, credentials, multipart
- [altastata-grpc/README.md](../altastata-grpc/README.md) — gRPC API and Web Console
- [altastata-mcp/README.md](../altastata-mcp/README.md) — MCP tools and policy
- [ALTASTATA_SERVICES_UBER_DESIGN.md](ALTASTATA_SERVICES_UBER_DESIGN.md) (internal) — why one JVM
- [DEVELOPERS_GUIDE.md](../docs/guides/DEVELOPERS_GUIDE.md) — build and JVM setup
