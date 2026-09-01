# AltaStata licensing FAQ

This summary is for convenience only. **[LICENSE.md](LICENSE.md) controls** in case of any conflict.

AltaStata is **source-available under Business Source License 1.1** (converts to **Apache License 2.0** on **2030-08-17** for this version) — **not** open source today. You may download, inspect, modify, and evaluate the code. See [LICENSE.md](LICENSE.md) for the full text. Each released version has its own Change Date (at most four years after that version’s first public release).

## Free production use

Allowed only if one of these applies:

1. **Evaluation / PoC** — internal use for up to 90 days (Licensor may extend upon request). By default this is Community-level (RSA). Full **Enterprise** capabilities (PQC, HSM, etc.) need a **trial license key** from AltaStata — email `contact@altastata.com`.
2. **Community Tier** — either (i) entity (with affiliates) under **USD $1M** gross annual revenue, or (ii) academic / non-profit for non-commercial research or teaching — with **≤5 users in aggregate** across all deployments plus **one org custodian**, **RSA only**, Community features only, and only on that entity’s **own** cloud accounts or internal storage.

**Commercial license required** for production outside the Community Tier, for cloud/managed-service providers, and for deploying or embedding AltaStata on a **customer, partner, or other third-party** cloud account or storage environment. If you are unsure whether your use qualifies for the Community Tier, please contact AltaStata — `contact@altastata.com`.

What Enterprise *does* (Custodian as access manager, PQC, HSM/HPCS, org CA): **[ENTERPRISE.md](docs/guides/ENTERPRISE.md)**.

## Community FAQ

| Question | Answer |
|----------|--------|
| What counts as a **user**? | A distinct **human user or service/application identity** that authenticates to or uses the Licensed Work (e.g. via a signed account or key). Both count toward the limit of 5. |
| Does a **service account** or **CI job** count? | **Yes**, if it has its own identity/key that uses AltaStata. |
| Does an org **custodian** count toward the 5? | **No.** A custodian is a distinct account role that AltaStata **technically restricts** from decrypting file contents (oversight may hold copies of per-file encryption keys for governance). This is enforced by access control, not just a naming convention. By convention such accounts are named with a `custodian` username suffix for auditability. One such identity does not count toward the 5-user limit. |
| Is the limit **per datalake** / org name? | **No.** The 5 users (+ one custodian) apply to the entity and its affiliates **in aggregate**, across all organizations and deployments. |
| Do universities get unlimited free use? | **No.** Academic / non-profit research or teaching qualifies for Community, but the same **5 users + one custodian**, RSA-only, and own-infrastructure limits apply. |
| What if we cross **$1M** mid-year? | For the revenue path, Community looks at gross annual revenue in the **most recent fiscal year**. Once you no longer qualify, continued production use requires a **commercial license**. |
| Can a **consultant** use Community while deploying for a client? | **No.** Community covers only the entity’s **own** cloud accounts / internal storage — not customer, partner, or other third-party environments. |
| Does making my own CA or `license.jwt` expand Community rights? | **No.** The Community grant in [LICENSE.md](LICENSE.md) is a **user / revenue / deployment** limit, not a description of the signing service. Forging `license.jwt`, substituting your own CA, or removing license checks does not expand those rights. Official builds still verify certificates and licenses against AltaStata’s issuer. |
