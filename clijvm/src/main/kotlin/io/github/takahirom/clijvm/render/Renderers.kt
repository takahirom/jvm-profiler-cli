package io.github.takahirom.clijvm.render

import io.github.takahirom.clijvm.analysis.AllocationSite
import io.github.takahirom.clijvm.analysis.AllocationStats
import io.github.takahirom.clijvm.analysis.HeapTrend
import io.github.takahirom.clijvm.analysis.HeapTrendDirection
import io.github.takahirom.clijvm.analysis.HotMethod
import io.github.takahirom.clijvm.analysis.ProfileResult
import io.github.takahirom.clijvm.analysis.ThreadWait
import io.github.takahirom.clijvm.analysis.WaitStats
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
    /** Layer 2: 1-based index of the hot thread to drill into (its own top methods). */
    val threadIndex: Int? = null,
    /** Wait/park/sleep view: render per-thread off-CPU time instead of the CPU/memory layers. */
    val waits: Boolean = false,
) {
    /** True when a single node is being drilled into (Layer 2). */
    val isDrillDown: Boolean get() = methodIndex != null || siteIndex != null || threadIndex != null

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
            options.waits -> appendWaitsBody(result, options)
            options.isDrillDown -> appendDrillDown(result, options)
            options.digest -> appendDigestBody(result, showMemory)
            else -> appendLayer1Body(result, options, showCpu, showMemory)
        }
    }.trimEnd('\n')

    /** Wait/park/sleep view: per-thread off-CPU time, ranked, honoring --top and --max-stack-depth. */
    private fun StringBuilder.appendWaitsBody(result: ProfileResult, options: RenderOptions) {
        val waits = result.waits
        if (waits == null || waits.threads.isEmpty()) {
            appendLine("Thread waits (park/monitor-wait/sleep):")
            appendLine("  (no wait events captured)")
            return
        }
        val n = waits.threads.size
        appendLine(
            "Thread waits by total off-CPU time (${formatMillis(waits.totalWaitMs)} across " +
                "$n ${if (n == 1) "thread" else "threads"}):"
        )
        limit(waits.threads, options.top).forEachIndexed { i, t ->
            appendLine(
                String.format(
                    Locale.US,
                    "  #%-2d %-28s total %s  (park %s/%d, wait %s/%d, sleep %s/%d)",
                    i + 1, t.thread, formatMillis(t.totalMs),
                    formatMillis(t.parkedMs), t.parkEvents,
                    formatMillis(t.monitorWaitMs), t.monitorWaitEvents,
                    formatMillis(t.sleepMs), t.sleepEvents,
                )
            )
            if (t.topBlockers.isNotEmpty()) {
                appendLine("        blockers: ${t.topBlockers.take(3).joinToString(", ")}")
            }
            appendStack(t.stack, options.maxStackDepth)
        }
    }

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
        appendHeapTrend(result)
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
        appendHeapTrend(result)
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
        options.threadIndex?.let { idx ->
            val breakdown = result.threadBreakdowns[idx - 1]
            appendLine("Hot thread #$idx: ${breakdown.name}  (${breakdown.totalSamples} samples)")
            appendLine("Top methods within this thread (self%):")
            if (breakdown.topMethods.isEmpty()) {
                appendLine("  (no samples attributed to this thread)")
            } else {
                breakdown.topMethods.forEachIndexed { i, m ->
                    appendLine(String.format(Locale.US, "  #%-2d %5.1f%%  %s", i + 1, m.selfPct, m.method))
                    appendStack(m.stack, options.maxStackDepth)
                }
            }
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

    /** One line summarising the post-GC heap trend (leak signal), when heap data was recorded. */
    private fun StringBuilder.appendHeapTrend(result: ProfileResult) {
        val trend = result.heapTrend ?: return
        appendLine("Post-GC heap: ${heapTrendLine(trend)}")
        appendLine()
    }

    private fun heapTrendLine(t: HeapTrend): String = when (t.direction) {
        HeapTrendDirection.GROWING ->
            "growing (~${formatBytes(t.firstThirdAvgBytes)} -> ~${formatBytes(t.lastThirdAvgBytes)}) " +
                "over ${t.gcCount} GCs — possible leak or growing retained set"
        HeapTrendDirection.STABLE -> "stable (~${formatBytes(t.lastThirdAvgBytes)}) over ${t.gcCount} GCs"
        HeapTrendDirection.SHRINKING ->
            "shrinking (~${formatBytes(t.firstThirdAvgBytes)} -> ~${formatBytes(t.lastThirdAvgBytes)}) over ${t.gcCount} GCs"
        HeapTrendDirection.INSUFFICIENT_DATA -> "insufficient data (${t.gcCount} GCs)"
    }

    private fun heapTrendDirectionLabel(direction: HeapTrendDirection): String = when (direction) {
        HeapTrendDirection.GROWING -> "growing"
        HeapTrendDirection.STABLE -> "stable"
        HeapTrendDirection.SHRINKING -> "shrinking"
        HeapTrendDirection.INSUFFICIENT_DATA -> "insufficient-data"
    }

    private fun heapTrendJson(t: HeapTrend): Json = jsonObject(
        "trend" to jsonString(heapTrendDirectionLabel(t.direction)),
        "summary" to jsonString(heapTrendLine(t)),
        "gcCount" to jsonInt(t.gcCount),
        "firstThirdAvgBytes" to jsonInt(t.firstThirdAvgBytes),
        "lastThirdAvgBytes" to jsonInt(t.lastThirdAvgBytes),
        "minBytes" to jsonInt(t.minBytes),
        "maxBytes" to jsonInt(t.maxBytes),
    )

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
            // Wait/park/sleep view: per-thread off-CPU time only.
            options.waits -> {
                entries.add(0, "layer" to jsonString("waits"))
                entries.add("waits" to waitsJson(result.waits, options))
            }

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
                // Headline leak signal, so "is it leaking?" is answerable from the digest alone.
                result.heapTrend?.let { entries.add("postGcHeap" to heapTrendJson(it)) }
            }

            // Layer 2: exactly the one drilled node, nothing else in the big arrays.
            options.isDrillDown -> {
                entries.add(0, "layer" to jsonString("drill-down"))
                val method = options.methodIndex?.let { idx ->
                    // Method/site drills show the FULL stack (depth ignored).
                    listOf(hotMethodJson(result.hotMethods[idx - 1], idx, maxDepth = 0))
                } ?: emptyList()
                entries.add(
                    "cpu" to jsonObject(
                        "hotMethods" to jsonArray(method),
                        "hotThreads" to jsonArray(emptyList()),
                    )
                )
                // Thread drill shows the thread's own top methods, at --max-stack-depth.
                options.threadIndex?.let { idx ->
                    val breakdown = result.threadBreakdowns[idx - 1]
                    entries.add(
                        "thread" to jsonObject(
                            "index" to jsonInt(idx),
                            "name" to jsonString(breakdown.name),
                            "totalSamples" to jsonInt(breakdown.totalSamples),
                            "topMethods" to jsonArray(
                                breakdown.topMethods.mapIndexed { i, m ->
                                    hotMethodJson(m, i + 1, options.maxStackDepth)
                                }
                            ),
                        )
                    )
                }
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
                result.heapTrend?.let { entries.add("postGcHeap" to heapTrendJson(it)) }
            }
        }
        return Json.Obj(entries).render()
    }

    private fun waitsJson(waits: WaitStats?, options: RenderOptions): Json {
        if (waits == null) return jsonObject("totalWaitMs" to jsonInt(0), "threads" to jsonArray(emptyList()))
        val threads = limit(waits.threads, options.top).mapIndexed { i, t -> threadWaitJson(t, i + 1, options.maxStackDepth) }
        return jsonObject(
            "totalWaitMs" to jsonNumber(waits.totalWaitMs),
            "threadCount" to jsonInt(waits.threads.size),
            "threads" to jsonArray(threads),
        )
    }

    private fun threadWaitJson(t: ThreadWait, index: Int, maxDepth: Int): Json =
        Json.Obj(
            buildList {
                add("index" to jsonInt(index))
                add("thread" to jsonString(t.thread))
                add("totalMs" to jsonNumber(t.totalMs))
                add("parkedMs" to jsonNumber(t.parkedMs))
                add("monitorWaitMs" to jsonNumber(t.monitorWaitMs))
                add("sleepMs" to jsonNumber(t.sleepMs))
                add("parkEvents" to jsonInt(t.parkEvents))
                add("monitorWaitEvents" to jsonInt(t.monitorWaitEvents))
                add("sleepEvents" to jsonInt(t.sleepEvents))
                add("topBlockers" to jsonArray(t.topBlockers.map { jsonString(it) }))
                addAll(stackEntries(t.stack, maxDepth))
            }
        )

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

    /** Formats a millisecond duration compactly, e.g. `340 ms` or `12.3s`. */
    fun formatMillis(ms: Double): String =
        if (ms < 1000) String.format(Locale.US, "%.0f ms", ms) else String.format(Locale.US, "%.1fs", ms / 1000)

    /** Takes the first [n] items, or all of them when [n] <= 0 ("no limit"). */
    private fun <T> limit(list: List<T>, n: Int): List<T> = if (n <= 0) list else list.take(n)

    /** Trims [stack] to [depth] frames, or returns it whole when [depth] <= 0 ("no limit"). */
    private fun limitStack(stack: List<String>, depth: Int): List<String> =
        if (depth <= 0 || stack.size <= depth) stack else stack.take(depth)
}
