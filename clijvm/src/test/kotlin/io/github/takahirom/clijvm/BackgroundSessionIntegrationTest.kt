package io.github.takahirom.clijvm

import com.sun.tools.attach.VirtualMachine
import io.github.takahirom.clijvm.analysis.JfrAnalyzer
import io.github.takahirom.clijvm.jfr.JfrRecorder
import io.github.takahirom.clijvm.session.Session
import io.github.takahirom.clijvm.session.SessionStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mirrors the `cpu start` -> wait -> `cpu stop` flow against a spawned BusyTarget child:
 * persist a session, record in the background, then stop via the session and report.
 */
class BackgroundSessionIntegrationTest {

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @Test
    fun `start then stop against a busy child reports the hot method`() {
        val javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val classpath = System.getProperty("java.class.path")

        val child = ProcessBuilder(
            javaExecutable, "-cp", classpath, "io.github.takahirom.clijvm.BusyTargetKt",
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()

        val workDir = Files.createTempDirectory("clijvm-bg-it")
        val sessions = SessionStore(workDir.resolve("sessions"))
        try {
            Thread.sleep(1500)
            VirtualMachine.attach(child.pid().toString()).detach()

            // start: begin recording and persist the session.
            val recordingName = "clijvm-${child.pid()}"
            JfrRecorder(child.pid(), recordingName).start()
            sessions.save(Session(child.pid(), recordingName, System.currentTimeMillis(), "BusyTarget"))
            assertTrue(sessions.exists(child.pid()))

            Thread.sleep(3000)

            // stop: read the session, dump, stop, and clean up.
            val session = sessions.find(child.pid()) ?: error("session should exist")
            val recordingFile = workDir.resolve("recording.jfr")
            JfrRecorder(child.pid(), session.recordingName).apply {
                dump(recordingFile)
                stop()
            }
            sessions.delete(child.pid())
            assertNull(sessions.find(child.pid()))

            val result = JfrAnalyzer.analyze(recordingFile, pid = child.pid())
            assertTrue(result.totalSamples > 0, "expected CPU samples")
            val topMethod = result.hotMethods.first().method
            assertTrue(topMethod.contains("hotFibonacci"), "expected hotFibonacci but was '$topMethod'")
        } finally {
            child.destroyForcibly()
            runCatching { workDir.deleteRecursively() }
        }
    }
}
