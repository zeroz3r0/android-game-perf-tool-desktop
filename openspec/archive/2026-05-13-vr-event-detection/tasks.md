# Tasks: VR Event Detection

Strict TDD. `.R` = failing test FIRST. `.G` = minimum code to GREEN. Verify cmd per task.

## Phase 1: Catalog wiring (3 tasks)

- [x] 1.1.R [VR-001] In `SdkSignatureCatalogTest.kt` change size assertion `ALL.size == 18` → `== 19` AND add `"VRRuntime"` to expected names set. Run: `./gradlew test --tests "*SdkSignatureCatalogTest*"` → RED (size/name mismatch). **DONE** — also extended `noActivityRequired` allowlist to include `VR_SESSION` (VR runtimes don't push their own Activity).
- [x] 1.2.G [VR-001..VR-003,VR-007] In `core/events/SdkSignature.kt` add `val dedupWindowMs: Long? = null` (additive last param). In `core/events/SdkSignatureCatalog.kt` append ONE `VRRuntime` entry: `defaultType = VR_SESSION`, `logcatTags = listOf("VrApi","OVRPlugin","OpenXR","xrInstance")`, 5 open regex + 5 close regex per design, `dedupWindowMs = 5_000L`, KDoc block citing `confidence = HINT — patterns not lab-verified (Khronos OpenXR spec + Meta sample sources)`. Run: `./gradlew test --tests "*SdkSignatureCatalogTest*"` → GREEN. **DONE**
- [x] 1.3.R [VR-001 anti-dup] Add test `no VR regex outside catalog`: grep `core/events/` files (skip `SdkSignatureCatalog.kt`) for `vrapi_|xrBegin|xrEnd|HMDMounted`; assert zero hits. Run same cmd → GREEN (single source invariant). **DONE** — also added `XR_SESSION_STATE_` to scanned tokens.

## Phase 2: Per-pattern positive + negative (5 tasks)

Create `src/test/kotlin/.../events/VrSignaturesTest.kt` and add cases incrementally. Run after each: `./gradlew test --tests "*VrSignaturesTest*"`.

- [x] 2.1.R+G [VR-002] Positive: line `tag=VrApi msg="vrapi_EnterVrMode()"` at t=1000 → 1 `VR_SESSION` open, `sdkSource="VRRuntime"`, `startMs=1000`. Negative: `tag=VrApi msg="surface created"` → NO event. **DONE**
- [x] 2.2.R+G [VR-002] Positive: `tag=OVRPlugin msg="HMDMounted"` opens VR_SESSION. Positive: `tag=VrApi msg="Entered VR Mode"` opens (case-insensitive). Close: `vrapi_LeaveVrMode` + `HMDUnmounted` close it. **DONE**
- [x] 2.3.R+G [VR-003] Positive: `tag=OpenXR msg="xrBeginSession succeeded"` t=2000 → 1 VR_SESSION startMs=2000. Close: `xrEndSession` + `XR_SESSION_STATE_STOPPING`. **DONE**
- [x] 2.4.R+G [VR-003] Positive: `tag=xrInstance msg="XR_SESSION_STATE_READY"` opens VR_SESSION. Negative: `tag=OpenXR msg="log noise unrelated"` → NO event. **DONE**
- [x] 2.5.R+G [VR-006] Negative: `tag=XR msg="exchange rate update"` → NO event (bare `XR` excluded from allowlist). Negative: `tag=Unity msg="xrBeginSession-like prose"` → NO event (tag not in VR allowlist). **DONE**

## Phase 3: Dedup + synthesis (4 tasks)

- [x] 3.1.R [VR-004] Test in `VrSignaturesTest`: VrApi open t=1000 + OpenXR open t=3000 → exactly 1 VR_SESSION (`startMs=1000`). Second test: same opens t=1000 / t=11000 → 2 events. Run: `./gradlew test --tests "*VrSignaturesTest*"` → RED (dedup not wired). **DONE** — RED confirmed: first test failed with 2 events, second already produced 2.
- [x] 3.2.G [VR-004] In `EventDetectorImpl.tryOpen` BEFORE the existing same-type open check, insert: `sig.dedupWindowMs?.let { w -> if (openEvents.values.any { it.sdkSource == sig.sdk && (startMs - it.startMs) <= w }) return }`. Run same → GREEN. **DONE**
- [x] 3.3.R [VR-005] Tests: (a) open VR_SESSION t=1000, close via `vrapi_LeaveVrMode` t=10000 → assert 1 VR_RETURN_TRANSITION emitted with `startMs=10000`, `endMs=12000`, same sdkSource, `confidence=LOW`, `signatureMatched="synthesized:vr-return-transition"`, `endInferred=true`. (b) open VR_SESSION t=1000, call `detector.stop()` t=10000 → assert VR_SESSION closes AND VR_RETURN_TRANSITION synthesized with `endInferred=true`. Run → RED. **DONE** — RED confirmed: 2 close/synthesis tests failed before implementation.
- [x] 3.4.G [VR-005] Add `private fun emitVrReturnTransition(closed: DetectedEvent)` to `EventDetectorImpl` per design (guard `closed.type == VR_SESSION` + `totalEventCount() < MAX_EVENTS`). Call at end of `tryClose` AND inside `stop()` force-close loop for VR_SESSION entries. Run → GREEN. **DONE** — extracted `VR_RETURN_TRANSITION_WINDOW_MS = 2_000L` constant in companion to eliminate magic number.

## Phase 4: Fixture-driven smoke (3 tasks)

- [x] 4.1 Create `src/test/resources/logcat-fixtures/vr-oculus-session.log` — ~50 lines threadtime format: boot → `tag=VrApi msg=vrapi_EnterVrMode` → gameplay noise (Choreographer, ActivityManager) → `tag=OVRPlugin msg=HMDMounted` (within 5s of enter, must dedup) → 30s gameplay → `tag=VrApi msg=vrapi_LeaveVrMode` → `tag=OVRPlugin msg=HMDUnmounted`. **DONE** — 49 lines, 04-21 14:32:00 → 14:32:51 timeline.
- [x] 4.2 Create `src/test/resources/logcat-fixtures/vr-openxr-session.log` — ~50 lines threadtime: boot → `tag=OpenXR msg=xrBeginSession succeeded` → `tag=xrInstance msg=XR_SESSION_STATE_READY` (within 5s, must dedup) → gameplay noise → `tag=xrInstance msg=XR_SESSION_STATE_STOPPING` → `tag=OpenXR msg=xrEndSession`. **DONE** — 50 lines, 04-21 15:10:00 → 15:10:50 timeline.
- [x] 4.3.R+G [VR-008] Fixture tests in `VrSignaturesTest`: replay each fixture via existing fixture-replay helper, call `detector.stop()`, assert EXACTLY 1 VR_SESSION + 1 VR_RETURN_TRANSITION per fixture. Run: `./gradlew test --tests "*VrSignaturesTest*"` → GREEN. **DONE**

## Phase 5: Verify gate (2 tasks)

- [x] 5.1 Run `./gradlew check` → all tests + detekt clean. Fix any detekt finding (likely: long regex line — use `@Suppress` ONLY if unavoidable, prefer `Regex("""...""")` multiline). **DONE** — BUILD SUCCESSFUL, detekt clean, zero findings, all VR tests + 22 in VrSignaturesTest pass.
- [x] 5.2 Save apply-progress to engram with `topic_key=sdd/vr-event-detection/apply-progress`, summary of files changed + commit hash. Hand off to `sdd-verify`. **DONE**

## Progress

- **Phase 1 (3/3) — DONE**: catalog wired with VRRuntime entry, dedupWindowMs field added, anti-dup invariant test in place.
- **Phase 2 (5/5) — DONE**: 14 per-pattern positive+negative tests in new VrSignaturesTest.kt covering VR-002/003/006/007.
- **Phase 3 (4/4) — DONE**: dedup hook in tryOpen + emitVrReturnTransition helper wired into tryClose AND stop() force-close loop.
- **Phase 4 (3/3) — DONE**: two threadtime fixtures (oculus + openxr) + fixture-replay tests asserting 1 VR_SESSION + 1 VR_RETURN_TRANSITION each.
- **Phase 5 (2/2) — DONE**: `./gradlew check` BUILD SUCCESSFUL, detekt 0 findings.
- 17/17 tasks complete. Ready for sdd-verify.
