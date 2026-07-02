package io.github.takahirom.clijvm.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import io.github.takahirom.clijvm.attach.JvmDiscovery
import io.github.takahirom.clijvm.attach.JvmProcess
import io.github.takahirom.clijvm.util.jsonArray
import io.github.takahirom.clijvm.util.jsonBool
import io.github.takahirom.clijvm.util.jsonInt
import io.github.takahirom.clijvm.util.jsonObject
import io.github.takahirom.clijvm.util.jsonString

/** `clijvm list` — enumerate attachable JVM processes. */
class ListCommand : CliktCommand(
    name = "list",
    help = "List attachable JVM processes. Gradle test workers are marked.",
) {
    private val format by option("--format", help = "Output format").choice("table", "json").default("table")

    override fun run() {
        val processes = JvmDiscovery.list()
        echo(if (format == "json") renderJson(processes) else renderTable(processes))
    }

    private fun renderTable(processes: List<JvmProcess>): String {
        if (processes.isEmpty()) return "No attachable JVM processes found."
        val pidWidth = maxOf(3, processes.maxOf { it.pid.toString().length })
        return buildString {
            appendLine("%-${pidWidth}s  %-7s  %s".format("PID", "GRADLE", "MAIN CLASS / DISPLAY NAME"))
            processes.forEach { p ->
                val marker = if (p.isGradleWorker) "yes" else ""
                appendLine("%-${pidWidth}d  %-7s  %s".format(p.pid, marker, p.displayName))
            }
        }.trimEnd()
    }

    private fun renderJson(processes: List<JvmProcess>): String =
        jsonArray(processes.map { p ->
            jsonObject(
                "pid" to jsonInt(p.pid),
                "displayName" to jsonString(p.displayName),
                "gradleWorker" to jsonBool(p.isGradleWorker),
            )
        }).render()
}
