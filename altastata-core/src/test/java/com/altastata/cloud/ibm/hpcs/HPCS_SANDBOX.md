# HPCS sandbox account (safe experiments)

Use a **separate** account from any shared production folder. Keygen
tools refuse to overwrite existing `public.key` / `hpcs-privkey.blob`
unless you explicitly set `ALTASTATA_HPCS_ALLOW_PROTECTED_ACCOUNT=true`.
It does not delete keys already in HPCS.

## Sandbox defaults

| Item | Value |
|------|--------|
| Account directory | `~/.altastata/accounts/amazon.rsa.hpcs.hpcsdev` |
| HPCS key label / `myuser` | `hpcsdev` |
| Local key files | `public.key`, `hpcs-privkey.blob`, `hpcs.marker` |

GREP11 creates a **new** key in IBM HPCS each run. The real risk is
**overwriting local blobs** that others rely on — hence the guard on
directories that already have key files.

## 1. Create sandbox keys (Mac / GREP11)

```bash
export GREP11_YAML=/path/to/populated/grep11client.yaml
./altastata-core/scripts/run-hpcs-create-sandbox-key.sh
```

Or manually:

```bash
./gradlew :altastata-core:runHPCSCreateKey \
  -PaccountDir="$HOME/.altastata/accounts/amazon.rsa.hpcs.hpcsdev" \
  -PhpcsUser=hpcsdev
```

## 2. Add user properties (from your org admin)

Copy a `*user.properties` into the sandbox dir and set at least:

```properties
myuser=hpcsdev
key-protection=HPCS
hpcs-key-label=hpcsdev
hpcs-yaml-path=/path/to/grep11client.yaml
hpcs-priv-key-blob-path=/path/to/hpcs-privkey.blob
```

Use **your own** cloud prefix / credentials — do not copy another
account’s storage credentials unless you intend to share the same buckets.

## 3. Verify GREP11 sign/decrypt

```bash
GREP11_YAML=/path/to/grep11client.yaml \
  ./gradlew :altastata-core:runHPCSGrep11EncryptDecryptSignVerifyTest \
  -PaccountDir="$HOME/.altastata/accounts/amazon.rsa.hpcs.hpcsdev"
```

## 4. gRPC / Console / JavaFX

- **Console `GenerateKeys`:** use `suggested_display_name` like `amazon.rsa.hpcs.hpcsdev`
- **JavaFX SetupUI:** create account name containing `hpcs` e.g. `amazon.rsa.hpcs.hpcsdev`
- **S3 scripts:** `export ALTASTATA_ACCOUNT_DIR=~/.altastata/accounts/amazon.rsa.hpcs.hpcsdev`

## Related docs

- [HPCS_CREATE_KEY_AND_SIGN_CERT.md](HPCS_CREATE_KEY_AND_SIGN_CERT.md) — key + cert workflow
- [README.md](README.md) — LinuxONE PKCS#11 setup
- gRPC `GenerateKeys` — HPCS keygen path
- `CLAUDE.md` — GREP11 on Mac (`runGrep11CreateKey`)
