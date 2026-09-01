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

// Create graph instance
graph = JanusGraphFactory.open('conf/janusgraph-hbase.properties')
g = graph.traversal()

// Add some vertices and edges
v1 = g.addV('person').property('name', 'Alice').next()
v2 = g.addV('person').property('name', 'Patrick').next()
g.V(v1).addE('knows').to(v2).property('weight', 1.0d).iterate()

// Commit transaction
graph.tx().commit()

// Query back
println "Vertices: " + g.V().valueMap().toList()
println "Edges: " + g.E().valueMap().toList()

graph.close()
