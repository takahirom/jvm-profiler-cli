package io.github.takahirom.clijvm.analysis

import java.util.Locale

/** Confidence caveats and actionable hints derived from a [ProfileResult]. */
data class Insights(
    val warnings: List<String>,
    val hints: List<String>,
)

/**
 * Derives [Insights] from an analysed recording using fixed, data-driven heuristics.
 *
 * Kept pure (a function of the already-computed [ProfileResult] fields) so it can be
 * unit-tested with synthetic inputs. The caller merges the result back via `copy`.
 */
object InsightRules {

    /** Below this many CPU samples, self% is statistically noisy. */
    const val LOW_SAMPLE_COUNT = 200

    /** Below this sampling rate (samples/sec), the target looks mostly idle. */
    const val IDLE_SAMPLES_PER_SEC = 20.0

    /** GC pause share of wall-clock time considered worth flagging. */
    const val GC_PAUSE_SHARE = 0.10

    /** Frame markers that indicate Robolectric sandbox class-loading work. */
    private val SANDBOX_FRAME_MARKERS = listOf("SandboxClassLoader", "org.robolectric")

    /** Matches Robolectric per-SDK worker threads such as "SDK 33 Main Thread". */
    private val SDK_THREAD_REGEX = Regex("""SDK\s*\d+""")

    fun derive(result: ProfileResult): Insights {
        val warnings = mutableListOf<String>()
        val hints = mutableListOf<String>()

        if (result.partial) {
            warnings += "This recording was salvaged from a target that exited; it may be incomplete."
        }

        // --- confidence warnings ---
        if (result.totalSamples in 1 until LOW_SAMPLE_COUNT) {
            warnings += "Low sample count (${result.totalSamples} CPU samples): self% values are " +
                "statistically noisy. Increase --duration for stabler hotspots."
        } else if (result.totalSamples == 0) {
            warnings += "No CPU samples were captured; hotspot data is unavailable."
        }

        val seconds = result.durationMs / 1000.0
        if (seconds > 0 && result.totalSamples > 0) {
            val samplesPerSec = result.totalSamples / seconds
            if (samplesPerSec < IDLE_SAMPLES_PER_SEC) {
                warnings += String.format(
                    Locale.US,
                    "Target looks mostly idle (~%.1f CPU samples/sec); hotspots may not reflect real work.",
                    samplesPerSec,
                )
            }
        }

        // --- GC pressure hint ---
        if (result.durationMs > 0) {
            val gcShare = result.gc.totalPauseMs / result.durationMs
            if (gcShare >= GC_PAUSE_SHARE) {
                hints += String.format(
                    Locale.US,
                    "GC pauses account for %.0f%% of the recording (%.0f ms); consider heap sizing " +
                        "or reducing allocation.",
                    gcShare * 100,
                    result.gc.totalPauseMs,
                )
            }
        }

        // --- class-loading hint ---
        result.classLoading?.let { cl ->
            if (cl.loadedDuringRecording >= 2000) {
                hints += "${cl.loadedDuringRecording} classes were loaded during the recording; " +
                    "class loading may be a bottleneck."
            }
        }

        // --- Robolectric sandbox hint (from allocation hot paths) ---
        val sandboxInAllocation = result.allocation?.topSites.orEmpty().any { site ->
            site.stack.any { frame -> SANDBOX_FRAME_MARKERS.any { frame.contains(it) } }
        }
        if (sandboxInAllocation) {
            hints += "Robolectric sandbox class loading appears in allocation hot paths; sandbox setup " +
                "may dominate. Check @Config sdk spread (each SDK level rebuilds its own sandbox)."
        }

        // --- multiple Android SDK worker threads ---
        val sdkThreads = result.hotThreads
            .map { it.name }
            .filter { SDK_THREAD_REGEX.containsMatchIn(it) }
            .distinct()
        if (sdkThreads.size >= 2) {
            hints += "Tests fan out across multiple Android SDK levels (${sdkThreads.joinToString(", ")}); " +
                "each builds its own sandbox. Narrow @Config sdk to reduce setup cost."
        }

        return Insights(warnings, hints)
    }
}
