package io.github.takahirom.clijvm

import com.sun.tools.attach.VirtualMachine
import io.github.takahirom.clijvm.analysis.JfrAnalyzer
import io.github.takahirom.clijvm.attach.ProcessWaiter
import io.github.takahirom.clijvm.jfr.JfrRecorder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.concurrent.thread
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the `--wait` path: the waiter starts BEFORE the target exists, the target appears a
 * moment later, and the waiter attaches and profiles it — the short-lived Gradle worker scenario.
 */
class WaitIntegrationTest {

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @Test
    fun `waits for a late-starting JVM then profiles it`() {
        val javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val classpath = System.getProperty("java.class.path")
        val workDir = Files.createTempDirectory("clijvm-wait-it")

        val childRef = java.util.concurrent.atomic.AtomicReference<Process>()
        // Spawn the target ~1.5s after the waiter begins polling.
        val spawner = thread(start = true) {
            Thread.sleep(1500)
            childRef.set(
                ProcessBuilder(
                    javaExecutable, "-cp", classpath, "io.github.takahirom.clijvm.BusyTargetKt",
                ).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            )
        }

        try {
            val matched = ProcessWaiter.awaitProcess("BusyTargetKt", timeout = Duration.ofSeconds(20))
            assertTrue(matched != null, "waiter should find the late-starting target")

            spawner.join()
            val child = childRef.get()
            assertEquals(child.pid(), matched!!.pid, "waiter should match the spawned child")

            VirtualMachine.attach(matched.pid.toString()).detach()
            val recorder = JfrRecorder(matched.pid)
            recorder.start()
            Thread.sleep(3000)
            val recordingFile = workDir.resolve("recording.jfr")
            recorder.dump(recordingFile)
            recorder.stop()

            val result = JfrAnalyzer.analyze(recordingFile, pid = matched.pid)
            assertTrue(result.hotMethods.first().method.contains("hotFibonacci"), "expected hotFibonacci hot")
        } finally {
            childRef.get()?.destroyForcibly()
            runCatching { workDir.deleteRecursively() }
        }
    }
}
