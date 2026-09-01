# Security Policy

AltaStata protects data with per-file client-side encryption, so we treat
reports about this code as high priority. Thank you for taking the time to
tell us about a problem.

## Reporting a vulnerability

**Do not open a public GitHub issue for a security problem.**

Use either channel:

- **GitHub private vulnerability reporting** — on
  [`AltaStata/sovereign-data-fabric`](https://github.com/AltaStata/sovereign-data-fabric/security/advisories/new)
  (Security → Report a vulnerability). Preferred: it keeps the report, the fix,
  and the advisory in one place.
- **Email** — `contact@altastata.com` with `SECURITY` in the subject.

Please include, as far as you can:

- affected release tag (`vYYYY.MM.DD`) or pip wheel version (`1.0.YYYYMMDD.N`),
- component (`altastata-core`, `altastata-services`, S3 gateway, gRPC, Hadoop
  filesystem, Desktop UI, Admin Tool, MCP server),
- environment (OS, JDK, storage backend: AWS / Azure / GCP / IBM / MinIO / POSIX),
- reproduction steps or a proof of concept,
- the impact you believe it has.

We aim to acknowledge a report within **5 business days** and to agree on a
disclosure timeline with you. Please give us a chance to ship a fix before
publishing details.

## Supported versions

Releases are date-tagged (`vYYYY.MM.DD`). Fixes ship in a **new** date tag; we
do not backport to earlier tags. Older tags stay published because deployed
clients pin to them — always compare against the
[latest release](https://github.com/AltaStata/sovereign-data-fabric/releases/latest)
before reporting.

## Scope

In scope:

- source in this repository (the BSL modules listed in `README.md`),
- artifacts published on the Releases page — the Hadoop and Services uber JARs,
  the Desktop UI and Admin Tool installers,
- the `altastata` pip wheel on PyPI.

Out of scope:

- findings that need a compromised host or an attacker who already has the
  account passphrase and private key material,
- vulnerabilities in third-party dependencies with no exploit path through
  AltaStata — report those upstream, though we do want to hear about it if a
  dependency we ship is exploitable as we use it,
- reports produced only by an automated scanner, with no analysis of impact.

## Handling credentials and keys

Most incidents we see are configuration mistakes rather than code flaws:

- **Never commit credentials** — account directories, `*.user.properties`,
  `private.key`, PQC private keys, `hpcs-privkey.blob`, `license.jwt`, or the
  org CA private key — to version control.
- Private key material stays on the user's machine. Only the **public** key
  goes to the organization admin (see [USER_SETUP_GUIDE.md](docs/guides/USER_SETUP_GUIDE.md)).
- Keep the org CA private key on the Admin host and the custodian account only
  (see [ADMIN_TOOL_GUIDE.md](docs/guides/ADMIN_TOOL_GUIDE.md)).
- Pass passphrases through environment variables or interactive prompts rather
  than command-line arguments, which land in shell history and process lists.
- Use the **signed** Bouncy Castle JARs we ship; JCE rejects re-packed ones
  (see [UBER_JARS.md](docs/guides/UBER_JARS.md)).

## Licensing note

This repository is source-available under the Business Source License 1.1.
Reporting a vulnerability grants no license beyond [LICENSE.md](LICENSE.md).
