# Support

## Start with the docs

| Question | Document |
|----------|----------|
| What is AltaStata, which client do I want? | [README.md](README.md) |
| Upload, download, share, delete | [HOWTO.md](docs/guides/HOWTO.md) |
| Same tasks in Python | [Python HOWTO](https://github.com/AltaStata/altastata-python-package/blob/main/docs/guides/HOWTO.md) |
| Low-level Scala / `CloudFile` API | [Low-level-Scala-API.md](docs/guides/Low-level-Scala-API.md) |
| I need an account and keys | [USER_SETUP_GUIDE.md](docs/guides/USER_SETUP_GUIDE.md) |
| I am the org admin, I need to provision storage | [ADMIN_TOOL_GUIDE.md](docs/guides/ADMIN_TOOL_GUIDE.md) |
| Community vs Enterprise sharing / Custodian / PQC | [ENTERPRISE.md](docs/guides/ENTERPRISE.md) |
| I want to try it without a cloud subscription | [POSIX / LocalFS walkthrough](docs/guides/ADMIN_TOOL_GUIDE.md#33-evaluate-on-local-disk-posix--localfs) |
| Which JAR goes where (Spark, Databricks, servers) | [UBER_JARS.md](docs/guides/UBER_JARS.md) |
| How do I run the S3 gateway / gRPC / Console | [altastata-services/README.md](altastata-services/README.md) |
| S3 clients, credentials, multipart | [altastata-s3-gateway/README.md](altastata-s3-gateway/README.md) |
| Spark / Hadoop / HBase integration | [altastata-hadoop/README.md](altastata-hadoop/README.md) |
| Claude Desktop / Cursor via MCP | [altastata-mcp/README.md](altastata-mcp/README.md) |
| Build from source, run tests | [DEVELOPERS_GUIDE.md](docs/guides/DEVELOPERS_GUIDE.md) |
| How to contribute (code, bug reports, CLA) | [CONTRIBUTING.md](CONTRIBUTING.md) |
| Java / Scala API reference | [API docs](https://altastata.github.io/sovereign-data-fabric/api/javadoc/) |
| What changed in this release | [RELEASE_NOTES.md](RELEASE_NOTES.md) |
| Am I allowed to use this in production? | [LICENSE_FAQ.md](LICENSE_FAQ.md), then [LICENSE.md](LICENSE.md) |

## Where to ask

- **Something is broken** — [open a GitHub issue](https://github.com/AltaStata/sovereign-data-fabric/issues/new)
  with the release tag
  (`vYYYY.MM.DD`) or wheel version, the component, the storage backend, your OS
  and JDK, and the relevant log lines with credentials removed. Log files:
  `~/.altastata/application/logs/` (Desktop UI),
  `~/.altastata/services/logs/` (Python `pip install altastata`, Services JAR).
  Without the tag and the backend we usually cannot reproduce.
- **Security vulnerability** — never in an issue. See [SECURITY.md](SECURITY.md).
- **Licensing, evaluation, commercial terms, Enterprise license JWTs, or an
  Admin Tool question tied to your organization** — `contact@altastata.com`.

Community Tier support is best-effort through issues. Commercial customers
should use the channel named in their agreement, which is not this repository.

## Before you file

Two checks resolve a large share of reports:

- Are you on the [latest release](https://github.com/AltaStata/sovereign-data-fabric/releases/latest)?
  Fixes ship in new `vYYYY.MM.DD` tags and are not backported.
- Do the **signed** Bouncy Castle JARs sit where the process can see them? A
  missing or re-packed `lib/bc*.jar` surfaces as
  `JCE cannot authenticate the provider BC`, and every private-key operation —
  login, sharing, decryption — fails from that one cause. See
  [UBER_JARS.md](docs/guides/UBER_JARS.md).
