package io.github.takahirom.clijvm.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import io.github.takahirom.clijvm.session.SessionStore

/** `clijvm memory start <target>` — begin a background recording (shared with cpu) and persist it. */
class MemoryStartCommand(
    private val sessions: SessionStore = SessionStore(),
) : CliktCommand(
    name = "start",
    help = "Start a background recording on the target JVM and exit. " +
        "cpu and memory share one recording per process.",
) {
    private val target by argument(
        name = "target",
        help = "A pid, or a case-insensitive substring of a process display name.",
    )

    override fun run() = doStart(target, sessions)
}
