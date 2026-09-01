# Performance Tuning: HBase & JanusGraph on AltaStata HDFS

Running HBase + JanusGraph on AltaStata (encrypted object storage behind a Hadoop `FileSystem`) needs a few deliberate tunings. Without them, every WAL sync or directory probe can turn into a high-latency cloud round-trip.

## What we changed in this stack

### 1. Local HBase WALs
- **Config:** `hbase.wal.dir=file:///tmp/hbase-wals` (HFiles stay on `altastata:///...`)
- **Why:** WAL durability must be fast; object-store sync on every put is too slow.
- **Effect:** Steady-state writes stay in MemStore + local WAL; cloud is hit mainly on MemStore flush (HFile write).

### 2. JanusGraph `ASYNC_WAL`
- **Config:** `storage.hbase.ext.hbase.client.durability=ASYNC_WAL`
- **Why:** Default sync durability amplifies WAL cost even with local disks.
- **Effect:** Commits acknowledge without waiting for every sync; fine for this demo / read-heavy graph use.

### 3. AltaStata in-memory directory cache
- **Code:** `createdDirectories` in `AltaStataHadoopFileSystem`
- **Why:** Object storage has no real empty directories; HBase expects POSIX `mkdirs` / `getFileStatus`.
- **Effect:** HBase Master starts reliably; fewer cloud `list` calls for virtual dirs.

### 4. Gremlin Server exposed on the host
- **Compose:** JanusGraph runs `entrypoint-janusgraph.sh` (waits for ZK, then starts server)
- **Network:** `network_mode: service:hbase` so `localhost:8182` on the host maps to the server
- **Verify:** `./test-remote-gremlin.sh`

### 5. Write-path / cache tunings
In `hbase-site.xml`:
- larger MemStore flush size (`256MB`)
- larger block cache / memstore fractions
- larger client write buffer
- longer `optionalcacheflushinterval` (delay periodic flushes to AltaStata)
- major compaction timer disabled for the test stack

In `janusgraph-hbase.properties`:
- larger `cache.db-cache-size`
- `storage.buffer-size` / `storage.write-time`
- larger `ids.block-size` (fewer ID-authority round-trips)
- `hbase.client.write.buffer=8MB`

### 6. Cap the Gremlin Server heap
- **Config:** `JAVA_OPTIONS=-Xms512m -Xmx1536m` on the `janusgraph` service
- **Why:** The image’s `conf/jvm-11.options` is `-Xms4096m -Xmx4096m` plus
  `AlwaysPreTouch` (~4.6 GiB RSS). A typical Docker Desktop VM has ~8 GiB for all
  containers; HBase is already ~0.6 GiB. Starting `gremlin.sh -Xmx512m` in
  the same container (the `./test-*.sh` scripts) then OOM-kills JanusGraph
  (exit 137). `janusgraph-server.sh` appends `JAVA_OPTIONS` after the file,
  and the last `-Xmx` wins. Console scripts still pass
  `JAVA_OPTIONS=-Xms64m -Xmx512m` on `docker exec`.

### 7. Optional batch-loading profile
- **File:** `config/janusgraph-hbase-batch.properties` (`storage.batch-loading=true`)
- **Use:** bulk insert scripts only — not the OLTP Gremlin Server config
- Both JanusGraph property profiles are tracked release files.

## What the benchmarks showed

Early smoke numbers (~4 v/s vertex insert) looked bad because they mixed:
1. cold start / first commits
2. occasional `MemStoreFlusher` HFile uploads to AltaStata (e.g. `hbase:meta` flush ~11s)
3. measuring via a fresh `gremlin.sh` JVM + new WebSocket client each run

After isolating the **hot path** (server already up, tiny warmup commit, then batches):

| Path | Observed |
|---|---|
| Hot batch insert (100 vertices) | ~155–220 ms → **~450–650 v/s** |
| Hot load 500 vertices after warmup | **~1.2 s (~420 v/s)** |
| Tiny commit after client is connected | **~20–30 ms** |
| First tiny commit of a new client session | ~200–400 ms (client/pool setup, not AltaStata) |
| Cold / flush-contended window | can drop to single-digit–low tens v/s |

HBase **does** cache: MemStore for recent writes, BlockCache for HFile reads. Steady-state OLTP/graph queries are fine. Spikes happen when flusher writes HFiles to AltaStata.

## Practical guidance

1. Prefer a **long-lived Gremlin client / console session** for analysis (`localhost:8182`). Avoid `gremlin.sh -e` per query (new JVM each time).
2. For loaders: do a **tiny warmup commit**, then large batches (`./test-warmup-then-load.sh`).
3. For very large imports: CSV/HFile bulk load beats Gremlin `addV()`; optionally create unique indexes after load.
4. Local WAL is for speed — back up / accept loss of unsynced WAL on container wipe if you need stronger durability stories.

## How to reproduce

```bash
cd altastata-hadoop/docker-hbase-janusgraph
cp .env.example .env
# edit ALTASTATA_ACCOUNT_DIR, ALTASTATA_PASSWORD, and HBASE_ROOTDIR
docker compose up -d --build
./test-remote-gremlin.sh          # port + remote count
./test-warmup-then-load.sh        # warmup + hot 500-vertex load
./test-large-graph.sh             # schema + vertices/edges + traversals
./test-batch-load.sh              # batch-loading properties profile
```
