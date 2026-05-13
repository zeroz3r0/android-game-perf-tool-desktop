# Proposal: KPI Session Adapter (Issue #2 — follow-up Block E.1)

## Intent

`KpiScoringFacade.compute()` consumes a lightweight `KpiInput` (deviceModel + `Map<Phase, Map<KpiId, Double>>`). Production captures produce a heavy `SessionResult` (FPS histories, CPU histories, thermal/FPower, detected events, etc.). No mapping exists today, so KPI scoring only runs against synthetic fixtures in tests. Build a pure adapter `SessionResultToKpiInput` that translates a real captured session into the `KpiInput` shape, unblocking wiring KPI scoring to real sessions without bleeding viewmodel types into `core/kpi/`.

## Scope

### In Scope
- New file `core/kpi/adapter/SessionResultToKpiInput.kt` — pure top-level function `toKpiInput(session: SessionResult): KpiInput`.
- Segment metric histories by phase using `SessionResult.events` (`DetectedEvent.startMs/endMs`), mapping `EventType → Phase` per the table in design.
- Compute per-phase per-KPI scalars from the per-tick histories (`fpsHistory`, `cpuHistory`, `memHistory`, `tempCpuHistory`, `fpowerHistory`, plus pre-computed aggregates).
- Skip KPIs whose source data is missing (no entry in inner map) so aggregators renormalize per kpi-scoring D4.
- Handle 0-event sessions → only `GAMEPLAY` phase populated.
- Handle missing thermal (`thermalAvailable=false` in `ThermalSnapshot` summary fields → no thermal-derived KPIs).
- Handle missing FPower (`SessionResult.fpowerAvailable=false`) → no FPOWER KPI.
- KDoc on the function documenting the EventType→Phase mapping table and KPI source rules.
- Tests (TDD red-green): happy path, missing thermal, missing FPower, determinism, empty events, multiple ads.

### Out of Scope
- UI exposure of `KpiScoreReport` (separate change, Block F).
- ViewModel integration (caller wires `toKpiInput → KpiScoringFacade.compute` separately).
- Calibration of thresholds (Block G).
- New phase types beyond what `KpiCatalog`/`Phase` already declares.
- CINEMATIC and TUTORIAL phases (no auto-detection signals in v1 — empty in adapter output).
- Per-tick re-segmentation of CPU/RAM (those use the session-level aggregates already filtered by `FilteredMetricsCalculator`).

## Capabilities

### New Capabilities
- `kpi-session-adapter`: pure mapping from `viewmodel.SessionResult` to `core.kpi.KpiInput`. Owns the EventType→Phase table and the per-KPI source rules.

### Modified Capabilities
- None. `kpi-scoring` capability is unchanged (input shape already declared via `KpiInput`).

## Approach

Single pure top-level function `toKpiInput(session)` in a new package `core/kpi/adapter/`:

1. Resolve `deviceModel` from `SessionResult.deviceModel`.
2. Build event windows: `EventType` → list of `(startMs, endMs)` intervals. Open events (`endMs=null`) clamp to `session.duration*1000`.
3. Convert each event window to a `Phase` via a private mapping table (`INTERSTITIAL→INTERSTITIAL_AD`, `REWARDED_VIDEO→REWARDED_AD`, `LOADING→LEVEL_LOADING`, `SCREEN_TRANSITION→SCREEN_NAV`, `APP_STARTUP→APP_STARTUP`; others ignored).
4. For each phase window, compute KPI scalars from the second-indexed histories slicing `fpsTimed`/`fpowerTimed` between `startMs/1000..endMs/1000`. For session-wide KPIs (avg CPU/RAM/temp), use the pre-computed `SessionResult` scalars (`avgFps`, `avgCpu`, `maxCpu`, `peakMemMb`, `maxTempCpu`, `fpowerAvg`, `fpowerPeak`, `totalJank`).
5. `GAMEPLAY` phase = inverse of all event windows (non-event second buckets); if zero events, `GAMEPLAY` covers the whole session.
6. Drop empty inner maps so aggregators renormalize correctly.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `core/kpi/adapter/SessionResultToKpiInput.kt` | New | Pure mapping function + KDoc table |
| `src/test/kotlin/.../core/kpi/adapter/SessionResultToKpiInputTest.kt` | New | TDD tests for the 6 scenarios |
| `viewmodel/AppViewModel.kt` | None this change | Wiring deferred (out of scope) |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Mapping diverges from `KpiCatalog`/`Phase` evolution | Med | Adapter imports `KpiId` + `Phase` directly; new phases trigger compile-time TODO at mapping table |
| Time-window slicing edge cases (overlap, zero-length, end=null) | Med | Property test: `mapWindows([])` → only GAMEPLAY; ordered intervals collapse correctly |
| Mojibake / locale-sensitive parsing | Low | Adapter is pure data shuffling — no I/O or charset reads |
| Bleeding `SessionResult` types into `core/kpi/` | Low | Adapter lives in `core/kpi/adapter/` subpkg; only consumer side imports `viewmodel.SessionResult` |

## Rollback Plan

Delete `core/kpi/adapter/` package + its tests. `KpiScoringFacade` keeps working with synthetic `KpiInput` fixtures. No call sites are added in this change → no consumers break.

## Dependencies

- `kpi-scoring-framework` change (already implemented — `KpiInput`, `Phase`, `KpiId`, `KpiCatalog` exist).
- No new external libs.

## Success Criteria

- [ ] `./gradlew check` green (detekt + tests).
- [ ] `SessionResultToKpiInputTest` covers: happy path, missing thermal, missing FPower, determinism, empty events, 2-ad segmentation.
- [ ] `toKpiInput` is pure (no I/O, no `System.currentTimeMillis()`, no random).
- [ ] No reference to `SessionResult` outside `viewmodel/` and `core/kpi/adapter/`.
- [ ] KDoc on `toKpiInput` lists the full EventType→Phase mapping table.
