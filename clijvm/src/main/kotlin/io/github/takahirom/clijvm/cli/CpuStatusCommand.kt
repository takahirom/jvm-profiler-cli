package io.github.takahirom.clijvm.cli

import com.github.ajalt.clikt.core.CliktCommand
import io.github.takahirom.clijvm.session.SessionStore

/** `clijvm cpu status` — list active background sessions and clean up dead/corrupt ones. */
class CpuStatusCommand(
    private val sessions: SessionStore = SessionStore(),
) : CliktCommand(
    name = "status",
    help = "List active background recording sessions; prune dead and corrupt ones.",
) {
    override fun run() = doStatus(sessions)
}
