package io.github.takahirom.clijvm.attach

/** Raised when a user-supplied target cannot be resolved to exactly one JVM process. */
class TargetResolutionException(message: String) : RuntimeException(message)

/**
 * Resolves a user-supplied `<target>` into a single [JvmProcess].
 *
 * A target is either a numeric pid, or a case-insensitive substring matched against
 * process display names. Ambiguous name matches fail with the candidate list.
 */
object TargetResolver {

    fun resolve(target: String, processes: List<JvmProcess> = JvmDiscovery.list()): JvmProcess {
        val pid = target.toLongOrNull()
        if (pid != null) {
            return processes.firstOrNull { it.pid == pid }
                ?: throw TargetResolutionException(
                    "No attachable JVM found with pid $pid. Run 'clijvm list' to see available processes."
                )
        }

        // Exclude clijvm itself: its command line often contains the searched substring.
        val matches = processes.filter {
            it.displayName.contains(target, ignoreCase = true) && !JvmDiscovery.isClijvmItself(it.displayName)
        }
        return when {
            matches.isEmpty() -> throw TargetResolutionException(
                "No attachable JVM matches '$target'. Run 'clijvm list' to see available processes."
            )
            matches.size > 1 -> throw TargetResolutionException(
                buildString {
                    appendLine("Target '$target' is ambiguous and matches ${matches.size} processes:")
                    matches.forEach { appendLine("  ${it.pid}\t${it.displayName}") }
                    append("Specify a pid to disambiguate.")
                }
            )
            else -> matches.single()
        }
    }
}
