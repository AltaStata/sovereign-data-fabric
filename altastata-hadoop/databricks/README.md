# AltaStata on Databricks

Spark on Databricks uses the **Hadoop uber JAR** plus the three signed
Bouncy Castle JARs. Those files are **not** in `pip install altastata`.

Download them from
[GitHub Releases](https://github.com/AltaStata/sovereign-data-fabric/releases)
and follow **[UBER_JARS.md](../../docs/guides/UBER_JARS.md)** (artifact names, versions,
and why `lib/` stays next to Services).

Typical files for a cluster:

```text
altastata-hadoop-YYYY.MM.DD-uber.jar
bcprov-jdk18on-1.85.jar
bcpkix-jdk18on-1.85.jar
bcutil-jdk18on-1.85.jar
```

The BC set is in `altastata-services-YYYY.MM.DD-uber.zip` → `lib/`.

---

## Cluster libraries (preferred)

1. Create or edit the cluster.
2. **Libraries → Install → Upload** (or DBFS / Volume) and add all four JARs.
3. Restart the cluster so every executor sees them.

In a notebook you can still `pip install altastata` for account setup. The
**job** needs the four JARs on the Spark classpath separately.

---

## Optional: init script

If you prefer DBFS + an init script instead of the Libraries UI,
[`init-script.sh`](init-script.sh) copies everything under
`dbfs:/FileStore/altastata-jars/` to `/databricks/jars`.

```bash
# After downloading the four JARs locally:
databricks fs mkdirs dbfs:/FileStore/altastata-jars
databricks fs cp --overwrite altastata-hadoop-YYYY.MM.DD-uber.jar dbfs:/FileStore/altastata-jars/
databricks fs cp --overwrite bcprov-jdk18on-1.85.jar dbfs:/FileStore/altastata-jars/
databricks fs cp --overwrite bcpkix-jdk18on-1.85.jar dbfs:/FileStore/altastata-jars/
databricks fs cp --overwrite bcutil-jdk18on-1.85.jar dbfs:/FileStore/altastata-jars/
```

Upload `init-script.sh` to your workspace and attach it under
**Advanced options → Init scripts**. Then restart the cluster.

To empty that DBFS folder: [`delete_databricks_dbfs_jars.sh`](delete_databricks_dbfs_jars.sh).

---

## Account

The cluster still needs an AltaStata account directory (`*user.properties`
and keys). See [USER_SETUP_GUIDE.md](../../docs/guides/USER_SETUP_GUIDE.md).
