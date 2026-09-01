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

// Large Graph Benchmark for JanusGraph + HBase on AltaStata HDFS

println "=========================================================="
println "  JanusGraph + AltaStata HDFS Large Graph Test & Benchmark"
println "=========================================================="

startTime = System.currentTimeMillis()

// 1. Open Graph
println "\n[1/5] Opening JanusGraph with HBase backend..."
graph = JanusGraphFactory.open('conf/janusgraph-hbase.properties')
g = graph.traversal()

println "Graph opened successfully in " + (System.currentTimeMillis() - startTime) + " ms."

// 2. Define Schema & Indexes
try {
    mgmt = graph.openManagement()
    if (!mgmt.containsVertexLabel('person')) {
        println "[2/5] Creating schema and indexes..."
        person = mgmt.makeVertexLabel('person').make()
        
        personId = mgmt.makePropertyKey('personId').dataType(Integer.class).make()
        name = mgmt.makePropertyKey('name').dataType(String.class).make()
        age = mgmt.makePropertyKey('age').dataType(Integer.class).make()
        city = mgmt.makePropertyKey('city').dataType(String.class).make()
        dept = mgmt.makePropertyKey('dept').dataType(String.class).make()
        weight = mgmt.makePropertyKey('weight').dataType(Double.class).make()
        
        knows = mgmt.makeEdgeLabel('knows').make()
        
        // Build composite index on personId, name, city
        mgmt.buildIndex('byPersonId', Vertex.class).addKey(personId).unique().buildCompositeIndex()
        mgmt.buildIndex('byName', Vertex.class).addKey(name).buildCompositeIndex()
        mgmt.buildIndex('byCity', Vertex.class).addKey(city).buildCompositeIndex()
        
        mgmt.commit()
        println "Schema and indexes created successfully."
    } else {
        println "[2/5] Schema already exists, skipping management configuration."
        mgmt.rollback()
    }
} catch (Exception e) {
    println "Schema configuration note: " + e.getMessage()
}

// 3. Generate Large Dataset
NUM_VERTICES = 500
NUM_EDGES_PER_VERTEX = 3
BATCH_SIZE = 100

println "\n[3/5] Inserting " + NUM_VERTICES + " vertices and ~" + (NUM_VERTICES * NUM_EDGES_PER_VERTEX) + " edges in batch transactions..."

cities = ["New York", "San Francisco", "Austin", "London", "Tokyo", "Berlin", "Toronto", "Sydney"]
depts = ["Engineering", "Product", "Sales", "Research", "Operations", "Finance"]

loadStart = System.currentTimeMillis()
vertexMap = [:] // Map personId -> JanusGraph Vertex ID

// Insert Vertices in Batches
for (i = 1; i <= NUM_VERTICES; i++) {
    pId = i
    pName = "User_" + i
    pAge = 20 + (i % 45)
    pCity = cities[i % cities.size()]
    pDept = depts[i % depts.size()]
    
    v = g.addV('person')
            .property('personId', pId)
            .property('name', pName)
            .property('age', pAge)
            .property('city', pCity)
            .property('dept', pDept)
            .next()
            
    vertexMap[pId] = v
    
    if (i % BATCH_SIZE == 0) {
        graph.tx().commit()
        print "."
    }
}
graph.tx().commit()
vTime = System.currentTimeMillis() - loadStart
println "\nVertices created: " + NUM_VERTICES + " in " + vTime + " ms (" + String.format("%.2f", (NUM_VERTICES * 1000.0 / vTime)) + " vertices/sec)"

// Insert Edges in Batches
edgeStart = System.currentTimeMillis()
edgeCount = 0

for (i = 1; i <= NUM_VERTICES; i++) {
    sourceV = vertexMap[i]
    for (k = 1; k <= NUM_EDGES_PER_VERTEX; k++) {
        targetId = ((i + k * 17) % NUM_VERTICES) + 1
        if (targetId != i) {
            targetV = vertexMap[targetId]
            w = 0.1d * ((i + k) % 10 + 1)
            g.V(sourceV).addE('knows').to(targetV).property('weight', w).iterate()
            edgeCount++
        }
    }
    
    if (i % (BATCH_SIZE / 2) == 0) {
        graph.tx().commit()
        print ":"
    }
}
graph.tx().commit()
eTime = System.currentTimeMillis() - edgeStart
println "\nEdges created: " + edgeCount + " in " + eTime + " ms (" + String.format("%.2f", (edgeCount * 1000.0 / eTime)) + " edges/sec)"

// 4. Run Graph Traversal & Analytical Queries
println "\n[4/5] Running Graph Queries & Traversals..."

// Query 1: Total Counts
q1Start = System.currentTimeMillis()
totalV = g.V().count().next()
totalE = g.E().count().next()
q1Time = System.currentTimeMillis() - q1Start
println "  - Query 1 (Total Counts): " + totalV + " vertices, " + totalE + " edges in " + q1Time + " ms"

// Query 2: Point Lookup by Property
q2Start = System.currentTimeMillis()
targetUser = g.V().has('person', 'personId', 250).valueMap().next()
q2Time = System.currentTimeMillis() - q2Start
println "  - Query 2 (Point Lookup personId=250): " + targetUser + " in " + q2Time + " ms"

// Query 3: Filter & Aggregation (City count)
q3Start = System.currentTimeMillis()
sfCount = g.V().has('person', 'city', 'San Francisco').count().next()
q3Time = System.currentTimeMillis() - q3Start
println "  - Query 3 (Filter city='San Francisco'): found " + sfCount + " users in " + q3Time + " ms"

// Query 4: 1-Hop Traversal (Friends of User_250)
q4Start = System.currentTimeMillis()
friends = g.V().has('person', 'personId', 250).out('knows').values('name').toList()
q4Time = System.currentTimeMillis() - q4Start
println "  - Query 4 (1-Hop Out-Edges for User_250): " + friends + " in " + q4Time + " ms"

// Query 5: 2-Hop Traversal (Friends of Friends)
q5Start = System.currentTimeMillis()
fof = g.V().has('person', 'personId', 250).out('knows').out('knows').values('name').dedup().limit(10).toList()
q5Time = System.currentTimeMillis() - q5Start
println "  - Query 5 (2-Hop Friends-of-Friends limit 10): " + fof + " in " + q5Time + " ms"

// 5. Cleanup
println "\n[5/5] Closing Graph session..."
graph.close()

totalDuration = System.currentTimeMillis() - startTime
println "=========================================================="
println "  Benchmark Complete in " + String.format("%.2f", totalDuration / 1000.0) + " seconds!"
println "=========================================================="
