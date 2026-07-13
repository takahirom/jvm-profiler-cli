package io.github.takahirom.clijvm.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional

/**
 * `clijvm guide [topic]` — Layer 2 playbooks.
 *
 * Holds the situational discipline that does not fit in per-command output: which recipe to run for
 * a given kind of slow JVM, and how to read the report without stopping at the first plausible
 * cause. With no topic it prints a one-line index; with a topic it prints that topic's playbook.
 */
class GuideCommand : CliktCommand(
    name = "guide",
    help = "Playbooks for common profiling situations. Run without a topic to list them.",
) {
    private val topic by argument(
        name = "topic",
        help = "One of: ${GUIDE_TOPICS.keys.joinToString(", ")}. Omit to list all topics.",
    ).optional()

    override fun run() {
        val requested = topic
        if (requested == null) {
            echo(GUIDE_INDEX)
            return
        }
        val playbook = GUIDE_TOPICS[requested]
            ?: throw CliktError(
                "Unknown guide topic \"$requested\". Valid topics: ${GUIDE_TOPICS.keys.joinToString(", ")}."
            )
        echo(playbook)
    }
}

/**
 * Trailer appended to the situational playbooks pointing at the report-reading discipline.
 * Worded as a trigger ("before concluding") so the reader fires it at the right moment,
 * not as an optional footnote.
 */
private const val READING_TRAILER = "\nBefore concluding: clijvm guide reading — how to weigh warnings and hints."

/** The one-line-per-topic index printed when `guide` is run without a topic. */
private val GUIDE_INDEX = """
    clijvm guide <topic> — pick the playbook that matches your situation:
      slow-tests              slow JVM/JUnit tests
      slow-robolectric-tests  slow Robolectric/Compose tests (idle waits, leaks, sandbox)
      slow-server             slow long-lived service or daemon
      slow-build              slow Gradle/Maven build or batch job
      short-lived             JVMs that exit within seconds (attach loses the race)
      reading                 how to read a report without jumping to conclusions
""".trimIndent()

/** Topic name -> playbook text. Terse, imperative, plain text; keep each low-token. */
private val GUIDE_TOPICS: Map<String, String> = linkedMapOf(
    "slow-tests" to """
        == guide: slow-tests ==
        Slow JVM tests (JUnit, Gradle Test Executor).

        Recipe:
          1. Start the tests, then immediately in another shell:
               clijvm profile --wait "Gradle Test Executor" --duration 30s
             --wait matches the worker as it spawns; the recording salvages on exit if
             the worker dies mid-run, so you still get a report.
          2. Read the hints, then drill into a hot method with:
               clijvm report --last --method N
             If CPU looks idle, the time is off-CPU: clijvm report --last --waits
        Done when you can name what multiplies the test time and the hint that shows it.
        Robolectric/Compose/Android tests: clijvm guide slow-robolectric-tests
    """.trimIndent() + READING_TRAILER,

    "slow-robolectric-tests" to """
        == guide: slow-robolectric-tests ==
        Slow Robolectric/Compose tests — the common causes, most user-fixable first.

        Capture as in 'guide slow-tests':
          clijvm profile --wait "Gradle Test Executor" --duration 30s

        Check in this order:
          1. UI idle-waiting: an idle-wait hint, or wait stacks in waitForIdle /
             Espresso idling. A long or infinite animation keeps the UI non-idle, so
             every waitForIdle burns its full timeout. Disable animations in tests;
             check rememberInfiniteTransition and repeating animators.
          2. Leaks between tests: a rising post-GC heap across the run points at
             unclosed ActivityScenario or static references keeping Activities alive.
          3. Sandbox churn: thousands of classes loaded during the recording means
             per-test sandbox rebuilds; share @Config across tests.
          4. SDK spread: several "SDK NN Main Thread" threads means @Config sdk=[...]
             multiplies every cost above; narrow the SDK list.
        Done when you can name which of the four dominates and the hint that shows it.
    """.trimIndent() + READING_TRAILER,

    "slow-server" to """
        == guide: slow-server ==
        Slow long-lived service or daemon.

        Recipe:
          1. Reproduce the slow operation WHILE recording — the recording must cover the
             slow window, not idle time:
               clijvm profile <pid> --duration 30s
             or bracket the operation with:
               clijvm profile start <pid>   (trigger the slow op)   clijvm profile stop <pid>
          2. If CPU looks idle but the operation is still slow, the time is off-CPU:
             look at waits and lock contention rather than hot methods:
               clijvm report --last --waits
          3. A thread blocked on a lock, socket, or sleep will not show as a CPU hotspot.
        Done when the recording covers the slow window and you can name where its time
        went: a CPU method, a contended lock, or a wait.
    """.trimIndent() + READING_TRAILER,

    "slow-build" to """
        == guide: slow-build ==
        Slow Gradle/Maven build or batch job.

        Recipe:
          1. Find the right JVM — a build has a daemon plus worker processes:
               clijvm list
             Profile the busy one (the worker doing the compile/task, not an idle daemon).
          2. Profile it while the slow phase runs:
               clijvm profile <pid> --duration 30s
          3. Look for a single-thread bottleneck: one thread holding most of the samples
             means the work is not parallel. Also check GC pressure — frequent GCs or a
             rising post-GC heap point at allocation, not compute.
        Done when you can say whether the build is single-thread-bound, GC-bound, or
        CPU-bound, with the hint or thread share that shows it.
    """.trimIndent() + READING_TRAILER,

    "short-lived" to """
        == guide: short-lived ==
        JVMs that exit within seconds — attach cannot win the race.

        Recipe:
          Do not try to attach. Have the JVM record itself from the start:
            JAVA_TOOL_OPTIONS='-XX:StartFlightRecording=filename=/tmp/rec-%p.jfr,dumponexit=true' <command>
          then read the file it leaves behind:
            clijvm report /tmp/rec-<pid>.jfr
          Notes:
            - dumponexit=true guarantees a file even on a fast exit.
            - JAVA_TOOL_OPTIONS propagates to child JVMs, so forked workers record too;
              %p expands to each pid, keeping concurrent recordings from overwriting
              each other.
        Done when clijvm report prints a summary from the dumped file.
    """.trimIndent() + READING_TRAILER,

    "reading" to """
        == guide: reading ==
        How to read a report without jumping to conclusions.

        Warnings are confidence caveats, not findings. Low sample count, an idle target,
        or a partial/salvaged recording all mean the data is noisy — do not conclude from
        it. Record longer or reproduce the load, then re-read.

        Hints are suspects, not verdicts. Performance problems rarely have a single
        culprit, so when several hints appear, weigh them all before delivering a verdict.

        Work the layers in order, going deeper only as needed:
          digest (--digest)  ->  summary (default)  ->  targeted drill-downs:
            --method N   a hot method's full stacks
            --site N     an allocation site's full stacks
            --thread N   one thread's breakdown
            --waits      off-CPU time (blocked/parked/sleeping threads)
        Every truncated list tells you how to expand it — follow that line.
    """.trimIndent(),
)
