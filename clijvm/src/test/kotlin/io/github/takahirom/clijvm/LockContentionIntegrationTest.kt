package io.github.takahirom.clijvm

import com.sun.tools.attach.VirtualMachine
import io.github.takahirom.clijvm.analysis.JfrAnalyzer
import io.github.takahirom.clijvm.analysis.ThreadStatePoller
import io.github.takahirom.clijvm.jfr.JfrRecorder
import io.github.takahirom.clijvm.render.OutputFormat
import io.github.takahirom.clijvm.render.RenderOptions
import io.github.takahirom.clijvm.render.Renderers
import io.github.takahirom.clijvm.util.RecordingMeta
import io.github.takahirom.clijvm.util.readRecordingMeta
import io.github.takahirom.clijvm.util.writeRecordingMeta
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Reproduces the monopolized-lock gap: eight workers barge on one intrinsic monitor, so JFR sees
 * ~zero `jdk.JavaMonitorEnter` events. Asserts that the thread-state poller still surfaces the
 * contention (named monitor class, estimated), which is exactly what the eval exposed as missing.
 */
class LockContentionIntegrationTest {

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @Test
    fun `thread-state polling surfaces a monopolized lock that JFR misses`() {
        val javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val classpath = System.getProperty("java.class.path")
        val child = ProcessBuilder(
            javaExecutable, "-cp", classpath, "io.github.takahirom.clijvm.MonopolyLockTargetKt",
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()

        val workDir = Files.createTempDirectory("clijvm-lock-it")
        try {
            Thread.sleep(1500)
            VirtualMachine.attach(child.pid().toString()).detach()

            val recorder = JfrRecorder(child.pid())
            val poller = ThreadStatePoller.forDuration(child.pid(), 5000)
            recorder.start()
            poller.start()
            Thread.sleep(5000)
            val recordingFile = workDir.resolve("recording.jfr")
            recorder.dump(recordingFile)
            recorder.stop()
            val polled = poller.stop(5000)

            val result = JfrAnalyzer.analyze(
                recordingFile, pid = child.pid(), polledContention = polled,
            )

            val lock = result.lockContention
            assertTrue(lock != null, "expected lock contention to be detected via polling")
            val ledger = lock.monitors.firstOrNull { it.className.contains("ContendedLedger") }
            assertTrue(
                ledger != null,
                "expected ContendedLedger among contended monitors but saw ${lock.monitors.map { it.className }}",
            )
            // Whether the lock rotates enough for JFR to commit enter events depends on core count
            // and scheduling, so only require the sampled estimate when JFR genuinely missed it —
            // that is the gap the poller exists to close.
            val jfrOnly = JfrAnalyzer.analyze(recordingFile, pid = child.pid())
            val jfrSawIt = jfrOnly.lockContention?.monitors.orEmpty().any { it.className.contains("ContendedLedger") }
            if (!jfrSawIt) {
                assertTrue(
                    ledger.estimated,
                    "JFR recorded no enter events for ContendedLedger, so contention must be a sampled estimate",
                )
            }
            assertTrue(ledger.topBlockedThreads.any { it.startsWith("ledger-worker-") }, "${ledger.topBlockedThreads}")

            // It must render in the --waits view, marked as sampled when it came from the poller.
            val waitsReport = Renderers.render(result, OutputFormat.SUMMARY, options = RenderOptions(waits = true))
            assertTrue(waitsReport.contains("Contended locks"), waitsReport)
            assertTrue(waitsReport.contains("ContendedLedger"), waitsReport)
            if (ledger.estimated) assertTrue(waitsReport.contains("sampled"), waitsReport)

            // Report-path round-trip: persist the sampled contention to the sidecar, then re-analyze
            // the saved recording the way `report --waits` does (polled data read back from meta,
            // NOT the in-memory `polled`). The contended-locks section must survive.
            writeRecordingMeta(
                recordingFile,
                RecordingMeta(child.pid(), "MonopolyLockTarget", startedAt = 1, partial = false, sampledContention = polled),
            )
            val reloaded = readRecordingMeta(recordingFile)
            assertTrue(reloaded?.sampledContention != null, "sidecar should carry sampled contention")
            val reportResult = JfrAnalyzer.analyze(
                recordingFile, pid = child.pid(), polledContention = reloaded!!.sampledContention,
            )
            val reportWaits = Renderers.render(reportResult, OutputFormat.SUMMARY, options = RenderOptions(waits = true))
            assertTrue(reportWaits.contains("Contended locks"), reportWaits)
            assertTrue(reportWaits.contains("ContendedLedger"), reportWaits)
            if (!jfrSawIt) assertTrue(reportWaits.contains("sampled"), reportWaits)
        } finally {
            child.destroyForcibly()
            runCatching { workDir.deleteRecursively() }
        }
    }
}
