package io.github.takahirom.clijvm.attach

import java.time.Duration

/**
 * Waits for a JVM whose display name matches a substring to appear.
 *
 * This is the key mechanism for short-lived Gradle test workers: start clijvm first, then poll
 * `VirtualMachine.list()` until the worker shows up and attach immediately.
 */
object ProcessWaiter {

    /** Processes whose display name contains [substring] (case-insensitive). */
    fun match(substring: String, processes: List<JvmProcess>): List<JvmProcess> =
        processes.filter { it.displayName.contains(substring, ignoreCase = true) }

    /**
     * Polls until a process matches [substring] or [timeout] elapses. Excludes clijvm itself
     * (by pid and by its process marker) so `--wait` never attaches to the tool's own process.
     *
     * The clock, sleep, and process-listing functions are injectable for testing.
     *
     * @return the lowest-pid matching process, or null on timeout.
     */
    fun awaitProcess(
        substring: String,
        timeout: Duration,
        pollInterval: Duration = Duration.ofMillis(250),
        clock: () -> Long = System::currentTimeMillis,
        sleep: (Long) -> Unit = { Thread.sleep(it) },
        list: () -> List<JvmProcess> = JvmDiscovery::list,
        selfPid: Long = ProcessHandle.current().pid(),
    ): JvmProcess? {
        val deadline = clock() + timeout.toMillis()
        while (true) {
            match(substring, list())
                .filter { it.pid != selfPid && !JvmDiscovery.isClijvmItself(it.displayName) }
                .minByOrNull { it.pid }
                ?.let { return it }
            if (clock() >= deadline) return null
            sleep(pollInterval.toMillis())
        }
    }
}
