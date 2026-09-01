# AltaStata Hadoop FileSystem

A Hadoop `FileSystem` implementation
(`org.apache.hadoop.fs.altastata.AltaStataHadoopFileSystem`) that serves the
`altastata://` scheme, so any engine speaking the Hadoop filesystem API can
read and write AltaStata storage with per-file encryption and decryption
happening in the job's own JVM.

Exercised in this repository: **Spark** (including Databricks and GCP
Dataproc), **HBase**, and **JanusGraph** on top of HBase — see the guides
below. Other Hadoop-API engines such as Hive, Flink, or Apache Phoenix use the
same `fs.altastata.impl` registration, but this module carries no setup guide
for them.

This module is a **library**, not a service. You put its shaded JAR on a
cluster classpath.

## Get the JAR

Download `altastata-hadoop-YYYY.MM.DD-uber.jar` from
[Releases](https://github.com/AltaStata/sovereign-data-fabric/releases), or
build it:

```bash
./gradlew :altastata-hadoop:clean :altastata-hadoop:shadowJar \
  -PexcludeBouncyCastle=true
# altastata-hadoop/build/libs/altastata-hadoop-<version>-uber.jar
```

Three build flags trim the result for specific targets:
`-PminimalBuild=true`, `-PdataprocBuild=true`, and `-PexcludeBouncyCastle=true`
(what each drops: [README-JAR-OPTIMIZATION.md](README-JAR-OPTIMIZATION.md)).

The build above excludes Bouncy Castle because the JAR needs the three
**signed** Bouncy Castle JARs on the same classpath — JCE rejects re-packed
ones. A plain `shadowJar` without `-PexcludeBouncyCastle=true` embeds unsigned
copies and is not a usable release artifact. Which files, and how to place
them on Spark and Databricks: [UBER_JARS.md](../docs/guides/UBER_JARS.md).

Guava, Apache Commons, and Protobuf are relocated under `altastata.shaded.*` so
the JAR can coexist with a cluster's own versions.

## Configure

Set these in `core-site.xml`, `hbase-site.xml`, or as `spark.hadoop.*`
properties:

| Property | Purpose |
|----------|---------|
| `fs.altastata.impl` | `org.apache.hadoop.fs.altastata.AltaStataHadoopFileSystem` — registers the scheme |
| `altastata.account.home` | Account **directory**; the `*.user.properties` inside it is discovered. Takes precedence over the next one |
| `altastata.account.properties` | Path to a single `*.user.properties` file |
| `altastata.account.password` | Passphrase for the private key. Required for RSA and PQC accounts; HSM/HPCS accounts do not use it |
| `altastata.account.encryptedprivatekey` | PEM of the encrypted private key, when the key is supplied inline rather than from the account directory |

Hadoop expands `${env.VAR}` in these values, which is how you keep a passphrase
out of a config file that lives in git:

```xml
<property>
  <name>altastata.account.password</name>
  <value>${env.ALTASTATA_PASSWORD}</value>
</property>
```

Create the account itself first — see
[USER_SETUP_GUIDE.md](../docs/guides/USER_SETUP_GUIDE.md).

### Spark example

```bash
spark-submit \
  --conf spark.hadoop.fs.altastata.impl=org.apache.hadoop.fs.altastata.AltaStataHadoopFileSystem \
  --conf spark.hadoop.altastata.account.home=/opt/altastata/account \
  --conf spark.hadoop.altastata.account.password='***' \
  --conf spark.driver.extraClassPath=/opt/jars/altastata-hadoop-uber.jar:/opt/jars/bcprov-jdk18on-1.85.jar:/opt/jars/bcpkix-jdk18on-1.85.jar:/opt/jars/bcutil-jdk18on-1.85.jar \
  --conf spark.executor.extraClassPath=/opt/jars/altastata-hadoop-uber.jar:/opt/jars/bcprov-jdk18on-1.85.jar:/opt/jars/bcpkix-jdk18on-1.85.jar:/opt/jars/bcutil-jdk18on-1.85.jar \
  your-job.py
```

Then read and write ordinary paths: `altastata:///warehouse/events`.

## What it implements

`create`, `createNonRecursive`, `append`, `open`, `rename`, `delete`, `mkdirs`,
`listStatus`, `getFileStatus`, `isFile` / `isDirectory`, `setAcl`, and the
working-directory and block-size accessors.

Input streams are `Seekable`, `PositionedReadable`, and `ByteBufferReadable`, so
split-based readers (Parquet, ORC) work normally.

Output streams implement Hadoop `Syncable` and `StreamCapabilities` with
`hflush` and `hsync`, which is what makes HBase WALs durable on AltaStata
storage — HBase refuses a filesystem without those capabilities when
`hbase.unsafe.stream.capability.enforce` is on.

AltaStata object storage is flat and creates no zero-byte directory markers, so
the filesystem keeps a JVM-level cache of created directories. HBase depends on
directory existence during initialization and cannot recover from the
`FileNotFoundException` it would otherwise see.

## Where to go next

| Path | What it covers |
|------|----------------|
| [databricks/README.md](databricks/README.md) | Databricks cluster setup and init script |
| [dataproc/GCP_DATAPROC_SETUP.md](dataproc/GCP_DATAPROC_SETUP.md) | GCP Dataproc init actions and classpath |
| [docker-hbase-janusgraph/README.md](docker-hbase-janusgraph/README.md) | Runnable HBase + JanusGraph demo stack on AltaStata storage |
| [docker-hbase-janusgraph/PERFORMANCE_TUNING.md](docker-hbase-janusgraph/PERFORMANCE_TUNING.md) | What we tuned for HBase on cloud storage, and the measurements |
| [SPARK_CONTAINER_UPGRADE.md](SPARK_CONTAINER_UPGRADE.md) | Rebuilding a Spark / Jupyter container against a new JAR |
| [README-JAR-OPTIMIZATION.md](README-JAR-OPTIMIZATION.md) | How the uber JAR is trimmed and why |
| [scripts/INITIALIZATIONS_AND_NETTY.md](scripts/INITIALIZATIONS_AND_NETTY.md) | Netty and initialization pitfalls on shared classpaths |

The `altastata` pip wheel does **not** bundle this JAR. Notebooks can `pip
install altastata` for account setup, but the Spark job still needs the Hadoop
JAR on the cluster classpath.
