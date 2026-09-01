# altastata-grpc

`altastata-grpc` is a **Micronaut library** that exposes AltaStata RPC APIs on
port `9877` (gRPC, gRPC-Web, and the Web Console). It is no longer a
standalone Micronaut application — it is consumed by the
[`altastata-services`](../altastata-services) uber jar, which is the one
deployable artifact for the gRPC server, the S3 gateway, the Web Console, and MCP.
See [UBER_JARS.md](../docs/guides/UBER_JARS.md).

The server supports both:

- native gRPC (`application/grpc`) for Python/Java/etc.
- gRPC-Web (`application/grpc-web+proto` and `application/grpc-web-text+proto`) for browser JavaScript clients.

Launch options (via `altastata-services`):

- Dev run (recommended while developing):
  `./gradlew :altastata-services:run` from the repository root.
- Self-contained launch:
  `java -jar altastata-services/build/libs/altastata-services-YYYY.MM.DD-uber.jar`
  (Main-Class `com.altastata.services.AltaStataServicesLauncher`; gRPC is
  on by default, S3 / py4j gated via system properties — see
  [`altastata-services/README.md`](../altastata-services/README.md)).
- Python wheel ships the same uber jar under `altastata/lib/` and launches it
  either as `py4j.GatewayServer` (py4j transport) or as
  `AltaStataServicesApplication` (gRPC transport).

## Browser JavaScript quick start (gRPC-Web)

### 1) Install frontend deps

```bash
npm install grpc-web google-protobuf
```

### 2) Generate JS stubs from proto

Use `protoc` with `protoc-gen-grpc-web` against the auth and users proto files
in `altastata/grpc/v1`.

Example command (run in your frontend repo):

```bash
PROTO_ROOT=/path/to/sovereign-data-fabric/altastata-grpc/src/main/proto
protoc -I "$PROTO_ROOT" \
  "$PROTO_ROOT/altastata/grpc/v1/auth.proto" \
  "$PROTO_ROOT/altastata/grpc/v1/users.proto" \
  --js_out=import_style=commonjs,binary:src/gen \
  --grpc-web_out=import_style=commonjs,mode=grpcweb:src/gen
```

### 3) Call AltaStata from browser

```javascript
import { AuthServiceClient } from "./gen/altastata/grpc/v1/AuthServiceClientPb";
import { UsersServiceClient } from "./gen/altastata/grpc/v1/UsersServiceClientPb";
import { LoginV2Request } from "./gen/altastata/grpc/v1/auth_pb";
import { GetMyAccountRequest } from "./gen/altastata/grpc/v1/users_pb";

const endpoint = "http://127.0.0.1:9877";
const authClient = new AuthServiceClient(endpoint, null, null);
const usersClient = new UsersServiceClient(endpoint, null, null);

const login = new LoginV2Request();
login.setClientHint("browser-example");
login.setPassword("your-account-password");
login.setUserAccountDirectory(
  "/home/user/.altastata/accounts/amazon.rsa.bob123");

authClient.loginV2(login, {}, (err, resp) => {
  if (err) {
    console.error("LoginV2 failed:", err.message);
    return;
  }
  const metadata = { authorization: `Bearer ${resp.getSessionToken()}` };
  usersClient.getMyAccount(
    new GetMyAccountRequest(), metadata, (accountErr, account) => {
      if (accountErr) {
        console.error("GetMyAccount failed:", accountErr.message);
        return;
      }
      console.log("user:", account.getUserName());
    });
});
```

A full runnable version of this flow is available at:

- `altastata-grpc/examples/grpcweb-users-example.js`

Only the opaque `sess-*` token returned by `LoginV2` is accepted as the Bearer
token. Account names such as `local-bob123` are not authentication tokens.

## Serving the AltaStata Console SPA

The same port (`9877`) can also serve the React UI built from
[`altastata-console`](https://github.com/AltaStata/altastata-console).
This is opt-in: if no UI directory is configured, only gRPC is served
and the legacy behavior is preserved.

To enable it, set `ALTASTATA_WEB_UI_DIR` to the directory that
contains the SPA bundle (`index.html` plus assets) before launching
the server:

```bash
ALTASTATA_WEB_UI_DIR=/path/to/altastata-console-static ./gradlew :altastata-services:run
```

In production, the `altastata` Python package ships the bundle under
`altastata/lib/altastata-console-static/`, and the Python launcher
sets this env var automatically — same origin, same port, no separate
web container.

Behavior:

- `GET /` and any unknown path → `index.html` (SPA history-API fallback)
- Requests for real files (e.g. `/assets/index-XXXX.js`) → the file
  with the correct MIME type
- gRPC paths (`/altastata.v1.<Service>/<Method>`) take priority over
  static routing, so the same port multiplexes both cleanly
- Path traversal (`/../...`) is rejected at the HTTP routing layer
  with `400`, with a defense-in-depth check inside the handler

If `ALTASTATA_WEB_UI_DIR` points at a missing directory or one
without `index.html`, the server logs a warning and continues with
gRPC-only routing — it never fails to start.

You can override or pin the path via `application.yml`:

```yaml
grpcgateway:
  web-ui-dir: /path/to/altastata-console-static
```

## Notes

- Browsers cannot use raw gRPC/HTTP2 directly, so use a gRPC-Web client library.
- CORS is enabled on the gRPC port for browser calls.
- Keep production CORS origins restricted (replace `*` with explicit origins).

## Quick validation and troubleshooting

### Why opening the URL in browser does not work

Opening this in a tab:

- `http://127.0.0.1:9877/altastata.v1.UsersService/GetMyAccount`

does not execute an RPC because browser navigation sends a `GET` request.
gRPC-Web RPC methods require framed `POST` requests with gRPC-Web headers.

### Validate server is running

```bash
./gradlew :altastata-services:run
```

In another terminal:

```bash
lsof -nP -iTCP:9877 -sTCP:LISTEN
```

### Validate CORS preflight

```bash
curl -i -X OPTIONS "http://127.0.0.1:9877/altastata.v1.UsersService/GetMyAccount" \
  -H "Origin: http://localhost:3000" \
  -H "Access-Control-Request-Method: POST" \
  -H "Access-Control-Request-Headers: content-type,x-grpc-web,authorization"
```

Expected: `HTTP/1.1 200 OK` with `access-control-allow-origin` and
`access-control-allow-headers` including `x-grpc-web`.

### Validate gRPC-Web POST transport

```bash
python3 - <<'PY'
from pathlib import Path
Path('/tmp/grpcweb-empty.bin').write_bytes(bytes([0,0,0,0,0]))
PY

curl -i -X POST "http://127.0.0.1:9877/altastata.v1.UsersService/GetMyAccount" \
  -H "Content-Type: application/grpc-web+proto" \
  -H "x-grpc-web: 1" \
  -H "x-user-agent: grpc-web-javascript/0.1" \
  --data-binary @/tmp/grpcweb-empty.bin
```

Expected: a gRPC-Web response rather than `404`, with
`content-type: application/grpc-web+proto`. Without a Bearer session the RPC
normally reports `UNAUTHENTICATED`; that still proves the transport and route
are working.
