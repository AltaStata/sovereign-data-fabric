# Enterprise mode

AltaStata has **two access-control and sharing models**. Day-to-day file
operations are the same ([HOWTO.md](HOWTO.md)); this page is **who governs
access** and **which crypto / CA features** a commercial license unlocks.

Admin provisioning steps: [ADMIN_TOOL_GUIDE.md](ADMIN_TOOL_GUIDE.md).
Licensing limits: [LICENSE.md](../../LICENSE.md), [LICENSE_FAQ.md](../../LICENSE_FAQ.md).

---

## Who controls a shared file

When two organizations exchange files in the usual way, the sender **loses
control** once a copy leaves their boundary. With AltaStata, whoever puts a
**file** into the fabric **keeps cryptographic control of that file** after
sharing it across organizational boundaries. They **control** which **humans or
applications** — in their own organization or a partner’s — have access.

### Community mode

Whoever **uploads** the file is the **owner**. They share with readers and
control who has access. A reader can open the file and **observe** who has
access, but cannot grant or revoke.

RSA only, up to **5 users** plus **one org custodian**, on the entity’s **own**
cloud or internal storage ([LICENSE_FAQ.md](../../LICENSE_FAQ.md)).

### Enterprise (CISO / security as access manager)

The access manager is **not** the uploader. It is CISO / security, acting
through a **Custodian**. All sharing goes through the Custodian.

CISO can **observe** who has access and **fully manage** it (share / revoke /
delete) — including across partners — **without plaintext**. The Custodian
never decrypts the file.

There is nothing to “transfer” for control: security already governs the file
through the Custodian, whether Bob or Alice uploaded it.

Runtime stamp: `enterprise-custodian-mode=true`. Login needs `license.jwt`
with feature `custodian`. Peer SHARE / DELETE from ordinary users is rejected;
the Custodian routes share, revoke, and delete.

### Example — Bob shares a file with Alice

1. Bob uploads the file to the AltaStata-protected cloud. He is **not** the
   access manager: CISO / security already is, via the Custodian.
2. Bob asks the Custodian to share it with Alice (human or application).
3. The Custodian checks organization policy (automatically, or via an app
   where the CISO approves) and then grants **read** access to Alice.
4. The Custodian can **revoke Bob’s access** while Alice **keeps** access to
   the file — or revoke Alice while Bob keeps it.

### Policies, graph, and compliance audit

Custodian mode is the governance control plane: every share / revoke / delete
goes through it, so the org can enforce **who may share which files with whom**.

AltaStata’s commercial product adds policy evaluation (including from a
**policy graph**), allow/deny of share requests, and a **compliance audit trail**
(GDPR, DORA, AI Act, and similar). The Custodian never sees plaintext — it
decides from signed metadata and events, then grants or refuses.

Typical rules: allow or deny sharing a path (or subtree) between users; keep a
directory shared with a fixed reader set without each pair sharing
file-by-file. You can also run **your own** program as Custodian (the graph can
live on the same fabric, for example JanusGraph on AltaStata HDFS —
[altastata-hadoop](../../altastata-hadoop/README.md)). Evaluation:
`contact@altastata.com`.

---

## What Enterprise unlocks

Community and Enterprise share one source tree. The difference is the
**organization license JWT** and Admin checkboxes
([ADMIN_TOOL_GUIDE.md](ADMIN_TOOL_GUIDE.md#2-community-vs-enterprise)).

| Capability | Community | Enterprise (as granted in the JWT) |
|------------|-----------|--------------------------------------|
| RSA, AES-256-GCM, streams, S3 / gRPC / Hadoop / Python | Yes | Yes |
| Users | ≤5 + one org custodian | JWT `seats` (`0` = unlimited) |
| Certificate signing | AltaStata cloud CA (`POST /sign`), RSA only | **Customer-owned org CA** (`org-ca.pem` + private key). No `/sign` fallback |
| **PQC** (ML-KEM / Kyber, ML-DSA / Dilithium) | No | Feature `pqc` |
| **HSM / IBM HPCS** (private key stays in the HSM) | No | Features `hsm`, `hpcs` |
| **Enterprise Custodian mode** (Custodian is access manager; no plaintext) | Community has a custodian *identity* (oversight), not this mode | Feature `custodian` |
| **Governance product** (policy graph or custom rules, compliance audit) | No | Commercial add-on; `contact@altastata.com` |
| Runtime files beside keys | `*user.properties` | Also `license.jwt` and `org-ca.pem` |

A commercial license can also include **SSO / directory integration** and
**SLA support** — confirm what your JWT and contract actually grant.
`contact@altastata.com`.

Evaluation / PoC without a commercial contract is Community-level (RSA)
unless AltaStata issues a **trial license** for Enterprise features.

---

## Enterprise Admin Tool — network and trust (CISO)

The Admin Tool (`altastata-admin`) is **closed-source** and runs on your
workstation. Banks often ask whether it **phones home** when you enter cloud
admin credentials.

**Summary:** Enterprise Admin talks to the network **only to your cloud**.
Signing uses **your** org CA. AltaStata `POST /sign` is **off**. The “black box”
concern is addressed with egress policy, packet capture on PoC, and optional
source escrow — not by open-sourcing the Admin binary. The runtime SDK is
source-available BSL; the Admin binary is not.

### Network egress (Enterprise mode)

| Destination | Used? | What goes there |
|-------------|-------|-----------------|
| **Your cloud APIs** (endpoints you configure) | **Yes** | IAM, buckets, policies — credentials **you** enter |
| **AltaStata cloud `POST /sign`** | **No** | Local org CA only. Missing key → error, no fallback |
| **AltaStata servers / telemetry** | **No** | No update check or analytics |
| **`license.jwt` verification** | Local | Embedded AltaStata issuer public key — not HTTP |

Turn on **“Use organization license and local CA”** with
`licenses/altastata-{org}.jwt` on disk
([ADMIN_TOOL_GUIDE.md](ADMIN_TOOL_GUIDE.md#2-community-vs-enterprise)).

Admin does **not** send lake plaintext, user private keys, or the org CA
private key. Generated `*user.properties` stay under `~/.altastata/admin/`
until you distribute them. What leaves the bank in normal Enterprise
provisioning is API calls to **your** cloud only — without them, Admin cannot
create IAM roles and storage.

Typical due diligence: jump host, egress only to your cloud, packet capture
on PoC, contract for no vendor telemetry, optional Admin source escrow under
NDA. Runtime crypto (Services JAR, core SDK) is **source-available** under BSL
for white-box review separately from the Admin binary.

Community mode may call AltaStata `POST /sign` with `organization`,
`userName`, `email`, and `publicKeyPEM` only — still **not** cloud admin
credentials.

---

## Files on each account

After the admin provisions the fabric, each runtime folder
`~/.altastata/accounts/<name>/` needs:

- keys and `*user.properties` (same as Community)
- **`license.jwt`** and **`org-ca.pem`** for Enterprise / eval

Keep the **org CA private key** only on the Admin machine and the
**custodian** account — not on ordinary end-user folders.

Python clients: pass those files with `AltaStataFunctions.from_upload` — see
the [Python USER_SETUP_GUIDE](https://github.com/AltaStata/altastata-python-package/blob/main/docs/guides/USER_SETUP_GUIDE.md)
and [Python ENTERPRISE.md](https://github.com/AltaStata/altastata-python-package/blob/main/docs/guides/ENTERPRISE.md).
