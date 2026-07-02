package io.github.takahirom.clijvm.cli

import com.github.ajalt.clikt.core.CliktCommand

/** Root command: `clijvm`. */
class ClijvmCommand : CliktCommand(
    name = "clijvm",
    help = "AI-friendly JVM profiler powered by JDK Flight Recorder.",
    printHelpOnEmptyArgs = true,
) {
    override fun run() = Unit
}
