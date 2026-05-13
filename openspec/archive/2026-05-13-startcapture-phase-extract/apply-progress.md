# Apply Progress: startcapture-phase-extract (FINAL)

> Archived from engram observation #421 (`sdd/startcapture-phase-extract/apply-progress`).

**Mode**: Standard (behavior-preserving refactor).
**Status**: ALL PHASES DONE. Branch `chore/startcapture-phase-extract` ready for verify/PR.

## Completed (cumulative)
- [x] 1.1-1.5 Phase 1 — `CaptureAccumulators` holder hoisted (commit 5a12d9b)
- [x] 2.1 Extract `bootstrapScreenRecording` — commit 9ea4420
- [x] 2.2 Extract `launchEventDetector` — commit 513251e
- [x] 2.3 Extract `launchChainedRecording` — commit 8548672
- [x] 2.4 Extract `launchUiTimer` — commit efc350f
- [x] 3.1 Extract `runCaptureLoop` — commit 489e7a5 — THE BIG ONE
- [x] 3.2 Measure CCN — DONE. Lowered threshold to 200, ran `./gradlew detekt`, BUILD SUCCESSFUL. Both `startCapture` AND `runCaptureLoop` standalone CCN ≤ 200. No violation surfaced.
- [x] 3.3 SKIPPED — branch not taken (no sub-extracts needed, 3.2 passed first-try).
- [x] 4.x SKIPPED — Phase 4 (`finalizeSession` + `setupCaptureState`) was a contingency for tightening CCN further, but design success gate is "CCN ≤ 200" which Phase 3.1 achieved. Per orchestrator scope of this run, Phase 4 was conditional on Phase 3.2 showing runCaptureLoop > 200; that condition did not fire.
- [x] 5.1 AdbBridge fn count check — `grep "fun "` returned 53 (above 48). Documented.
- [x] 5.2 detekt.yml edits — CCN 230 → 200 (committed). thresholdInObjects 54 → tried 48, FAILED ("Object 'AdbBridge' with '53' functions detected. Defined threshold inside objects is set to '48'"), reverted to 54 + updated comment. thresholdInInterfaces 33 → tried 30, FAILED ("Interface 'AdbBridgeApi' with '32' functions detected"), reverted to 33 + updated comment.
- [x] 5.3 `./gradlew check --rerun-tasks` BUILD SUCCESSFUL in 2m 35s (all 9 tasks executed, detekt + full test suite).
- [x] 5.4 Commit `chore(detekt): revert thresholds post H.7 refactor` — 008a006.
- [ ] 6.1 Manual smoke test #1 (Android end-to-end capture) — DEFERRED to sdd-verify phase (out of apply scope per task list).
- [ ] 6.2 Manual smoke test #2 (iOS) — DEFERRED to sdd-verify.
- [x] 6.3 Run full `./gradlew check` one final time — DONE (BUILD SUCCESSFUL).
- [ ] 6.4 PR description — DEFERRED to sdd-archive / branch-pr skill.

## CCN Measurement Results (Phase 3.2)
- threshold lowered to 200 in detekt.yml
- `./gradlew detekt` → BUILD SUCCESSFUL
- Neither `startCapture` nor `runCaptureLoop` flagged at threshold 200
- Conclusion: both methods CCN ≤ 200. Design target HIT first-try (Phase 3.1 extraction alone was enough; per-tick sub-extracts insurance was unneeded).

## Final Branch State
7 commits since main, all clean refactor commits:
```
008a006 chore(detekt): revert thresholds post H.7 refactor
489e7a5 refactor(viewmodel): extract runCaptureLoop (H.7 Phase 3.1)
efc350f refactor(viewmodel): extract launchUiTimer (H.7 Phase 2.4)
8548672 refactor(viewmodel): extract launchChainedRecording (H.7 Phase 2.3)
513251e refactor(viewmodel): extract launchEventDetector (H.7 Phase 2.2)
9ea4420 refactor(viewmodel): extract bootstrapScreenRecording (H.7 Phase 2.1)
5a12d9b refactor(viewmodel): hoist startCapture accumulators to CaptureAccumulators holder (H.7 Phase 1)
```

## Files Changed (cumulative)
| File | Action | What Was Done |
|------|--------|---------------|
| `src/main/kotlin/com/gameperf/desktop/viewmodel/AppViewModel.kt` | Modified | 6 phase methods extracted (`bootstrapScreenRecording`, `launchEventDetector`, `launchChainedRecording`, `launchUiTimer`, `runCaptureLoop`) + `CaptureAccumulators` holder class added. `startCapture` body reduced from 1183 LOC to orchestrator-thin. |
| `detekt.yml` | Modified | (a) thresholdInClasses 75 → 80 (Phase 3.1, post-refactor steady state). (b) CyclomaticComplexMethod 230 → 200 (Phase 5, reverted). (c) thresholdInObjects/Interfaces tried to revert but blocked by AdbBridge size — comments updated documenting the blocker. |

## Behavior Preservation Evidence
- `./gradlew check --rerun-tasks` BUILD SUCCESSFUL in 2m 35s (full test suite, detekt clean)
- All 200+ AppViewModel*Test stayed green throughout all 7 commits
- Phase 3.1 diff symmetry: 130 `+acc.*` vs 130 `-acc.*` (no leaked references)
- Behavior-preserving refactor: no public API changes, no data shape changes

## CCN Trajectory (this change)
- Pre-refactor: startCapture CCN 226, threshold cap 230
- Post Phase 3.1: startCapture ≤ 200, runCaptureLoop ≤ 200, threshold cap revertable to 200
- Net: -26+ CCN points dropped from startCapture alone via extraction

## Next Recommended
**sdd-verify** — run the verification phase against spec acceptance criteria. Then sdd-archive to sync delta specs + close the change. PR creation (branch-pr skill) after archive.

## Open Items for Verify Phase
- Manual smoke tests on real Android + iOS device (out of automated scope)
- PR description that links to H.7 origin + `network-bandwidth-total-app` (engram #416) as the unblocked downstream change

## Risks (final)
- **CLOSED**: detekt `TooManyFunctions` threshold for AppViewModel — bumped to 80 as documented steady state.
- **CLOSED**: runCaptureLoop CCN unknown — measured ≤ 200 in Phase 3.2.
- **OPEN (out of scope)**: AdbBridge `thresholdInObjects` and AdbBridgeApi `thresholdInInterfaces` could not revert to the pre-FPower baseline (48/30) because AdbBridge accumulated 5+ fn additions over the FPower→GPU sprint cycle. A future change to split AdbBridge into domain-specific objects would free those thresholds; documented as TODO in the detekt.yml comments. Not a blocker for this change — current thresholds (54/33) hold and the CCN revert is the primary win.
- **OPEN (low)**: manual smoke tests deferred to verify phase. Automated test coverage (200+ AppViewModel tests against FakeAdbBridge) is the safety net.
