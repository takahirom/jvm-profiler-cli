package io.github.takahirom.clijvm.util

import io.github.takahirom.clijvm.analysis.ContendedMonitor
import io.github.takahirom.clijvm.analysis.LockContentionStats
import java.nio.file.Files
import java.nio.file.Path

/**
 * Sidecar metadata written next to a `.jfr` recording as `<recording>.meta.json`.
 *
 * It lets `report` recover facts the raw recording does not carry reliably: the target's main
 * class (JFR often reports it as unknown), whether the recording was a salvaged PARTIAL dump, and
 * the sampled lock contention gathered by thread-state polling during a live run (a monopolized
 * monitor emits no `jdk.JavaMonitorEnter` events, so it exists nowhere in the `.jfr` itself).
 * The sidecar is best-effort — a missing or corrupt file simply falls back to filename heuristics.
 */
data class RecordingMeta(
    val pid: Long?,
    val mainClass: String?,
    /** When profiling started, in epoch milliseconds. */
    val startedAt: Long?,
    val partial: Boolean,
    /**
     * Thread-state-sampled lock contention captured during the live run, or null. Persisted so
     * `report --waits` on the saved recording can still show contention the `.jfr` cannot carry.
     */
    val sampledContention: LockContentionStats? = null,
)

/** Resolves the sidecar path for [recording]: `<recording>.meta.json`. */
fun recordingMetaPath(recording: Path): Path =
    recording.resolveSibling("${recording.fileName}.meta.json")

/** Writes [meta] next to [recording]; failures are swallowed since the sidecar is best-effort. */
fun writeRecordingMeta(recording: Path, meta: RecordingMeta) {
    val entries = mutableListOf(
        "pid" to (meta.pid?.let { jsonInt(it) } ?: Json.Literal("null")),
        "mainClass" to jsonStringOrNull(meta.mainClass),
        "startedAt" to (meta.startedAt?.let { jsonInt(it) } ?: Json.Literal("null")),
        "partial" to jsonBool(meta.partial),
    )
    meta.sampledContention?.let { entries += "sampledContention" to sampledContentionJson(it) }
    runCatching { Files.writeString(recordingMetaPath(recording), Json.Obj(entries).render()) }
}

/** Reads the sidecar for [recording], or null when it is absent or not well-formed. */
fun readRecordingMeta(recording: Path): RecordingMeta? {
    val path = recordingMetaPath(recording)
    if (!Files.isReadable(path)) return null
    return runCatching {
        val obj = JsonParser.parse(Files.readString(path)) as? Map<*, *> ?: return null
        RecordingMeta(
            pid = (obj["pid"] as? Double)?.toLong(),
            mainClass = obj["mainClass"] as? String,
            startedAt = (obj["startedAt"] as? Double)?.toLong(),
            partial = obj["partial"] as? Boolean ?: false,
            sampledContention = (obj["sampledContention"] as? Map<*, *>)?.let { parseSampledContention(it) },
        )
    }.getOrNull()
}

private fun sampledContentionJson(lc: LockContentionStats): Json = Json.Obj(
    listOf(
        "totalBlockedMs" to jsonNumber(lc.totalBlockedMs),
        "monitors" to jsonArray(
            lc.monitors.map { m ->
                Json.Obj(
                    listOf(
                        "class" to jsonString(m.className),
                        "totalBlockedMs" to jsonNumber(m.totalBlockedMs),
                        "events" to jsonInt(m.events),
                        "topBlockedThreads" to jsonArray(m.topBlockedThreads.map { jsonString(it) }),
                        "ownerThread" to jsonStringOrNull(m.ownerThread),
                        "stack" to jsonArray(m.stack.map { jsonString(it) }),
                    )
                )
            }
        ),
    )
)

/** Reconstructs sampled contention from the sidecar; monitors are always flagged estimated. */
private fun parseSampledContention(obj: Map<*, *>): LockContentionStats? {
    val monitors = (obj["monitors"] as? List<*>).orEmpty()
        .mapNotNull { it as? Map<*, *> }
        .mapNotNull { m ->
            val className = m["class"] as? String ?: return@mapNotNull null
            ContendedMonitor(
                className = className,
                totalBlockedMs = (m["totalBlockedMs"] as? Double) ?: 0.0,
                events = (m["events"] as? Double)?.toInt() ?: 0,
                topBlockedThreads = (m["topBlockedThreads"] as? List<*>).orEmpty().mapNotNull { it as? String },
                ownerThread = m["ownerThread"] as? String,
                stack = (m["stack"] as? List<*>).orEmpty().mapNotNull { it as? String },
                estimated = true,
            )
        }
    if (monitors.isEmpty()) return null
    val total = (obj["totalBlockedMs"] as? Double) ?: monitors.sumOf { it.totalBlockedMs }
    return LockContentionStats(monitors, total)
}
