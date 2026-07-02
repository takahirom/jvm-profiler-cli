package io.github.takahirom.clijvm

import io.github.takahirom.clijvm.analysis.AllocationSite
import io.github.takahirom.clijvm.analysis.AllocationStats
import io.github.takahirom.clijvm.analysis.ClassLoadingStats
import io.github.takahirom.clijvm.analysis.GcStats
import io.github.takahirom.clijvm.analysis.HotThread
import io.github.takahirom.clijvm.analysis.InsightRules
import io.github.takahirom.clijvm.analysis.ProfileResult
import kotlin.test.Test
import kotlin.test.assertTrue

class InsightRulesTest {

    private fun baseline(
        durationMs: Long = 30_000,
        totalSamples: Int = 1000,
        gc: GcStats = GcStats(0, 0.0, 0.0),
        hotThreads: List<HotThread> = listOf(HotThread("main", 100.0, 1000)),
        allocation: AllocationStats? = null,
        classLoading: ClassLoadingStats? = null,
        partial: Boolean = false,
    ) = ProfileResult(
        pid = 1,
        mainClass = "T",
        durationMs = durationMs,
        totalSamples = totalSamples,
        hotMethods = emptyList(),
        hotThreads = hotThreads,
        gc = gc,
        collapsed = emptyList(),
        allocation = allocation,
        classLoading = classLoading,
        recordingPath = null,
        partial = partial,
    )

    @Test
    fun `warns on low sample count`() {
        val insights = InsightRules.derive(baseline(totalSamples = 50, durationMs = 30_000))
        assertTrue(insights.warnings.any { it.contains("Low sample count") }, "${insights.warnings}")
    }

    @Test
    fun `warns when target looks idle`() {
        // 58 samples over 18s ~= 3.2 samples/sec, below the idle threshold.
        val insights = InsightRules.derive(baseline(totalSamples = 58, durationMs = 18_000))
        assertTrue(insights.warnings.any { it.contains("idle") }, "${insights.warnings}")
    }

    @Test
    fun `no confidence warnings for a healthy recording`() {
        val insights = InsightRules.derive(baseline(totalSamples = 2000, durationMs = 30_000))
        assertTrue(insights.warnings.isEmpty(), "${insights.warnings}")
    }

    @Test
    fun `hints when GC dominates`() {
        val insights = InsightRules.derive(
            baseline(durationMs = 10_000, gc = GcStats(count = 20, totalPauseMs = 2000.0, maxPauseMs = 200.0)),
        )
        assertTrue(insights.hints.any { it.contains("GC pauses account for") }, "${insights.hints}")
    }

    @Test
    fun `hints on heavy class loading`() {
        val insights = InsightRules.derive(
            baseline(classLoading = ClassLoadingStats(30_000, 100, 8000)),
        )
        assertTrue(insights.hints.any { it.contains("classes were loaded") }, "${insights.hints}")
    }

    @Test
    fun `hints on Robolectric sandbox allocation`() {
        val allocation = AllocationStats(
            source = "jdk.ObjectAllocationSample",
            totalBytes = 1000,
            totalEvents = 10,
            topSites = listOf(
                AllocationSite(
                    className = "byte[]",
                    bytes = 1000,
                    sharePct = 100.0,
                    events = 10,
                    stack = listOf("java.util.zip.ZipFile.getEntry", "org.robolectric.internal.bytecode.SandboxClassLoader.getByteCode"),
                ),
            ),
        )
        val insights = InsightRules.derive(baseline(allocation = allocation))
        assertTrue(insights.hints.any { it.contains("sandbox") }, "${insights.hints}")
    }

    @Test
    fun `hints on multiple SDK worker threads`() {
        val insights = InsightRules.derive(
            baseline(
                hotThreads = listOf(
                    HotThread("SDK 33 Main Thread", 40.0, 400),
                    HotThread("SDK 34 Main Thread", 35.0, 350),
                    HotThread("main", 25.0, 250),
                ),
            ),
        )
        assertTrue(insights.hints.any { it.contains("multiple Android SDK levels") }, "${insights.hints}")
    }

    @Test
    fun `partial recordings are warned about`() {
        val insights = InsightRules.derive(baseline(partial = true))
        assertTrue(insights.warnings.any { it.contains("salvaged") }, "${insights.warnings}")
    }
}
