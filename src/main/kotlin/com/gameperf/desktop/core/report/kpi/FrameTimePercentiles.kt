package com.gameperf.desktop.core.report.kpi

/**
 * Frame-time percentiles used to render the optional `p1` and `p0.1` pills
 * inside `#sec-frametime`.
 *
 * The "1% high / 0.1% high" convention follows PC-benchmark percentiles
 * (`docs/competitive-analysis-and-kpis.md` §2.3): the value below which 99%
 * (or 99.9%) of the frame times fall. For a single capture, the helpers
 * return `null` when there are not enough samples to make the statistic
 * meaningful so the renderer can omit the pill entirely.
 *
 * Pure: deterministic, no I/O. Sorts internally; callers do not need to
 * pre-sort. Empty input → `null` for both functions.
 *
 * @since v4.6 (shareable-html-report Block F)
 */
object FrameTimePercentiles {

    /** Minimum sample count required to render a `p1` pill. */
    private const val P1_MIN_SAMPLES: Int = 100

    /** Minimum sample count required to render a `p0.1` pill. */
    private const val P01_MIN_SAMPLES: Int = 1000

    /**
     * Returns the 1% high frame-time percentile, or `null` when
     * `samples.size < 100`.
     *
     * For 1000 samples this is the value at sorted index
     * `size - size/100 - 1` (i.e. the 990th element, 0-based 989).
     */
    fun p1(samples: List<Double>): Double? =
        percentileFromTop(samples, P1_MIN_SAMPLES, divisor = 100)

    /**
     * Returns the 0.1% high frame-time percentile, or `null` when
     * `samples.size < 1000`.
     */
    fun p01(samples: List<Double>): Double? =
        percentileFromTop(samples, P01_MIN_SAMPLES, divisor = 1000)

    private fun percentileFromTop(samples: List<Double>, minSize: Int, divisor: Int): Double? {
        if (samples.size < minSize) return null
        val sorted = samples.sorted()
        val index = sorted.size - sorted.size / divisor - 1
        return sorted[index]
    }
}
