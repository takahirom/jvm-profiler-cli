package io.github.takahirom.clijvm

import io.github.takahirom.clijvm.analysis.ContendedMonitor
import io.github.takahirom.clijvm.analysis.LockContentionStats
import io.github.takahirom.clijvm.analysis.ThreadStatePoller
import io.github.takahirom.clijvm.analysis.aggregate
import io.github.takahirom.clijvm.analysis.mergeLockContention
import io.github.takahirom.clijvm.analysis.parseThreadDump
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThreadStatePollerTest {

    // A canned `jcmd Thread.print` excerpt: two contended monitors — one with a known owner and two
    // blocked threads, one with a blocked thread but no owner shown in the dump.
    private val dump = """
        "owner-A" #10 [111] daemon prio=5 os_prio=31 cpu=99ms elapsed=2s tid=0x01 nid=111 runnable  [0x0001]
           java.lang.Thread.State: RUNNABLE
        	at com.example.Ledger.post(Ledger.java:20)
        	- locked <0x00000000aaaa0000> (a com.example.Ledger)
        	at com.example.App.run(App.java:5)

        "w1" #11 [112] daemon prio=5 os_prio=31 cpu=0.1ms elapsed=2s tid=0x02 nid=112 waiting for monitor entry  [0x0002]
           java.lang.Thread.State: BLOCKED (on object monitor)
        	at com.example.Ledger.post(Ledger.java:18)
        	- waiting to lock <0x00000000aaaa0000> (a com.example.Ledger)
        	at com.example.App.run(App.java:5)

        "w2" #12 [113] daemon prio=5 os_prio=31 cpu=0.1ms elapsed=2s tid=0x03 nid=113 waiting for monitor entry  [0x0003]
           java.lang.Thread.State: BLOCKED (on object monitor)
        	at com.example.Ledger.post(Ledger.java:18)
        	- waiting to lock <0x00000000aaaa0000> (a com.example.Ledger)

        "w3" #13 [114] daemon prio=5 os_prio=31 cpu=0.1ms elapsed=2s tid=0x04 nid=114 waiting for monitor entry  [0x0004]
           java.lang.Thread.State: BLOCKED (on object monitor)
        	at com.example.Cache.get(Cache.java:9)
        	- waiting to lock <0x00000000bbbb0000> (a com.example.Cache)
    """.trimIndent()

    @Test
    fun `parses blocked threads, owners, classes, and missing owners`() {
        val parsed = parseThreadDump(dump)
        assertEquals(2, parsed.monitors.size, "${parsed.monitors.keys}")

        val ledger = parsed.monitors.getValue("0x00000000aaaa0000")
        assertEquals("com.example.Ledger", ledger.className)
        assertEquals(setOf("w1", "w2"), ledger.blockedThreads.keys)
        assertEquals("owner-A", ledger.owner)
        // Blocked stack is the frames above the "waiting to lock" line, leaf-first.
        assertEquals("com.example.Ledger.post(Ledger.java:18)", ledger.blockedThreads.getValue("w1").first())

        val cache = parsed.monitors.getValue("0x00000000bbbb0000")
        assertEquals("com.example.Cache", cache.className)
        assertEquals(setOf("w3"), cache.blockedThreads.keys)
        assertNull(cache.owner, "no owner is shown for the Cache monitor")
    }

    @Test
    fun `aggregate estimates blocked time per class from snapshot hits`() {
        val snapshots = List(4) { parseThreadDump(dump) }
        val stats = aggregate(snapshots, durationMs = 10_000)!!
        // Ledger: two threads blocked in all 4 snapshots -> ~(1 + 1) * 10s = 20s; Cache: one -> ~10s.
        val ledger = stats.monitors.first { it.className == "com.example.Ledger" }
        val cache = stats.monitors.first { it.className == "com.example.Cache" }
        assertEquals(20_000.0, ledger.totalBlockedMs, 1.0)
        assertEquals(10_000.0, cache.totalBlockedMs, 1.0)
        assertEquals(8, ledger.events) // 2 threads x 4 snapshots of blocked observations
        assertEquals(listOf("com.example.Ledger", "com.example.Cache"), stats.monitors.map { it.className })
        assertTrue(ledger.estimated, "polled monitors are flagged estimated")
        assertEquals("owner-A", ledger.ownerThread)
        assertEquals(setOf("w1", "w2"), ledger.topBlockedThreads.toSet())
    }

    @Test
    fun `aggregate returns null when no snapshots or no contention`() {
        assertNull(aggregate(emptyList(), 10_000))
        assertNull(aggregate(List(3) { parseThreadDump("\"main\" #1\n   java.lang.Thread.State: RUNNABLE\n") }, 10_000))
    }

    private fun exact(cls: String, ms: Double) =
        ContendedMonitor(cls, ms, events = 12, topBlockedThreads = listOf("t"), ownerThread = "db", stack = emptyList())

    private fun estimated(cls: String, ms: Double) =
        ContendedMonitor(cls, ms, events = 5, topBlockedThreads = listOf("w"), ownerThread = null, stack = emptyList(), estimated = true)

    @Test
    fun `merge prefers exact JFR data per class and adds poller-only monitors`() {
        val jfr = LockContentionStats(listOf(exact("com.example.Ledger", 5_000.0)), 5_000.0)
        val polled = LockContentionStats(
            listOf(estimated("com.example.Ledger", 20_000.0), estimated("com.example.Cache", 10_000.0)),
            30_000.0,
        )
        val merged = mergeLockContention(jfr, polled)!!
        // Ledger keeps its exact JFR value (not the estimate); Cache is added from the poller.
        val ledger = merged.monitors.first { it.className == "com.example.Ledger" }
        val cache = merged.monitors.first { it.className == "com.example.Cache" }
        assertEquals(5_000.0, ledger.totalBlockedMs, 0.001)
        assertTrue(!ledger.estimated, "JFR data wins and stays exact")
        assertTrue(cache.estimated, "poller-only monitor is estimated")
        assertEquals(15_000.0, merged.totalBlockedMs, 0.001)
        // Sorted by blocked time desc: Cache (10s) before Ledger (5s).
        assertEquals(listOf("com.example.Cache", "com.example.Ledger"), merged.monitors.map { it.className })
    }

    @Test
    fun `merge handles either source being null`() {
        val jfr = LockContentionStats(listOf(exact("A", 1_000.0)), 1_000.0)
        val polled = LockContentionStats(listOf(estimated("B", 2_000.0)), 2_000.0)
        assertNull(mergeLockContention(null, null))
        assertEquals(listOf("A"), mergeLockContention(jfr, null)!!.monitors.map { it.className })
        assertEquals(listOf("B"), mergeLockContention(null, polled)!!.monitors.map { it.className })
    }

    @Test
    fun `poller runtime collects snapshots from the provider and aggregates them`() {
        // Drive the runtime with a stub provider (no real jcmd), a tiny interval, and stop quickly.
        val poller = ThreadStatePoller(pid = 0, intervalMs = 20, dumpProvider = { dump })
        poller.start()
        Thread.sleep(150)
        val stats = poller.stop(recordingDurationMs = 8_000)!!
        assertTrue(stats.monitors.any { it.className == "com.example.Ledger" }, "${stats.monitors}")
        assertTrue(stats.monitors.all { it.estimated })
    }
}
