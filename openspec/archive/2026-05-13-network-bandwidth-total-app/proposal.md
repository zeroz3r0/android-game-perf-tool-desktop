# Proposal: Network Bandwidth Total App (Sprint 2 — Issue #1 Gap 2)

## Intent

Close 60% of the remaining GameBench parity gap by adding per-tick total-app network bandwidth (RX + TX, scoped to the game's UID) to the Android capture pipeline. Network is the last metric required by Issue #1 Gap 2 (engram `roadmap/gamebench-parity` #289). H.7 refactor merged → `runCaptureLoop` + `CaptureAccumulators` exist, so Option A (recommended in exploration #416) is now unblocked.

## Scope

### In Scope
- `NetworkSnapshot` model + `NetworkDiagnostic` (5 reasons) in `core/model/Metrics.kt`
- `core/NetworkVendorCatalog.kt` — single source of truth for binder codes + dumpsys path
- `core/NetworkBandwidthParser.kt` — pure parser for `service call netstats` hex + `dumpsys netstats detail --uid` text
- `AdbBridge.captureNetworkBandwidth(deviceId, pkg)` — probe-once-then-cache pattern (mirrors `captureFPower`)
- Wire into existing `runCaptureLoop` medium-tier poll (every 4 ticks, ~2 s) via `acc.lastNetwork`
- `SessionHistory` SerializableEntry/Entry +5 fields (rxBytes, txBytes, networkAvailable, rx/tx history arrays, diagnostic)
- `ReportGenerator` HTML section + Spanish-tuteo unavailability banner per reason
- TDD: parser tests (pure) + bridge tests via `FakeAdbBridge`

### Out of Scope
- Per-connection breakdown (`api.unity3d.com:443`) — requires hook libc / eBPF
- TTFB per endpoint
- Headers/payloads (MITM proxy)
- Wifi vs cellular per-interface split — `service call netstats` doesn't expose; deferred to potential v2

## Capabilities

### New Capabilities
- `network-bandwidth`: Per-tick total-app network bandwidth capture (RX/TX bytes for the game UID) with vendor-fallback probe strategy and diagnostic banner.

### Modified Capabilities
- None. (FPower / GPU / thermal specs unaffected; medium-tier poll structure stable post-H.7.)

## Approach

Mirror the proven FPower probe-once-then-cache pattern with a vendor catalog.

1. **Cold probe (first medium tick)**: resolve UID via `cmd package list packages -U <pkg>` (cached). Walk `service call netstats <code> i32 <uid> i32 0` candidates `[11, 12, 14, 15]` from `NetworkVendorCatalog`; accept first response parsing as 4 plausible non-negative int64 (RX_BYTES, RX_PACKETS, TX_BYTES, TX_PACKETS). Cache winning code per-device. Fall back to `dumpsys netstats detail --uid <uid>` if all binder codes fail.
2. **Steady-state (every 4 ticks)**: 1 shell call with cached code+uid → parse → store cumulative `(rxBytes, txBytes)` → compute delta from previous tick → divide by elapsed seconds → emit `rxKbps`, `txKbps`.
3. **Sticky failure**: if both probes fail, cache `firstProbeFailed=true` and emit `networkAvailable=false` for session lifetime (no more shells).
4. **Wire**: ONE field `acc.lastNetwork`, ONE invocation inside `runCaptureLoop` medium-tier block, ONE history append guarded by `available && rxKbps >= 0`, ONE LiveMetrics emission. Zero new branching in `startCapture` body — that's the whole point of H.7.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `core/model/Metrics.kt` | Modified | Add `NetworkSnapshot` data class |
| `core/model/NetworkDiagnostic.kt` | New | Diagnostic + `NetworkUnavailableReason` enum |
| `core/NetworkVendorCatalog.kt` | New | Ordered binder-code candidates + dumpsys fallback |
| `core/NetworkBandwidthParser.kt` | New | Pure parser, hex + text formats |
| `core/AdbBridge.kt` | Modified | `captureNetworkBandwidth(deviceId, pkg)` + per-device state |
| `core/AdbBridgeApi.kt` | Modified | Add interface fn (bump threshold cap if needed) |
| `core/FakeAdbBridge.kt` | Modified | Test fake |
| `viewmodel/AppViewModel.kt` | Modified | `acc.lastNetwork` + 1 call + 1 history + LiveMetrics emit |
| `core/SessionHistory.kt` | Modified | +5 network fields on SerializableEntry/Entry |
| `report/ReportGenerator.kt` | Modified | Network section + banner |
| `src/test/.../core/network/*Test.kt` | New | Parser + catalog + bridge fake tests |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Binder transaction code unstable Android 11→14 | High | Catalog-of-candidates probe (mirrors Mali catalog) — accept first parsing as 4 plausible int64s |
| `dumpsys netstats` slow (1-2 s on long history) | Med | Use ONLY as cold-probe fallback, NEVER steady-state — sticky cache after first failure |
| Vendor binder renumbering (Samsung Knox, MIUI) | Med | Catalog walks; falls through to dumpsys |
| detekt CCN regression above 200 | Med | H.7 brought CCN back to 200 — MUST add zero new branching in `startCapture`. Wire via `acc.lastNetwork` only. Sub-agent verifies `./gradlew detekt` per task. |
| HINT-quality confidence until lab-verified across OEMs | Med | Mark `confidence=HINT` in catalog metadata (mirrors VR Sprint 4); banner copy says "estimado" |

## Rollback Plan

Single-feature behind no flag; revert by reverting the merge commit. No persisted schema breaks: new `SessionHistory` fields default to `-1L / false / null`, so older `.gameperf` files still load and new ones still load on older builds (Kotlin serialization tolerates missing fields with defaults). If the binder probe destabilises a vendor device in the field, the sticky-failure cache already neutralises it — diagnostic banner shows, other metrics keep working.

## Dependencies

- H.7 refactor merged (DONE — confirmed `runCaptureLoop` + `CaptureAccumulators` present in `AppViewModel.kt`)
- No external libraries — pure adb shell + Kotlin parser

## Success Criteria

- [ ] On a Pixel running Android 13+, network section renders with non-zero kbps during a 60s game session
- [ ] On a device with no working probe, banner appears with specific reason (one of 5 enum values), other metrics unaffected
- [ ] `./gradlew check` green; detekt CCN ≤ 200; no new warnings
- [ ] Probe-once-then-cache verified: max 5 shell calls on tick 1, max 1 shell call per medium tick steady-state
- [ ] Closes Issue #1 Gap 2 at "60% of gap" coverage per engram #289
