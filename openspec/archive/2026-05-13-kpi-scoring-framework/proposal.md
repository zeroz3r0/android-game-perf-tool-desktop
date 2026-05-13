# Proposal: KPI Scoring Framework (Issue #2 Block E)

## Intent

Today `FinalScoreCalculator` produces ONE overall A-F grade from ~6 ad-hoc penalties. It cannot answer "is loading bad?" or "how does Smoothness compare across builds?". Issue #2 Block E delivers a market-anchored, multi-layer scoring framework: per-KPI (0-100), per-category (Smoothness/Resource/Thermal/Stability/Responsiveness), per-phase, and overall session score, all derived via pure functions from a single KPI catalog anchored on Android Vitals + RAIL + Apple launch budget thresholds (see `docs/competitive-analysis-and-kpis.md`). v1 is internal-only (UI gated behind feature flag).

## Scope

### In Scope
- `core/kpi/KpiCatalog.kt` — 23+ KPIs as single source of truth (mirrors `SdkSignatureCatalog.ALL` pattern); each KPI carries id, category, unit, source, per-tier thresholds (target/floor).
- `core/kpi/DeviceTierCatalog.kt` — TOP/MID/LOW classifier (mirror `ThermalZoneClassifier`); default tier devices per §6.3.
- `core/kpi/PhaseWeights.kt` — phase × KPI weight table sourced from §5.2, with override hook reserved for future product tuning.
- `core/kpi/LinearScoring.kt` — pure `scoreLinear(value, target, floor): Int` (Model A only).
- Pure aggregators: KPI-score-from-session, PhaseScoreAggregator, CategoryScoreAggregator, SessionScoreAggregator.
- `core/kpi/ComparisonEngine.kt` — delta vs target + color band classification (green/amber/red) per KPI, phase, category, session.
- Synthetic `SessionResult` fixtures + expected-score golden tests for each scoring layer (strict TDD).
- Feature flag `kpi.scoring.internal` (default OFF) — calc runs but is NOT exposed in UI/HTML report.

### Out of Scope
- F — HTML report rendering of new scores (separate change `shareable-html-report`).
- G — Calibration against real captured sessions (depends on B.1/B.2 lab data).
- Model B (Sigmoid) and Model C (Buckets) — v2+.
- UI exposure of scores — follow-up change.
- New KPI capture paths (FPower, GPU%, network bandwidth) — separate changes; framework only scores what the session already contains.

## Capabilities

### New Capabilities
- `kpi-scoring`: KPI catalog, device tier catalog, linear scoring model, phase/category/session aggregators, comparison engine, feature-flag gate.

### Modified Capabilities
- None. `FinalScoreCalculator` remains unchanged in v1 (parallel path); migration is a future change.

## Approach

Pure functional core, no I/O. Single source of truth for KPI metadata + thresholds + weights (`KpiCatalog`, `DeviceTierCatalog`, `PhaseWeights`). Input is the existing `SessionResult` (and detected events for phase boundaries) plus a `DeviceTier`. Pipeline: extract per-phase KPI values → `scoreLinear` per KPI → weighted sum into phase → weighted sum into category and session → comparison engine emits deltas + color bands. Strict TDD red→green per layer with deterministic synthetic fixtures. Feature flag `kpi.scoring.internal` gates UI exposure; calc tested standalone.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `core/kpi/` | New package | KpiCatalog, DeviceTierCatalog, PhaseWeights, LinearScoring, aggregators, ComparisonEngine |
| `core/grading/FinalScoreCalculator.kt` | Untouched | Parallel path; deprecation in future change |
| `core/Settings.kt` | Modified | Add `kpiScoringInternalEnabled` boolean (default false) |
| `src/test/kotlin/.../core/kpi/` | New | Per-layer unit tests + golden fixtures |
| `src/test/resources/kpi-fixtures/` | New | Synthetic SessionResult JSON fixtures |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Thresholds in catalog drift from §3.1 / §3.6 sources | Med | Inline KDoc citation per KPI; one test asserts catalog cites authoritative URL anchor |
| Aggregator math diverges between phase/category/session | Med | Aggregators share `weightedAverage` helper; property test on weight-sum = 1.0 |
| Feature flag accidentally exposed in UI | Low | Default OFF in Settings; no UI wiring in this change (verified in sdd-verify) |
| Phase weights become wrong after first 10 real sessions | Med | Weights live in PhaseWeights as `val DEFAULT` + reserved override hook; tune in follow-up |
| Catalog duplicated outside `core/kpi/` (recurrent v4.2.13 pattern) | High | Detekt custom rule unfeasible; covered by code review + design doc anti-pattern note |

## Rollback Plan

Feature flag default OFF — calc runs but is invisible. Full rollback = revert the `core/kpi/` package + `Settings.kt` field. `FinalScoreCalculator` untouched, so revert does not affect existing A-F grading path. No DB, no on-disk schema change (synthetic fixtures live under `src/test/resources/`).

## Dependencies

- `docs/competitive-analysis-and-kpis.md` (frozen as v1 source of truth).
- Existing `core/model/SessionResult` and `core/events/DetectedEvent` types (no schema changes required for v1).

## Success Criteria

- [x] `KpiCatalog.ALL` contains ≥23 KPIs, each with TOP/MID/LOW thresholds + category + source citation.
- [x] `scoreLinear(value, target, floor)` is pure, deterministic, covered by ≥6 boundary tests (value=target → 100, value=floor → 0, mid → 50, beyond floor → 0, before target → 100, NaN → 0).
- [x] Phase/category/session aggregators produce expected scores on ≥3 synthetic fixtures (good/mixed/bad session).
- [x] `ComparisonEngine.compare(session, targets)` returns delta + color band per layer.
- [x] All new code passes `./gradlew check` (detekt + tests).
- [x] Zero KPI metadata defined outside `KpiCatalog`; zero phase weight defined outside `PhaseWeights` (greppable).
- [x] Feature flag `kpi.scoring.internal` defaults to false; no UI references it in this change.
