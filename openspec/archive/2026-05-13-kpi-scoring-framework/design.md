# Design: KPI Scoring Framework (Issue #2 Block E)

## Technical Approach

Pure-functional scoring core in a new `core/kpi/` package. Single source of truth `KpiCatalog` defines every KPI (id, category, phase relevance, per-tier `Threshold`). `KpiScoringFacade` is the only entry point that takes a `SessionResult` (+ optional `DeviceTier`) and produces a `KpiScoreReport`. Calculation runs unconditionally (testable); UI/report exposure is gated by Kotlin system-property `gameperf.kpi.internal=true`. `FinalScoreCalculator` is left untouched (parallel path), so v1 ships without breaking the existing A-F grade or report regeneration of pre-v4.5 sessions. KPI numbers/thresholds anchored on `docs/competitive-analysis-and-kpis.md` §3.1 (Android Vitals), §3.2 (RAIL), §3.6 (PerfDog), §5.1 (master catalog), §5.2 (relevance map), §6.3 (tiers).

## Package Layout (`core/kpi/`)

| File | Role |
|------|------|
| `KpiMetadata.kt` | Data classes: `KpiId` (enum), `Kpi`, `Category` (Smoothness/Resource/Thermal/Stability/Responsiveness), `Phase` (8 from §4.1), `Direction` (HIGHER_IS_BETTER/LOWER), `Threshold(target,floor)`, `DeviceTier` (TOP/MID/LOW). Pure types. |
| `KpiCatalog.kt` | **SINGLE SOURCE OF TRUTH.** `ALL: List<Kpi>` covering 23+ KPIs from §5.1 with citation in KDoc per entry (mirror `SdkSignatureCatalog.ALL`). Lookup helpers: `byId(KpiId)`, `forPhase(Phase): List<Kpi>`. |
| `DeviceTierCatalog.kt` | Strict allow-list of device-model strings + SoC families → `DeviceTier` (mirror `ThermalZoneClassifier` / `FPowerVendorCatalog` exact-match-first pattern). `classify(deviceModel: String, soc: String?): DeviceTier?`. Default tier = MID when null. |
| `PhaseWeights.kt` | Tables `kpiWeightsForPhase: Map<Phase, Map<KpiId, Double>>` + `phaseWeights: Map<Phase, Double>` from §5.2. Property test asserts each map sums to 1.0. |
| `LinearScoring.kt` | Pure `scoreLinear(value, target, floor, direction): Int` (0-100). Model A only. NaN/null → 0. |
| `PhaseAggregator.kt` | Pure weighted-average → `Int` per phase. Handles missing KPIs by renormalizing weights. |
| `CategoryAggregator.kt` | Pure cross-phase weighted average grouped by `Category`. |
| `SessionAggregator.kt` | Pure weighted average across phases present (renormalizes when phases absent). |
| `ComparisonEngine.kt` | Pure `band(score: Int): Band` (GREEN ≥80, AMBER 60-79, RED <60) + `delta(value, target): Double`. |
| `KpiScoringFacade.kt` | Public orchestrator. `compute(session: SessionResult, tier: DeviceTier? = null): KpiScoreReport?` returns null when flag OFF. |
| `FeatureFlags.kt` | `const val INTERNAL_FLAG_KEY = "gameperf.kpi.internal"` + `fun isKpiScoringInternalEnabled(): Boolean = System.getProperty(INTERNAL_FLAG_KEY) == "true"`. |

`Settings.kt`: add `val kpiScoringInternalEnabled: Boolean = false` (proposal §Affected Areas). Read at facade entry alongside system-property override.

## Data Flow

```
SessionResult ─┐
                ├──► KpiScoringFacade.compute(session, tier?)
DeviceTier? ────┤        │
                │        ├─ ExtractPhaseValues (uses session.events for phase windows + filteredAggregates)
KpiCatalog ─────┤        ├─ for each (Phase, Kpi): LinearScoring.score(value, threshold(tier))
PhaseWeights ───┤        ├─ PhaseAggregator.score(phase)
                │        ├─ CategoryAggregator.score(category)
                │        ├─ SessionAggregator.score()
                │        └─ ComparisonEngine.bands(...)
                └────────► KpiScoreReport { perKpi, perPhase, perCategory, session, bands, deltas }
                            (null if flag OFF)
```

## Architecture Decisions

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | **Parallel path**, do NOT replace `FinalScoreCalculator` v1 | Keeps `report.problems` / A-F grade / `HistoryEntry` schema byte-equivalent. Old `.gameperf` files still regenerate. Migration deferred to a future change after calibration (Block G). |
| D2 | **Linear (Model A) only** in v1 (§6.2) | Debuggable, explainable, anchored on Vitals thresholds — switching to Sigmoid (B) requires real-data curves. §6.2 doc decision #5 says "Evolve to Model B once ≥50 sessions are scored". |
| D3 | **Per-tier thresholds in catalog** as `Map<DeviceTier, Threshold>` per `Kpi` | Same KPI scores differently on TOP vs LOW (§6.3 table). Null tier → MID. Avoids parallel `LowTierCatalog` files. |
| D4 | **Missing data = exclude + renormalize** | If a session has no cinematics, its phase weight redistributes (proposal docs §6.5 open question). Aggregators receive only KPIs with non-null values; weight sum is recomputed from present entries. Property test guards. |
| D5 | **Feature flag via system property + Settings field** | `System.getProperty("gameperf.kpi.internal") == "true"` enables in any build (`-Dgameperf.kpi.internal=true`); `Settings.kpiScoringInternalEnabled` is the persistent default. `isKpiScoringInternalEnabled()` checks `sysprop OR settings`. No env-var (cross-OS pain), no Gradle property (not runtime-visible). Future config UI can write to `Settings`. |
| D6 | **Single-source enforcement** — every Kpi metadata lives in `KpiCatalog.ALL`, every weight in `PhaseWeights.kt` | CLAUDE.md v4.2.13 / v4.4.0 anti-duplication rule. KDoc at top of both files warns "DO NOT define KPI metadata or phase weights outside this file" (mirror `SdkSignatureCatalog` wording). Reviewer-checklist item in `branch-pr` skill addendum: "grep `KpiId\.` outside `core/kpi/` — must be ZERO references except in callers using `KpiCatalog.byId`". |
| D7 | **Test structure**: per-layer pure unit tests + golden synthetic-`SessionResult` round trip | TDD red-green per layer (mirror existing `core/conclusions/` test layout). Golden fixtures live in `src/test/resources/kpi-fixtures/` as JSON (kotlinx-serialization already available). |
| D8 | **`KpiInput` decouples facade from `SessionResult`** (deviation, documented during apply) | The viewmodel `SessionResult` is heavy and platform-coupled. Introducing a narrow `KpiInput` data class keeps `core/kpi/` pure and testable in isolation. Adapter `SessionResult → KpiInput` deferred to a follow-up change wiring the v2 UI exposure. |

## Interfaces / Contracts

```kotlin
enum class KpiId { FPS_AVG, FPS_P1, FPS_STABILITY, FRAME_TIME_P99, SLOW_FRAMES, FROZEN_FRAMES,
    CPU_AVG_NORMALIZED, CPU_MAX, GPU_AVG, RAM_AVG, RAM_MAX, TEMP_AVG, TEMP_MAX, THROTTLING_EVENTS,
    NETWORK_TOTAL, BATTERY_DRAIN, COLD_START_MS, WARM_START_MS, HOT_START_MS, TTID, TTFD,
    ANR_COUNT, CRASH_COUNT, SLOW_SESSION_RATE, FPOWER, JANK_COUNT, BIG_JANK_COUNT }

enum class Category { Smoothness, Resource, Thermal, Stability, Responsiveness }
enum class Phase { APP_STARTUP, CINEMATIC, TUTORIAL, LEVEL_LOADING, SCREEN_NAV,
    INTERSTITIAL_AD, REWARDED_AD, GAMEPLAY }
enum class DeviceTier { TOP, MID, LOW }
enum class Direction { HIGHER_IS_BETTER, LOWER_IS_BETTER }

data class Threshold(val target: Double, val floor: Double)
data class Kpi(val id: KpiId, val unit: String, val category: Category,
    val direction: Direction, val thresholds: Map<DeviceTier, Threshold>,
    val sourceCitation: String)

data class KpiScore(val id: KpiId, val phase: Phase, val rawValue: Double?,
    val score: Int, val delta: Double, val band: Band)
data class PhaseScore(val phase: Phase, val score: Int, val band: Band, val kpiScores: List<KpiScore>)
data class CategoryScore(val category: Category, val score: Int, val band: Band)
data class KpiScoreReport(val sessionScore: Int, val sessionBand: Band,
    val phases: List<PhaseScore>, val categories: List<CategoryScore>)

enum class Band { GREEN, AMBER, RED }

object KpiScoringFacade {
    fun compute(session: SessionResult, tier: DeviceTier? = null): KpiScoreReport?
}
```

## Testing Strategy

| Layer | Test Class | What |
|-------|-----------|------|
| Catalog invariants | `KpiCatalogTest` | ≥23 KPIs; every KPI has TOP/MID/LOW thresholds; citation non-empty; no duplicate `KpiId`; greppable single-source assertion |
| Threshold doc anchor | `KpiCatalogDocAnchorTest` | Reads `docs/competitive-analysis-and-kpis.md`, asserts cold-start ≥5s threshold, slow-frame ratio 50%, FPower 50/65 mW match catalog values |
| Tier resolution | `DeviceTierCatalogTest` | Galaxy S24 → TOP, Pixel 6a → MID, low-end model → LOW, unknown → null |
| Linear scoring | `LinearScoringTest` | 6+ boundary tests per proposal Success Criteria: value=target→100, value=floor→0, midpoint→50, beyond floor→0, beyond target→100, NaN→0; inverted direction |
| Phase aggregator | `PhaseAggregatorTest` | Synthetic phase with 3 KPIs; missing-KPI renormalization; weights sum to 1.0 invariant |
| Category aggregator | `CategoryAggregatorTest` | Cross-phase grouping; empty category → null score |
| Session aggregator | `SessionAggregatorTest` | Missing-phase renormalization; all-phases-present sanity |
| Comparison engine | `ComparisonEngineTest` | Bands at 80/60 boundaries; delta sign w/ direction |
| Facade integration | `KpiScoringFacadeTest` | 3 golden fixtures (good/mixed/bad `SessionResult`) → expected score; flag OFF → null; flag ON via sysprop → report |
| Property tests | `KpiScoringPropertiesTest` | weight maps sum to 1.0; all scores ∈ [0,100]; reports stable under shuffled input order |
| Feature flag | `FeatureFlagsTest` | sysprop OFF → false; sysprop ON → true; settings ON → true; both OFF → false |

TDD order: KpiMetadata → KpiCatalog → DeviceTierCatalog → PhaseWeights → LinearScoring → PhaseAggregator → CategoryAggregator → SessionAggregator → ComparisonEngine → KpiScoringFacade → properties.

## Migration / Rollout

Feature flag default OFF. Calc runs in tests only. No `Settings.kt` schema break (additive `val` with default). No DB / disk schema change. Rollback = revert `core/kpi/` + remove `kpiScoringInternalEnabled`. Internal v1 — no UI wiring this change.

## Risks

| Risk | Mitigation |
|------|------------|
| Threshold drift from `docs/competitive-analysis-and-kpis.md` | `KpiCatalogDocAnchorTest` reads doc + asserts key thresholds — fails CI if doc and catalog diverge |
| Phase weight tuning needs real data | `PhaseWeights.DEFAULT` + reserved `override(map): PhaseWeights` constructor for Block G calibration without touching callers |
| Catalog duplication recurrence (CLAUDE.md v4.2.13 lesson) | KDoc warning at top of `KpiCatalog.kt` + `PhaseWeights.kt`; reviewer checklist; greppable test asserting no `KpiId.` references outside `core/kpi/` (except `KpiCatalog.byId` call sites) |
| `FinalScoreCalculator` drift over time | `core/kpi/package-info.kt` documents the parallel-path split + migration plan (Block G); design doc cited |
| Feature flag accidentally exposed in UI | Default OFF in `Settings`; verify task in `sdd-verify` greps `KpiScoringFacade` references — must be zero outside `core/kpi/` + tests in v1 |
| `SessionResult.events` empty / `filteredAggregates == null` (no auto-detection) | Facade returns null phases for unmeasured phases; SessionAggregator renormalizes. Documented in KDoc. |

## Out of Scope

- HTML report rendering of KPI scores (`shareable-html-report` change)
- Calibration against real captures (Block G; depends on B.1/B.2 hands-on lab)
- UI surface for scores (deferred — internal v1 only)
- JSON / CSV export of `KpiScoreReport` (separate change)
- New KPI capture paths (FPower, GPU%, network bandwidth — separate changes; framework only scores what `SessionResult` already contains)
- Sigmoid / Bucket scoring models (Model B / C — v2+)
- ML-based curve tuning, multi-session trends, regression detection

## Open Questions

- [ ] D5: should the system-property fallback be removed once `Settings.kpiScoringInternalEnabled` UI lands? (Leave both, tag with `@Deprecated` later.)
- [ ] D3: do we want SoC-only fallback when `deviceModel` is missing? (v1: yes, via second arg to `DeviceTierCatalog.classify`.)
