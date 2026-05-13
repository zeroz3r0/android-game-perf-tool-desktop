# Design: GPU Usage % on Android via sysfs (Sprint 1 — GameBench parity)

NOTE: artifact belongs to project `android-game-perf-tool-desktop` (orchestrator CWD = `firebase-remote-config-sync`).

Aligned with proposal v2 (engram obs #297) and Adreno Option B decision (obs #299). Mirrors the v4.4.1 `temperature-not-shown` thermal pattern end-to-end — same package layout (flat under `core/`), same single-shell + pure-parser split, same Diagnostic + Reason enum + report banner + Spanish tuteo-formal copy.

---

## 1. Architecture overview

```
                                  ┌──────────────────────────────────┐
                                  │  GpuVendorCatalog (object)       │
                                  │  PROBE_CANDIDATES: ordered list  │
                                  │  Mali → AdrenoBusyPct → AdrenoBusy│
                                  └──────────────┬───────────────────┘
                                                 │ (read-only SSOT)
                                                 ▼
 AdbBridge.captureGpuUsage(deviceId)
   │
   ├─► [first-tick] shell() one-shot probe loop over PROBE_CANDIDATES
   │       │
   │       ▼
   │   GpuUsageParser.parseProbeOutput(rawOutput) ──► GpuProbeResult
   │       │      (vendor + winning path + ProbeFormat + raw payload)
   │       ▼
   │   AdbBridge stores winner in gpuStateMap[deviceId]
   │
   ├─► [steady-state Mali] shell() cat winningPath → parser.parseMali() → Int 0..100
   │
   ├─► [steady-state Adreno gpu_busy_percentage] shell() cat → parser.parseAdrenoGpuBusyPercentage() → Int
   │
   ├─► [steady-state Adreno gpubusy] shell() cat → parser.parseAdrenoGpuBusy() → Pair<busy,total>
   │       │       compute delta vs prev → computeAdrenoDelta() → Int / null (warm-up)
   │       │       baseline stored in gpuStateMap[deviceId].lastBusyTotal
   │
   ├─► [Adreno both empty + !firstProbeFailed] shell() echo 1 > perfcounter
   │       │   success → perfcounterEnabledByUs=true → return UNAVAILABLE this tick → retry next tick
   │       │   failure → firstProbeFailed=true → UNAVAILABLE+ADRENO_PERFCOUNTER_DISABLED forever
   │
   └─► [PowerVR | all-empty + non-Adreno] UNAVAILABLE+POWERVR_UNSUPPORTED | ALL_PROBES_FAILED
                                                 │
                                                 ▼
                                          GpuSnapshot
                                          (usagePct, gpuAvailable, diagnostic?)
                                                 │
            ┌────────────────────────────────────┴───────────────────────────────┐
            ▼                                                                    ▼
   AppViewModel.startCapture loop                                       SessionHistory payload
   (every-4-tick poll, ~2 s)                                            (gpuAvailable persisted)
   lastGpu MutableState                                                          │
   gpuUsageHistory / gpuUsageTimed                                               ▼
            │                                                          ReportGenerator.generate
            ▼                                                          gpuAvailable + diagnostic
   LiveMetrics.gpuUsage HUD                                            chart OR banner OR caveat
```

State direction is exclusively `core` → `viewmodel` → `report`. No reverse edges. `AppViewModel` only speaks `core.model.*` (mirrors thermal — never directly imports `core/Gpu*`).

---

## 2. Component contracts

### 2.1 `core/GpuVendorCatalog.kt` (NEW)

```kotlin
package com.gameperf.desktop.core

object GpuVendorCatalog {
    /**
     * Ordered probe priority. Mali first (single read, kernel-computed pct).
     * Adreno `gpu_busy_percentage` before raw `gpubusy` because it avoids
     * the 1-tick warm-up. PowerVR is a placeholder — Sprint 1 returns
     * POWERVR_UNSUPPORTED on its branch.
     *
     * Adding a candidate: append to PROBE_CANDIDATES. ORDER MATTERS — the
     * first non-empty hit wins (substring match in FakeAdbBridge.shellResponses
     * must stay unique, asserted in GpuVendorCatalogTest).
     */
    val PROBE_CANDIDATES: List<GpuProbeCandidate> = listOf(
        GpuProbeCandidate(GpuVendor.MALI,    "/sys/class/misc/mali0/device/utilization",      ProbeFormat.MALI_INT_0_100,            Confidence.HIGH),
        GpuProbeCandidate(GpuVendor.MALI,    "/sys/class/misc/mali0/device/utility",          ProbeFormat.MALI_INT_0_100,            Confidence.MEDIUM), // BSP typo alternate
        GpuProbeCandidate(GpuVendor.ADRENO,  "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",  ProbeFormat.ADRENO_GPU_BUSY_PERCENTAGE, Confidence.HIGH),
        GpuProbeCandidate(GpuVendor.ADRENO,  "/sys/class/kgsl/kgsl-3d0/gpubusy",              ProbeFormat.ADRENO_KGSL_BUSY_TOTAL,    Confidence.HIGH),
        GpuProbeCandidate(GpuVendor.POWERVR, "/proc/mtk_mali/utilization",                    ProbeFormat.POWERVR_UNKNOWN,           Confidence.LOW),
    )

    /** Adreno A13+ privileged write to unlock the perfcounter family. */
    const val ADRENO_PERFCOUNTER_NODE: String = "/sys/class/kgsl/kgsl-3d0/perfcounter"
}

enum class GpuVendor { MALI, ADRENO, POWERVR }

enum class ProbeFormat {
    MALI_INT_0_100,
    ADRENO_KGSL_BUSY_TOTAL,
    ADRENO_GPU_BUSY_PERCENTAGE,
    POWERVR_UNKNOWN,
}

enum class Confidence { HIGH, MEDIUM, LOW }

data class GpuProbeCandidate(
    val vendor: GpuVendor,
    val path: String,
    val format: ProbeFormat,
    val confidence: Confidence,
)
```

### 2.2 `core/GpuUsageParser.kt` (NEW — pure object, NO state)

```kotlin
package com.gameperf.desktop.core

internal object GpuUsageParser {

    /**
     * Plausibility window for any parsed % value (Mali kbase, Adreno computed).
     * Anything outside is treated as a parse error (mirrors thermal sensor sanity).
     */
    private const val MIN_PCT = 0
    private const val MAX_PCT = 100

    /** Parse the multi-line `path:value` probe output. Returns the FIRST non-empty hit
     *  matched against [GpuVendorCatalog.PROBE_CANDIDATES] in declaration order. */
    fun parseProbeOutput(rawOutput: String): GpuProbeResult?  // null when every candidate is empty

    /** Mali kbase: single int 0..100 (sometimes with trailing newline). */
    fun parseMali(raw: String): Int?

    /** Adreno gpu_busy_percentage: single int. Some kernels append "%" — strip it. */
    fun parseAdrenoGpuBusyPercentage(raw: String): Int?

    /** Adreno gpubusy: two cumulative u64 counters "<busy> <total>" (whitespace-sep).
     *  Returns Pair(busy, total). null on malformed / partial. */
    fun parseAdrenoGpuBusy(raw: String): Pair<Long, Long>?

    /**
     * Pure delta math for Adreno gpubusy. Returns null on:
     *  - deltaTotal <= 0    (counter wraparound | post-boot idle | clock anomaly)
     *  - deltaBusy  <  0    (defensive — u64 in kernel, but `Long` in JVM)
     *  - computed % outside [MIN_PCT..MAX_PCT]
     * Otherwise returns (deltaBusy * 100 / deltaTotal).toInt() clamped to 0..100.
     *
     * Mirrors `AdbBridge.captureProcessCpuPercent` delta semantics (lines 645-659).
     */
    fun computeAdrenoDelta(prev: Pair<Long, Long>, curr: Pair<Long, Long>): Int?
}

internal data class GpuProbeResult(
    val vendor: GpuVendor,
    val winningPath: String,
    val format: ProbeFormat,
    val rawPayload: String,   // post-`path:` substring, trimmed
)
```

Heredoc fixture convention applies — tests use inline `"""...""".trimIndent()`, no resource files. Matches `AdbThermalParser` precedent.

### 2.3 `core/model/Metrics.kt` — ADD `GpuSnapshot`

```kotlin
@Serializable
data class GpuSnapshot(
    /** 0..100 when [gpuAvailable]; -1 sentinel otherwise. Pre-clamped by parser. */
    val usagePct: Int = -1,
    val gpuAvailable: Boolean = false,
    val diagnostic: GpuDiagnostic? = null,
)
```

Default `gpuAvailable=false` + `usagePct=-1` so a deserialized v4.4.1 `.gameperf` (no gpu field) round-trips as "no gpu data" — exactly what the thermal pattern does (mirrors `ThermalSnapshot.thermalAvailable = true` BUT note thermal defaults to `true` only because pre-v4.4.1 sessions DID capture thermal. v4.4.1 sessions never captured GPU → default MUST be `false`). Documented in KDoc.

### 2.4 `core/model/GpuDiagnostic.kt` (NEW — mirrors `ThermalDiagnostic.kt`)

```kotlin
package com.gameperf.desktop.core.model

import kotlinx.serialization.Serializable

@Serializable
data class GpuDiagnostic(
    /** Truncated to 10 entries (per proposal §Scope IN). */
    val probedPaths: List<String>,
    /** Vendor detected from winning probe, or null when all probes failed. */
    val detectedVendor: String? = null,
    /** Last echo command issued (when reason = ADRENO_PERFCOUNTER_DISABLED). */
    val failedEnableCommand: String? = null,
    val reason: GpuUnavailableReason,
)

@Serializable
enum class GpuUnavailableReason {
    ALL_PROBES_FAILED,            // no vendor responded — non-Adreno, non-Mali (unrecognized SoC)
    ADRENO_BLOCKED,               // Adreno detected but perfcounter family unreadable post-enable
    ADRENO_PERFCOUNTER_DISABLED,  // Adreno + both probes empty + echo 1 > perfcounter failed
    POWERVR_UNSUPPORTED,          // PowerVR detected (any path matched) — Sprint 1 graceful unavailable
    CAPTURE_THREW,                // try/catch fallback (mirrors thermal resilience)
}
```

Cap-10 enforcement lives at the parser/bridge boundary (same as `AdbThermalParser.DIAGNOSTIC_RAW_NAMES_LIMIT = 10`). Defensive second `take(10)` in `ReportGenerator` mirrors thermal banner.

### 2.5 `core/AdbBridgeApi.kt` — interface addition

```kotlin
fun captureGpuUsage(deviceId: String): GpuSnapshot
```

Required (not nullable). Failure returns `GpuSnapshot(usagePct=-1, gpuAvailable=false, diagnostic=…)`. Mirrors `captureTemperature` (also non-nullable post-v4.4.1).

### 2.6 `core/AdbBridge.kt` — implementation

Add method `captureGpuUsage(deviceId: String): GpuSnapshot` (see §4 algorithm).

Add private state map and lock (next to `cpuLock` at L258):

```kotlin
private val gpuLock = Any()
private val gpuStateMap = mutableMapOf<String, GpuDeviceState>()

private data class GpuDeviceState(
    val vendor: GpuVendor?,
    val winningPath: String?,
    val format: ProbeFormat?,
    val lastBusyTotal: Pair<Long, Long>?,
    val perfcounterEnabledByUs: Boolean,
    val firstProbeFailed: Boolean,
)
```

Add to `resetSessionState()` (current L270-286), AFTER the `pidCpuLock` block:

```kotlin
synchronized(gpuLock) {
    // Best-effort disable for every device where WE flipped the bit on.
    gpuStateMap.values
        .filter { it.perfcounterEnabledByUs }
        .forEach { /* shell echo 0 > perfcounter, swallow failures */ }
    gpuStateMap.clear()
}
```

`RealAdbBridge.captureGpuUsage` is a 1-line passthrough to `AdbBridge.captureGpuUsage` (matches every other method post-v4.2.2).

### 2.7 `testing/FakeAdbBridge.kt` — additions

```kotlin
@Volatile
private var scriptedGpu: GpuSnapshot? = null

fun setGpu(snapshot: GpuSnapshot): FakeAdbBridge { scriptedGpu = snapshot; return this }

override fun captureGpuUsage(deviceId: String): GpuSnapshot =
    scriptedGpu ?: GpuSnapshot(usagePct = -1, gpuAvailable = false, diagnostic = null)
```

Reuse the existing `shellResponses: MutableMap<String, String>` for wired-flow tests (already substring-keyed, first-match-wins). Tests register the 3 GPU paths (Mali utilization, Adreno gpu_busy_percentage, Adreno gpubusy) AND the `echo 1 > /sys/class/kgsl/kgsl-3d0/perfcounter` command. Uniqueness asserted in `GpuVendorCatalogTest` per proposal mitigation.

Probe-loop output simulation: tests set a single `shellResponses["for p in /sys/class"]` value containing the multi-line `path:value` body the real device would emit. Steady-state ticks set `shellResponses["/sys/class/misc/mali0"]` etc. individually.

---

## 3. State management — bridge state map

State per device (`Map<deviceId, GpuDeviceState>`):

| Phase | `vendor` | `winningPath` | `format` | `lastBusyTotal` | `perfcounterEnabledByUs` | `firstProbeFailed` |
|---|---|---|---|---|---|---|
| **Initial** (no entry yet) | — | — | — | — | — | — |
| **After first probe (Mali wins)** | MALI | `…/utilization` | `MALI_INT_0_100` | null | false | false |
| **Steady-state Mali** | unchanged | unchanged | unchanged | null | false | false |
| **After first probe (Adreno gpu_busy_percentage wins)** | ADRENO | `…/gpu_busy_percentage` | `ADRENO_GPU_BUSY_PERCENTAGE` | null | false | false |
| **After first probe (Adreno gpubusy wins, tick N)** | ADRENO | `…/gpubusy` | `ADRENO_KGSL_BUSY_TOTAL` | `(b0,t0)` baseline | false | false |
| **Adreno gpubusy steady-state (tick N+1)** | ADRENO | `…/gpubusy` | `ADRENO_KGSL_BUSY_TOTAL` | `(b1,t1)` rolled | false | false |
| **Adreno probes empty → enable success** | ADRENO | null (still probing) | null | null | **true** | false |
| **Adreno probes empty → enable failure** | ADRENO | null | null | null | false | **true** |
| **PowerVR detected** | POWERVR | null | null | null | false | **true** |
| **resetSessionState()** | (entry removed) | | | | | |

Transitions:
- Probe-failure is sticky via `firstProbeFailed=true` → subsequent ticks short-circuit to UNAVAILABLE without re-shelling. Mirrors thermal's stateless "diagnostic regenerates every tick" pattern but cheaper — we DON'T re-probe.
- Enable-success is one-shot: next tick re-enters the probe path. We accept ONE wasted UNAVAILABLE tick (~2 s) in exchange for never polluting non-A13+ devices with `echo 1 >`.
- `firstProbeFailed` cleared ONLY by `resetSessionState()`. User stopping + restarting capture → fresh attempt. Matches CPU `prevPidInitialized` semantics (line 666).

---

## 4. Read-flow algorithm — `captureGpuUsage`

```
fun captureGpuUsage(deviceId: String): GpuSnapshot {
  try {
    val st0 = gpuStateMap[deviceId]                                     // STEP 1

    if (st0 == null || st0.winningPath == null) {                       // STEP 2: probe
      if (st0?.firstProbeFailed == true) return cachedUnavailable(st0)  //   stuck — return cached diagnostic
      val probeCmd = buildProbeOneShellCommand(GpuVendorCatalog.PROBE_CANDIDATES)
      val raw = shell(deviceId, probeCmd, timeoutMs = 3000)
      val hit = GpuUsageParser.parseProbeOutput(raw)
      if (hit == null) {
        // no vendor responded → cache permanent failure
        val diag = GpuDiagnostic(probedPaths = catalogPaths().take(10),
                                 detectedVendor = null,
                                 reason = GpuUnavailableReason.ALL_PROBES_FAILED)
        gpuStateMap[deviceId] = GpuDeviceState(null, null, null, null, false, true)
        return GpuSnapshot(-1, false, diag)
      }
      // first hit defines vendor + format
      gpuStateMap[deviceId] = GpuDeviceState(hit.vendor, hit.winningPath, hit.format,
                                             lastBusyTotal = null,
                                             perfcounterEnabledByUs = st0?.perfcounterEnabledByUs ?: false,
                                             firstProbeFailed = false)
      // PowerVR detected via path match → permanent unavailable
      if (hit.vendor == GpuVendor.POWERVR) return powervrUnsupported(hit)
      // fall through using `hit.rawPayload` so the FIRST tick already returns a value
      // when format is MALI or ADRENO_GPU_BUSY_PERCENTAGE
    }

    val st = gpuStateMap[deviceId]!!

    return when (st.format) {                                            // STEP 3-5
      ProbeFormat.MALI_INT_0_100 -> {
        val raw = shell(deviceId, "cat ${st.winningPath} 2>/dev/null", 2000)
        val pct = GpuUsageParser.parseMali(raw) ?: return maliUnavailable(deviceId)
        GpuSnapshot(pct, gpuAvailable = true, diagnostic = null)
      }
      ProbeFormat.ADRENO_GPU_BUSY_PERCENTAGE -> {
        val raw = shell(deviceId, "cat ${st.winningPath} 2>/dev/null", 2000)
        val pct = GpuUsageParser.parseAdrenoGpuBusyPercentage(raw)
        if (pct != null) GpuSnapshot(pct, true, null)
        else fallbackToGpubusy(deviceId, st)                              // alt-path attempt
      }
      ProbeFormat.ADRENO_KGSL_BUSY_TOTAL -> {
        val raw = shell(deviceId, "cat ${st.winningPath} 2>/dev/null", 2000)
        val parsed = GpuUsageParser.parseAdrenoGpuBusy(raw)
        if (parsed == null) return adrenoProbeFailedTryEnable(deviceId, st)
        val prev = st.lastBusyTotal
        gpuStateMap[deviceId] = st.copy(lastBusyTotal = parsed)
        if (prev == null) return GpuSnapshot(-1, false, null)             // baseline tick — UNAVAILABLE
        val pct = GpuUsageParser.computeAdrenoDelta(prev, parsed)
          ?: return GpuSnapshot(-1, false, null)                          // wraparound / total<=0
        GpuSnapshot(pct, true, null)
      }
      ProbeFormat.POWERVR_UNKNOWN, null -> cachedUnavailable(st)
    }
  } catch (_: Exception) {
    // CAPTURE_THREW path — mirrors thermal resilience
    return GpuSnapshot(-1, false, GpuDiagnostic(
      probedPaths = emptyList(),
      detectedVendor = null,
      reason = GpuUnavailableReason.CAPTURE_THREW,
    ))
  }
}

private fun adrenoProbeFailedTryEnable(deviceId: String, st: GpuDeviceState): GpuSnapshot {
  if (st.firstProbeFailed) return cachedAdrenoBlocked(deviceId)
  val enableCmd = "echo 1 > ${GpuVendorCatalog.ADRENO_PERFCOUNTER_NODE} 2>&1; echo rc=$?"
  val out = shell(deviceId, enableCmd, 2000)
  val ok = out.contains("rc=0") && !out.contains("Permission") && !out.contains("denied")
  if (ok) {
    gpuStateMap[deviceId] = st.copy(perfcounterEnabledByUs = true,
                                    winningPath = null, format = null) // re-probe next tick
    return GpuSnapshot(-1, false, null)                                // warm-up tick
  } else {
    val diag = GpuDiagnostic(
      probedPaths = listOf("…/gpu_busy_percentage", "…/gpubusy").take(10),
      detectedVendor = "ADRENO",
      failedEnableCommand = enableCmd,
      reason = GpuUnavailableReason.ADRENO_PERFCOUNTER_DISABLED,
    )
    gpuStateMap[deviceId] = st.copy(firstProbeFailed = true)
    return GpuSnapshot(-1, false, diag)
  }
}
```

Steps explicitly enumerated:

1. **Lookup** `gpuStateMap[deviceId]`.
2. **First-tick probe**: single shell-out with `for p in /sys/class/...; do echo "${p}:$(cat $p 2>/dev/null)"; done` (literal pattern, no user input — safe from injection per CLAUDE.md `shell()` rules). Parser returns first non-empty hit. Cache vendor + winning path + format.
3. **Mali**: single `cat` per tick → `parseMali` → return.
4. **Adreno gpu_busy_percentage**: single `cat` per tick → return; if empty mid-session (device flipped mode? rare), fallback to `gpubusy`.
5. **Adreno gpubusy**: single `cat` → `parseAdrenoGpuBusy` → if first read store baseline + return UNAVAILABLE; else compute delta.
6. **Adreno BOTH probes empty + `!firstProbeFailed`**: attempt `echo 1 > perfcounter`. Detect success via `rc=0` AND absence of "denied"/"Permission" substrings. Success → `perfcounterEnabledByUs=true`, drop winningPath so STEP 2 re-runs next tick. Failure → `firstProbeFailed=true`, return `ADRENO_PERFCOUNTER_DISABLED` diagnostic.
7. **PowerVR** (any PowerVR-tagged path matched): permanent `POWERVR_UNSUPPORTED` + diagnostic.

`resetSessionState()`: best-effort `echo 0 > perfcounter` for every device with `perfcounterEnabledByUs=true`, swallow failures. Then `gpuStateMap.clear()`.

---

## 5. Test architecture

Match thermal split (`AdbThermalParserTest` + `ThermalZoneClassifierTest` + thermal coverage in `AdbBridgeTest`):

| Test file | Scope | Scenarios |
|---|---|---|
| `src/test/.../core/GpuUsageParserTest.kt` | pure parser | • `parseMali` int 0..100 happy / trailing newline / out-of-range / non-numeric → null<br>• `parseAdrenoGpuBusyPercentage` int / int with `%` suffix / empty / malformed<br>• `parseAdrenoGpuBusy` two-counter happy / single-counter (malformed) → null / negative numbers → null<br>• `computeAdrenoDelta` happy delta → 0..100 / wraparound `deltaTotal <= 0` → null / `total=0` post-boot idle → null / 0%-load → 0 / 100%-load → 100<br>• `parseProbeOutput` Mali-only / Adreno-only / both-empty / multi-vendor (Mali first wins per catalog order) |
| `src/test/.../core/GpuVendorCatalogTest.kt` | catalog | • Order: MALI before ADRENO before POWERVR<br>• `gpu_busy_percentage` before `gpubusy`<br>• Substring uniqueness: no candidate's path is a substring of another (asserts FakeAdbBridge.shellResponses keys won't collide)<br>• Confidence levels assigned correctly<br>• At least 1 candidate per vendor (POWERVR included for Sprint 1.5 readiness) |
| `src/test/.../core/AdbBridgeGpuTest.kt` | wired Mali + Adreno read flow | • Mali single-tick happy → `usagePct ∈ [0,100]`, gpuAvailable=true<br>• Adreno `gpu_busy_percentage` single-tick happy<br>• Adreno `gpubusy` tick-1 baseline → gpuAvailable=false, tick-2 delta → true<br>• Adreno `gpubusy` wraparound between ticks → gpuAvailable=false (no diagnostic, just unavailable)<br>• PowerVR path matched → gpuAvailable=false reason=POWERVR_UNSUPPORTED<br>• All probes empty (no Adreno, no Mali) → ALL_PROBES_FAILED, `firstProbeFailed=true`, subsequent ticks return cached diagnostic without re-shelling<br>• `shellResponses` substring uniqueness asserted |
| `src/test/.../core/AdbBridgeGpuLifecycleTest.kt` | perfcounter lifecycle | • Adreno both empty + echo-success → `perfcounterEnabledByUs=true`, returns UNAVAILABLE (warm-up), next tick re-probes successfully<br>• Adreno both empty + echo-failure (`Permission denied`) → reason=ADRENO_PERFCOUNTER_DISABLED, `failedEnableCommand` populated, `firstProbeFailed=true`, subsequent ticks short-circuit (no re-shell)<br>• `resetSessionState()` with `perfcounterEnabledByUs=true` → issues `echo 0 > perfcounter`, swallows failure, clears `gpuStateMap`<br>• `resetSessionState()` with `perfcounterEnabledByUs=false` → does NOT issue echo 0 (assert via FakeAdbBridge.shellCalls)<br>• Multi-device session: enable on device A does NOT touch device B's state |
| `src/test/.../viewmodel/AppViewModelGpuTest.kt` (NEW or extend `AppViewModelTest`) | wiring | • `iterCount % 4 == 0` cadence: `captureGpuUsage` called once per ~2 s (4 ticks @ 500 ms)<br>• `gpuUsageHistory` accumulates only when `gpuAvailable=true`<br>• `gpuUsageTimed` adds `TimedSample(sampleSecond, usagePct)`<br>• `MAX_HISTORY_SIZE` cap enforced (mirrors `cpuHistory` cap)<br>• `LiveMetrics.gpuUsage` HUD field updated per tick<br>• `SessionResult`/`SerializableEntry.gpuAvailable` persisted = `lastGpu.gpuAvailable` (last-known)<br>• Round-trip: write `.gameperf` then load → GpuSnapshot deserializes with null `gpuAvailable=false` default for v4.4.1 fixtures<br>• ReportGenerator integration: `gpuAvailable=true` → chart rendered; `gpuAvailable=false` + diagnostic → banner rendered with Spanish copy |

Convention: inline heredoc fixtures (`"""…""".trimIndent()`), NO `src/test/resources/`. Zero new test dependencies. Pure tests, no mocks (CLAUDE.md). All TDD red-first.

---

## 6. Wiring points in `AppViewModel.kt`

Verified against current v4.4.1 source (the brief's line refs from exploration #296 are accurate to within ±2 lines):

| v4.4.1 line | What's there now | GPU addition |
|---|---|---|
| L1107 | `var lastThermal = ThermalSnapshot(NaN,NaN,NaN,NaN)` | ADD next line: `var lastGpu = GpuSnapshot(usagePct = -1, gpuAvailable = false, diagnostic = null)` |
| L1177-1197 | MEDIUM TIER `if (runThermal)` block (every 4th tick) | ADD parallel `val runGpu = iterCount % 4 == 0; if (runGpu) { lastGpu = adb.captureGpuUsage(device.id); if (shouldStop) break }`. Keep separate from `runThermal` block for clarity (parallel sibling, NOT nested). iOS branch (L1145-1162) does NOT call `captureGpuUsage` — iOS GPU is out of scope (proposal §Scope OUT). |
| L1284 (`val shouldRecordThermal = isIosDevice \|\| (iterCount % 4 == 1)`) | thermal history-recording cadence | ADD sibling `val shouldRecordGpu = iterCount % 4 == 1` (align with runGpu+1 same as thermal). Inside the `if (shouldRecordGpu) { … }` block: `if (lastGpu.gpuAvailable && lastGpu.usagePct >= 0) { gpuUsageHistory.add(lastGpu.usagePct); gpuUsageTimed.add(TimedSample(sampleSecond, lastGpu.usagePct.toDouble())); enforce MAX_HISTORY_SIZE }`. Mirror the thermal `tempGpuHistory` (L1315-1320) pattern exactly. |
| L1357 `_liveMetrics.value = LiveMetrics(...)` | LiveMetrics emission | ADD field `gpuUsage = lastGpu.usagePct, gpuAvailable = lastGpu.gpuAvailable, gpuUsageHistory = if (snapshotHistories) gpuUsageHistory.toList() else prev.gpuUsageHistory`. Requires `LiveMetrics` data class extension (new file: trivial — same pattern as `tempCpuHistory`). |
| L1676 `ConclusionInput(... thermalAvailable = lastThermal.thermalAvailable)` | conclusion engine wiring | NO change Sprint 1. GPU conclusion rules deferred to Sprint 2 (proposal §Scope OUT). Leave conclusion engine untouched. |
| L1683-1729 `ReportGenerator.generate(...)` call | report wiring | ADD params: `gpuUsageHistory = gpuUsageHistory.toList(), maxGpuUsage = if (gpuUsageHistory.isNotEmpty()) gpuUsageHistory.max() else -1, gpuAvailable = lastGpu.gpuAvailable, gpuDiagnostic = lastGpu.diagnostic` |
| L1824 `SerializableEntry(... thermalAvailable = lastThermal.thermalAvailable)` | history persistence | ADD `gpuAvailable = lastGpu.gpuAvailable, maxGpuUsage = if (gpuUsageHistory.isNotEmpty()) gpuUsageHistory.max() else -1, gpuDiagnostic = lastGpu.diagnostic`. `SessionHistory.SerializableEntry` + `SessionHistory.Entry` gain these 3 fields (mirror L193-292 thermalAvailable pattern: defaults preserve v4.4.1 backward compat). |

LiveMetrics data class addition (`viewmodel/LiveMetrics.kt` or wherever it lives — search confirms it's referenced at L1357 in AppViewModel; check the actual file in implementation phase):

```kotlin
val gpuUsage: Int = -1,
val gpuAvailable: Boolean = false,
val gpuUsageHistory: List<Int> = emptyList(),
```

History collection declared near `cpuHistory` (search: `val cpuHistory = ArrayDeque<Int>()` style — implementation phase finds exact line):

```kotlin
val gpuUsageHistory = ArrayDeque<Int>()
val gpuUsageTimed = ArrayDeque<TimedSample>()
```

---

## 7. Report HTML (`report/ReportGenerator.kt`)

Mirror the thermal `N/D + banner` pattern (commit 459fdc4, current L364-393 + L471 + L1371-1400).

**Generator signature additions** (next to `thermalAvailable`/`thermalDiagnostic` at current L84-85, both default-valued for legacy fixture compat):

```kotlin
gpuAvailable: Boolean = false,
gpuDiagnostic: GpuDiagnostic? = null,
gpuUsageHistory: List<Int> = emptyList(),
maxGpuUsage: Int = -1,
```

**New metric card** — slot it AFTER the CPU card and BEFORE the Temperature card (current L353-393). Same `metricCard(...)` helper. Card body:

```
if (!gpuAvailable) {
    metricCard(
        title = "GPU",
        value = "N/D",
        icon = "gpu",
        grade = 'A',
        gc = "#94a3b8",
        detail = "Sensor no disponible",
    )
} else {
    val gpuPct = maxGpuUsage.coerceIn(0, 100)
    val avgGpu = if (gpuUsageHistory.isNotEmpty()) gpuUsageHistory.average().toInt() else 0
    val gpuGrade = metricGrade(100 - gpuPct, 80, 60, 40, 20)
    metricCard("GPU", "${gpuPct}%", "gpu", gpuGrade, gradeColor(gpuGrade), "Promedio ${avgGpu}%")
}
```

**New `#sec-gpu` chart section** — slot AFTER `#sec-cpu` (current L454-464). Same `chart-container` + `canvas` shape as the CPU section. Chart-data wiring follows `cpuD` pattern (L139).

**New `gpuDiagnosticBanner(gpuAvailable, gpuDiagnostic)` private helper** — clone of `thermalDiagnosticBanner` (L1371-1400). Spanish tuteo-formal copy:

| Reason | Copy |
|---|---|
| `ALL_PROBES_FAILED` | "No detectamos sensores GPU legibles. Probablemente el dispositivo usa un GPU PowerVR u otra familia que todavía no soportamos." |
| `ADRENO_BLOCKED` | "El dispositivo expone sensores Adreno pero no nos deja leerlos. SELinux o el OEM bloquean el acceso." |
| `ADRENO_PERFCOUNTER_DISABLED` | "Tu dispositivo Adreno requiere habilitar los contadores de rendimiento. Intentamos hacerlo automáticamente pero el OEM nos bloqueó." |
| `POWERVR_UNSUPPORTED` | "El catálogo Sprint 1 todavía no cubre tu GPU PowerVR. Si querés ayudarnos a soportarla, exportá esta sesión y abrí un issue con los paths listados debajo." |
| `CAPTURE_THREW` | "Se produjo un error inesperado leyendo el sensor GPU. Reportá este caso para que podamos investigarlo." |

Banner emitted at the top of the new GPU section (parallel to current L471 `${thermalDiagnosticBanner(...)}` in `#sec-temp`).

**Caveat tooltip** — single-line hint under the new chart (`<p class="hint">`):

> "GPU compartido entre sistema y juego (el valor se atribuye generalmente al juego en foreground). Picos pueden reducir el % mostrado porque el clock sube. Adreno necesita ~4 s de warm-up para mostrar el primer valor."

Mirrors the proposal §Caveats verbatim, GameBench-docs aligned.

---

## 8. Migration / backward compatibility

- **No data migration.** GPU snapshot, diagnostic, history, persisted flag are all NEW additive fields. No existing field renamed or repurposed.
- **`.gameperf` v4.4.1 files load fine on v4.5.0**: new fields default (`gpuAvailable = false`, `usagePct = -1`, `diagnostic = null`, `maxGpuUsage = -1`, `gpuDiagnostic = null`). Report re-render shows "GPU N/D" with no banner — neutral fallback.
- **`.gameperf` v4.5.0 files DO NOT load on v4.4.1**: kotlinx.serialization fails on unknown fields by default. Documented in CHANGELOG under "Breaking" with an explicit note: users must update both the desktop app AND any sidecar tooling to v4.5.0+ before opening v4.5.0 sessions. NO downgrade path. (Matches the v4.4.1 `temperature-not-shown` handling — additive fields, no v4.3.x downgrade.)
- **iOS branch**: `iosBridge` does NOT gain `captureGpuUsage` — iOS GPU is out of scope (proposal §Scope OUT). iOS sessions persist `gpuAvailable=false` always, report renders "GPU N/D" with no banner.

---

## 9. Detekt impact

Anticipated warnings + mitigations:

| Rule | Risk | Mitigation |
|---|---|---|
| `LongMethod` (60+ lines) | `AdbBridge.captureGpuUsage` is ~90 lines including pseudo-code branches | Extract `adrenoProbeFailedTryEnable`, `cachedUnavailable`, `cachedAdrenoBlocked`, `powervrUnsupported`, `fallbackToGpubusy`, `buildProbeOneShellCommand`, `catalogPaths` into private helpers (mirrors `AdbThermalParser.buildDiagnostic`/`withinPlausibilityWindow` split). Each helper ≤ 25 lines. |
| `ComplexCondition` | `parseProbeOutput` candidate-matching across vendor + non-empty + format | Lift each clause into a named local `val isHit = line.contains(":") && payload.isNotBlank()`. Identical idiom to `ThermalZoneClassifier.classify` step ladder. |
| `MagicNumber` | `2000`, `3000` shell timeouts, plausibility window `0..100` | Hoist to named `internal const val`s next to `MAX_FRAME_TIME_MS` (L32). Names: `GPU_PROBE_SHELL_TIMEOUT_MS`, `GPU_STEADY_STATE_SHELL_TIMEOUT_MS`, `GPU_USAGE_MIN_PCT`, `GPU_USAGE_MAX_PCT`. |
| `TooGenericExceptionCaught` | `catch (_: Exception)` for CAPTURE_THREW | Same pattern as `AdbBridge.exec`, `captureTemperature`, `captureMemory` — already baselined per `detekt-baseline.xml`. Add to baseline if Detekt flags. |
| `ReturnCount` | `captureGpuUsage` has many early returns | Acceptable per existing baseline (`captureFrames`, `captureProcessCpuPercent` both exceed default 2). Baseline if needed. |

Goal: zero NEW Detekt suppressions outside baseline. Existing baseline carries the precedent.

---

## 10. Performance budget

| Path | Shell-outs per ~2 s tick | Estimated USB RTT | Total host overhead |
|---|---|---|---|
| First tick (probe phase) | 1 (multi-path `for` loop) | ~50-100 ms | 1 invocation amortized once per session |
| Steady-state Mali | 1 (`cat utilization`) | ~30-60 ms | negligible |
| Steady-state Adreno `gpu_busy_percentage` | 1 | ~30-60 ms | negligible |
| Steady-state Adreno `gpubusy` | 1 | ~30-60 ms | negligible (delta math is pure CPU, microseconds) |
| Adreno enable attempt (one-shot) | 1 | ~50-100 ms | amortized once per session |
| Failure path (`firstProbeFailed=true`) | 0 (cached) | 0 | zero — we never re-probe |
| `resetSessionState()` best-effort disable | up to N devices × 1 echo | ~50-100 ms each | one-time per session-end |

Target: < 100 ms host-side per polling tick (well within the 500 ms tick budget, ~20% of one tick). DUT load: ~0% beyond the `cat` syscall itself. Compared to GameBench's reported 3.8% on-DUT CPU overhead (Pixel 6 Tensor) → our DUT-side cost is effectively zero because we're not running an on-device profiler binary.

Cadence rationale (every 4 ticks = ~2 s): identical to thermal. Mirrors GameBench docs' 1 Hz claim (we're at ~0.5 Hz, half the GameBench rate but plenty for trend analysis).

---

## 11. Open questions

**None.** Q1-Q5 from exploration (#296) baked into proposal v2 (#297). Adreno enable decision frozen in #299. Sub-decisions resolved inline:

| Sub-decision | Resolution | Rationale |
|---|---|---|
| `GpuSnapshot.gpuAvailable` default value | `false` (NOT `true` like thermal) | Thermal pre-v4.4.1 sessions DID capture thermal so `true` default was correct. GPU pre-v4.5.0 sessions NEVER captured GPU → `false` is the truthful default. |
| Where do `gpuUsageHistory` / `gpuUsageTimed` collections live? | `AppViewModel` local `val gpuUsageHistory = ArrayDeque<Int>()` next to `cpuHistory` | Mirrors thermal exactly (L260-ish area inside `startCapture` lambda scope). |
| `LiveMetrics.gpuUsage` field type | `Int` (not `Double`) | GPU usage is always an integer percentage 0..100. Mirrors `cpu: Int` field at L1366 / L1394 in `LiveMetrics`. |
| iOS branch behavior | Skip `captureGpuUsage` call entirely; persist `gpuAvailable=false` | iOS GPU out of scope, sidecar has no equivalent endpoint, no need to widen `IosBridge` Sprint 1. |
| Probe one-shell command exact form | `for p in <paths>; do echo "${p}:$(cat $p 2>/dev/null)"; done` | Identical pattern to `captureTemperature` L684. Reuses `shell()` (3 s timeout). |
| Adreno enable success detection | `out.contains("rc=0") && !out.contains("Permission") && !out.contains("denied")` | Combined `2>&1; echo rc=$?` pattern is the most portable across BusyBox + Toybox shells. SELinux failures emit "Permission denied" to stderr, redirected with `2>&1`. |
| Where does `buildProbeOneShellCommand` live? | Private inside `AdbBridge.kt` (NOT `GpuVendorCatalog`) | Catalog stays pure data — same separation thermal uses (`AdbThermalParser` builds the shell command, `ThermalZoneClassifier` is data). |
| Cap on `GpuDiagnostic.probedPaths` | 10 (matches `ThermalDiagnostic.rawZoneNames`) | Same rationale: bounded export size. |

---

## 12. Effort breakdown

| File | Action | Hours (TDD red→green) |
|---|---|---|
| `core/GpuVendorCatalog.kt` | NEW | 1.0 |
| `core/GpuUsageParser.kt` | NEW | 2.5 |
| `core/model/Metrics.kt` | MOD (`GpuSnapshot`) | 0.5 |
| `core/model/GpuDiagnostic.kt` | NEW | 0.5 |
| `core/AdbBridge.kt` | MOD (`captureGpuUsage` + state map + `resetSessionState` clear + best-effort disable + helper extractions) | 4.0 |
| `core/AdbBridgeApi.kt` | MOD (interface + `RealAdbBridge` passthrough) | 0.25 |
| `testing/FakeAdbBridge.kt` | MOD (`scriptedGpu` + `setGpu` + 3 `shellResponses` keys recognized) | 0.5 |
| `viewmodel/AppViewModel.kt` | MOD (`lastGpu` + every-4-tick poll + histories + persistence + report wiring) | 2.5 |
| `viewmodel/LiveMetrics.kt` (or wherever it lives) | MOD (`gpuUsage` + `gpuAvailable` + `gpuUsageHistory` fields) | 0.5 |
| `core/SessionHistory.kt` | MOD (`SerializableEntry` + `Entry` `gpuAvailable` + `maxGpuUsage` + `gpuDiagnostic`) | 0.75 |
| `report/ReportGenerator.kt` | MOD (signature + card + chart section + `gpuDiagnosticBanner` + caveat hint) | 2.5 |
| `core/GpuUsageParserTest.kt` | NEW | 2.0 |
| `core/GpuVendorCatalogTest.kt` | NEW | 0.5 |
| `core/AdbBridgeGpuTest.kt` | NEW | 2.0 |
| `core/AdbBridgeGpuLifecycleTest.kt` | NEW | 1.5 |
| `viewmodel/AppViewModelGpuTest.kt` (or extension) | NEW/MOD | 1.5 |
| **Total** | | **22.5 h ≈ 2.8 days** |

Aligns with proposal v2 estimate (2.5-3 days TDD red→green, +0.25 day vs v1 for enable/disable lifecycle). Sub-budget per file < 4 h; longest is `AdbBridge.kt` itself.

---

## Skill resolution

`fallback-path` — loaded `sdd-design/SKILL.md` + `_shared/sdd-phase-common.md`. No Project Standards block injected by orchestrator.
