# AltaStata S3 Gateway — testing

The gateway is a library inside `altastata-services`. There is no standalone
S3 container and no `testuser` / `realuser` shell suite in this tree.

## Ports

- **Web Console / gRPC:** http://127.0.0.1:9877
- **S3:** http://127.0.0.1:9876 (`./gradlew :altastata-services:run`)

## Java unit tests

```bash
./gradlew :altastata-s3-gateway:test
```

## End-to-end (Python wheel)

```bash
./gradlew :altastata-services:run \
  -Daltastata.services.s3gateway.enabled=true \
  -Daltastata.services.grpc.enabled=true
```

```python
from altastata import AltaStataFunctions

f = AltaStataFunctions.from_account_dir(
    "~/.altastata/accounts/amazon.rsa.bob123",
    password="your_password",
)
s3 = f.boto3_s3()
s3.list_objects_v2(Bucket="altastata-bucket", Prefix="Public/")
```

Or log in at http://127.0.0.1:9877 — S3 credentials are issued on login.

## Snowflake S3-compat

See [SNOWFLAKE_S3COMPAT_TESTS.md](../SNOWFLAKE_S3COMPAT_TESTS.md). Bootstrap
credentials with the Python SDK, then run the upstream Maven suite.
