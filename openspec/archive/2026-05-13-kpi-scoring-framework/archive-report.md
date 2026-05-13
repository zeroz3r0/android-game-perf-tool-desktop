# Archive Report: kpi-scoring-framework

**Change**: `kpi-scoring-framework`
**Archived on**: 2026-05-13
**Status**: ARCHIVED (verify PASS)
**Archive path**: `openspec/archive/2026-05-13-kpi-scoring-framework/`
**New main spec**: `openspec/specs/kpi-scoring/spec.md` (NEW capability)

## Change Summary

Implemented internal v1 KPI scoring framework. Pure functional core. 27 KPIs catalog, 3 device tiers, linear scoring model A, phase/category/session aggregators, comparison engine with color bands, feature-flag-gated facade. Internal v1 — UI exposure deferred. SessionResult adapter deferred. Block E of issue #2.

## Engram Observation IDs (Audit Trail)

| Artifact | Observation ID | Topic Key |
|----------|---------------|-----------|
| Proposal | #361 | `sdd/kpi-scoring-framework/proposal` |
| Spec | #367 | `sdd/kpi-scoring-framework/spec` |
| Design | #368 | `sdd/kpi-scoring-framework/design` |
| Tasks | #370 | `sdd/kpi-scoring-framework/tasks` |
| Apply progress | #374 | `sdd/kpi-scoring-framework/apply-progress` |
| Verify report | #378 | `sdd/kpi-scoring-framework/verify-report` |
| Archive report (this) | (saved) | `sdd/kpi-scoring-framework/archive-report` |
| Project context | #96 | `sdd-init/android-game-perf-tool-desktop` |

## Specs Synced

| Domain | Action | Details |
|--------|--------|---------|
| `kpi-scoring` | **Created** (new capability) | 8 ADDED requirements, 18 scenarios — copied wholesale from delta spec since main spec did not exist |

## Files Added (24 production + test files)

### Main code (11 files in `src/main/kotlin/com/gameperf/desktop/core/kpi/`)
- `KpiMetadata.kt` — data classes & enums (KpiId, Category, Phase, DeviceTier, Direction, Band, Threshold, Kpi, KpiInput, KpiValue, PhaseScore, CategoryScore, SessionScore, Comparison, KpiScoreReport)
- `KpiCatalog.kt` — 27-KPI single source of truth with per-tier thresholds + KDoc citations
- `DeviceTierCatalog.kt` — model + SoC allow-list → TOP/MID/LOW (default MID)
- `PhaseWeights.kt` — DEFAULT phase × KpiId weight table; sums to 1.0 invariant
- `LinearScoring.kt` — pure scoreLinear(value, target, floor, direction): Int with NaN/clamp/Direction handling
- `PhaseAggregator.kt` — aggregatePhase + bandOf delegate
- `CategoryAggregator.kt` — cross-phase grouping via KpiCatalog.byId(...).category
- `SessionAggregator.kt` — aggregateSession returning KpiScoreReport
- `ComparisonEngine.kt` — band(score):Band (single source: GREEN ≥80, AMBER 60-79, RED <60) + delta(value, target, direction):Double
- `FeatureFlags.kt` — isKpiScoringInternalEnabled(settings) checking sysprop OR Settings
- `KpiScoringFacade.kt` — KpiInput data class + compute orchestrator (gate → resolve tier → score → aggregate)

### Tests (13 files in `src/test/kotlin/com/gameperf/desktop/core/kpi/`)
- `KpiMetadataTest.kt`
- `KpiCatalogTest.kt`
- `DeviceTierCatalogTest.kt`
- `PhaseWeightsTest.kt`
- `LinearScoringTest.kt`
- `PhaseAggregatorTest.kt`
- `CategoryAggregatorTest.kt`
- `SessionAggregatorTest.kt`
- `ComparisonEngineTest.kt`
- `FeatureFlagsTest.kt`
- `KpiScoringFacadeTest.kt`
- `KpiScoringGoldenTest.kt` (good/mixed/bad fixtures + determinism property + flag-OFF property)
- `KpiCatalogDocAnchorTest.kt` (cold/warm/hot start + slow frames + FPower anchors vs docs/competitive-analysis-and-kpis.md)

### Modified
- `src/main/kotlin/com/gameperf/desktop/core/Settings.kt` — added `val kpiScoringInternalEnabled: Boolean = false` (additive, backward-compat)

## Tests Added

**113 new tests under `core/kpi/`**, distributed:

| Test class | Count | Coverage |
|-----------|-------|----------|
| KpiMetadataTest | 15 | data classes + enums |
| KpiCatalogTest | (subset of 28) | 27 KPIs, tier coverage, citations, no duplicates |
| DeviceTierCatalogTest | (subset of 28) | Galaxy S23/Pixel/Tab A8/unknown→MID |
| PhaseWeightsTest | (subset of 28) | weight maps sum to 1.0, all phases covered |
| LinearScoringTest | 13 | target→100, floor→0, mid→50, clamps, NaN→0, both directions |
| PhaseAggregatorTest | (subset of 18) | weighted avg, renormalization, band boundaries 80/60/59 |
| CategoryAggregatorTest | (subset of 18) | cross-phase grouping, empty→null |
| SessionAggregatorTest | (subset of 18) | missing-phase renormalization, empty→null |
| ComparisonEngineTest | 15 | band boundaries 90/80/79/65/60/59/40/100/0, delta sign per direction |
| FeatureFlagsTest | (subset of 14) | sysprop OFF/ON, settings OFF/ON, default OFF |
| KpiScoringFacadeTest | (subset of 14) | flag OFF→null, sysprop ON→non-null, settings ON→non-null, tier resolution |
| KpiScoringGoldenTest | 5 | good=100 GREEN, mixed=65 AMBER, bad=20 RED + determinism + flag-OFF property |
| KpiCatalogDocAnchorTest | 5 | cold ≥5s / warm ≥2s / hot ≥1s / slow frames >50% / FPower 50&65 mW |
| **TOTAL** | **113** | 13 test files |

## Spec Requirements Implemented (Cross-Reference)

| Requirement | Spec ID | Implemented in | Test class |
|-------------|---------|----------------|-----------|
| KPI Catalog Single Source of Truth | KPI-001 | `KpiCatalog.kt` | `KpiCatalogTest`, `KpiCatalogDocAnchorTest` |
| Linear Scoring Function | KPI-002 | `LinearScoring.kt` | `LinearScoringTest` |
| Phase Aggregation | KPI-003 | `PhaseAggregator.kt` | `PhaseAggregatorTest` |
| Category Aggregation | KPI-004 | `CategoryAggregator.kt` | `CategoryAggregatorTest` |
| Session Aggregation | KPI-005 | `SessionAggregator.kt` | `SessionAggregatorTest` |
| Comparison Engine with Color Bands | KPI-006 | `ComparisonEngine.kt` | `ComparisonEngineTest` |
| Feature Flag Gating | KPI-007 | `FeatureFlags.kt` + `Settings.kt` | `FeatureFlagsTest`, `KpiScoringFacadeTest` |
| Device Tier Resolution | KPI-008 | `DeviceTierCatalog.kt` | `DeviceTierCatalogTest`, `KpiScoringFacadeTest` |

End-to-end facade composition locked by `KpiScoringGoldenTest` (3 fixtures) and `KpiScoringFacadeTest` (flag + tier).

## Verification Gate (verify-report #378)

- `./gradlew check` → BUILD SUCCESSFUL 1m 38s
- detekt → 0 findings
- KPI tests → 113 / 0 failures across 13 files
- Grep guard `KpiId\.` outside `core/kpi/` → **0 references** (D6 single-source invariant)
- `Settings.kpiScoringInternalEnabled` default → `false`
- 0 CRITICAL, 0 WARNING

## Lessons Learned (for future SDD)

1. **`roundToInt()` not `toInt()` for FP→Int aggregation** (engram #377) — truncation silently dropped the all-equal-80 canary in `PhaseAggregatorTest` to 79. Always round on the float-to-int boundary in weighted averages; the canary-by-construction pattern (all-equal inputs → that exact integer) is the cheapest defense.
2. **Big catalog phases need dedicated apply runs** (engram #369) — Phase 2 (catalog + tier catalog + phase weights) had 28 triangulated cases across 3 tables. The natural split was successive RED→GREEN micro-iterations per table, not a single block. Don't fight the test surface; let it carve the iteration size.
3. **Coupling-aware deviation: `KpiInput` vs coupling to `SessionResult`** (apply-progress #374) — design called for `KpiScoringFacade.compute(session: SessionResult, ...)`. During apply we noticed the viewmodel `SessionResult` is heavy + platform-coupled. Introduced narrow `KpiInput` to keep `core/kpi/` pure and standalone-testable. Adapter `SessionResult → KpiInput` deferred to follow-up wiring v2 UI. Document the deviation in apply-progress + verify-report + archive — don't silently absorb it.
4. **In-test factories beat JSON fixtures for catalog-coupled tests** (engram learning) — the original plan had `kpi-fixtures/*.json` files; pivoted to inline Kotlin factories so changing a weight or threshold doesn't force manually re-deriving the expected score. Catalog-coupled tests should reference the catalog at compile time, not snapshot it in JSON.
5. **Two-sided doc anchor pattern** (engram learning, Phase 8) — `assertTrue(docContent.contains("≥ 5 seconds"))` AND `assertEquals(5000.0, cold.thresholds[tier]!!.floor)` together. Single-sided drifts silently. Use externally authoritative thresholds (Google Vitals §3.1, PerfDog §3.6) for the anchor list because those don't drift on a whim.

## Files Left for Follow-up

- **Adapter `SessionResult → KpiInput`** — needed to wire `KpiScoringFacade.compute` into `AppViewModel` so the v2 UI can display scores. Internal v1 ships without it (calc runs in tests only). Track as separate SDD change.
- **Calibration test pack (Block G of issue #2)** — needs real captures from B.1/B.2 hands-on lab sessions. Will validate that the catalog thresholds match real-device behavior (good FPS_AVG=60 game on TOP tier truly scores ≥80, etc.) and tune `PhaseWeights.DEFAULT` if necessary. Separate change.
- **Sigmoid (Model B) / Bucket (Model C) scoring** — v2+ after calibration accumulates ≥50 sessions per §6.2 doc decision #5.
- **HTML report rendering of KPI scores** — separate `shareable-html-report` change.
- **JSON / CSV export of `KpiScoreReport`** — separate change.
- **System-property `gameperf.kpi.internal` deprecation** — open question D5; remove once persistent Settings UI lands.

## SDD Cycle Complete

The change has been fully planned, implemented, verified, and archived. Source of truth in `openspec/specs/kpi-scoring/spec.md` reflects the new behavior. Ready for the next change.
