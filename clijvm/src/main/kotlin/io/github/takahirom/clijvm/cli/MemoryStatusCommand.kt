package io.github.takahirom.clijvm.cli

import com.github.ajalt.clikt.core.CliktCommand
import io.github.takahirom.clijvm.session.SessionStore

/**
 * `clijvm memory status` — list active background sessions and clean up dead/corrupt ones.
 *
 * Sessions are shared with `cpu`, so this shows the same recordings as `cpu status`.
 */
class MemoryStatusCommand(
    private val sessions: SessionStore = SessionStore(),
) : CliktCommand(
    name = "status",
    help = "List active background recording sessions; prune dead and corrupt ones.",
) {
    override fun run() = doStatus(sessions)
}
