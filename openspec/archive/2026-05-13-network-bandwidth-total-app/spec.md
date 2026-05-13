# Network Bandwidth Specification (Delta)

## Purpose

Per-tick total-app network bandwidth capture (RX/TX bytes for game UID) with vendor-fallback probe strategy and diagnostic banner. Mirrors FPower/GPU probe-once-then-cache pattern. Closes Issue #1 Gap 2 (60% of GameBench parity gap).

## ADDED Requirements

### Requirement: NET-001 NetworkSnapshot data shape

The system MUST expose `NetworkSnapshot(rxBytes: Long = -1L, txBytes: Long = -1L, networkAvailable: Boolean = false, diagnostic: NetworkDiagnostic? = null)` in `core/model/Metrics.kt`. Sentinel `-1L` mirrors FPower/GPU precedent. Type MUST be `@Serializable` (kotlinx-serialization).

#### Scenario: Default-constructed snapshot

- GIVEN no probe has run
- WHEN `NetworkSnapshot()` is constructed with defaults
- THEN `rxBytes == -1L AND txBytes == -1L AND networkAvailable == false AND diagnostic == null`

#### Scenario: SessionHistory serialization round-trip

- GIVEN a `NetworkSnapshot(rxBytes=12345, txBytes=678, networkAvailable=true, diagnostic=null)` written to a `.gameperf` file via SessionHistory
- WHEN the file is deserialized on a build that omits the network fields (older client) AND on a build that includes them
- THEN both deserializations succeed; older builds ignore unknown fields; newer builds recover all 4 fields exactly

### Requirement: NET-002 NetworkDiagnostic enum

The system MUST define `NetworkUnavailableReason` with exactly 5 values: `ALL_PROBES_FAILED`, `DUMPSYS_PERMISSION_DENIED`, `BINDER_UNAVAILABLE`, `IMPLAUSIBLE_VALUE`, `CAPTURE_THREW`. `NetworkDiagnostic(reason: NetworkUnavailableReason, detail: String? = null)`.

#### Scenario: All five reasons present and serializable
- GIVEN each enum value
- WHEN wrapped in `NetworkDiagnostic` and serialized
- THEN JSON round-trip preserves the reason exactly

#### Scenario: No sixth value accepted
- GIVEN the enum
- WHEN tests enumerate `entries`
- THEN size MUST equal 5

### Requirement: NET-003 Probe candidate catalog single source

The system MUST expose `NetworkVendorCatalog.PROBE_CANDIDATES: List<NetworkProbeCandidate>` ordered binder-first then dumpsys-fallback. KDoc MUST warn against duplicating candidates elsewhere (mirrors `GpuVendorCatalog`).

#### Scenario: Catalog ordering invariant
- GIVEN `PROBE_CANDIDATES`
- WHEN inspected
- THEN every `BINDER`-kind entry MUST appear before any `DUMPSYS`-kind entry

#### Scenario: KDoc anti-duplication warning present
- GIVEN the file `NetworkVendorCatalog.kt`
- WHEN the KDoc is read
- THEN it MUST contain a warning that any probe addition belongs only here (cite v4.2.13 lesson)

### Requirement: NET-004 Binder transaction code catalog

The catalog MUST include multiple Android binder codes (`[11, 12, 14, 15]`) to absorb AOSP renumbering across Android 11–15 and vendor builds. Each candidate MAY carry `androidMinSdk: Int?`.

#### Scenario: Walks candidate codes until parse succeeds
- GIVEN binder candidates `[11, 12, 14, 15]` and a device where only code `14` returns valid output
- WHEN the probe walks the list
- THEN it accepts code `14` and caches it as the winning probe

#### Scenario: All binder codes fail → BINDER_UNAVAILABLE diagnostic
- GIVEN every binder code returns garbage
- WHEN walking completes with no parse hit
- THEN diagnostic reason MUST be `BINDER_UNAVAILABLE` and the dumpsys fallback runs next

### Requirement: NET-005 Pure parser for dumpsys netstats

`NetworkBandwidthParser.parseDumpsysNetstats(raw: String, uid: Int): Pair<Long, Long>?` MUST return `(rxBytes, txBytes)` summed across all buckets for the given UID, or `null` on absence/malformed input. Pure (no I/O), top-level regex.

#### Scenario: UID present
- GIVEN dumpsys output with `uid=10234 ... rxBytes=17086802 ... txBytes=1214969` across two buckets
- WHEN `parseDumpsysNetstats(raw, 10234)` is called
- THEN it returns `(sum_rx, sum_tx)` per UID

#### Scenario: UID absent or malformed
- GIVEN output missing the UID OR a line where `rxBytes=` is non-numeric
- WHEN parsed
- THEN it returns `null`

### Requirement: NET-006 Pure parser for binder service call response

`NetworkBandwidthParser.parseServiceCallResponse(raw: String): Pair<Long, Long>?` MUST decode 4 hex int64 words from `Result: Parcel(...)` output, returning `(rxBytes, txBytes)` (positions 0 and 2). Returns `null` if fewer than 4 hex words OR non-hex tokens.

#### Scenario: Well-formed parcel
- GIVEN `Result: Parcel(00000000 00000064 00000000 00000200)`
- WHEN parsed
- THEN it returns `(100L, 512L)`

#### Scenario: Malformed parcel
- GIVEN `Result: Parcel(XX YY)` or fewer than 4 hex words
- WHEN parsed
- THEN it returns `null`

### Requirement: NET-007 Probe-once-then-cache state

`AdbBridge.captureNetworkBandwidth(deviceId, pkg): NetworkSnapshot` MUST perform full probe (resolve UID + walk binder + maybe dumpsys) ONLY on first call per device per session, then cache the winning method. Subsequent calls MUST issue at most 1 shell command.

#### Scenario: First call probes; second call uses cached method
- GIVEN a fresh `FakeAdbBridge` with call counter at 0 AND working binder code `11`
- WHEN `captureNetworkBandwidth` is called twice in succession
- THEN tick 1 issues ≥ 2 shell calls (UID resolve + binder probe); tick 2 issues exactly 1 shell call

### Requirement: NET-008 Sticky failure

When all probes fail on first attempt, the bridge MUST set `firstProbeFailed=true` per device and on every subsequent call return `NetworkSnapshot(networkAvailable=false, diagnostic=cached)` WITHOUT issuing further shell calls.

#### Scenario: Sticky cache prevents re-shelling
- GIVEN tick 1 returned `ALL_PROBES_FAILED`
- WHEN `captureNetworkBandwidth` is called again
- THEN no new shell calls fire (FakeAdbBridge call counter unchanged) AND the snapshot reports `networkAvailable=false` with the cached diagnostic

### Requirement: NET-009 Try/catch resilience

`captureNetworkBandwidth` MUST wrap its full body in try/catch. Any thrown exception (IOException, RuntimeException, etc.) MUST yield `NetworkSnapshot(networkAvailable=false, diagnostic=NetworkDiagnostic(CAPTURE_THREW, detail=ex.message))`. No exception MAY propagate to the caller.

#### Scenario: FakeAdbBridge throws → CAPTURE_THREW snapshot
- GIVEN a `FakeAdbBridge` configured to throw `RuntimeException("boom")` on the shell call
- WHEN `captureNetworkBandwidth` is invoked
- THEN it returns `networkAvailable=false`, `diagnostic.reason == CAPTURE_THREW`, no propagation

### Requirement: NET-010 Plausibility window

Returned `rxBytes` / `txBytes` MUST be in `[0L, 100_000_000_000L]` (100 GB session ceiling). Out-of-range values MUST yield `NetworkSnapshot(networkAvailable=false, diagnostic=NetworkDiagnostic(IMPLAUSIBLE_VALUE, detail))`.

#### Scenario: Negative bytes rejected
- GIVEN a parsed result of `rxBytes = -1L`
- WHEN plausibility is applied
- THEN snapshot is unavailable with `IMPLAUSIBLE_VALUE`

#### Scenario: Over-100GB rejected
- GIVEN a parsed result of `rxBytes = 200_000_000_000L`
- WHEN plausibility is applied
- THEN snapshot is unavailable with `IMPLAUSIBLE_VALUE`
