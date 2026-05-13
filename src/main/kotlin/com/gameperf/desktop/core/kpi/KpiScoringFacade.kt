package com.gameperf.desktop.core.kpi

import com.gameperf.desktop.core.Settings

/**
 * Lightweight input contract for [KpiScoringFacade].
 *
 * The `core.kpi` package is decoupled from the heavy
 * `viewmodel.SessionResult` so the scoring pipeline stays pure and easily
 * testable. A future adapter (separate change) maps `SessionResult` →
 * [KpiInput] without bleeding the viewmodel dependency tree into `core.kpi`.
 *
 * @property deviceModel device-model string used by [DeviceTierCatalog] to
 *   resolve the [DeviceTier] when the caller does not pass an explicit tier.
 * @property rawByPhase per-phase per-KPI raw measured values. KPIs missing
 *   from the inner map are treated as "not measured" → excluded from
 *   aggregation per design D4. Phases missing from the outer map are
 *   excluded from the session aggregate (renormalized).
 *
 * @since v4.5 (kpi-scoring internal v1)
 */
data class KpiInput(
    val deviceModel: String,
    val rawByPhase: Map<Phase, Map<KpiId, Double>>,
)

/**
 * Phase 6 — public orchestrator for the KPI scoring framework (KPI-008).
 *
 * Pipeline (design D1 — parallel path, does NOT replace `FinalScoreCalculator`):
 *   1. Gate on [FeatureFlags.isKpiScoringInternalEnabled]; return `null` when OFF.
 *   2. Resolve [DeviceTier] (explicit arg → [DeviceTierCatalog.resolve] fallback).
 *   3. For each phase × KPI in [KpiInput.rawByPhase], score via
 *      [scoreLinear] using the tier's [Threshold] from [KpiCatalog].
 *   4. Aggregate per-phase via [aggregatePhase], per-category via
 *      [aggregateCategories], session via [aggregateSession].
 *   5. Attach categories to the [KpiScoreReport] returned by the session
 *      aggregator (which leaves them empty by contract).
 *
 * Pure: deterministic, no I/O.
 *
 * @since v4.5 (kpi-scoring internal v1)
 */
object KpiScoringFacade {

    /**
     * Computes the [KpiScoreReport] for [input].
     *
     * @param input per-phase per-KPI raw values + device model.
     * @param tier explicit tier override; when null, resolved via
     *   [DeviceTierCatalog.resolve] from [KpiInput.deviceModel].
     * @param weights weights table (defaults to [PhaseWeights.DEFAULT]).
     * @param settings settings instance carrying
     *   [Settings.kpiScoringInternalEnabled]; default `Settings()` reads the
     *   "all defaults" state so callers that do not depend on settings can
     *   still enable scoring via the JVM system property.
     * @return [KpiScoreReport] when the feature flag is ON AND there is at
     *   least one phase with at least one scorable KPI; `null` otherwise.
     */
    fun compute(
        input: KpiInput,
        tier: DeviceTier? = null,
        weights: PhaseWeights = PhaseWeights.DEFAULT,
        settings: Settings = Settings(),
    ): KpiScoreReport? {
        if (!FeatureFlags.isKpiScoringInternalEnabled(settings)) return null

        val resolvedTier: DeviceTier = tier ?: DeviceTierCatalog.resolve(input.deviceModel)

        // Phase × KPI scoring: raw → linear score (or null if KPI missing).
        val scoresByPhase: Map<Phase, Map<KpiId, Int?>> = input.rawByPhase.mapValues { (phase, rawMap) ->
            val phaseKpiWeights = weights.kpiWeightsForPhase[phase] ?: return@mapValues emptyMap()
            // For every KPI declared in the phase's weight map, score it if a
            // raw value is present; otherwise mark null so aggregators
            // renormalize per design D4.
            phaseKpiWeights.keys.associateWith { kpiId ->
                val raw = rawMap[kpiId] ?: return@associateWith null
                val kpi = KpiCatalog.byId(kpiId)
                val threshold = kpi.thresholds[resolvedTier] ?: kpi.thresholds[DeviceTier.MID] ?: return@associateWith null
                scoreLinear(
                    value = raw,
                    target = threshold.target,
                    floor = threshold.floor,
                    direction = kpi.direction,
                )
            }
        }

        // Per-phase aggregates (null phases excluded).
        val phaseScores: List<PhaseScore> = scoresByPhase.mapNotNull { (phase, scores) ->
            aggregatePhase(phase, scores, weights)
        }

        // Session aggregate (returns null if no phase scored).
        val sessionReport = aggregateSession(phaseScores, weights) ?: return null

        // Category aggregates (cross-phase).
        val categories = aggregateCategories(scoresByPhase, weights)

        return sessionReport.copy(categories = categories)
    }
}
