package io.github.takahirom.clijvm.analysis

/** One class row of a heap histogram (`jcmd GC.class_histogram`). */
data class HeapHistogramEntry(
    val className: String,
    val instances: Long,
    val bytes: Long,
)

/** A parsed heap histogram. */
data class HeapHistogram(
    val entries: List<HeapHistogramEntry>,
    val totalInstances: Long,
    val totalBytes: Long,
)

/**
 * Parses the text output of `jcmd <pid> GC.class_histogram`.
 *
 * The output looks like:
 * ```
 *  num     #instances         #bytes  class name (module)
 * -------------------------------------------------------
 *    1:         12345        1976544  [B (java.base@21)
 *    2:          6789         543120  java.lang.String (java.base@21)
 * ...
 * Total         99999      123456789
 * ```
 * Array class names such as `[B` are normalised to a readable form (`byte[]`).
 */
object HeapHistogramParser {

    private val rowRegex = Regex("""^\s*\d+:\s+(\d+)\s+(\d+)\s+(\S+)""")
    private val totalRegex = Regex("""^Total\s+(\d+)\s+(\d+)""")

    fun parse(output: String): HeapHistogram {
        val entries = ArrayList<HeapHistogramEntry>()
        var totalInstances = 0L
        var totalBytes = 0L
        var sawExplicitTotal = false

        for (line in output.lineSequence()) {
            totalRegex.find(line)?.let { match ->
                totalInstances = match.groupValues[1].toLong()
                totalBytes = match.groupValues[2].toLong()
                sawExplicitTotal = true
                return@let
            }
            rowRegex.find(line)?.let { match ->
                entries.add(
                    HeapHistogramEntry(
                        instances = match.groupValues[1].toLong(),
                        bytes = match.groupValues[2].toLong(),
                        className = readableClassName(match.groupValues[3]),
                    )
                )
            }
        }

        if (!sawExplicitTotal) {
            totalInstances = entries.sumOf { it.instances }
            totalBytes = entries.sumOf { it.bytes }
        }
        return HeapHistogram(entries, totalInstances, totalBytes)
    }
}
