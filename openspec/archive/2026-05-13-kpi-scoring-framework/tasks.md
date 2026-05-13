# Tasks: KPI Scoring Framework (Issue #2 Block E)

Strict TDD. `.R` = RED (failing test). `.G` = GREEN (min code). Pkg `com.gameperf.desktop.core.kpi`.

## Phase 1: Foundation (data classes) ✅

- [x] 1.R Add `KpiMetadataTest.kt` asserting enum `KpiId` has ≥23 entries; `Category`, `Phase`, `DeviceTier`, `Direction`, `Band` exist; `Threshold(target,floor)` and `Kpi` data classes compile. Cmd: `./gradlew test --tests *KpiMetadataTest`
- [x] 1.G Create `core/kpi/KpiMetadata.kt` with enums + `Threshold`, `Kpi`, `KpiScore`, `PhaseScore`, `CategoryScore`, `KpiScoreReport` data classes per design Interfaces section.

## Phase 2: Catalogs (single source of truth — KPI-001/006) ✅

- [x] 2.R `KpiCatalogTest`: ≥23 KPIs, all 3 tiers present per KPI, citations non-empty, no dup `KpiId`, `byId`/`forCategory` lookups, doc-anchor cold-start/slow-frames/FPower.
- [x] 2.G Create `core/kpi/KpiCatalog.kt` (27 entries, `ALL: List<Kpi>`) covering all KPIs from docs §5.1 with per-tier `Threshold` + KDoc citation. KDoc warning block: "DO NOT define KPI metadata outside this file" (mirror `SdkSignatureCatalog`).
- [x] 2.R `DeviceTierCatalogTest`: Galaxy S23 (SM-S911B)→TOP, Pixel 8 Pro→TOP, Pixel 6a→MID, Galaxy Tab A8→LOW, unknown→MID default, null/blank→MID.
- [x] 2.G Create `core/kpi/DeviceTierCatalog.kt` exact-match + substring containment allow-list (mirror `ThermalZoneClassifier`).
- [x] 2.R `PhaseWeightsTest`: each phase weight map sums to 1.0 ±0.001; `phaseWeights` sums to 1.0; covers all `Phase` enum values; gameplay weight dominates.
- [x] 2.G Create `core/kpi/PhaseWeights.kt` with `DEFAULT.kpiWeightsForPhase` + `phaseWeights` from docs §5.2.

## Phase 3: Pure linear scoring (KPI-002) ✅

- [x] 3.R `LinearScoringTest` 13 scenarios: target→100, floor→0, mid→50, beyond target→100 (clamp), beyond floor→0 (clamp), NaN→0, ±∞ per direction, both directions covered.
- [x] 3.G Create `core/kpi/LinearScoring.kt` pure top-level `scoreLinear(value,target,floor,direction):Int`. Defensive `span==0` branch (no divide-by-zero).

## Phase 4: Aggregators (KPI-003/004/005) ✅

- [x] 4.R `PhaseAggregatorTest`: weighted avg, missing-KPI renormalization, all-missing→null, band boundaries (80/60/59).
- [x] 4.G Create `core/kpi/PhaseAggregator.kt` pure `aggregatePhase(phase, scores, weights)`. Uses `roundToInt()` not `toInt()` to absorb floating-point weight drift (canary: all-equal-80 case was returning 79 with truncation).
- [x] 4.R `CategoryAggregatorTest`: cross-phase weighted avg using `phase × kpi` combined weight, missing-data renormalization, empty-category→null (distinct from 0), every category present in output.
- [x] 4.G Create `core/kpi/CategoryAggregator.kt` pure `aggregateCategories(scoresByPhase, weights): List<CategoryScore>`. Uses `KpiCatalog.byId(kpi).category` for grouping.
- [x] 4.R `SessionAggregatorTest`: all-phases-present, weighted-avg different scores, missing-phase renormalization, empty-list→null.
- [x] 4.G Create `core/kpi/SessionAggregator.kt` pure `aggregateSession(phaseScores, weights): KpiScoreReport?`. `categories` left empty — facade will populate.

Also added: `internal fun bandOf(score: Int): Band` in `PhaseAggregator.kt` (top-level, shared by all aggregators). Promoted to public `ComparisonEngine.band` in Phase 5 — `bandOf` now delegates.

## Phase 5: Comparison engine (KPI-007) ✅

- [x] 5.R `ComparisonEngineTest`: bands at 80/60 boundaries (90/65/40/80/79/60/59/100/0); delta sign per direction (HIGHER vs LOWER); positive-means-better convention.
- [x] 5.G Create `core/kpi/ComparisonEngine.kt` pure `object` with `band(score):Band` + `delta(actual,target,direction):Double`. Constants `GREEN_THRESHOLD=80` / `AMBER_THRESHOLD=60`. Refactored `PhaseAggregator.bandOf` to delegate (single source of truth).

## Phase 6: Feature flag + facade (KPI-008) ✅

- [x] 6.R `FeatureFlagsTest`: default OFF, sysprop ON, settings ON, sysprop non-"true" stays OFF, both ON stays ON.
- [x] 6.G Create `core/kpi/FeatureFlags.kt` (`object` w/ `isKpiScoringInternalEnabled(settings)`); add `kpiScoringInternalEnabled: Boolean=false` to `core/Settings.kt`.
- [x] 6.R `KpiScoringFacadeTest`: flag OFF→null; sysprop ON→non-null; settings ON→non-null; orchestrates phase+category+session aggregation; degrades on bad raw values; tier resolution via deviceModel; explicit tier override beats deviceModel.
- [x] 6.G Create `core/kpi/KpiScoringFacade.kt` (`object`) + `KpiInput` data class (decoupled from heavy viewmodel `SessionResult` — adapter deferred). Pipeline: gate → resolve tier → score linear → aggregate phase → aggregate categories → aggregate session → attach categories via `.copy()`.

## Phase 7: Golden fixtures ✅

- [x] 7.R `KpiScoringGoldenTest` 3 in-test fixtures (good/mixed/bad) + 2 property tests. NO JSON files — kept fixtures inline as Kotlin factories per orchestrator simplification (compile-time catalog/weights references, self-contained). good=100 GREEN, mixed=65 AMBER (FPS_AVG+FPS_P1 at floor → exactly 65), bad=20 RED (only TEMP_MAX+JANK_COUNT+CPU_AVG at target → exactly 20). Properties: determinism (same input → equal report); flag-OFF gate (good/mixed/bad/empty all → null).
- [x] 7.G No production code needed — facade was already correct from Phase 6. All 5 tests pass on first run (GREEN-on-first-execution confirms Phase 6 facade composition matches the documented band thresholds end-to-end).

## Phase 8: Doc anchor test (anti-drift) ✅

- [x] 8.R `KpiCatalogDocAnchorTest` reads `docs/competitive-analysis-and-kpis.md` via `File.readText(Charsets.UTF_8)` (explicit UTF-8 per CLAUDE.md mojibake lesson). 5 anchor assertions: cold start ≥5s (COLD_START_MS.floor=5000 all tiers), warm start ≥2s, hot start ≥1s, slow frames >50% (SLOW_FRAMES.floor=50 all tiers), FPower 50/65 mW/frame (FPOWER.target=50 + floor=65 all tiers). Each anchor verifies BOTH the doc phrase still exists AND the catalog matches — drift in either direction fails the test.
- [x] 8.G No catalog adjustment needed — every anchor matched on first run.

## Phase 9: Verification gate ✅

- [x] 9.1 Ran `./gradlew check` — BUILD SUCCESSFUL in 1m 43s, detekt 0 findings, all tests green. KPI package: **13 test files, 113 tests, 0 failures** (Phase 6 baseline 103 → +10 new: 5 golden + 5 anchor).
- [x] 9.2 Grep guard `KpiId\.` outside `core/kpi/` — PowerShell `Get-ChildItem -Recurse src\main\kotlin\com\gameperf\desktop -File -Include *.kt | Where-Object { $_.FullName -notmatch 'core\\kpi' } | Select-String 'KpiId\.' | Measure-Object` returned **count = 0** (single-source D6 confirmed — no callers wired yet, internal v1 by design).
