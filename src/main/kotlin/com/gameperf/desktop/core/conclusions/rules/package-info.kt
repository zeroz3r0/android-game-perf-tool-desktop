/**
 * Individual heuristic rule implementations for [com.gameperf.desktop.core.conclusions.ConclusionEngine].
 *
 * Each file in this package contains exactly ONE [com.gameperf.desktop.core.conclusions.Rule]
 * implementation, named after the rule it represents (e.g., `StableLowFpsRule.kt`,
 * `ThermalThrottlingRule.kt`).
 *
 * Conventions:
 *  - Each rule has a stable kebab-case `id` (used for ordering tiebreaks).
 *  - Predicates are pure: same input → same boolean.
 *  - Rendered output is Castilian Spanish (formal tuteo) per project convention.
 *  - Rules are registered centrally in [com.gameperf.desktop.core.conclusions.RuleRegistry]
 *    — the SINGLE source of truth. Adding a rule = adding to that list. No parallel
 *    registration sites.
 *
 * Test contract: every rule MUST have a corresponding `<RuleId>Test.kt` under
 * `src/test/kotlin/com/gameperf/desktop/core/conclusions/rules/` with at minimum:
 *  1. A fixture that fires the rule.
 *  2. A fixture that does NOT fire the rule.
 *  3. A boundary fixture (just below threshold).
 *
 * @since v4.4.0
 * @see com.gameperf.desktop.core.conclusions.Rule
 * @see com.gameperf.desktop.core.conclusions.ConclusionEngine
 */
package com.gameperf.desktop.core.conclusions.rules
