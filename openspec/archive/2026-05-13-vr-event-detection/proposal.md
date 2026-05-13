# Proposal: VR Event Detection

## Intent

`EventType.VR_SESSION` and `EventType.VR_RETURN_TRANSITION` are wired through the enum, report renderer, and KPI adapter since Sprint 4a, but `SdkSignatureCatalog.ALL` has zero VR signatures → these event types are never produced at runtime. This change closes the gap so VR captures (Quest 2/3/Pro, Pico 4/4 Ultra, Vive Focus 3 / XR Elite, Samsung XR) get headset-on/off ranges segmented in the report. Issue #2, Sprint 4 / D.6.

## Scope

### In Scope
- ONE new `SdkSignature` entry "VRRuntime" in `SdkSignatureCatalog.ALL` (Approach A from explore #398).
- Tier 1 patterns only: Oculus VrApi (`vrapi_EnterVrMode`/`vrapi_LeaveVrMode`), OVRPlugin (`HMDMounted`/`HMDUnmounted`), OpenXR (`xrBeginSession`/`xrEndSession`, `XR_SESSION_STATE_READY`/`XR_SESSION_STATE_STOPPING`).
- Catalog-level dedup so VrApi + OpenXR firing in the same Quest session produce ONE `VR_SESSION` event, not two.
- `VR_RETURN_TRANSITION` synthesized post-hoc: when `VR_SESSION` closes, emit synthetic 2-second event starting at `vrSession.endMs`, same `sdkSource`. Minimal `EventDetectorImpl` touch, mirrors INSTRUMENTED Stop pattern.
- Fixtures `vr-oculus-session.log` + `vr-openxr-session.log` (~50 lines each, threadtime format).
- Tests: positive per pattern, negative for short tag `XR` collision, cross-runtime dedup, fixture-driven.
- `confidence = HINT` in KDoc until lab-verified on real Quest/Pico.

### Out of Scope
- Pico proprietary PxrApi (routes via OpenXR in v2.4+).
- HTC WaveVR proprietary (routes via OpenXR in Wave 5+).
- Google Daydream (deprecated 2019).
- `dumpsys SurfaceFlinger` VR-layer corroboration (defer to v2).
- Real-device lab verification (best-effort from public docs / Khronos spec).

## Capabilities

### New Capabilities
- None.

### Modified Capabilities
- `event-segmentation`: ADD VR_SESSION lifecycle detection from multi-runtime patterns; ADD VR_RETURN_TRANSITION synthesis on VR_SESSION close. (Existing capability — extends current SDK signature detection to a new event family.)

## Approach

Approach A from explore #398: single combined "VRRuntime" `SdkSignature` row with heterogeneous open/close regex lists, narrow tag allowlist (`VrApi`, `OpenXR`, `OVRPlugin`, `xrInstance`, `XR`). Catalog dedup keyed by `sdk` name suppresses double-open. Synthesis is a small `EventDetectorImpl` post-close hook; no new state-machine capability.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `core/events/SdkSignatureCatalog.kt` | Modified | +1 entry → catalog size 18→19 |
| `core/events/EventDetectorImpl.kt` | Modified | Post-hoc VR_RETURN_TRANSITION synthesis on VR_SESSION close |
| `test/.../SdkSignatureCatalogTest.kt` | Modified | Bump size assertion 18→19, add VR to expected names |
| `test/.../VrSignaturesTest.kt` | New | Positive/negative/dedup/synthesis tests |
| `test/resources/logcat-fixtures/vr-oculus-session.log` | New | 50-line Quest-flavored fixture |
| `test/resources/logcat-fixtures/vr-openxr-session.log` | New | 50-line Pico/OpenXR-flavored fixture |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| VrApi+OpenXR double-open on Quest | High | Same-`sdk` dedup at catalog level |
| Patterns not lab-verified | High | `confidence = HINT` in KDoc, document for v2 verification pass |
| Tag `XR` too generic, collides with custom tags | Low | Regex specificity, narrow to `OpenXR`/`xrInstance` if collision found |
| Catalog size assertion drift | Low | Deliberate bump 18→19, part of change |

## Rollback Plan

Revert the single commit. Catalog returns to 18 entries; `VR_SESSION`/`VR_RETURN_TRANSITION` stop being produced but no other event type is affected (additive change). Report renderer + KPI adapter already tolerate zero VR events.

## Dependencies

None. Enum + report wiring landed in Sprint 4a.

## Success Criteria

- [x] `./gradlew check` green (detekt + tests).
- [x] `SdkSignatureCatalogTest` size assertion = 19, VRRuntime in expected names.
- [x] `VrSignaturesTest` covers positive (per pattern), negative (`XR` tag collision), dedup (VrApi+OpenXR same session → 1 event), synthesis (VR_SESSION close → VR_RETURN_TRANSITION at endMs, 2s window).
- [x] Fixture-driven: replaying `vr-oculus-session.log` and `vr-openxr-session.log` yields exactly 1 VR_SESSION + 1 VR_RETURN_TRANSITION each.
- [x] No regression in existing 18 catalog entries' tests.
