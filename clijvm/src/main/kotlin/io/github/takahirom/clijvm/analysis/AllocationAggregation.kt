package io.github.takahirom.clijvm.analysis

/** A single allocation event reduced to the fields we aggregate on. */
data class AllocationRecord(
    val className: String,
    /** Bytes attributed to this event (the `weight` of a sample, or the allocation size). */
    val weight: Long,
    /** The allocation stack, ordered leaf-first. */
    val stack: List<String>,
)

/**
 * Aggregates raw allocation events into per-type [AllocationStats], ranked by bytes.
 *
 * Kept as a pure function (independent of the JFR API) so it can be unit-tested directly.
 * Returns null when there are no records, so callers can fall back to another event source.
 */
fun aggregateAllocation(source: String, records: List<AllocationRecord>, topN: Int = 20): AllocationStats? {
    if (records.isEmpty()) return null

    val bytesByClass = LinkedHashMap<String, Long>()
    val eventsByClass = LinkedHashMap<String, Int>()
    val representativeStack = HashMap<String, List<String>>()
    var totalBytes = 0L

    for (record in records) {
        bytesByClass.merge(record.className, record.weight, Long::plus)
        eventsByClass.merge(record.className, 1, Int::plus)
        representativeStack.putIfAbsent(record.className, record.stack)
        totalBytes += record.weight
    }

    val topSites = bytesByClass.entries
        .sortedByDescending { it.value }
        .take(topN)
        .map { (className, bytes) ->
            AllocationSite(
                className = className,
                bytes = bytes,
                sharePct = if (totalBytes == 0L) 0.0 else 100.0 * bytes / totalBytes,
                events = eventsByClass.getValue(className),
                stack = representativeStack[className].orEmpty(),
            )
        }

    return AllocationStats(
        source = source,
        totalBytes = totalBytes,
        totalEvents = records.size,
        topSites = topSites,
    )
}

/**
 * Converts a JVM class name into a readable form, turning array descriptors such as
 * `[B` into `byte[]` and `[Ljava.lang.String;` into `java.lang.String[]`.
 */
fun readableClassName(raw: String): String {
    if (!raw.startsWith("[")) return raw
    var dimensions = 0
    var i = 0
    while (i < raw.length && raw[i] == '[') {
        dimensions++
        i++
    }
    val base = when (raw.getOrNull(i)) {
        'B' -> "byte"
        'C' -> "char"
        'D' -> "double"
        'F' -> "float"
        'I' -> "int"
        'J' -> "long"
        'S' -> "short"
        'Z' -> "boolean"
        'L' -> raw.substring(i + 1).removeSuffix(";")
        else -> raw.substring(i)
    }
    return base + "[]".repeat(dimensions)
}
