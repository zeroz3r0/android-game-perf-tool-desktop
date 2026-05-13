# Archive Report: vr-event-detection

**Change**: `vr-event-detection`
**Archive date**: 2026-05-13
**Verify status**: PASS ✅ (engram #409)
**Backend**: engram (primary) + openspec archive folder (audit trail)
**Archived folder**: `openspec/archive/2026-05-13-vr-event-detection/`

## Engram artifact IDs (audit trail)

| Artifact | Topic Key | Observation ID |
|----------|-----------|----------------|
| Proposal | `sdd/vr-event-detection/proposal` | #401 |
| Spec | `sdd/vr-event-detection/spec` | #403 |
| Design | `sdd/vr-event-detection/design` | #404 |
| Tasks | `sdd/vr-event-detection/tasks` | #405 |
| Apply-progress | `sdd/vr-event-detection/apply-progress` | #406 |
| Verify-report | `sdd/vr-event-detection/verify-report` | #409 |
| Archive-report (this) | `sdd/vr-event-detection/archive-report` | (this save) |
| Project context | `sdd-init/android-game-perf-tool-desktop` | #96 |

## Change Summary

Added `VR_SESSION` + `VR_RETURN_TRANSITION` detection via a single combined "VRRuntime" `SdkSignature` catalog entry (catalog size 18→19). Covers Tier 1 VR runtimes: Oculus VrApi (`vrapi_EnterVrMode`/`vrapi_LeaveVrMode`), OVRPlugin (`HMDMounted`/`HMDUnmounted`), and OpenXR (`xrBeginSession`/`xrEndSession`, `XR_SESSION_STATE_READY`/`XR_SESSION_STATE_STOPPING`). Dedup via additive `dedupWindowMs: Long? = null` field on `SdkSignature` (5s for VRRuntime, null for all other 18 entries — zero behaviour change for existing entries). `VR_RETURN_TRANSITION` synthesized post-hoc 2s from `VR_SESSION` close, invoked from BOTH `tryClose` AND `stop()` force-close paths. HINT confidence in KDoc only (data class has no `confidence` field) — lab verification on real Quest/Pico devices deferred. This change SUPERSEDES the Sprint 4a stubs `ESC-VR-001..005` from the parent `event-segmentation-coverage` change.

## Files Added/Modified in this Change

### Production (3 files)

| File | Action | Brief |
|------|--------|-------|
| `src/main/kotlin/com/gameperf/desktop/core/events/SdkSignature.kt` | Modified | Added `val dedupWindowMs: Long? = null` (additive last param) |
| `src/main/kotlin/com/gameperf/desktop/core/events/SdkSignatureCatalog.kt` | Modified | Appended 19th entry "VRRuntime" with HINT KDoc, dedupWindowMs=5000L, 5 open regex + 5 close regex covering VrApi/OVRPlugin/OpenXR |
| `src/main/kotlin/com/gameperf/desktop/core/events/EventDetectorImpl.kt` | Modified | New `VR_RETURN_TRANSITION_WINDOW_MS = 2_000L` constant in companion; dedup hook in `tryOpen` (consumes `sig.dedupWindowMs`); `emitVrReturnTransition` helper guarded by type + MAX_EVENTS; `tryClose` calls helper post-close; `stop()` force-close loop iterates and calls helper for VR_SESSION entries |

### Tests (1 new file, 1 modified, 22 new tests)

| File | Action | Brief |
|------|--------|-------|
| `src/test/kotlin/com/gameperf/desktop/core/events/VrSignaturesTest.kt` | Created | 22 tests across 5 groups: positive patterns (5), negatives (2 + KDoc HINT regression-protect), dedup boundary (2), synthesis paths (2), fixture-replay (2) + helpers `newVrDetectorAtTime` + `readFixtureLines` |
| `src/test/kotlin/com/gameperf/desktop/core/events/SdkSignatureCatalogTest.kt` | Modified | Size assertion 18→19; expected names += "VRRuntime"; `noActivityRequired` allowlist += `VR_SESSION` (VR runtimes don't push their own Activity); anti-dup scanner tokens += `XR_SESSION_STATE_` |

### Fixtures (2 new files)

| File | Action | Brief |
|------|--------|-------|
| `src/test/resources/logcat-fixtures/vr-oculus-session.log` | Created | 49 lines threadtime, VrApi+OVRPlugin Quest-flavoured cycle, HMDMounted at +700ms (exercises dedup) |
| `src/test/resources/logcat-fixtures/vr-openxr-session.log` | Created | 50 lines threadtime, OpenXR+xrInstance Pico-flavoured cycle, XR_SESSION_STATE_READY at +700ms (exercises dedup) |

### OpenSpec audit-trail (this archive — 7 files)

- `openspec/archive/2026-05-13-vr-event-detection/proposal.md`
- `openspec/archive/2026-05-13-vr-event-detection/spec.md`
- `openspec/archive/2026-05-13-vr-event-detection/design.md`
- `openspec/archive/2026-05-13-vr-event-detection/tasks.md`
- `openspec/archive/2026-05-13-vr-event-detection/apply-progress.md`
- `openspec/archive/2026-05-13-vr-event-detection/verify-report.md`
- `openspec/archive/2026-05-13-vr-event-detection/archive-report.md` (this file)

### Parent-change annotations (2 files)

- `openspec/changes/event-segmentation-coverage/specs/event-segmentation/spec.md` — §8 "VR_SESSION + VR_RETURN_TRANSITION (Sprint 4a — Quest only)" annotated as SUPERSEDED. `ESC-VR-001` redirected to `VR-001..VR-003`. `ESC-VR-002` (silent-gap close) marked DROPPED in favour of explicit close patterns. `ESC-VR-003` redirected to `VR-005` (with implementation diff noted: 2s vs 5s window, LOW vs MEDIUM confidence, both paths covered). `ESC-VR-004` partially DROPPED (`XrPerformanceManager` removed from allowlist). `ESC-VR-005` (Quest-only doc) redirected to multi-runtime VR-002/VR-003/VR-007. Sprint summary line at top of spec also annotated. Original wording preserved as historical traceability.
- `openspec/changes/event-segmentation-coverage/tasks.md` — Sprint 4a section header annotated with shipped/archived banner pointing at this archive. Batch 4a.1, 4a.2, 4a.3, 4a.4, 4a.5 all marked SUPERSEDED (kept for historical traceability). Note: `PostVrRecoveryRule` (Batch 4a.3) was NOT shipped in this change — it remains a backlog follow-up.

## Tests Added

- **22 new tests**: all in `VrSignaturesTest.kt` covering VR-001 through VR-008
- **1 catalog test edit**: `SdkSignatureCatalogTest` size assertion 18→19 + expected names + noActivityRequired bypass + anti-dup tokens
- **Suite totals after change**: `./gradlew check` BUILD SUCCESSFUL, detekt 0 findings on modified files

## Spec Requirements Implemented

| ID | Requirement | Test Class(es) |
|----|-------------|----------------|
| VR-001 | VR Runtime catalog entry (catalog size 18→19) | `SdkSignatureCatalogTest::eighteen→nineteen catalogued SDKs and engines` + anti-dup invariant |
| VR-002 | Oculus VrApi + OVRPlugin detection (open/close) | `VrSignaturesTest::vrapi_EnterVrMode opens VR_SESSION` + `HMDMounted opens` + close pairs |
| VR-003 | OpenXR detection (xrBeginSession/xrEndSession + state tokens) | `VrSignaturesTest::xrBeginSession opens` + `XR_SESSION_STATE_READY opens` + close pairs |
| VR-004 | VrApi+OpenXR same-session dedup within 5s window | `VrSignaturesTest::VrApi + OpenXR within 5s → ONE event` + boundary `outside 5s → TWO events` |
| VR-005 | VR_RETURN_TRANSITION post-hoc 2s synthesis | `VrSignaturesTest::synthesis on close pattern` + `synthesis on stop() force-close` |
| VR-006 | Tag specificity (no bare `XR` collision, no Unity-tag false-positive) | `VrSignaturesTest::bare XR tag negative` + `Unity tag negative` |
| VR-007 | HINT confidence in KDoc only | KDoc on VRRuntime entry; regression-protect test in `VrSignaturesTest` |
| VR-008 | Fixture coverage (1+1 each for Oculus + OpenXR cycles) | `VrSignaturesTest::oculus fixture replay` + `openxr fixture replay` |

## Lessons Learned

### 1. Additive `dedupWindowMs` field cleanly extends single-source catalog pattern

The catalog is the single source of truth for SDK detection (per `CLAUDE.md` anti-duplication rule, lesson learned from v4.2.13 `ToolResolver` duplication bug). Adding cross-pattern dedup behaviour as a new `SdkSignature.dedupWindowMs: Long? = null` field is the right shape: it keeps the catalog the sole authority (the value lives on the row), preserves backward compat for all 18 existing entries (null default → no behaviour change), and gives the detector a single `sig.dedupWindowMs?.let { ... }` hook to apply. The alternative — a hardcoded "if sdk == VRRuntime" exception in `EventDetectorImpl` — would have been the very anti-pattern v4.2.13 warns against.

**Corollary**: the dedup hook must run BEFORE the `openEvents.containsKey(key)` short-circuit because VrApi and OpenXR generate DIFFERENT key shapes (`VRRuntime:VrApi:...` vs `VRRuntime:OpenXR:...`). The keying scheme intentionally namespaces by tag+pattern, so the dedup window is the only mechanism that collapses cross-pattern same-session opens. This is documented in `apply-progress.md` learned section.

### 2. Post-hoc synthesis mirrors existing INSTRUMENTED Stop pattern — no new detector capability required

The shipped `VR_RETURN_TRANSITION` synthesis is a thin post-close hook in `tryClose` + `stop()` force-close. It does NOT introduce a new state-machine, a new detector branch class, or a new lifecycle phase — it's literally `if (closed.type == VR_SESSION) emitVrReturnTransition(closed)`. This mirrors how `instrumented-event-mode` (engram #372 dormant catalog entry pattern) keeps detector logic narrow and avoids breaking changes to the generic catalog-match flow. The 5s "delayed emission" idea from the original `ESC-VR-003` stub was DROPPED in favour of an immediate 2s synthesis at close-time — simpler, deterministic, no extra tick/scheduler infrastructure needed.

**Corollary**: the synthesis hook had to be added in TWO places — end of `tryClose` (pattern-driven closes) AND inside `stop()`'s force-close `for (vrEv in closed)` loop (session-end force-closes). The `tryClose` path doesn't run during `stop()` because `stop()` uses `replaceEvents` directly to flag `endInferred=true` on the bulk close. A single hook would miss one of the two paths. This is the same "two-place-hook" gotcha as the v4.3.2 `activeProcesses` lifecycle bug — when two subsystems have distinct close paths, both need explicit hooks; don't trust a single chokepoint.

### 3. HINT confidence in KDoc-only is sufficient when data class has no confidence field

`SdkSignature` has no `confidence` field (Confidence enum is per-emitted-event, not per-signature). Adding a field for ONE entry would have been over-engineering — every other catalog entry would carry an unused `confidence = HIGH` annotation. **Choice**: KDoc block on the VRRuntime entry citing Khronos OpenXR 1.0 spec + Meta public sample code (VrCubeWorld_NativeActivity) + Unity OpenXR plugin source, with explicit `// confidence: HINT — patterns not lab-verified, sourced from public docs only`. A test in `VrSignaturesTest` regression-protects the KDoc string (reads the source file via test resources). This pattern is reusable: future entries with similar "patterns not lab-verified" provenance can follow the same recipe without bloating the data class.

### 4. Multi-runtime via single catalog row > separate rows per runtime

The original Sprint 4a stub (`ESC-VR-001`) had a separate "Meta Quest VR" entry. The shipped change collapses Oculus VrApi + OVRPlugin + OpenXR into ONE "VRRuntime" entry with heterogeneous open/close regex lists and a narrow shared `logcatTags` allowlist. Benefit: a Quest device that emits BOTH `vrapi_EnterVrMode` AND `xrBeginSession` (OpenXR backend running underneath the Meta runtime, very common in Unity Quest builds) gets ONE VR_SESSION event via the `dedupWindowMs = 5000L` window, not two. The alternative (separate rows + per-row dedup) would have required cross-row dedup logic in the detector, which is exactly the anti-pattern called out in lesson #1.

### 5. Parent-change supersession via spec annotation (re-applying instrumented-event-mode pattern)

Rather than deleting the original `ESC-VR-001..005` requirements from the parent change's spec, we annotated each one with a `(SUPERSEDED by VR-NNN)` heading and replaced the body with a one-line cross-reference. The parent-change tasks file got a banner under "Sprint 4a — VR_SESSION + VR_RETURN_TRANSITION" pointing at this archive. Future readers see the supersession at a glance without us losing the historical wording. This is the same pattern used for `instrumented-event-mode` (archived 2026-05-13) — re-applying a previously validated archive pattern. Worth doing the same for similar "stub in parent change, shipped as separate change" workflows going forward.

## Files Left for Follow-up

Deferred (NOT in this change's scope):

- **Pico proprietary PxrApi** — Pico v2.4+ routes through OpenXR (covered by VR-003); older Pico builds with proprietary PxrApi NOT detected.
- **HTC Wave proprietary WaveVR** — Wave 5+ routes through OpenXR (covered by VR-003); older WaveVR-only builds NOT detected.
- **Google Daydream** — deprecated 2019; explicitly out of scope.
- **`dumpsys SurfaceFlinger` VR-layer corroboration** — deferred to v2 verification pass.
- **Real-device lab verification of patterns** — HINT confidence in KDoc is the appropriate label until real Quest/Pico/Vive captures confirm the regex set. Backlog item: build a `vr-real-device-*.log` fixture pack and upgrade the VRRuntime entry's confidence labeling.
- **2s synthesis window tuning** — the 2s `VR_RETURN_TRANSITION_WINDOW_MS` is a heuristic disclosed via `confidence=LOW` + `endInferred=true`. Real-device lab capture data may tune to 1-3s.
- **`PostVrRecoveryRule`** — the parent change's Batch 4a.3 listed a new conclusions rule emitting "Tras cerrar la sesión VR la temperatura sube X°C..." when VR_RETURN_TRANSITION + temp delta > 2.0°C. This was NOT shipped in `vr-event-detection`. Remains a backlog follow-up under the parent `event-segmentation-coverage` change.

## Status

**FULLY ARCHIVED.** Engram artifact trail complete. The 7 archive files under `openspec/archive/2026-05-13-vr-event-detection/` provide the audit trail. The 2 parent-change annotations preserve historical traceability of the original `ESC-VR-*` stubs. Implementation lives in commits before `30db12d` (already merged to `main` as part of Issue #2 Sprint 4 progression). Per orchestrator instruction: this archive does NOT touch git.
