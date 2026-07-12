package io.github.takahirom.clijvm

/**
 * Integration-test fixture reproducing the monopolized-lock case (mirrors eval/LockContentionEval):
 * eight workers fight over one [ContendedLedger] monitor, each holding it for ~5ms of CPU busy-work
 * (no sleeping inside the lock). Because intrinsic locks are unfair, one worker barges and the rest
 * stay BLOCKED — so JFR emits ~zero `jdk.JavaMonitorEnter` events and only thread-state polling
 * reveals the contention.
 */
fun main() {
    println("[monopoly-lock-target] started pid=${ProcessHandle.current().pid()}")
    val ledger = ContendedLedger()
    repeat(8) { id ->
        Thread({ while (true) ledger.post(id) }, "ledger-worker-$id").apply { isDaemon = true }.start()
    }
    while (true) Thread.sleep(1000)
}

/** A monitor held for ~5ms of CPU work per call — genuinely contended when many threads call it. */
class ContendedLedger {
    private var balance = 0L

    @Synchronized
    fun post(amount: Int) {
        val deadline = System.nanoTime() + 5_000_000L // ~5ms of busy-work while holding the lock
        var acc = balance
        while (System.nanoTime() < deadline) {
            acc = (acc * 1_000_003L + amount) xor (acc ushr 7)
        }
        balance = acc
    }
}
