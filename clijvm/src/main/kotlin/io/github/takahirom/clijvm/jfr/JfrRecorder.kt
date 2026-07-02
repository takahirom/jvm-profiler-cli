package io.github.takahirom.clijvm.jfr

import io.github.takahirom.clijvm.util.Jcmd
import java.nio.file.Path

/**
 * Controls a JFR recording on a target JVM via `jcmd`.
 *
 * The recording is driven entirely from the target side, so the flow is:
 * [start] -> wait -> [dump] -> [stop]. Attach is verified separately by the caller.
 */
class JfrRecorder(
    private val pid: Long,
    private val recordingName: String = "clijvm",
) {
    /** Starts a recording using a built-in settings profile (`profile` or `default`). */
    fun start(settings: String = "profile") {
        Jcmd.run(pid, "JFR.start", "name=$recordingName", "settings=$settings")
    }

    /**
     * Starts a recording that also writes to disk and auto-dumps to [dumpOnExit] when the target
     * JVM exits. This lets a later `stop` salvage a partial recording if the target dies first.
     */
    fun startWithDumpOnExit(dumpOnExit: Path, settings: String = "profile") {
        Jcmd.run(
            pid,
            "JFR.start",
            "name=$recordingName",
            "settings=$settings",
            "disk=true",
            "dumponexit=true",
            "filename=${dumpOnExit.toAbsolutePath()}",
        )
    }

    /** Writes the current recording contents to [file]. */
    fun dump(file: Path) {
        Jcmd.run(pid, "JFR.dump", "name=$recordingName", "filename=${file.toAbsolutePath()}")
    }

    /** Stops and discards the in-JVM recording (the dumped file is unaffected). */
    fun stop() {
        Jcmd.run(pid, "JFR.stop", "name=$recordingName")
    }
}
