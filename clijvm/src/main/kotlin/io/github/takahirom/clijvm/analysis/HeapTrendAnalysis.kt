package io.github.takahirom.clijvm.analysis

/**
 * Derives a post-GC heap [HeapTrend] from the ordered series of "After GC" live-heap sizes.
 *
 * Kept pure (a function of the sampled byte values) so the growing / stable / shrinking /
 * insufficient-data branches can be unit-tested without a recording.
 */
object HeapTrendAnalysis {

    /** Fewer post-GC samples than this can't distinguish a trend from noise. */
    const val MIN_GCS = 8

    /** Last-third average at or above this multiple of the first-third average counts as growing. */
    const val GROWTH_RATIO = 1.5

    /** Last-third average at or below this multiple of the first-third average counts as shrinking. */
    const val SHRINK_RATIO = 0.75

    /** Ignore sub-this growth on tiny heaps, where ratios are noisy (16 MB). */
    const val MIN_GROWTH_BYTES = 16L * 1024 * 1024

    /** Returns null when there are no post-GC samples at all (no GC / unsupported recording). */
    fun derive(postGcHeapUsed: List<Long>): HeapTrend? {
        if (postGcHeapUsed.isEmpty()) return null

        val min = postGcHeapUsed.min()
        val max = postGcHeapUsed.max()

        if (postGcHeapUsed.size < MIN_GCS) {
            return HeapTrend(postGcHeapUsed.size, postGcHeapUsed.first(), postGcHeapUsed.last(), min, max, HeapTrendDirection.INSUFFICIENT_DATA)
        }

        val third = postGcHeapUsed.size / 3
        val firstThirdAvg = postGcHeapUsed.take(third).average()
        val lastThirdAvg = postGcHeapUsed.takeLast(third).average()

        val direction = when {
            lastThirdAvg >= firstThirdAvg * GROWTH_RATIO && (lastThirdAvg - firstThirdAvg) >= MIN_GROWTH_BYTES ->
                HeapTrendDirection.GROWING
            lastThirdAvg <= firstThirdAvg * SHRINK_RATIO -> HeapTrendDirection.SHRINKING
            else -> HeapTrendDirection.STABLE
        }
        return HeapTrend(postGcHeapUsed.size, firstThirdAvg.toLong(), lastThirdAvg.toLong(), min, max, direction)
    }
}
