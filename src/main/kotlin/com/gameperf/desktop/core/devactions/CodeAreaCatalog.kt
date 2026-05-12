package com.gameperf.desktop.core.devactions

/**
 * Per-rule × per-engine code-area hints.
 *
 * **Sprint 0 ships this catalog empty** — Sprint 1 (`S1-T3..T11`) will fill
 * it for the 8 production rules × {UNITY, UNREAL, COCOS2D, GENERIC}.
 * Sprint 1 introduces [CodeAreaCatalogCompletenessTest] which red-fails
 * until every (ruleId, engine) combination has at least one entry.
 *
 * The catalog mirrors the `RuleRegistry` + `SdkSignatureCatalog` static-object
 * pattern already established in the codebase (design ADR-2).
 *
 * @since v4.5.0
 */
internal object CodeAreaCatalog {

    /**
     * Returns the code-area hints for [ruleId] under [engine].
     *
     * Sprint 0: always returns an empty list.
     * Sprint 1: returns the engine-specific entries, falling back to
     * [GameEngine.GENERIC] when the engine has no dedicated entries.
     */
    @Suppress("UNUSED_PARAMETER") // Sprint 1 fills the catalog body; signature is the locked contract.
    fun lookup(ruleId: String, engine: GameEngine): List<CodeAreaHint> = emptyList()
}
