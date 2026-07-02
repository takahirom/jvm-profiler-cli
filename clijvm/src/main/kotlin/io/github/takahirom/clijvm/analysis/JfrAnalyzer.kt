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
     */
    fun analyze(
        file: Path,
        pid: Long? = null,
        mainClass: String? = null,
        durationMsHint: Long? = null,
        topN: Int = 20,
        partial: Boolean = false,
    ): ProfileResult {
        val selfCounts = HashMap<String, Int>()
        val representativeStacks = HashMap<String, List<String>>()
        val threadCounts = HashMap<String, Int>()
        val collapsedCounts = LinkedHashMap<String, Int>()
        val collapsedFrames = HashMap<String, List<String>>()

        // Allocation events: prefer ObjectAllocationSample (JDK 16+), fall back to TLAB events.
        val sampleAllocations = ArrayList<AllocationRecord>()
        val tlabAllocations = ArrayList<AllocationRecord>()

        var totalSamples = 0
        var gcCount = 0
        var gcTotalMs = 0.0
        var gcMaxMs = 0.0
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

        val collapsed = collapsedCounts.entries
            .sortedByDescending { it.value }
            .map { (key, samples) -> CollapsedStack(collapsedFrames.getValue(key), samples) }

        val allocation = aggregateAllocation("jdk.ObjectAllocationSample", sampleAllocations, topN)
            ?: aggregateAllocation("jdk.ObjectAllocationInNewTLAB/OutsideTLAB", tlabAllocations, topN)

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
            gc = GcStats(count = gcCount, totalPauseMs = gcTotalMs, maxPauseMs = gcMaxMs),
            collapsed = collapsed,
            allocation = allocation,
            classLoading = classLoading,
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
}
