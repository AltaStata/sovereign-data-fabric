#!/bin/bash
# Shared helpers for performance benchmark scripts. Source, do not execute.

# Caps JVM heap for the benchmark process (not the Gradle daemon).
# large defaults to 10g (16GB laptops: safer than 12g; 8g OOMed on compressed text).
# Override: PERF_MAX_HEAP=12g ./run-aws-performance-large.sh
perf_max_heap() {
  local profile="${1:-smoke}"
  if [[ -n "${PERF_MAX_HEAP:-}" ]]; then
    echo "$PERF_MAX_HEAP"
    return
  fi
  case "$profile" in
    smoke) echo "4g" ;;
    full|large) echo "10g" ;;
    *) echo "4g" ;;
  esac
}

perf_echo_heap() {
  local profile="$1"
  local heap
  heap="$(perf_max_heap "$profile")"
  echo "JVM heap cap for benchmark: -Xmx${heap} (override: PERF_MAX_HEAP=…)"
}

# Force profile for this script so a leftover PERF_PROFILE=smoke cannot hijack -PappArgs=large.
perf_export_profile() {
  local profile="$1"
  export PERF_PROFILE="$profile"
  echo "PERF_PROFILE=$PERF_PROFILE"
}

# True only if a real benchmark JVM is running (not this bash script's cmdline).
perf_java_running() {
  local main_simple="$1" # e.g. PerformanceTestCombinedGCP
  pgrep -f "com\\.altastata\\.performance\\..*${main_simple}" >/dev/null 2>&1
}

perf_wait_java_gone() {
  local main_simple="$1"
  local label="${2:-$main_simple}"
  while perf_java_running "$main_simple"; do
    echo "$(date '+%F %T') waiting for $label to finish..."
    sleep 30
  done
}

perf_refresh_live_table() {
  local root
  root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
  python3 "$root/altastata-examples/scripts/summarize-performance-logs.py" >/dev/null 2>&1 || true
}
