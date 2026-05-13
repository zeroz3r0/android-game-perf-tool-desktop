package com.gameperf.desktop.core.kpi

import kotlin.math.roundToInt

/**
 * Phase 4 — per-phase aggregator (KPI-003).
 *
 * Combines per-KPI scores into a single weighted-average phase score using
 * [PhaseWeights.kpiWeightsForPhase]. Missing KPIs (score == null) are
 * EXCLUDED from the denominator and the remaining weights are renormalized,
 * per design D4 (`docs/competitive-analysis-and-kpis.md` §5.2 missing-data
 * policy + design doc D4 anti-bias rule).
 *
 * Pure: deterministic, no I/O. Returns `null` when no KPI in the phase
 * has a score (all-missing case — caller should treat as "phase not
 * measured in this session").
 *
 * @since v4.5 (kpi-scoring internal v1)
 */

/**
 * Aggregates per-KPI scores for [phase] into a [PhaseScore].
 *
 * @param phase the game phase being aggregated.
 * @param scores per-KPI scores; `null` value means the KPI was not measured
 *   for this session and MUST be excluded from the denominator.
 * @param weights weights table (defaults to [PhaseWeights.DEFAULT] via caller).
 * @return aggregated [PhaseScore] or `null` if every KPI is missing.
 */
fun aggregatePhase(
    phase: Phase,
    scores: Map<KpiId, Int?>,
    weights: PhaseWeights,
): PhaseScore? {
    val phaseKpiWeights: Map<KpiId, Double> = weights.kpiWeightsForPhase[phase] ?: return null

    var numerator = 0.0
    var denominator = 0.0
    for ((kpiId, weight) in phaseKpiWeights) {
        val score = scores[kpiId] ?: continue // null → excluded
        numerator += weight * score
        denominator += weight
    }
    if (denominator == 0.0) return null

    // Round-to-nearest, not truncate — otherwise floating-point drift in the
    // weights table (e.g. 0.20+0.15+...+0.05 → 0.9999...) silently loses
    // 1 score point on every all-equal-input case. See test
    // `happy path computes weighted average of present KPIs` for the canary.
    val aggregated = (numerator / denominator).roundToInt()
    return PhaseScore(
        phase = phase,
        score = aggregated,
        band = bandOf(aggregated),
        kpiScores = emptyList(), // populated by facade once KpiScore details are known
    )
}

/**
 * Trichotomy band per spec: `GREEN ≥ 80`, `AMBER 60..79`, `RED < 60`.
 *
 * Delegates to [ComparisonEngine.band] (single source of truth, Phase 5).
 * Kept as an internal top-level shim so [CategoryAggregator] /
 * [SessionAggregator] don't need to change call sites; aggregator
 * implementation files MUST NOT inline band thresholds.
 */
internal fun bandOf(score: Int): Band = ComparisonEngine.band(score)
