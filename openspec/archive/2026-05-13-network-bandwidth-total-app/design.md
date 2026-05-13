# Design: Network Bandwidth Total App (Issue #1 Gap 2)

## Technical Approach

Mirror the v4.5.0 GPU + FPower probe-once-then-cache pattern. New `core.NetworkVendorCatalog` is the single source of truth for binder transaction codes + dumpsys fallback (CLAUDE.md v4.2.13 anti-duplication). Pure parser (`NetworkBandwidthParser`) handles hex + text formats with zero I/O. Bridge orchestrator `AdbBridge.captureNetworkBandwidth` owns per-device `NetworkDeviceState` (mirror of `GpuDeviceState`). Wire-up is ONE line in the existing `runCaptureLoop` medium-tier block — zero new branching in `startCapture` (post-H.7 CCN budget protected).

## Package Layout (flat under `core/`)

| File | Action |
|------|--------|
| `core/model/Metrics.kt` | Modify — add `NetworkSnapshot` data class |
| `core/model/NetworkDiagnostic.kt` | New — `NetworkDiagnostic` + `NetworkUnavailableReason` enum (5 reasons) |
| `core/NetworkVendorCatalog.kt` | New — `PROBE_CANDIDATES: List<NetworkProbeCandidate>` + `DUMPSYS_FALLBACK_CMD` |
| `core/NetworkBandwidthParser.kt` | New — pure `parseServiceCallResponse`, `parseDumpsysNetstats`, plausibility helpers |
| `core/AdbBridge.kt` | Modify — `captureNetworkBandwidth(deviceId, pkg)` + private `NetworkDeviceState` |
| `core/AdbBridgeApi.kt` | Modify — add interface fn, bump detekt `thresholdInInterfaces` |
| `core/FakeAdbBridge.kt` | Modify — test double |
| `core/SessionHistory.kt` | Modify — +5 fields on SerializableEntry/HistoryEntry |
| `viewmodel/AppViewModel.kt` | Modify — `acc.lastNetwork` + ONE call in `runCaptureLoop` medium tier + LiveMetrics/SessionResult threading |
| `report/ReportGenerator.kt` | Modify — `networkSection` + `networkDiagnosticBanner` |
| `src/test/.../core/network/*Test.kt` | New — parser + catalog + bridge fake tests |

## Probe Orchestration Algorithm

1. **Sticky failure short-circuit** — if `state.firstProbeFailed == true`, return cached `terminalDiagnostic` snapshot, ZERO shells.
2. **UID resolve (lazy, once-per-session)** — `cmd package list packages -U <pkg>` → cache `uid` on `NetworkDeviceState`. Failure → terminal `UID_LOOKUP_FAILED`.
3. **Cold probe** — single multi-call shell concatenating each binder candidate `service call netstats <code> i32 <uid> i32 0` (mirror GPU `buildProbeOneShellCommand`). Parser walks output blocks; first that yields 4 plausible int64 `(rxB, rxP, txB, txP)` wins → cache `winningProbeMethod=BINDER(code)` + baseline bytes. No delta yet → return baseline snapshot with `rxKbps=-1.0, txKbps=-1.0, networkAvailable=true`.
4. **Dumpsys fallback** — all binder empty → single `dumpsys netstats detail --uid <uid>` call. Success → cache `winningProbeMethod=DUMPSYS`. Failure → set `firstProbeFailed=true` + terminal reason (`ALL_PROBES_FAILED` / `DUMPSYS_FORMAT_UNKNOWN` / `PERMISSION_DENIED` based on parser verdict).
5. **Steady-state** — 1 shell call using cached method → parse cumulative bytes → compute delta from `state.lastBytes` → divide by `elapsedSeconds` from `state.lastSampleEpochMs` → emit `rxKbps`/`txKbps`. Update state.
6. **Outer try/catch** — `captureNetworkBandwidth` wraps `*Impl` in try/catch returning `CAPTURE_THREW` (mirrors `captureGpuUsage` exactly).

## Decisions

| ID | Choice | Alternatives | Rationale |
|----|--------|--------------|-----------|
| D1 | Single multi-call shell for cold probe | Per-candidate shell loop | 1 round-trip vs N; matches `buildProbeOneShellCommand` GPU precedent. FakeAdbBridge keys must remain substring-unique across candidates (same as GPU). |
| D2 | Per-uid bytes only (no wifi/cellular split) v1 | Per-interface enrichment via dumpsys | `service call netstats` doesn't expose interface; split deferred to v2 (D-Out-1). |
| D3 | `Confidence.HINT` on all binder candidates | HIGH/MEDIUM/LOW | No real-device captures yet across OEMs (mirrors VR Sprint 4 pattern); banner copy says "estimado". |
| D4 | Plausibility window `0 ≤ bytes ≤ 100GB` | Trust raw values | Catches binder code collisions (random Parcel ints look like negatives or terabytes). Beyond → `IMPLAUSIBLE_VALUE` → catalog walk continues. |
| D5 | Target: 1 shell/tick steady-state, max 5 cold | Multi-shell per tick | Medium tier is 2s; budget 1 shell. Binder path 50ms; dumpsys 1-2s only on cold probe. |
| D6 | SessionHistory defaults `networkAvailable=false` | `true` (FPower precedent) | Pre-v4.5.x sessions NEVER captured network — `false` is honest. Mirrors `gpuAvailable=false` default (v4.5.0 GPU precedent). |
| D7 | ONE line `acc.lastNetwork = adb.captureNetworkBandwidth(...)` in `runCaptureLoop` medium block | Separate coroutine (Option B from exploration) | H.7 just landed; preserves zero new `startCapture` branching. CCN stays ≤ 200. Medium-tier append guard `if (snap.networkAvailable && snap.rxKbps >= 0)` is 1 condition (same shape as FPower/GPU guards). |

## Data Flow

```
runCaptureLoop (medium tier, iterCount%4==0)
   └─→ adb.captureNetworkBandwidth(deviceId, pkg)
         └─→ AdbBridge.captureNetworkBandwidthImpl
               ├─ sticky? → cached terminal diag
               ├─ uid cache miss → cmd package list packages -U
               ├─ cold probe → multi-call binder shell → NetworkBandwidthParser
               ├─ all empty → dumpsys netstats detail --uid → parser
               └─ steady-state → cat cached method → parser → delta math
         └─→ NetworkSnapshot
   acc.lastNetwork = snap
   if (available && rxKbps >= 0) append to networkRxHistory/TxHistory
LiveMetrics.emit / SessionResult / HistoryEntry threading (parallel to gpu/fpower)
ReportGenerator.networkSection + networkDiagnosticBanner (5 reasons)
```

## Interfaces

```kotlin
// core/model/Metrics.kt
@Serializable
data class NetworkSnapshot(
    val rxKbps: Double = -1.0,
    val txKbps: Double = -1.0,
    val rxBytesTotal: Long = -1L,
    val txBytesTotal: Long = -1L,
    val networkAvailable: Boolean = false,
    val diagnostic: NetworkDiagnostic? = null,
)

// core/model/NetworkDiagnostic.kt
@Serializable
data class NetworkDiagnostic(
    val probedCodes: List<Int>,
    val winningMethod: String? = null, // "BINDER:11" | "DUMPSYS" | null
    val resolvedUid: Int? = null,
    val reason: NetworkUnavailableReason,
)

@Serializable
enum class NetworkUnavailableReason {
    UID_LOOKUP_FAILED,
    BINDER_UNAVAILABLE,
    DUMPSYS_PERMISSION_DENIED,
    DUMPSYS_FORMAT_UNKNOWN,
    ALL_PROBES_FAILED,
    IMPLAUSIBLE_VALUE,
    CAPTURE_THREW,
}

// core/NetworkVendorCatalog.kt
object NetworkVendorCatalog {
    val PROBE_CANDIDATES: List<NetworkProbeCandidate> = listOf(
        NetworkProbeCandidate(binderCode = 11, confidence = Confidence.HINT), // Android 11 (R)
        NetworkProbeCandidate(binderCode = 12, confidence = Confidence.HINT), // Android 12 (S)
        NetworkProbeCandidate(binderCode = 14, confidence = Confidence.HINT), // Android 13 (T)
        NetworkProbeCandidate(binderCode = 15, confidence = Confidence.HINT), // Android 14+
    )
    const val DUMPSYS_FALLBACK_CMD = "dumpsys netstats detail --uid"
    const val UID_RESOLVER_CMD = "cmd package list packages -U"
    const val MAX_PLAUSIBLE_BYTES = 100L * 1024 * 1024 * 1024 // 100GB
}

data class NetworkProbeCandidate(val binderCode: Int, val confidence: Confidence)
```

## Testing Strategy (TDD red→green, 7 phases ≤15min each)

| Phase | Focus | Key Tests |
|-------|-------|-----------|
| 1 | `NetworkSnapshot` + `NetworkDiagnostic` + enum | Data class equality, serialization round-trip, enum cardinality = 7 |
| 2 | `NetworkVendorCatalog` invariants | `PROBE_CANDIDATES.isNotEmpty()`, distinct binder codes, all `Confidence.HINT` (D3), max 5 entries |
| 3 | `NetworkBandwidthParser` pure | Binder hex fixture → (rxB, rxP, txB, txP); dumpsys fixture → bytes; negative cases → null; plausibility window rejection |
| 4 | `AdbBridge.captureNetworkBandwidth` via `FakeAdbBridge` | Cold probe walks catalog, caches winner; sticky failure returns cached diag; CAPTURE_THREW path; delta math from baseline tick |
| 5 | `AppViewModel` wire + `SessionHistory` round-trip | `runCaptureLoop` populates `acc.lastNetwork` at medium tier; history append guard; legacy `.gameperf` load defaults `networkAvailable=false` (D6) |
| 6 | `ReportGenerator.networkSection` + banner | 5-reason banner copy (Spanish tuteo formal); empty history → no section; available + history → numeric card |
| 7 | Detekt + docs | `./gradlew check` green; CCN ≤ 200; CHANGELOG/README ES+EN + GAMEBENCH-COMPARISON.md updated |

## Migration / Rollout

No migration. `SessionHistory` schema additive (5 new fields with defaults — `Json { ignoreUnknownKeys = true }` + Kotlin serialization default-field tolerance covers pre-v4.5.x rows). Single-feature, no flag; rollback = revert merge commit. Sticky-failure cache neutralises field-discovered vendor issues automatically.

## Risks + Mitigations

| Risk | Mitigation |
|------|------------|
| Binder code 11 unstable Android 11→14 | Catalog walk `[11, 12, 14, 15]` (D1), substring-unique FakeAdbBridge keys |
| `dumpsys netstats` slow (1-2s) on long history | Cold-probe only, sticky failure cache; never steady-state (D5) |
| Vendor binder renumbering (Samsung Knox, MIUI) | Catalog fallthrough → dumpsys |
| HINT confidence until lab-verified | Banner copy says "estimado"; promote to MEDIUM/HIGH after real-device captures (D3) |
| CCN regression above 200 | ONE line in `runCaptureLoop`, zero new `startCapture` branching (D7); subagent runs `./gradlew detekt` per task |
| Counter wraparound on long sessions | int64 → no wrap; first-tick baseline returns `rxKbps=-1.0` (no delta yet, mirrors Adreno gpubusy warm-up) |

## Out of Scope (explicit)

- Per-connection bandwidth (`api.unity3d.com:443`) — requires eBPF/libc hook, rejected per engram #289
- TTFB per endpoint
- Headers/payloads (MITM proxy)
- Per-interface wifi vs cellular split — deferred to v2
- Real-device lab verification across OEM matrix — HINT confidence v1; promote post-Sprint 2 telemetry

## Open Questions

None blocking. Real-device binder code validation deferred to post-release telemetry (HINT confidence intentional per D3).

## Next Step

Ready for `sdd-tasks`. Suggested batches: (1) models + catalog tests, (2) parser pure, (3) bridge orchestrator + fake, (4) AppViewModel wire + SessionHistory, (5) ReportGenerator + docs.
