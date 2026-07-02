package io.github.takahirom.clijvm

import io.github.takahirom.clijvm.analysis.HeapTrendAnalysis
import io.github.takahirom.clijvm.analysis.HeapTrendDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HeapTrendAnalysisTest {

    private val mb = 1024L * 1024

    @Test
    fun `no samples yields null`() {
        assertNull(HeapTrendAnalysis.derive(emptyList()))
    }

    @Test
    fun `too few GCs is insufficient data`() {
        val trend = HeapTrendAnalysis.derive(listOf(100 * mb, 200 * mb, 300 * mb))
        assertEquals(HeapTrendDirection.INSUFFICIENT_DATA, trend!!.direction)
        assertEquals(3, trend.gcCount)
    }

    @Test
    fun `steadily growing post-GC heap is flagged as growing`() {
        // 9 samples ramping ~120 MB -> ~360 MB.
        val samples = (1..9).map { (100L + it * 30) * mb }
        val trend = HeapTrendAnalysis.derive(samples)!!
        assertEquals(HeapTrendDirection.GROWING, trend.direction)
        // first third avg well below last third avg
        assert(trend.lastThirdAvgBytes > trend.firstThirdAvgBytes)
    }

    @Test
    fun `flat post-GC heap is stable`() {
        val samples = List(12) { 120 * mb + (if (it % 2 == 0) 2 * mb else 0) }
        val trend = HeapTrendAnalysis.derive(samples)!!
        assertEquals(HeapTrendDirection.STABLE, trend.direction)
    }

    @Test
    fun `sawtooth around a flat baseline stays stable not growing`() {
        // Classic healthy GC sawtooth: rises then drops, no upward drift.
        val samples = (1..12).map { (100L + (it % 3) * 20) * mb }
        assertEquals(HeapTrendDirection.STABLE, HeapTrendAnalysis.derive(samples)!!.direction)
    }

    @Test
    fun `declining post-GC heap is shrinking`() {
        val samples = (1..9).map { (400L - it * 30) * mb }
        assertEquals(HeapTrendDirection.SHRINKING, HeapTrendAnalysis.derive(samples)!!.direction)
    }

    @Test
    fun `tiny-heap growth below the floor stays stable`() {
        // Ratio looks like growth (2 MB -> 5 MB) but absolute delta is under the noise floor.
        val samples = (1..12).map { (2L + it / 4) * mb }
        assertEquals(HeapTrendDirection.STABLE, HeapTrendAnalysis.derive(samples)!!.direction)
    }
}
