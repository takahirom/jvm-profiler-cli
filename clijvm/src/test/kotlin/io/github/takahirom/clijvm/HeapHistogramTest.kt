package io.github.takahirom.clijvm

import io.github.takahirom.clijvm.analysis.HeapHistogramParser
import kotlin.test.Test
import kotlin.test.assertEquals

class HeapHistogramTest {
    // Representative `jcmd <pid> GC.class_histogram` output.
    private val fixture = """
        num     #instances         #bytes  class name (module)
        -------------------------------------------------------
           1:         50000        2400000  [B (java.base@21.0.8)
           2:         30000        1200000  java.lang.String (java.base@21.0.8)
           3:         10000         480000  [Ljava.lang.Object; (java.base@21.0.8)
           4:          5000         160000  java.util.HashMap${'$'}Node (java.base@21.0.8)
        Total        95000        4240000
    """.trimIndent()

    @Test
    fun `parses rows with instances bytes and normalised class names`() {
        val histogram = HeapHistogramParser.parse(fixture)
        assertEquals(4, histogram.entries.size)

        val first = histogram.entries[0]
        assertEquals("byte[]", first.className)
        assertEquals(50000, first.instances)
        assertEquals(2400000, first.bytes)

        assertEquals("java.lang.String", histogram.entries[1].className)
        assertEquals("java.lang.Object[]", histogram.entries[2].className)
        assertEquals("java.util.HashMap\$Node", histogram.entries[3].className)
    }

    @Test
    fun `reads the explicit total line`() {
        val histogram = HeapHistogramParser.parse(fixture)
        assertEquals(95000, histogram.totalInstances)
        assertEquals(4240000, histogram.totalBytes)
    }

    @Test
    fun `falls back to summing rows when no total line is present`() {
        val noTotal = fixture.lineSequence().filterNot { it.startsWith("Total") }.joinToString("\n")
        val histogram = HeapHistogramParser.parse(noTotal)
        assertEquals(95000, histogram.totalInstances)
        assertEquals(4240000, histogram.totalBytes)
    }
}
