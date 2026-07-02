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
import io.github.takahirom.clijvm.render.RenderOptions
import io.github.takahirom.clijvm.render.Renderers
import io.github.takahirom.clijvm.render.ReportView
import io.github.takahirom.clijvm.util.recordingsDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries

/** `clijvm report [--last | <file.jfr>]` — re-analyse a saved recording without re-profiling. */
class ReportCommand : CliktCommand(
    name = "report",
    help = """
        Re-analyse a saved .jfr recording in a chosen format.

        Reports are layered so an AI can read cheaply, then drill in:
          1. clijvm report --last --digest       # takeaways only (warnings + hints + headline numbers)
          2. clijvm report --last                 # top 5 hot methods/sites, shallow stacks
          3. clijvm report --last --method 3      # one hot method with its full stack

        The #N labels printed in step 2 are the indices you pass to --method / --site in step 3.
    """.trimIndent(),
) {
    private val last by option("--last", help = "Use the newest recording in ~/.clijvm/recordings.").flag()
    private val fileArg by argument(name = "file", help = "Path to a .jfr recording.").optional()
    private val format by option("--format", help = "Output format.").outputFormat()
    private val output by option("--output", help = "Write the report to a file instead of stdout.")

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
    private val full by option(
        "--full",
        help = "Escape hatch — render everything with full stacks (same as --top 0 --max-stack-depth 0).",
    ).flag()

    override fun run() {
        val file = when {
            last -> newestRecording()
                ?: throw CliktError("No recordings found in $recordingsDir. Run 'clijvm cpu <target>' first.")
            fileArg != null -> Path.of(fileArg!!)
            else -> throw CliktError("Specify a .jfr file or use --last.")
        }
        if (!Files.isReadable(file)) throw CliktError("Cannot read recording: $file")

        val result = JfrAnalyzer.analyze(file, pid = pidFromFilename(file))
        val options = buildRenderOptions(result)
        // A saved recording carries both CPU and allocation data, so report shows the full view.
        writeReport(Renderers.render(result, format, ReportView.FULL, options), output)
    }

    /** Resolves the layer flags into a [RenderOptions], validating any drill-down index. */
    private fun buildRenderOptions(result: ProfileResult): RenderOptions {
        methodIndex?.let { checkDrillIndex("--method", "hot methods", it, result.hotMethods.size) }
        siteIndex?.let { checkDrillIndex("--site", "allocation sites", it, result.allocation?.topSites?.size ?: 0) }
        val effectiveTop = if (full) 0 else top
        val effectiveDepth = if (full) 0 else maxStackDepth
        return RenderOptions(
            top = effectiveTop,
            maxStackDepth = effectiveDepth,
            digest = digest,
            methodIndex = methodIndex,
            siteIndex = siteIndex,
        )
    }

    private fun newestRecording(): Path? {
        if (!Files.isDirectory(recordingsDir)) return null
        return recordingsDir.listDirectoryEntries()
            .filter { it.isRegularFile() && it.extension == "jfr" }
            .maxByOrNull { it.getLastModifiedTime().toMillis() }
    }

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
