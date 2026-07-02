package io.github.takahirom.clijvm.util

import java.nio.file.Path

/** Root directory for all clijvm state: `~/.clijvm`. */
val clijvmHome: Path
    get() = Path.of(System.getProperty("user.home"), ".clijvm")

/** Directory where raw `.jfr` recordings are saved: `~/.clijvm/recordings`. */
val recordingsDir: Path
    get() = clijvmHome.resolve("recordings")

/**
 * Resolves a JDK tool (such as `jcmd`) to an absolute path.
 *
 * Prefers `$JAVA_HOME` when set, otherwise falls back to the home of the running JVM.
 * Requires a full JDK (not a JRE) since the attach-based tools live there.
 */
fun javaTool(tool: String): String {
    val home = System.getenv("JAVA_HOME")?.takeIf { it.isNotBlank() }
        ?: System.getProperty("java.home")
    val executable = if (isWindows) "$tool.exe" else tool
    return Path.of(home, "bin", executable).toString()
}

private val isWindows: Boolean
    get() = System.getProperty("os.name").orEmpty().lowercase().contains("win")
