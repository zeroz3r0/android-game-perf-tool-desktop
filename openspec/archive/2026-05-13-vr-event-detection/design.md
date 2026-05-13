# Design: VR Event Detection

## Technical Approach

Approach A from explore #398 / proposal #401: ONE `SdkSignature` row "VRRuntime" in `SdkSignatureCatalog.ALL` covering Oculus VrApi + OVRPlugin + OpenXR (Tier 1). Catalog grows 18→19. Two minimal `EventDetectorImpl` hooks: same-SDK open dedup (5s window) + post-close VR_RETURN_TRANSITION synthesis. Confidence HINT lives in KDoc only (data class unchanged — minimal blast radius).

## Architecture Decisions

### D1: Dedup mechanism — catalog-level via additive `SdkSignature` field

| Option | Pros | Cons |
|--------|------|------|
| A. New `dedupWindowMs: Long? = null` on SdkSignature | Backward compat (null default); single source per CLAUDE.md anti-dup rule; testable in isolation | Touches data class (1 line) |
| B. Hardcoded VR exception in EventDetectorImpl | Zero data-class change | Special-case-by-name is ugly; the very anti-pattern v4.2.13 ToolResolver lesson warns against |

**Choice: A.** Mirrors the `closePatterns = emptyList()` pattern (additive optional behavior keyed in the catalog row). `EventDetectorImpl.tryOpen` checks `sig.dedupWindowMs` and skips when an open event for the same `sig.sdk` started within window. VRRuntime sets `dedupWindowMs = 5000L`; all other entries leave it null (no behavior change).

### D2: VR_RETURN_TRANSITION synthesis — hook in `tryClose` + `stop()`

| Option | Pros | Cons |
|--------|------|------|
| A. Post-hoc 2s synthetic event on VR_SESSION close | Single source, deterministic; mirrors `emitScreenTransition` shape | 2s window is a heuristic |
| B. Multi-stage OpenXR state-machine | More accurate | New capability, +3-4 tasks, breaks Approach A scope |
| C. Drop VR_RETURN_TRANSITION | Cheapest | Loses report distinction |

**Choice: A.** When `tryClose` runs on a VR_SESSION event, emit synthetic `VR_RETURN_TRANSITION` (startMs = closed.endMs, endMs = startMs + 2000, same sdkSource, `confidence = LOW`, `signatureMatched = "synthesized:vr-return-transition"`, `endInferred = true`). For `stop()` force-close path: same hook, just `endInferred = true` on both events. Helper: `emitVrReturnTransition(closed: DetectedEvent)` private to EventDetectorImpl.

### D3: Confidence HINT — KDoc-only, no data class change

`SdkSignature` has no `confidence` field today; `Confidence` enum is per-event, not per-signature. Adding a field for one entry is over-engineering. **Choice:** KDoc block on the VRRuntime entry citing Khronos OpenXR spec + Meta public sample sources, with explicit `// confidence: HINT — patterns not lab-verified, sourced from public docs only`. A test asserts the KDoc string is present (reads file via test resources or uses reflection on a companion constant — see test plan). Mirrors the Sprint 1 SDK_INIT "Patterns are best-effort" disclaimer (catalog lines 23-25).

## Data Flow

    LogLine(tag=VrApi, msg="vrapi_EnterVrMode")
       │
       ▼
    matchOpen → MatchResult(VRRuntime, VR_SESSION)
       │
       ▼
    tryOpen ──[dedupWindowMs check]──▶ skip if same-SDK open <5s ago
       │
       ▼ (open VR_SESSION)
    handleLogLine(tag=OpenXR, msg="xrEndSession")
       │
       ▼
    matchClose → tryClose(VR_SESSION)
       │
       ├─▶ stamp endMs on VR_SESSION
       └─▶ emitVrReturnTransition(closed) ──▶ synthetic VR_RETURN_TRANSITION [startMs..startMs+2000]

## File Changes

| File | Action | Description |
|------|--------|-------------|
| `core/events/SdkSignature.kt` | Modify | Add `val dedupWindowMs: Long? = null` (additive, default null) |
| `core/events/SdkSignatureCatalog.kt` | Modify | +1 entry "VRRuntime" with HINT KDoc, dedupWindowMs=5000L |
| `core/events/EventDetectorImpl.kt` | Modify | `tryOpen` dedup check; `tryClose` synthesis hook; `stop()` synthesis on VR_SESSION force-close |
| `test/.../SdkSignatureCatalogTest.kt` | Modify | Size 18→19; expected names += "VRRuntime" |
| `test/.../VrSignaturesTest.kt` | New | Per-pattern positive/negative, dedup, synthesis, KDoc-HINT assertion, fixture-driven |
| `test/resources/logcat-fixtures/vr-oculus-session.log` | New | ~50 lines VrApi flow |
| `test/resources/logcat-fixtures/vr-openxr-session.log` | New | ~50 lines OpenXR flow |

## Interfaces / Contracts

```kotlin
// SdkSignature.kt — additive field
internal data class SdkSignature(
    val sdk: String,
    val defaultType: EventType,
    val activityClasses: List<String>,
    val logcatTags: List<String>,
    val openPatterns: List<Pair<Regex, EventType>>,
    val closePatterns: List<Regex>,
    val dedupWindowMs: Long? = null,  // NEW: when non-null, suppress same-sdk re-open inside window
)

// SdkSignatureCatalog.kt — new entry (abridged; full KDoc in implementation)
// confidence: HINT — patterns not lab-verified, sourced from Khronos OpenXR 1.0
// spec + Meta public sample code (VrCubeWorld_NativeActivity) + Unity OpenXR
// plugin public source. Verify on real Quest/Pico capture before promoting.
SdkSignature(
    sdk = "VRRuntime",
    defaultType = EventType.VR_SESSION,
    activityClasses = emptyList(),
    logcatTags = listOf("VrApi", "OVRPlugin", "OpenXR", "xrInstance"),
    openPatterns = listOf(
        Regex("""\bvrapi_EnterVrMode\b""") to EventType.VR_SESSION,
        Regex("""(?i)\bEntered\s*VR\s*Mode\b""") to EventType.VR_SESSION,
        Regex("""(?i)\bHMDMounted\b""") to EventType.VR_SESSION,
        Regex("""\bxrBeginSession\b""") to EventType.VR_SESSION,
        Regex("""\bXR_SESSION_STATE_READY\b""") to EventType.VR_SESSION,
    ),
    closePatterns = listOf(
        Regex("""\bvrapi_LeaveVrMode\b"""),
        Regex("""(?i)\bLeft\s*VR\s*Mode\b"""),
        Regex("""(?i)\bHMDUnmounted\b"""),
        Regex("""\bxrEndSession\b"""),
        Regex("""\bXR_SESSION_STATE_STOPPING\b"""),
    ),
    dedupWindowMs = 5_000L,
)
```

```kotlin
// EventDetectorImpl.kt — dedup in tryOpen (before openEvents.containsKey check)
sig.dedupWindowMs?.let { window ->
    val recentOpen = openEvents.values.firstOrNull {
        it.sdkSource == sig.sdk && (startMs - it.startMs) <= window
    }
    if (recentOpen != null) return
}

// EventDetectorImpl.kt — synthesis hook in tryClose
private fun emitVrReturnTransition(closed: DetectedEvent) {
    if (closed.type != EventType.VR_SESSION) return
    if (totalEventCount() >= MAX_EVENTS) return
    val startMs = closed.endMs ?: closed.startMs
    appendEvent(DetectedEvent(
        type = EventType.VR_RETURN_TRANSITION,
        sdkSource = closed.sdkSource,
        startMs = startMs,
        endMs = startMs + 2_000L,
        confidence = Confidence.LOW,
        signatureMatched = "synthesized:vr-return-transition",
        endInferred = true,
        metadata = mapOf("source" to "synthesized", "fromEventId" to closed.id),
    ))
}
// Called at end of tryClose, and inside stop()'s force-close loop for VR_SESSION entries.
```

## Testing Strategy

| Phase | Tests (RED → GREEN) |
|-------|--------------------|
| 1. Catalog wiring | (a) size 18→19 RED; (b) "VRRuntime" in expected names RED; GREEN add entry |
| 2. Patterns | per-pattern positive (5 open + 5 close); negative `Unity` tag with VR-like msg must NOT match; tag-specificity for short `XR` (we exclude bare `XR` from tags — only `xrInstance`+`OpenXR`) |
| 3. Dedup + synthesis | RED: VrApi open + OpenXR open within 5s → 1 event; GREEN dedup field+check; RED: close VR_SESSION → emits VR_RETURN_TRANSITION at endMs, 2s window, LOW confidence, endInferred=true; GREEN synthesis hook; RED: stop() with open VR_SESSION → both events emitted with endInferred=true |
| 4. Fixtures | `vr-oculus-session.log` replay → exactly 1 VR_SESSION + 1 VR_RETURN_TRANSITION; same for `vr-openxr-session.log` |
| 5. Verify gate | `./gradlew check` green, detekt clean, apply-progress save |

## Migration / Rollout

No migration. Additive: `dedupWindowMs` defaults to null → zero behavior change for existing 18 entries. Rollback = revert single commit.

## Risks + Mitigations

| Risk | Mitigation |
|------|------------|
| VrApi+OpenXR double-open on Quest | `dedupWindowMs = 5000L` on VRRuntime row |
| Tag `XR` too generic | Excluded from logcatTags — only `OpenXR`, `xrInstance` (narrower than explore suggested) |
| Patterns not lab-verified | HINT KDoc block + test asserting disclaimer string present |
| Catalog size assertion drift | Deliberate 18→19, test updated |
| 2s VR_RETURN_TRANSITION is a heuristic | `endInferred = true` + `confidence = LOW` disclose this |

## Out of Scope

- Pico proprietary PxrApi (routes via OpenXR in v2.4+)
- HTC WaveVR proprietary (routes via OpenXR in Wave 5+)
- Google Daydream (deprecated 2019)
- `dumpsys SurfaceFlinger` VR-layer corroboration (defer to v2)
- Lab verification (best-effort from Khronos spec + Meta sample code; follow-up via real-device capture test pack)

## Open Questions

None — Approach A from explore is approved by proposal #401; design phase locked synthesis option 2 (post-hoc 2s) and dedup option A (catalog field).

## Ready for Tasks

Yes. Phase order in test plan dictates task breakdown.
