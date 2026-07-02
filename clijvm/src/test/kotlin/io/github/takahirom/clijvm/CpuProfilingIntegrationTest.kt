package io.github.takahirom.clijvm

import com.sun.tools.attach.VirtualMachine
import io.github.takahirom.clijvm.analysis.JfrAnalyzer
import io.github.takahirom.clijvm.jfr.JfrRecorder
import io.github.takahirom.clijvm.render.OutputFormat
import io.github.takahirom.clijvm.render.RenderOptions
import io.github.takahirom.clijvm.render.Renderers
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Spawns an unconfigured busy child JVM, profiles it for ~5s through the real recorder and
 * analyzer, and asserts that [hotFibonacci] shows up as the dominant hot method.
 */
class CpuProfilingIntegrationTest {

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @Test
    fun `profiles a busy child JVM and finds the hot method`() {
        val javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val classpath = System.getProperty("java.class.path")

        val child = ProcessBuilder(
            javaExecutable, "-cp", classpath, "io.github.takahirom.clijvm.BusyTargetKt",
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()

        val workDir = Files.createTempDirectory("clijvm-it")
        try {
            // Give the target time to start and warm up.
            Thread.sleep(1500)

            // Sanity check that attach works, mirroring what the cpu command does.
            VirtualMachine.attach(child.pid().toString()).detach()

            val recorder = JfrRecorder(child.pid())
            recorder.start()
            Thread.sleep(5000)
            val recordingFile = workDir.resolve("recording.jfr")
            recorder.dump(recordingFile)
            recorder.stop()

            assertTrue(Files.size(recordingFile) > 0, "recording file should not be empty")

            val result = JfrAnalyzer.analyze(recordingFile, pid = child.pid())
            assertTrue(result.totalSamples > 0, "expected CPU samples to be captured")

            val topMethod = result.hotMethods.first().method
            assertTrue(
                topMethod.contains("hotFibonacci"),
                "expected hotFibonacci as the top hot method but was '$topMethod'.\n" +
                    Renderers.render(result, OutputFormat.SUMMARY),
            )

            // The collapsed output should be parseable and reference the hot method.
            val collapsed = Renderers.render(result, OutputFormat.COLLAPSED)
            assertTrue(collapsed.contains("hotFibonacci"), collapsed)

            // The same recording carries allocation events; allocateStrings churns String/char[]/byte[].
            val allocation = result.allocation
            assertTrue(allocation != null, "expected allocation events in the recording")
            assertTrue(allocation!!.totalBytes > 0, "expected non-zero allocated bytes")
            val allocatedClasses = allocation.topSites.map { it.className }
            assertTrue(
                allocatedClasses.any { it.contains("String") || it.endsWith("[]") },
                "expected a String/array type among top allocation sites but was $allocatedClasses",
            )
        } finally {
            child.destroyForcibly()
            runCatching { workDir.deleteRecursively() }
        }
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @Test
    fun `captures off-CPU wait time for the parking helper thread`() {
        val javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val classpath = System.getProperty("java.class.path")
        val child = ProcessBuilder(
            javaExecutable, "-cp", classpath, "io.github.takahirom.clijvm.BusyTargetKt",
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()

        val workDir = Files.createTempDirectory("clijvm-waits-it")
        try {
            Thread.sleep(1500)
            VirtualMachine.attach(child.pid().toString()).detach()

            val recorder = JfrRecorder(child.pid())
            recorder.start()
            Thread.sleep(6000)
            val recordingFile = workDir.resolve("recording.jfr")
            recorder.dump(recordingFile)
            recorder.stop()

            val result = JfrAnalyzer.analyze(recordingFile, pid = child.pid())
            val waits = result.waits
            assertTrue(waits != null, "expected wait/park/sleep events to be captured")
            val waiter = waits!!.threads.firstOrNull { it.thread == "clijvm-waiter" }
            assertTrue(
                waiter != null,
                "expected the clijvm-waiter thread in waits but saw ${waits.threads.map { it.thread }}",
            )
            assertTrue(
                waiter!!.parkedMs > 0 || waiter.sleepMs > 0,
                "expected park or sleep time for clijvm-waiter but was $waiter",
            )
            // And it must render in the --waits view.
            val waitsReport = Renderers.render(
                result, OutputFormat.SUMMARY, options = RenderOptions(waits = true),
            )
            assertTrue(waitsReport.contains("clijvm-waiter"), waitsReport)
        } finally {
            child.destroyForcibly()
            runCatching { workDir.deleteRecursively() }
        }
    }
}
