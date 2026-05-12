package com.gameperf.desktop.core.devactions

/**
 * Per-rule list of [ActionStep] suggestions.
 *
 * **Sprint 0 ships this catalog empty** — Sprint 1 (`S1-T3..T11`) fills
 * it with research-grade entries for the 8 production rules. Each rule
 * gets 1..5 steps; engine-specific steps carry an [ActionStep.engineSpecific]
 * tag and are filtered downstream in [DevActionEngine] based on the
 * detected engine.
 *
 * Mirrors the static-object pattern (design ADR-2).
 *
 * @since v4.5.0
 */
internal object ActionStepsCatalog {

    /**
     * Returns the suggested action steps for [ruleId].
     *
     * Sprint 0: always returns an empty list.
     */
    @Suppress("UNUSED_PARAMETER") // Sprint 1 fills the catalog body; signature is the locked contract.
    fun lookup(ruleId: String): List<ActionStep> = emptyList()
}
