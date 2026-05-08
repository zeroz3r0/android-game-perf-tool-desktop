package com.gameperf.desktop.core.conclusions.rules

import com.gameperf.desktop.core.conclusions.Conclusion
import com.gameperf.desktop.core.conclusions.ConclusionInput
import com.gameperf.desktop.core.conclusions.Rule
import com.gameperf.desktop.core.conclusions.Severity
import com.gameperf.desktop.viewmodel.TimedSample

/**
 * Detects sustained linear memory growth via least-squares regression on the
 * filtered memory time series — a strong heuristic for a leak as opposed to a
 * normal saw-tooth GC pattern (which has near-zero average slope).
 *
 * Predicate:
 *  - At least [MIN_SAMPLES] points in the series (otherwise the slope is noisy).
 *  - Best-fit slope ≥ [GROWTH_SLOPE_MB_PER_S] MB/s (≈30 MB/min sustained).
 *
 * `TimedSample.second` is capture-relative seconds; the regression uses that as
 * the X axis, so the resulting slope is in MB/s (units of [TimedSample.value],
 * which is MB).
 *
 * @since v4.4.0
 */
object MemoryGrowthRule : Rule {
    override val id: String = "memory-leak-suspect"
    override val severity: Severity = Severity.WARNING

    /** Minimum points needed for a meaningful trend. */
    private const val MIN_SAMPLES = 30

    /** Sustained slope above which we flag a likely leak. */
    private const val GROWTH_SLOPE_MB_PER_S = 0.5

    override fun matches(input: ConclusionInput): Boolean {
        val series = input.memTimedFiltered
        if (series.size < MIN_SAMPLES) return false
        return linearRegressionSlope(series) >= GROWTH_SLOPE_MB_PER_S
    }

    override fun render(input: ConclusionInput): Conclusion {
        val slope = linearRegressionSlope(input.memTimedFiltered)
        val slopeStr = "%.2f".format(slope)
        val growthPerMin = "%.0f".format(slope * 60)
        val peak = input.filtered.peakMem
        return Conclusion(
            ruleId = id,
            severity = severity,
            headline = "La memoria del juego crece a un ritmo sostenido de $slopeStr MB/s " +
                "(≈$growthPerMin MB/min), alcanzando un pico de $peak MB.",
            recommendation = "Crecimiento lineal sin caídas indica una posible fuga de memoria. " +
                "Revisa con el desarrollador: pools de objetos, listeners no liberados, texturas " +
                "no descargadas entre escenas. Una sesión de profiling con Android Studio Profiler " +
                "o Unity Memory Profiler debería confirmarlo.",
        )
    }

    /**
     * Slope of the best-fit line through `(second, value)` points.
     *
     * Returns 0.0 for empty / insufficient input or for a degenerate series
     * where every point shares the same X coordinate.
     */
    private fun linearRegressionSlope(series: List<TimedSample>): Double {
        if (series.size < 2) return 0.0
        val n = series.size.toDouble()
        val sumX = series.sumOf { it.second.toDouble() }
        val sumY = series.sumOf { it.value }
        val sumXY = series.sumOf { it.second.toDouble() * it.value }
        val sumX2 = series.sumOf { it.second.toDouble() * it.second.toDouble() }
        val denominator = n * sumX2 - sumX * sumX
        if (denominator == 0.0) return 0.0
        return (n * sumXY - sumX * sumY) / denominator
    }
}
