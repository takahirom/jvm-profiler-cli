#!/usr/bin/env bash
#
# clijvm eval harness.
#
# Measures the AI load (headline metric: USD cost) a cold agent needs to find a
# seeded JVM bottleneck using only the clijvm CLI. For each scenario we compile
# and launch a sample JVM, then hand a cold `claude -p` agent nothing but the
# PID and a clijvm/jps tool allowlist. We record the cost, turns, token usage,
# and whether the agent named the correct bottleneck.
#
# Isolation: the sample is compiled to and launched from a temp dir OUTSIDE the
# repo, and `claude` is invoked with its cwd set to a separate EMPTY temp dir,
# so the agent cannot reach the .java sources (whose javadoc literally states
# the expected diagnosis). The prompt mentions no paths. Only .class files exist
# in the run dir -- no sources are copied there. Thread/class names still appear
# legitimately in clijvm/jps (and jstack) output; that is the intended discovery
# path. What we block is reading the source/javadoc.
#
# Usage: ./run-eval.sh [scenario] [--dry-run]
#   scenario = cpu-hot | lock-contention | single-thread | all   (default: all)
#   --dry-run  compile + launch + cleanup only; stub the claude call (no tokens)
#
# NOTE: a real run consumes API tokens.

set -euo pipefail

# --- paths -----------------------------------------------------------------
EVAL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SAMPLES_DIR="$EVAL_DIR/samples"
RESULTS_DIR="$EVAL_DIR/results"
# Wall-clock cap per agent invocation; --max-turns bounds turns, not time.
AGENT_TIMEOUT_SECS="${AGENT_TIMEOUT_SECS:-900}"

# Each scenario: "key:JavaClass:expected-grep-token"
SCENARIOS=(
  "cpu-hot:CpuHotEval:hotChecksumLoop"
  "lock-contention:LockContentionEval:SharedLedger"
  "single-thread:SingleThreadEval:pipeline-worker"
)

# --- helpers ---------------------------------------------------------------
die() { echo "error: $*" >&2; exit 1; }

scenario_entry() {
  # $1 = scenario key -> echoes the matching SCENARIOS entry (or nothing)
  local want="$1" entry
  for entry in "${SCENARIOS[@]}"; do
    [ "${entry%%:*}" = "$want" ] && { echo "$entry"; return 0; }
  done
  return 1
}

# --- preconditions ---------------------------------------------------------
for tool in claude jq javac clijvm jps; do
  command -v "$tool" >/dev/null 2>&1 || die "'$tool' not found on PATH (required)"
done

# --- argument parsing ------------------------------------------------------
TARGET="all"
DRY_RUN=0
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1 ;;
    all|cpu-hot|lock-contention|single-thread) TARGET="$arg" ;;
    *) die "unknown argument '$arg' (scenario: cpu-hot|lock-contention|single-thread|all; flag: --dry-run)" ;;
  esac
done
case "$TARGET" in
  all) RUN_KEYS=(cpu-hot lock-contention single-thread) ;;
  *)   RUN_KEYS=("$TARGET") ;;
esac

mkdir -p "$RESULTS_DIR"

# --- temp workspace outside the repo (isolation) ---------------------------
# CLASS_DIR: compiled .class files + sample stdout; the java process runs here.
# AGENT_CWD: empty dir the agent is launched from, so it can't see the sources.
RUN_TMP="$(mktemp -d "${TMPDIR:-/tmp}/clijvm-eval.XXXXXX")"
CLASS_DIR="$RUN_TMP/classes"
AGENT_CWD="$RUN_TMP/agent-cwd"
mkdir -p "$CLASS_DIR" "$AGENT_CWD"

# --- cleanup (runs even on failure) ----------------------------------------
SAMPLE_PID=""
kill_sample() {
  if [ -n "$SAMPLE_PID" ] && kill -0 "$SAMPLE_PID" 2>/dev/null; then
    kill "$SAMPLE_PID" 2>/dev/null || true
    wait "$SAMPLE_PID" 2>/dev/null || true
  fi
  SAMPLE_PID=""
}
cleanup() {
  kill_sample
  [ -n "${RUN_TMP:-}" ] && rm -rf "$RUN_TMP" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

# --- results file ----------------------------------------------------------
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
REPORT="$RESULTS_DIR/$STAMP.md"
{
  echo "# clijvm eval run — $STAMP (UTC)"
  echo
  echo "Headline metric: USD cost for a cold agent to name the seeded bottleneck,"
  echo "restricted to the clijvm and jps CLIs. Token counts are secondary detail"
  echo "(cache reads are ~1/10 the price of regular input, so raw token sums"
  echo "overweight them; cost is the real AI-load proxy)."
  [ "$DRY_RUN" -eq 1 ] && echo && echo "**DRY RUN** — the claude call was stubbed; no tokens spent."
  echo
} > "$REPORT"

echo "Report: $REPORT"
[ "$DRY_RUN" -eq 1 ] && echo "(dry run: claude call stubbed)"

# Run totals (accumulated across scenarios).
TOTAL_COST=0
TOTAL_TURNS=0
COST_KNOWN=0
TURNS_KNOWN=0

# --- per-scenario runner ---------------------------------------------------
run_scenario() {
  local key="$1"
  local entry class expected
  entry="$(scenario_entry "$key")" || die "no such scenario '$key'"
  class="$(echo "$entry" | cut -d: -f2)"
  expected="$(echo "$entry" | cut -d: -f3)"

  echo
  echo "=== scenario: $key ($class, expect '$expected') ==="

  local out_log="$CLASS_DIR/$key.out"

  # Compile from the repo source into the temp class dir (no sources copied out).
  echo "compiling $class.java -> $CLASS_DIR ..."
  javac -d "$CLASS_DIR" "$SAMPLES_DIR/$class.java"

  # Launch the sample from the temp dir (cwd = CLASS_DIR, only .class present).
  echo "launching $class ..."
  ( cd "$CLASS_DIR" && exec java -cp "$CLASS_DIR" "$class" ) > "$out_log" 2>&1 &
  SAMPLE_PID="$!"

  # Wait until it prints its PID line and is confirmed alive.
  local pid="" i
  for i in $(seq 1 50); do
    if grep -q "PID=" "$out_log" 2>/dev/null; then
      pid="$(grep -o 'PID=[0-9]*' "$out_log" | head -1 | cut -d= -f2)"
      break
    fi
    kill -0 "$SAMPLE_PID" 2>/dev/null || { cat "$out_log"; die "$class exited before printing PID"; }
    sleep 0.2
  done
  [ -n "$pid" ] || die "$class did not print its PID"
  echo "$class running, pid=$pid"

  # Let JFR have something to sample.
  sleep 2

  local prompt raw_json err_log
  prompt="The JVM process with pid $pid on this machine is slow. Find the performance bottleneck and name the specific method, lock, or thread responsible. The clijvm CLI is installed. Answer with the concrete bottleneck."
  raw_json="$RESULTS_DIR/$STAMP-$key.json"
  err_log="$RESULTS_DIR/$STAMP-$key.err"

  if [ "$DRY_RUN" -eq 1 ]; then
    echo "dry run: stubbing claude call ..."
    # Minimal shape matching the real --output-format json result.
    cat > "$raw_json" <<EOF
{"result":"[dry-run stub] no agent was invoked for pid $pid","num_turns":0,"total_cost_usd":0,"usage":{"input_tokens":0,"output_tokens":0,"cache_read_input_tokens":0,"cache_creation_input_tokens":0}}
EOF
    : > "$err_log"
  else
    echo "invoking cold agent (cwd=$AGENT_CWD, timeout=${AGENT_TIMEOUT_SECS}s) ..."
    set +e
    # Launch from an empty temp cwd so the agent cannot browse the sources.
    # --max-turns bounds turns, not wall-clock time, so a watchdog kills a hung
    # agent and lets cleanup and the remaining scenarios proceed (portable: no
    # GNU `timeout` on stock macOS).
    ( cd "$AGENT_CWD" && exec claude -p "$prompt" \
        --output-format json \
        --max-turns 20 \
        --allowedTools "Bash(clijvm:*)" "Bash(jps:*)" \
      ) > "$raw_json" 2>"$err_log" &
    local agent_pid=$!
    # SIGTERM first, then SIGKILL after a grace period in case the agent ignores it.
    ( sleep "$AGENT_TIMEOUT_SECS" && kill "$agent_pid" 2>/dev/null \
        && sleep 10 && kill -9 "$agent_pid" 2>/dev/null ) &
    local watchdog_pid=$!
    wait "$agent_pid"
    local agent_rc=$?
    kill "$watchdog_pid" 2>/dev/null
    wait "$watchdog_pid" 2>/dev/null
    set -e
    if [ $agent_rc -ne 0 ]; then
      echo "warning: claude exited with code $agent_rc (see $err_log)"
    fi
  fi

  # Stop the sample now that the agent is done with it.
  kill_sample

  # --- parse JSON defensively ---------------------------------------------
  local result num_turns cost in_tok out_tok cache_read cache_creation
  result="$(jq -r '.result // .error // "(no result field)"' "$raw_json" 2>/dev/null || echo "(unparseable JSON)")"
  num_turns="$(jq -r '.num_turns // "n/a"' "$raw_json" 2>/dev/null || echo "n/a")"
  cost="$(jq -r '.total_cost_usd // "n/a"' "$raw_json" 2>/dev/null || echo "n/a")"
  in_tok="$(jq -r '.usage.input_tokens // "n/a"' "$raw_json" 2>/dev/null || echo "n/a")"
  out_tok="$(jq -r '.usage.output_tokens // "n/a"' "$raw_json" 2>/dev/null || echo "n/a")"
  cache_read="$(jq -r '.usage.cache_read_input_tokens // "n/a"' "$raw_json" 2>/dev/null || echo "n/a")"
  cache_creation="$(jq -r '.usage.cache_creation_input_tokens // "n/a"' "$raw_json" 2>/dev/null || echo "n/a")"

  # --- grade (case-insensitive substring match) ---------------------------
  local verdict="FAIL"
  if echo "$result" | grep -qi "$expected"; then
    verdict="PASS"
  fi
  echo "verdict: $verdict (looking for '$expected'), cost=$cost turns=$num_turns"

  # --- accumulate run totals ----------------------------------------------
  if [ "$cost" != "n/a" ]; then
    TOTAL_COST="$(awk -v a="$TOTAL_COST" -v b="$cost" 'BEGIN{printf "%.6f", a+b}')"
    COST_KNOWN=1
  fi
  case "$num_turns" in
    ''|*[!0-9]*) : ;;               # non-numeric -> skip
    *) TOTAL_TURNS=$((TOTAL_TURNS + num_turns)); TURNS_KNOWN=1 ;;
  esac

  # --- token total (sum numeric fields only) ------------------------------
  local total_tok
  total_tok="$(jq -r '
    [ .usage.input_tokens, .usage.output_tokens,
      .usage.cache_read_input_tokens, .usage.cache_creation_input_tokens ]
    | map(select(type=="number")) | add // "n/a"' "$raw_json" 2>/dev/null || echo "n/a")"

  # --- append to report ----------------------------------------------------
  {
    echo "## $key — $verdict"
    echo
    echo "- **Cost (USD): $cost**  ← headline metric"
    echo "- Turns: $num_turns"
    echo "- Sample: \`$class\` (pid $pid)"
    echo "- Expected token in answer: \`$expected\`"
    echo
    echo "Token breakdown (secondary):"
    echo
    echo "| token type | count |"
    echo "|---|---|"
    echo "| input_tokens | $in_tok |"
    echo "| output_tokens | $out_tok |"
    echo "| cache_read_input_tokens | $cache_read |"
    echo "| cache_creation_input_tokens | $cache_creation |"
    echo "| total | $total_tok |"
    echo
    echo "### Agent answer"
    echo
    echo '```'
    echo "$result"
    echo '```'
    echo
    echo "Raw JSON: \`$raw_json\`"
    echo
  } >> "$REPORT"

  echo "appended $key to report"
}

for key in "${RUN_KEYS[@]}"; do
  run_scenario "$key"
done

# --- run totals ------------------------------------------------------------
total_cost_str="n/a"; [ "$COST_KNOWN" -eq 1 ] && total_cost_str="$TOTAL_COST"
total_turns_str="n/a"; [ "$TURNS_KNOWN" -eq 1 ] && total_turns_str="$TOTAL_TURNS"
{
  echo "## Run totals"
  echo
  echo "- **Total cost (USD): $total_cost_str**"
  echo "- Total turns: $total_turns_str"
  echo "- Scenarios: ${#RUN_KEYS[@]}"
} >> "$REPORT"

echo
echo "Total cost (USD): $total_cost_str | total turns: $total_turns_str"
echo "Done. Report written to: $REPORT"
