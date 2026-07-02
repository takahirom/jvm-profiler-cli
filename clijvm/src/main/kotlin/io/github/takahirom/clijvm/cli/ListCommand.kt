package io.github.takahirom.clijvm.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
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
    help = """
        List attachable JVM processes. Gradle test workers are marked in the GRADLE column.

        A Gradle test worker (where Robolectric tests run) looks like this:
          worker.org.gradle.process.internal.worker.GradleWorkerMain 'Gradle Test Executor N'
        To profile one, wait for it by its stable substring:
          clijvm cpu --wait GradleWorkerMain --duration 20s

        Long display names (e.g. the Kotlin daemon's classpath) are truncated; pass --full to expand.
    """.trimIndent(),
) {
    private val format by option("--format", help = "Output format").choice("table", "json").default("table")
    private val full by option("--full", help = "Show full display names instead of truncating them.").flag()
    private val testWorkers by option(
        "--test-workers",
        help = "Show only Gradle test workers (the usual Robolectric profiling target).",
    ).flag()

    override fun run() {
        val all = try {
            JvmDiscovery.list()
        } catch (e: Exception) {
            throw CliktError("Could not enumerate JVM processes: ${e.message}")
        }
        val processes = if (testWorkers) all.filter { it.isGradleWorker } else all
        echo(if (format == "json") renderJson(processes) else renderTable(processes))
    }

    private fun renderTable(processes: List<JvmProcess>): String {
        if (processes.isEmpty()) {
            return if (testWorkers) "No Gradle test workers found." else "No attachable JVM processes found."
        }
        val pidWidth = maxOf(3, processes.maxOf { it.pid.toString().length })
        return buildString {
            appendLine("%-${pidWidth}s  %-7s  %s".format("PID", "GRADLE", "MAIN CLASS / DISPLAY NAME"))
            processes.forEach { p ->
                val marker = if (p.isGradleWorker) "yes" else ""
                val (name, _) = displayName(p.displayName)
                appendLine("%-${pidWidth}d  %-7s  %s".format(p.pid, marker, name))
            }
        }.trimEnd()
    }

    private fun renderJson(processes: List<JvmProcess>): String =
        jsonArray(processes.map { p ->
            val (name, truncated) = displayName(p.displayName)
            jsonObject(
                "pid" to jsonInt(p.pid),
                "displayName" to jsonString(name),
                "displayNameTruncated" to jsonBool(truncated),
                "gradleWorker" to jsonBool(p.isGradleWorker),
            )
        }).render()

    /** Returns the display name to show and whether it was truncated. `--full` keeps it whole. */
    private fun displayName(name: String): Pair<String, Boolean> = truncateDisplayName(name, full)
}

/** Names longer than this are truncated by default (e.g. the Kotlin daemon classpath). */
internal const val MAX_DISPLAY_NAME = 120

/**
 * Truncates [name] to [MAX_DISPLAY_NAME] chars plus an ellipsis unless [full] is set. Returns the
 * text to display and whether it was truncated. The full name is always used for matching/`--wait`;
 * only the rendered output is shortened.
 */
internal fun truncateDisplayName(name: String, full: Boolean): Pair<String, Boolean> {
    if (full || name.length <= MAX_DISPLAY_NAME) return name to false
    return (name.take(MAX_DISPLAY_NAME) + "…") to true
}
