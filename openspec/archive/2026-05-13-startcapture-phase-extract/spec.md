# Delta Spec: startcapture-phase-extract

> Archived from engram observation #418 (`sdd/startcapture-phase-extract/spec`).
>
> This change is a **pure internal refactor**. Per proposal §Capabilities: NO new capabilities, NO modified capabilities. No public API, no user-observable behavior, no spec-level contract changes.
>
> Below is a single internal **structural requirement** that gates the change and downstream work (`network-bandwidth-total-app`). It is testable via the existing detekt config + `./gradlew check`.

## Domain: internal-code-structure (new, internal-only)

## ADDED Requirements

### Requirement: AppViewModel.startCapture phase decomposition

The system MUST decompose `AppViewModel.startCapture` from a monolithic ~1183-LOC method into a thin orchestrator that delegates lifecycle phases to discrete private methods, such that detekt's `CyclomaticComplexMethod` threshold can be reverted from 230 back to its pre-FPower baseline of 200 without triggering build failures, AND such that the existing AppViewModel test suite continues to pass with zero source edits.

This requirement gates downstream metric work: future metrics MUST be wirable by adding a single call inside a phase method (no new branching in the `startCapture` body itself).

#### Scenario: detekt CyclomaticComplexMethod threshold reverted

- GIVEN `detekt.yml` `complexity.CyclomaticComplexMethod.threshold` set to `200` (was `230`)
- WHEN `./gradlew detekt` runs
- THEN the build SHALL pass with zero `CyclomaticComplexMethod` violations on `AppViewModel.startCapture` or any newly extracted phase method

#### Scenario: detekt TooManyFunctions object/interface thresholds reverted

- GIVEN `detekt.yml` `complexity.TooManyFunctions.thresholdInObjects` set to `48` (was `54`) AND `thresholdInInterfaces` set to `30` (was `33`)
- WHEN `./gradlew detekt` runs
- THEN the build SHALL pass with zero `TooManyFunctions` violations

#### Scenario: existing AppViewModel test suite remains green

- GIVEN all `src/test/kotlin/com/gameperf/desktop/viewmodel/AppViewModel*Test.kt` files unchanged from pre-refactor state
- WHEN `./gradlew test` runs
- THEN every test in the AppViewModel suite SHALL pass (the existing ~200+ tests are the behavioural safety net)

#### Scenario: full check passes end-to-end

- GIVEN the refactor merged AND `detekt.yml` thresholds reverted (CCN 200, thresholdInObjects 48, thresholdInInterfaces 30)
- WHEN `./gradlew check` runs (lint + tests + detekt combined)
- THEN the command SHALL exit with status 0

#### Scenario: H.7 TODO comment updated

- GIVEN the refactor merged
- WHEN inspecting `detekt.yml` `CyclomaticComplexMethod` section
- THEN the comment SHALL reference the change name (`startcapture-phase-extract`) as DONE, replacing the prior `URGENT: extract capture-phase sub-functions BEFORE next release` TODO

#### Scenario: future metric wires through a phase method

- GIVEN a new per-tick metric (e.g. network bandwidth) added after this refactor lands
- WHEN integrating the new metric into the capture pipeline
- THEN the integration SHALL be possible by adding ONE call inside an existing phase method (e.g. medium-tier poll) plus accumulator/emit edits — without introducing a new top-level branch in the `startCapture` body
- AND `startCapture` CCN SHALL stay at or below 200 after the metric lands (verified by `./gradlew detekt`)

#### Scenario: per-tick scheduling preserved

- GIVEN the refactor merged
- WHEN a capture session runs against a real or `FakeAdbBridge` device
- THEN the per-tick scheduling SHALL be byte-equivalent to pre-refactor: fast metrics every tick, medium metrics every 4 ticks, slow metrics every 10 ticks
- AND `LiveMetrics` emission cadence and field set SHALL be unchanged
- AND `SessionResult` shape SHALL be unchanged

#### Scenario: no new concurrency primitives introduced

- GIVEN the refactor merged
- WHEN inspecting the diff against `AppViewModel.kt`
- THEN no new `scope.launch`, `Mutex`, `withContext`, or `Channel` SHALL appear that were not present pre-refactor
- AND the existing `recordJob` + `timerJob` + `captureJob` + event-detector launches SHALL be preserved verbatim

## MODIFIED Requirements

None.

## REMOVED Requirements

None.

## Coverage Summary

| Aspect | Covered |
|--------|---------|
| Happy path (full check passes) | YES (scenario "full check passes end-to-end") |
| Detekt-specific gates | YES (3 scenarios, one per threshold) |
| Test safety net | YES ("AppViewModel test suite remains green") |
| Downstream unblock contract | YES ("future metric wires through a phase method") |
| Behavior preservation | YES ("per-tick scheduling preserved", "no new concurrency primitives") |
| Documentation hygiene | YES ("H.7 TODO comment updated") |

## Notes

- No new unit tests are required by this spec — the existing AppViewModel test suite (~200+ tests) is the explicit behavioural verifier per the proposal's "Out of Scope".
- The "future metric wires through a phase method" scenario will be verified indirectly when `network-bandwidth-total-app` lands — if that change can wire its new metric without bumping the CCN threshold again, this scenario is satisfied.
