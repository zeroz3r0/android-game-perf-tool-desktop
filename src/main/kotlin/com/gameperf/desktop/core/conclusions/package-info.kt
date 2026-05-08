/**
 * Deterministic heuristic rule engine for session analysis.
 *
 * This package provides the [ConclusionEngine] which runs a catalog of pure Kotlin
 * rules over filtered/raw metrics aggregates, device tier, and detected events to
 * produce ordered [Conclusion] hypotheses about performance issues.
 *
 * Design principles:
 *  - **Deterministic**: same input always produces identical output.
 *  - **Pure Kotlin**: no LLM, no external config, no network calls.
 *  - **Testable**: every rule has a `<RuleId>Test.kt` with fires/no-fires/boundary cases.
 *  - **Versionable**: rules live in code, changes tracked via git.
 *
 * Key types:
 *  - [Rule] — interface for individual heuristic rules.
 *  - [Conclusion] — output of a fired rule (headline + optional recommendation).
 *  - [Severity] — `CRITICAL`, `WARNING`, `INFO` for ordering.
 *  - [ConclusionInput] — aggregated data passed to each rule's predicate.
 *
 * Rules are registered in [RuleRegistry.all] and sorted by severity then rule ID.
 * The `rules/` subpackage contains the individual rule implementations.
 *
 * @since v4.4.0
 * @see com.gameperf.desktop.core.metrics.FilteredMetricsCalculator for metrics input
 * @see com.gameperf.desktop.core.events.DetectedEvent for event context
 */
package com.gameperf.desktop.core.conclusions
