# Design — fpower-metric

Topic key: `sdd/fpower-metric/design`
Branch: `fix/autoupdater-resilience-v4-4-1` @ `f335444`
Depends on: proposal `sdd/fpower-metric/proposal`, spec `sdd/fpower-metric/spec`

The thermal v4.4.1 (`temperature-not-shown`) change is the precedent. Every component below has a live counterpart in the repo at HEAD; we follow the same layering, the same naming idiom, the same serialization rules, the same coroutine boundaries.

---

## Component map

### 1. `core/FPowerParser.kt` (NEW, pure, internal `object`)

Mirrors `core/AdbThermalParser.kt:1-241`. No `adb` invocation, no I/O. Stateless. Takes two raw strings (already-fetched sysfs payloads) plus the current FPS integer, returns an `FPowerSnapshot`.

```kotlin
internal object FPowerParser {

    private const val POWER_DIVISOR: Double = 1e12          // microA * microV -> W
    private val POWER_W_WINDOW: ClosedRange<Double> = 0.0..30.0
    private val FPOWER_MW_WINDOW: ClosedRange<Double> = 0.0..500.0
    private const val DIAGNOSTIC_PATHS_LIMIT: Int = 8

    fun parseBatteryOutput(
        currentRaw: String,
        voltageRaw: String,
        fps: Int,
        pathsTried: List<String> = emptyList(),
        lastReadouts: Map<String, String> = emptyMap(),
    ): FPowerSnapshot { ... }

    fun unavailable(reason: FPowerUnavailableReason, paths: List<String>, readouts: Map<String, String>): FPowerSnapshot { ... }
}
```

Algorithm:

1. Parse `currentRaw.trim().toLongOrNull()` → if null, return `unavailable(BATTERY_PATH_MISSING, ...)`.
2. Parse `voltageRaw.trim().toLongOrNull()` → if null, return `unavailable(BATTERY_PATH_MISSING, ...)`.
3. If the raw string equals or contains `"permission denied"` literal → `unavailable(PERMISSION_DENIED, ...)`.
4. Compute `powerW = abs(currentMicroA) * voltageMicroV / POWER_DIVISOR`.
5. If `fps <= 0` → `unavailable(FPS_ZERO, ...)`.
6. Compute `fpowerMw = powerW * 1000.0 / fps`.
7. If `powerW !in POWER_W_WINDOW || fpowerMw !in FPOWER_MW_WINDOW` → `unavailable(IMPLAUSIBLE_VALUE, ...)`.
8. Return populated `FPowerSnapshot(fpowerMwPerFrame=fpowerMw, powerW, currentMicroA, voltageMicroV, fpowerAvailable=true)`.

### 2. `core/FPowerVendorCatalog.kt` (NEW, public `object`)

Mirrors `core/ThermalZoneClassifier.kt:37-205`. Single source of truth for OEM path alternates. Strict-list, NO fuzzy matching.

```kotlin
object FPowerVendorCatalog {

    /** Battery sysfs path tuple: (currentPath, voltagePath). */
    data class PathTuple(val currentPath: String, val voltagePath: String)

    /** Ordered probe list. AOSP-canonical first; OEM alternates after. */
    val ORDERED_PATHS: List<PathTuple> = listOf(
        PathTuple("/sys/class/power_supply/battery/current_now",          "/sys/class/power_supply/battery/voltage_now"),
        PathTuple("/sys/class/power_supply/battery/batt_current_ua_now",  "/sys/class/power_supply/battery/voltage_now"),
        PathTuple("/sys/class/power_supply/Battery/current_now",          "/sys/class/power_supply/Battery/voltage_now"),
        PathTuple("/sys/class/power_supply/bms/current_now",              "/sys/class/power_supply/bms/voltage_now"),
        PathTuple("/sys/class/power_supply/bq2589x_charger/current_now",  "/sys/class/power_supply/bq2589x_charger/voltage_now"),
    )
}
```

### 3. `core/model/Metrics.kt` (EXTEND, ADDITIVE)

Add at the bottom of the existing file, alongside `ThermalSnapshot`:

```kotlin
@Serializable
data class FPowerSnapshot(
    val fpowerMwPerFrame: Double = -1.0,
    val powerW: Double = -1.0,
    val currentMicroA: Double = -1.0,
    val voltageMicroV: Double = -1.0,
    val fpowerAvailable: Boolean = true,
    val diagnostic: FPowerDiagnostic? = null,
)
```

Defaulted fields enable kotlinx-serialization-compatible decoding of pre-this-change `.gameperf` JSON (mirrors `ThermalSnapshot` v4.4.1 widening at `Metrics.kt:65`).

### 4. `core/model/FPowerDiagnostic.kt` (NEW)

Own file, mirrors `core/model/ThermalDiagnostic.kt:1-52`.

```kotlin
@Serializable
data class FPowerDiagnostic(
    val rawPathsTried: List<String>,
    val lastReadout: Map<String, String>,
    val reason: FPowerUnavailableReason,
)

@Serializable
enum class FPowerUnavailableReason {
    BATTERY_PATH_MISSING,
    FPS_ZERO,
    IMPLAUSIBLE_VALUE,
    OEM_LOCKED,
    PERMISSION_DENIED,
    UNKNOWN,
}
```

### 5. `core/AdbBridge.kt` (EXTEND)

Add `captureFPower(deviceId: String, fps: Int): FPowerSnapshot` next to `captureTemperature`. Mirrors `AdbBridge.kt:680-692`.

```kotlin
private val fpowerPathCache: MutableMap<String, FPowerVendorCatalog.PathTuple> = ConcurrentHashMap()

fun captureFPower(deviceId: String, fps: Int): FPowerSnapshot {
    val cached = fpowerPathCache[deviceId]
    val tuples = if (cached != null) listOf(cached) else FPowerVendorCatalog.ORDERED_PATHS
    val pathsTried = mutableListOf<String>()
    val readouts = mutableMapOf<String, String>()

    for (tuple in tuples) {
        pathsTried += tuple.currentPath
        val currentRaw = shell(deviceId, "cat ${tuple.currentPath}", timeoutMs = 2000)
        val voltageRaw = shell(deviceId, "cat ${tuple.voltagePath}", timeoutMs = 2000)
        readouts[tuple.currentPath] = currentRaw
        readouts[tuple.voltagePath] = voltageRaw

        val snap = FPowerParser.parseBatteryOutput(currentRaw, voltageRaw, fps, pathsTried.toList(), readouts.toMap())
        if (snap.fpowerAvailable) {
            fpowerPathCache[deviceId] = tuple
            return snap
        }
        // If reason is FPS_ZERO, do NOT iterate further — the path worked, the divisor failed.
        if (snap.diagnostic?.reason == FPowerUnavailableReason.FPS_ZERO) return snap
    }
    return FPowerParser.unavailable(FPowerUnavailableReason.BATTERY_PATH_MISSING, pathsTried, readouts)
}
```

Also extend `resetSessionState()` to call `fpowerPathCache.clear()`.

### 6. `core/AdbBridgeApi.kt` + `RealAdbBridge` (EXTEND)

Add `fun captureFPower(deviceId: String, fps: Int): FPowerSnapshot` to the interface. One-line passthrough in `RealAdbBridge`.

### 7. `core/bridge/AndroidBridge.kt` / `CompositeBridge.kt` (EXTEND)

Add `captureFPower` to the bridge interface chain. `IosBridge` returns an unavailable snapshot with `reason = UNKNOWN` (iOS deferred per proposal). `CompositeBridge` routes to the correct bridge identical to `captureTemperature` at `CompositeBridge.kt:70-71`.

### 8. `test/testing/FakeAdbBridge.kt` (EXTEND)

Mirrors `setThermal(...)` at `FakeAdbBridge.kt:101-114`.

```kotlin
@Volatile private var scriptedFPower: FPowerSnapshot? = null
fun setFPower(snapshot: FPowerSnapshot): FakeAdbBridge {
    scriptedFPower = snapshot; return this
}
override fun captureFPower(deviceId: String, fps: Int): FPowerSnapshot =
    scriptedFPower ?: FPowerSnapshot()  // sentinel (-1.0 fields, fpowerAvailable=true matches NaN-quad thermal default)
```

Bridge-level capture tests use the existing `shellResponses` map keyed on each sysfs path string (already supports substring match per `FakeAdbBridge.kt:184-189`).

### 9. `viewmodel/AppViewModel.kt` (EXTEND — 4 wiring points)

#### 9a. Initialiser next to `lastThermal` (current line `:1107`)

```kotlin
var lastFPower = FPowerSnapshot()  // sentinel, fpowerAvailable=true, fields = -1.0
```

#### 9b. Accumulators next to thermal histories (current lines `:1056-1077`)

```kotlin
val fpowerHistory = mutableListOf<Double>()
val fpowerTimed = mutableListOf<TimedSample>()
```

#### 9c. Poll alongside thermal at `iterCount % 4 == 0` (current lines `:1177-1196`)

Inside the existing `if (runThermal)` block, immediately after the thermal assignment:

```kotlin
val f = adb.captureFPower(device.id, fps = frame?.fps ?: 0)
if (shouldStop) break
lastFPower = f
```

We reuse the per-tick `frame?.fps ?: 0` already in scope at line `:1244` — but the FPower call must run AFTER frame capture in the same tick, which already happens because frame capture lives at `:1166` before the runThermal block at `:1177`.

#### 9d. History accumulation alongside `tempDieCpuHistory` (current lines `:1284-1332`)

Inside `if (shouldRecordThermal)` (or new parallel guard `iterCount % 4 == 1`):

```kotlin
if (lastFPower.fpowerAvailable && lastFPower.fpowerMwPerFrame > 0) {
    fpowerHistory.add(lastFPower.fpowerMwPerFrame)
    fpowerTimed.add(TimedSample(sampleSecond, lastFPower.fpowerMwPerFrame))
    if (fpowerHistory.size > MAX_HISTORY_SIZE) fpowerHistory.removeFirst()
    if (fpowerTimed.size > MAX_HISTORY_SIZE) fpowerTimed.removeFirst()
}
```

#### 9e. LiveMetrics emission (current line `:1357`)

Extend `LiveMetrics` data class with `fpower: Double = 0.0`, `fpowerHistory: List<Double> = emptyList()`, `fpowerTimed: List<TimedSample> = emptyList()`. Add to the constructor call at `:1357`:

```kotlin
fpower = if (lastFPower.fpowerAvailable) lastFPower.fpowerMwPerFrame else 0.0,
fpowerHistory = if (snapshotHistories) fpowerHistory.toList() else prev.fpowerHistory,
fpowerTimed   = if (snapshotHistories) fpowerTimed.toList()   else prev.fpowerTimed,
```

#### 9f. Post-loop aggregates + report wiring (current lines `:1686-1729`)

```kotlin
val fpowerAvg  = if (fpowerHistory.isNotEmpty()) fpowerHistory.average() else 0.0
val fpowerPeak = fpowerHistory.maxOrNull() ?: 0.0
```

Extend `ReportGenerator.generate(...)` call with:

```kotlin
fpowerHistory = fpowerHistory,
fpowerAvg = fpowerAvg,
fpowerPeak = fpowerPeak,
fpowerAvailable = lastFPower.fpowerAvailable,
fpowerDiagnostic = lastFPower.diagnostic,
```

Extend `SessionResult(...)` at `:1735` with the same five fields.

Extend `SessionHistory.HistoryEntry(...)` builder at `:1780` with the same five fields (named-args, additive).

### 10. `report/ReportGenerator.kt` (EXTEND)

New named-args parameters in `ReportGenerator.generate(...)` (all defaulted so legacy fixtures compile):

```kotlin
fpowerHistory: List<Double> = emptyList(),
fpowerAvg: Double = 0.0,
fpowerPeak: Double = 0.0,
fpowerAvailable: Boolean = true,
fpowerDiagnostic: FPowerDiagnostic? = null,
```

New HTML section: `<section class="fpower-card">` with avg, peak, color-coded class, line chart of history. CSS classes:

```css
.fpower-green { color: var(--fpower-green, #1B873F); }
.fpower-amber { color: var(--fpower-amber, #B26B00); }
.fpower-red   { color: var(--fpower-red,   #B11D1D); }
```

Helper: `private fun fpowerBand(value: Double): String =
    when { value < 50.0 -> "fpower-green"; value < 65.0 -> "fpower-amber"; else -> "fpower-red" }`.

Banner: when `!fpowerAvailable` AND `fpowerDiagnostic != null`, render the same Spanish-tuteo-formal banner pattern as the v4.4.1 thermal diagnostic banner — text template:

> "No pudimos leer el consumo de batería en este dispositivo (motivo: `${diagnostic.reason}`). Probamos los siguientes paths sysfs: `${diagnostic.rawPathsTried.joinToString(", ")}`. Si querés sumar tu vendor al catálogo, abrí un issue con esa lista."

### 11. `ui/screens/CaptureScreen.kt` (EXTEND, minor)

Add an FPower live tile alongside `tempCpu`. Single MutableState read off `LiveMetrics.fpower`, color class via `fpowerBand`. Identical pattern to the existing `tempCpu` tile.

---

## Architectural decisions (ADRs)

### ADR-1: Mirror the thermal architecture exactly

We do NOT invent a new pattern. We do NOT introduce a `power/` sub-package. We do NOT use a `sealed class` for the snapshot. Every layer copies the v4.4.1 thermal precedent. Rationale: thermal has shipped, has user trust, has integration tests already exercising the every-4-tick cadence and the diagnostic banner. Mirroring minimises review surface, minimises bug surface, minimises learning cost.

### ADR-2: `abs(current_now)` instead of detecting charging state

Both kernel sign conventions exist in the wild (positive-on-charge per `power_supply.h` core, positive-on-discharge per some OEM quirks). Detecting which convention applies at runtime is fragile (we'd need to correlate against charging state from another `dumpsys`, doubling the I/O). `abs()` is a one-character fix that neutralises both conventions and the energy delivered to the device is magnitude-only regardless of sign. The existing `disableCharging` at session start already removes the systemic charging-current contamination.

### ADR-3: FPS = same per-tick value, NOT smoothed

The thermal block at `AppViewModel.kt:1180` runs after frame capture at `:1166`, so `frame?.fps ?: 0` is in scope and is the most-recent honest reading. Smoothing belongs at aggregation time (per-phase, owned by `kpi-scoring-framework`). Using the raw value keeps `FPowerParser` pure and the timeline truthful. `fps = 0` raises `FPS_ZERO` and that tick is dropped — same defensive shape as thermal `ALL_TEMPS_INVALID`.

### ADR-4: Plausibility window mirrors thermal envelopes

`0 W < powerW < 30 W`, `0 mW/frame < fpowerMwPerFrame < 500 mW/frame`. Empirically grounded in obs #312 case-study numbers and exploration §"Plausibility window". The 30 W upper bound is generous enough to cover fast-charge transients post-`disableCharging` failure modes.

### ADR-5: Diagnostic banner uses Spanish-tuteo-formal copy

The repo's user-facing strings in v4.4.1 thermal banner use tuteo-formal voice ("Tu dispositivo no expone ... Si querés ayudarnos, ..."). We extend the same voice register for consistency. Tone matches the personality rule in `~/.config/opencode/AGENTS.md` Rioplatense default.

### ADR-6: NO new coroutine, NO new timer, NO new threading concern

The poll lives inside the existing `startCapture` while-loop alongside the thermal poll. The whole change adds one `adb shell cat` × 2 paths per FPower poll (~2 shells per ~2 s). On a cached-path tick, that's well below the existing thermal cost.

### ADR-7: Bridge cache scoped to session (`resetSessionState` clears it)

We do NOT carry the per-device cache across sessions because the user may swap devices, switch USB ports, root/unroot, etc. Clearing on session start mirrors thermal hygiene and the existing `prevPidProcJiffies` lock cache pattern at `AdbBridge.kt:645-660`.

---

## Test architecture

| Test file | Coverage focus |
|---|---|
| `core/FPowerParserTest.kt` | FPW-002, FPW-003, FPW-004 (model defaults), FPW-005 (each reason), FPW-011 (plausibility) — all pure unit cases with literal raw strings. |
| `core/FPowerVendorCatalogTest.kt` | FPW-010 — assert each tuple is present and ordered AOSP-first. |
| `core/AdbBridgeFPowerTest.kt` | FPW-001 (catalog fallback), FPW-006 (cache hit/miss + reset), uses `FakeAdbBridge.shellResponses`. |
| `viewmodel/AppViewModelFPowerTest.kt` | FPW-007 (cadence: 8-tick capture yields 2 polls), FPW-008 (persisted fields populated). Uses `FakeAdbBridge.setFPower(...)`. |
| `report/ReportGeneratorFPowerTest.kt` | FPW-009 (color bands, banner copy, defaulted-args legacy fixture). |

All tests follow the existing TDD red→green strict workflow per repo convention. Detekt baseline kept clean — no suppressions.

---

## Risks acknowledged in this design

- The Samsung One UI `batt_current_ua_now` path is documented as "always positive" in vendor reverse-engineering; we still `abs()` defensively. Confirmed safe.
- The Huawei capital-B `Battery/` branch may not exist post-HarmonyOS NEXT pivot — we catalog-fallback past it harmlessly.
- The `bq2589x_charger` Xiaomi/OnePlus path reads charger-IC, not battery-IC; values can differ by 5-10 % from BMS readings. Acceptable for v1; documented in the catalog comment.
- Pre-v4.4.1 `.gameperf` round-trip is covered by `@Serializable` defaults — there is one historical risk: `kotlinx.serialization` strict-mode JSON might still warn on unknown fields if the user downgrades the binary after capturing. Out of scope for this change; the repo already uses `Json { ignoreUnknownKeys = true }` per existing session loaders.
