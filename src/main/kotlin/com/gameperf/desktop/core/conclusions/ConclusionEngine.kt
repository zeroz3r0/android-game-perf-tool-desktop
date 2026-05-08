package com.gameperf.desktop.core.conclusions

/**
 * Pure-function rule executor for heuristic conclusions.
 *
 * Iterates over all registered [Rule]s in [RuleRegistry.all], filters by
 * [Rule.matches], renders surviving rules into [Conclusion]s, and sorts by
 * (severity DESC, ruleId ASC) for stable output.
 *
 * Determinism contract:
 *  - Same [ConclusionInput] always produces the same output (CON-001).
 *  - Output ordering is deterministic across runs (CON-004).
 *  - No I/O, no clock reads, no Random — pure function over input.
 *
 * Severity ordering note: [Severity] is declared as `CRITICAL, WARNING, INFO`
 * so `ordinal` is `0, 1, 2` respectively. Sorting `compareByDescending { ordinal }`
 * therefore places CRITICAL first, WARNING second, INFO last — exactly what the
 * spec (CON-004) requires.
 *
 * @since v4.4.0
 */
object ConclusionEngine {

    /**
     * Runs all registered rules against [input] and returns the firing
     * conclusions sorted for display.
     *
     * Sort order:
     *  1. Severity descending: CRITICAL > WARNING > INFO.
     *  2. Rule id ascending (kebab-case alphabetical) for stable tiebreak.
     *
     * @param input Aggregated session data (filtered + raw + events + tier).
     * @return Ordered list of conclusions; empty when no rules fire.
     */
    fun run(input: ConclusionInput): List<Conclusion> {
        return RuleRegistry.all
            .filter { it.matches(input) }
            .map { it.render(input) }
            .sortedWith(
                // CRITICAL.ordinal=0, WARNING=1, INFO=2.
                // Ascending ordinal => CRITICAL first, INFO last (exactly the
                // visual "severity DESC" the spec asks for in CON-004).
                compareBy<Conclusion> { it.severity.ordinal }
                    .thenBy { it.ruleId }
            )
    }
}
