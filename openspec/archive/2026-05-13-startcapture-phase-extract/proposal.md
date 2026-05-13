# Proposal: startCapture Phase Extract (H.7)

> Archived from engram observation #417 (`sdd/startcapture-phase-extract/proposal`).

## Intent

`AppViewModel.startCapture` is a monolithic ~1183-LOC method (lines 962-2145) with CCN 230 — six detekt threshold bumps in 7 weeks (200 → 215 → 216 → 226 → 230) chasing a growing capture loop. The `detekt.yml` `CyclomaticComplexMethod` comment explicitly logs **"TODO H.7 — URGENT: extract capture-phase sub-functions BEFORE next release. 6+ threshold bumps now — structural refactor overdue."** This is also a HARD BLOCKER for `network-bandwidth-total-app` (Sprint 2 / Issue #1 Gap 2): naive wire-up of one more metric adds ~5 CCN → 231 → detekt fail. Refactor must happen FIRST.

## Scope

### In Scope
- Extract `AppViewModel.startCapture` body into 4-6 private phase methods (private `suspend` where needed; visibility kept `private`).
- Drop `startCapture` CCN from 230 → ≤200 (pre-FPower baseline).
- Revert `detekt.yml` thresholds: `CyclomaticComplexMethod` 230 → 200; `thresholdInObjects` 54 → 48 (revert by 6); `thresholdInInterfaces` 33 → 30 (revert by 3).
- Update `detekt.yml` H.7 TODO comment to `DONE in startcapture-phase-extract`.
- Existing AppViewModel*Test files stay GREEN unchanged (~200+ tests are the safety net).

### Out of Scope
- Network bandwidth metric wiring (separate `network-bandwidth-total-app` change — depends on this).
- Any behavioural change to capture flow, per-tick scheduling, LiveMetrics emit contract, or SessionResult shape.
- New unit tests for the extracted phase methods (refactor is behavior-preserving; existing AppViewModel tests cover end-to-end).
- Splitting AppViewModel into multiple viewmodels (separate TODO at `thresholdInClasses=75`).
- HomeScreen LongMethod refactor (separate H.7 sibling TODO at `LongMethod=760`).

## Capabilities

### New Capabilities
None.

### Modified Capabilities
None — pure internal refactor. No public API or spec-level behavior changes. Existing capability `capture-session` (or equivalent) keeps its current contract.

## Approach

Extract along **temporal phases of the capture lifecycle**, not along metric type. Each phase is a private method on `AppViewModel`, accessing instance state directly. Local accumulators currently declared at top of `startCapture` (fpsHistory, memHistory, etc., lines 1119-1160+) MUST be hoisted into a single mutable `CaptureAccumulators` data class (private inner) so they survive the phase boundary without changing scope semantics.

Proposed phase methods (final names bikeshed in design):

1. `setupCaptureState(device, pkg)` — UI state reset (lines 967-975), battery/missed start reads, charging disable, screen-recording bootstrap (video dir + sessionId + iOS sidecar OR Android `startSegmentWithRetry`) (lines 978-1031).
2. `launchEventDetector(device, pkg, isIosDevice)` — v4.4.0 auto event detection wiring (lines 1043-1052).
3. `launchChainedRecording(device, sessionId, isIosDevice)` — Android chain-recording loop in its own `scope.launch` (lines 1075-1107).
4. `runCaptureLoop(device, pkg, isIosDevice, accumulators, startTime)` — the `while(!shouldStop)` main loop body, delegating per-tick work to inline `pollFastMetrics`, `pollMediumTickMetrics(iterCount)`, `pollSlowMetrics(iterCount)`, `recordTickHistory(iterCount)`, `emitLiveMetrics(iterCount)` (further sub-extracts only if needed to hit ≤200 CCN).
5. `finalizeSession(device, pkg, accumulators, startTime, batteryStart, missedStart)` — post-loop work: stop recording, gather totals, build `SessionResult`, render report, persist `.gameperf`, navigate to `RESULTS` screen.

`startCapture` itself becomes a thin orchestrator: prelude → `scope.launch { ... }` containing only sequential calls to the phase methods. Estimated post-refactor CCN of `startCapture` body: ~30-50 (mostly early-returns + try/catch). Estimated CCN of `runCaptureLoop`: ≤180 (the loop + per-tick branching, MUCH less than 230 after extracting setup/finalize/chain).

Behavior preservation strategy:
- Local-variable lifetimes preserved by hoisting all in-loop accumulators into a single `CaptureAccumulators` object passed by reference to each phase that mutates it.
- No new coroutines, no new Mutex, no new state flow.
- iOS/Android branching preserved verbatim — phase methods accept `isIosDevice` and replicate existing `if (isIosDevice)` switches.
- Order of operations on every code path stays byte-equivalent to current implementation.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `src/main/kotlin/com/gameperf/desktop/viewmodel/AppViewModel.kt` | Modified | `startCapture` decomposed into 5-6 private methods + introduce `CaptureAccumulators` private holder class. |
| `detekt.yml` | Modified | Revert 3 thresholds (`CyclomaticComplexMethod` 230→200, `thresholdInObjects` 54→48, `thresholdInInterfaces` 33→30); update H.7 TODO comment to `DONE`. |
| `src/test/kotlin/com/gameperf/desktop/viewmodel/AppViewModel*Test.kt` | Unchanged | 200+ existing tests are the behavioral safety net — they MUST stay green with zero edits. |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Hidden coupling between accumulator init and loop body breaks if hoisted into a holder class. | Med | Keep accumulators as `var` fields on `CaptureAccumulators`, mutate in place (1:1 substitution for current local `mutableListOf<>()`). Run `./gradlew check` after EACH extracted phase. |
| Phase extraction inadvertently changes per-tick scheduling (e.g. medium-tier moves to fast tier). | Med | Design phase MUST produce an exact line-to-line extraction map. Verify by diffing the `iterCount % 4 == 0` blocks pre/post refactor. |
| CCN of `runCaptureLoop` still exceeds 200 after first extraction round. | Low | Plan for two passes: pass 1 extract setup/chain/finalize; if CCN of loop > 200, pass 2 sub-extracts `pollMediumTickMetrics` and `recordTickHistory` further. |
| Refactor lands but `thresholdInObjects` revert is wrong number (AdbBridge function count may have shifted). | Low | Verify current AdbBridge fn count with `grep -c "fun " AdbBridge.kt` BEFORE setting the new threshold; cap at exactly `<current count> + 0`. |
| Behavior regression in some edge path that no existing test covers (e.g. iOS sidecar timeout during phase boundary). | Low | Manual smoke: 1 Android + 1 iOS capture end-to-end before merge. Refactor commits stay small (4-6 commits) so `git bisect` is trivial if regression appears post-merge. |
| H.7 was also flagged for `HomeScreen.HomeScreen` (LongMethod 760) — user may expect both in this change. | Low | OUT OF SCOPE here; separate sibling refactor. Update H.7 comment to reflect partial completion (startCapture done, HomeScreen still pending). |

## Rollback Plan

Per-commit rollback: each phase extraction is its own commit (see tasks breakdown). `git revert <sha>` restores prior state. Detekt threshold revert is the LAST commit — if any earlier extraction caused trouble, revert that commit and the detekt revert never lands. If the entire refactor is rejected post-merge, `git revert <merge-sha>` is safe because no spec-level contracts changed, no new tests, no public API delta. Downstream `network-bandwidth-total-app` would then need to fall back to Option B (separate coroutine, +2 CCN) — documented in `sdd/network-bandwidth/explore` #416.

## Dependencies

- Must land BEFORE `network-bandwidth-total-app` (this change unblocks it).
- No external library or sidecar version bumps required.
- No CI/CD workflow changes.

## Success Criteria

- [ ] `./gradlew check` passes with `CyclomaticComplexMethod: threshold: 200`.
- [ ] `./gradlew check` passes with `thresholdInObjects: 48` and `thresholdInInterfaces: 30`.
- [ ] `startCapture` body fits on roughly one screen (≤80 LOC) and reads as a sequential phase pipeline.
- [ ] All existing AppViewModel*Test files pass with zero source edits.
- [ ] `detekt.yml` H.7 TODO comment updated to `DONE in startcapture-phase-extract` (or removed if obsolete).
- [ ] Manual smoke: 1 Android + 1 iOS capture session run end-to-end, produces identical `SessionResult` shape and `.gameperf` artifact as pre-refactor (byte-equivalent metric values within sampling noise).
- [ ] `network-bandwidth-total-app` (next change) can wire its new metric by adding ONE call inside `pollMediumTickMetrics` + ONE field to `LiveMetrics` emit + accumulator append in `recordTickHistory` — NO new branches in `startCapture` body.
