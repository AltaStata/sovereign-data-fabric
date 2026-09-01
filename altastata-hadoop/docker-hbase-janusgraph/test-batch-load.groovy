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

// Batch-loading throughput test (storage.batch-loading=true)

println "=========================================================="
println "  JanusGraph Batch-Loading Throughput Test"
println "=========================================================="

startTime = System.currentTimeMillis()

println "\n[1/3] Opening graph with storage.batch-loading=true..."
graph = JanusGraphFactory.open('conf/janusgraph-hbase-batch.properties')
g = graph.traversal()
println "Opened in " + (System.currentTimeMillis() - startTime) + " ms"

NUM_VERTICES = 500
BATCH_SIZE = 100
BASE_ID = 20000

println "\n[2/3] Inserting " + NUM_VERTICES + " vertices (batch size " + BATCH_SIZE + ")..."
cities = ["New York", "San Francisco", "Austin", "London", "Tokyo", "Berlin", "Toronto", "Sydney"]
depts = ["Engineering", "Product", "Sales", "Research", "Operations", "Finance"]

loadStart = System.currentTimeMillis()
for (i = 1; i <= NUM_VERTICES; i++) {
    pId = BASE_ID + i
    g.addV('person')
        .property('personId', pId)
        .property('name', 'BatchUser_' + pId)
        .property('age', 20 + (i % 45))
        .property('city', cities[i % cities.size()])
        .property('dept', depts[i % depts.size()])
        .iterate()

    if (i % BATCH_SIZE == 0) {
        graph.tx().commit()
        print "."
    }
}
graph.tx().commit()
vTime = System.currentTimeMillis() - loadStart
rate = NUM_VERTICES * 1000.0 / vTime
println "\nVertices created: " + NUM_VERTICES + " in " + vTime + " ms (" + String.format("%.2f", rate) + " vertices/sec)"

println "\n[3/3] Closing graph..."
graph.close()

println "=========================================================="
println "  Batch test complete in " + String.format("%.2f", (System.currentTimeMillis() - startTime) / 1000.0) + " s"
println "=========================================================="
