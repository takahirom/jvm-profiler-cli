# cli-jvm

**clijvm** — an AI-friendly JVM profiler CLI built on JDK Flight Recorder.

Attach to any running JVM (no agent or flags needed on the target), record CPU,
allocation, GC, off-CPU wait, and lock-contention data in one pass, and read the results
as human-friendly summaries, structured JSON, or flamegraph collapsed stacks. Designed so
an AI agent can profile a process — a slow test run, service, or build — and diagnose it
from the output directly.

## Install

```console
$ brew install takahirom/repo/clijvm
```

## Quick start

```bash
clijvm list                                                  # attachable JVMs (Gradle test workers are marked)
clijvm profile <pid> --duration 30s                          # profile every axis (CPU, allocation, GC, waits)
clijvm profile --wait "Gradle Test Executor" --duration 20s  # wait for a test worker to appear, then attach
clijvm report --last --format json                           # re-render the last recording for an AI to read
clijvm guide                                                 # playbooks: slow-tests / slow-server / slow-build / short-lived
```

Example: pointing it at a Robolectric test run surfaces the diagnosis directly in `hints`:

```
Hints:
  * 32029 classes were loaded during the recording; class loading may be a bottleneck.
  * Robolectric sandbox class loading appears in allocation hot paths; sandbox setup may
    dominate. Check @Config sdk spread (each SDK level rebuilds its own sandbox).
  * Tests fan out across multiple Android SDK levels (SDK 30, 33, 35); each builds its
    own sandbox. Narrow @Config sdk to reduce setup cost.
```

See [clijvm/README.md](clijvm/README.md) for the full command reference, background
recording sessions, memory/heap commands, and output formats.

## License

[Apache License 2.0](LICENSE)
