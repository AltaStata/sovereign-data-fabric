# JanusGraph & Apache HBase on AltaStata HDFS

This environment demonstrates running **JanusGraph** on top of **Apache HBase 2.5.8** using **AltaStata HDFS** (`AltaStataHadoopFileSystem`) as the secure cloud storage backend.

It is a single-node demo stack, not a production topology.

## Architecture

* **Storage Layer**: AltaStata HDFS (`altastata:///`) stores HBase HFiles in encrypted cloud storage.
* **Write-Ahead Logs (WAL)**: HBase WALs go to local disk (`file:///tmp/hbase-wals`) for low-latency writes, while HFiles persist durably in AltaStata cloud storage. That directory is bind-mounted to `./data/hbase-wals` so it survives `docker compose down` — see [Restarting](#restarting-the-stack).
* **Database Layer**: Apache HBase 2.5.8 (Hadoop 3) in standalone mode with an embedded ZooKeeper.
* **Graph DB Layer**: JanusGraph 1.0.0 using HBase as its storage backend with `ASYNC_WAL` enabled for performance.

Both containers share one network namespace (`network_mode: service:hbase`), so JanusGraph reaches HBase on `127.0.0.1` and Gremlin Server is published on the host as `localhost:8182`.

## Prerequisites

1. Docker and Docker Compose.
2. An AltaStata account directory containing your keys and the `*.user.properties`
   file from your org admin (see [USER_SETUP_GUIDE.md](../../docs/guides/USER_SETUP_GUIDE.md)).
3. The shaded Hadoop JAR and the three signed Bouncy Castle JARs next to this
   compose file:
   ```bash
   ./gradlew :altastata-hadoop:shadowJar -PexcludeBouncyCastle=true
   ./gradlew :altastata-services:copyBouncyCastleJars
   cp altastata-hadoop/build/libs/altastata-hadoop-YYYY.MM.DD-uber.jar altastata-hadoop/docker-hbase-janusgraph/
   cp altastata-services/build/libs/lib/bc*-jdk18on-*.jar altastata-hadoop/docker-hbase-janusgraph/
   ```
   Keep exactly one `altastata-hadoop-*-uber.jar` beside the compose file;
   Docker's `COPY` glob fails if several versions match. Rebuild the images
   with `docker compose up -d --build` whenever you replace this JAR.
4. Your settings in `.env`:
   ```bash
   cd altastata-hadoop/docker-hbase-janusgraph
   cp .env.example .env
   # edit ALTASTATA_ACCOUNT_DIR, ALTASTATA_PASSWORD, HBASE_ROOTDIR
   ```
   `.env` is gitignored. Compose refuses to start if `ALTASTATA_ACCOUNT_DIR` or
   `ALTASTATA_PASSWORD` is unset. Compose mounts `ALTASTATA_ACCOUNT_DIR` at
   `/root/.altastata/admin`; `hbase-site.xml` uses that fixed container path
   and reads `ALTASTATA_PASSWORD` / `HBASE_ROOTDIR` through `${env....}`. No
   account name or passphrase is committed to this repo.
5. Python 3 on the host; for the host-side smoke check:
   `pip install gremlinpython`.

## Running the Stack

Run every command in this section from the compose directory:

```bash
cd altastata-hadoop/docker-hbase-janusgraph
```

1. **Start the containers**:
   ```bash
   docker compose up -d --build
   ```

   A cold start is slow: HBase Master creates its layout on cloud storage, so
   `hbase.master.init.timeout` is raised to 10 minutes in `config/hbase-site.xml`.
   JanusGraph does not race it — `entrypoint-janusgraph.sh` waits for ZooKeeper,
   then the Master UI, then for the Master JMX bean to report
   `numRegionServers=1`, and only then starts Gremlin Server. Expect several
   minutes before port 8182 accepts connections.

   Follow progress with:
   ```bash
   docker logs -f hbase        # look for "Master has completed initialization"
   docker logs -f janusgraph   # waits, then "Channel started at port 8182"
   ```

2. **Verify** (host-side, no JVM inside the container):
   ```bash
   ./test-remote-gremlin.sh
   ```

3. **Load / query tests**:
   ```bash
   ./test-gremlin.sh              # small smoke test
   ./test-warmup-then-load.sh     # tiny warmup + hot-path 500-vertex load via :8182
   ./test-large-graph.sh          # schema + vertices/edges + traversals
   ./test-batch-load.sh           # storage.batch-loading=true profile
   ```
   These run `gremlin.sh` inside the JanusGraph container with a capped heap
   (`-Xmx512m`). The Gremlin Server itself is also capped (`JAVA_OPTIONS`
   `-Xmx1536m` in compose): the upstream image defaults to a 4 GiB
   always-pre-touched heap, which OOM-kills the container (exit 137) next to
   HBase on a typical Docker Desktop VM. See `PERFORMANCE_TUNING.md`.

4. **Useful local endpoints**:
   - Gremlin Server: `ws://localhost:8182/gremlin`
   - HBase Master UI: `http://localhost:16010`
   - ZooKeeper: `localhost:2181`

5. **Stop the stack**:
   ```bash
   docker compose down
   ```
   The WAL bind mount remains on disk; read the next section before deleting
   it or changing `HBASE_ROOTDIR`.

## Restarting the stack

HBase must find both halves of its state — HFiles under `HBASE_ROOTDIR` on
AltaStata **and** the local WALs under `./data/hbase-wals`. Losing one while
keeping the other makes the Master abort on the next start (typically
`WAL directory for MasterRegion is missing` or a failed `hbase.version` check).

Run these commands from `altastata-hadoop/docker-hbase-janusgraph`.

**To keep your data**, flush before stopping so the memstore reaches AltaStata.
The `janusgraph` table exists after the graph has been opened at least once
(for example by `./test-gremlin.sh`); skip the flush before that first use:

```bash
docker exec -i hbase /opt/hbase/bin/hbase shell -n <<< "flush 'janusgraph'"
docker compose down
docker compose up -d         # same HBASE_ROOTDIR reopens the same tables
```

`./data/hbase-wals` is a bind mount, so it survives `down` (and `down -v`, which
only drops volumes) — the container filesystem is recreated, the WALs are not.

JanusGraph writes an instance id into the graph and would refuse to reopen it
after an unclean shutdown; `config/janusgraph-hbase*.properties` set
`graph.replace-instance-if-exists=true` so a restarted container takes the lock
back.

**To start clean**, discard both halves together — deleting only one is what
produces the mismatch above:

```bash
docker compose down
rm -rf ./data/hbase-wals
# then set a fresh HBASE_ROOTDIR in .env, e.g. altastata:///hbase_janusgraph_2
docker compose up -d
```
