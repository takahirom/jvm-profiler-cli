package io.github.takahirom.clijvm

import io.github.takahirom.clijvm.analysis.AllocationSite
import io.github.takahirom.clijvm.analysis.AllocationStats
import io.github.takahirom.clijvm.analysis.ClassLoadingStats
import io.github.takahirom.clijvm.analysis.CollapsedStack
import io.github.takahirom.clijvm.analysis.GcStats
import io.github.takahirom.clijvm.analysis.HotMethod
import io.github.takahirom.clijvm.analysis.HotThread
import io.github.takahirom.clijvm.analysis.ProfileResult
import io.github.takahirom.clijvm.render.OutputFormat
import io.github.takahirom.clijvm.render.Renderers
import io.github.takahirom.clijvm.render.ReportView
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
