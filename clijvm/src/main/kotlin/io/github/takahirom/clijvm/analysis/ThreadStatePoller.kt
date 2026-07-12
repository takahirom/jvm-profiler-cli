package io.github.takahirom.clijvm.analysis

import io.github.takahirom.clijvm.util.Jcmd
import java.util.Collections

/**
 * A thread-state sampling fallback for lock contention.
 *
 * `jdk.JavaMonitorEnter` events only commit when a blocked thread finally *acquires* the monitor.
 * Intrinsic (synchronized) locks are unfair — one thread can barge and monopolize the monitor for
 * the whole recording, leaving the other threads permanently BLOCKED and never emitting an enter
 * event. The JFR-event path then reports zero contention despite severe blocking.
 *
 * This poller closes that gap: while a synchronous recording runs, it periodically shells out to
 * `jcmd <pid> Thread.print` and parses which threads are BLOCKED on which monitor and who holds it.
 * Blocked time is *estimated* from how many snapshots each thread was seen blocked in, so the result
 * is marked [ContendedMonitor.estimated]. The parsing and aggregation are pure functions of their
 * inputs (see [parseThreadDump] / [aggregate]) so they can be unit-tested with canned dump text.
 */
class ThreadStatePoller(
    private val pid: Long,
    /** How long to wait between snapshots; derived from the recording duration by [forDuration]. */
    private val intervalMs: Long,
    /** Seam for tests: supplies one `Thread.print` snapshot on demand. */
    // A stalled jcmd is killed after 5s so a wedged target can't leave the daemon worker running.
    private val dumpProvider: () -> String = { Jcmd.run(pid, "Thread.print", timeoutMs = 5_000) },
) {
    private val snapshots = Collections.synchronizedList(ArrayList<ParsedDump>())
    @Volatile private var running = false
    private var worker: Thread? = null

    /** Starts sampling in a daemon thread; takes the first snapshot immediately. */
    fun start() {
        running = true
        worker = Thread({
            while (running) {
                runCatching { dumpProvider() }.getOrNull()?.let { snapshots.add(parseThreadDump(it)) }
                if (!running) break
                try {
                    Thread.sleep(intervalMs)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "clijvm-thread-poller").apply { isDaemon = true; start() }
    }

    /**
     * Stops sampling and aggregates the collected snapshots into contention stats, estimating
     * blocked time against [recordingDurationMs]. Returns null when no contention was observed.
     */
    fun stop(recordingDurationMs: Long): LockContentionStats? {
        running = false
        worker?.interrupt()
        worker?.join(2_000)
        return aggregate(snapshots.toList(), recordingDurationMs)
    }

    companion object {
        /**
         * Builds a poller whose interval yields at least a few snapshots over [recordingDurationMs]
         * (targeting ~2s spacing, but tightening for short recordings so we still get >= 3 samples).
         */
        fun forDuration(pid: Long, recordingDurationMs: Long): ThreadStatePoller {
            val interval = (recordingDurationMs / 4).coerceIn(200L, 2_000L)
            return ThreadStatePoller(pid, interval)
        }
    }
}

/** One monitor's contention as seen in a single [parseThreadDump] snapshot, keyed by object address. */
data class MonitorSnapshot(
    val address: String,
    val className: String,
    /** Blocked thread name -> its stack above the "waiting to lock" line, leaf-first. */
    val blockedThreads: Map<String, List<String>>,
    /** The thread currently holding the monitor, or null when the dump did not show an owner. */
    val owner: String?,
)

/** All contended monitors parsed from one `Thread.print` snapshot, keyed by object address. */
data class ParsedDump(val monitors: Map<String, MonitorSnapshot>)

private val THREAD_HEADER = Regex("^\"(.*?)\"\\s+#?\\d*.*")
private val WAITING_TO_LOCK = Regex("""-\s+waiting to lock\s+<(0x[0-9a-fA-F]+)>\s+\(a\s+(.+?)\)""")
private val LOCKED = Regex("""-\s+locked\s+<(0x[0-9a-fA-F]+)>\s+\(a\s+(.+?)\)""")
private val STACK_FRAME = Regex("""^\s*at\s+(.+)$""")

/** Max blocked-stack frames kept per monitor, mirroring the JFR path's shallow representative stack. */
private const val MAX_BLOCKED_STACK = 8

/**
 * Parses a `jcmd <pid> Thread.print` dump into per-monitor contention. For each thread block we read
 * the quoted name, the frames it is executing, and any `- waiting to lock` / `- locked` markers,
 * pairing blocked threads and owners by the monitor's object address.
 */
fun parseThreadDump(dump: String): ParsedDump {
    val blocked = HashMap<String, MutableMap<String, List<String>>>() // address -> (thread -> stack)
    val classByAddress = HashMap<String, String>()
    val ownerByAddress = HashMap<String, String>()

    var currentThread: String? = null
    val currentFrames = ArrayList<String>()

    for (rawLine in dump.lineSequence()) {
        val header = THREAD_HEADER.matchEntire(rawLine.trimEnd())
        if (header != null && !rawLine.startsWith("\t") && !rawLine.startsWith(" ")) {
            currentThread = header.groupValues[1]
            currentFrames.clear()
            continue
        }
        val thread = currentThread ?: continue

        STACK_FRAME.matchEntire(rawLine)?.let { currentFrames.add(it.groupValues[1]); return@let }

        WAITING_TO_LOCK.find(rawLine)?.let { m ->
            val address = m.groupValues[1]
            classByAddress[address] = m.groupValues[2]
            // Frames seen so far (above this marker) are this thread's blocked stack, leaf-first.
            blocked.getOrPut(address) { LinkedHashMap() }[thread] = currentFrames.take(MAX_BLOCKED_STACK)
        }
        LOCKED.find(rawLine)?.let { m ->
            val address = m.groupValues[1]
            classByAddress[address] = m.groupValues[2]
            // The first `locked` line for a monitor is the innermost owner; keep it.
            ownerByAddress.putIfAbsent(address, thread)
        }
    }

    val monitors = blocked.mapValues { (address, threads) ->
        MonitorSnapshot(
            address = address,
            className = classByAddress[address] ?: "(unknown monitor)",
            blockedThreads = threads,
            owner = ownerByAddress[address],
        )
    }
    return ParsedDump(monitors)
}

/**
 * Aggregates polled snapshots into [LockContentionStats], keyed by monitor class. Blocked time is
 * estimated as `(hits / totalSnapshots) * durationMs` summed over each class's blocked threads, so a
 * thread seen blocked in every snapshot contributes roughly the whole recording. Returns null when
 * no snapshots showed contention.
 */
fun aggregate(snapshots: List<ParsedDump>, durationMs: Long): LockContentionStats? {
    val total = snapshots.size
    if (total == 0) return null

    // Per class: blocked-hit counts per thread, owner-hit counts, and a representative blocked stack.
    val blockedHits = HashMap<String, HashMap<String, Int>>()
    val ownerHits = HashMap<String, HashMap<String, Int>>()
    val repStack = HashMap<String, List<String>>()

    for (snapshot in snapshots) {
        for (monitor in snapshot.monitors.values) {
            val cls = monitor.className
            val classBlocked = blockedHits.getOrPut(cls) { HashMap() }
            for ((thread, stack) in monitor.blockedThreads) {
                classBlocked.merge(thread, 1, Int::plus)
                if (stack.isNotEmpty()) repStack.putIfAbsent(cls, stack)
            }
            monitor.owner?.let { ownerHits.getOrPut(cls) { HashMap() }.merge(it, 1, Int::plus) }
        }
    }

    val monitors = blockedHits.entries.map { (cls, hitsByThread) ->
        val estimatedMs = hitsByThread.values.sumOf { it.toDouble() / total } * durationMs
        ContendedMonitor(
            className = cls,
            totalBlockedMs = estimatedMs,
            // No exact event count for polling; surface total blocked-thread observations as a proxy.
            events = hitsByThread.values.sum(),
            topBlockedThreads = hitsByThread.entries.sortedByDescending { it.value }.map { it.key },
            ownerThread = ownerHits[cls]?.entries?.maxByOrNull { it.value }?.key,
            stack = repStack[cls].orEmpty(),
            estimated = true,
        )
    }.sortedByDescending { it.totalBlockedMs }

    if (monitors.isEmpty()) return null
    return LockContentionStats(monitors, monitors.sumOf { it.totalBlockedMs })
}

/**
 * Merges exact JFR contention with polled-estimate contention, keyed by monitor class. A class
 * present in the JFR data wins (exact durations); classes seen only by the poller are added with
 * their estimated durations. Returns null when neither source observed contention.
 */
fun mergeLockContention(jfr: LockContentionStats?, polled: LockContentionStats?): LockContentionStats? {
    if (jfr == null && polled == null) return null
    val byClass = LinkedHashMap<String, ContendedMonitor>()
    jfr?.monitors?.forEach { byClass[it.className] = it }
    polled?.monitors?.forEach { byClass.putIfAbsent(it.className, it) }
    val monitors = byClass.values.sortedByDescending { it.totalBlockedMs }
    if (monitors.isEmpty()) return null
    return LockContentionStats(monitors, monitors.sumOf { it.totalBlockedMs })
}
