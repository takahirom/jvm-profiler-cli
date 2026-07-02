package io.github.takahirom.clijvm

import io.github.takahirom.clijvm.attach.JvmProcess
import io.github.takahirom.clijvm.attach.ProcessWaiter
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProcessWaiterTest {
    private fun proc(pid: Long, name: String) = JvmProcess(pid, name, isGradleWorker = false)

    @Test
    fun `match is case-insensitive substring`() {
        val processes = listOf(
            proc(1, "org.gradle.process.internal.worker.GradleWorkerMain"),
            proc(2, "com.example.App"),
        )
        val matched = ProcessWaiter.match("gradleworkermain", processes)
        assertEquals(listOf(1L), matched.map { it.pid })
    }

    @Test
    fun `awaitProcess returns once a matching process appears`() {
        // The process only appears on the third poll.
        val listings = ArrayDeque(
            listOf(
                emptyList(),
                listOf(proc(10, "com.example.Other")),
                listOf(proc(10, "com.example.Other"), proc(20, "Gradle Test Executor 5")),
            )
        )
        var slept = 0
        val result = ProcessWaiter.awaitProcess(
            substring = "Gradle Test Executor",
            timeout = Duration.ofSeconds(10),
            pollInterval = Duration.ofMillis(1),
            clock = { 0L },
            sleep = { slept++ },
            list = { listings.removeFirst() },
        )
        assertEquals(20L, result?.pid)
        assertEquals(2, slept)
    }

    @Test
    fun `awaitProcess ignores clijvm itself and the current process`() {
        val listing = listOf(
            proc(42, "io.github.takahirom.clijvm.MainKt cpu --wait Gradle Test Executor"),
            proc(7, "clijvm-self"),
            proc(99, "Gradle Test Executor 3"),
        )
        val result = ProcessWaiter.awaitProcess(
            substring = "Gradle Test Executor",
            timeout = Duration.ofSeconds(1),
            pollInterval = Duration.ofMillis(1),
            clock = { 0L },
            sleep = {},
            list = { listing },
            selfPid = 7,
        )
        assertEquals(99L, result?.pid)
    }

    @Test
    fun `awaitProcess returns null on timeout`() {
        val times = ArrayDeque(listOf(0L, 0L, 1000L))
        val result = ProcessWaiter.awaitProcess(
            substring = "never",
            timeout = Duration.ofMillis(500),
            pollInterval = Duration.ofMillis(1),
            clock = { times.removeFirst() },
            sleep = {},
            list = { emptyList() },
        )
        assertNull(result)
    }
}
