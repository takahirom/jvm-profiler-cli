package io.github.takahirom.clijvm

import io.github.takahirom.clijvm.analysis.AllocationSite
import io.github.takahirom.clijvm.analysis.AllocationStats
import io.github.takahirom.clijvm.analysis.ClassLoadingStats
import io.github.takahirom.clijvm.analysis.ContendedMonitor
import io.github.takahirom.clijvm.analysis.GcStats
import io.github.takahirom.clijvm.analysis.HotMethod
import io.github.takahirom.clijvm.analysis.HotThread
import io.github.takahirom.clijvm.analysis.HeapTrend
import io.github.takahirom.clijvm.analysis.HeapTrendDirection
import io.github.takahirom.clijvm.analysis.InsightRules
import io.github.takahirom.clijvm.analysis.LockContentionStats
import io.github.takahirom.clijvm.analysis.ProfileResult
import io.github.takahirom.clijvm.analysis.ThreadWait
import io.github.takahirom.clijvm.analysis.WaitStats
import kotlin.test.Test
import kotlin.test.assertEquals
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

    // ---- lock contention ---------------------------------------------------------------------

    private fun contention(
        className: String = "com.example.SharedLedger",
        totalBlockedMs: Double = 12_400.0,
        ownerThread: String? = "db-writer",
    ) = LockContentionStats(
        monitors = listOf(
            ContendedMonitor(
                className = className,
                totalBlockedMs = totalBlockedMs,
                events = 200,
                topBlockedThreads = listOf("worker-1", "worker-2"),
                ownerThread = ownerThread,
                stack = listOf("com.example.SharedLedger.post"),
            ),
        ),
        totalBlockedMs = totalBlockedMs,
    )

    @Test
    fun `hints on lock contention naming the monitor and the holding thread`() {
        val insights = InsightRules.derive(baseline(durationMs = 30_000).copy(lockContention = contention()))
        assertTrue(
            insights.hints.any {
                it.contains("Lock contention") && it.contains("com.example.SharedLedger") &&
                    it.contains("held mostly by 'db-writer'") && it.contains("report --waits")
            },
            "${insights.hints}",
        )
    }

    @Test
    fun `lock contention hint omits the holder clause when the owner is unknown`() {
        val insights = InsightRules.derive(baseline().copy(lockContention = contention(ownerThread = null)))
        val hint = insights.hints.first { it.contains("Lock contention") }
        assertTrue(hint.contains("com.example.SharedLedger"), hint)
        assertFalse(hint.contains("held mostly by"), hint)
    }

    @Test
    fun `lock contention hint fires at the absolute threshold but not just below it`() {
        // 30s recording: 10% share is 3000 ms, so only the 1000 ms absolute floor is in play here.
        val below = InsightRules.derive(
            baseline(durationMs = 30_000).copy(lockContention = contention(totalBlockedMs = 999.0)),
        )
        assertFalse(below.hints.any { it.contains("Lock contention") }, "${below.hints}")
        val at = InsightRules.derive(
            baseline(durationMs = 30_000).copy(lockContention = contention(totalBlockedMs = 1000.0)),
        )
        assertTrue(at.hints.any { it.contains("Lock contention") }, "${at.hints}")
    }

    @Test
    fun `lock contention hint fires on the duration-share threshold below the absolute floor`() {
        // 500 ms blocked is under the 1000 ms floor but is 10% of a 5s recording.
        val insights = InsightRules.derive(
            baseline(durationMs = 5_000).copy(lockContention = contention(totalBlockedMs = 500.0)),
        )
        assertTrue(insights.hints.any { it.contains("Lock contention") }, "${insights.hints}")
    }

    // ---- single-thread bottleneck ------------------------------------------------------------

    @Test
    fun `hints when execution is effectively single-threaded`() {
        val insights = InsightRules.derive(
            baseline(
                totalSamples = 1000,
                hotThreads = listOf(HotThread("worker", 95.0, 950), HotThread("other", 5.0, 50)),
            ),
        )
        assertTrue(
            insights.hints.any {
                it.contains("effectively single-threaded") && it.contains("worker") && it.contains("95%")
            },
            "${insights.hints}",
        )
    }

    @Test
    fun `no single-thread hint when work is spread across threads`() {
        val insights = InsightRules.derive(
            baseline(hotThreads = listOf(HotThread("a", 40.0, 400), HotThread("b", 35.0, 350), HotThread("c", 25.0, 250))),
        )
        assertFalse(insights.hints.any { it.contains("effectively single-threaded") }, "${insights.hints}")
    }

    @Test
    fun `no single-thread hint below the low sample count`() {
        val insights = InsightRules.derive(
            baseline(totalSamples = 100, hotThreads = listOf(HotThread("worker", 99.0, 99))),
        )
        assertFalse(insights.hints.any { it.contains("effectively single-threaded") }, "${insights.hints}")
    }

    @Test
    fun `single-thread hint coexists with the idle helper-threads warning`() {
        // 250 samples over 30s = ~8/s (idle rate) with one thread at 98%: both signals apply and
        // agree (busy single thread, idle process), so they must not contradict each other.
        val insights = InsightRules.derive(
            baseline(
                totalSamples = 250, durationMs = 30_000,
                hotThreads = listOf(HotThread("worker", 98.0, 245), HotThread("other", 2.0, 5)),
            ),
        )
        assertTrue(insights.warnings.any { it.contains("helper threads") }, "${insights.warnings}")
        assertTrue(insights.hints.any { it.contains("effectively single-threaded") }, "${insights.hints}")
    }

    // ---- UI idle-wait hint --------------------------------------------------------------------

    private fun idleWait(thread: String, totalMs: Double, frame: String) = ThreadWait(
        thread = thread, parkedMs = totalMs, monitorWaitMs = 0.0, sleepMs = 0.0,
        parkEvents = 10, monitorWaitEvents = 0, sleepEvents = 0,
        topBlockers = emptyList(),
        stack = listOf("jdk.internal.misc.Unsafe.park", frame, "org.junit.runners.ParentRunner.run"),
    )

    @Test
    fun `idle-wait hint fires when waitForIdle waits dominate`() {
        // 12s of a 30s recording (40%) parked inside Compose's waitForIdle loop.
        val waits = WaitStats(
            threads = listOf(idleWait("Test worker", 12_000.0, "androidx.compose.ui.test.ComposeUiTest.waitForIdle")),
            totalWaitMs = 12_000.0,
        )
        val insights = InsightRules.derive(baseline().copy(waits = waits))
        assertTrue(insights.hints.any { it.contains("waiting for the UI to become idle") }, "${insights.hints}")
    }

    @Test
    fun `no idle-wait hint below the share threshold or for non-idle stacks`() {
        // 3s of 30s (10%) is below IDLE_WAIT_SHARE.
        val small = WaitStats(
            threads = listOf(idleWait("Test worker", 3_000.0, "ComposeIdlingResource.isIdleNow")),
            totalWaitMs = 3_000.0,
        )
        assertFalse(
            InsightRules.derive(baseline().copy(waits = small))
                .hints.any { it.contains("waiting for the UI to become idle") },
        )
        // Long waits without idle-synchronization frames must not fire it either.
        val unrelated = WaitStats(
            threads = listOf(idleWait("db-writer", 20_000.0, "java.util.concurrent.LinkedBlockingQueue.take")),
            totalWaitMs = 20_000.0,
        )
        assertFalse(
            InsightRules.derive(baseline().copy(waits = unrelated))
                .hints.any { it.contains("waiting for the UI to become idle") },
        )
    }

    // ---- test-context leak hint ---------------------------------------------------------------

    private val growingHeap = HeapTrend(
        direction = HeapTrendDirection.GROWING,
        gcCount = 40,
        firstThirdAvgBytes = 100L shl 20,
        lastThirdAvgBytes = 400L shl 20,
        minBytes = 90L shl 20,
        maxBytes = 420L shl 20,
    )

    @Test
    fun `growing heap in a test worker names leaks between tests`() {
        val insights = InsightRules.derive(
            baseline(hotThreads = listOf(HotThread("SDK 33 Main Thread", 90.0, 900)))
                .copy(waits = null, heapTrend = growingHeap),
        )
        assertTrue(insights.hints.any { it.contains("state leaks between tests") }, "${insights.hints}")
    }

    @Test
    fun `growing heap outside a test context stays generic`() {
        val insights = InsightRules.derive(baseline().copy(heapTrend = growingHeap))
        assertTrue(insights.hints.any { it.contains("possible leak") }, "${insights.hints}")
        assertFalse(insights.hints.any { it.contains("state leaks between tests") }, "${insights.hints}")
    }

    // ---- kotlinx.coroutines debug-mode hint ---------------------------------------------------

    private fun allocationOf(className: String, events: Int, stack: List<String>) = AllocationStats(
        source = "jdk.ObjectAllocationSample",
        totalBytes = 1000,
        totalEvents = events,
        topSites = listOf(AllocationSite(className, 1000, 100.0, events, stack)),
    )

    @Test
    fun `hints on coroutine debug cost in allocation hot paths, with the trade-off stated`() {
        val insights = InsightRules.derive(
            baseline(allocation = allocationOf("kotlinx.coroutines.CoroutineId", events = 20, stack = listOf("kotlinx.coroutines.CoroutineId.updateThreadContext"))),
        )
        val hint = insights.hints.find { it.contains("kotlinx.coroutines debug mode") }
        assertTrue(hint != null, "${insights.hints}")
        assertTrue(hint.contains("trade-off"), hint)
        assertTrue(hint.contains("only if allocation/GC is your bottleneck"), hint)
    }

    @Test
    fun `debug mode being merely on does not fire the coroutine hint`() {
        // @coroutine#N thread names prove debug mode is enabled, not that it costs anything —
        // and in tests its diagnosability is usually worth keeping.
        val insights = InsightRules.derive(
            baseline(hotThreads = listOf(HotThread("DefaultDispatcher-worker-1 @coroutine#7", 80.0, 800))),
        )
        assertFalse(insights.hints.any { it.contains("kotlinx.coroutines debug mode") }, "${insights.hints}")
    }

    @Test
    fun `no coroutine debug hint without any signal`() {
        val insights = InsightRules.derive(baseline(hotThreads = listOf(HotThread("DefaultDispatcher-worker-1", 80.0, 800))))
        assertFalse(insights.hints.any { it.contains("kotlinx.coroutines debug mode") }, "${insights.hints}")
    }

    // ---- Robolectric font-copy hint -----------------------------------------------------------

    @Test
    fun `hints on Robolectric font copying in allocation hot paths`() {
        val insights = InsightRules.derive(
            baseline(allocation = allocationOf("byte[]", events = 30, stack = listOf("org.robolectric.nativeruntime.DefaultNativeRuntimeLoader.maybeCopyFonts"))),
        )
        assertTrue(
            insights.hints.any { it.contains("font copying") && it.contains("maybeCopyFonts") },
            "${insights.hints}",
        )
    }

    // ---- low-confidence top allocation site ---------------------------------------------------

    @Test
    fun `warns when the top allocation site rests on too few sampled events`() {
        val insights = InsightRules.derive(baseline(allocation = allocationOf("byte[]", events = 1, stack = emptyList())))
        assertTrue(
            insights.warnings.any { it.contains("extrapolated from only 1 sampled event") && it.contains("rough") },
            "${insights.warnings}",
        )
    }

    @Test
    fun `no low-confidence warning when the top site has enough events`() {
        val insights = InsightRules.derive(baseline(allocation = allocationOf("byte[]", events = 25, stack = emptyList())))
        assertFalse(insights.warnings.any { it.contains("extrapolated from only") }, "${insights.warnings}")
    }

    // ---- off-CPU trigger ignores housekeeping/idle threads ------------------------------------

    @Test
    fun `off-CPU dominant is not triggered by housekeeping or idle threads alone`() {
        val waits = WaitStats(
            threads = listOf(
                ThreadWait("Cleaner-1", parkedMs = 58_000.0, monitorWaitMs = 0.0, sleepMs = 0.0, parkEvents = 1, monitorWaitEvents = 0, sleepEvents = 0, topBlockers = emptyList(), stack = listOf("jdk.internal.ref.CleanerImpl.run")),
                ThreadWait("pool-1-thread-1", parkedMs = 58_000.0, monitorWaitMs = 0.0, sleepMs = 0.0, parkEvents = 1, monitorWaitEvents = 0, sleepEvents = 0, topBlockers = emptyList(), stack = listOf("java.util.concurrent.ThreadPoolExecutor.getTask")),
            ),
            totalWaitMs = 116_000.0,
        )
        val result = baseline(totalSamples = 60, durationMs = 60_000).copy(waits = waits)
        assertFalse(InsightRules.isOffCpuDominant(result))
        assertFalse(insightsOffCpuHint(result), "off-CPU hint should not fire for housekeeping/idle only")
    }

    @Test
    fun `off-CPU dominant is triggered by a genuinely blocked thread`() {
        val waits = WaitStats(
            threads = listOf(
                ThreadWait("Cleaner-1", parkedMs = 58_000.0, monitorWaitMs = 0.0, sleepMs = 0.0, parkEvents = 1, monitorWaitEvents = 0, sleepEvents = 0, topBlockers = emptyList(), stack = listOf("jdk.internal.ref.CleanerImpl.run")),
                ThreadWait("main", parkedMs = 40_000.0, monitorWaitMs = 0.0, sleepMs = 0.0, parkEvents = 1, monitorWaitEvents = 0, sleepEvents = 0, topBlockers = emptyList(), stack = listOf("com.example.Service.awaitResult")),
            ),
            totalWaitMs = 98_000.0,
        )
        val result = baseline(totalSamples = 60, durationMs = 60_000).copy(waits = waits)
        assertTrue(InsightRules.isOffCpuDominant(result))
    }

    private fun insightsOffCpuHint(result: ProfileResult): Boolean =
        InsightRules.derive(result).hints.any { it.contains("off-CPU") && it.contains("report --waits") }
}
