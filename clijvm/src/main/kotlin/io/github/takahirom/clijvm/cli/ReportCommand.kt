package io.github.takahirom.clijvm.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import io.github.takahirom.clijvm.analysis.JfrAnalyzer
import io.github.takahirom.clijvm.analysis.ProfileResult
import io.github.takahirom.clijvm.render.OutputFormat
import io.github.takahirom.clijvm.render.RenderOptions
import io.github.takahirom.clijvm.render.Renderers
import io.github.takahirom.clijvm.render.ReportView
import io.github.takahirom.clijvm.util.Json
import io.github.takahirom.clijvm.util.jsonArray
import io.github.takahirom.clijvm.util.jsonInt
import io.github.takahirom.clijvm.util.jsonObject
import io.github.takahirom.clijvm.util.jsonString
import io.github.takahirom.clijvm.util.jsonStringOrNull
import io.github.takahirom.clijvm.util.readRecordingMeta
import io.github.takahirom.clijvm.util.recordingsDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.io.path.extension
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries

/** `clijvm report [--last | <file.jfr>]` — re-analyse a saved recording without re-profiling. */
class ReportCommand : CliktCommand(
    name = "report",
    help = """
        Re-analyse a saved .jfr recording in a chosen format.

        First, 'clijvm report --list' shows which recordings exist (file, pid, mainClass) so you pick the right one.

        Reports are layered so an AI can read cheaply, then drill in:
          1. clijvm report --last --digest       # takeaways only (warnings + hints + headline numbers)
          2. clijvm report --last                 # top 5 hot methods/sites, shallow stacks
          3. clijvm report --last --method 3      # one hot method with its full stack

        The #N labels printed in step 2 are the indices you pass to --method / --site / --thread.
        Where does non-CPU time go? -> clijvm report --last --waits (per-thread park/wait/sleep).
    """.trimIndent(),
) {
    private val last by option("--last", help = "Use the newest recording in ~/.clijvm/recordings.").flag()
    private val fileArg by argument(name = "file", help = "Path to a .jfr recording.").optional()
    private val format by option("--format", help = "Output format.").outputFormat()
    private val output by option("--output", help = "Write the report to a file instead of stdout.")

    private val listRecordings by option(
        "--list",
        help = "List saved recordings (file, timestamp, pid, mainClass, size), newest first, then exit.",
    ).flag()
    private val digest by option(
        "--digest",
        help = "Layer 0 — takeaways only: warnings, hints, and headline numbers. No stacks or lists.",
    ).flag()
    private val top by option(
        "--top",
        help = "Layer 1 — how many hot methods/threads/sites to show (default 5; 0 = no limit).",
    ).int().default(RenderOptions.DEFAULT_TOP)
    private val maxStackDepth by option(
        "--max-stack-depth",
        help = "Layer 1 — how many stack frames to show per item (default 5; 0 = no limit).",
    ).int().default(RenderOptions.DEFAULT_MAX_STACK_DEPTH)
    private val methodIndex by option(
        "--method",
        help = "Layer 2 — drill into one hot method by its #N index, printing its full stack.",
    ).int()
    private val siteIndex by option(
        "--site",
        help = "Layer 2 — drill into one allocation site by its #N index, printing its full stack.",
    ).int()
    private val threadIndex by option(
        "--thread",
        help = "Layer 2 — drill into one hot thread by its #N index, showing its own top methods.",
    ).int()
    private val waits by option(
        "--waits",
        help = "Wait view — per-thread off-CPU time (park/monitor-wait/sleep). Honors --top and --max-stack-depth.",
    ).flag()
    private val full by option(
        "--full",
        help = "Escape hatch — render everything with full stacks (same as --top 0 --max-stack-depth 0).",
    ).flag()

    override fun run() {
        if (listRecordings) {
            writeReport(renderRecordingList(), output)
            return
        }

        val file = when {
            last -> newestRecording()
                ?: throw CliktError("No recordings found in $recordingsDir. Run 'clijvm cpu <target>' first.")
            fileArg != null -> Path.of(fileArg!!)
            else -> throw CliktError("Specify a .jfr file, use --last, or list saved recordings with --list.")
        }
        if (!Files.isReadable(file)) throw CliktError("Cannot read recording: $file")

        // Prefer the sidecar (main class, partial flag) when present; fall back to the filename pid.
        val meta = readRecordingMeta(file)
        val result = JfrAnalyzer.analyze(
            file = file,
            pid = meta?.pid ?: pidFromFilename(file),
            mainClass = meta?.mainClass,
            partial = meta?.partial ?: false,
        )
        val options = buildRenderOptions(result)
        // A saved recording carries both CPU and allocation data, so report shows the full view.
        writeReport(Renderers.render(result, format, ReportView.FULL, options), output)
    }

    /** Resolves the layer flags into a [RenderOptions], validating any drill-down index. */
    private fun buildRenderOptions(result: ProfileResult): RenderOptions {
        methodIndex?.let { checkDrillIndex("--method", "hot methods", it, result.hotMethods.size) }
        siteIndex?.let { checkDrillIndex("--site", "allocation sites", it, result.allocation?.topSites?.size ?: 0) }
        threadIndex?.let { checkDrillIndex("--thread", "hot threads", it, result.hotThreads.size) }
        val effectiveTop = if (full) 0 else top
        val effectiveDepth = if (full) 0 else maxStackDepth
        return RenderOptions(
            top = effectiveTop,
            maxStackDepth = effectiveDepth,
            digest = digest,
            methodIndex = methodIndex,
            siteIndex = siteIndex,
            threadIndex = threadIndex,
            waits = waits,
        )
    }

    /** Renders the saved-recordings inventory as a table (or JSON when `--format json`). */
    private fun renderRecordingList(): String {
        val recordings = savedRecordings()
        if (format == OutputFormat.JSON) {
            return jsonArray(recordings.map { r ->
                jsonObject(
                    "file" to jsonString(r.file.toString()),
                    "timestamp" to jsonString(r.timestamp),
                    "pid" to (r.pid?.let { jsonInt(it) } ?: Json.Literal("null")),
                    "mainClass" to jsonStringOrNull(r.mainClass),
                    "sizeBytes" to jsonInt(r.sizeBytes),
                )
            }).render()
        }
        if (recordings.isEmpty()) return "No recordings found in $recordingsDir."
        val pidWidth = maxOf(3, recordings.maxOf { (it.pid?.toString() ?: "-").length })
        val fileWidth = maxOf(4, recordings.maxOf { it.file.fileName.toString().length })
        val row = "%-19s  %-${pidWidth}s  %-9s  %-${fileWidth}s  %s"
        return buildString {
            appendLine(row.format("TIMESTAMP", "PID", "SIZE", "FILE", "MAIN CLASS"))
            recordings.forEach { r ->
                val pid = r.pid?.toString() ?: "-"
                val mainClass = recordingMainClassCell(r.mainClass)
                appendLine(
                    row.format(
                        r.timestamp, pid, Renderers.formatBytes(r.sizeBytes), r.file.fileName.toString(), mainClass,
                    )
                )
            }
        }.trimEnd()
    }

    private data class RecordingInfo(
        val file: Path,
        val timestamp: String,
        val pid: Long?,
        val mainClass: String?,
        val sizeBytes: Long,
    )

    private fun savedRecordings(): List<RecordingInfo> {
        if (!Files.isDirectory(recordingsDir)) return emptyList()
        val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
        return recordingsDir.listDirectoryEntries()
            .filter { it.isRegularFile() && it.extension == "jfr" }
            .sortedByDescending { it.getLastModifiedTime().toMillis() }
            .map { file ->
                val meta = readRecordingMeta(file)
                RecordingInfo(
                    file = file.toAbsolutePath(),
                    timestamp = stamp.format(Instant.ofEpochMilli(file.getLastModifiedTime().toMillis())),
                    pid = meta?.pid ?: pidFromFilename(file),
                    mainClass = meta?.mainClass,
                    sizeBytes = file.fileSize(),
                )
            }
    }

    private fun newestRecording(): Path? = savedRecordings().firstOrNull()?.file

    /** Recovers the pid from a `<yyyyMMdd-HHmmss>-<pid>.jfr` filename, if present. */
    private fun pidFromFilename(file: Path): Long? =
        Regex("-(\\d+)\\.jfr$").find(file.fileName.toString())?.groupValues?.get(1)?.toLongOrNull()
}

/**
 * Validates a 1-based drill-down [index] against the number of available items [size], raising a
 * clean [CliktError] that names the valid range. [flag] is the option (e.g. `--method`) and [label]
 * the human noun (e.g. `hot methods`).
 */
internal fun checkDrillIndex(flag: String, label: String, index: Int, size: Int) {
    if (size == 0) throw CliktError("This recording has no $label to drill into.")
    if (index !in 1..size) throw CliktError("$flag must be between 1 and $size (got $index).")
}

/**
 * The MAIN CLASS cell for `report --list`: the main class truncated like `list` does (so a daemon's
 * classpath doesn't blow up the row), or `(unknown)` when no sidecar recorded it. The FILE column
 * keeps the recording identifiable, so the filename never needs to stand in here.
 */
internal fun recordingMainClassCell(mainClass: String?): String =
    mainClass?.let { truncateDisplayName(it, full = false).first } ?: "(unknown)"
