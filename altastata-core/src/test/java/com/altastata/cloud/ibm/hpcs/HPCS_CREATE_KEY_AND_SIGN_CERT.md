# Create key at HPCS, then sign certificate and import

> **Experiments:** use the **sandbox** account
> [`amazon.rsa.hpcs.hpcsdev`](HPCS_SANDBOX.md). Keygen refuses to overwrite
> existing `public.key` / `hpcs-privkey.blob` unless
> `ALTASTATA_HPCS_ALLOW_PROTECTED_ACCOUNT=true`. See [HPCS_SANDBOX.md](HPCS_SANDBOX.md).

Create the RSA key in HPCS using Java, write the public key (and for GREP11 the
private key blob) to the account dir, then sign the certificate and import it.

## 1. Create the key in HPCS (Java) and write to account dir

From the project root.

**GREP11 sandbox (recommended for Mac dev):**

```bash
export GREP11_YAML=/full/path/to/grep11client.yaml
./altastata-core/scripts/run-hpcs-create-sandbox-key.sh
```

Default sandbox: `~/.altastata/accounts/amazon.rsa.hpcs.hpcsdev`, user/label `hpcsdev`.

**GREP11 (explicit account directory):**

```bash
export GREP11_YAML=/full/path/to/grep11client.yaml
# Required if the directory already has public.key + hpcs-privkey.blob:
# export ALTASTATA_HPCS_ALLOW_PROTECTED_ACCOUNT=true
./gradlew :altastata-core:runHPCSCreateKey \
  -PaccountDir="$HOME/.altastata/accounts/amazon.rsa.hpcs.hpcsdev" \
  -PhpcsUser=hpcsdev
```

Writes: `public.key`, `hpcs-privkey.blob`, `hpcs.marker`. Set `hpcs-priv-key-blob-path` to the blob file for later use.

**PKCS#11 (LinuxONE):** Use `-PaccountDir` with an account that has properties; the task uses `IBMHPCSKeyManager.generateKeyPairInHPCS()` and writes `public.key` and `hpcs.marker` (no blob).

## 2. Sign the certificate

You sign the certificate for this public key with your CA or AltaStata signing service. Use the contents of `public.key` as the subject public key.

## 3. Import the signed certificate into HPCS

After you have the signed certificate (e.g. `cert.pem`), import it so Java’s KeyStore can use the private key:

- From code: `IBMHPCSKeyManager.importCertificateToHPCS(certificatePEM)` (same account/key label).
- Or use `pkcs11-tool` on LinuxONE: same label and id as the key (see README.md in this folder for pkcs11-tool certificate import).

## Account properties

Ensure the account dir has at least:

- `myuser=hpcsdev`
- `hpcs-key-label=hpcsdev` (or omit and it defaults to myuser)
- `key-protection=HPCS`
- **PKCS#11 (LinuxONE):** `hpcs-pkcs11-library` pointing to the .so; optionally `hpcs-user-pin` for API key.
- **GREP11:** `hpcs-yaml-path` (or env `GREP11_YAML`) pointing to `grep11client.yaml` — the YAML contains endpoint, instance ID, and API key; plus `hpcs-priv-key-blob-path` (or `hpcs-privkey.blob` in the account dir) for the private key blob.

After the cert is imported, run `RSAAlgTest` with this account to exercise sign/verify and encrypt/decrypt.

## 4. Verify key with encrypt/decrypt and sign/verify (GREP11)

Standalone test using the key in the account dir (no cert required):

```bash
GREP11_YAML=/path/to/grep11client.yaml ./gradlew :altastata-core:runHPCSGrep11EncryptDecryptSignVerifyTest --no-daemon
```

Adjust `GREP11_YAML` to the IBM-provided `grep11client.yaml` on your machine.

Uses `public.key` and `hpcs-privkey.blob` from `~/.altastata/accounts/amazon.rsa.hpcs.hpcsdev` (or pass account dir as first program arg). Encrypts with the public key (Java), decrypts in HPCS (GREP11); signs in HPCS, verifies with the public key (Java).
