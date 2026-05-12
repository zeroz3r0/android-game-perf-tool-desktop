# Spec — power-usage capability (fpower-metric change)

Topic key: `sdd/fpower-metric/spec`
Change: `fpower-metric`
Capability: `power-usage`
Scope: Android (this delta). iOS deferred.

Stable requirement IDs FPW-001..FPW-013. EARS keyword style + GIVEN/WHEN/THEN scenarios.

---

## FPW-001 — Battery sysfs read flow

The system **shall** read instantaneous battery current and voltage from Android sysfs on every FPower poll, attempting the AOSP-canonical path first and falling back to vendor-catalog alternates only on failure.

### Scenarios

GIVEN a stock AOSP / Pixel / Samsung device with `/sys/class/power_supply/battery/{current_now,voltage_now}` readable by `shell` UID
WHEN `AdbBridge.captureFPower(deviceId, fps)` runs
THEN the bridge **shall** issue one `adb shell cat /sys/class/power_supply/battery/current_now` and one `adb shell cat /sys/class/power_supply/battery/voltage_now`
AND it **shall** cache the successful path tuple per-device for the duration of the session
AND it **shall** return an `FPowerSnapshot` with `fpowerAvailable = true` when both reads parse to numeric values.

GIVEN a device where the AOSP path returns empty / errors
WHEN the bridge falls back through `FPowerVendorCatalog` alternates (Samsung `batt_current_ua_now`, Huawei `/sys/class/power_supply/Battery/...`, Xiaomi `bms/current_now`, OnePlus `bq2589x_charger/...`)
THEN the **first** path tuple that yields both a numeric `current` AND a numeric `voltage` **shall** be cached
AND the snapshot **shall** be returned with `fpowerAvailable = true`.

GIVEN no path in the catalog yields a numeric pair
WHEN the bridge has exhausted all alternates
THEN the snapshot **shall** carry `fpowerAvailable = false` and `diagnostic.reason = BATTERY_PATH_MISSING`.

---

## FPW-002 — Power(W) calculation

The system **shall** compute `Power(W) = abs(current_now_microA) * voltage_now_microV / 1e12`, using `abs()` to neutralise the kernel/OEM sign-convention divergence (positive-on-charge vs negative-on-discharge).

### Scenarios

GIVEN `current_now = -350000` (microA, discharging) and `voltage_now = 4100000` (microV)
WHEN `FPowerParser.parseBatteryOutput("-350000", "4100000", fps = 60)` runs
THEN the resulting `FPowerSnapshot.powerW` **shall** equal `1.435` (within `±0.001`).

GIVEN `current_now = 350000` (microA, OEM positive-on-discharge variant) and `voltage_now = 4100000`
WHEN the parser runs
THEN `powerW` **shall** equal `1.435` (same magnitude, sign neutralised).

GIVEN `current_now = 0` and `voltage_now = 4100000`
WHEN the parser runs
THEN `powerW` **shall** equal `0.0` AND the snapshot **shall** carry `fpowerAvailable = false`, `diagnostic.reason = IMPLAUSIBLE_VALUE` (zero current at non-zero voltage during gameplay is a sensor fault).

---

## FPW-003 — FPower (mW/frame) calculation

The system **shall** compute `FPower(mW/frame) = Power(W) * 1000.0 / fps` only when `fps > 0`, otherwise it **shall** flag the snapshot unavailable with reason `FPS_ZERO`.

### Scenarios

GIVEN `powerW = 2.4`, `fps = 60`
WHEN the parser computes FPower
THEN `fpowerMwPerFrame` **shall** equal `40.0` (within `±0.01`).

GIVEN `powerW = 4.8`, `fps = 30`
WHEN the parser computes FPower
THEN `fpowerMwPerFrame` **shall** equal `160.0`.

GIVEN `powerW = 2.4`, `fps = 0`
WHEN the parser computes FPower
THEN the snapshot **shall** carry `fpowerAvailable = false` AND `diagnostic.reason = FPS_ZERO` AND `fpowerMwPerFrame = -1.0` (sentinel).

GIVEN `powerW = 2.4`, `fps = -1` (FrameSnapshot sentinel for capture failure)
WHEN the parser computes FPower
THEN the snapshot **shall** behave identically to the `fps = 0` case (reason `FPS_ZERO`).

---

## FPW-004 — FPowerSnapshot model

The system **shall** expose an `@Serializable` `FPowerSnapshot` data class living in `core/model/Metrics.kt`, carrying: `fpowerMwPerFrame: Double`, `powerW: Double`, `currentMicroA: Double`, `voltageMicroV: Double`, `fpowerAvailable: Boolean = true`, `diagnostic: FPowerDiagnostic? = null`. All numeric fields default to `-1.0` sentinel for unavailability and the boolean defaults to `true` for backward compatibility with pre-v4.4.1 `.gameperf` decoders (mirrors `ThermalSnapshot` v4.4.1 widening at `core/model/Metrics.kt:65`).

### Scenarios

GIVEN a healthy capture
WHEN the parser returns
THEN all four numeric fields **shall** be populated (no `-1.0` sentinel).

GIVEN `kotlinx.serialization` deserialises a pre-v4.4.1 `.gameperf` JSON that has NO FPower fields
WHEN the decoder runs
THEN the resulting snapshot **shall** decode with `fpowerAvailable = true`, `fpowerMwPerFrame = -1.0`, `diagnostic = null` — and the report HTML **shall** render the legacy "no FPower data" cell without throwing.

---

## FPW-005 — FPowerDiagnostic + reasons

The system **shall** expose an `@Serializable` `FPowerDiagnostic(rawPathsTried: List<String>, lastReadout: Map<String,String>, reason: FPowerUnavailableReason)` data class in `core/model/FPowerDiagnostic.kt`.

The system **shall** expose an `@Serializable` `enum class FPowerUnavailableReason { BATTERY_PATH_MISSING, FPS_ZERO, IMPLAUSIBLE_VALUE, OEM_LOCKED, PERMISSION_DENIED, UNKNOWN }`.

### Scenarios

GIVEN all catalog paths returned empty strings via `adb shell`
WHEN `FPowerParser` finishes
THEN `diagnostic.reason` **shall** be `BATTERY_PATH_MISSING` AND `diagnostic.rawPathsTried` **shall** list each path attempted (capped at 8 entries).

GIVEN the AOSP path returned `cat: permission denied` literal in stdout
WHEN `FPowerParser` finishes
THEN `diagnostic.reason` **shall** be `PERMISSION_DENIED`.

GIVEN the parser computed `Power = 47.2 W` (outside plausibility window)
WHEN it runs the FPW-011 check
THEN `diagnostic.reason` **shall** be `IMPLAUSIBLE_VALUE` AND `fpowerAvailable = false`.

GIVEN `fps = 0`
WHEN the parser runs
THEN `diagnostic.reason` **shall** be `FPS_ZERO`.

---

## FPW-006 — Stateful bridge cache

The system **shall** maintain a per-device cache `Map<DeviceId, FPowerPathTuple>` inside `AdbBridge`. Once a `(currentPath, voltagePath)` tuple yields a successful read, subsequent `captureFPower` calls for that device **shall** skip the catalog fallback loop and read directly from the cached pair. The cache **shall** be cleared by `resetSessionState()`.

### Scenarios

GIVEN a Samsung device where `batt_current_ua_now` was cached during tick 1
WHEN tick 2 runs `captureFPower`
THEN the bridge **shall** issue exactly 2 `adb shell` calls (current + voltage), NOT iterate the catalog.

GIVEN `resetSessionState()` is called between two sessions
WHEN the next `captureFPower` runs
THEN the catalog walk **shall** restart from the AOSP-canonical path (cache cleared).

---

## FPW-007 — Cadence every 4 ticks (~2 s)

The system **shall** poll `captureFPower` every fourth iteration of the `AppViewModel.startCapture` loop, alongside the existing thermal poll (`iterCount % 4 == 0`), maintaining a tiered-cadence parity with thermal sampling.

### Scenarios

GIVEN a session running 8 ticks
WHEN the loop completes
THEN `captureFPower` **shall** have been invoked exactly 2 times (at `iterCount == 0` and `iterCount == 4`).

GIVEN the user-facing live HUD reads `LiveMetrics.fpower`
WHEN a non-poll tick occurs (`iterCount % 4 != 0`)
THEN the HUD **shall** display the last-known `fpower` value, identical to the thermal sticky-last-value pattern at `AppViewModel.kt:1107`.

---

## FPW-008 — Persisted session payload

The system **shall** persist `fpowerHistory: List<Double>`, `fpowerTimed: List<TimedSample>`, `fpowerAvg: Double`, `fpowerPeak: Double`, `fpowerAvailable: Boolean = true`, `fpowerDiagnostic: FPowerDiagnostic? = null` into `SessionHistory.HistoryEntry` and `SessionResult`. All fields default for pre-v4.4.1 read compatibility.

### Scenarios

GIVEN a complete capture session
WHEN `_result.value = SessionResult(...)` is assigned at `AppViewModel.kt:1735`
THEN it **shall** carry `fpowerAvg = fpowerHistory.average()` and `fpowerPeak = fpowerHistory.maxOrNull() ?: 0.0`.

GIVEN `SessionHistory.addEntry(pendingEntry)` at `AppViewModel.kt:1828`
WHEN the entry serialises to disk
THEN the JSON **shall** contain `fpowerHistory`, `fpowerTimed`, `fpowerAvg`, `fpowerPeak`, `fpowerAvailable`, `fpowerDiagnostic`.

GIVEN a pre-v4.4.1 `.gameperf` JSON loaded via `SessionHistory.load()`
WHEN decoded
THEN it **shall** decode cleanly with `fpowerHistory = emptyList()`, `fpowerAvg = 0.0`, `fpowerPeak = 0.0`, `fpowerAvailable = true`, `fpowerDiagnostic = null`.

---

## FPW-009 — Report HTML rendering

The system **shall** render an FPower card in the HTML report with: avg, peak, line chart of `fpowerHistory`, color-coded band based on PerfDog anchors (green `<50 mW/frame`, amber `50–65 mW/frame`, red `>65 mW/frame`). When `fpowerAvailable == false` the card **shall** render an N/D placeholder + Spanish-tuteo-formal diagnostic banner listing the raw paths tried.

### Scenarios

GIVEN `fpowerAvg = 38.4`, `fpowerPeak = 51.2`
WHEN `ReportGenerator.generate(...)` runs
THEN the card avg cell **shall** carry CSS class `fpower-green` (the avg lands in `<50`) AND the peak cell **shall** carry CSS class `fpower-amber` (peak lands in 50–65).

GIVEN `fpowerAvailable = false` and `fpowerDiagnostic.reason = BATTERY_PATH_MISSING`
WHEN the report renders
THEN the FPower card **shall** display `"N/D"` for avg/peak AND a banner element **shall** render the Spanish-tuteo-formal text "No pudimos leer el consumo de batería en este dispositivo. Probamos los siguientes paths sysfs: ..." listing `diagnostic.rawPathsTried`.

GIVEN `fpowerAvailable = true` and `fpowerHistory.isEmpty()` (edge: ultra-short capture)
WHEN the report renders
THEN the card **shall** display the legacy `"N/D"` placeholder WITHOUT the diagnostic banner (matches the v4.4.1 thermal `thermalAvailable = true` + empty history fallback).

---

## FPW-010 — Vendor catalog

The system **shall** expose an `FPowerVendorCatalog` object listing battery sysfs path tuples in priority order: AOSP-canonical first, then OEM-specific fallbacks. Catalog entries are private `val` sets of `(currentPath: String, voltagePath: String)` pairs, modelled on `ThermalZoneClassifier`.

### Scenarios

GIVEN the catalog is initialised
WHEN inspected
THEN it **shall** contain at minimum:
- `("/sys/class/power_supply/battery/current_now", "/sys/class/power_supply/battery/voltage_now")` (AOSP, Pixel, Samsung, Xiaomi, OnePlus default)
- `("/sys/class/power_supply/battery/batt_current_ua_now", "/sys/class/power_supply/battery/voltage_now")` (Samsung One UI 5+ alternate)
- `("/sys/class/power_supply/Battery/current_now", "/sys/class/power_supply/Battery/voltage_now")` (Huawei capital-B branch)
- `("/sys/class/power_supply/bms/current_now", "/sys/class/power_supply/bms/voltage_now")` (Xiaomi BMS-side reading)
- `("/sys/class/power_supply/bq2589x_charger/current_now", "/sys/class/power_supply/bq2589x_charger/voltage_now")` (OnePlus/Realme charger IC reading).

GIVEN a unit test adds a new OEM tuple
WHEN the test calls the catalog
THEN the new tuple **shall** be picked up without modifying any other source file (single source of truth at `FPowerVendorCatalog.kt`).

---

## FPW-011 — Plausibility window

The system **shall** reject snapshots where `powerW <= 0`, `powerW >= 30`, `fpowerMwPerFrame <= 0`, or `fpowerMwPerFrame >= 500`, flagging them `fpowerAvailable = false` with `diagnostic.reason = IMPLAUSIBLE_VALUE` (mirrors `AdbThermalParser.withinPlausibilityWindow`).

### Scenarios

GIVEN `powerW = 32.0` (fast-charge mid-spike post `abs`)
WHEN the parser checks plausibility
THEN the snapshot **shall** be marked unavailable with `IMPLAUSIBLE_VALUE`.

GIVEN `fpowerMwPerFrame = 0.0` (caused by `powerW = 0` at `fps = 60`)
WHEN the parser checks plausibility
THEN the snapshot **shall** be marked unavailable with `IMPLAUSIBLE_VALUE`.

GIVEN `fpowerMwPerFrame = 499.9`
WHEN the parser checks plausibility
THEN the snapshot **shall** be marked AVAILABLE (boundary is exclusive `>= 500`).

---

## FPW-012 — Backward compat with v4.4.1 `.gameperf`

The system **shall** decode pre-v4.4.1 (and pre-this-change) `.gameperf` exports without throwing. All FPower-related fields **shall** carry defaults preserving the legacy "no FPower" semantics.

### Scenarios

GIVEN a `.gameperf` export written by v4.4.1
WHEN loaded by post-this-change `SessionHistory.load()`
THEN it **shall** decode cleanly AND the report regeneration **shall** render the legacy view (no FPower card / FPower card with N/D placeholder).

GIVEN a `.gameperf` export written by post-this-change build, loaded back by the same build
WHEN displayed
THEN `fpowerHistory` and `fpowerTimed` **shall** round-trip identically.

---

## FPW-013 — Detekt clean

The change **shall** introduce no new detekt warnings or suppressions. The existing detekt baseline (3 pre-existing items: `HomeScreen` 751/750, `HelperLogWatcherTest`, `MiniGraphWithEvents`) **shall not** grow.

### Scenarios

GIVEN `./gradlew detekt` runs on the change branch
WHEN it completes
THEN exit code **shall** be `0` AND the baseline file **shall** not have grown.
