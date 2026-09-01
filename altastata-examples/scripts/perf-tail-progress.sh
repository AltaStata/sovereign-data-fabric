#!/bin/bash
# Live filtered progress for the all-clouds performance suite.
# Usage:
#   ./altastata-examples/scripts/perf-tail-progress.sh
#   ./altastata-examples/scripts/perf-tail-progress.sh /tmp/perf-all-clouds.log
set -euo pipefail
LOG="${1:-/tmp/perf-all-clouds.log}"
STATE=/tmp/perf-all-clouds.state
PIDFILE=/tmp/perf-all-clouds.pid

echo "=== log: $LOG ==="
if [[ -f "$STATE" ]]; then
  echo "=== checkpoints ==="
  cat "$STATE"
fi
if [[ -f "$PIDFILE" ]] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
  echo "=== orchestrator pid=$(cat "$PIDFILE") alive ==="
else
  echo "=== orchestrator: not running ==="
fi
echo "=== follow (Ctrl-C to stop) ==="
touch "$LOG"
# Strip ANSI / spinner CR noise; keep step markers + run lines + comparisons
tail -n 80 -F "$LOG" 2>/dev/null | sed -u 's/\x1b\[[0-9;]*m//g; s/\r/\n/g' | grep --line-buffered -E \
  '#####|=====.*(START|DONE|RESUME|ALL CLOUDS|watchdog)|=== .*Files Test:|Direct (GCS|Azure|AWS)|AltaStata (Upload|Download)|GCS Run |Azure Run |AWS Run |AltaStata Run |(UPLOAD|DOWNLOAD) COMPARISON|Throughput Ratio|TRIMMED (Duration|Throughput)|warm-up —|BUILD SUCCESS|BUILD FAILED|Profile:|Log:|SKIP '
