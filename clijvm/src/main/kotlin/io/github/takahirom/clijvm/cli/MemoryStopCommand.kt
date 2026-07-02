package io.github.takahirom.clijvm.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import io.github.takahirom.clijvm.render.ReportView
import io.github.takahirom.clijvm.session.SessionStore

/** `clijvm memory stop <target>` — dump and stop the background recording, then report allocations. */
class MemoryStopCommand(
    private val sessions: SessionStore = SessionStore(),
) : CliktCommand(
    name = "stop",
    help = "Stop a background recording, save it, and print the allocation report.",
) {
    private val target by argument(
        name = "target",
        help = "A pid, or a case-insensitive substring of a process display name.",
    )
    private val format by option("--format", help = "Output format for the report.").outputFormat()
    private val output by option("--output", help = "Write the report to a file instead of stdout.")

    override fun run() = doStop(target, sessions, ReportView.MEMORY, format, output)
}
