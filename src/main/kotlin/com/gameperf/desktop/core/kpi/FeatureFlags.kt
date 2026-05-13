package com.gameperf.desktop.core.kpi

import com.gameperf.desktop.core.Settings

/**
 * Phase 6 — feature-flag gate for the KPI scoring framework (KPI-008).
 *
 * Design D5: enable via EITHER
 *   - JVM system property `gameperf.kpi.internal=true` (per-process override
 *     useful for ad-hoc inspection without touching the persisted JSON), OR
 *   - [Settings.kpiScoringInternalEnabled] = true (persistent default for
 *     dogfooding builds; a future Settings UI can flip this).
 *
 * Default OFF. The facade returns `null` while OFF so callers never observe
 * a partially-computed [KpiScoreReport].
 *
 * Pure: deterministic side-effect-free reads.
 *
 * @since v4.5 (kpi-scoring internal v1)
 */
object FeatureFlags {

    /** System-property key for the per-process override. */
    const val INTERNAL_FLAG_KEY: String = "gameperf.kpi.internal"

    /**
     * @return `true` iff either the sysprop is `"true"` OR
     *   [Settings.kpiScoringInternalEnabled] is `true`.
     */
    fun isKpiScoringInternalEnabled(settings: Settings): Boolean {
        if (settings.kpiScoringInternalEnabled) return true
        return System.getProperty(INTERNAL_FLAG_KEY) == "true"
    }
}
