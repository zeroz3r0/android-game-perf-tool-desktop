package com.gameperf.desktop.core.events

/**
 * Single source of truth for engine-phase keyword rules used by
 * [EnginePhaseClassifier] to map scene names (captured from Unity / Unreal
 * scene-load log lines) to [EventType] phases.
 *
 * **Anti-duplication rule.** All keyword maps, priority constants, and
 * regex compilations for auto-phase detection MUST live here. No parallel
 * definitions elsewhere — same lesson as `SdkSignatureCatalog`
 * (v4.4.0) and `ToolResolver` (v4.2.13). If a future engine joins the
 * scope (Cocos2d, Godot…) it gets a new rule list in THIS file. Never
 * hand-roll keyword sets in `EventDetectorImpl`, `ReportGenerator`, or
 * anywhere else.
 *
 * **Priority schema.** When a scene name matches multiple keyword sets,
 * the highest-priority match wins (DESC ordering, first match in the
 * sorted list returned by [rulesForEngine]).
 *
 *  - `BOSS` keyword → COMBAT_PHASE @ 100  (boss fights subsume combat)
 *  - COMBAT family → COMBAT_PHASE @ 90    (combat | combate | fight |
 *                                          battle | wave | oleada)
 *  - CUTSCENE family → CUTSCENE @ 80      (cinematic | cinemática |
 *                                          cutscene | intro | outro)
 *  - TUTORIAL family → TUTORIAL_PHASE @ 70 (tutorial | onboarding |
 *                                          introducción | tuto)
 *  - MENU family → MENU_NAV @ 60          (menu | lobby | mainmenu |
 *                                          home | inicio)
 *
 * **Bilingual coverage (AUTO-005).** Each rule carries ES+EN keywords as
 * one combined set; the classifier is locale-agnostic.
 *
 * **Tag-allowlist guard (AUTO-010).** Scene names whose text contains the
 * substring `Ad`, `Ads`, or `AdLayout` (case-insensitive) are excluded by
 * [EnginePhaseClassifier] BEFORE keyword matching. This prevents Unity Ads
 * mediation lines like `MainMenuAdLayout` from being mis-classified as
 * MENU_NAV. The exclusion is enforced classifier-side so the catalog
 * stays a pure data table.
 *
 * @since auto-phase-detection-from-engine-logs (Phase 2)
 */
internal data class KeywordRule(
    val keywords: Set<String>,
    val type: EventType,
    val priority: Int,
) {
    /**
     * Compiled regex matching any of [keywords] with camelCase-aware
     * boundaries. Compiled ONCE at construction (data class field) — hot
     * paths must not recompile per line (v4.2.4 lesson: top-level regex as
     * `private val`).
     *
     * Boundary semantics: a keyword matches when it is NOT preceded by and
     * NOT followed by another lowercase letter. This covers:
     *  - `BattleArena` — `battle` matches: previous char is start, next
     *    char `A` is not lowercase.
     *  - `oleada_03` — `oleada` matches: previous char is start, next char
     *    `_` is not lowercase.
     *  - `MainMenu` — `menu` matches: previous char `n` (lowercase) fails…
     *    so the regex also accepts a preceding uppercase letter via the
     *    capitalised form. We solve this by treating the input as if word
     *    boundaries existed between lower→upper case transitions: the
     *    lookbehind permits start, non-letter, OR uppercase letter.
     *
     * The resulting pattern: `(?<![a-z])(keywords)(?![a-z])` with
     * case-insensitive flag — `(?<![a-z])` permits start-of-string,
     * non-letters, AND uppercase letters as left boundary. Symmetric on
     * the right.
     */
    val regex: Regex = Regex(
        // Inline (?i) only on the keyword group; lookbehind / lookahead
        // character classes [a-z] stay case-sensitive so uppercase letters
        // count as boundaries (camelCase: `BattleArena` → `Battle` matches).
        "(?<![a-z])(?i:${keywords.joinToString("|")})(?![a-z])",
    )
}

internal object EnginePhaseCatalog {

    /** Priority constants — DESC order resolves compound names (AUTO-006). */
    const val PRIORITY_BOSS: Int = 100
    const val PRIORITY_COMBAT: Int = 90
    const val PRIORITY_CUTSCENE: Int = 80
    const val PRIORITY_TUTORIAL: Int = 70
    const val PRIORITY_MENU: Int = 60

    /** Explicit DESC priority order — referenced by invariant tests. */
    val PRIORITY_ORDER: List<Int> = listOf(
        PRIORITY_BOSS,
        PRIORITY_COMBAT,
        PRIORITY_CUTSCENE,
        PRIORITY_TUTORIAL,
        PRIORITY_MENU,
    )

    /**
     * Unity engine keyword rules. Already sorted DESC by priority.
     *
     * BOSS rule precedes COMBAT rule so `BossArenaMenu` → COMBAT_PHASE
     * (BOSS wins over both COMBAT and MENU); see AUTO-006 priority test.
     */
    val UNITY_RULES: List<KeywordRule> = listOf(
        KeywordRule(
            keywords = setOf("boss", "jefe"),
            type = EventType.COMBAT_PHASE,
            priority = PRIORITY_BOSS,
        ),
        KeywordRule(
            keywords = setOf("combat", "combate", "fight", "battle", "wave", "oleada"),
            type = EventType.COMBAT_PHASE,
            priority = PRIORITY_COMBAT,
        ),
        KeywordRule(
            keywords = setOf("cinematic", "cinemática", "cutscene", "intro", "outro"),
            type = EventType.CUTSCENE,
            priority = PRIORITY_CUTSCENE,
        ),
        KeywordRule(
            keywords = setOf("tutorial", "onboarding", "introducción", "tuto"),
            type = EventType.TUTORIAL_PHASE,
            priority = PRIORITY_TUTORIAL,
        ),
        KeywordRule(
            keywords = setOf("menu", "lobby", "mainmenu", "home", "inicio"),
            type = EventType.MENU_NAV,
            priority = PRIORITY_MENU,
        ),
    )

    /**
     * Unreal engine keyword rules. Same keyword set as Unity — phase
     * semantics are engine-independent. Kept as a separate list because
     * future divergence (e.g., Unreal-specific `EditorPie` tutorial scenes)
     * should not bleed into Unity classification.
     */
    val UNREAL_RULES: List<KeywordRule> = UNITY_RULES

    /**
     * Return the rule list for [engine] (case-insensitive substring on the
     * engine name).
     *
     * Unknown engines fall through to an empty list — classifier then
     * returns `null` and the detector emits LOADING only (AUTO-009 graceful
     * degradation).
     */
    fun rulesForEngine(engine: String): List<KeywordRule> {
        val key = engine.lowercase()
        return when {
            key.contains("unity") -> UNITY_RULES
            key.contains("unreal") -> UNREAL_RULES
            else -> emptyList()
        }
    }

    /**
     * Substring guard: a scene name containing the `Ad` / `Ads` / `AdLayout`
     * marker as a CamelCase boundary segment is rejected by the classifier
     * BEFORE keyword matching. Enforces AUTO-010 tag-allowlist negative.
     *
     * Boundary semantics (mirrors [KeywordRule.regex]):
     *  - left side: start-of-string, non-letter, or any letter that is
     *    followed by an uppercase boundary (CamelCase: `MainMenuAdLayout`
     *    → `Ad` starts at uppercase `A` after `u`).
     *  - right side: must be uppercase letter (`AdLayout`, `AdMenu`) or
     *    end-of-string / non-letter (Ad, Ads as standalone tokens).
     *
     * Crucially the segment MUST start with capital `A` — lowercase `ad`
     * embedded in `oleada` is NOT a match. This is why the regex is
     * case-sensitive on the marker (no `(?i)` flag) — case-insensitive
     * matching would catch the false positive `oleada` (Spanish for "wave"
     * → COMBAT_PHASE).
     */
    private val AD_SUBSTRING_REGEX: Regex = Regex(
        // Capital `A` is required; the marker is one of Ad / Ads / AdLayout.
        // Right boundary: uppercase letter (next segment starts capital) or
        // end / non-letter. Left boundary: any non-lowercase (start, _, /,
        // digit, OR uppercase — `MainMenuAdLayout` has `u` then `A`, but
        // since `Ad` starts at the capital A, the `u` is at the position
        // BEFORE the match: we accept it because the marker itself starts
        // with capital A which already encodes the CamelCase boundary).
        "(AdLayout|Ads|Ad)(?![a-z])",
    )

    /** @return `true` when [sceneName] matches Unity Ads mediation patterns. */
    fun looksLikeAdMediation(sceneName: String): Boolean =
        AD_SUBSTRING_REGEX.containsMatchIn(sceneName)
}
