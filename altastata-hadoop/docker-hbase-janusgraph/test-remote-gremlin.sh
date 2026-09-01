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

# Smoke check from the host. Prefer this when you only need a count: it
# starts no second JVM in the container. In-container gremlin.sh is safe
# once compose caps the server heap (see docker-compose.yml JAVA_OPTIONS).
#
# Requires: pip install gremlinpython

set -e

echo "Checking Gremlin Server on localhost:8182..."
python3 - <<'PY'
import socket, sys

s = socket.socket()
s.settimeout(3)
try:
    s.connect(("127.0.0.1", 8182))
except Exception as exc:
    print(f"Gremlin Server is not listening on localhost:8182: {exc}")
    sys.exit(1)
finally:
    s.close()
print("Port 8182 is open.")

try:
    from gremlin_python.driver.client import Client
except ImportError:
    print("gremlinpython is not installed — run: pip install gremlinpython")
    sys.exit(1)

client = Client("ws://127.0.0.1:8182/gremlin", "g")
try:
    count = client.submit("g.V().count()").all().result()[0]
    print(f"Remote g.V().count() => {count}")
finally:
    client.close()
PY

echo "Remote Gremlin Server check OK."
