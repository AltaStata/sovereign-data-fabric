#!/bin/bash
# Sequential GCP → Azure → AWS (smoke then large).
#
# Hardening vs mid-run repo edits + crashes:
#   1) Re-exec from a /tmp snapshot of THIS file (editing the repo copy cannot EOF-kill the run)
#   2) Checkpoint each completed step → SKIP_FIXTURES / resume skips done work
#   3) Optional watchdog restarts the orchestrator if the pidfile dies before ALL DONE
#
# Usage:
#   ./altastata-examples/scripts/run-all-clouds-performance.sh
#   SKIP_FIXTURES=1 ...          # skip fixture generation (also auto if those steps are checkpointed)
#   PERF_RESET=1 ...             # clear checkpoints and start fresh
#   PERF_NO_WATCHDOG=1 ...       # do not spawn the auto-resume watchdog
#
set -euo pipefail

# --- Snapshot re-exec: running bash must not read a file that agents edit in-place ---
if [[ "${PERF_ALL_CLOUDS_SNAPSHOT:-}" != "1" ]]; then
  SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
  export PERF_ALL_CLOUDS_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  SNAP="/tmp/perf-all-clouds-runner-$$.sh"
  cp "$SELF" "$SNAP"
  chmod +x "$SNAP"
  export PERF_ALL_CLOUDS_SNAPSHOT=1
  exec bash "$SNAP" "$@"
fi

SCRIPT_DIR="${PERF_ALL_CLOUDS_SCRIPT_DIR:?missing PERF_ALL_CLOUDS_SCRIPT_DIR}"
# shellcheck source=perf-common.sh
source "$SCRIPT_DIR/perf-common.sh"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
LOG=/tmp/perf-all-clouds.log
PIDFILE=/tmp/perf-all-clouds.pid
STATE=/tmp/perf-all-clouds.state
WATCHDOG_PIDFILE=/tmp/perf-all-clouds-watchdog.pid
cd "$ROOT_DIR"

if [[ "${PERF_RESET:-}" == "1" ]]; then
  rm -f "$STATE"
  echo "PERF_RESET=1 — cleared $STATE"
fi

if [[ -f "$PIDFILE" ]] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
  echo "Already running pid=$(cat "$PIDFILE") — log: $LOG" >&2
  exit 0
fi
echo $$ >"$PIDFILE"
# Keep STATE across crashes; only clear pidfile on exit.
trap 'rm -f "$PIDFILE"' EXIT

export PERF_COOLDOWN_MS="${PERF_COOLDOWN_MS:-750}"
export AWS_REGION="${AWS_REGION:-us-east-1}"
export AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-us-east-1}"

step_done() {
  local label="$1"
  [[ -f "$STATE" ]] && grep -qx "DONE $label" "$STATE"
}

mark_done() {
  local label="$1"
  mkdir -p "$(dirname "$STATE")"
  # idempotent
  if ! step_done "$label"; then
    echo "DONE $label" >>"$STATE"
  fi
}

run_step() {
  local label="$1"
  shift
  if step_done "$label"; then
    echo ""
    echo "########## $(date) SKIP $label (checkpoint) ##########"
    return 0
  fi
  echo ""
  echo "########## $(date) START $label ##########"
  "$@"
  perf_refresh_live_table
  mark_done "$label"
  echo "########## $(date) DONE $label ##########"
}

# Watchdog: if orchestrator dies before ALL DONE, resume (checkpoints skip finished steps).
start_watchdog() {
  if [[ "${PERF_NO_WATCHDOG:-}" == "1" ]]; then
    return 0
  fi
  if [[ -f "$WATCHDOG_PIDFILE" ]] && kill -0 "$(cat "$WATCHDOG_PIDFILE")" 2>/dev/null; then
    return 0
  fi
  (
    echo $$ >"$WATCHDOG_PIDFILE"
    trap 'rm -f "$WATCHDOG_PIDFILE"' EXIT
    while true; do
      sleep 120
      if [[ -f "$PIDFILE" ]] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
        continue
      fi
      if grep -q "ALL CLOUDS DONE" "$LOG" 2>/dev/null && step_done AWS-LARGE; then
        echo "$(date) watchdog: suite complete — exiting" >>"$LOG"
        exit 0
      fi
      # Dead mid-suite → resume
      echo "$(date) watchdog: orchestrator dead; resuming from checkpoints" >>"$LOG"
      SKIP_FIXTURES=1 PERF_NO_WATCHDOG=1 \
        "$SCRIPT_DIR/run-all-clouds-performance.sh" >>"$LOG" 2>&1 || true
      # If resume script exits (success or fail), loop again unless done
      if step_done AWS-LARGE; then
        exit 0
      fi
    done
  ) >/dev/null 2>&1 &
  disown || true
}

exec > >(tee -a "$LOG") 2>&1
echo "===== $(date) ALL-CLOUDS START (pid=$$ cooldown=${PERF_COOLDOWN_MS}ms state=$STATE) ====="
start_watchdog

# Fixtures: skip if asked OR already checkpointed
if [[ "${SKIP_FIXTURES:-}" == "1" ]]; then
  mark_done FIXTURES-SMOKE
  mark_done FIXTURES-LARGE
fi

run_step FIXTURES-SMOKE ./gradlew :altastata-examples:runExample \
  -PmainClass=com.altastata.performance.utils.TestFileGenerator \
  -PappArgs='smoke force' -PmaxHeap=2g
run_step FIXTURES-LARGE ./gradlew :altastata-examples:runExample \
  -PmainClass=com.altastata.performance.utils.TestFileGenerator \
  -PappArgs='large force' -PmaxHeap=2g

run_step GCP-SMOKE "$SCRIPT_DIR/run-gcp-performance-smoke.sh"
run_step GCP-LARGE "$SCRIPT_DIR/run-gcp-performance-large.sh"

run_step AZURE-SMOKE "$SCRIPT_DIR/run-azure-performance-smoke.sh"
run_step AZURE-LARGE "$SCRIPT_DIR/run-azure-performance-large.sh"

run_step AWS-SMOKE "$SCRIPT_DIR/run-aws-performance-smoke.sh"
run_step AWS-LARGE "$SCRIPT_DIR/run-aws-performance-large.sh"

perf_refresh_live_table
echo "===== $(date) ALL CLOUDS DONE ====="
python3 "$SCRIPT_DIR/summarize-performance-logs.py" --promote 2>/dev/null || \
  python3 "$SCRIPT_DIR/summarize-performance-logs.py" || true
