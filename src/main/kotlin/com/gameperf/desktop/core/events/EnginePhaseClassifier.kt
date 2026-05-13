package com.gameperf.desktop.core.events

/**
 * Pure classifier that maps an engine name + scene name to an [EventType]
 * phase (CUTSCENE / MENU_NAV / COMBAT_PHASE / TUTORIAL_PHASE), or `null` if
 * no keyword matches.
 *
 * Pure function: no I/O, no state, no side effects. Reads from
 * [EnginePhaseCatalog] (single source of truth — anti-duplication rule).
 *
 * **Algorithm.**
 *  1. Reject scene names that look like Unity Ads mediation (substring
 *     `Ad` / `Ads` / `AdLayout`) — AUTO-010 negative obligation.
 *  2. Look up the rule list for the engine via
 *     [EnginePhaseCatalog.rulesForEngine] (already sorted DESC by
 *     priority).
 *  3. Walk the rules in order; the first rule whose regex matches the
 *     scene name wins. Returns that rule's [KeywordRule.type].
 *  4. Returns `null` if no rule matches → detector emits LOADING only
 *     (AUTO-009 graceful degradation).
 *
 * @since auto-phase-detection-from-engine-logs (Phase 1)
 */
internal object EnginePhaseClassifier {

    /**
     * Classify [sceneName] for the given [engine].
     *
     * @param engine Engine identifier (case-insensitive substring; `Unity`,
     *   `UnityEngine`, `Unreal`, `LogStreaming` all accepted via the
     *   catalog's `rulesForEngine` resolver).
     * @param sceneName Scene name as captured from the engine's scene-load
     *   log line.
     * @return The matched [EventType] phase, or `null` if no rule applies
     *   (obfuscated name, unknown engine, or ad mediation substring).
     */
    fun classify(engine: String, sceneName: String): EventType? {
        if (sceneName.isEmpty()) return null
        if (EnginePhaseCatalog.looksLikeAdMediation(sceneName)) return null
        val rules = EnginePhaseCatalog.rulesForEngine(engine)
        for (rule in rules) {
            if (rule.regex.containsMatchIn(sceneName)) {
                return rule.type
            }
        }
        return null
    }
}
