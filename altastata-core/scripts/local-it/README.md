# Local integration test helpers

Personal dev scripts — **not CI**. Require `~/.altastata` accounts and env vars.

```bash
export ALTASTATA_IT_SOURCE_DIR="/path/to/test/files"
export ALTASTATA_IT_PASSWORD="your-test-password"
./altastata-core/scripts/local-it/run_all_upload_download.sh
```

| Script | Description |
|--------|-------------|
| `run_all_upload_download.sh` | Real-folder upload IT across azure/amazon/google/ibm |
| `run_all_clouds.sh` | Alias |
| `run_three_clouds.sh` | Deprecated alias |
