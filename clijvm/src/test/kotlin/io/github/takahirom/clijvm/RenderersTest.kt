package io.github.takahirom.clijvm

import io.github.takahirom.clijvm.analysis.AllocationSite
import io.github.takahirom.clijvm.analysis.AllocationStats
import io.github.takahirom.clijvm.analysis.ClassLoadingStats
import io.github.takahirom.clijvm.analysis.CollapsedStack
import io.github.takahirom.clijvm.analysis.GcStats
import io.github.takahirom.clijvm.analysis.HotMethod
import io.github.takahirom.clijvm.analysis.HotThread
import io.github.takahirom.clijvm.analysis.ProfileResult
import io.github.takahirom.clijvm.cli.ReportCommand
import io.github.takahirom.clijvm.cli.checkDrillIndex
import io.github.takahirom.clijvm.render.OutputFormat
import io.github.takahirom.clijvm.render.RenderOptions
import io.github.takahirom.clijvm.render.Renderers
import io.github.takahirom.clijvm.render.ReportView
import com.github.ajalt.clikt.core.CliktError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RenderersTest {
    private val allocation = AllocationStats(
        source = "jdk.ObjectAllocationSample",
        totalBytes = 3_000_000,
        totalEvents = 40,
        topSites = listOf(
            AllocationSite("byte[]", 2_000_000, 66.67, 25, listOf("java.lang.String.<init>", "io.github.takahirom.clijvm.BusyTargetKt.allocateStrings")),
            AllocationSite("java.lang.String", 1_000_000, 33.33, 15, listOf("io.github.takahirom.clijvm.BusyTargetKt.allocateStrings")),
        ),
    )
    private val result = ProfileResult(
        pid = 4242,
        mainClass = "com.example.\"Main\"",
        durationMs = 5000,
        totalSamples = 200,
        hotMethods = listOf(
            HotMethod("io.github.takahirom.clijvm.BusyTargetKt.hotFibonacci", 87.5, 175, listOf("io.github.takahirom.clijvm.BusyTargetKt.hotFibonacci")),
            HotMethod("java.lang.String.length", 12.5, 25, listOf("java.lang.String.length")),
        ),
        hotThreads = listOf(HotThread("main", 100.0, 200)),
        gc = GcStats(count = 3, totalPauseMs = 12.5, maxPauseMs = 8.0),
        collapsed = listOf(
            CollapsedStack(listOf("main", "hotFibonacci", "hotFibonacci"), 175),
            CollapsedStack(listOf("main", "allocateStrings"), 25),
        ),
        allocation = allocation,
        classLoading = ClassLoadingStats(loadedClassCount = 5000, unloadedClassCount = 10, loadedDuringRecording = 1200),
        recordingPath = "/tmp/rec.jfr",
        warnings = listOf("Low sample count (200 CPU samples): noisy."),
        hints = listOf("GC pauses account for 12% of the recording."),
    )

    @Test
    fun `collapsed format is frames semicolon-joined with count`() {
        val expected = """
            main;hotFibonacci;hotFibonacci 175
            main;allocateStrings 25
        """.trimIndent()
        assertEquals(expected, Renderers.render(result, OutputFormat.COLLAPSED))
    }

    @Test
    fun `json escapes strings and uses locale-independent numbers`() {
        val json = Renderers.render(result, OutputFormat.JSON)
        assertTrue(json.contains("\"pid\": 4242"), json)
        assertTrue(json.contains("\"selfPct\": 87.5"), json)
        // The quote inside the main class name must be escaped.
        assertTrue(json.contains("""com.example.\"Main\""""), json)
        assertTrue(json.contains("\"totalPauseMs\": 12.5"), json)
        assertTrue(json.contains("\"hotMethods\""), json)
    }

    @Test
    fun `json includes the allocation section when present`() {
        val json = Renderers.render(result, OutputFormat.JSON)
        assertTrue(json.contains("\"allocation\""), json)
        assertTrue(json.contains("\"class\": \"byte[]\""), json)
        assertTrue(json.contains("\"bytes\": 2000000"), json)
        assertTrue(json.contains("\"totalBytes\": 3000000"), json)
    }

    @Test
    fun `json omits the allocation section when absent`() {
        val json = Renderers.render(result.copy(allocation = null), OutputFormat.JSON)
        assertFalse(json.contains("\"allocation\""), json)
    }

    @Test
    fun `cpu summary shows hotspots but not allocation`() {
        val summary = Renderers.render(result, OutputFormat.SUMMARY, ReportView.CPU)
        assertTrue(summary.contains("hotFibonacci"), summary)
        assertTrue(summary.contains("87.5%"), summary)
        assertFalse(summary.contains("Top allocation sites"), summary)
    }

    @Test
    fun `memory summary shows allocation sites`() {
        val summary = Renderers.render(result, OutputFormat.SUMMARY, ReportView.MEMORY)
        assertTrue(summary.contains("Top allocation sites"), summary)
        assertTrue(summary.contains("byte[]"), summary)
        assertFalse(summary.contains("Top hot methods"), summary)
    }

    @Test
    fun `full summary shows both sections`() {
        val summary = Renderers.render(result, OutputFormat.SUMMARY, ReportView.FULL)
        assertTrue(summary.contains("Top hot methods"), summary)
        assertTrue(summary.contains("Top allocation sites"), summary)
    }

    @Test
    fun `summary renders warnings, hints, and class loading`() {
        val summary = Renderers.render(result, OutputFormat.SUMMARY, ReportView.CPU)
        assertTrue(summary.contains("Warnings:"), summary)
        assertTrue(summary.contains("Low sample count"), summary)
        assertTrue(summary.contains("Hints:"), summary)
        assertTrue(summary.contains("GC pauses account for"), summary)
        assertTrue(summary.contains("Class loading:"), summary)
        assertTrue(summary.contains("1200"), summary)
    }

    @Test
    fun `json includes warnings, hints, and class loading`() {
        val json = Renderers.render(result, OutputFormat.JSON)
        assertTrue(json.contains("\"warnings\""), json)
        assertTrue(json.contains("\"hints\""), json)
        assertTrue(json.contains("\"classLoading\""), json)
        assertTrue(json.contains("\"loadedDuringRecording\": 1200"), json)
    }

    // ---- Progressive disclosure (layered reports) --------------------------------------------

    /** A larger result: 8 hot methods and 8 allocation sites, each with a 10-frame stack. */
    private fun deepResult(): ProfileResult {
        fun stack(prefix: String) = (1..10).map { "$prefix.frame$it" }
        val methods = (1..8).map { i ->
            HotMethod("pkg.Class$i.method$i", 90.0 - i, 100 - i, stack("m$i"))
        }
        val sites = (1..8).map { i ->
            AllocationSite("pkg.Type$i", 1_000_000L - i, 50.0 - i, 30 - i, stack("s$i"))
        }
        return result.copy(
            hotMethods = methods,
            hotThreads = (1..8).map { HotThread("thread-$it", 12.0, 20) },
            allocation = allocation.copy(topSites = sites),
        )
    }

    @Test
    fun `digest summary keeps warnings hints and headline scalars but omits lists`() {
        val summary = Renderers.render(deepResult(), OutputFormat.SUMMARY, ReportView.FULL, RenderOptions(digest = true))
        assertTrue(summary.contains("Warnings:"), summary)
        assertTrue(summary.contains("Low sample count"), summary)
        assertTrue(summary.contains("Hints:"), summary)
        assertTrue(summary.contains("GC:"), summary)
        assertTrue(summary.contains("Class loading:"), summary)
        // No hot-method / allocation-site lists, and no stack frames.
        assertFalse(summary.contains("Top hot methods"), summary)
        assertFalse(summary.contains("Top allocation sites"), summary)
        assertFalse(summary.contains("frame1"), summary)
    }

    @Test
    fun `digest json omits hotMethods and topSites arrays but keeps scalars`() {
        val json = Renderers.render(deepResult(), OutputFormat.JSON, options = RenderOptions(digest = true))
        assertFalse(json.contains("\"hotMethods\""), json)
        assertFalse(json.contains("\"topSites\""), json)
        assertFalse(json.contains("\"stack\""), json)
        // Headline scalars survive.
        assertTrue(json.contains("\"warnings\""), json)
        assertTrue(json.contains("\"hints\""), json)
        assertTrue(json.contains("\"totalSamples\""), json)
        assertTrue(json.contains("\"totalPauseMs\""), json)
        assertTrue(json.contains("\"loadedDuringRecording\""), json)
        assertTrue(json.contains("\"totalBytes\""), json)
        // Counts advertise how much is available to drill into.
        assertTrue(json.contains("\"hotMethodCount\": 8"), json)
        assertTrue(json.contains("\"topSiteCount\": 8"), json)
    }

    @Test
    fun `top and max-stack-depth truncate item and frame counts in summary`() {
        val summary = Renderers.render(
            deepResult(), OutputFormat.SUMMARY, ReportView.FULL,
            RenderOptions(top = 3, maxStackDepth = 2),
        )
        assertTrue(summary.contains("#1"), summary)
        assertTrue(summary.contains("#3"), summary)
        assertFalse(summary.contains("#4"), summary)
        // 10-frame stacks trimmed to 2, with a visible marker.
        assertTrue(summary.contains("(8 more frames"), summary)
    }

    @Test
    fun `top and max-stack-depth truncate item and frame counts in json`() {
        val json = Renderers.render(
            deepResult(), OutputFormat.JSON, options = RenderOptions(top = 2, maxStackDepth = 3),
        )
        assertEquals(2, Regex("\"method\":").findAll(json).count(), json)
        assertTrue(json.contains("\"stackTruncated\": true"), json)
        assertTrue(json.contains("\"stackFrameTotal\": 10"), json)
    }

    @Test
    fun `top and max-stack-depth truncate collapsed output`() {
        val many = result.copy(
            collapsed = (1..8).map { CollapsedStack((1..6).map { f -> "f$f" }, 10) },
        )
        val collapsed = Renderers.render(many, OutputFormat.COLLAPSED, options = RenderOptions(top = 2, maxStackDepth = 3))
        val lines = collapsed.lines()
        assertEquals(2, lines.size, collapsed)
        // Each line trimmed to 3 frames: "f1;f2;f3 10".
        assertEquals("f1;f2;f3 10", lines.first(), collapsed)
    }

    @Test
    fun `full renders every item with full stacks and no truncation markers`() {
        val json = Renderers.render(deepResult(), OutputFormat.JSON, options = RenderOptions.FULL)
        assertEquals(8, Regex("\"method\":").findAll(json).count(), json)
        assertFalse(json.contains("\"stackTruncated\""), json)
    }

    @Test
    fun `method drill-down shows exactly one method with its full stack`() {
        val json = Renderers.render(deepResult(), OutputFormat.JSON, options = RenderOptions(methodIndex = 3))
        assertEquals(1, Regex("\"method\":").findAll(json).count(), json)
        assertTrue(json.contains("\"index\": 3"), json)
        assertTrue(json.contains("pkg.Class3.method3"), json)
        // Full 10-frame stack, no truncation marker despite the depth default.
        assertTrue(json.contains("m3.frame10"), json)
        assertFalse(json.contains("\"stackTruncated\""), json)
        assertEquals("drill-down", Regex("\"layer\": \"(.*?)\"").find(json)?.groupValues?.get(1), json)
    }

    @Test
    fun `method drill-down summary shows one full stack`() {
        val summary = Renderers.render(deepResult(), OutputFormat.SUMMARY, ReportView.FULL, RenderOptions(methodIndex = 1))
        assertTrue(summary.contains("Hot method #1"), summary)
        assertTrue(summary.contains("m1.frame10"), summary)
        assertFalse(summary.contains("#2"), summary)
    }

    @Test
    fun `out-of-range drill index errors cleanly with the valid range`() {
        val tooBig = assertFailsWith<CliktError> { checkDrillIndex("--method", "hot methods", 9, 8) }
        assertTrue(tooBig.message!!.contains("between 1 and 8"), tooBig.message)
        val none = assertFailsWith<CliktError> { checkDrillIndex("--site", "allocation sites", 1, 0) }
        assertTrue(none.message!!.contains("no allocation sites"), none.message)
    }

    @Test
    fun `report help teaches the layered workflow recipe`() {
        val help = ReportCommand().getFormattedHelp() ?: ""
        assertTrue(help.contains("1. clijvm report --last --digest"), help)
        assertTrue(help.contains("2. clijvm report --last"), help)
        assertTrue(help.contains("3. clijvm report --last --method 3"), help)
    }
}
