# Tasks: KPI Session Adapter

TDD order: every Phase 2 task starts RED (write the failing test in its sibling test class), then turns GREEN (minimal impl in `SessionResultToKpiInput.kt`), then REFACTOR (cleanup before next task). Each task budget < 15min.

## Phase 1: Foundation

- [x] 1.1 Create directory `src/main/kotlin/com/gameperf/desktop/core/kpi/adapter/` and the test sibling `src/test/kotlin/com/gameperf/desktop/core/kpi/adapter/`.
- [x] 1.2 Create empty `SessionResultToKpiInput.kt` with `package com.gameperf.desktop.core.kpi.adapter` and a stubbed `fun toKpiInput(session: SessionResult): KpiInput = TODO()`. Run `./gradlew compileKotlin` to confirm it compiles.

## Phase 2: TDD Red-Green (one scenario at a time — order matters)

- [x] 2.1 **Determinism + deviceModel forwarding** — RED test referenced TODO stub (failure confirmed). GREEN: minimal `KpiInput(session.deviceModel, emptyMap())`.
- [x] 2.2 **Empty events → GAMEPLAY only** — RED: tested FPS_AVG missing under empty impl. GREEN: introduced `kpisForPhase`, `PhaseWindow`, sole `GAMEPLAY` window covering `[0,duration)`.
- [x] 2.3 **Single interstitial → INTERSTITIAL_AD phase** — RED. GREEN: added `eventTypeToPhase`, `buildEventWindows`, `computeGameplayWindows` (BooleanArray inversion).
- [x] 2.4 **Two interstitials carve gameplay** — Test passed without changes: `groupBy { phase }` already unions windows.
- [x] 2.5 **IAP carves gameplay but no phase** — Already handled: unmapped types collected separately and fed to gameplay inversion.
- [x] 2.6 **Missing thermal → no TEMP_*** — Already handled: `maxTempCpu > 0.0` guard.
- [x] 2.7 **Missing FPower → no FPOWER** — Already handled: `fpowerAvailable && fpowerHistory.isNotEmpty()` guard.
- [x] 2.8 **Input not mutated** — Verified: adapter only reads. Reference identity + size assertions all pass.

## Phase 3: Polish & verify

- [x] 3.1 KDoc + file header `// SINGLE SOURCE OF TRUTH for SessionResult → KpiInput mapping.` in place.
- [x] 3.2 `./gradlew detekt` GREEN.
- [x] 3.3 `./gradlew check` GREEN (with WIP `core/report/kpi/*Test.kt` files temporarily disabled — see apply-progress for note).
- [x] 3.4 `import com.gameperf.desktop.viewmodel.SessionResult` only in `core/kpi/adapter/SessionResultToKpiInput.kt` (verified via grep).
