/*
 * Copyright (c) 2026 AltaStata Inc. All rights reserved.
 *
 * This software is dual-licensed. It is licensed under the Business Source License 1.1 
 * (BSL) for open use and evaluation, with an eventual transition to the Apache 2.0 
 * license on the Change Date.
 * 
 * PATENT NOTICE: Protected by US Patent No. 10,693,660.
 *
 * For the full license text, see the LICENSE.md file in the root of the repository,
 * or https://github.com/AltaStata/sovereign-data-fabric/blob/main/LICENSE.md
 */

// Explicit tiny warmup commit, then measure hot-path batch load via Gremlin Server

cluster = Cluster.build('127.0.0.1').port(8182).create()
client = cluster.connect()

println "=========================================================="
println "  Warmup + Hot-path Load (remote Gremlin Server :8182)"
println "=========================================================="

// 1) Tiny warmup transaction — pay cold-start cost once
tWarm = System.currentTimeMillis()
client.submit("""
  g.addV('person').property('personId', 990001).property('name', 'warmup').property('city', 'Austin').iterate()
  g.tx().commit()
""").all().get()
warmMs = System.currentTimeMillis() - tWarm
println "[1/2] Warmup commit (1 vertex): " + warmMs + " ms"

// 2) Hot-path load
NUM = 500
BATCH = 100
BASE = 991000
t0 = System.currentTimeMillis()
batchTimes = []
for (b = 0; b < (NUM / BATCH); b++) {
  bt = System.currentTimeMillis()
  client.submit("""
    (${BATCH}).times { i ->
      pid = ${BASE} + ${b}*${BATCH} + (i + 1)
      g.addV('person').property('personId', pid).property('name', 'WU_' + pid).property('age', 25).property('city', 'Tokyo').iterate()
    }
    g.tx().commit()
  """).all().get()
  ms = System.currentTimeMillis() - bt
  batchTimes << ms
  println "  hot batch " + (b + 1) + "/" + (NUM / BATCH) + ": " + ms + " ms (" + String.format("%.1f", BATCH * 1000.0 / ms) + " v/s)"
}
loadMs = System.currentTimeMillis() - t0
hotOnly = batchTimes.sum()
println "[2/2] Hot load: " + NUM + " vertices in " + loadMs + " ms (" + String.format("%.2f", NUM * 1000.0 / loadMs) + " v/s avg incl. scheduling)"
println "       Batches 1..n sum: " + hotOnly + " ms (" + String.format("%.2f", NUM * 1000.0 / hotOnly) + " v/s)"
println "       Slowest batch: " + batchTimes.max() + " ms, fastest: " + batchTimes.min() + " ms"

count = client.submit("g.V().count()").all().get()
println "Total vertices now: " + count

client.close()
cluster.close()
println "=========================================================="
println "Warmup " + warmMs + " ms + hot load " + loadMs + " ms"
println "=========================================================="
