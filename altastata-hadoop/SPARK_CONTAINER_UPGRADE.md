# Upgrading the Spark Container

When upgrading a Spark container (e.g., Databricks or a custom Spark cluster) to use AltaStata with PyTorch, run the following commands **inside the container** (or as part of your image build):

## Rebuilding altastata-hadoop for Jupyter-notebook image

To recreate the Hadoop JAR and dependencies used in the Jupyter-notebook image (e.g. for pip-installed altastata or Spark classpath), from the repository root run:

```bash
./gradlew :altastata-core:build :altastata-hadoop:clean :altastata-hadoop:build :altastata-hadoop:shadowJar :altastata-hadoop:copyDeps -PexcludeBouncyCastle=true -PminimalBuild=true
```

Outputs:
- **Shadow JAR:** `altastata-hadoop/build/libs/altastata-hadoop-YYYY.MM.DD-uber.jar`
- **Dependencies:** `altastata-hadoop/build/libs_dependency/`

## Commands

```bash
pip install altastata
pip uninstall torch torchvision torchaudio -y
pip install torch torchvision torchaudio==2.5.1
```

## Why

1. **`pip install altastata`** — Installs or upgrades the AltaStata Python package for cloud storage access.
2. **`pip uninstall torch torchvision torchaudio -y`** — Removes the existing PyTorch stack, which may be broken or incompatible.
3. **`pip install torch torchvision torchaudio==2.5.1`** — Reinstalls a known-good PyTorch 2.5.1 release. Newer versions can have internal import errors (e.g., missing `torch._utils`, `StreamContextVariable`), so pinning to 2.5.1 avoids those issues.

## Order matters

Run them in the order shown. Uninstalling PyTorch before reinstalling ensures a clean slate; installing AltaStata first keeps any existing PyTorch until you explicitly replace it.

## Setting up a Jupyter token

To list running Jupyter servers and see the access URL (including the token), run from the **host**:

```bash
docker exec jupyter jupyter server list
```

The output shows URLs like `http://localhost:8888/?token=...`. Copy the token from the URL to authenticate. If no token is shown, generate one by running inside the container:

```bash
docker exec -it jupyter jupyter server password
```

You'll be prompted to set a password; Jupyter will use it for login.
