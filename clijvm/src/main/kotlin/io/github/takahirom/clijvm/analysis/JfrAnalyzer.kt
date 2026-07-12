package io.github.takahirom.clijvm.analysis

import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordedFrame
import jdk.jfr.consumer.RecordingFile
import java.nio.file.Path
import java.time.Instant

/**
 * Aggregates a `.jfr` recording into a [ProfileResult]:
 * hot methods (self time), hot threads, GC pauses, collapsed stacks, and allocation sites.
 */
object JfrAnalyzer {

    private const val MAX_STACK_DEPTH = 64

    /**
     * @param file the recording to analyse.
     * @param pid known process id, or null when re-analysing an arbitrary file.
     * @param mainClass known main class, or null.
     * @param durationMsHint the wall-clock profiling duration; when null it is derived
     *   from the recording's event timespan.
     * @param topN how many hot methods / threads to keep.
     * @param polledContention lock contention gathered out-of-band by thread-state polling, merged
     *   with the JFR-event contention. Supplied by the caller so this analyzer stays file-pure.
     */
    fun analyze(
        file: Path,
        pid: Long? = null,
        mainClass: String? = null,
        durationMsHint: Long? = null,
        topN: Int = 20,
        partial: Boolean = false,
        polledContention: LockContentionStats? = null,
    ): ProfileResult {
        val selfCounts = HashMap<String, Int>()
        val representativeStacks = HashMap<String, List<String>>()
        val threadCounts = HashMap<String, Int>()
        // Per-thread self-method counts and representative stacks, for the --thread drill-down.
        val threadSelfCounts = HashMap<String, HashMap<String, Int>>()
        val threadStacks = HashMap<String, HashMap<String, List<String>>>()
        val collapsedCounts = LinkedHashMap<String, Int>()
        val collapsedFrames = HashMap<String, List<String>>()

        // Allocation events: prefer ObjectAllocationSample (JDK 16+), fall back to TLAB events.
        val sampleAllocations = ArrayList<AllocationRecord>()
        val tlabAllocations = ArrayList<AllocationRecord>()

        // Wait/park/sleep events, aggregated per thread for the `report --waits` view.
        val waitByThread = HashMap<String, WaitAcc>()

        // Monitor-enter (lock contention) events, aggregated per contended monitor class.
        val monitorByClass = HashMap<String, MonitorAcc>()

        var totalSamples = 0
        var gcCount = 0
        var gcTotalMs = 0.0
        var gcMaxMs = 0.0
        // Live-heap size after each GC, in recording order, for the leak/heap-trend signal.
        val postGcHeapUsed = ArrayList<Long>()
        var minTime: Instant? = null
        var maxTime: Instant? = null

        // Class loading: jdk.ClassLoadingStatistics reports cumulative counts periodically.
        var firstLoadedCount: Long? = null
        var lastLoadedCount: Long? = null
        var lastUnloadedCount = 0L

        RecordingFile(file).use { recording ->
            while (recording.hasMoreEvents()) {
                val event = recording.readEvent()
                event.startTime?.let { t ->
                    if (minTime == null || t.isBefore(minTime)) minTime = t
                    if (maxTime == null || t.isAfter(maxTime)) maxTime = t
                }
                when (event.eventType.name) {
                    "jdk.ExecutionSample" -> {
                        totalSamples++
                        val frames = event.stackTrace?.frames.orEmpty()
                        val leafFirst = frames.take(MAX_STACK_DEPTH).map { formatFrame(it) }

                        val topKey = leafFirst.firstOrNull() ?: "(no stack)"
                        selfCounts.merge(topKey, 1, Int::plus)
                        representativeStacks.putIfAbsent(topKey, leafFirst)

                        // For execution samples the sampled thread is in the "sampledThread"
                        // field; the event thread is the JFR sampler, not the profiled thread.
                        val recordedThread = runCatching { event.getThread("sampledThread") }.getOrNull()
                            ?: event.thread
                        val thread = recordedThread?.javaName?.takeIf { it.isNotBlank() }
                            ?: recordedThread?.osName
                            ?: "(unknown thread)"
                        threadCounts.merge(thread, 1, Int::plus)
                        threadSelfCounts.getOrPut(thread) { HashMap() }.merge(topKey, 1, Int::plus)
                        threadStacks.getOrPut(thread) { HashMap() }.putIfAbsent(topKey, leafFirst)

                        val rootFirst = leafFirst.asReversed()
                        val collapsedKey = rootFirst.joinToString(";")
                        collapsedCounts.merge(collapsedKey, 1, Int::plus)
                        collapsedFrames.putIfAbsent(collapsedKey, rootFirst)
                    }
                    "jdk.GarbageCollection" -> {
                        gcCount++
                        val ms = event.duration.toNanos() / 1_000_000.0
                        gcTotalMs += ms
                        if (ms > gcMaxMs) gcMaxMs = ms
                    }
                    "jdk.ObjectAllocationSample" ->
                        allocationRecord(event, "weight")?.let { sampleAllocations.add(it) }
                    "jdk.ObjectAllocationInNewTLAB", "jdk.ObjectAllocationOutsideTLAB" ->
                        allocationRecord(event, "allocationSize")?.let { tlabAllocations.add(it) }
                    "jdk.GCHeapSummary" -> {
                        // "After GC" heapUsed is the live set once dead objects are collected.
                        val phase = runCatching { event.getString("when") }.getOrNull()
                        if (phase == "After GC") {
                            runCatching { event.getLong("heapUsed") }.getOrNull()?.let { postGcHeapUsed.add(it) }
                        }
                    }
                    "jdk.JavaMonitorEnter" -> accumulateMonitorEnter(monitorByClass, event)
                    "jdk.ThreadPark" -> accumulateWait(waitByThread, event, WaitKind.PARK, "parkedClass")
                    "jdk.JavaMonitorWait" -> accumulateWait(waitByThread, event, WaitKind.MONITOR, "monitorClass")
                    "jdk.ThreadSleep" -> accumulateWait(waitByThread, event, WaitKind.SLEEP, classField = null)
                    "jdk.ClassLoadingStatistics" -> {
                        val loaded = runCatching { event.getLong("loadedClassCount") }.getOrNull()
                        if (loaded != null) {
                            if (firstLoadedCount == null) firstLoadedCount = loaded
                            lastLoadedCount = loaded
                        }
                        runCatching { event.getLong("unloadedClassCount") }.getOrNull()
                            ?.let { lastUnloadedCount = it }
                    }
                }
            }
        }

        val durationMs = durationMsHint ?: run {
            val start = minTime
            val end = maxTime
            if (start != null && end != null) java.time.Duration.between(start, end).toMillis() else 0L
        }

        val hotMethods = selfCounts.entries
            .sortedByDescending { it.value }
            .take(topN)
            .map { (method, samples) ->
                HotMethod(
                    method = method,
                    selfPct = percentage(samples, totalSamples),
                    samples = samples,
                    stack = representativeStacks[method].orEmpty(),
                )
            }

        val hotThreads = threadCounts.entries
            .sortedByDescending { it.value }
            .take(topN)
            .map { (name, samples) ->
                HotThread(
                    name = name,
                    cpuPct = percentage(samples, totalSamples),
                    samples = samples,
                )
            }

        // Per-thread method breakdowns, aligned to the hot threads (the ones a user can drill into).
        val threadBreakdowns = hotThreads.map { t ->
            val counts = threadSelfCounts[t.name].orEmpty()
            val stacks = threadStacks[t.name].orEmpty()
            val methods = counts.entries
                .sortedByDescending { it.value }
                .take(topN)
                .map { (method, samples) ->
                    HotMethod(
                        method = method,
                        selfPct = percentage(samples, t.samples),
                        samples = samples,
                        stack = stacks[method].orEmpty(),
                    )
                }
            ThreadBreakdown(name = t.name, totalSamples = t.samples, topMethods = methods)
        }

        val collapsed = collapsedCounts.entries
            .sortedByDescending { it.value }
            .map { (key, samples) -> CollapsedStack(collapsedFrames.getValue(key), samples) }

        val allocation = aggregateAllocation("jdk.ObjectAllocationSample", sampleAllocations, topN)
            ?: aggregateAllocation("jdk.ObjectAllocationInNewTLAB/OutsideTLAB", tlabAllocations, topN)

        // Threads ranked by total wait time; null when the recording had no wait events.
        val waitThreads = waitByThread.entries
            .map { (name, acc) -> acc.toThreadWait(name) }
            .sortedByDescending { it.totalMs }
        val waits = if (waitThreads.isEmpty()) null else WaitStats(waitThreads, waitThreads.sumOf { it.totalMs })

        // Contended monitors ranked by total blocked time; null when no monitor-enter events.
        val monitors = monitorByClass.entries
            .map { (name, acc) -> acc.toContendedMonitor(name) }
            .sortedByDescending { it.totalBlockedMs }
        val jfrLockContention =
            if (monitors.isEmpty()) null else LockContentionStats(monitors, monitors.sumOf { it.totalBlockedMs })
        // Merge exact JFR contention with the caller's polled estimate (covers monopolized locks
        // that emit zero JavaMonitorEnter events). JFR wins per class; poller-only classes are added.
        val lockContention = mergeLockContention(jfrLockContention, polledContention)

        val classLoading = lastLoadedCount?.let { last ->
            ClassLoadingStats(
                loadedClassCount = last,
                unloadedClassCount = lastUnloadedCount,
                loadedDuringRecording = (last - (firstLoadedCount ?: last)).coerceAtLeast(0),
            )
        }

        val base = ProfileResult(
            pid = pid,
            mainClass = mainClass,
            durationMs = durationMs,
            totalSamples = totalSamples,
            hotMethods = hotMethods,
            hotThreads = hotThreads,
            threadBreakdowns = threadBreakdowns,
            gc = GcStats(count = gcCount, totalPauseMs = gcTotalMs, maxPauseMs = gcMaxMs),
            collapsed = collapsed,
            allocation = allocation,
            classLoading = classLoading,
            waits = waits,
            lockContention = lockContention,
            heapTrend = HeapTrendAnalysis.derive(postGcHeapUsed),
            recordingPath = file.toAbsolutePath().toString(),
            partial = partial,
        )
        val insights = InsightRules.derive(base)
        return base.copy(warnings = insights.warnings, hints = insights.hints)
    }

    private fun formatFrame(frame: RecordedFrame): String {
        val method = frame.method ?: return "(unknown)"
        val type = method.type?.name
        return if (type.isNullOrEmpty()) method.name else "$type.${method.name}"
    }

    /** Builds an [AllocationRecord] from an allocation event, reading its size from [weightField]. */
    private fun allocationRecord(event: RecordedEvent, weightField: String): AllocationRecord? {
        val objectClass = runCatching { event.getClass("objectClass") }.getOrNull() ?: return null
        val weight = runCatching { event.getLong(weightField) }.getOrElse { 0L }
        val stack = event.stackTrace?.frames?.take(MAX_STACK_DEPTH)?.map { formatFrame(it) }.orEmpty()
        return AllocationRecord(
            className = readableClassName(objectClass.name),
            weight = weight,
            stack = stack,
        )
    }

    private fun percentage(part: Int, total: Int): Double =
        if (total == 0) 0.0 else 100.0 * part / total

    private enum class WaitKind { PARK, MONITOR, SLEEP }

    /** Mutable per-thread accumulator for wait/park/sleep events. */
    private class WaitAcc {
        var parkedMs = 0.0
        var monitorWaitMs = 0.0
        var sleepMs = 0.0
        var parkEvents = 0
        var monitorWaitEvents = 0
        var sleepEvents = 0
        val blockerCounts = HashMap<String, Int>()
        var longestMs = -1.0
        var stack: List<String> = emptyList()

        fun toThreadWait(name: String) = ThreadWait(
            thread = name,
            parkedMs = parkedMs,
            monitorWaitMs = monitorWaitMs,
            sleepMs = sleepMs,
            parkEvents = parkEvents,
            monitorWaitEvents = monitorWaitEvents,
            sleepEvents = sleepEvents,
            topBlockers = blockerCounts.entries.sortedByDescending { it.value }.map { it.key },
            stack = stack,
        )
    }

    /** Mutable per-monitor accumulator for `jdk.JavaMonitorEnter` (lock-contention) events. */
    private class MonitorAcc {
        var totalBlockedMs = 0.0
        var events = 0
        val blockedByThread = HashMap<String, Double>()
        val ownerCounts = HashMap<String, Int>()
        var longestMs = -1.0
        var stack: List<String> = emptyList()

        fun toContendedMonitor(className: String) = ContendedMonitor(
            className = className,
            totalBlockedMs = totalBlockedMs,
            events = events,
            topBlockedThreads = blockedByThread.entries.sortedByDescending { it.value }.map { it.key },
            // "Held mostly by": the thread most often recorded as the monitor's previous owner.
            ownerThread = ownerCounts.entries.maxByOrNull { it.value }?.key,
            stack = stack,
        )
    }

    /** Folds one `jdk.JavaMonitorEnter` event into [monitorByClass], keyed by the contended class. */
    private fun accumulateMonitorEnter(monitorByClass: HashMap<String, MonitorAcc>, event: RecordedEvent) {
        val monitorClass = runCatching { event.getClass("monitorClass") }.getOrNull() ?: return
        val durationMs = event.duration.toNanos() / 1_000_000.0
        val acc = monitorByClass.getOrPut(readableClassName(monitorClass.name)) { MonitorAcc() }
        acc.totalBlockedMs += durationMs
        acc.events++
        // For monitor-enter the event thread IS the blocked thread waiting to acquire the lock.
        val blocked = event.thread?.javaName?.takeIf { it.isNotBlank() }
            ?: event.thread?.osName
            ?: "(unknown thread)"
        acc.blockedByThread.merge(blocked, durationMs, Double::plus)
        // previousOwner is the thread that held the monitor; read defensively (may be absent).
        runCatching { event.getThread("previousOwner") }.getOrNull()?.let { owner ->
            (owner.javaName?.takeIf { it.isNotBlank() } ?: owner.osName)
                ?.let { acc.ownerCounts.merge(it, 1, Int::plus) }
        }
        // Keep the stack from this monitor's single longest block as its representative.
        if (durationMs > acc.longestMs) {
            acc.longestMs = durationMs
            acc.stack = event.stackTrace?.frames?.take(MAX_STACK_DEPTH)?.map { formatFrame(it) }.orEmpty()
        }
    }

    /** Folds one wait event into [waitByThread], attributing it to the event's own thread. */
    private fun accumulateWait(
        waitByThread: HashMap<String, WaitAcc>,
        event: RecordedEvent,
        kind: WaitKind,
        classField: String?,
    ) {
        val durationMs = event.duration.toNanos() / 1_000_000.0
        // For these events the event thread IS the waiting thread (unlike ExecutionSample).
        val thread = event.thread?.javaName?.takeIf { it.isNotBlank() }
            ?: event.thread?.osName
            ?: "(unknown thread)"
        val acc = waitByThread.getOrPut(thread) { WaitAcc() }
        when (kind) {
            WaitKind.PARK -> { acc.parkedMs += durationMs; acc.parkEvents++ }
            WaitKind.MONITOR -> { acc.monitorWaitMs += durationMs; acc.monitorWaitEvents++ }
            WaitKind.SLEEP -> { acc.sleepMs += durationMs; acc.sleepEvents++ }
        }
        classField?.let { field ->
            runCatching { event.getClass(field) }.getOrNull()?.let { c ->
                acc.blockerCounts.merge(readableClassName(c.name), 1, Int::plus)
            }
        }
        // Keep the stack from this thread's single longest wait as its representative.
        if (durationMs > acc.longestMs) {
            acc.longestMs = durationMs
            acc.stack = event.stackTrace?.frames?.take(MAX_STACK_DEPTH)?.map { formatFrame(it) }.orEmpty()
        }
    }
}
