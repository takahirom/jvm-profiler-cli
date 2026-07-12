package io.github.takahirom.clijvm.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import io.github.takahirom.clijvm.render.RenderOptions
import io.github.takahirom.clijvm.render.ReportView
import io.github.takahirom.clijvm.session.SessionStore
import io.github.takahirom.clijvm.util.parseDuration
import java.time.Duration

/**
 * `clijvm profile <target> [--duration 30s]` — the canonical entry point.
 *
 * Records the same JFR profile as `cpu`/`memory` but renders the FULL report (CPU + allocation +
 * GC + class loading + heap trend), so every axis is visible from one command. `cpu` and `memory`
 * are focused views of this same recording. Also the parent of the background verbs
 * `start`, `stop`, and `status`; when one of those is invoked, this command does nothing.
 */
class ProfileCommand : CliktCommand(
    name = "profile",
    help = "Profile a running JVM across all axes (CPU, allocation, GC, waits) and print a summary. " +
        "The recommended first command; cpu/memory are focused views of the same full recording.",
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
            "Recommended for short-lived Gradle test workers, e.g. --wait GradleWorkerMain.",
    )
    private val waitTimeout by option("--wait-timeout", help = "How long to wait with --wait (default 120s).")
        .convert { runCatching { parseDuration(it) }.getOrElse { e -> fail(e.message ?: "invalid duration") } }
        .default(Duration.ofSeconds(120))
    private val format by option("--format", help = "Output format for the immediate report.").outputFormat()
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

    override fun run() {
        // If a subcommand (start/stop/status) was invoked, let it handle the work.
        if (currentContext.invokedSubcommand != null) return

        val process = resolveOrWait(target, wait, waitTimeout)
        val options = RenderOptions(top = top, maxStackDepth = maxStackDepth, digest = digest)
        profileSynchronously(process, duration, ReportView.FULL, format, output, options)
    }
}

/** `clijvm profile stop <target>` — dump and stop a background recording, then print the FULL report. */
class ProfileStopCommand(
    private val sessions: SessionStore = SessionStore(),
) : CliktCommand(
    name = "stop",
    help = "Stop a background recording, save it, and print the full profile report.",
) {
    private val target by argument(
        name = "target",
        help = "A pid, or a case-insensitive substring of a process display name.",
    )
    private val format by option("--format", help = "Output format for the report.").outputFormat()
    private val output by option("--output", help = "Write the report to a file instead of stdout.")

    override fun run() = doStop(target, sessions, ReportView.FULL, format, output)
}
