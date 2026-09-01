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

port_open() {
  local host=$1 port=$2
  (echo >/dev/tcp/${host}/${port}) >/dev/null 2>&1
}

# Master UI is up before finishActiveMasterInitialization. JMX numRegionServers=1
# means the embedded RS has reported in — closer to "do not PleaseHold".
master_has_live_rs() {
  local body
  body=$(
    exec 3<>/dev/tcp/127.0.0.1/16010 || exit 1
    printf 'GET /jmx?qry=Hadoop:service=HBase,name=Master,sub=Server HTTP/1.0\r\nHost: 127.0.0.1\r\n\r\n' >&3
    cat <&3
    exec 3>&-
  ) || return 1
  echo "$body" | grep -Eq '"numRegionServers"[[:space:]]*:[[:space:]]*1'
}

echo "Waiting for HBase ZooKeeper on 127.0.0.1:2181..."
for i in $(seq 1 90); do
  if port_open 127.0.0.1 2181; then
    echo "ZooKeeper is up."
    break
  fi
  sleep 2
done

echo "Waiting for HBase Master UI on 127.0.0.1:16010..."
for i in $(seq 1 180); do
  if port_open 127.0.0.1 16010; then
    echo "Master UI is up."
    break
  fi
  sleep 2
done

echo "Waiting for a live RegionServer (Master finished enough to accept clients)..."
for i in $(seq 1 180); do
  if master_has_live_rs; then
    echo "HBase Master reports numRegionServers=1."
    exec /opt/janusgraph/bin/janusgraph-server.sh /opt/janusgraph/conf/janusgraph-server.yaml
  fi
  sleep 2
done

echo "Timed out waiting for HBase Master to report a live RegionServer." >&2
exit 1
