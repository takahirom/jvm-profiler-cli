# clijvm

An AI-friendly JVM profiler CLI. It attaches to a running JVM, records with **JDK Flight
Recorder** (no agent or flags needed on the target), and prints results as human-readable text,
structured **JSON**, or flamegraph **collapsed stacks** — so an AI agent can read a profile and
reason about it directly.

clijvm does not reinvent the profiling engine; it wraps `jcmd`/JFR and
`jdk.jfr.consumer.RecordingFile` and focuses on turning a profile into an answer.

## Requirements

- A **JDK** (not a JRE) on `JAVA_HOME` or as the running Java — the attach API and `jcmd` live there.
- The target JVM must run as the **same OS user**. Processes started with
  `-XX:+DisableAttachMechanism`, and JVMs in other containers, cannot be attached.
- JDK 11+ targets. Allocation sampling (`jdk.ObjectAllocationSample`) needs JDK 16+ on the target;
  older targets fall back to TLAB allocation events.

## Build and run

```bash
./gradlew run --args="list"                 # run from source
./gradlew installDist                        # produces build/install/clijvm/bin/clijvm
build/install/clijvm/bin/clijvm list         # run the installed launcher
```

## Commands

```
clijvm list [--format table|json]                 # attachable JVMs; Gradle test workers are marked
clijvm cpu <target> [--duration 30s]              # synchronous CPU profile, then report
clijvm cpu start|stop|status <target>             # background recording (see below)
clijvm memory <target> [--duration 30s]           # synchronous allocation profile
clijvm memory start|stop|status <target>          # background recording (shares cpu's recording)
clijvm heap <target> [--limit 20] [--format ...]  # class histogram (jcmd GC.class_histogram)
clijvm report [--last | <file.jfr>] [--format ...] # re-analyse a saved recording, no re-profiling
```

- **`<target>`** is a pid, or a case-insensitive substring of a process display name. Ambiguous
  name matches fail with the candidate list. clijvm never matches its own process.
- **`--format`** is `summary` (default), `json`, or `collapsed`; **`--output <file>`** writes to a file.
- Every recording is saved to `~/.clijvm/recordings/<timestamp>-<pid>.jfr`, so you can re-view it in
  another format with `report` without paying to profile again.
- **cpu and memory share one recording per process.** `settings=profile` captures both execution
  samples and allocation events, so `cpu start` then `memory stop` on the same pid works and reports
  allocations. Background session state lives in `~/.clijvm/sessions/<pid>.json`.

## Recommended AI workflow

1. Profile: `clijvm cpu <target> --duration 20s` (or `memory`, or `heap`).
2. Re-emit as JSON: `clijvm report --last --format json`.
3. Feed that JSON to your AI agent. It contains `warnings` (confidence caveats), `hints`
   (actionable one-liners), `cpu.hotMethods` with self%, `cpu.hotThreads`, `gc`, `allocation`
   (when present), and `classLoading`. The `warnings`/`hints` arrays come first so the agent sees
   the takeaways before the raw data.

`collapsed` output (`frame;frame;count`, root-first) is a flamegraph-compatible view an agent can
also read stack-by-stack.

## Profiling Gradle test workers (Robolectric)

Gradle test workers are short-lived: they can die before you get to `stop`. Two reliable options:

- **`--wait` (recommended)**: start clijvm *before* the worker exists and let it attach the moment
  it appears.
  ```bash
  clijvm cpu --wait "Gradle Test Executor" --wait-timeout 120s --duration 20s --format json
  # then, in another shell: ./gradlew test
  ```
- **Synchronous `--duration`** is the most dependable mode for short-lived processes, because the
  recording is dumped as soon as the duration elapses.

If you do use `cpu start`/`memory start` and the worker dies before `stop`, clijvm still tries to
**salvage** a partial recording: the background recording is started with JFR `dumponexit=true`, so
`stop` recovers whatever the JVM dumped on its way out and reports it, clearly marked `PARTIAL`.

## Caveats

- **Allocation bytes are estimates.** They are extrapolated from sampled allocation events; treat
  them as relative magnitudes, not exact totals.
- **Sampling granularity.** Short or mostly-idle recordings yield few CPU samples, making self%
  noisy. clijvm emits a `warnings` entry when the sample count is low or the target looks idle;
  prefer a longer `--duration` for stable hotspots.
- **Same-user attach only.** clijvm cannot profile another user's JVM or a JVM in another container.
- **JDK required** on the clijvm side (for the attach API and `jcmd`).
