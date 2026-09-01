#!/bin/bash
# Copyright (c) 2026 AltaStata Inc. All rights reserved.
#
# This software is dual-licensed. It is licensed under the Business Source License 1.1
# (BSL) for open use and evaluation, with an eventual transition to the Apache 2.0
# license on the Change Date.
#
# PATENT NOTICE: Protected by US Patent No. 10,693,660.
#
# For the full license text, see the LICENSE.md file in the root of the repository,
# or https://github.com/AltaStata/sovereign-data-fabric/blob/main/LICENSE.md

set -e

echo "Copying test-large-graph.groovy to JanusGraph container and executing benchmark..."

# Cap the console heap: it runs beside the Gremlin Server in the same container.
docker cp test-large-graph.groovy janusgraph:/opt/janusgraph/test-large-graph.groovy
docker exec -e JAVA_OPTIONS="-Xms64m -Xmx512m" janusgraph bin/gremlin.sh -e test-large-graph.groovy
