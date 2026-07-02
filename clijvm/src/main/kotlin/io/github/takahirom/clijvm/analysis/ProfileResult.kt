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

/** Garbage collection pause statistics over the recording. */
data class GcStats(
    val count: Int,
    val totalPauseMs: Double,
    val maxPauseMs: Double,
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
    val gc: GcStats,
    val collapsed: List<CollapsedStack>,
    /** Allocation profile, or null when the recording contains no allocation events. */
    val allocation: AllocationStats?,
    /** Class-loading stats, or null when the recording contains no such events. */
    val classLoading: ClassLoadingStats?,
    val recordingPath: String?,
    /** Whether this recording was salvaged from a dead target and may be incomplete. */
    val partial: Boolean = false,
    /** Confidence caveats about the data (e.g. low sample count, idle target). */
    val warnings: List<String> = emptyList(),
    /** Actionable, data-driven observations phrased as one-liners. */
    val hints: List<String> = emptyList(),
)
