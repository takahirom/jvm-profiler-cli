package io.github.takahirom.clijvm.analysis

/** A method that appears on top of the stack (i.e. self time) in CPU samples. */
data class HotMethod(
    val method: String,
    val selfPct: Double,
    val samples: Int,
    /** A representative stack for this method, ordered leaf-first (the hot method is first). */
    val stack: List<String>,
)

/** A thread ranked by its share of CPU samples. */
data class HotThread(
    val name: String,
    val cpuPct: Double,
    val samples: Int,
)

/**
 * Per-thread hot-method breakdown, used only by the `--thread` drill-down. Kept out of the default
 * render paths so it never bloats summary/json/collapsed output.
 */
data class ThreadBreakdown(
    val name: String,
    val totalSamples: Int,
    /** Hottest methods WITHIN this thread; [HotMethod.selfPct] is relative to this thread. */
    val topMethods: List<HotMethod>,
)

/**
 * Where one thread's non-CPU time went, from JFR `jdk.ThreadPark`, `jdk.JavaMonitorWait`, and
 * `jdk.ThreadSleep` events. Times are duration-weighted totals; a thread can accumulate more wait
 * time than the wall clock if it parks repeatedly.
 */
data class ThreadWait(
    val thread: String,
    val parkedMs: Double,
    val monitorWaitMs: Double,
    val sleepMs: Double,
    val parkEvents: Int,
    val monitorWaitEvents: Int,
    val sleepEvents: Int,
    /** Blocking classes seen (`parkedClass` / `monitorClass`), most-frequent first. */
    val topBlockers: List<String>,
    /** A representative stack (from this thread's longest single wait), ordered leaf-first. */
    val stack: List<String>,
) {
    val totalMs: Double get() = parkedMs + monitorWaitMs + sleepMs
    val totalEvents: Int get() = parkEvents + monitorWaitEvents + sleepEvents
}

/** Thread wait/park/sleep analysis. Rendered only on demand (`report --waits`), never by default. */
data class WaitStats(
    /** Threads ranked by total wait time, longest first. */
    val threads: List<ThreadWait>,
    /** Sum of all threads' wait time (cumulative; may exceed wall-clock duration). */
    val totalWaitMs: Double,
)

/** Garbage collection pause statistics over the recording. */
data class GcStats(
    val count: Int,
    val totalPauseMs: Double,
    val maxPauseMs: Double,
)

/** Direction of the post-GC heap trend across a recording. */
enum class HeapTrendDirection { GROWING, STABLE, SHRINKING, INSUFFICIENT_DATA }

/**
 * The trend of live (post-GC) heap over the recording, from `jdk.GCHeapSummary` "After GC" events.
 * Steadily growing post-GC heap is the classic memory-leak / growing-retained-set signature;
 * flat or shrinking heap (with enough GCs to judge) is a positive "probably no leak" signal.
 */
data class HeapTrend(
    /** Number of post-GC heap samples observed. */
    val gcCount: Int,
    val firstThirdAvgBytes: Long,
    val lastThirdAvgBytes: Long,
    val minBytes: Long,
    val maxBytes: Long,
    val direction: HeapTrendDirection,
)

/** A distinct stack observed in CPU samples, ordered root-first for flamegraph output. */
data class CollapsedStack(
    val frames: List<String>,
    val samples: Int,
)

/** An allocation site aggregated by allocated type. */
data class AllocationSite(
    val className: String,
    /** Estimated bytes allocated for this type over the recording. */
    val bytes: Long,
    val sharePct: Double,
    val events: Int,
    /** A representative allocation stack, ordered leaf-first (the allocating frame is first). */
    val stack: List<String>,
)

/** Allocation profile aggregated from JFR allocation events. */
data class AllocationStats(
    /** Which JFR event family produced this data, e.g. `jdk.ObjectAllocationSample`. */
    val source: String,
    val totalBytes: Long,
    val totalEvents: Int,
    val topSites: List<AllocationSite>,
)

/** Class-loading activity over the recording, from `jdk.ClassLoadingStatistics`. */
data class ClassLoadingStats(
    /** Total loaded classes reported at the end of the recording. */
    val loadedClassCount: Long,
    /** Total unloaded classes reported at the end of the recording. */
    val unloadedClassCount: Long,
    /** Classes loaded during the recording (last minus first sample). */
    val loadedDuringRecording: Long,
)

/** The analysed result of a single recording. */
data class ProfileResult(
    val pid: Long?,
    val mainClass: String?,
    val durationMs: Long,
    val totalSamples: Int,
    val hotMethods: List<HotMethod>,
    val hotThreads: List<HotThread>,
    /** Per-thread hot-method breakdowns for `--thread`; empty unless captured. */
    val threadBreakdowns: List<ThreadBreakdown> = emptyList(),
    val gc: GcStats,
    val collapsed: List<CollapsedStack>,
    /** Allocation profile, or null when the recording contains no allocation events. */
    val allocation: AllocationStats?,
    /** Class-loading stats, or null when the recording contains no such events. */
    val classLoading: ClassLoadingStats?,
    /** Thread wait/park/sleep analysis for `report --waits`; null when no wait events were recorded. */
    val waits: WaitStats? = null,
    /** Post-GC heap trend (leak signal), or null when no `jdk.GCHeapSummary` events were recorded. */
    val heapTrend: HeapTrend? = null,
    val recordingPath: String?,
    /** Whether this recording was salvaged from a dead target and may be incomplete. */
    val partial: Boolean = false,
    /** Confidence caveats about the data (e.g. low sample count, idle target). */
    val warnings: List<String> = emptyList(),
    /** Actionable, data-driven observations phrased as one-liners. */
    val hints: List<String> = emptyList(),
)
