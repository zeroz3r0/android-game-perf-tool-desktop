# Spec: kpi-session-adapter (NEW capability)

## Purpose

Pure mapping from `viewmodel.SessionResult` (captured session payload) into `core.kpi.KpiInput` (lightweight scoring input). Owns the `EventType → Phase` translation and the per-KPI source-data rules. No I/O, no time, deterministic.

## ADDED Requirements

### Requirement: Pure Mapping Function

The system MUST expose a single top-level pure function `toKpiInput(session: SessionResult): KpiInput` returning a `KpiInput` whose `deviceModel` equals `session.deviceModel` and whose `rawByPhase` is computed from `session.events` plus the metric histories/aggregates on `SessionResult`. The function MUST be deterministic and free of I/O, threading, randomness, or wall-clock reads.

#### Scenario: Determinism

- GIVEN any `SessionResult`
- WHEN `toKpiInput(session)` invoked twice
- THEN both results are structurally equal (`==`).

#### Scenario: deviceModel is forwarded verbatim

- GIVEN `session.deviceModel == "SM-S911B"`
- WHEN `toKpiInput(session)` invoked
- THEN result.deviceModel == "SM-S911B" (no normalization in adapter — `DeviceTierCatalog` owns that)

### Requirement: EventType to Phase Mapping

The system MUST translate each `DetectedEvent` to a `Phase` using this exact table:

| EventType | Phase |
|-----------|-------|
| `APP_STARTUP` | `APP_STARTUP` |
| `LOADING` | `LEVEL_LOADING` |
| `SCREEN_TRANSITION` | `SCREEN_NAV` |
| `INTERSTITIAL` | `INTERSTITIAL_AD` |
| `REWARDED_VIDEO` | `REWARDED_AD` |
| `IAP, FOREGROUND_LOSS, SDK_INIT, ANR, INSTRUMENTED, VR_SESSION, VR_RETURN_TRANSITION, RATE_US, UNKNOWN` | (not mapped — excluded from phase aggregation; their time window is still subtracted from GAMEPLAY) |

Events with `endMs == null` MUST clamp to `session.duration * 1000`.

#### Scenario: Single interstitial creates INTERSTITIAL_AD phase

- GIVEN `session.events == [DetectedEvent(type=INTERSTITIAL, startMs=10000, endMs=20000, ...)]`
- WHEN `toKpiInput(session)` invoked
- THEN `result.rawByPhase[Phase.INTERSTITIAL_AD]` is non-null and non-empty

#### Scenario: IAP event does not produce a phase

- GIVEN `session.events == [DetectedEvent(type=IAP, startMs=5000, endMs=8000, ...)]`
- WHEN `toKpiInput(session)` invoked
- THEN `result.rawByPhase` contains NO key for any phase derived from IAP (IAP has no Phase mapping)
- AND the IAP time window is still excluded from the GAMEPLAY phase

### Requirement: GAMEPLAY Phase Coverage

The system MUST emit `Phase.GAMEPLAY` containing KPI values computed over the session time NOT covered by mapped event windows AND NOT covered by unmappable event windows (IAP, ANR, etc.). When `session.events` is empty, GAMEPLAY MUST cover the full session.

#### Scenario: Empty event list yields gameplay-only mapping

- GIVEN `session.events == emptyList()` AND `session.avgFps == 58`
- WHEN `toKpiInput(session)` invoked
- THEN `result.rawByPhase.keys == setOf(Phase.GAMEPLAY)`
- AND `result.rawByPhase[Phase.GAMEPLAY]!![KpiId.FPS_AVG] == 58.0`

#### Scenario: Multiple ads carve out gameplay correctly

- GIVEN `session.events == [Interstitial(10..20s), Interstitial(40..50s)]` AND `session.duration == 60`
- WHEN `toKpiInput(session)` invoked
- THEN GAMEPLAY phase is populated AND its KPI values are computed over the union of `[0,10) ∪ [20,40) ∪ [50,60)` seconds
- AND INTERSTITIAL_AD phase is populated (events merged within the same phase)

### Requirement: Missing Thermal Data Excluded

When `session` lacks thermal data (`session.maxTempCpu == 0.0` AND `session.tempCpuHistory` empty — meaning thermal capture was unavailable), the system MUST omit thermal KPIs (`TEMP_AVG`, `TEMP_MAX`, `THROTTLING_EVENTS`) from EVERY phase map so the scoring aggregator renormalizes.

#### Scenario: thermalAvailable=false yields no thermal KPIs

- GIVEN `session.maxTempCpu == 0.0` AND `session.tempCpuHistory == emptyList()`
- WHEN `toKpiInput(session)` invoked
- THEN no inner map contains `KpiId.TEMP_AVG` or `KpiId.TEMP_MAX`.

### Requirement: Missing FPower Data Excluded

When `session.fpowerAvailable == false` OR `session.fpowerHistory` is empty, the system MUST omit `KpiId.FPOWER` from every phase map.

#### Scenario: fpowerAvailable=false yields no FPOWER KPI

- GIVEN `session.fpowerAvailable == false`
- WHEN `toKpiInput(session)` invoked
- THEN no inner map contains `KpiId.FPOWER`.

### Requirement: Immutability of Input

The system MUST NOT mutate `session` or any of its lists/maps.

#### Scenario: Adapter does not mutate input

- GIVEN a `SessionResult` whose `events` and history lists are observed before the call
- WHEN `toKpiInput(session)` invoked
- THEN every observed reference is identity-equal and content-equal after the call.

## Notes

- All scenarios are pure unit tests in `src/test/kotlin/.../core/kpi/adapter/SessionResultToKpiInputTest.kt`.
- Per-tick slicing uses `fpsTimed: List<TimedSample(second, value)>` to recompute FPS averages over phase windows; session-wide aggregates fall back to `SessionResult` scalar fields for KPIs lacking a tick series (e.g. `peakMemMb`).
- `RAM_AVG` MUST use `session.memHistory` average when non-empty; falls back to `peakMemMb` only when history is empty AND value is non-zero.
- The adapter does NOT normalize `deviceModel` (underscore→hyphen lives in `DeviceTierCatalog.resolve` per project pattern).
