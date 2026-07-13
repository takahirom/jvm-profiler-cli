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

    /** If one thread holds at least this share of CPU samples, the process isn't idle — it's single-threaded. */
    const val BUSY_THREAD_SHARE_PCT = 70.0

    /** GC pause share of wall-clock time considered worth flagging. */
    const val GC_PAUSE_SHARE = 0.10

    /** Absolute blocked time (ms) on one monitor above which lock contention is worth flagging. */
    const val LOCK_CONTENTION_MS = 1000.0

    /** Blocked-time share of wall-clock time above which lock contention is worth flagging. */
    const val LOCK_CONTENTION_DURATION_SHARE = 0.10

    /** Frame markers that indicate Robolectric sandbox class-loading work. */
    private val SANDBOX_FRAME_MARKERS = listOf("SandboxClassLoader", "org.robolectric")

    /** Matches Robolectric per-SDK worker threads such as "SDK 33 Main Thread". */
    private val SDK_THREAD_REGEX = Regex("""SDK\s*\d+""")

    /** Wait-stack frames of UI idle-synchronization loops (Compose/Espresso/Robolectric loopers). */
    private val IDLE_WAIT_FRAME_MARKERS = listOf(
        "waitForIdle", "ComposeIdlingResource", "IdlingResourceRegistry", "IdlingRegistry",
        "ComposeUiTest", "androidx.test.espresso.base.UiControllerImpl", "ShadowLooper.idle",
        "ShadowPausedLooper",
    )

    /** Idle-wait share of wall-clock time above which the idle-wait hint fires. */
    const val IDLE_WAIT_SHARE = 0.25

    /** Frame markers of coverage-agent instrumentation (JaCoCo). */
    private val COVERAGE_FRAME_MARKERS = listOf("org.jacoco", "CRC64", "Instrumenter")

    /** Frame markers of image encoding, typical of screenshot-testing workloads. */
    private val IMAGE_ENCODING_FRAME_MARKERS = listOf(
        "javax.imageio", "BufferedImage", "WebP", "PNGImageWriter", "PNGImageEncoder",
        "NeuQuant", "AnimatedGifEncoder",
    )

    /** Self% share of hot methods above which a workload signature is worth flagging. */
    const val HOTSPOT_SHARE_PCT = 5.0

    /**
     * Allocation sites sampled from fewer than this many events carry a low-confidence caveat: the
     * extrapolated byte figure rests on too few samples to trust. Shared with the renderer.
     */
    const val LOW_ALLOCATION_CONFIDENCE_EVENTS = 5

    /** Class/frame markers of the CoroutineId that debug mode threads through allocations. */
    private val COROUTINE_ID_MARKERS = listOf("kotlinx.coroutines.CoroutineId", "CoroutineId")

    /** Frame markers of Robolectric native-runtime font copying (done per sandbox creation). */
    private val FONT_COPY_MARKERS = listOf("maybeCopyFonts", "DefaultNativeRuntimeLoader")

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

        // The headline allocation site is the one a reader trusts most; warn when it rests on too
        // few sampled events for its extrapolated byte figure to be reliable.
        result.allocation?.topSites?.firstOrNull()?.let { top ->
            if (top.events < LOW_ALLOCATION_CONFIDENCE_EVENTS) {
                val unit = if (top.events == 1) "event" else "events"
                warnings += "Top allocation site is extrapolated from only ${top.events} sampled $unit; " +
                    "treat its byte figure as rough."
            }
        }

        val gcShare = if (result.durationMs > 0) result.gc.totalPauseMs / result.durationMs else 0.0

        val seconds = result.durationMs / 1000.0
        if (seconds > 0 && result.totalSamples > 0) {
            val samplesPerSec = result.totalSamples / seconds
            if (samplesPerSec < IDLE_SAMPLES_PER_SEC) {
                val topThread = result.hotThreads.maxByOrNull { it.cpuPct }
                // Precedence: GC pauses (samples can't fire during STW) > a single busy thread
                // (process is single-threaded, not idle) > plain idle.
                warnings += when {
                    gcShare >= GC_PAUSE_SHARE -> String.format(
                        Locale.US,
                        "Low sample rate is likely due to GC pauses (GC accounted for %.0f%% of the " +
                            "recording), not an idle target.",
                        gcShare * 100,
                    )
                    topThread != null && topThread.cpuPct >= BUSY_THREAD_SHARE_PCT -> String.format(
                        Locale.US,
                        "Process-wide sample rate is low (~%.1f/s), but '%s' is busy (%.0f%% of samples) — " +
                            "the idle time is in helper threads.",
                        samplesPerSec, topThread.name, topThread.cpuPct,
                    )
                    else -> String.format(
                        Locale.US,
                        "Target looks mostly idle (~%.1f CPU samples/sec); hotspots may not reflect real work.",
                        samplesPerSec,
                    )
                }
            }
        }

        // --- GC pressure hint ---
        if (gcShare >= GC_PAUSE_SHARE) {
            hints += String.format(
                Locale.US,
                "GC pauses account for %.0f%% of the recording (%.0f ms); consider heap sizing " +
                    "or reducing allocation.",
                gcShare * 100,
                result.gc.totalPauseMs,
            )
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

        // --- Robolectric native-runtime font copying (from allocation hot paths) ---
        val fontCopyInAllocation = result.allocation?.topSites.orEmpty().any { site ->
            site.stack.any { frame -> FONT_COPY_MARKERS.any { frame.contains(it) } }
        }
        if (fontCopyInAllocation) {
            hints += "Robolectric native runtime font copying appears in allocation hot paths " +
                "(maybeCopyFonts); fonts are copied per sandbox creation — reducing sandbox rebuilds " +
                "(@Config spread) avoids it."
        }

        // --- kotlinx.coroutines debug-mode cost showing up in allocation hot paths ---
        // Debug mode being merely ON (thread names carry @coroutine#N) is not worth a hint: it
        // exists to make coroutine failures diagnosable, which is exactly what tests want. Only
        // flag it when its CoroutineId bookkeeping actually surfaces in the measured hot paths,
        // and present the trade-off rather than a one-sided "turn it off".
        val coroutineDebugCost = result.allocation?.topSites.orEmpty().any { site ->
            COROUTINE_ID_MARKERS.any { m -> site.className.contains(m) || site.stack.any { it.contains(m) } }
        }
        if (coroutineDebugCost) {
            hints += "kotlinx.coroutines debug mode's CoroutineId bookkeeping appears in allocation hot " +
                "paths (debug mode is auto-enabled by -ea in test workers). It buys better coroutine " +
                "stack traces on failure, so this is a trade-off: only if allocation/GC is your " +
                "bottleneck, try -Dkotlinx.coroutines.debug=off."
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

        // --- coverage-agent overhead (JaCoCo) in hot methods ---
        val coverageShare = hotMethodShareMatching(result, COVERAGE_FRAME_MARKERS)
        if (coverageShare >= HOTSPOT_SHARE_PCT) {
            hints += String.format(
                Locale.US,
                "Coverage agent overhead detected (~%.0f%% of samples); consider disabling coverage " +
                    "when profiling.",
                coverageShare,
            )
        }

        // --- image encoding (screenshot testing) in hot methods ---
        val imageShare = hotMethodShareMatching(result, IMAGE_ENCODING_FRAME_MARKERS)
        if (imageShare >= HOTSPOT_SHARE_PCT) {
            hints += String.format(
                Locale.US,
                "Image encoding accounts for ~%.0f%% of samples (screenshot testing workload).",
                imageShare,
            )
        }

        // --- post-GC heap growth (possible leak) ---
        result.heapTrend?.let { trend ->
            if (trend.direction == HeapTrendDirection.GROWING) {
                // In a test worker the classic cause is state leaking between tests, so name it.
                val testContext = if (looksLikeTestWorker(result)) {
                    " In a test run this usually means state leaks between tests " +
                        "(unclosed ActivityScenario, static references)."
                } else {
                    ""
                }
                hints += "Post-GC heap grew from ~${formatBytes(trend.firstThirdAvgBytes)} to " +
                    "~${formatBytes(trend.lastThirdAvgBytes)} over the recording (${trend.gcCount} GCs); " +
                    "possible leak or growing retained set.$testContext"
            }
            // Flat/shrinking heap is surfaced as a positive digest signal by the renderer, not a hint.
        }

        // --- UI idle-waiting in tests (Compose/Espresso waitForIdle, ShadowLooper idling) ---
        // The most common user-fixable cause of slow UI tests: an animation (often infinite)
        // keeps the UI non-idle, so every waitForIdle burns its full timeout.
        val idleWaitMs = idleWaitMillis(result)
        if (result.durationMs > 0 && idleWaitMs >= IDLE_WAIT_SHARE * result.durationMs) {
            hints += String.format(
                Locale.US,
                "Tests spent ~%.1fs waiting for the UI to become idle (waitForIdle/Espresso idling). " +
                    "Long or infinite animations are the usual cause — disable animations in tests or " +
                    "check rememberInfiniteTransition/repeating animators.",
                idleWaitMs / 1000.0,
            )
        }

        // --- significant non-CPU (wait/park/sleep) time ---
        result.waits?.let { waits ->
            // Ignore housekeeping/idle-worker threads so a parked Cleaner doesn't look like a bottleneck.
            val maxThreadWaitMs = waits.threads
                .filterNot { OffCpuNoise.isSuppressed(it) }
                .maxOfOrNull { it.totalMs } ?: 0.0
            // Flag when at least one thread spent half the recording parked/waiting.
            if (result.durationMs > 0 && maxThreadWaitMs >= 0.5 * result.durationMs) {
                hints += String.format(
                    Locale.US,
                    "Threads spent significant time off-CPU (up to %.1fs parked/waiting on one thread); " +
                        "run 'report --waits' to see where non-CPU time went.",
                    maxThreadWaitMs / 1000.0,
                )
            }
        }

        // --- lock contention on a synchronized monitor ---
        if (isLockContentionSignificant(result)) {
            val top = result.lockContention!!.monitors.first()
            val holder = top.ownerThread?.let { " (held mostly by '$it')" } ?: ""
            // Estimated figures come from thread-state sampling (a monopolized lock emits no JFR
            // enter events); flag them so the duration isn't read as exact.
            val sampled = if (top.estimated) " (blocked time sampled from thread states)" else ""
            hints += String.format(
                Locale.US,
                "Lock contention: threads blocked ~%.1fs total on the '%s' monitor%s; " +
                    "see 'report --waits' for the blocked stacks.%s",
                top.totalBlockedMs / 1000.0, top.className, holder, sampled,
            )
        }

        // --- single-thread bottleneck ---
        // Independent of the low-sample-rate warning path: this fires even at a healthy sample rate,
        // where the process is clearly busy but the work is not parallelised.
        val busiestThread = result.hotThreads.maxByOrNull { it.cpuPct }
        if (result.totalSamples >= LOW_SAMPLE_COUNT &&
            busiestThread != null && busiestThread.cpuPct >= BUSY_THREAD_SHARE_PCT
        ) {
            hints += String.format(
                Locale.US,
                "Execution is effectively single-threaded: '%s' holds %.0f%% of samples. If wall-clock " +
                    "is the problem, look for serialization points (locks, unparallelized work).",
                busiestThread.name, busiestThread.cpuPct,
            )
        }

        return Insights(warnings, hints)
    }

    /**
     * True when the wall-clock is dominated by off-CPU time: the CPU sampler is nearly idle yet one
     * thread spent at least half the recording parked/waiting. Used by the renderer to switch the
     * default Layer-1 axis to off-CPU. Mirrors the wait/idle conditions used for the hints above.
     */
    fun isOffCpuDominant(result: ProfileResult): Boolean {
        // Zero samples is the strongest off-CPU signal, so only a missing duration disqualifies.
        if (result.durationMs <= 0) return false
        val samplesPerSec = result.totalSamples / (result.durationMs / 1000.0)
        val waits = result.waits ?: return false
        // Only real (non-housekeeping/idle) threads count toward the trigger, otherwise a JVM whose
        // Cleaner/Finalizer parks for the whole run looks off-CPU-dominated when nothing waits.
        val maxThreadWaitMs = waits.threads
            .filterNot { OffCpuNoise.isSuppressed(it) }
            .maxOfOrNull { it.totalMs } ?: 0.0
        return samplesPerSec < IDLE_SAMPLES_PER_SEC && maxThreadWaitMs >= 0.5 * result.durationMs
    }

    /** True when the profile looks like a test worker (Robolectric SDK threads or a Gradle test worker). */
    private fun looksLikeTestWorker(result: ProfileResult): Boolean =
        result.hotThreads.any { SDK_THREAD_REGEX.containsMatchIn(it.name) } ||
            result.mainClass?.contains("GradleWorkerMain") == true

    /**
     * Total off-CPU time of threads whose wait stack sits in a UI idle-synchronization loop.
     * Zero when no wait data was recorded.
     */
    private fun idleWaitMillis(result: ProfileResult): Double =
        result.waits?.threads.orEmpty()
            .filter { t -> t.stack.any { frame -> IDLE_WAIT_FRAME_MARKERS.any { frame.contains(it) } } }
            .sumOf { it.totalMs }

    /** True when contention on the busiest monitor is large enough to surface as a hint/section. */
    fun isLockContentionSignificant(result: ProfileResult): Boolean {
        val top = result.lockContention?.monitors?.firstOrNull() ?: return false
        return top.totalBlockedMs >= LOCK_CONTENTION_MS ||
            (result.durationMs > 0 && top.totalBlockedMs >= LOCK_CONTENTION_DURATION_SHARE * result.durationMs)
    }

    /** Compact binary-scaled byte label (e.g. `120 MB`) for hint text; mirrors the renderer's format. */
    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024
        var index = 0
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024
            index++
        }
        return String.format(Locale.US, "%.1f %s", value, units[index])
    }

    /**
     * Summed self% of hot methods whose name or representative stack contains any of [markers].
     * Each hot method is counted once even if several markers match.
     */
    private fun hotMethodShareMatching(result: ProfileResult, markers: List<String>): Double =
        result.hotMethods
            .filter { m ->
                markers.any { marker ->
                    m.method.contains(marker) || m.stack.any { it.contains(marker) }
                }
            }
            .sumOf { it.selfPct }
}
