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
clijvm --version                                  # print the build/release version
clijvm list [--format table|json] [--test-workers] # attachable JVMs; Gradle test workers are marked
clijvm profile <target> [--duration 30s]          # full profile: CPU, allocation, GC, waits (start here)
clijvm profile start|stop|status <target>         # background recording (see below)
clijvm cpu <target> [--duration 30s]              # CPU-focused view of the same full recording
clijvm memory <target> [--duration 30s]           # allocation-focused view of the same full recording
clijvm heap <target> [--limit 20] [--format ...]  # class histogram (jcmd GC.class_histogram)
clijvm report --list                              # inventory of saved recordings (pick one)
clijvm report [--last | <file.jfr>] [--format ...] # re-analyse a saved recording (layered; see below)
clijvm guide [jvm-test|server|build|short-lived|reading]  # diagnosis playbooks by situation
```

- **`<target>`** is a pid, or a case-insensitive substring of a process display name. Ambiguous
  name matches fail with the candidate list. clijvm never matches its own process.
- **`list`** truncates long display names (e.g. the Kotlin daemon's classpath) to keep rows scannable;
  pass **`--full`** to expand. JSON marks a shortened row with `"displayNameTruncated": true`.
  Matching/`--wait` always use the full name. A Gradle test worker looks like
  `…GradleWorkerMain 'Gradle Test Executor N'`; filter to just those with **`--test-workers`** and
  attach with `--wait GradleWorkerMain`.
- **`--format`** is `summary` (default), `json`, or `collapsed`; **`--output <file>`** writes to a file.
- Each recording gets a `<recording>.meta.json` sidecar (pid, mainClass, startedAt, partial, sampled
  lock contention) so a later `report` shows the real main class, remembers a salvaged `PARTIAL`
  recording, and keeps contention data the `.jfr` itself cannot carry. A missing sidecar falls back
  to the pid embedded in the filename.
- Every recording is saved to `~/.clijvm/recordings/<timestamp>-<pid>.jfr`, so you can re-view it in
  another format with `report` without paying to profile again.
- **profile, cpu, and memory share one full recording per process.** `settings=profile` captures
  execution samples, allocation events, GC, and wait events in every run, so the commands differ only
  in which view the report emphasises — picking the "wrong" one never requires re-recording, and
  `cpu start` then `memory stop` on the same pid works. Background session state lives in
  `~/.clijvm/sessions/<pid>.json`.

## Layered reports (progressive disclosure)

A full JSON report can be ~40k+ tokens — 20 hot methods and 20 allocation sites, each with a deep
stack. That's too much to read all at once. `report` is **layered** so an AI can read cheaply, then
drill only into what matters. `report --help` teaches the whole workflow on its own:

1. **Layer 0 — `--digest`**: takeaways only. Warnings, hints, and headline numbers (pid, duration,
   samples, GC, class loading, allocation totals, and the post-GC heap trend). No hot-method/
   allocation lists, no stacks.
   ```bash
   clijvm report --last --digest             # ~1 KB
   ```
   The digest carries a **post-GC heap trend** (`postGcHeap` in JSON) derived from `jdk.GCHeapSummary`
   "After GC" events, so "is it leaking?" is answerable from the digest alone: steady growth becomes a
   hint ("Post-GC heap grew from ~120 MB to ~340 MB … possible leak"), while flat/shrinking heap reads
   as a positive signal ("Post-GC heap: stable (~120 MB) over N GCs").
2. **Layer 1 — the default**: the top hot methods/threads/sites (numbered `#1`, `#2`, …) with
   shallow stacks. `--top N` (default 5) and `--max-stack-depth D` (default 5) control the size. A
   trimmed stack is flagged — `… (23 more frames)` in summary, `"stackTruncated": true` /
   `"stackFrameTotal": 28` in JSON — so you know when to drill.
   ```bash
   clijvm report --last                      # top 5, 5-frame stacks (~5% the size of --full)
   clijvm report --last --top 10 --format json
   ```
3. **Layer 2 — drill-down**: `--method N` / `--site N` (the `#N` from Layer 1) print exactly that one
   node with its **full** stack. `--thread N` drills into a hot thread, showing that thread's own top
   methods (self% within the thread) at `--max-stack-depth`.
   ```bash
   clijvm report --last --method 3           # one hot method, full stack
   clijvm report --last --site 1             # one allocation site, full stack
   clijvm report --last --thread 1           # one thread's own hot methods
   ```

Where does non-CPU time go? **`clijvm report --last --waits`** ranks threads by off-CPU time
(park / monitor-wait / sleep), with per-type totals, event counts, top blocker classes, and a
representative stack, plus a **Contended locks** section naming each contended monitor, its total
blocked time, the blocked threads, and the holding thread. Honors `--top` and `--max-stack-depth`;
`--format json` supported. Reads the JFR `jdk.ThreadPark` / `jdk.JavaMonitorWait` /
`jdk.ThreadSleep` / `jdk.JavaMonitorEnter` events the recording already holds, so no re-profiling
is needed.

Lock contention has a JFR blind spot: `jdk.JavaMonitorEnter` only commits when a blocked thread
finally *acquires* the monitor, so a monopolized lock can emit zero events despite severe blocking.
During synchronous profiling clijvm therefore also samples thread states (`jcmd Thread.print`) and
merges the result — sampled figures are marked `~`/`(sampled)` and persist to the recording's
sidecar, so `report --last --waits` shows them too. When off-CPU time dominates, the default
summary automatically leads with an **Off-CPU (dominant)** section instead of CPU hotspots.

Pick the right recording first with **`clijvm report --list`** (file, timestamp, pid, mainClass, size;
newest first; `--format json` for machines).

Escape hatch: **`--full`** (equivalently `--top 0 --max-stack-depth 0`) renders everything with full
stacks, i.e. the pre-layering behaviour.

`warnings`/`hints` come first in every layer, so the agent sees the takeaways before the raw data.
`--digest`, `--top`, and `--max-stack-depth` also work on the synchronous `cpu`/`memory` commands;
the drill-down `--method`/`--site` are `report`-only (you need Layer 1's indices first).

`collapsed` output (`frame;frame;count`, root-first) is a flamegraph-compatible view; `--top` and
`--max-stack-depth` trim it too.

### Recommended AI workflow

1. Profile: `clijvm profile <target> --duration 20s` (full recording across all axes).
2. Read the takeaways: `clijvm report --last --digest`.
3. Skim the hotspots: `clijvm report --last --format json` (top 5, shallow stacks).
4. Drill where it matters: `clijvm report --last --method <#N>` (or `--site <#N>`, `--waits`).

Situational playbooks live in the CLI itself: `clijvm guide` lists them (`jvm-test`, `server`,
`build`, `short-lived`, `reading`), and the root `--help` ends with the same 3-step workflow —
a cold agent needs no external docs.

## Profiling Gradle test workers (Robolectric)

Gradle test workers are short-lived: they can die before you get to `stop`. Two reliable options:

- **`--wait` (recommended)**: start clijvm *before* the worker exists and let it attach the moment
  it appears.
  ```bash
  clijvm profile --wait "Gradle Test Executor" --wait-timeout 120s --duration 20s --format json
  # then, in another shell: ./gradlew test
  ```
- **Synchronous `--duration`** is the most dependable mode for short-lived processes, because the
  recording is dumped as soon as the duration elapses. If the target exits *during* the recording,
  clijvm salvages the JVM's dump-on-exit file and prints a `PARTIAL` report covering the time until
  exit (e.g. "Target JVM exited 47s into the 180s recording") — no data loss, no raw attach
  stacktrace. If the target is `kill -9`'d it cannot dump on the way out; you then get a clean
  one-line error suggesting `--wait` with a shorter `--duration`.

Both the synchronous path and `profile start`/`cpu start` use JFR `dumponexit=true`, so a worker that
dies before you `stop` still yields a salvageable `PARTIAL` recording.

For JVMs that exit within seconds (attach loses the race entirely), have the JVM record itself:
`JAVA_TOOL_OPTIONS='-XX:StartFlightRecording=filename=/tmp/rec-%p.jfr,dumponexit=true' <command>`,
then `clijvm report /tmp/rec-<pid>.jfr`. `JAVA_TOOL_OPTIONS` propagates to child JVMs, so use a
unique filename per process (`%p` expands to the pid) or concurrent workers overwrite each other.
See `clijvm guide short-lived`.

## Caveats

- **Allocation bytes are estimates.** They are extrapolated from sampled allocation events; treat
  them as relative magnitudes, not exact totals.
- **Sampling granularity.** Short or mostly-idle recordings yield few CPU samples, making self%
  noisy. clijvm emits a `warnings` entry when the sample count is low or the target looks idle;
  prefer a longer `--duration` for stable hotspots.
- **Same-user attach only.** clijvm cannot profile another user's JVM or a JVM in another container.
- **JDK required** on the clijvm side (for the attach API and `jcmd`).
