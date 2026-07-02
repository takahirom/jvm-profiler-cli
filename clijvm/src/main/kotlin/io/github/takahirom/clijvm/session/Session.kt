package io.github.takahirom.clijvm.session

/**
 * Persistent state of a background JFR recording running inside a target JVM.
 *
 * The clijvm process that starts a recording does not stay resident; this state
 * lets a later `stop`/`status` invocation find and control the same recording.
 *
 * @param pid the target process id.
 * @param recordingName the JFR recording name used with `jcmd` (e.g. `clijvm-<pid>`).
 * @param startEpochMs when the recording was started, in epoch milliseconds.
 * @param mainClass the target's display name at start time, if known.
 * @param dumpOnExitPath file the target JVM auto-dumps to on exit (`dumponexit=true`),
 *   used to salvage a partial recording if the process dies before `stop`. Null if unknown.
 */
data class Session(
    val pid: Long,
    val recordingName: String,
    val startEpochMs: Long,
    val mainClass: String?,
    val dumpOnExitPath: String? = null,
)
