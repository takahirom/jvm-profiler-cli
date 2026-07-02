package io.github.takahirom.clijvm.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.github.takahirom.clijvm.analysis.JfrAnalyzer
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
    help = "Re-analyse a saved .jfr recording in a chosen format.",
) {
    private val last by option("--last", help = "Use the newest recording in ~/.clijvm/recordings.").flag()
    private val fileArg by argument(name = "file", help = "Path to a .jfr recording.").optional()
    private val format by option("--format", help = "Output format.").outputFormat()
    private val output by option("--output", help = "Write the report to a file instead of stdout.")

    override fun run() {
        val file = when {
            last -> newestRecording()
                ?: throw CliktError("No recordings found in $recordingsDir. Run 'clijvm cpu <target>' first.")
            fileArg != null -> Path.of(fileArg!!)
            else -> throw CliktError("Specify a .jfr file or use --last.")
        }
        if (!Files.isReadable(file)) throw CliktError("Cannot read recording: $file")

        val result = JfrAnalyzer.analyze(file, pid = pidFromFilename(file))
        // A saved recording carries both CPU and allocation data, so report shows the full view.
        writeReport(Renderers.render(result, format, ReportView.FULL), output)
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
