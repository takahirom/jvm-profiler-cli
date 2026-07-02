package io.github.takahirom.clijvm.render

import io.github.takahirom.clijvm.analysis.AllocationStats
import io.github.takahirom.clijvm.analysis.ProfileResult
import io.github.takahirom.clijvm.util.Json
import io.github.takahirom.clijvm.util.jsonArray
import io.github.takahirom.clijvm.util.jsonBool
import io.github.takahirom.clijvm.util.jsonInt
import io.github.takahirom.clijvm.util.jsonNumber
import io.github.takahirom.clijvm.util.jsonObject
import io.github.takahirom.clijvm.util.jsonString
import io.github.takahirom.clijvm.util.jsonStringOrNull
import java.util.Locale

/** Output formats for profiling results. */
enum class OutputFormat { SUMMARY, JSON, COLLAPSED }

/** Which sections a text summary emphasises. JSON/collapsed always carry the full data. */
enum class ReportView { CPU, MEMORY, FULL }

/** Renders a [ProfileResult] into one of the supported [OutputFormat]s. */
object Renderers {

    fun render(result: ProfileResult, format: OutputFormat, view: ReportView = ReportView.FULL): String =
        when (format) {
            OutputFormat.SUMMARY -> summary(result, view)
            OutputFormat.JSON -> json(result)
            OutputFormat.COLLAPSED -> collapsed(result)
        }

    /** Human- and AI-friendly text summary, showing sections relevant to [view]. */
    fun summary(result: ProfileResult, view: ReportView = ReportView.FULL): String = buildString {
        val showCpu = view == ReportView.CPU || view == ReportView.FULL
        val showMemory = view == ReportView.MEMORY || view == ReportView.FULL

        val title = when (view) {
            ReportView.CPU -> "CPU profile"
            ReportView.MEMORY -> "memory profile"
            ReportView.FULL -> "profile"
        }
        appendLine("=== clijvm $title ===")
        if (result.partial) appendLine("(PARTIAL: salvaged from an exited target)")
        appendLine("pid:        ${result.pid ?: "(unknown)"}")
        appendLine("mainClass:  ${result.mainClass ?: "(unknown)"}")
        appendLine("duration:   ${result.durationMs} ms")
        if (showCpu) appendLine("samples:    ${result.totalSamples}")
        result.recordingPath?.let { appendLine("recording:  $it") }
        appendLine()

        // Warnings and hints go first: an AI reader should see caveats and takeaways up top.
        if (result.warnings.isNotEmpty()) {
            appendLine("Warnings:")
            result.warnings.forEach { appendLine("  ! $it") }
            appendLine()
        }
        if (result.hints.isNotEmpty()) {
            appendLine("Hints:")
            result.hints.forEach { appendLine("  * $it") }
            appendLine()
        }

        if (showCpu) {
            appendLine("Top hot methods (self%):")
            if (result.hotMethods.isEmpty()) {
                appendLine("  (no CPU samples captured)")
            } else {
                result.hotMethods.forEach { m ->
                    appendLine(String.format(Locale.US, "  %5.1f%%  %s", m.selfPct, m.method))
                }
            }
            appendLine()

            appendLine("Hot threads (sample share):")
            if (result.hotThreads.isEmpty()) {
                appendLine("  (none)")
            } else {
                result.hotThreads.forEach { t ->
                    appendLine(String.format(Locale.US, "  %5.1f%%  %s", t.cpuPct, t.name))
                }
            }
            appendLine()
        }

        if (showMemory) {
            appendLine("Top allocation sites:")
            val allocation = result.allocation
            if (allocation == null) {
                appendLine("  (no allocation events captured)")
            } else {
                // Byte figures are extrapolated from sampled events, so label them as estimates.
                appendLine(
                    "  source: ${allocation.source}, estimated total: ${formatBytes(allocation.totalBytes)} " +
                        "over ${allocation.totalEvents} events"
                )
                allocation.topSites.forEach { site ->
                    appendLine(
                        String.format(
                            Locale.US,
                            "  %5.1f%%  %10s  %s",
                            site.sharePct,
                            formatBytes(site.bytes),
                            site.className,
                        )
                    )
                }
            }
            appendLine()
        }

        result.classLoading?.let { cl ->
            appendLine("Class loading:")
            appendLine("  loaded (total):          ${cl.loadedClassCount}")
            appendLine("  loaded during recording: ${cl.loadedDuringRecording}")
            appendLine("  unloaded (total):        ${cl.unloadedClassCount}")
            appendLine()
        }

        appendLine("GC:")
        appendLine("  collections:   ${result.gc.count}")
        appendLine(String.format(Locale.US, "  total pause:   %.1f ms", result.gc.totalPauseMs))
        append(String.format(Locale.US, "  max pause:     %.1f ms", result.gc.maxPauseMs))
    }

    /** Structured JSON following the schema sketched in PLAN.md. */
    fun json(result: ProfileResult): String {
        val hotMethods = jsonArray(result.hotMethods.map { m ->
            jsonObject(
                "method" to jsonString(m.method),
                "selfPct" to jsonNumber(m.selfPct),
                "samples" to jsonInt(m.samples),
                "stack" to jsonArray(m.stack.map { jsonString(it) }),
            )
        })
        val hotThreads = jsonArray(result.hotThreads.map { t ->
            jsonObject(
                "name" to jsonString(t.name),
                "cpuPct" to jsonNumber(t.cpuPct),
                "samples" to jsonInt(t.samples),
            )
        })

        val entries = mutableListOf(
            "pid" to (result.pid?.let { jsonInt(it) } ?: Json.Literal("null")),
            "mainClass" to jsonStringOrNull(result.mainClass),
            "durationMs" to jsonInt(result.durationMs),
            "totalSamples" to jsonInt(result.totalSamples),
            "partial" to jsonBool(result.partial),
            "recordingPath" to jsonStringOrNull(result.recordingPath),
            // warnings/hints first so an AI reader sees caveats and takeaways up front.
            "warnings" to jsonArray(result.warnings.map { jsonString(it) }),
            "hints" to jsonArray(result.hints.map { jsonString(it) }),
            "cpu" to jsonObject(
                "hotMethods" to hotMethods,
                "hotThreads" to hotThreads,
            ),
            "gc" to jsonObject(
                "count" to jsonInt(result.gc.count),
                "totalPauseMs" to jsonNumber(result.gc.totalPauseMs),
                "maxPauseMs" to jsonNumber(result.gc.maxPauseMs),
            ),
        )
        // Only present when the recording actually contains allocation events.
        result.allocation?.let { entries.add("allocation" to allocationJson(it)) }
        result.classLoading?.let { cl ->
            entries.add(
                "classLoading" to jsonObject(
                    "loadedClassCount" to jsonInt(cl.loadedClassCount),
                    "unloadedClassCount" to jsonInt(cl.unloadedClassCount),
                    "loadedDuringRecording" to jsonInt(cl.loadedDuringRecording),
                )
            )
        }
        return Json.Obj(entries).render()
    }

    private fun allocationJson(allocation: AllocationStats): Json = jsonObject(
        "source" to jsonString(allocation.source),
        "totalBytes" to jsonInt(allocation.totalBytes),
        "totalEvents" to jsonInt(allocation.totalEvents),
        "topSites" to jsonArray(allocation.topSites.map { site ->
            jsonObject(
                "class" to jsonString(site.className),
                "bytes" to jsonInt(site.bytes),
                "sharePct" to jsonNumber(site.sharePct),
                "events" to jsonInt(site.events),
                "stack" to jsonArray(site.stack.map { jsonString(it) }),
            )
        }),
    )

    /** Flamegraph collapsed-stack format: `frame;frame;frame count`, root-first. */
    fun collapsed(result: ProfileResult): String =
        result.collapsed.joinToString("\n") { stack ->
            "${stack.frames.joinToString(";")} ${stack.samples}"
        }

    /** Formats a byte count with a binary-scaled unit, e.g. `1.9 MB`. */
    fun formatBytes(bytes: Long): String {
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
}
