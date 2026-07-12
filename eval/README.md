# clijvm eval harness

## Purpose

The project's north-star metric (PLAN.md M7) is: **how much does it cost a cold
AI agent to reach the correct JVM bottleneck using clijvm?** The headline number
is the USD cost (`total_cost_usd`) — a lower cost means the CLI's output teaches
an agent more per dollar of AI load. We report cost rather than a raw token sum
because cache reads are billed at roughly one-tenth the price of regular input,
so summing all token types overweights them; cost is the honest AI-load proxy.
Token counts are kept as secondary detail.

This harness measures that directly. For each scenario it:

1. Compiles and launches a single-file Java program with a deliberately seeded
   bottleneck.
2. Hands a fresh `claude -p` agent only the process PID and a tool allowlist
   restricted to `clijvm` and `jps` — no other hints. The agent can only learn
   about the process through the CLI, so the cost reflects what the CLI itself
   teaches.
3. Records cost, turns, token usage, and whether the agent named the correct
   bottleneck.

## Isolation

The measurement is only meaningful if the agent learns the bottleneck from
clijvm, not by reading the sample source (whose javadoc literally states the
expected diagnosis). Read/Grep/Glob and sandboxed read-only bash commands do not
require permission, so the tool allowlist alone does not stop source peeking.
The harness therefore:

- compiles each sample into a temp dir **outside the repo** and launches the
  java process from there;
- copies **no `.java` sources** into the run dir — only `.class` files;
- invokes `claude` with its cwd set to a **separate empty temp dir**, and the
  prompt mentions no paths.

Thread names and class names still appear legitimately in `clijvm`/`jps` (and
`jstack`) output — that is the intended discovery path. What we block is reading
the source/javadoc that names the answer outright. `jstack` remains usable
inside claude's sandbox; that is legitimate JVM tooling, so we leave it.

## Scenarios

| scenario | sample | seeded bottleneck | pass if answer contains |
|---|---|---|---|
| `cpu-hot` | `CpuHotEval` | main thread spins in `hotChecksumLoop()` | `hotChecksumLoop` |
| `lock-contention` | `LockContentionEval` | 8 threads contend on the `SharedLedger` monitor | `SharedLedger` |
| `single-thread` | `SingleThreadEval` | all work serial on the `pipeline-worker` thread while a pool idles | `pipeline-worker` |

## Running

```bash
./run-eval.sh                # all scenarios
./run-eval.sh cpu-hot        # one scenario
./run-eval.sh --dry-run      # compile + launch + cleanup only; stub the agent
./run-eval.sh cpu-hot --dry-run
```

Valid scenarios: `cpu-hot`, `lock-contention`, `single-thread`, `all` (default).
`--dry-run` exercises the whole pipeline (compile, launch, PID capture, parse,
report, cleanup) but stubs the `claude` call, so it spends no tokens — use it to
smoke-test the harness.

**A real run consumes API tokens.** The allowlist
(`--allowedTools "Bash(clijvm:*)" "Bash(jps:*)"`) restricts the agent to
clijvm and jps on purpose — that is the whole point of the measurement, so the
harness intentionally does *not* use `--dangerously-skip-permissions`.

Preconditions checked up front: `claude`, `jq`, `javac`, `clijvm`, `jps` on PATH.

## Reading results

Each run writes a timestamped markdown report to `results/<UTC timestamp>.md`.
Per scenario: PASS/FAIL, the headline **Cost (USD)** and turns up top, the
seeded sample and expected token, a secondary token breakdown table (input,
output, cache read, cache creation, total), the agent's full answer, and the
path to the raw JSON (`results/<timestamp>-<scenario>.json`; stderr in the
matching `.err`). The file ends with a **Run totals** section: total cost across
scenarios, total turns, and the scenario count.

`results/` is gitignored. Compiled classes and sample stdout live in a temp dir
outside the repo and are removed on exit.
