package io.github.takahirom.clijvm.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import io.github.takahirom.clijvm.analysis.JfrAnalyzer
import io.github.takahirom.clijvm.analysis.ThreadStatePoller
import io.github.takahirom.clijvm.attach.JvmProcess
import io.github.takahirom.clijvm.attach.ProcessWaiter
import io.github.takahirom.clijvm.jfr.JfrRecorder
import io.github.takahirom.clijvm.render.OutputFormat
import io.github.takahirom.clijvm.render.RenderOptions
import io.github.takahirom.clijvm.render.Renderers
import io.github.takahirom.clijvm.render.ReportView
import io.github.takahirom.clijvm.session.Session
import io.github.takahirom.clijvm.session.SessionEntry
import io.github.takahirom.clijvm.session.SessionStore
import io.github.takahirom.clijvm.util.JcmdException
import io.github.takahirom.clijvm.util.RecordingMeta
import io.github.takahirom.clijvm.util.recordingsDir
import io.github.takahirom.clijvm.util.writeRecordingMeta
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared implementation of the CPU and memory recording verbs.
 *
 * CPU and memory profiling use the same underlying JFR recording (settings=profile captures both
 * execution samples and allocation events), so the background verbs operate on a single shared
 * session per pid; only the rendered [ReportView] differs. That means, for example, `cpu start`
 * followed by `memory stop` on the same pid works and reports allocations from that recording.
 */

/**
 * Resolves the process to profile: either wait for one matching [wait] to appear, or resolve
 * [target] directly. `--wait` is the reliable path for short-lived Gradle test workers.
 */
fun CliktCommand.resolveOrWait(target: String?, wait: String?, waitTimeout: Duration): JvmProcess {
    if (wait != null) {
        echo("Waiting up to ${waitTimeout.toSeconds()}s for a JVM whose name contains \"$wait\" ...")
        val process = ProcessWaiter.awaitProcess(wait, waitTimeout)
            ?: throw CliktError(ProcessWaiter.timeoutMessage(wait, waitTimeout))
        echo("Matched pid ${process.pid} (${process.displayName}).")
        return process
    }
    if (target != null) return resolveTarget(target)
    throw CliktError("Provide a target pid or name, or use --wait <name-substring>.")
}

/** How often to poll the target's liveness while a synchronous recording runs. */
private const val LIVENESS_POLL_MS = 500L

/**
 * Records [process] synchronously for [duration], then dumps, stops, and prints the report.
 *
 * The recording is started with dump-on-exit, and the target's liveness is polled during the wait,
 * so if it dies mid-recording we salvage the auto-dumped file and print a PARTIAL report instead of
 * losing everything (and instead of leaking a raw attach stacktrace). Ctrl-C still dumps and reports.
 */
fun CliktCommand.profileSynchronously(
    process: JvmProcess,
    duration: Duration,
    view: ReportView,
    format: OutputFormat,
    output: String?,
    renderOptions: RenderOptions = RenderOptions.DEFAULT,
) {
    val pid = process.pid
    verifyAttach(pid, process.displayName)

    val recordingFile = newRecordingPath(pid)
    val salvageFile = recordingsDir.resolve("sync-$pid-onexit.jfr")
    Files.deleteIfExists(salvageFile)
    val recorder = JfrRecorder(pid)

    echo("Profiling pid $pid (${process.displayName}) for ${duration.toSeconds()}s ...")
    // Dump-on-exit means a mid-recording death still leaves a salvageable file behind.
    try {
        recorder.startWithDumpOnExit(salvageFile)
    } catch (e: JcmdException) {
        throw CliktError("Could not start recording on pid $pid (${process.displayName}): ${e.message}")
    }

    // Sample thread states alongside the recording so monopolized (unfair) locks — which emit zero
    // jdk.JavaMonitorEnter events — still surface as contention. Merged into the analysis below.
    val poller = ThreadStatePoller.forDuration(pid, duration.toMillis())
    poller.start()

    val finished = AtomicBoolean(false)
    val startedAt = System.currentTimeMillis()

    fun report(result: io.github.takahirom.clijvm.analysis.ProfileResult) {
        writeReport(Renderers.render(result, format, view, renderOptions), output)
        echoDrillGuidance(recordingFile, format, output, renderOptions.digest)
    }

    fun reportLive() {
        recorder.dump(recordingFile)
        recorder.stop()
        Files.deleteIfExists(salvageFile)
        val durationMs = System.currentTimeMillis() - startedAt
        val polled = runCatching { poller.stop(durationMs) }.getOrNull()
        // Persist the sampled contention so `report --waits` on this recording can show it too —
        // it lives nowhere in the .jfr (a monopolized monitor emits no JavaMonitorEnter events).
        writeRecordingMeta(
            recordingFile,
            RecordingMeta(pid, process.displayName, startedAt, partial = false, sampledContention = polled),
        )
        echo("Recording saved to $recordingFile")
        report(JfrAnalyzer.analyze(recordingFile, pid, process.displayName, durationMs, polledContention = polled))
    }

    fun reportSalvaged(diedAfterMs: Long) {
        // The target is dead, so thread-state polling can no longer reach it; drop any partial data.
        runCatching { poller.stop(diedAfterMs) }
        val salvaged = awaitSalvage(salvageFile)
            ?: throw CliktError(
                "Target JVM (pid $pid) exited before any data could be dumped. " +
                    "For short-lived processes prefer --wait with a shorter --duration."
            )
        Files.move(salvaged, recordingFile, StandardCopyOption.REPLACE_EXISTING)
        writeRecordingMeta(recordingFile, RecordingMeta(pid, process.displayName, startedAt, partial = true))
        echo(
            "Target JVM exited ${diedAfterMs / 1000}s into the ${duration.toSeconds()}s recording; " +
                "report covers the time until exit."
        )
        echo("Recording saved to $recordingFile")
        // Let the analyzer derive the duration from the recording's own timespan.
        report(JfrAnalyzer.analyze(recordingFile, pid, process.displayName, partial = true))
    }

    // Claims the single report and routes to the live or salvage path.
    fun finish(targetDead: Boolean) {
        if (!finished.compareAndSet(false, true)) return
        if (targetDead) {
            reportSalvaged(System.currentTimeMillis() - startedAt)
            return
        }
        try {
            reportLive()
        } catch (e: JcmdException) {
            // A dump/stop failed — almost always the target died just now. Salvage instead of erroring.
            if (isProcessAlive(pid)) throw CliktError("Recording on pid $pid failed: ${e.message}")
            reportSalvaged(System.currentTimeMillis() - startedAt)
        }
    }

    // Ctrl-C: dump, stop, and report the live target.
    val shutdownHook = Thread { runCatching { finish(targetDead = false) } }
    Runtime.getRuntime().addShutdownHook(shutdownHook)
    try {
        val deadline = startedAt + duration.toMillis()
        while (System.currentTimeMillis() < deadline) {
            if (!isProcessAlive(pid)) {
                finish(targetDead = true)
                return
            }
            Thread.sleep((deadline - System.currentTimeMillis()).coerceIn(1, LIVENESS_POLL_MS))
        }
        finish(targetDead = !isProcessAlive(pid))
    } finally {
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
    }
}

/** Waits briefly for a JVM's dump-on-exit file to appear and be non-empty; null if it never does. */
private fun awaitSalvage(file: Path): Path? {
    repeat(20) {
        if (Files.exists(file) && Files.size(file) > 0) return file
        Thread.sleep(100)
    }
    return file.takeIf { Files.exists(it) && Files.size(it) > 0 }
}

/**
 * Prints one self-teaching line pointing at the drill-down commands, in the same style as
 * `report`. Only for a summary printed to stdout — it must not corrupt json/collapsed or a file.
 * In digest mode there are no #N indices yet, so it points at the default report first.
 */
private fun CliktCommand.echoDrillGuidance(
    recording: Path,
    format: OutputFormat,
    output: String?,
    digest: Boolean,
) {
    if (format != OutputFormat.SUMMARY || output != null) return
    echo(drillGuidance(recording.toString(), digest))
}

/**
 * Builds the self-teaching drill-down line. Digest output carries no #N indices yet, so it points
 * at the default report first; the non-digest line references the #N labels already printed.
 */
internal fun drillGuidance(recording: String, digest: Boolean): String =
    if (digest) {
        "Drill in: run 'clijvm report $recording' first to get #N indices, " +
            "then --method N / --site N / --thread N (or --waits for off-CPU time)."
    } else {
        "Drill in: clijvm report $recording --method N (see #N above), " +
            "--waits for off-CPU time, or --digest for takeaways."
    }

/** Starts a background recording (with dump-on-exit salvage) and persists its session. */
fun CliktCommand.doStart(target: String, sessions: SessionStore) {
    val process = resolveTarget(target)
    val pid = process.pid

    if (sessions.exists(pid)) {
        throw CliktError(
            "A recording session already exists for pid $pid. Stop it first with 'clijvm cpu stop $pid' " +
                "(cpu and memory share one recording per process)."
        )
    }

    verifyAttach(pid, process.displayName)

    Files.createDirectories(recordingsDir)
    val recordingName = "clijvm-$pid"
    // If the target exits before we stop, JFR auto-dumps here so we can still salvage a report.
    val dumpOnExit = recordingsDir.resolve("session-$pid-onexit.jfr")
    Files.deleteIfExists(dumpOnExit)

    try {
        JfrRecorder(pid, recordingName).startWithDumpOnExit(dumpOnExit)
    } catch (e: JcmdException) {
        throw CliktError("Could not start background recording on pid $pid (${process.displayName}): ${e.message}")
    }
    sessions.save(
        Session(
            pid = pid,
            recordingName = recordingName,
            startEpochMs = System.currentTimeMillis(),
            mainClass = process.displayName,
            dumpOnExitPath = dumpOnExit.toString(),
        )
    )

    echo("Started background recording on pid $pid (${process.displayName}).")
    echo("Stop and report with: clijvm cpu stop $pid  (or: clijvm memory stop $pid)")
}

/** Stops a background recording, saves it, and prints the report using [view]. */
fun CliktCommand.doStop(
    target: String,
    sessions: SessionStore,
    view: ReportView,
    format: OutputFormat,
    output: String?,
) {
    // Resolve to a pid without requiring the process to be alive when a numeric pid is given,
    // so we can still salvage or clean up sessions whose target has already exited.
    val pid = target.toLongOrNull() ?: resolveTarget(target).pid

    val session = sessions.find(pid)
        ?: throw CliktError("No active recording session for pid $pid. Run 'clijvm cpu status'.")

    if (!isProcessAlive(pid)) {
        sessions.delete(pid)
        salvagePartial(session, view, format, output)
        return
    }

    val recordingFile = newRecordingPath(pid)
    val recorder = JfrRecorder(pid, session.recordingName)
    try {
        recorder.dump(recordingFile)
        recorder.stop()
    } catch (e: JcmdException) {
        // Target died between the liveness check and the dump; fall back to the dump-on-exit file.
        if (isProcessAlive(pid)) throw CliktError("Could not stop recording on pid $pid: ${e.message}")
        sessions.delete(pid)
        salvagePartial(session, view, format, output)
        return
    }
    sessions.delete(pid)
    // The live dump succeeded, so the dump-on-exit safety file is no longer needed.
    session.dumpOnExitPath?.let { runCatching { Files.deleteIfExists(Path.of(it)) } }

    val durationMs = System.currentTimeMillis() - session.startEpochMs
    writeRecordingMeta(
        recordingFile,
        RecordingMeta(pid, session.mainClass, session.startEpochMs, partial = false),
    )
    val result = JfrAnalyzer.analyze(recordingFile, pid, session.mainClass, durationMs)
    echo("Recording saved to $recordingFile")
    writeReport(Renderers.render(result, format, view), output)
}

/** Attempts to salvage a partial report from a dead target's dump-on-exit file. */
private fun CliktCommand.salvagePartial(
    session: Session,
    view: ReportView,
    format: OutputFormat,
    output: String?,
) {
    val onExit = session.dumpOnExitPath?.let { Path.of(it) }
    if (onExit == null || !Files.exists(onExit) || Files.size(onExit) == 0L) {
        throw CliktError(
            "Target pid ${session.pid} is no longer running and no salvageable recording was found; " +
                "removed the stale session. (For short-lived processes, prefer synchronous " +
                "'cpu <target> --duration' or '--wait'.)"
        )
    }

    val salvaged = newRecordingPath(session.pid)
    Files.move(onExit, salvaged, StandardCopyOption.REPLACE_EXISTING)
    echo("Target pid ${session.pid} had already exited; salvaged its dump-on-exit recording.")
    writeRecordingMeta(
        salvaged,
        RecordingMeta(session.pid, session.mainClass, session.startEpochMs, partial = true),
    )
    // Let the analyzer derive the duration from the recording's own timespan.
    val result = JfrAnalyzer.analyze(salvaged, session.pid, session.mainClass, partial = true)
    echo("Recording saved to $salvaged")
    writeReport(Renderers.render(result, format, view), output)
}

/** Lists active sessions and prunes dead and corrupt ones. */
fun CliktCommand.doStatus(sessions: SessionStore) {
    val live = mutableListOf<Session>()
    for (entry in sessions.list()) {
        when (entry) {
            is SessionEntry.Corrupt -> {
                sessions.deleteFile(entry.path)
                echo("Removed corrupt session file ${entry.path} (${entry.reason}).")
            }
            is SessionEntry.Valid -> {
                val session = entry.session
                if (isProcessAlive(session.pid)) {
                    live += session
                } else {
                    sessions.delete(session.pid)
                    val salvageable = session.dumpOnExitPath
                        ?.let { Path.of(it) }
                        ?.let { Files.exists(it) && Files.size(it) > 0 } == true
                    val hint = if (salvageable) {
                        " A dump-on-exit recording is available; run 'clijvm cpu stop ${session.pid}' to salvage it."
                    } else {
                        ""
                    }
                    echo("Removed stale session for pid ${session.pid} (process is no longer running).$hint")
                }
            }
        }
    }

    if (live.isEmpty()) {
        echo("No active recording sessions.")
        return
    }

    val now = System.currentTimeMillis()
    val pidWidth = maxOf(3, live.maxOf { it.pid.toString().length })
    echo("%-${pidWidth}s  %-9s  %s".format("PID", "ELAPSED", "MAIN CLASS / DISPLAY NAME"))
    live.sortedBy { it.pid }.forEach { s ->
        val elapsed = formatElapsed(now - s.startEpochMs)
        echo("%-${pidWidth}d  %-9s  %s".format(s.pid, elapsed, s.mainClass ?: "(unknown)"))
    }
}

private fun formatElapsed(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "%dh%02dm%02ds".format(hours, minutes, seconds)
        minutes > 0 -> "%dm%02ds".format(minutes, seconds)
        else -> "%ds".format(seconds)
    }
}
