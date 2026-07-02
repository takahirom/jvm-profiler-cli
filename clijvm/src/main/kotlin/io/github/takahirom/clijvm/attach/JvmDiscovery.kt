package io.github.takahirom.clijvm.attach

import com.sun.tools.attach.VirtualMachine

/**
 * A JVM process that this tool can potentially attach to and profile.
 *
 * @param pid the operating-system process id.
 * @param displayName the main class plus arguments as reported by the attach API.
 * @param isGradleWorker true when the process looks like a Gradle test worker
 *   (where Robolectric tests run), which is the primary target of this tool.
 */
data class JvmProcess(
    val pid: Long,
    val displayName: String,
    val isGradleWorker: Boolean,
)

/** Enumerates attachable JVM processes via the JDK attach API. */
object JvmDiscovery {

    /**
     * The clijvm CLI's own main class. Used to exclude clijvm itself from name/`--wait` matching,
     * since its command line often contains the very substring being searched for
     * (e.g. `clijvm cpu --wait "Gradle Test Executor"`). This is the exact main class, not the
     * package, so profiling targets that merely share the package are not excluded.
     */
    const val CLIJVM_MARKER = "io.github.takahirom.clijvm.MainKt"

    /** True if [displayName] looks like a clijvm process (this tool), not a profiling target. */
    fun isClijvmItself(displayName: String): Boolean = displayName.contains(CLIJVM_MARKER)

    fun list(): List<JvmProcess> =
        VirtualMachine.list()
            .mapNotNull { descriptor ->
                val pid = descriptor.id().toLongOrNull() ?: return@mapNotNull null
                val name = descriptor.displayName().orEmpty().ifBlank { "(no display name)" }
                JvmProcess(pid = pid, displayName = name, isGradleWorker = isGradleWorker(name))
            }
            .sortedBy { it.pid }

    /** A Gradle test worker hosts Robolectric tests; recognise it from the display name. */
    fun isGradleWorker(displayName: String): Boolean =
        displayName.contains("GradleWorkerMain") || displayName.contains("Gradle Test Executor")
}
