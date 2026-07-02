package io.github.takahirom.clijvm

import io.github.takahirom.clijvm.analysis.AllocationSite
import io.github.takahirom.clijvm.analysis.AllocationStats
import io.github.takahirom.clijvm.analysis.ClassLoadingStats
import io.github.takahirom.clijvm.analysis.GcStats
import io.github.takahirom.clijvm.analysis.HotMethod
import io.github.takahirom.clijvm.analysis.HotThread
import io.github.takahirom.clijvm.analysis.HeapTrend
import io.github.takahirom.clijvm.analysis.HeapTrendDirection
import io.github.takahirom.clijvm.analysis.InsightRules
import io.github.takahirom.clijvm.analysis.ProfileResult
import io.github.takahirom.clijvm.analysis.ThreadWait
import io.github.takahirom.clijvm.analysis.WaitStats
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InsightRulesTest {

    private fun baseline(
        durationMs: Long = 30_000,
        totalSamples: Int = 1000,
        gc: GcStats = GcStats(0, 0.0, 0.0),
        hotMethods: List<HotMethod> = emptyList(),
        hotThreads: List<HotThread> = listOf(HotThread("main", 100.0, 1000)),
        allocation: AllocationStats? = null,
        classLoading: ClassLoadingStats? = null,
        partial: Boolean = false,
    ) = ProfileResult(
        pid = 1,
        mainClass = "T",
        durationMs = durationMs,
        totalSamples = totalSamples,
        hotMethods = hotMethods,
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
    fun `idle warning blames GC when GC dominates a low-sample recording`() {
        // 58 samples over 18s = idle-rate, but GC ate 40% of the wall clock.
        val insights = InsightRules.derive(
            baseline(
                totalSamples = 58,
                durationMs = 18_000,
                gc = GcStats(count = 50, totalPauseMs = 7_200.0, maxPauseMs = 400.0),
            ),
        )
        assertTrue(insights.warnings.any { it.contains("Low sample rate is likely due to GC pauses") }, "${insights.warnings}")
        assertFalse(insights.warnings.any { it.contains("mostly idle") }, "${insights.warnings}")
    }

    @Test
    fun `idle warning blames idleness when GC is negligible and no thread dominates`() {
        // Spread samples so no single thread is >= 70%.
        val insights = InsightRules.derive(
            baseline(
                totalSamples = 58, durationMs = 18_000,
                hotThreads = listOf(HotThread("a", 40.0, 23), HotThread("b", 35.0, 20), HotThread("c", 25.0, 15)),
            ),
        )
        assertTrue(insights.warnings.any { it.contains("mostly idle") }, "${insights.warnings}")
        assertFalse(insights.warnings.any { it.contains("due to GC pauses") }, "${insights.warnings}")
        assertFalse(insights.warnings.any { it.contains("helper threads") }, "${insights.warnings}")
    }

    @Test
    fun `idle warning blames a busy single thread when one dominates`() {
        val insights = InsightRules.derive(
            baseline(
                totalSamples = 58, durationMs = 18_000,
                hotThreads = listOf(HotThread("SDK 35 Main Thread", 98.5, 57), HotThread("DefaultDispatcher", 1.5, 1)),
            ),
        )
        assertTrue(insights.warnings.any { it.contains("SDK 35 Main Thread") && it.contains("helper threads") }, "${insights.warnings}")
        assertFalse(insights.warnings.any { it.contains("mostly idle") }, "${insights.warnings}")
    }

    @Test
    fun `GC explanation takes precedence over a busy thread`() {
        val insights = InsightRules.derive(
            baseline(
                totalSamples = 58, durationMs = 18_000,
                gc = GcStats(count = 50, totalPauseMs = 7_200.0, maxPauseMs = 400.0),
                hotThreads = listOf(HotThread("SDK 35 Main Thread", 98.5, 57)),
            ),
        )
        assertTrue(insights.warnings.any { it.contains("due to GC pauses") }, "${insights.warnings}")
        assertFalse(insights.warnings.any { it.contains("helper threads") }, "${insights.warnings}")
    }

    @Test
    fun `hints on significant off-CPU wait time`() {
        val waits = WaitStats(
            threads = listOf(
                ThreadWait("pool-1", parkedMs = 20_000.0, monitorWaitMs = 0.0, sleepMs = 0.0, parkEvents = 100, monitorWaitEvents = 0, sleepEvents = 0, topBlockers = emptyList(), stack = emptyList()),
            ),
            totalWaitMs = 20_000.0,
        )
        val insights = InsightRules.derive(baseline(durationMs = 30_000).copy(waits = waits))
        assertTrue(insights.hints.any { it.contains("off-CPU") && it.contains("report --waits") }, "${insights.hints}")
    }

    @Test
    fun `hints on coverage agent overhead`() {
        val insights = InsightRules.derive(
            baseline(
                hotMethods = listOf(
                    HotMethod("org.jacoco.agent.rt.internal.Offline.getProbes", 12.0, 120, emptyList()),
                    HotMethod("com.example.Foo.bar", 3.0, 30, emptyList()),
                ),
            ),
        )
        assertTrue(insights.hints.any { it.contains("Coverage agent overhead") }, "${insights.hints}")
    }

    @Test
    fun `hints on image encoding workload`() {
        val insights = InsightRules.derive(
            baseline(
                hotMethods = listOf(
                    HotMethod("com.example.Encoder.write", 8.0, 80, listOf("javax.imageio.ImageIO.write")),
                ),
            ),
        )
        assertTrue(insights.hints.any { it.contains("Image encoding accounts for") }, "${insights.hints}")
    }

    @Test
    fun `no coverage or image hint when those frames are absent`() {
        val insights = InsightRules.derive(
            baseline(hotMethods = listOf(HotMethod("com.example.Foo.bar", 90.0, 900, emptyList()))),
        )
        assertFalse(insights.hints.any { it.contains("Coverage agent") || it.contains("Image encoding") }, "${insights.hints}")
    }

    @Test
    fun `hints on growing post-GC heap`() {
        val trend = HeapTrend(gcCount = 20, firstThirdAvgBytes = 120L * 1024 * 1024, lastThirdAvgBytes = 340L * 1024 * 1024, minBytes = 100L * 1024 * 1024, maxBytes = 360L * 1024 * 1024, direction = HeapTrendDirection.GROWING)
        val insights = InsightRules.derive(baseline().copy(heapTrend = trend))
        assertTrue(insights.hints.any { it.contains("Post-GC heap grew") && it.contains("possible leak") }, "${insights.hints}")
    }

    @Test
    fun `stable post-GC heap produces no leak hint`() {
        val trend = HeapTrend(gcCount = 20, firstThirdAvgBytes = 120L * 1024 * 1024, lastThirdAvgBytes = 122L * 1024 * 1024, minBytes = 118L * 1024 * 1024, maxBytes = 130L * 1024 * 1024, direction = HeapTrendDirection.STABLE)
        val insights = InsightRules.derive(baseline().copy(heapTrend = trend))
        assertFalse(insights.hints.any { it.contains("Post-GC heap grew") }, "${insights.hints}")
    }

    @Test
    fun `partial recordings are warned about`() {
        val insights = InsightRules.derive(baseline(partial = true))
        assertTrue(insights.warnings.any { it.contains("salvaged") }, "${insights.warnings}")
    }
}
