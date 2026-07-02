package io.github.takahirom.clijvm

/**
 * A dummy CPU-bound JVM used as an integration-test fixture. It is started without any
 * agent or JFR flags, so it exercises the same "attach to an unconfigured JVM" path the
 * tool targets in practice.
 */
fun main() {
    println("[target] started pid=${ProcessHandle.current().pid()}")

    // A helper thread that spends its time off-CPU (parking + sleeping), so `report --waits` has
    // something to attribute to a named thread. Daemon so it never keeps the JVM alive.
    Thread({
        while (true) {
            java.util.concurrent.locks.LockSupport.parkNanos(50_000_000) // 50 ms park
            Thread.sleep(50) // 50 ms sleep
        }
    }, "clijvm-waiter").apply { isDaemon = true }.start()

    val end = System.currentTimeMillis() + 120_000
    var acc = 0L
    while (System.currentTimeMillis() < end) {
        acc += hotFibonacci(28)
        acc += allocateStrings()
    }
    println(acc)
}

fun hotFibonacci(n: Int): Long =
    if (n < 2) n.toLong() else hotFibonacci(n - 1) + hotFibonacci(n - 2)

fun allocateStrings(): Long =
    (0 until 1000).map { "str$it" }.sumOf { it.length.toLong() }
