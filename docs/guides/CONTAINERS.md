# Example Docker Images (GitHub Packages)

Published images live under the [AltaStata GitHub Packages](https://github.com/orgs/AltaStata/packages) org (`ghcr.io/altastata/...`).

## Version consistency with BSL time-slices

Image tags are intentionally aligned with the same date-based source snapshot used for public BSL exports in [AltaStata/sovereign-data-fabric](https://github.com/AltaStata/sovereign-data-fabric/releases). Each cut is tagged `vYYYY.MM.DD` (for example [v2026.08.19](https://github.com/AltaStata/sovereign-data-fabric/releases/tag/v2026.08.19)).

* For `governance-reference` / `altastata-services` we publish under `:vYYYY.MM.DD` (plus `:latest`).
* For `jupyter-datascience-*` the tag format is `YYYYMMDD.N`; the `YYYYMMDD` part still matches the corresponding `vYYYY.MM.DD` BSL export date.

**Community and Enterprise use the same container pattern:** nothing in the image is tied to a specific customer. You **mount your account directory** (and pass the passphrase when needed). What differs is **what files are in that folder**, not how the container starts.

| Mode | Files in the mounted account folder |
|------|-------------------------------------|
| **Community** | `*user.properties`, keys (`private.key`, …), signed user cert |
| **Enterprise / eval** | Same as Community, plus **`license.jwt`** and **`org-ca.pem`** |

Runtime checks `license.jwt` locally against AltaStata’s embedded issuer key — you cannot substitute a self-signed JWT. See [LICENSE_FAQ.md](../../LICENSE_FAQ.md) and [ENTERPRISE.md](ENTERPRISE.md).

---

## Account directory layout

Typical host path:

```text
~/.altastata/accounts/<name>/
  altastata-{org}-{user}.user.properties
  private.key                    # RSA; PQC/HPCS use their own key files
  license.jwt                    # Enterprise / eval only
  org-ca.pem                     # Enterprise / eval only
```

Provision the folder with the [Admin Tool](ADMIN_TOOL_GUIDE.md) or [USER_SETUP_GUIDE.md](USER_SETUP_GUIDE.md), then mount it into the container.

### Common environment variables

| Variable | Purpose |
|----------|---------|
| `ALTASTATA_ACCOUNT_DIR` | Path **inside the container** to the account folder |
| `ALTASTATA_ACCOUNT_PASSWORD` | Passphrase for RSA/PQC accounts (omit for HSM/HPCS when configured) |
| `ALTASTATA_ACCOUNT_HOST_DIR` | Host path used in compose examples (`docker-compose` bind mount source) |

Example (read-only mount):

```bash
docker run --rm \
  -e ALTASTATA_ACCOUNT_DIR=/home/jovyan/.altastata/accounts/amazon.rsa.bob123 \
  -e ALTASTATA_ACCOUNT_PASSWORD='your-passphrase' \
  -v "$HOME/.altastata/accounts/amazon.rsa.bob123:/home/jovyan/.altastata/accounts/amazon.rsa.bob123:ro" \
  ghcr.io/altastata/jupyter-datascience-amd64:latest
```

Use the **`amd64`** or **`arm64`** image tag that matches your CPU (see [Architecture](#architecture-amd64-vs-arm64) below).

---

## Images

### `jupyter-datascience-amd64` / `jupyter-datascience-arm64`

JupyterLab with the Python `altastata` package and bundled **Services** (S3 `:9876`, gRPC / Web Console `:9877`). Mount your account directory as above; run notebooks against encrypted storage without rebuilding the stack locally.

- Persist notebooks on the host, e.g. `-v ~/jupyter-work:/home/jovyan/work`
- Prefer binding Services ports to loopback only, e.g. `-p 127.0.0.1:9877:9877`

### `altastata-services` (pure Java, published)

Single-process **Services uber JAR** — S3 gateway, gRPC, Web Console. Same account-directory mount; **no Jupyter layer** and **no Python**.

MCP is **OFF by default** in the published image (feature-gated). `Py4J` is also **disabled** (gRPC-only).

### `governance-reference` (Enterprise reference)

Headless **custodian daemon** for Enterprise custodian mode. **Reference implementation only:** it keeps a custodian session alive and **auto-approves every `ADDREADER` share request** — no policy graph, no allow/deny rules, no compliance audit. Suitable for PoC and as a starting point for a custom custodian program; **not** for production governance.

Requires Enterprise custodian account (`enterprise-custodian-mode=true`, valid `license.jwt` with feature `custodian`). Source for this MVP is **not** in the public BSL tree; the **container image** is published on GHCR for Enterprise customers. Build instructions are available from AltaStata on request or in enterprise delivery kits.

### `janusgraph-hbase`

JanusGraph + HBase demo stack on AltaStata HDFS. Mount the account directory via `.env` / compose; see [altastata-hadoop/docker-hbase-janusgraph/README.md](../../altastata-hadoop/docker-hbase-janusgraph/README.md).

---

## Architecture (`amd64` vs `arm64`)

| Host | Image tag |
|------|-----------|
| Intel / AMD Linux, Windows x64, most cloud VMs | `jupyter-datascience-amd64` |
| Apple Silicon (M1/M2/M3), ARM64 Linux | `jupyter-datascience-arm64` |

Licensing and certificate checks are identical on both architectures — only the CPU binary differs. **`s390x` (IBM LinuxONE)** images are built on LinuxONE hardware, not from a generic amd64/arm64 workstation.

---

## Security notes

- Mount account directories **`:ro`** when the container only needs to read keys and properties.
- Never bake `license.jwt`, private keys, or passphrases into an image layer.
- Do not commit account folders to git — see [SECURITY.md](../../SECURITY.md).

## Related

| Document | Contents |
|----------|----------|
| [USER_SETUP_GUIDE.md](USER_SETUP_GUIDE.md) | Create keys and install `*user.properties` |
| [ENTERPRISE.md](ENTERPRISE.md) | `license.jwt`, org CA, Custodian mode |
| [UBER_JARS.md](UBER_JARS.md) | Services and Hadoop JARs without Docker |
| [DEVELOPERS_GUIDE.md](DEVELOPERS_GUIDE.md) | Build from source |
