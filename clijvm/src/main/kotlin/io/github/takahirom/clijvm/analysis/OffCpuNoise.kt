package io.github.takahirom.clijvm.analysis

/**
 * Shared predicate for off-CPU threads that are almost always noise rather than a bottleneck:
 * JVM housekeeping threads and idle worker-pool threads parked waiting for work.
 *
 * A busy JVM parks these threads for nearly the whole recording (e.g. `Cleaner-` sitting on a
 * reference queue for 58s of a 60s run), which otherwise dominates the off-CPU view and falsely
 * trips the "off-CPU dominant" signal. Both [InsightRules] (for triggering) and the renderers (for
 * the text summary) consult this single predicate so the two views agree on what counts as noise.
 */
object OffCpuNoise {

    /**
     * JVM housekeeping thread names, matched as prefixes (covers numbered variants such as
     * `Cleaner-1`, `C1 CompilerThread3`, `JFR Recorder Thread`).
     */
    private val HOUSEKEEPING_THREAD_PREFIXES = listOf(
        "Cleaner-", "Common-Cleaner", "Finalizer", "Reference Handler", "Notification Thread",
        "Signal Dispatcher", "Attach Listener", "JFR ", "C1 CompilerThread", "C2 CompilerThread",
    )

    /**
     * Frames that identify an idle worker-pool loop: a thread parked waiting for its next task
     * rather than blocked on real work.
     */
    private val IDLE_WORKER_FRAME_MARKERS = listOf(
        "ThreadPoolExecutor.getTask", "SynchronousQueue",
        "ScheduledThreadPoolExecutor\$DelayedWorkQueue.take", "TaskRunner.awaitTaskToRun",
        "ForkJoinPool.scan", "CoroutineScheduler.park",
    )

    /** True when [thread] is a housekeeping thread or an idle worker parked for its next task. */
    fun isSuppressed(thread: ThreadWait): Boolean =
        isHousekeepingName(thread.thread) || isIdleWorkerStack(thread.stack)

    private fun isHousekeepingName(name: String): Boolean =
        HOUSEKEEPING_THREAD_PREFIXES.any { name.startsWith(it) }

    private fun isIdleWorkerStack(stack: List<String>): Boolean =
        stack.any { frame -> IDLE_WORKER_FRAME_MARKERS.any { frame.contains(it) } }
}
