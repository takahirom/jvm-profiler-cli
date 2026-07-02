package io.github.takahirom.clijvm

import io.github.takahirom.clijvm.attach.JvmProcess
import io.github.takahirom.clijvm.attach.TargetResolutionException
import io.github.takahirom.clijvm.attach.TargetResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TargetResolverTest {
    private val processes = listOf(
        JvmProcess(100, "org.gradle.process.internal.worker.GradleWorkerMain", isGradleWorker = true),
        JvmProcess(200, "com.example.WebServer --port 8080", isGradleWorker = false),
        JvmProcess(300, "com.example.WebWorker", isGradleWorker = false),
    )

    @Test
    fun `resolves by pid`() {
        assertEquals(200, TargetResolver.resolve("200", processes).pid)
    }

    @Test
    fun `resolves by unique name substring`() {
        assertEquals(100, TargetResolver.resolve("GradleWorkerMain", processes).pid)
    }

    @Test
    fun `unknown pid fails`() {
        assertFailsWith<TargetResolutionException> { TargetResolver.resolve("999", processes) }
    }

    @Test
    fun `no name match fails`() {
        assertFailsWith<TargetResolutionException> { TargetResolver.resolve("Database", processes) }
    }

    @Test
    fun `excludes clijvm itself from name matching`() {
        val withSelf = processes + JvmProcess(
            999,
            "io.github.takahirom.clijvm.MainKt cpu --wait WebServer",
            isGradleWorker = false,
        )
        // "WebServer" appears in both clijvm's own args and the real target; only the target resolves.
        assertEquals(200, TargetResolver.resolve("WebServer", withSelf).pid)
    }

    @Test
    fun `ambiguous name match lists candidates`() {
        val error = assertFailsWith<TargetResolutionException> { TargetResolver.resolve("Web", processes) }
        val message = error.message.orEmpty()
        assertEquals(true, message.contains("200"))
        assertEquals(true, message.contains("300"))
    }
}
