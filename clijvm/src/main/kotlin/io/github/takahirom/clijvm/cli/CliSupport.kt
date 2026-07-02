package io.github.takahirom.clijvm.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.parameters.options.NullableOption
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.types.choice
import com.sun.tools.attach.VirtualMachine
import io.github.takahirom.clijvm.attach.JvmProcess
import io.github.takahirom.clijvm.attach.TargetResolutionException
import io.github.takahirom.clijvm.attach.TargetResolver
import io.github.takahirom.clijvm.render.OutputFormat
import io.github.takahirom.clijvm.util.recordingsDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Shared `--format summary|json|collapsed` option, defaulting to summary. */
fun NullableOption<String, String>.outputFormat() =
    choice(
        "summary" to OutputFormat.SUMMARY,
        "json" to OutputFormat.JSON,
        "collapsed" to OutputFormat.COLLAPSED,
    ).default(OutputFormat.SUMMARY)

/** Writes a rendered report to [output] file, or to stdout when [output] is null. */
fun CliktCommand.writeReport(report: String, output: String?) {
    if (output == null) {
        echo(report)
    } else {
        val path = Path.of(output)
        path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        Files.writeString(path, report)
        echo("Report written to ${path.toAbsolutePath()}")
    }
}

/** Resolves a `<target>` to a process, converting resolution failures into clean CLI errors. */
fun resolveTarget(target: String): JvmProcess =
    try {
        TargetResolver.resolve(target)
    } catch (e: TargetResolutionException) {
        throw CliktError(e.message ?: "Could not resolve target '$target'.")
    }

/** Fails fast with a clear message if the target cannot be attached to. */
fun verifyAttach(pid: Long, displayName: String) {
    try {
        VirtualMachine.attach(pid.toString()).detach()
    } catch (e: Exception) {
        throw CliktError("Cannot attach to pid $pid ($displayName): ${e.message}")
    }
}

/** True if a process with [pid] currently exists and is running. */
fun isProcessAlive(pid: Long): Boolean =
    ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

/** Builds the standard recording path `~/.clijvm/recordings/<yyyyMMdd-HHmmss>-<pid>.jfr`. */
fun newRecordingPath(pid: Long): Path {
    Files.createDirectories(recordingsDir)
    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    return recordingsDir.resolve("$stamp-$pid.jfr")
}
