package io.github.takahirom.clijvm

/**
 * A dummy CPU-bound JVM used as an integration-test fixture. It is started without any
 * agent or JFR flags, so it exercises the same "attach to an unconfigured JVM" path the
 * tool targets in practice.
 */
fun main() {
    println("[target] started pid=${ProcessHandle.current().pid()}")
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
