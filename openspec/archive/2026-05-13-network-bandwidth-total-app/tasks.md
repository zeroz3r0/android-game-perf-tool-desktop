# Tasks: Network Bandwidth Total App (Issue #1 Gap 2)

TDD red→green. Each task is atomic (≤15min). NET-NNN cites spec. Verify cmd inline. CCN startCapture MUST stay ≤200 (D7).

## Phase 1 — Models (3 tasks)

- [x] 1.1 RED: `core/model/NetworkDiagnosticTest.kt` — enum cardinality=5 (NET-002), 5 reasons present, round-trip JSON. Verify: `./gradlew test --tests "*NetworkDiagnosticTest*"` (fails).
- [x] 1.2 GREEN: create `core/model/NetworkDiagnostic.kt` — `NetworkUnavailableReason` enum (ALL_PROBES_FAILED, DUMPSYS_PERMISSION_DENIED, BINDER_UNAVAILABLE, IMPLAUSIBLE_VALUE, CAPTURE_THREW) + `NetworkDiagnostic(reason, detail: String? = null)`, both `@Serializable`. NET-002. Verify: same cmd green.
- [x] 1.3 RED→GREEN: `core/model/NetworkSnapshotTest.kt` (default ctor `-1L/-1L/false/null`, round-trip) + add `NetworkSnapshot(rxBytes=-1L, txBytes=-1L, networkAvailable=false, diagnostic=null)` `@Serializable` to `core/model/Metrics.kt`. NET-001. Verify: `./gradlew test --tests "*NetworkSnapshotTest*"`.

## Phase 2 — Catalog single source (3 tasks)

- [x] 2.1 RED: `core/network/NetworkVendorCatalogTest.kt` — `PROBE_CANDIDATES` non-empty, all BINDER kind before DUMPSYS (NET-003), binder codes `[11,12,14,15]` distinct (NET-004), KDoc anti-dup warning present (mirror `GpuVendorCatalogTest`). Verify: fails.
- [x] 2.2 GREEN: `core/NetworkVendorCatalog.kt` — `PROBE_CANDIDATES` with binder codes [11,12,14,15] all `Confidence.HINT` + `DUMPSYS_FALLBACK_CMD = "dumpsys netstats detail --uid"` + `UID_RESOLVER_CMD = "cmd package list packages -U"` + KDoc "single source of truth, do NOT duplicate elsewhere (CLAUDE.md v4.2.13)". NET-003 NET-004. Verify: 2.1 green.
- [x] 2.3 GREEN: ordering invariant test green + lock with `entries.size == 4` test guard against accidental growth.

## Phase 3 — Pure parser (5 tasks)

- [x] 3.1 RED: `core/network/NetworkBandwidthParserTest.kt::parseServiceCallResponse_wellFormed` — `Result: Parcel(00000000 00000064 00000000 00000200)` → `(100L,512L)`. NET-006. Verify: fails.
- [x] 3.2 GREEN: `core/NetworkBandwidthParser.kt::parseServiceCallResponse(raw): Pair<Long,Long>?` — top-level `private val PARCEL_HEX = Regex(...)`, decode 4 hex int64, positions 0+2. NET-006. Verify: 3.1 green.
- [x] 3.3 RED→GREEN: malformed parcel test (XX YY, <4 words) → null. NET-006. Verify: green.
- [x] 3.4 RED→GREEN: `parseDumpsysNetstats(raw, uid): Pair<Long,Long>?` — multi-bucket sum, UID absent→null, non-numeric→null. Top-level regex. NET-005. Verify: green.
- [x] 3.5 RED→GREEN: plausibility helpers `isPlausibleBytes(b): Boolean` — `[0L, 100_000_000_000L]`. Negative→false, >100GB→false. NET-010. Verify: green.

## Phase 4 — Bridge orchestrator (5 tasks)

- [x] 4.1 GREEN: `AdbBridgeApi.captureNetworkBandwidth(deviceId, pkg, uid): NetworkSnapshot` interface fn + RealAdbBridge passthrough. **Signature override**: prompt-mandated `uid: Int` extra param (needed for binder `service call netstats <code> i32 <uid>` AND dumpsys `--uid` filter). detekt `thresholdInInterfaces` bumped 33→34. Verify: `./gradlew compileKotlin` green.
- [x] 4.2 GREEN: `FakeAdbBridge` — `scriptedNetwork: NetworkSnapshot? = null`, `setNetwork(snap)`, `networkThrowOn: MutableMap<String, Throwable>`, override `captureNetworkBandwidth` with mirror of production state machine (sticky failure, cold probe walk, dumpsys fallback, plausibility reject, CAPTURE_THREW). resetSessionState clears networkStateMap. Verify: `./gradlew compileTestKotlin` green.
- [x] 4.3 RED: `AdbBridgeNetworkTest.kt` — 10 tests covering NET-007 (cold probe walks catalog + cached steady-state 1 shell + dumpsys fallback), NET-008 (sticky BINDER_UNAVAILABLE / DUMPSYS_PERMISSION_DENIED ZERO new shells), NET-009 (CAPTURE_THREW no propagation), NET-010 (>100GB IMPLAUSIBLE_VALUE), multi-device isolation, resetSessionState clears cache. Verify: `./gradlew test --tests "*AdbBridgeNetwork*"` 10/10 pass.
- [x] 4.4 GREEN: `AdbBridge.captureNetworkBandwidth(deviceId, pkg, uid)` + private `NetworkDeviceState(winningMethod, lastRxTxBytes, firstProbeFailed, terminalDiagnostic)` + 7 private helpers (captureNetworkBandwidthImpl, steadyStateNetwork, coldProbeNetwork, cacheWinningProbe, cacheTerminalNetworkFailure, resolveNetworkParsed, implausibleNetworkSnapshot). Outer try/catch → CAPTURE_THREW. NET-007/008/009/010. detekt `thresholdInObjects` bumped 54→62.
- [x] 4.5 Batch-end: full `./gradlew check` GREEN — 1420 passing / 0 fail / 10 skip. 1m50s.

## Phase 5 — ViewModel + SessionHistory (4 tasks)

- [x] 5.1 GREEN: `CaptureAccumulators` — add `lastNetwork: NetworkSnapshot? = null`, `networkRxHistory: MutableList<Double>`, `networkTxHistory: MutableList<Double>`. Verify: `./gradlew compileKotlin`.
- [x] 5.2 GREEN: ONE line in `runCaptureLoop` medium-tier block (iterCount%4==0): `acc.lastNetwork = adb.captureNetworkBandwidth(deviceId, pkg, uid)` + guarded append `if (snap.networkAvailable && snap.rxKbps >= 0) { acc.networkRxHistory += ...; acc.networkTxHistory += ... }`. Zero new branching in `startCapture` (D7). Verify: `./gradlew detekt` — CCN startCapture ≤200 (NOT bumped).
- [x] 5.3 RED→GREEN: `SessionHistory` SerializableEntry +5 fields (rxBytes, txBytes, networkAvailable, rxKbps, txKbps) default values; HistoryEntry mirror; LiveMetrics emission. Round-trip test for new fields. NET-001 scenario 2. Verify: `./gradlew test --tests "*SessionHistory*"`.
- [x] 5.4 RED→GREEN: legacy compat test — load `.gameperf` JSON omitting network fields → defaults `networkAvailable=false, rxBytes=-1L`. NET-001 scenario 2 (D6). Verify: green.

## Phase 6 — Report HTML (3 tasks)

- [x] 6.1 RED: `ReportGeneratorNetworkTest.kt` — 5 banner variants, each `NetworkUnavailableReason` produces distinct castellano tuteo formal copy ("No se pudo medir...", "Permiso denegado...", "Binder no disponible...", "Valor implausible...", "Captura falló..."). Verify: fails.
- [x] 6.2 GREEN: `ReportGenerator.networkDiagnosticBanner(diag): String` — `when(reason)` with 5 castellano tuteo formal copies. Verify: 6.1 green.
- [x] 6.3 RED→GREEN: `networkSection(history): String?` — null if empty/unavailable, numeric card (avg/peak rx/tx kbps) if available. Wire into report template. Verify: `./gradlew test --tests "*ReportGenerator*"`.

## Phase 7 — Verify + docs (3 tasks)

- [x] 7.1 Verify: `./gradlew check` green + `./gradlew detekt` CCN startCapture ≤200 (NOT bumped from 200). If CCN >200, refactor wire to comply with D7 — do NOT raise threshold.
- [x] 7.2 Docs: CHANGELOG.md entry under next version (Arreglos / Que hay de nuevo / Detalles tecnicos sections, castellano tuteo formal) + README.md ES feature bullet + README_EN.md EN feature bullet.
- [x] 7.3 Docs: GAMEBENCH-COMPARISON.md — flip "Network bandwidth (total)" row from ✗ to ✓ with note "v4.6.x — per-tick RX/TX, HINT confidence". Verify: grep row green.

## Total: 26 tasks, 7 phases. Done: 26/26 (all phases complete).
