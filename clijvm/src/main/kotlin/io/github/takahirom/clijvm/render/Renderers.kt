package io.github.takahirom.clijvm.render

import io.github.takahirom.clijvm.analysis.AllocationSite
import io.github.takahirom.clijvm.analysis.AllocationStats
import io.github.takahirom.clijvm.analysis.HotMethod
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

/**
 * Controls how much of a [ProfileResult] a render emits. Reports are layered so an AI consumer can
 * read cheaply, then drill in:
 *
 *  - **Layer 0 ([digest] = true):** takeaways only — warnings, hints, and headline scalars. No
 *    hot-method / allocation-site lists, no stacks.
 *  - **Layer 1 (the default):** the [top] hottest methods / sites, each stack trimmed to
 *    [maxStackDepth] frames, across all formats.
 *  - **Layer 2 ([methodIndex] or [siteIndex] set):** exactly one hot method / allocation site,
 *    printed with its full untruncated stack.
 *
 * [top] or [maxStackDepth] set to `0` means "no limit" for that dimension; [FULL] uses both, which
 * reproduces the pre-layering behaviour (everything, full stacks).
 */
data class RenderOptions(
    /** How many hot methods / threads / allocation sites to show (0 = no limit). */
    val top: Int = DEFAULT_TOP,
    /** How many leaf-first frames of each representative stack to show (0 = no limit). */
    val maxStackDepth: Int = DEFAULT_MAX_STACK_DEPTH,
    /** Layer 0: emit only warnings, hints, and headline scalars. */
    val digest: Boolean = false,
    /** Layer 2: 1-based index of the single hot method to drill into with its full stack. */
    val methodIndex: Int? = null,
    /** Layer 2: 1-based index of the single allocation site to drill into with its full stack. */
    val siteIndex: Int? = null,
) {
    /** True when a single node is being drilled into (Layer 2). */
    val isDrillDown: Boolean get() = methodIndex != null || siteIndex != null

    companion object {
        const val DEFAULT_TOP = 5
        const val DEFAULT_MAX_STACK_DEPTH = 5

        /** Layer 1 default: top 5 items, shallow 5-frame stacks. */
        val DEFAULT = RenderOptions()

        /** Escape hatch (`--full`): everything, with full stacks. */
        val FULL = RenderOptions(top = 0, maxStackDepth = 0)
    }
}

/** Renders a [ProfileResult] into one of the supported [OutputFormat]s. */
object Renderers {

    fun render(
        result: ProfileResult,
        format: OutputFormat,
        view: ReportView = ReportView.FULL,
        options: RenderOptions = RenderOptions.DEFAULT,
    ): String =
        when (format) {
            OutputFormat.SUMMARY -> summary(result, view, options)
            OutputFormat.JSON -> json(result, options)
            OutputFormat.COLLAPSED -> collapsed(result, options)
        }

    /** Human- and AI-friendly text summary, showing sections relevant to [view]. */
    fun summary(
        result: ProfileResult,
        view: ReportView = ReportView.FULL,
        options: RenderOptions = RenderOptions.DEFAULT,
    ): String = buildString {
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

        // Warnings and hints go first in every layer: an AI reader should see caveats and
        // takeaways up top, before any raw data.
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

        when {
            options.isDrillDown -> appendDrillDown(result, options)
            options.digest -> appendDigestBody(result, showMemory)
            else -> appendLayer1Body(result, options, showCpu, showMemory)
        }
    }.trimEnd('\n')

    /** Layer 0 body: headline scalars only — no hot-method / allocation-site lists, no stacks. */
    private fun StringBuilder.appendDigestBody(result: ProfileResult, showMemory: Boolean) {
        appendLine("Digest (takeaways only). For details: 'report --last', then '--method N' / '--site N'.")
        appendLine()
        if (showMemory) {
            result.allocation?.let { allocation ->
                appendLine("Allocation:")
                appendLine(
                    "  source: ${allocation.source}, estimated total: ${formatBytes(allocation.totalBytes)} " +
                        "over ${allocation.totalEvents} events (${allocation.topSites.size} sites; use --site N)"
                )
                appendLine()
            }
        }
        appendClassLoading(result)
        appendGc(result)
    }

    /** Layer 1 body: top-N methods/threads/sites with shallow stacks and truncation markers. */
    private fun StringBuilder.appendLayer1Body(
        result: ProfileResult,
        options: RenderOptions,
        showCpu: Boolean,
        showMemory: Boolean,
    ) {
        if (showCpu) {
            appendLine("Top hot methods (self%):")
            if (result.hotMethods.isEmpty()) {
                appendLine("  (no CPU samples captured)")
            } else {
                limit(result.hotMethods, options.top).forEachIndexed { i, m ->
                    appendLine(String.format(Locale.US, "  #%-2d %5.1f%%  %s", i + 1, m.selfPct, m.method))
                    appendStack(m.stack, options.maxStackDepth)
                }
            }
            appendLine()

            appendLine("Hot threads (sample share):")
            if (result.hotThreads.isEmpty()) {
                appendLine("  (none)")
            } else {
                limit(result.hotThreads, options.top).forEachIndexed { i, t ->
                    appendLine(String.format(Locale.US, "  #%-2d %5.1f%%  %s", i + 1, t.cpuPct, t.name))
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
                limit(allocation.topSites, options.top).forEachIndexed { i, site ->
                    appendLine(
                        String.format(
                            Locale.US,
                            "  #%-2d %5.1f%%  %10s  %s",
                            i + 1,
                            site.sharePct,
                            formatBytes(site.bytes),
                            site.className,
                        )
                    )
                    appendStack(site.stack, options.maxStackDepth)
                }
            }
            appendLine()
        }

        appendClassLoading(result)
        appendGc(result)
    }

    /** Layer 2 body: exactly one hot method or allocation site, with its full stack. */
    private fun StringBuilder.appendDrillDown(result: ProfileResult, options: RenderOptions) {
        options.methodIndex?.let { idx ->
            val m = result.hotMethods[idx - 1]
            appendLine("Hot method #$idx (self%):")
            appendLine(String.format(Locale.US, "  %5.1f%%  %s  (${m.samples} samples)", m.selfPct, m.method))
            appendLine("  full stack (${m.stack.size} frames, leaf-first):")
            m.stack.forEach { appendLine("      $it") }
        }
        options.siteIndex?.let { idx ->
            val site = result.allocation!!.topSites[idx - 1]
            appendLine("Allocation site #$idx:")
            appendLine(
                String.format(
                    Locale.US,
                    "  %5.1f%%  %s  %s  (${site.events} events)",
                    site.sharePct,
                    formatBytes(site.bytes),
                    site.className,
                )
            )
            appendLine("  full stack (${site.stack.size} frames, leaf-first):")
            site.stack.forEach { appendLine("      $it") }
        }
    }

    private fun StringBuilder.appendClassLoading(result: ProfileResult) {
        result.classLoading?.let { cl ->
            appendLine("Class loading:")
            appendLine("  loaded (total):          ${cl.loadedClassCount}")
            appendLine("  loaded during recording: ${cl.loadedDuringRecording}")
            appendLine("  unloaded (total):        ${cl.unloadedClassCount}")
            appendLine()
        }
    }

    private fun StringBuilder.appendGc(result: ProfileResult) {
        appendLine("GC:")
        appendLine("  collections:   ${result.gc.count}")
        appendLine(String.format(Locale.US, "  total pause:   %.1f ms", result.gc.totalPauseMs))
        appendLine(String.format(Locale.US, "  max pause:     %.1f ms", result.gc.maxPauseMs))
    }

    /** Appends a stack indented under its owning method/site, with a marker if it was truncated. */
    private fun StringBuilder.appendStack(stack: List<String>, maxDepth: Int) {
        if (stack.isEmpty()) return
        val shown = limitStack(stack, maxDepth)
        shown.forEach { appendLine("        $it") }
        val hidden = stack.size - shown.size
        if (hidden > 0) appendLine("        … ($hidden more frames; drill in for the full stack)")
    }

    /** Structured JSON following the schema sketched in PLAN.md, sliced per [options]. */
    fun json(result: ProfileResult, options: RenderOptions = RenderOptions.DEFAULT): String {
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
        )

        when {
            // Layer 0: no hot-method / allocation-site arrays, just counts so a consumer knows
            // how much detail is available to drill into.
            options.digest -> {
                entries.add(0, "layer" to jsonString("digest"))
                entries.add(
                    "cpu" to jsonObject(
                        "hotMethodCount" to jsonInt(result.hotMethods.size),
                        "hotThreadCount" to jsonInt(result.hotThreads.size),
                    )
                )
                entries.add("gc" to gcJson(result))
                result.allocation?.let { allocation ->
                    entries.add(
                        "allocation" to jsonObject(
                            "source" to jsonString(allocation.source),
                            "totalBytes" to jsonInt(allocation.totalBytes),
                            "totalEvents" to jsonInt(allocation.totalEvents),
                            "topSiteCount" to jsonInt(allocation.topSites.size),
                        )
                    )
                }
                result.classLoading?.let { entries.add("classLoading" to classLoadingJson(result)) }
            }

            // Layer 2: exactly the one drilled node, full stack, nothing else in the big arrays.
            options.isDrillDown -> {
                entries.add(0, "layer" to jsonString("drill-down"))
                val method = options.methodIndex?.let { idx ->
                    listOf(hotMethodJson(result.hotMethods[idx - 1], idx, maxDepth = 0))
                } ?: emptyList()
                entries.add(
                    "cpu" to jsonObject(
                        "hotMethods" to jsonArray(method),
                        "hotThreads" to jsonArray(emptyList()),
                    )
                )
                entries.add("gc" to gcJson(result))
                result.allocation?.let { allocation ->
                    val site = options.siteIndex?.let { idx ->
                        listOf(allocationSiteJson(allocation.topSites[idx - 1], idx, maxDepth = 0))
                    } ?: emptyList()
                    entries.add("allocation" to allocationJson(allocation, site))
                }
                result.classLoading?.let { entries.add("classLoading" to classLoadingJson(result)) }
            }

            // Layer 1: top-N items with shallow stacks and truncation markers.
            else -> {
                val hotMethods = limit(result.hotMethods, options.top)
                    .mapIndexed { i, m -> hotMethodJson(m, i + 1, options.maxStackDepth) }
                val hotThreads = limit(result.hotThreads, options.top).mapIndexed { i, t ->
                    jsonObject(
                        "index" to jsonInt(i + 1),
                        "name" to jsonString(t.name),
                        "cpuPct" to jsonNumber(t.cpuPct),
                        "samples" to jsonInt(t.samples),
                    )
                }
                entries.add(
                    "cpu" to jsonObject(
                        "hotMethods" to jsonArray(hotMethods),
                        "hotThreads" to jsonArray(hotThreads),
                    )
                )
                entries.add("gc" to gcJson(result))
                result.allocation?.let { allocation ->
                    val sites = limit(allocation.topSites, options.top)
                        .mapIndexed { i, site -> allocationSiteJson(site, i + 1, options.maxStackDepth) }
                    entries.add("allocation" to allocationJson(allocation, sites))
                }
                result.classLoading?.let { entries.add("classLoading" to classLoadingJson(result)) }
            }
        }
        return Json.Obj(entries).render()
    }

    private fun gcJson(result: ProfileResult): Json = jsonObject(
        "count" to jsonInt(result.gc.count),
        "totalPauseMs" to jsonNumber(result.gc.totalPauseMs),
        "maxPauseMs" to jsonNumber(result.gc.maxPauseMs),
    )

    private fun classLoadingJson(result: ProfileResult): Json {
        val cl = result.classLoading!!
        return jsonObject(
            "loadedClassCount" to jsonInt(cl.loadedClassCount),
            "unloadedClassCount" to jsonInt(cl.unloadedClassCount),
            "loadedDuringRecording" to jsonInt(cl.loadedDuringRecording),
        )
    }

    private fun hotMethodJson(m: HotMethod, index: Int, maxDepth: Int): Json =
        Json.Obj(
            buildList {
                add("index" to jsonInt(index))
                add("method" to jsonString(m.method))
                add("selfPct" to jsonNumber(m.selfPct))
                add("samples" to jsonInt(m.samples))
                addAll(stackEntries(m.stack, maxDepth))
            }
        )

    private fun allocationJson(allocation: AllocationStats, sites: List<Json>): Json = jsonObject(
        "source" to jsonString(allocation.source),
        "totalBytes" to jsonInt(allocation.totalBytes),
        "totalEvents" to jsonInt(allocation.totalEvents),
        "topSites" to jsonArray(sites),
    )

    private fun allocationSiteJson(site: AllocationSite, index: Int, maxDepth: Int): Json =
        Json.Obj(
            buildList {
                add("index" to jsonInt(index))
                add("class" to jsonString(site.className))
                add("bytes" to jsonInt(site.bytes))
                add("sharePct" to jsonNumber(site.sharePct))
                add("events" to jsonInt(site.events))
                addAll(stackEntries(site.stack, maxDepth))
            }
        )

    /** Emits `stack`, trimmed to [maxDepth], plus truncation markers so a consumer knows to drill. */
    private fun stackEntries(stack: List<String>, maxDepth: Int): List<Pair<String, Json>> {
        val shown = limitStack(stack, maxDepth)
        val entries = mutableListOf<Pair<String, Json>>(
            "stack" to jsonArray(shown.map { jsonString(it) })
        )
        if (shown.size < stack.size) {
            entries += "stackTruncated" to jsonBool(true)
            entries += "stackFrameTotal" to jsonInt(stack.size)
        }
        return entries
    }

    /** Flamegraph collapsed-stack format: `frame;frame;frame count`, root-first. */
    fun collapsed(result: ProfileResult, options: RenderOptions = RenderOptions.DEFAULT): String =
        limit(result.collapsed, options.top).joinToString("\n") { stack ->
            // Collapsed frames are root-first; trimming keeps the root-most [maxStackDepth] frames.
            "${limitStack(stack.frames, options.maxStackDepth).joinToString(";")} ${stack.samples}"
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

    /** Takes the first [n] items, or all of them when [n] <= 0 ("no limit"). */
    private fun <T> limit(list: List<T>, n: Int): List<T> = if (n <= 0) list else list.take(n)

    /** Trims [stack] to [depth] frames, or returns it whole when [depth] <= 0 ("no limit"). */
    private fun limitStack(stack: List<String>, depth: Int): List<String> =
        if (depth <= 0 || stack.size <= depth) stack else stack.take(depth)
}
