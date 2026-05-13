# Apply Progress: VR Event Detection — Phase 3+4+5 complete (17/17 tasks DONE)

**What**: Completed Phases 3+4+5 of vr-event-detection (Sprint 4 / Issue #2 / D.6). Total 17/17 tasks across all phases. Dedup hook lives in `EventDetectorImpl.tryOpen` (VR-004), synthesis helper `emitVrReturnTransition` lives in `EventDetectorImpl` and is invoked from `tryClose` AND `stop()` force-close loop (VR-005). Two threadtime fixtures (oculus + openxr, ~50 lines each) prove end-to-end the dedup window collapses 2 opens to 1, and the synthesis produces the trailing VR_RETURN_TRANSITION (VR-008). Full `./gradlew check` GREEN, detekt 0 findings.

**Why**: Spec VR-004, VR-005, VR-008 — Sprint 4 closing the gap where `EventType.VR_SESSION` and `EventType.VR_RETURN_TRANSITION` were wired in the enum since Sprint 4a but never produced at runtime. With Phase 1+2 catalog wiring already in place (obs #406), this batch added the detector behaviour + fixture verification.

**Where**:
- `src/main/kotlin/com/gameperf/desktop/core/events/EventDetectorImpl.kt` —
  - new `VR_RETURN_TRANSITION_WINDOW_MS = 2_000L` constant in companion
  - dedup hook in `tryOpen` (consumes `sig.dedupWindowMs`)
  - `emitVrReturnTransition` helper guarded by type + MAX_EVENTS
  - `tryClose` calls helper post-close
  - `stop()` force-close loop iterates and calls helper for VR_SESSION entries
- `src/test/kotlin/com/gameperf/desktop/core/events/VrSignaturesTest.kt` — added 7 tests (2 dedup boundary + 2 synthesis paths + 2 fixture smoke) plus helper `newVrDetectorAtTime` + `readFixtureLines`. Total tests in file: 14 (Phase 2) + 7 (Phases 3+4) + 1 (KDoc HINT) = 22.
- `src/test/resources/logcat-fixtures/vr-oculus-session.log` — NEW, 49 lines threadtime, VrApi+OVRPlugin Quest-flavoured cycle, HMDMounted at +700ms (must dedup).
- `src/test/resources/logcat-fixtures/vr-openxr-session.log` — NEW, 50 lines threadtime, OpenXR+xrInstance Pico-flavoured cycle, XR_SESSION_STATE_READY at +700ms (must dedup).

**TDD Cycle Evidence** (Strict TDD Mode):

| Task | Test File | Layer | Safety Net | RED | GREEN | TRIANGULATE | REFACTOR |
|------|-----------|-------|------------|-----|-------|-------------|----------|
| 3.1.R | VrSignaturesTest.kt | Unit | EventDetectorImplTest baseline GREEN | "VrApi + OpenXR within 5s → ONE event" Written | Pass after 3.2 | pair: 5s in + 10s out | — |
| 3.2.G | EventDetectorImpl.kt | Unit | via 3.1 | Test failed (2 events instead of 1) | Passed after dedup hook | boundary test (10s) also covers outside-window | helper kept inline (one-shot) |
| 3.3.R | VrSignaturesTest.kt | Unit | — | both close-path + stop-path tests Written | Pass after 3.4 | 2 paths (pattern close + stop()) | — |
| 3.4.G | EventDetectorImpl.kt | Unit | via 3.3 | Test failed (1 event instead of 2) | Passed | both paths covered | Extracted VR_RETURN_TRANSITION_WINDOW_MS const |
| 4.1 | vr-oculus-session.log | Fixture | N/A (data file) | N/A | Replay test reads OK | structural | — |
| 4.2 | vr-openxr-session.log | Fixture | N/A (data file) | N/A | Replay test reads OK | structural | — |
| 4.3 | VrSignaturesTest.kt | Unit (fixture-replay) | via 3.4 | tests Written (initially RED — fixtures missing) | Passed after files created | 2 distinct flavours (Oculus + OpenXR) | — |
| 5.1 | (all tests + detekt) | Full check | Phase 4 GREEN | N/A | `./gradlew check` BUILD SUCCESSFUL | N/A | Zero detekt findings |
| 5.2 | engram | Persistence | N/A | N/A | apply-progress saved | N/A | N/A |

**Test Summary (cumulative)**:
- VrSignaturesTest: 22 tests, all GREEN
- SdkSignatureCatalogTest: 50 tests (unchanged from Phase 1+2)
- Full `./gradlew check`: BUILD SUCCESSFUL
- detekt: 0 findings on the modified file (EventDetectorImpl.kt)

**Learned**:
- The dedup hook must run BEFORE `openEvents.containsKey(key)` — same-key short-circuit does NOT catch the cross-pattern dedup case because VrApi and OpenXR generate DIFFERENT keys (`VRRuntime:VrApi:...` vs `VRRuntime:OpenXR:...`). The keying scheme intentionally namespaces by tag+pattern, so the dedup window is the only mechanism that collapses cross-pattern same-session opens.
- The synthesis hook had to be added in TWO places: end of `tryClose` (for pattern-driven closes) AND inside `stop()`'s force-close `for (vrEv in closed)` loop (for session-end force-closes). The `tryClose` path doesn't run during `stop()` because `stop()` uses `replaceEvents` directly to flag `endInferred=true` on the bulk close — so a single hook would miss one of the two paths.
- Threadtime fixture year inference: `LogcatLineParser` builds the year from `LocalDateTime.now().year` (desktop clock). The `tsMs` passed into the detector is therefore the parsed device timestamp converted to current-year epoch ms. Since the foreground guard is primed at `Long.MAX_VALUE` in the fixture tests, that timestamp difference is irrelevant — `MAX - <anything> = MAX`, well below the 2s guard.
- The `VR_RETURN_TRANSITION_WINDOW_MS` constant was extracted in the GREEN step (not REFACTOR) to avoid the 2_000L magic number from appearing in the original `emitVrReturnTransition` body. Detekt's MagicNumber rule wasn't tripped (2_000L is below the default threshold) but extracting the constant follows the project's existing convention of `FOREGROUND_GUARD_MS`, `MAX_EVENTS`, `APP_STARTUP_DEBOUNCE_MS` all living in the companion.

**Remaining**: None — all 17 tasks complete. Ready for sdd-verify (next phase).
