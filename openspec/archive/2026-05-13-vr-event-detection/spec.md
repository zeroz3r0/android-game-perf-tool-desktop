# Delta for event-segmentation

Change: `vr-event-detection` (Sprint 4 / Issue #2 / D.6). Extends existing SDK signature detection with VR runtime patterns + VR_RETURN_TRANSITION synthesis.

> **NOTE — supersedes ESC-VR-001..005 from parent change `event-segmentation-coverage`.** The Sprint 4a stubs in `openspec/changes/event-segmentation-coverage/specs/event-segmentation/spec.md` §8 used a Quest-only `VrApi`-tag-presence approach with silent-gap close. This change replaces them with multi-runtime Tier 1 patterns (Oculus VrApi + OVRPlugin + OpenXR), additive catalog-level `dedupWindowMs` field, and post-hoc 2s synthesis. Cross-reference: `VR-001..VR-008` REPLACE `ESC-VR-001..005`.

## ADDED Requirements

### Requirement: VR-001 VR Runtime catalog entry

The system MUST add exactly ONE `SdkSignature` entry named `VRRuntime` (or equivalent canonical name) to `SdkSignatureCatalog.ALL`. The catalog size invariant MUST bump from 18 to 19 entries.

#### Scenario: Catalog size invariant updated
- GIVEN `SdkSignatureCatalog.ALL` includes the new VR entry
- WHEN `SdkSignatureCatalogTest` asserts `ALL.size == 19`
- THEN the assertion passes
- AND the expected-name set includes `VRRuntime`

#### Scenario: No duplicate VR entries
- GIVEN the VR signatures live ONLY in `SdkSignatureCatalog.ALL`
- WHEN any file under `core/events/` is scanned for VR regex literals
- THEN no other source file defines VR open/close regex (anti-duplication invariant)

### Requirement: VR-002 Oculus / Meta Quest detection

The system MUST detect Oculus VR sessions from VrApi and OVRPlugin tags. Open patterns: `vrapi_EnterVrMode`, `Entered VR Mode`, `HMDMounted`. Close patterns: `vrapi_LeaveVrMode`, `Left VR Mode`, `HMDUnmounted`.

#### Scenario: vrapi_EnterVrMode opens VR_SESSION
- GIVEN logcat line `VrApi: vrapi_EnterVrMode()` at t=1000ms
- WHEN the detector processes it
- THEN one `VR_SESSION` event is opened with `startMs=1000` and `sdkSource="VRRuntime"`

#### Scenario: VrApi noise line does not open
- GIVEN logcat line `VrApi: surface created` at t=1000ms
- WHEN the detector processes it
- THEN NO `VR_SESSION` event is opened

### Requirement: VR-003 OpenXR detection

The system MUST detect OpenXR sessions from `OpenXR` / `xrInstance` tags. Open patterns: `xrBeginSession`, `XR_SESSION_STATE_READY`. Close patterns: `xrEndSession`, `XR_SESSION_STATE_STOPPING`.

#### Scenario: xrBeginSession opens VR_SESSION
- GIVEN logcat line `OpenXR: xrBeginSession succeeded` at t=2000ms
- WHEN the detector processes it
- THEN one `VR_SESSION` event opens with `startMs=2000`

#### Scenario: XR tag without canonical token does not open
- GIVEN logcat line `XR: log noise unrelated to OpenXR` at t=2000ms
- WHEN the detector processes it
- THEN NO `VR_SESSION` event is opened

### Requirement: VR-004 VrApi+OpenXR same-session dedup

When both VrApi and OpenXR fire open patterns for the same headset session, the system MUST emit exactly ONE `VR_SESSION` event. Dedup MUST be keyed by `sdkSource` within a configurable window (default 5000ms).

#### Scenario: VrApi + OpenXR within 5s → ONE event
- GIVEN `vrapi_EnterVrMode` at t=1000ms AND `xrBeginSession` at t=3000ms
- WHEN the detector processes both
- THEN exactly ONE `VR_SESSION` event exists with `startMs=1000`

#### Scenario: VrApi + OpenXR 10s apart → TWO events
- GIVEN `vrapi_EnterVrMode` at t=1000ms AND `xrBeginSession` at t=11000ms
- WHEN the detector processes both
- THEN TWO distinct `VR_SESSION` events exist (outside dedup window)

### Requirement: VR-005 VR_RETURN_TRANSITION synthesis

When a `VR_SESSION` closes (via close-pattern match OR `detector.stop()`), the system MUST synthesize a `VR_RETURN_TRANSITION` event with `startMs=vrSessionEndMs`, `endMs=vrSessionEndMs+2000`, and the same `sdkSource` as the closed `VR_SESSION`.

#### Scenario: Close pattern triggers synthesis
- GIVEN `VR_SESSION` started at t=1000ms
- WHEN `vrapi_LeaveVrMode` fires at t=10000ms
- THEN a `VR_RETURN_TRANSITION` is emitted with `startMs=10000`, `endMs=12000`

#### Scenario: detector.stop() while VR_SESSION open
- GIVEN `VR_SESSION` started at t=1000ms and never closed via pattern
- WHEN `detector.stop()` is called at t=10000ms
- THEN the `VR_SESSION` closes at its inferred end AND a `VR_RETURN_TRANSITION` is synthesized using that inferred end as `startMs`

### Requirement: VR-006 Tag specificity

The system MUST NOT match unrelated lines that share short tag `XR`. Regex MUST anchor to canonical OpenXR tokens (`xrBeginSession`, `xrEndSession`, `XR_SESSION_STATE_*`). `logcatTags` allowlist MUST narrow to known runtime tags.

#### Scenario: Custom XR tag from unrelated app
- GIVEN logcat line `XR: custom app message about exchange rate` at t=1000ms
- WHEN the detector processes it
- THEN NO `VR_SESSION` event is opened

#### Scenario: Unity tag not in VR allowlist
- GIVEN logcat line `Unity: xrBeginSession-like prose` at t=1000ms
- WHEN the detector processes it
- THEN NO `VR_SESSION` event is opened (tag `Unity` is not in the VR allowlist)

### Requirement: VR-007 Confidence policy: HINT

Until lab-verified on real VR hardware, all VR patterns MUST be marked `confidence = HINT` in KDoc (and, if catalog metadata supports it, in the `metadata` field). The system MUST NOT claim `HIGH` confidence without empirical real-device capture documented in repo.

#### Scenario: KDoc declares HINT
- GIVEN the `VRRuntime` signature is added to the catalog
- WHEN a reviewer inspects the KDoc above the entry
- THEN the KDoc explicitly states `confidence = HINT` and references "lab verification pending"

#### Scenario: No HIGH claim without evidence
- GIVEN no real-device fixture exists at `src/test/resources/logcat-fixtures/vr-real-device-*.log`
- WHEN the catalog metadata is inspected
- THEN no VR pattern is marked `HIGH` confidence

### Requirement: VR-008 Fixture coverage

The system MUST include two logcat fixtures: `vr-oculus-session.log` and `vr-openxr-session.log`, each containing a full enter → during → exit cycle. Each fixture MUST produce exactly ONE `VR_SESSION` event AND ONE `VR_RETURN_TRANSITION` event when replayed.

#### Scenario: Oculus fixture replay
- GIVEN fixture `vr-oculus-session.log` (50-200 lines, `vrapi_EnterVrMode` → gameplay → `vrapi_LeaveVrMode`)
- WHEN the detector replays the fixture and is stopped
- THEN exactly 1 `VR_SESSION` AND 1 `VR_RETURN_TRANSITION` event are emitted

#### Scenario: OpenXR fixture replay
- GIVEN fixture `vr-openxr-session.log` (`xrBeginSession` → `XR_SESSION_STATE_FOCUSED` → `xrEndSession`)
- WHEN the detector replays the fixture and is stopped
- THEN exactly 1 `VR_SESSION` AND 1 `VR_RETURN_TRANSITION` event are emitted
