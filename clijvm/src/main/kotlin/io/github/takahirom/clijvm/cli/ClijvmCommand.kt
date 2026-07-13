package io.github.takahirom.clijvm.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.versionOption

/** Root command: `clijvm`. */
class ClijvmCommand : CliktCommand(
    name = "clijvm",
    help = "AI-friendly JVM profiler powered by JDK Flight Recorder.",
    // Mordant renders the epilog as Markdown and collapses whitespace runs, so we rely on dash
    // separators (not column alignment) to keep the workflow readable.
    epilog = """
        Workflow:

        1. clijvm list — find the target JVM (or use --wait / name matching)

        2. clijvm profile <pid|name> — record 30s across all axes, get hints

        3. follow the report's 'Drill in:' line to go deeper

        Playbooks: clijvm guide slow-tests | slow-robolectric-tests | slow-server | slow-build | short-lived | reading
    """.trimIndent(),
    printHelpOnEmptyArgs = true,
) {
    init {
        versionOption(clijvmVersion())
    }

    override fun run() = Unit
}

/**
 * The build version, read from the jar manifest's `Implementation-Version` (stamped by Gradle from
 * the release tag). Falls back to `dev` for classpath/IDE runs without a packaged manifest.
 */
fun clijvmVersion(): String =
    ClijvmCommand::class.java.`package`?.implementationVersion ?: "dev"
