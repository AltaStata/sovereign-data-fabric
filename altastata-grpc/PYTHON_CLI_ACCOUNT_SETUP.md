# Python CLI / SDK account setup

Create RSA / PQC / HPCS accounts **without** the JavaFX Desktop UI, via:

1. gRPC `AccountSetupService` in this repo
2. The [`altastata` Python package](https://github.com/AltaStata/altastata-python-package)
   (`altastata account create` / `altastata account change-password`)

## Server (this repo)

| Piece | Location |
| --- | --- |
| Proto | `src/main/proto/altastata/grpc/v1/account_setup.proto` |
| Handlers | `altastata-core` → `com.altastata.api.accountsetup.*` |
| RPC impl | `AccountSetupGrpcService` / `GenerateKeysService` |

`GenerateKeys` is a bootstrap RPC (no Bearer session). Local-mode /
`allow-account-setup` policy applies — see `AccountSetupPolicy`.

## Client (Python package)

```bash
altastata help

altastata account create --type rsa --password 'secret' \
  --out ~/.altastata/accounts/amazon.rsa.alice222 --name amazon.rsa.alice222

# Re-encrypt private keys (bootstrap mode, no *user.properties needed)
altastata account change-password \
  --account-dir ~/.altastata/accounts/amazon.rsa.alice222
```

After keygen: admin wraps cloud credentials into `*user.properties`; the client
logs in with `AltaStataFunctions.from_account_dir`.

`ChangePassword` is a **bootstrap** RPC (like `GenerateKeys`): pass
`user_account_directory` + passwords; the gateway re-encrypts key files on disk.
No Login / `*user.properties` required.

When `account_setup.proto` changes, regenerate stubs in
[altastata-python-package](https://github.com/AltaStata/altastata-python-package).
