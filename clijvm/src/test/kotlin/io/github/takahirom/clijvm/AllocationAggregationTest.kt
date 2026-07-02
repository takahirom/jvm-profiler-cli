package io.github.takahirom.clijvm

import io.github.takahirom.clijvm.analysis.AllocationRecord
import io.github.takahirom.clijvm.analysis.aggregateAllocation
import io.github.takahirom.clijvm.analysis.readableClassName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AllocationAggregationTest {

    @Test
    fun `aggregates bytes and events per class, ranked by bytes`() {
        val records = listOf(
            AllocationRecord("java.lang.String", 100, listOf("String.<init>")),
            AllocationRecord("byte[]", 500, listOf("String.<init>", "allocateStrings")),
            AllocationRecord("java.lang.String", 300, listOf("String.<init>")),
            AllocationRecord("byte[]", 500, listOf("String.<init>", "allocateStrings")),
        )
        val stats = aggregateAllocation("jdk.ObjectAllocationSample", records, topN = 10)
            ?: error("expected non-null stats")

        assertEquals("jdk.ObjectAllocationSample", stats.source)
        assertEquals(1400, stats.totalBytes)
        assertEquals(4, stats.totalEvents)

        // byte[] (1000 bytes) outranks String (400 bytes).
        val top = stats.topSites[0]
        assertEquals("byte[]", top.className)
        assertEquals(1000, top.bytes)
        assertEquals(2, top.events)
        assertEquals(listOf("String.<init>", "allocateStrings"), top.stack)

        val second = stats.topSites[1]
        assertEquals("java.lang.String", second.className)
        assertEquals(400, second.bytes)
        // Shares are relative to total bytes.
        assertTrue(top.sharePct > second.sharePct)
        assertEquals(100.0, top.sharePct + second.sharePct, 0.001)
    }

    @Test
    fun `respects topN`() {
        val records = (1..5).map { AllocationRecord("Class$it", it.toLong(), emptyList()) }
        val stats = aggregateAllocation("src", records, topN = 2) ?: error("non-null")
        assertEquals(2, stats.topSites.size)
        assertEquals("Class5", stats.topSites[0].className)
    }

    @Test
    fun `returns null for no records`() {
        assertNull(aggregateAllocation("src", emptyList()))
    }

    @Test
    fun `normalises array class descriptors`() {
        assertEquals("byte[]", readableClassName("[B"))
        assertEquals("int[]", readableClassName("[I"))
        assertEquals("java.lang.String[]", readableClassName("[Ljava.lang.String;"))
        assertEquals("long[][]", readableClassName("[[J"))
        assertEquals("java.lang.String", readableClassName("java.lang.String"))
    }
}
