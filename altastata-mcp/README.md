# altastata-mcp

Model Context Protocol (MCP) front-end for AltaStata.

Agents (Claude Desktop, Cursor, …) talk **MCP** (JSON-RPC over stdio).
Tool handlers call the same in-process `AltaStataFileSystem` APIs that
back the gRPC gateway — there is **no second gRPC hop** inside the JVM.

## Enable

Embedded in `altastata-services` uber jar:

```bash
# Claude Desktop / Cursor — stdio (logs go to stderr + ~/.altastata/services/logs)
java -jar altastata-services-YYYY.MM.DD-uber.jar --mcp-stdio

# Or via Micronaut properties (co-hosted with gRPC):
# -Daltastata.services.mcp.enabled=true
# -Daltastata.mcp.account-dir=$HOME/.altastata/accounts/bob
# -Daltastata.mcp.password=...
```

Account binding (required for real tools):

| Env / property | Meaning |
|---|---|
| `ALTASTATA_MCP_ACCOUNT_DIR` / `altastata.mcp.account-dir` | Account directory with `*user.properties` |
| `ALTASTATA_MCP_PASSWORD` / `altastata.mcp.password` | Unlock password (RSA/PQC). Empty for HSM/HPCS. |

Default policy is **read-only** (`list_files`, `read_file`, …). Enable
`grant_access` / `revoke_access` explicitly in `altastata.mcp.enabled-tools`.

## Cursor / Claude Desktop snippet

```json
{
  "mcpServers": {
    "altastata": {
      "command": "java",
      "args": ["-jar", "/path/to/altastata-services-YYYY.MM.DD-uber.jar", "--mcp-stdio"],
      "env": {
        "ALTASTATA_MCP_ACCOUNT_DIR": "/Users/you/.altastata/accounts/bob",
        "ALTASTATA_MCP_PASSWORD": "…"
      }
    }
  }
}
```

## Status

v1: stdio transport, policy gate, core tools over `AltaStataFileSystem`.
Streamable HTTP, EventBus `MCPToolCallEvent`, and live event resources are
planned follow-ups.
