package com.gameperf.desktop.core.devactions

import com.gameperf.desktop.core.conclusions.Conclusion
import com.gameperf.desktop.core.conclusions.ConclusionEngine
import com.gameperf.desktop.core.conclusions.ConclusionInput

/**
 * v4.5.0 — wraps [ConclusionEngine] and enriches each [Conclusion]
 * with structured [DevActionItem] (evidence + diagnostic + code-area
 * hints + suggested actions + confidence) producing a [DevActionBrief]
 * ready for persistence and rendering.
 *
 * **Sprint 0 — foundation only.** Catalogs ([CodeAreaCatalog] +
 * [ActionStepsCatalog]) are empty in Sprint 0; engine detection is the
 * placeholder [GameEngine.GENERIC]. Sprint 1 will fill the catalogs and
 * Sprint 2 will wire `GameEngineDetector`.
 *
 * Sprint 0 guarantees [ConclusionEngine.run] output is byte-equivalent
 * vs the pre-DevAction baseline — locked by `ConclusionEngineSnapshotTest`
 * (DAB-016 invariant).
 *
 * Design: `sdd/dev-action-brief/design` — ADR-1 (wrap, don't replace),
 * ADR-4 (items + topN), ADR-5 (reuse 3-tier Severity), ADR-6 (per-rule
 * Confidence baseline).
 *
 * Spec: DAB-001..DAB-005, DAB-016.
 *
 * @since v4.5.0
 */
object DevActionEngine {

    /** Sprint 0 placeholder for engine detection — replaced by `GameEngineDetector` in Sprint 2. */
    private val SPRINT_0_PLACEHOLDER_ENGINE: GameEngine = GameEngine.GENERIC

    /**
     * Runs [ConclusionEngine.run] then enriches each surviving [Conclusion]
     * with a [DevActionItem].
     *
     * Sprint 0 contract:
     *  - Same order as `ConclusionEngine.run` (severity DESC then ruleId ASC).
     *  - One [DevActionItem] per [Conclusion]; `DevActionItem.ruleId == Conclusion.ruleId` (DAB-005).
     *  - `codeAreaHints` and `suggestedActions` are empty (catalogs unfilled).
     *  - `relatedLogcatLines` is always empty (DAB-014 reserved).
     *  - `confidence` follows [ConfidenceLookup] (design ADR-6).
     *  - `evidence` follows [EvidenceBuilder] (DAB-013).
     *
     * @param input The same [ConclusionInput] passed to [ConclusionEngine.run].
     * @return A [DevActionBrief] with 0..N items in the engine's natural order.
     */
    fun run(input: ConclusionInput): DevActionBrief {
        val conclusions = ConclusionEngine.run(input)
        if (conclusions.isEmpty()) return DevActionBrief(items = emptyList())

        val engine = SPRINT_0_PLACEHOLDER_ENGINE
        val items = conclusions.map { conclusion -> enrich(conclusion, engine, input) }
        return DevActionBrief(items = items, topN = DevActionBrief.DEFAULT_TOP_N)
    }

    /** Convert a single [Conclusion] into a [DevActionItem] under [engine]. */
    private fun enrich(
        conclusion: Conclusion,
        engine: GameEngine,
        input: ConclusionInput,
    ): DevActionItem {
        val hints = CodeAreaCatalog.lookup(conclusion.ruleId, engine)
        val allActions = ActionStepsCatalog.lookup(conclusion.ruleId)
        val filteredActions = allActions.filter {
            it.engineSpecific == null || it.engineSpecific == engine
        }
        return DevActionItem(
            ruleId = conclusion.ruleId,
            severity = conclusion.severity,
            title = conclusion.headline,
            evidence = EvidenceBuilder.build(conclusion.ruleId, input),
            diagnostic = conclusion.recommendation.orEmpty(),
            codeAreaHints = hints,
            suggestedActions = filteredActions,
            relatedLogcatLines = emptyList(),
            confidence = ConfidenceLookup.forRule(conclusion.ruleId),
        )
    }
}
