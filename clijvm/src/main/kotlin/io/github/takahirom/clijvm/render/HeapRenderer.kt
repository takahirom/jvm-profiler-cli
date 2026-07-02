package io.github.takahirom.clijvm.render

import io.github.takahirom.clijvm.analysis.HeapHistogram
import io.github.takahirom.clijvm.util.jsonArray
import io.github.takahirom.clijvm.util.jsonInt
import io.github.takahirom.clijvm.util.jsonObject
import io.github.takahirom.clijvm.util.jsonString

/** Renders a [HeapHistogram] as text or JSON. */
object HeapRenderer {

    fun summary(pid: Long, histogram: HeapHistogram, limit: Int): String = buildString {
        appendLine("=== clijvm heap histogram ===")
        appendLine("pid:      $pid")
        appendLine("classes:  ${histogram.entries.size}")
        appendLine("total:    ${histogram.totalInstances} instances, ${Renderers.formatBytes(histogram.totalBytes)}")
        appendLine()
        appendLine("%-14s  %-14s  %s".format("#INSTANCES", "BYTES", "CLASS"))
        histogram.entries.take(limit).forEach { entry ->
            appendLine(
                "%-14d  %-14s  %s".format(
                    entry.instances,
                    Renderers.formatBytes(entry.bytes),
                    entry.className,
                )
            )
        }
    }.trimEnd()

    fun json(pid: Long, histogram: HeapHistogram, limit: Int): String = jsonObject(
        "pid" to jsonInt(pid),
        "classCount" to jsonInt(histogram.entries.size.toLong()),
        "totalInstances" to jsonInt(histogram.totalInstances),
        "totalBytes" to jsonInt(histogram.totalBytes),
        "topClasses" to jsonArray(histogram.entries.take(limit).map { entry ->
            jsonObject(
                "class" to jsonString(entry.className),
                "instances" to jsonInt(entry.instances),
                "bytes" to jsonInt(entry.bytes),
            )
        }),
    ).render()
}
