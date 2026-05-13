package com.gameperf.desktop.core.kpi

import kotlin.math.roundToInt

/**
 * Phase 4 — session-level aggregator (KPI-005).
 *
 * Combines a list of [PhaseScore] into a session-level weighted average
 * using [PhaseWeights.phaseWeights]. Phases missing from the input are
 * EXCLUDED and the remaining weights are renormalized (design D4).
 *
 * Returns `null` if no phase has a score (all-missing case) — caller must
 * treat as "session has no measurable data".
 *
 * Pure: deterministic, no I/O.
 *
 * NOTE: This aggregator does not compute category scores. The [KpiScoreReport]
 * it returns has an empty `categories` list — the facade pipes the output of
 * [aggregateCategories] into the final report.
 *
 * @since v4.5 (kpi-scoring internal v1)
 */

/**
 * Aggregates per-phase scores into a session-level [KpiScoreReport].
 *
 * @param phaseScores non-null per-phase aggregates produced by
 *   [aggregatePhase]. Phases missing from this list are excluded.
 * @param weights weights table.
 * @return [KpiScoreReport] with session score + echoed `phases`; `null` when
 *   the input list is empty or no phase has a known weight.
 */
fun aggregateSession(
    phaseScores: List<PhaseScore>,
    weights: PhaseWeights,
): KpiScoreReport? {
    if (phaseScores.isEmpty()) return null

    var numerator = 0.0
    var denominator = 0.0
    for (phaseScore in phaseScores) {
        val phaseWeight = weights.phaseWeights[phaseScore.phase] ?: continue
        numerator += phaseWeight * phaseScore.score
        denominator += phaseWeight
    }
    if (denominator == 0.0) return null

    val sessionScore = (numerator / denominator).roundToInt()
    return KpiScoreReport(
        sessionScore = sessionScore,
        sessionBand = bandOf(sessionScore),
        phases = phaseScores,
        categories = emptyList(), // populated by the facade via aggregateCategories
    )
}
