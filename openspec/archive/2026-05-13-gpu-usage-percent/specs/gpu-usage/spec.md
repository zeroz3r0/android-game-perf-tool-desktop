# Spec: GPU Usage — Android sysfs per-tick utilization

This spec defines the GPU usage percentage capability for Android capture sessions in Sprint 1 of the GameBench-parity roadmap. It is a NEW capability — no prior `gpu-usage` spec exists. The implementation reads GPU utilization from kernel sysfs via `adb shell`, supports ARM Mali and Qualcomm Adreno vendors directly, gracefully degrades for PowerVR (MediaTek / Unisoc) until Sprint 1.5 crowdsource paths fill the catalog, and surfaces unavailability with a Spanish (tuteo-formal) diagnostic banner in the report. See `proposal.md` for context, GameBench positioning, and risk analysis.

Conventions (match `openspec/specs/core/spec.md`):
- Requirement IDs are stable and code-referenceable. They map directly to test names.
- Requirement statements use EARS keywords (SHALL, MUST, WHEN, WHILE, WHERE, IF/THEN).
- Scenarios use Given/When/Then for testability and MUST be implementable as pure-parser inline-heredoc fixture tests or `FakeAdbBridge.shellResponses`-driven bridge tests. No mocks. No new test deps.
- User-facing text in scenarios is in Castilian Spanish formal **tuteo** per project convention (`CLAUDE.md`).

Honest positioning constraint (from proposal v2): we read sysfs only. We do NOT promise driver-perfcounter accuracy. The sub-counters (Vertex Load / Pixel Load) GameBench exposes are out of Sprint 1 scope.

---

## ADDED Requirements

## 1. Vendor Detection and Probe Order

### Requirement: GPU-001 — Single-shell vendor probe

The system SHALL detect the GPU vendor and the winning sysfs path with a SINGLE `adb shell` invocation per device per session, using an inline shell `for ... cat ...` loop that iterates the candidate path list from `GpuVendorCatalog`.

#### Scenario: Mali path hit on first probe

- GIVEN a Mali device where `/sys/class/misc/mali0/device/utilization` returns `47`
- WHEN `AdbBridge.captureGpuUsage(deviceId)` is invoked for the first time on this device
- THEN exactly ONE `adb shell` invocation MUST be issued (the inline-loop probe)
- AND the bridge cache MUST record `vendor=MALI` and `winningPath=/sys/class/misc/mali0/device/utilization`

#### Scenario: Adreno path hit when Mali absent

- GIVEN a device where Mali probes return empty
- AND `/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage` returns `42`
- WHEN `captureGpuUsage(deviceId)` is invoked for the first time
- THEN the bridge cache MUST record `vendor=ADRENO` and `winningPath=/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage`

#### Scenario: All probes empty → PowerVR best-effort attempted, then unavailable

- GIVEN a device where Mali, Adreno `gpu_busy_percentage`, Adreno `gpubusy` all return empty
- AND PowerVR placeholder candidates also return empty
- AND the Adreno `echo 1 > /sys/class/kgsl/kgsl-3d0/perfcounter` enable attempt is NOT applicable (no Adreno paths existed at all)
- WHEN `captureGpuUsage(deviceId)` is invoked
- THEN the returned `GpuSnapshot.gpuAvailable` MUST be `false`
- AND `GpuSnapshot.diagnostic.reason` MUST be `POWERVR_UNSUPPORTED` (best guess — vendor unknown, no GPU sysfs found)
- AND `GpuSnapshot.diagnostic.probedPaths` MUST contain the full ordered probe list (capped at 10)

---

### Requirement: GPU-002 — Bridge cache prevents re-probing

The system MUST cache the winning vendor + path per `deviceId` after the first successful probe. Subsequent ticks SHALL issue ONLY a single `cat <winningPath>` invocation, never the full inline-loop probe.

#### Scenario: Subsequent tick reuses cached path

- GIVEN the bridge cache holds `vendor=MALI, winningPath=/sys/class/misc/mali0/device/utilization` for `deviceId=ABC123`
- WHEN `captureGpuUsage("ABC123")` is invoked for the second time
- THEN the `adb shell` command issued MUST be `cat /sys/class/misc/mali0/device/utilization` (NOT the inline-loop probe)
- AND no other path SHALL be consulted

#### Scenario: All-probes-failed device never re-probes

- GIVEN a device where `captureGpuUsage` already returned `gpuAvailable=false` with `reason=POWERVR_UNSUPPORTED`
- AND the bridge cache records `vendor=UNAVAILABLE` for that device
- WHEN `captureGpuUsage(deviceId)` is invoked again on the next tick
- THEN the system MUST NOT re-issue the probe
- AND it MUST immediately return a cached `GpuSnapshot(usagePct=-1, gpuAvailable=false, diagnostic=<same as before>)`

---

## 2. Mali Read-Flow

### Requirement: GPU-003 — Mali single-int utilization parse

WHERE the cached vendor is `MALI`, the system SHALL read the winning path with `cat <path>` and SHALL parse the stdout as a single integer in `[0, 100]` representing the kernel-computed utilization percentage. No delta math is performed.

#### Scenario: Mali well-formed reading

- GIVEN the cached Mali path returns the string `"73"` (with optional trailing newline)
- WHEN `GpuUsageParser.parseMali(stdout)` is invoked
- THEN it MUST return `GpuSnapshot(usagePct=73, gpuAvailable=true, diagnostic=null)`

#### Scenario: Mali out-of-range value rejected

- GIVEN the cached Mali path returns the string `"110"`
- WHEN `GpuUsageParser.parseMali(stdout)` is invoked
- THEN it MUST return `GpuSnapshot(usagePct=-1, gpuAvailable=false, diagnostic.reason=OUT_OF_RANGE_VALUE)`
- AND `diagnostic.vendorAttempted` MUST be `"MALI"`

#### Scenario: Mali empty stdout treated as transient unavailability

- GIVEN the cached Mali path returns an empty string
- WHEN `GpuUsageParser.parseMali(stdout)` is invoked
- THEN it MUST return `GpuSnapshot(usagePct=-1, gpuAvailable=false, diagnostic.reason=ALL_PATHS_EMPTY)`

---

### Requirement: GPU-004 — Mali path catalog

The system MUST expose, in `GpuVendorCatalog`, a Mali candidate path list that includes at minimum the canonical `/sys/class/misc/mali0/device/utilization`, the historical typo alternate `/sys/class/misc/mali0/device/utility`, and the platform-bus alternate `/sys/devices/platform/*/mali.0/utilization`. The list MUST be the single source of truth for Mali probing (no inline duplicates anywhere).

#### Scenario: Catalog is the single source of truth

- GIVEN the codebase
- WHEN any module needs the Mali candidate paths
- THEN it MUST read from `core/GpuVendorCatalog.kt`
- AND no duplicate Mali path constants MUST exist elsewhere (per `CLAUDE.md` anti-duplication rule, same lesson as `SdkSignatureCatalog` and `ToolResolver`)

---

## 3. Adreno Read-Flow (Option B: probe-then-enable-then-retry-then-graceful-fail)

### Requirement: GPU-005 — Adreno probe-order preference

WHERE the cached vendor is `ADRENO`, the system SHALL prefer `/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage` (kernel-computed single integer percent, no delta math) over `/sys/class/kgsl/kgsl-3d0/gpubusy` (cumulative `"busy total"` counters requiring delta math).

#### Scenario: GPU-005.1 — gpu_busy_percentage available, no delta needed

- GIVEN the cached Adreno path is `/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage`
- AND the path returns the string `"38"`
- WHEN `GpuUsageParser.parseAdrenoBusyPercentage(stdout)` is invoked
- THEN it MUST return `GpuSnapshot(usagePct=38, gpuAvailable=true, diagnostic=null)`
- AND the bridge cache MUST NOT record any `lastBusyTotal` (delta math is not used on this path)

---

### Requirement: GPU-006 — Adreno gpubusy delta math

WHERE the cached Adreno path is `/sys/class/kgsl/kgsl-3d0/gpubusy`, the system SHALL parse the stdout as two whitespace-separated `Long` values (`busy total`) and SHALL compute usage as `((deltaBusy * 100) / deltaTotal)` clamped to `[0, 100]`, using the previous tick's `(busy, total)` cached on the bridge per device.

#### Scenario: GPU-006.1 — First tick warm-up (no prior baseline)

- GIVEN no prior `lastBusyTotal` is cached for `deviceId=DEV1`
- AND `gpubusy` returns `"1000 10000"`
- WHEN `captureGpuUsage("DEV1")` is invoked for the first time on `gpubusy`
- THEN the returned `GpuSnapshot.gpuAvailable` MUST be `false`
- AND `GpuSnapshot.diagnostic.reason` MUST be `NOT_PROBED_YET`
- AND the bridge cache MUST store `lastBusyTotal=(1000L, 10000L)` for `DEV1`

#### Scenario: GPU-006.2 — Second tick computes delta

- GIVEN the bridge cache holds `lastBusyTotal=(1000L, 10000L)` for `DEV1`
- AND `gpubusy` now returns `"2500 30000"`
- WHEN `captureGpuUsage("DEV1")` is invoked
- THEN `GpuUsageParser.parseAdrenoGpubusy(prev=(1000,10000), curr=(2500,30000))` MUST return `usagePct = ((2500-1000)*100) / (30000-10000) = 7`
- AND the returned `GpuSnapshot` MUST be `(usagePct=7, gpuAvailable=true, diagnostic=null)`
- AND the bridge cache MUST update to `lastBusyTotal=(2500L, 30000L)`

#### Scenario: GPU-006.3 — Counter wraparound discards sample

- GIVEN the bridge cache holds `lastBusyTotal=(5000L, 50000L)` for `DEV1`
- AND `gpubusy` returns `"100 60000"` (busy went DOWN — wraparound or counter reset)
- WHEN `captureGpuUsage("DEV1")` is invoked
- THEN the returned `GpuSnapshot.gpuAvailable` MUST be `false`
- AND `GpuSnapshot.diagnostic.reason` MUST be `COUNTER_WRAPAROUND`
- AND the bridge cache MUST update `lastBusyTotal=(100L, 60000L)` so the NEXT tick can compute a fresh delta

#### Scenario: GPU-006.4 — Zero total delta discards sample

- GIVEN the bridge cache holds `lastBusyTotal=(5000L, 50000L)` for `DEV1`
- AND `gpubusy` returns `"5000 50000"` (`deltaTotal == 0` — idle / post-boot edge)
- WHEN `captureGpuUsage("DEV1")` is invoked
- THEN `GpuUsageParser.parseAdrenoGpubusy` MUST return `gpuAvailable=false` with `reason=COUNTER_WRAPAROUND` (deltaTotal ≤ 0 is conflated with wraparound for the same plausibility guard)
- AND the bridge cache MUST keep `lastBusyTotal=(5000L, 50000L)` unchanged so the next non-zero delta still computes correctly

---

### Requirement: GPU-007 — Adreno perfcounter enable lifecycle (Option B)

WHEN both Adreno probes (`gpu_busy_percentage` AND `gpubusy`) return empty on the same tick, the system SHALL attempt to enable the perfcounter via `adb shell "echo 1 > /sys/class/kgsl/kgsl-3d0/perfcounter"`. IF the echo succeeds, the system MUST mark `perfcounterEnabledByUs=true` on the bridge cache for that `deviceId` and SHALL retry the Adreno probe on the NEXT tick (not the same tick — avoids head-of-line blocking the capture loop). IF the echo fails (non-zero exit, SELinux denial, EACCES), the system MUST return `gpuAvailable=false` with `reason=ADRENO_PERFCOUNTER_DISABLED` and MUST cache that failure so subsequent ticks never retry the enable.

#### Scenario: GPU-007.1 — Both probes empty, enable succeeds, retry next tick

- GIVEN cached vendor is `ADRENO`
- AND `gpu_busy_percentage` returns empty
- AND `gpubusy` returns empty
- AND `echo 1 > /sys/class/kgsl/kgsl-3d0/perfcounter` exits with code 0
- WHEN `captureGpuUsage(deviceId)` is invoked on tick N
- THEN the returned `GpuSnapshot.gpuAvailable` MUST be `false`
- AND `GpuSnapshot.diagnostic.reason` MUST be `NOT_PROBED_YET` (the enable just happened — the perfcounter needs the next read cycle to populate)
- AND the bridge cache MUST set `perfcounterEnabledByUs=true` for that `deviceId`
- AND on tick N+1 the Adreno probe MUST be retried (and may succeed on `gpubusy` warm-up at tick N+2)

#### Scenario: GPU-007.2 — Echo fails → ADRENO_PERFCOUNTER_DISABLED, no further retries

- GIVEN cached vendor is `ADRENO`
- AND both Adreno probes return empty
- AND `echo 1 > /sys/class/kgsl/kgsl-3d0/perfcounter` exits non-zero (SELinux denial)
- WHEN `captureGpuUsage(deviceId)` is invoked
- THEN the returned `GpuSnapshot.gpuAvailable` MUST be `false`
- AND `GpuSnapshot.diagnostic.reason` MUST be `ADRENO_PERFCOUNTER_DISABLED`
- AND `GpuSnapshot.diagnostic.probedPaths` MUST list both Adreno probe paths
- AND `GpuSnapshot.diagnostic.vendorAttempted` MUST be `"ADRENO"`
- AND the bridge cache MUST mark this device as terminal-unavailable so subsequent ticks return the cached failure without re-issuing the echo

#### Scenario: GPU-007.3 — resetSessionState best-effort disable

- GIVEN the bridge cache holds `perfcounterEnabledByUs=true` for `deviceId=DEV1`
- WHEN `AdbBridge.resetSessionState()` is invoked
- THEN the bridge MUST issue `adb shell "echo 0 > /sys/class/kgsl/kgsl-3d0/perfcounter"` for `DEV1` (best-effort side-effect mitigation)
- AND any failure of that echo MUST be swallowed silently (NOT a hard contract — documented in test)
- AND the bridge cache MUST clear all GPU state for `DEV1` including `perfcounterEnabledByUs`

#### Scenario: GPU-007.4 — resetSessionState does not echo 0 when we never enabled

- GIVEN the bridge cache holds `perfcounterEnabledByUs=false` for `deviceId=DEV2` (the Adreno path was already working without us enabling anything, OR the device is Mali, OR the device is unavailable)
- WHEN `AdbBridge.resetSessionState()` is invoked
- THEN the bridge MUST NOT issue any `echo 0 > perfcounter` command for `DEV2`
- AND the bridge cache MUST still be cleared for `DEV2`

---

### Requirement: GPU-008 — Adreno path catalog

`GpuVendorCatalog` MUST define the Adreno candidate paths in this exact preference order: `/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage` first, then `/sys/class/kgsl/kgsl-3d0/gpubusy`. The catalog MUST also expose the perfcounter enable path `/sys/class/kgsl/kgsl-3d0/perfcounter` as a separate named constant (NOT in the read-probe list).

#### Scenario: Catalog preference order is stable

- GIVEN `GpuVendorCatalog.adrenoReadCandidates`
- WHEN the list is inspected
- THEN the first element MUST be `/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage`
- AND the second element MUST be `/sys/class/kgsl/kgsl-3d0/gpubusy`
- AND `GpuVendorCatalog.adrenoPerfcounterEnablePath` MUST equal `/sys/class/kgsl/kgsl-3d0/perfcounter`

---

## 4. PowerVR Graceful Degradation

### Requirement: GPU-009 — PowerVR best-effort then unavailable

WHERE Mali and Adreno probes have all returned empty, the system MAY attempt the PowerVR placeholder paths defined in `GpuVendorCatalog.powervrCandidates`. All PowerVR candidates MUST be flagged `confidence=LOW` in the catalog (no publicly verified path exists as of Sprint 1). IF any PowerVR probe returns a plausible integer in `[0, 100]`, it MAY be returned with `gpuAvailable=true`. IF all PowerVR probes also return empty, the system SHALL return `gpuAvailable=false` with `reason=POWERVR_UNSUPPORTED` and the full probed-paths list (capped at 10) for Sprint 1.5 crowdsourcing.

#### Scenario: PowerVR all probes empty → unavailable with diagnostic

- GIVEN Mali probes returned empty
- AND Adreno probes returned empty
- AND the `echo 1 > perfcounter` attempt was NOT applicable (no Adreno path existed)
- AND all PowerVR placeholder probes returned empty
- WHEN `captureGpuUsage(deviceId)` is invoked
- THEN the returned `GpuSnapshot.gpuAvailable` MUST be `false`
- AND `GpuSnapshot.diagnostic.reason` MUST be `POWERVR_UNSUPPORTED`
- AND `GpuSnapshot.diagnostic.probedPaths.size` MUST be ≤ 10
- AND `GpuSnapshot.diagnostic.probedPaths` MUST include at least one Mali path, one Adreno path, and one PowerVR placeholder path so the user-submitted bug report identifies the device's GPU sysfs surface

---

## 5. Snapshot and Diagnostic Models

### Requirement: GPU-010 — GpuSnapshot data class

The system MUST add a `GpuSnapshot` data class to `core/model/Metrics.kt` (next to `ThermalSnapshot`) with exactly these fields:
- `usagePct: Int` — `[0, 100]` when available; `-1` sentinel when unavailable (mirrors `ThermalSnapshot.dieCpu = -1.0` pattern, adapted for `Int`).
- `gpuAvailable: Boolean = true` — default `true` for backward-compat-friendly construction.
- `diagnostic: GpuDiagnostic? = null` — non-null IFF `gpuAvailable=false`.

The class MUST be annotated `@Serializable` so it survives `.gameperf` round-trip with default-tolerant decoding for sessions exported before this change.

#### Scenario: Happy-path snapshot construction

- GIVEN a Mali device returning `"55"`
- WHEN `GpuUsageParser.parseMali("55")` is invoked
- THEN the returned `GpuSnapshot` MUST equal `GpuSnapshot(usagePct=55, gpuAvailable=true, diagnostic=null)`

#### Scenario: Unavailable snapshot carries diagnostic

- GIVEN a PowerVR device with all probes empty
- WHEN `captureGpuUsage(deviceId)` is invoked
- THEN the returned `GpuSnapshot.usagePct` MUST be `-1`
- AND `GpuSnapshot.gpuAvailable` MUST be `false`
- AND `GpuSnapshot.diagnostic` MUST NOT be `null`

---

### Requirement: GPU-011 — GpuDiagnostic data class

The system MUST add `core/model/GpuDiagnostic.kt` (separate file, mirroring `ThermalDiagnostic.kt`). It MUST be `@Serializable` and contain exactly:
- `reason: GpuUnavailableReason`
- `probedPaths: List<String>` — MUST be capped at 10 entries by callers before construction
- `vendorAttempted: String?` — `"MALI"`, `"ADRENO"`, `"POWERVR"`, or `null` when the probe order never reached a vendor-attribution stage
- `messageEs: String? = null` — optional pre-rendered Spanish (tuteo-formal) one-liner for the report banner; when `null` the report MUST render a fallback derived from `reason`

#### Scenario: Probed-paths cap is enforced

- GIVEN a hypothetical bridge that probed 15 candidate paths
- WHEN the bridge constructs a `GpuDiagnostic`
- THEN it MUST truncate `probedPaths` to the first 10 entries before construction
- AND the truncation MUST be documented in the report (e.g., footnote "lista truncada a 10 entradas")

---

### Requirement: GPU-012 — GpuUnavailableReason enum

The system MUST add `GpuUnavailableReason` (in `GpuDiagnostic.kt`) as a `@Serializable enum class` with EXACTLY these cases:
- `NOT_PROBED_YET` — Adreno first tick (no `lastBusyTotal` baseline yet), OR perfcounter just enabled, awaiting next tick
- `MALI_NOT_FOUND` — Mali probes exhausted (used internally during cascade; rarely surfaced standalone)
- `ADRENO_NOT_FOUND` — Adreno probes exhausted before enable was attempted (used internally; rarely surfaced standalone)
- `ADRENO_PERFCOUNTER_DISABLED` — Adreno empty + echo enable failed (A13+ locked OEMs)
- `POWERVR_UNSUPPORTED` — terminal: no vendor recognized, sysfs surface unknown (PowerVR catalog gap or other vendor)
- `ALL_PATHS_EMPTY` — generic empty-stdout case used by the pure parser before the bridge attaches vendor attribution
- `OUT_OF_RANGE_VALUE` — parser saw a number outside `[0, 100]` (plausibility guard)
- `COUNTER_WRAPAROUND` — Adreno gpubusy delta math saw `deltaBusy < 0` OR `deltaTotal ≤ 0`

#### Scenario: Enum is exhaustive and stable

- GIVEN `GpuUnavailableReason.values()`
- WHEN the list is inspected
- THEN it MUST contain exactly the 8 cases listed above (no more, no less, in any order)
- AND each case MUST be stable string-named (renaming requires a follow-up spec change)

---

## 6. Stateful Bridge Cache

### Requirement: GPU-013 — Per-device GPU state map

The system MUST add a per-`deviceId` GPU state cache to `AdbBridge` (mirroring the existing `pidStateMap` precedent used by `captureCpuPercent(deviceId, pkg)`). The cache MUST hold:
- `vendor: GpuVendor?` — `MALI`, `ADRENO`, `POWERVR`, `UNAVAILABLE`, or `null` (pre-probe)
- `winningPath: String?` — populated after first successful probe
- `lastBusyTotal: Pair<Long, Long>?` — Adreno gpubusy delta state; `null` for Mali / `gpu_busy_percentage` / unavailable
- `perfcounterEnabledByUs: Boolean = false` — set `true` IFF this session's bridge issued the `echo 1 > perfcounter`
- `terminalDiagnostic: GpuDiagnostic?` — non-null IFF the device is permanently unavailable for this session; subsequent ticks return this without re-probing

#### Scenario: Cache structure is per-device

- GIVEN two connected devices `DEV1` (Mali) and `DEV2` (Adreno with perfcounter enable)
- WHEN `captureGpuUsage` has been called once on each
- THEN `gpuStateMap["DEV1"]` MUST hold `vendor=MALI, perfcounterEnabledByUs=false`
- AND `gpuStateMap["DEV2"]` MUST hold `vendor=ADRENO, perfcounterEnabledByUs=true`
- AND the two entries MUST be independent (no cross-device leakage)

---

### Requirement: GPU-014 — resetSessionState clears GPU state and best-effort-disables perfcounter

WHEN `AdbBridge.resetSessionState()` is invoked, the system MUST iterate every entry in the GPU state map and, for each entry with `perfcounterEnabledByUs=true`, issue a best-effort `echo 0 > /sys/class/kgsl/kgsl-3d0/perfcounter` to that device. Failures of these echo-disables MUST be swallowed silently (logged at debug level only, never thrown). After the iteration, the GPU state map MUST be cleared in its entirety.

#### Scenario: Reset disables for all perfcounter-enabled devices

- GIVEN `gpuStateMap` holds 3 entries: `DEV1 (perfcounterEnabledByUs=true)`, `DEV2 (perfcounterEnabledByUs=true)`, `DEV3 (perfcounterEnabledByUs=false)`
- WHEN `resetSessionState()` is invoked
- THEN the bridge MUST issue `echo 0 > .../perfcounter` to `DEV1`
- AND `echo 0 > .../perfcounter` to `DEV2`
- AND it MUST NOT issue any echo to `DEV3`
- AND after the calls return (regardless of their exit status), `gpuStateMap.isEmpty()` MUST be `true`

#### Scenario: Reset survives one echo failing

- GIVEN `gpuStateMap` holds `DEV1` with `perfcounterEnabledByUs=true`
- AND the simulated `echo 0` to `DEV1` exits non-zero (SELinux denial during teardown)
- WHEN `resetSessionState()` is invoked
- THEN no exception MUST escape `resetSessionState()`
- AND `gpuStateMap.isEmpty()` MUST be `true`

---

## 7. Capture Loop Wiring

### Requirement: GPU-015 — 4-tick poll cadence

The system MUST poll `captureGpuUsage(deviceId)` from `AppViewModel` every 4 capture-loop ticks (~2 seconds at the current 500 ms base tick), mirroring the thermal poll cadence at lines L1177-1196 of `viewmodel/AppViewModel.kt`. The GPU poll MUST run on the same `Dispatchers.IO` coroutine sibling as thermal.

#### Scenario: Poll fires every 4 ticks

- GIVEN a running capture session at base tick rate 500 ms
- WHEN the loop has executed 12 ticks (6 seconds)
- THEN `captureGpuUsage` MUST have been invoked exactly 3 times (ticks 4, 8, 12)
- AND no GPU poll MUST have been issued on ticks 1, 2, 3, 5, 6, 7, 9, 10, 11

#### Scenario: First valid Adreno delta available at tick 8

- GIVEN an Adreno device on the `gpubusy` fallback path (no `gpu_busy_percentage`)
- WHEN the capture session has run for 8 ticks (~4 seconds)
- THEN the first `captureGpuUsage` call (tick 4) MUST have returned `gpuAvailable=false, reason=NOT_PROBED_YET` (baseline established)
- AND the second `captureGpuUsage` call (tick 8) MUST be the first to return a valid `usagePct ∈ [0, 100]`
- AND the report-rendering layer MUST document this ~4-second warm-up in a footnote or tooltip

---

### Requirement: GPU-016 — Last-known snapshot exposed on ViewModel

The system MUST add a `lastGpu: MutableState<GpuSnapshot>` (or equivalent reactive holder matching the `lastThermal` pattern at `AppViewModel.kt:L1107`) initialized to `GpuSnapshot(usagePct=-1, gpuAvailable=false, diagnostic=GpuDiagnostic(reason=NOT_PROBED_YET, probedPaths=emptyList(), vendorAttempted=null))`. The poll MUST update `lastGpu` additively (preserves `gpuAvailable`+`diagnostic` across no-op ticks), mirroring how `lastThermal` is updated.

#### Scenario: Initial state shows NOT_PROBED_YET

- GIVEN `AppViewModel` has just been constructed and no capture has started
- WHEN `viewModel.lastGpu.value` is read
- THEN it MUST equal `GpuSnapshot(usagePct=-1, gpuAvailable=false, diagnostic.reason=NOT_PROBED_YET)`

---

## 8. Persistence

### Requirement: GPU-017 — Session payload extension

The persisted `.gameperf` session payload MUST add exactly these GPU fields (all defaulted for backward-compat decoding of pre-Sprint-1 exports):
- `gpuAvailable: Boolean = false` (default `false` so pre-Sprint-1 sessions render a "no data" banner gracefully)
- `gpuDiagnostic: GpuDiagnostic? = null`
- `gpuUsageHistory: List<Int> = emptyList()` — positional, mirrors `tempCpuHistory`
- `gpuUsageTimed: List<TimedSample> = emptyList()` — timed twin per FLT-001 pattern from `core/spec.md`

#### Scenario: Pre-Sprint-1 session deserializes cleanly

- GIVEN a `.gameperf` file exported under v4.4.x (before GPU support)
- WHEN it is loaded under the post-Sprint-1 build
- THEN deserialization MUST succeed without throwing
- AND `session.gpuAvailable` MUST be `false`
- AND `session.gpuDiagnostic` MUST be `null`
- AND `session.gpuUsageHistory` MUST be `emptyList()`
- AND `session.gpuUsageTimed` MUST be `emptyList()`

#### Scenario: Post-Sprint-1 round-trip preserves GPU fields

- GIVEN a session with `gpuAvailable=true`, `gpuUsageHistory=[10, 25, 50, 75]`, `gpuUsageTimed=[TimedSample(500, 10), ...]`
- WHEN the session is exported, then re-imported
- THEN every GPU field MUST round-trip byte-for-byte (modulo formatting whitespace)

---

## 9. Report HTML Rendering

### Requirement: GPU-018 — GPU section rendering

The report HTML MUST render a `#sec-gpu` section (placed adjacent to the existing `#sec-thermal` section in the dashboard) with two mutually exclusive sub-views:
- WHEN `session.gpuAvailable == true` → render a line chart of `gpuUsageTimed` with Y-axis fixed at `[0, 100]` and an "average GPU%" summary card.
- WHEN `session.gpuAvailable == false` → render a Spanish (tuteo-formal) banner mirroring the v4.4.1 thermal "temperature-not-shown" pattern (`ReportGenerator.kt`), explaining the reason per-case.

#### Scenario: GPU-018.1 — Available session renders chart

- GIVEN a session with `gpuAvailable=true` and `gpuUsageHistory=[10, 25, 50, 75]`
- WHEN the report HTML is generated
- THEN it MUST contain a `<canvas>` (or chart container) inside `#sec-gpu`
- AND it MUST contain a summary card showing the average value `(10+25+50+75)/4 = 40`
- AND it MUST NOT contain the unavailability banner

#### Scenario: GPU-018.2 — ADRENO_PERFCOUNTER_DISABLED banner is Spanish tuteo-formal

- GIVEN a session with `gpuAvailable=false` and `gpuDiagnostic.reason=ADRENO_PERFCOUNTER_DISABLED`
- WHEN the report HTML is generated
- THEN `#sec-gpu` MUST contain a banner whose Spanish text uses tuteo-formal style (e.g., "no se pudo leer el uso de GPU porque tu dispositivo Adreno no permite habilitar el contador de rendimiento")
- AND it MUST NOT use Rioplatense voseo ("no se pudo leer" NOT "no se pudo leer*lo*", "tu dispositivo" NOT "tu dispositivo che")
- AND it MUST list the probed paths so the user can file a bug report

#### Scenario: GPU-018.3 — POWERVR_UNSUPPORTED banner invites crowdsource

- GIVEN a session with `gpuAvailable=false` and `gpuDiagnostic.reason=POWERVR_UNSUPPORTED`
- WHEN the report HTML is generated
- THEN the Spanish tuteo-formal banner MUST mention that PowerVR (MediaTek / Unisoc) support is in progress
- AND it MUST display the probed paths
- AND it MUST link to (or describe) the Sprint 1.5 crowdsource channel for path submissions

---

### Requirement: GPU-019 — Foreground-attribution caveat tooltip

The report MUST surface, as a tooltip or footnote near the GPU chart, the GameBench-documented caveat that the reading reflects system-wide GPU utilization but "is generally attributed to the foreground app". This text MUST be Castilian Spanish formal tuteo.

#### Scenario: Caveat is rendered when GPU data is present

- GIVEN a session with `gpuAvailable=true`
- WHEN the report renders `#sec-gpu`
- THEN a tooltip or footnote MUST be present
- AND it MUST mention that the GPU usage is system-wide and conventionally attributed to the foreground app
- AND it MUST use tuteo-formal ("ten en cuenta", NOT "tené en cuenta")

---

### Requirement: GPU-020 — Adreno warm-up footnote

WHERE a session contains Adreno data with a `gpubusy`-based path (any sample with sequence position < 2 of the GPU history), the report MUST disclose, near the chart, a one-line footnote explaining the ~4-second warm-up.

#### Scenario: Warm-up note appears on Adreno gpubusy sessions

- GIVEN a session whose `gpuDiagnostic` carried `vendorAttempted="ADRENO"` at any point AND whose first valid sample timestamp is ≥ 4000 ms
- WHEN the report renders `#sec-gpu`
- THEN a footnote MUST disclose that Adreno devices may show ~4 seconds of warm-up before the first valid reading

---

## 10. Plausibility, Resilience, and Code Quality

### Requirement: GPU-021 — Usage clamping

The pure parser SHALL clamp every computed `usagePct` to `[0, 100]` before returning a `GpuSnapshot` with `gpuAvailable=true`. Values outside that range MUST result in `gpuAvailable=false` with `reason=OUT_OF_RANGE_VALUE` (per GPU-003 Scenario "Mali out-of-range value rejected") — clamping silently is NOT acceptable for Mali single-int reads (those are diagnostic signal). For Adreno delta math, the computed integer division `((deltaBusy * 100) / deltaTotal)` MAY exceed 100 transiently due to counter jitter; in that case the parser MAY clamp to 100 and return `gpuAvailable=true` (delta-math clamping is acceptable — the wraparound guard already catches the pathological cases).

#### Scenario: Mali 110% rejected loudly

- GIVEN Mali returns `"110"`
- WHEN parsed
- THEN `gpuAvailable` MUST be `false` (per GPU-003.2)

#### Scenario: Adreno delta jitter clamped quietly

- GIVEN Adreno `gpubusy` deltas computed to `usagePct=103` due to integer-division jitter
- WHEN parsed
- THEN the returned `GpuSnapshot.usagePct` MUST be `100`
- AND `gpuAvailable` MUST be `true`

---

### Requirement: GPU-022 — Capture exception resilience

IF `captureGpuUsage(deviceId)` throws any exception (adb crash, shell pipe broken, OOM), the bridge MUST catch it and return `GpuSnapshot(usagePct=-1, gpuAvailable=false, diagnostic=GpuDiagnostic(reason=ALL_PATHS_EMPTY, probedPaths=emptyList(), vendorAttempted=<cached vendor or null>))` rather than propagating the throw to `AppViewModel`. The capture loop MUST continue running.

#### Scenario: adb crash mid-tick does not kill the session

- GIVEN a running capture session
- WHEN the simulated `adb shell` for GPU throws `IOException`
- THEN `captureGpuUsage` MUST return a snapshot with `gpuAvailable=false`
- AND the capture session MUST continue accepting subsequent ticks
- AND `AppViewModel.captureRunning` MUST still be `true`

---

### Requirement: GPU-023 — Detekt cleanliness

All new code introduced by this change MUST pass `./gradlew detekt` with zero new warnings. Top-level regex constants MUST be declared `private val` per the existing project convention (`CLAUDE.md`: "Regex compilados como `private val` top-level, no inline").

#### Scenario: Detekt build passes after this change

- GIVEN the full source tree post-implementation
- WHEN `./gradlew detekt` is invoked
- THEN exit code MUST be 0
- AND no warning category MUST increase relative to the pre-change baseline

---

## 11. FakeAdbBridge Test Surface

### Requirement: GPU-024 — FakeAdbBridge GPU scripting

`testing/FakeAdbBridge.kt` MUST expose:
- `scriptedGpu: MutableMap<String, GpuSnapshot>` for high-level snapshot injection (one entry per `deviceId`)
- `setGpu(deviceId, GpuSnapshot)` convenience builder
- Per-vendor entries in the existing `shellResponses: MutableMap<String, String>` keyed by unique substrings of the candidate paths (so substring matching does not collide — uniqueness MUST be asserted in the catalog test)
- A scriptable echo-write hook (e.g., `perfcounterEchoExitCode: MutableMap<String, Int>`) so bridge tests can drive the GPU-007.1 (success) and GPU-007.2 (failure) scenarios deterministically

#### Scenario: FakeAdbBridge drives GPU-007.2 path

- GIVEN `FakeAdbBridge` with `shellResponses["gpu_busy_percentage"]=""`, `shellResponses["gpubusy"]=""`, `perfcounterEchoExitCode["DEV1"]=13` (SELinux EACCES)
- WHEN `captureGpuUsage("DEV1")` is invoked under that fake
- THEN the returned `GpuSnapshot.diagnostic.reason` MUST be `ADRENO_PERFCOUNTER_DISABLED`

#### Scenario: Catalog path uniqueness asserted at test time

- GIVEN `GpuVendorCatalog` Mali + Adreno + PowerVR candidate paths
- WHEN `GpuVendorCatalogTest` runs its uniqueness check
- THEN no Mali path's filename component MUST be a substring of any Adreno or PowerVR path filename component (so `FakeAdbBridge.shellResponses` substring-keying cannot collide)

---

End of `gpu-usage` spec. Word count target ≤ ~3500 (this is a NEW capability with broad surface — narrative kept tight via tables-of-scenarios per requirement; matches `core/spec.md` density).
