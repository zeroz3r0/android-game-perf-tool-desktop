/**
 * Developer-actionable enrichment of [com.gameperf.desktop.core.conclusions.ConclusionEngine] output.
 *
 * This package provides the [DevActionEngine] which wraps the existing
 * `ConclusionEngine` and enriches each [com.gameperf.desktop.core.conclusions.Conclusion]
 * with structured evidence, root-cause diagnostic, per-engine code-area
 * hints, suggested actions, and confidence — producing a [DevActionBrief]
 * that the report renders at the top of every session.
 *
 * Design principles (mirrors `core/conclusions/` package):
 *  - **Deterministic**: same `ConclusionInput` → same `DevActionBrief`.
 *  - **Pure Kotlin**: no I/O, no LLM, no network. Catalogs are static `object`s.
 *  - **Additive**: `ConclusionEngine` output is unchanged — the change is
 *    locked by `ConclusionEngineSnapshotTest` (DAB-016 invariant).
 *  - **Wrap, don't replace**: design ADR-1 — `DevActionEngine` consumes
 *    `ConclusionEngine.run(input)` then enriches each `Conclusion`.
 *
 * Sprint 0 ships:
 *  - The data class hierarchy ([DevActionItem], [DevActionBrief], [DevActionEvidence],
 *    [CodeAreaHint], [ActionStep], [LogcatLineRef], [GameEngine], [Confidence]).
 *  - [DevActionEngine.run] wrapping `ConclusionEngine.run`.
 *  - [EvidenceBuilder] stub covering the 8 production rule ids.
 *  - [ConfidenceLookup] with the documented per-rule baselines (design ADR-6).
 *  - Empty [CodeAreaCatalog] and [ActionStepsCatalog] (filled in Sprint 1).
 *
 * Sprint 1+ will fill the catalogs and wire engine auto-detection.
 *
 * Capability spec: `dev-actions` (`sdd/dev-action-brief/spec` — DAB-001..DAB-017).
 *
 * @since v4.5.0
 */
package com.gameperf.desktop.core.devactions
