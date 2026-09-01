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
echo "Checking Gremlin Server on localhost:8182 from host..."
python3 - <<'PY'
import socket, sys
s = socket.socket(); s.settimeout(3)
try:
    s.connect(("127.0.0.1", 8182))
    print("HOST OK: localhost:8182 is open")
except Exception as e:
    print("HOST FAIL: localhost:8182 not reachable:", e)
    sys.exit(1)
finally:
    s.close()
PY

echo "Running warmup + hot-path load via Gremlin Server..."
# Cap the console heap: it runs beside the Gremlin Server in the same container.
docker cp test-warmup-then-load.groovy janusgraph:/opt/janusgraph/test-warmup-then-load.groovy
docker exec -e JAVA_OPTIONS="-Xms64m -Xmx512m" janusgraph bin/gremlin.sh -e test-warmup-then-load.groovy
