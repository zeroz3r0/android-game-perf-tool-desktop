package com.gameperf.desktop.core.kpi

import kotlin.math.roundToInt

/**
 * Phase 4 — cross-phase category aggregator (KPI-004).
 *
 * For each [Category], computes a weighted average of all KPIs of that
 * category across all phases. KPI weight = `phaseWeight × kpiWeightInPhase`
 * so the category score is consistent with the session aggregate.
 *
 * Missing values (score == null) are excluded and the remaining weights are
 * renormalized. A category with NO scored KPIs is omitted from the output —
 * "no data" must be distinguishable from "score zero" (design D4).
 *
 * Pure: deterministic, no I/O.
 *
 * @since v4.5 (kpi-scoring internal v1)
 */

/**
 * Aggregates per-phase per-KPI scores into a list of [CategoryScore].
 *
 * @param scoresByPhase per-phase per-KPI scores; inner `null` values are
 *   excluded.
 * @param weights weights table.
 * @return one [CategoryScore] per category that has at least one scored
 *   KPI present in any phase. Categories with all-null data are omitted.
 */
fun aggregateCategories(
    scoresByPhase: Map<Phase, Map<KpiId, Int?>>,
    weights: PhaseWeights,
): List<CategoryScore> {
    // Accumulator per category: numerator (Σ weight * score), denominator (Σ weight).
    val numerator = mutableMapOf<Category, Double>()
    val denominator = mutableMapOf<Category, Double>()

    for ((phase, kpiScores) in scoresByPhase) {
        val phaseWeight = weights.phaseWeights[phase] ?: continue
        val kpiWeights = weights.kpiWeightsForPhase[phase] ?: continue
        for ((kpiId, kpiWeight) in kpiWeights) {
            val score = kpiScores[kpiId] ?: continue // null → excluded
            val category = KpiCatalog.byId(kpiId).category
            val combined = phaseWeight * kpiWeight
            numerator.merge(category, combined * score) { a, b -> a + b }
            denominator.merge(category, combined) { a, b -> a + b }
        }
    }

    return denominator.entries
        .filter { it.value > 0.0 }
        .map { (category, denom) ->
            val num = numerator[category] ?: 0.0
            val score = (num / denom).roundToInt()
            CategoryScore(category = category, score = score, band = bandOf(score))
        }
        .sortedBy { it.category.ordinal }
}
