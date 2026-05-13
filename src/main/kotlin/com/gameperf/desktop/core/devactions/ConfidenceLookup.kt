package com.gameperf.desktop.core.devactions

/**
 * Per-rule baseline [Confidence] lookup.
 *
 * Hand-assigned per design ADR-6 — based on how robust each rule's
 * heuristic is on real captures. Adding a new rule requires adding a
 * row here (or accepting the [Confidence.MEDIUM] fallback).
 *
 * @since v4.5.0
 * @see com.gameperf.desktop.core.conclusions.RuleRegistry for the 8 production rules.
 */
internal object ConfidenceLookup {

    private val map: Map<String, Confidence> = mapOf(
        "cpu-saturated" to Confidence.HIGH,
        "thermal-throttling" to Confidence.HIGH,
        "stable-low-fps-low-cpu" to Confidence.MEDIUM,
        "memory-leak-suspect" to Confidence.MEDIUM,
        "jank-with-good-avg" to Confidence.MEDIUM,
        "fps-cap-suspect" to Confidence.LOW,
        "ad-vs-game-fps-gap" to Confidence.HIGH,
        "loading-thermal-recovery" to Confidence.HIGH,
    )

    /**
     * Returns the documented confidence level for [ruleId], or [Confidence.MEDIUM]
     * as a safe fallback when the rule isn't in the catalog yet.
     */
    fun forRule(ruleId: String): Confidence = map[ruleId] ?: Confidence.MEDIUM
}
