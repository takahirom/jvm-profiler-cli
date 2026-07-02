package io.github.takahirom.clijvm.util

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
    fun run(pid: Long, vararg args: String, jcmdPath: String = javaTool("jcmd")): String {
        val command = listOf(jcmdPath, pid.toString()) + args
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val exitCode = process.waitFor()
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
