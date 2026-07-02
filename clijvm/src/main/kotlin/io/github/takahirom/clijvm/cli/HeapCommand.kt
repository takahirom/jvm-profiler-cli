package io.github.takahirom.clijvm.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import io.github.takahirom.clijvm.analysis.HeapHistogramParser
import io.github.takahirom.clijvm.render.HeapRenderer
import io.github.takahirom.clijvm.util.Jcmd
import io.github.takahirom.clijvm.util.JcmdException

/** `clijvm heap <target>` — immediate class histogram via `jcmd GC.class_histogram`. */
class HeapCommand : CliktCommand(
    name = "heap",
    help = "Print a class histogram (instances and bytes per class) for a running JVM.",
) {
    private val target by argument(
        name = "target",
        help = "A pid, or a case-insensitive substring of a process display name.",
    )
    private val limit by option("--limit", help = "How many classes to show (default 20).").int().default(20)
    private val format by option("--format", help = "Output format.").choice("summary", "json").default("summary")
    private val output by option("--output", help = "Write the report to a file instead of stdout.")

    override fun run() {
        val process = resolveTarget(target)
        val pid = process.pid
        verifyAttach(pid, process.displayName)

        val raw = try {
            Jcmd.run(pid, "GC.class_histogram")
        } catch (e: JcmdException) {
            throw CliktError(e.message ?: "GC.class_histogram failed")
        }

        val histogram = HeapHistogramParser.parse(raw)
        val report = if (format == "json") {
            HeapRenderer.json(pid, histogram, limit)
        } else {
            HeapRenderer.summary(pid, histogram, limit)
        }
        writeReport(report, output)
    }
}
