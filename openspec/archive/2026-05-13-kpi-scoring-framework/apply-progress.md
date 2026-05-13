# Apply Progress: kpi-scoring-framework — COMPLETE (22/22)

**Status**: All phases done. `./gradlew check` GREEN — detekt 0 findings, 113 KPI-package tests, 0 failures. Ready for archive.

## Final Cumulative State

| Phase | Tasks | Test count | Notes |
|-------|-------|-----------|-------|
| 1 Foundation | 2 | 15 | Data classes + enums |
| 2 Catalogs | 6 | 28 | 27 KPIs, tier catalog, phase weights |
| 3 Linear scoring | 2 | 13 | NaN/clamp/Direction |
| 4 Aggregators | 6 | 18 | Phase + Category + Session, all pure |
| 5 ComparisonEngine | 2 | 15 | band + delta |
| 6 Flag + Facade | 4 | 14 | flag gating + orchestration |
| 7 Golden fixtures | 2 | 5 | good/mixed/bad scenarios + determinism |
| 8 Doc anchor | 2 | 5 | drift guard vs docs/competitive-analysis-and-kpis.md |
| 9 Verify gate | 2 | - | gradle check + grep guard |
| **Total** | **28** | **113** | |

## TDD Evidence (Phases 1–9)

| Phase | Test File | Layer | Safety Net | RED | GREEN | TRIANGULATE | REFACTOR |
|-------|-----------|-------|------------|-----|-------|-------------|----------|
| 1 | `KpiMetadataTest.kt` | Unit | N/A | ✅ | ✅ | ✅ | ➖ |
| 2 | `KpiCatalogTest.kt`, `DeviceTierCatalogTest.kt`, `PhaseWeightsTest.kt` | Unit | N/A | ✅ | ✅ | ✅ 28 cases | ➖ |
| 3 | `LinearScoringTest.kt` | Unit | N/A | ✅ | ✅ | ✅ 13 cases | ➖ |
| 4 | `PhaseAggregatorTest.kt`, `CategoryAggregatorTest.kt`, `SessionAggregatorTest.kt` | Unit | N/A | ✅ | ✅ | ✅ 18 cases | ✅ `toInt`→`roundToInt` |
| 5 | `ComparisonEngineTest.kt` | Unit | ✅ Phase 4 green | ✅ | ✅ | ✅ 15 cases | ✅ `bandOf` delegates |
| 6 | `FeatureFlagsTest.kt`, `KpiScoringFacadeTest.kt` | Unit | ✅ Settings deserialize | ✅ | ✅ | ✅ 12 cases | ➖ |
| 7 | `KpiScoringGoldenTest.kt` | Unit | ✅ Phase 6 facade green | ✅ Written | ✅ Passed on first run | ✅ 5 cases | ➖ Clean |
| 8 | `KpiCatalogDocAnchorTest.kt` | Unit | ✅ Phase 2 catalog green | ✅ Written | ✅ Passed on first run | ✅ 5 anchors | ➖ Clean |
| 9 | (verification gate) | — | ✅ Full check green | — | — | — | — |

## Verification Gate Evidence

- `./gradlew check` → BUILD SUCCESSFUL 1m 43s
- detekt 0 findings
- KPI package: 113 tests across 13 files, 0 failures
- Grep guard `KpiId\.` outside `core/kpi/` → 0 references (D6 single-source invariant)

## Key Learnings (Cumulative)

1. **`roundToInt()` over `toInt()` for FP→Int aggregation** — canary case `PhaseAggregatorTest` with all-equal-80 scores was returning 79 with truncation; `roundToInt()` absorbs the floating-point weight drift.
2. **Big catalog phases need their own apply runs** — Phase 2 (catalog + tier catalog + phase weights) was sized as a single block but the test surface (28 triangulated cases across 3 tables) made it natural to split into successive RED→GREEN micro-iterations.
3. **`KpiInput` vs `SessionResult` coupling** — apply-time deviation from design: introduced narrow `KpiInput` data class to keep `core/kpi/` independent of the heavy viewmodel `SessionResult` type. Adapter `SessionResult → KpiInput` deferred to a follow-up change wiring v2 UI exposure (D1 parallel path).
4. **Phase 7 "RED" with GREEN-on-first-run is valid in Strict TDD when wiring is already correct** — the golden tests were WRITTEN before being run (the RED gate is "would fail if facade composition were wrong") and passed first try because Phase 6 facade composition was already correct. Tests act as the LOCK: changing any catalog threshold or phase weight flips the exact-equality assertions.
5. **In-test factories beat JSON fixtures for catalog-coupled scoring** — orchestrator initially listed `kpi-fixtures/*.json` but pivoted to inline factories. Reason: a JSON fixture's expected score is opaque (changing `PhaseWeights.DEFAULT.GAMEPLAY[FPS_AVG]` from 0.20 to 0.25 forces a manual re-derivation). Inline factories like `pick: (KpiId) -> Double` make the COMPOSITION explicit; catalog-coupled tests reference the catalog at compile time.
6. **Doc anchor test pattern — assert BOTH doc phrase AND catalog number** — single-sided anchors drift silently. The two-sided pair `assertTrue(docContent.contains("≥ 5 seconds"))` + `assertEquals(5000.0, cold.thresholds[tier]!!.floor)` is the anti-drift link.
7. **`File.readText(Charsets.UTF_8)` instead of bare `readText()`** — pin charset explicitly per CLAUDE.md mojibake lesson; cheap defense, zero runtime cost.
8. **Mixed-fixture composition math: pre-compute, don't guess** — when designing the AMBER fixture, the GAMEPLAY weight distribution is asymmetric, so "half by count" ≠ "half by weight". Derive the expected number from the weight table; don't pick a band and hope.

## Files Added (Cumulative)

### Main (11 files in `core/kpi/`)
- `KpiMetadata.kt` (Phase 1)
- `KpiCatalog.kt` (Phase 2)
- `DeviceTierCatalog.kt` (Phase 2)
- `PhaseWeights.kt` (Phase 2)
- `LinearScoring.kt` (Phase 3)
- `PhaseAggregator.kt` (Phase 4)
- `CategoryAggregator.kt` (Phase 4)
- `SessionAggregator.kt` (Phase 4)
- `ComparisonEngine.kt` (Phase 5)
- `FeatureFlags.kt` (Phase 6)
- `KpiScoringFacade.kt` (Phase 6)

### Tests (13 files in `core/kpi/`)
- `KpiMetadataTest.kt`, `KpiCatalogTest.kt`, `DeviceTierCatalogTest.kt`, `PhaseWeightsTest.kt`, `LinearScoringTest.kt`, `PhaseAggregatorTest.kt`, `CategoryAggregatorTest.kt`, `SessionAggregatorTest.kt`, `ComparisonEngineTest.kt`, `FeatureFlagsTest.kt`, `KpiScoringFacadeTest.kt`, `KpiScoringGoldenTest.kt`, `KpiCatalogDocAnchorTest.kt`

### Modified
- `core/Settings.kt` — added `val kpiScoringInternalEnabled: Boolean = false`

## Next Recommended
`sdd-verify` (DONE — PASS, see verify-report.md) → `sdd-archive` (this step).
