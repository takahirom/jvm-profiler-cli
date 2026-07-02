package io.github.takahirom.clijvm.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import io.github.takahirom.clijvm.render.ReportView
import io.github.takahirom.clijvm.util.parseDuration
import java.time.Duration

/**
 * `clijvm cpu <target> [--duration 30s]` — synchronous CPU profiling via JFR.
 *
 * Also the parent of the background verbs `start`, `stop`, and `status`. When one of those
 * subcommands is invoked, this command does nothing; otherwise it profiles [target] synchronously.
 */
class CpuCommand : CliktCommand(
    name = "cpu",
    help = "Profile CPU of a running JVM. With a target, profiles synchronously; " +
        "use start/stop/status for background recording.",
    invokeWithoutSubcommand = true,
) {
    private val target by argument(
        name = "target",
        help = "A pid, or a case-insensitive substring of a process display name.",
    ).optional()
    private val duration by option("--duration", help = "Profiling duration, e.g. 30s, 5m, or plain seconds.")
        .convert { runCatching { parseDuration(it) }.getOrElse { e -> fail(e.message ?: "invalid duration") } }
        .default(Duration.ofSeconds(30))
    private val wait by option(
        "--wait",
        help = "Wait for a JVM whose name contains this substring to appear, then profile it. " +
            "Recommended for short-lived Gradle test workers.",
    )
    private val waitTimeout by option("--wait-timeout", help = "How long to wait with --wait (default 120s).")
        .convert { runCatching { parseDuration(it) }.getOrElse { e -> fail(e.message ?: "invalid duration") } }
        .default(Duration.ofSeconds(120))
    private val format by option("--format", help = "Output format for the immediate report.").outputFormat()
    private val output by option("--output", help = "Write the report to a file instead of stdout.")

    override fun run() {
        // If a subcommand (start/stop/status) was invoked, let it handle the work.
        if (currentContext.invokedSubcommand != null) return

        val process = resolveOrWait(target, wait, waitTimeout)
        profileSynchronously(process, duration, ReportView.CPU, format, output)
    }
}
