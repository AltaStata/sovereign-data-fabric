# Uber JARs

How to use the published Java artifacts from
[GitHub Releases](https://github.com/AltaStata/sovereign-data-fabric/releases).
You do **not** need to build from source. **Java 17 or later** is required
(`UnsupportedClassVersionError` on JDK 11).

These two JARs are different products. Do not put the Services uber JAR on a
Spark classpath, and do not try to run the Hadoop JAR as a server.

| Artifact | What it is | Typical use |
|----------|------------|-------------|
| `altastata-hadoop-YYYY.MM.DD-uber.jar` | Shaded Hadoop `FileSystem` | Spark, Databricks, Hive, Flink, HBase (`altastata://…`) |
| `altastata-services-YYYY.MM.DD-uber.jar` | Standalone JVM you run | S3 gateway, gRPC, Web Console, MCP |
| `altastata-services-YYYY.MM.DD-uber.zip` | Services JAR + signed Bouncy Castle `lib/` | Same as the JAR; keep `lib/` next to it |

Current release example: [v2026.08.19](https://github.com/AltaStata/sovereign-data-fabric/releases/tag/v2026.08.19).

Bouncy Castle stays **signed** (JCE). Use these three official Maven artifacts
(`org.bouncycastle:bcprov-jdk18on:1.85` and the matching `bcpkix` / `bcutil`)
— do not shade or re-jar them; JCE checks the signature:

- `bcprov-jdk18on-1.85.jar`
- `bcpkix-jdk18on-1.85.jar`
- `bcutil-jdk18on-1.85.jar`

They ship in `altastata-services-YYYY.MM.DD-uber.zip` (`lib/`) and after
`pip install altastata` under `…/site-packages/altastata/lib/`.

The `altastata` **pip wheel does not include** the Hadoop JAR. It does bundle a
Services JAR for local Python / Jupyter. Spark and Databricks still need the
Hadoop JAR (and Bouncy Castle) on the **cluster** classpath.

---

## 1. Hadoop — Spark / Databricks

Jobs read and write encrypted paths (`altastata://…` or a configured Hadoop
URI) through this filesystem JAR.

### Download

From the same release: `altastata-hadoop-YYYY.MM.DD-uber.jar` plus the three
Bouncy Castle JARs above.

### Classpath

Put **all four** files on the Spark driver and executor classpath:

```text
spark.driver.extraClassPath    /path/altastata-hadoop-YYYY.MM.DD-uber.jar:/path/bcprov-jdk18on-1.85.jar:/path/bcpkix-jdk18on-1.85.jar:/path/bcutil-jdk18on-1.85.jar
spark.executor.extraClassPath  (same)
```

On Databricks, upload the four JARs to the cluster (Libraries, init script, or
`/databricks/jars`) so every executor sees them. Step-by-step:
[altastata-hadoop/databricks/README.md](../../altastata-hadoop/databricks/README.md).
A Python notebook can still `pip install altastata` for account setup; the
**job** itself needs Hadoop + Bouncy Castle on the Spark classpath separately.

GCP Dataproc walkthrough (init actions, `extraClassPath`):
[altastata-hadoop/dataproc/GCP_DATAPROC_SETUP.md](../../altastata-hadoop/dataproc/GCP_DATAPROC_SETUP.md).

### Account

The Spark process needs an AltaStata account (`*user.properties` and keys).
Create it with the Desktop UI ([USER_SETUP_GUIDE.md](USER_SETUP_GUIDE.md)); the
`altastata account create` CLI is documented in the
[Python package](https://github.com/AltaStata/altastata-python-package).
Point the job at that account directory.

---

## 2. Services — S3 gateway, gRPC, Console, MCP

One JVM you start. It is **not** a Hadoop connector.

Bouncy Castle is **not** inside the Services uber JAR (the JARs stay signed
for JCE). You need the same three files as for Hadoop, in a `lib/` directory
next to the Services JAR.

Prefer `altastata-services-YYYY.MM.DD-uber.zip` from the release — it already has
the JAR plus `lib/`. If you only downloaded the `.jar`, copy those three files
from the zip’s `lib/`, or from `…/site-packages/altastata/lib/` after
`pip install altastata`.

| Port | Service |
|------|---------|
| `9876` | S3-compatible gateway (boto3, AWS CLI, Snowflake, …) |
| `9877` | gRPC + Web Console (`http://127.0.0.1:9877`) |
| stdio | MCP (`--mcp-stdio`; off by default) |

### Run from the release zip

```bash
unzip altastata-services-YYYY.MM.DD-uber.zip
cd <extracted-dir>          # must contain the jar and lib/bc*.jar
java -jar altastata-services-YYYY.MM.DD-uber.jar
```

The manifest `Class-Path` is `lib/bc*.jar`. Run from the directory that
contains both the JAR and `lib/`, or JCE will reject Bouncy Castle.

Enable S3 (gRPC is on by default):

```bash
java -Daltastata.services.s3gateway.enabled=true \
     -jar altastata-services-YYYY.MM.DD-uber.jar
```

MCP stdio (Claude Desktop / Cursor):

```bash
java -jar altastata-services-YYYY.MM.DD-uber.jar --mcp-stdio
```

See [altastata-mcp/README.md](../../altastata-mcp/README.md).

### Further reading

- S3 clients and auth: [altastata-s3-gateway/README.md](../../altastata-s3-gateway/README.md)
- gRPC / Console: [altastata-grpc/README.md](../../altastata-grpc/README.md)
- MCP (Claude Desktop / Cursor): [altastata-mcp/README.md](../../altastata-mcp/README.md)
- Build from source instead: [DEVELOPERS_GUIDE.md](DEVELOPERS_GUIDE.md)

---

## 3. What not to mix

| Do | Don't |
|----|--------|
| Hadoop uber JAR + BC on Spark classpath | Services uber JAR on Spark classpath |
| `java -jar` Services with sibling `lib/` | Relocate the Services JAR without `lib/` |
| `pip install altastata` for notebooks / account CLI | Expect `pip` to install the Hadoop connector |
