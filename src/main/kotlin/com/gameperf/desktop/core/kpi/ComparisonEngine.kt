package com.gameperf.desktop.core.kpi

/**
 * Phase 5 — `ComparisonEngine` (KPI-007).
 *
 * Pure functions that map a numeric KPI score to a color [Band], and a raw
 * value to a signed [delta] vs the target, normalized so that POSITIVE delta
 * always means "better than target" regardless of the KPI's [Direction].
 *
 * Spec coverage: Comparison Engine with Color Bands.
 *
 * Pure: deterministic, no I/O. Single source of truth for band thresholds;
 * `PhaseAggregator.bandOf` (Phase 4) now delegates here.
 *
 * @since v4.5 (kpi-scoring internal v1)
 */
object ComparisonEngine {

    /** Inclusive lower bound of the GREEN band. */
    const val GREEN_THRESHOLD: Int = 80

    /** Inclusive lower bound of the AMBER band (anything below is RED). */
    const val AMBER_THRESHOLD: Int = 60

    /**
     * Trichotomy band per spec: `score ≥ 80 → GREEN`, `60 ≤ score < 80 → AMBER`,
     * `score < 60 → RED`.
     */
    fun band(score: Int): Band = when {
        score >= GREEN_THRESHOLD -> Band.GREEN
        score >= AMBER_THRESHOLD -> Band.AMBER
        else -> Band.RED
    }

    /**
     * Signed delta vs target, with the sign convention "positive = better".
     *
     * - [Direction.HIGHER_IS_BETTER]: returns `actual - target`. A higher
     *   actual is better → positive delta.
     * - [Direction.LOWER_IS_BETTER]: returns `target - actual`. A lower
     *   actual is better → positive delta.
     *
     * This lets the UI render `+Δ` green / `-Δ` red without inspecting the
     * KPI direction.
     *
     * @param actual measured value (in the KPI's natural unit).
     * @param target threshold target from [Threshold.target].
     * @param direction KPI direction from [Kpi.direction].
     */
    fun delta(actual: Double, target: Double, direction: Direction): Double = when (direction) {
        Direction.HIGHER_IS_BETTER -> actual - target
        Direction.LOWER_IS_BETTER -> target - actual
    }
}
