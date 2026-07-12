package io.github.takahirom.clijvm.util

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Raised when a `jcmd` invocation against a target JVM fails. */
class JcmdException(message: String) : RuntimeException(message)

/** Runs diagnostic commands against a target JVM by shelling out to `jcmd`. */
object Jcmd {

    /**
     * Runs `jcmd <pid> <args...>` and returns its trimmed output.
     *
     * @throws JcmdException if the command exits non-zero or its output looks like a failure
     *   (jcmd historically exits 0 even when the diagnostic command itself failed).
     */
    fun run(
        pid: Long,
        vararg args: String,
        jcmdPath: String = javaTool("jcmd"),
        /** Kills a stalled jcmd after this long, so callers (e.g. pollers) never block forever. */
        timeoutMs: Long = 30_000,
    ): String {
        val command = listOf(jcmdPath, pid.toString()) + args
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        // Destroying the process on timeout unblocks the read below with EOF.
        val timedOut = AtomicBoolean(false)
        val watchdog = Thread {
            try {
                if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    timedOut.set(true)
                    process.destroyForcibly()
                }
            } catch (_: InterruptedException) {
                // Normal completion path: the main thread finished reading and interrupted us.
            }
        }.apply { isDaemon = true; start() }
        val output = process.inputStream.bufferedReader().readText().trim()
        val exitCode = process.waitFor()
        watchdog.interrupt()
        if (timedOut.get()) {
            throw JcmdException("jcmd $pid ${args.joinToString(" ")} timed out after ${timeoutMs}ms and was killed")
        }
        if (exitCode != 0 || looksLikeFailure(output)) {
            throw JcmdException("jcmd $pid ${args.joinToString(" ")} failed (exit $exitCode):\n$output")
        }
        return output
    }

    private fun looksLikeFailure(output: String): Boolean {
        val lower = output.lowercase()
        return lower.startsWith("exception") ||
            lower.contains("could not") ||
            lower.contains("no such") ||
            lower.contains("not found")
    }
}
