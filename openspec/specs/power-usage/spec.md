# Spec — Power Usage

This capability covers per-frame power consumption measurement on Android via battery sysfs reads, with PerfDog-style FPower (mW/frame) as the headline metric. iOS support is deferred.

Conventions:
- Requirement IDs are stable and code-referenceable. They map directly to test names.
- Requirement statements use EARS keywords (SHALL, MUST, WHEN, WHILE, WHERE, IF/THEN).
- Scenarios use Given/When/Then for testability.
- User-facing strings are in Castilian Spanish formal **tuteo** per project convention.

> **Source delta:** `fpower-metric` (archived 2026-05-12). All requirements in this capability were added by that change. First capability landing under `power-usage` — no prior requirements existed.

---

## ADDED Requirements

### Requirement: FPW-001 — Battery sysfs read flow

The system SHALL read instantaneous battery current and voltage from Android sysfs on every FPower poll, attempting the AOSP-canonical path first and falling back to vendor-catalog alternates only on failure.

#### Scenario: AOSP-canonical path succeeds on stock device

- GIVEN a stock AOSP / Pixel / Samsung device with `/sys/class/power_supply/battery/{current_now,voltage_now}` readable by the `shell` UID
- WHEN `AdbBridge.captureFPower(deviceId, fps)` runs
- THEN the bridge MUST issue one `adb shell cat /sys/class/power_supply/battery/current_now` and one `adb shell cat /sys/class/power_supply/battery/voltage_now`
- AND it MUST cache the successful path tuple per-device for the duration of the session
- AND it MUST return an `FPowerSnapshot` with `fpowerAvailable = true` when both reads parse to numeric values

#### Scenario: AOSP path fails, vendor alternate succeeds

- GIVEN a device where the AOSP path returns empty or errors
- WHEN the bridge falls back through `FPowerVendorCatalog` alternates (Samsung `batt_current_ua_now`, Huawei `/sys/class/power_supply/Battery/...`, Xiaomi `bms/current_now`, OnePlus `bq2589x_charger/...`)
- THEN the FIRST path tuple that yields both a numeric `current` AND a numeric `voltage` MUST be cached
- AND the snapshot MUST be returned with `fpowerAvailable = true`

#### Scenario: No catalog path yields a numeric pair

- GIVEN no path in the catalog yields a numeric pair
- WHEN the bridge has exhausted all alternates
- THEN the snapshot MUST carry `fpowerAvailable = false` and `diagnostic.reason = BATTERY_PATH_MISSING`

---

### Requirement: FPW-002 — Power(W) calculation

The system SHALL compute `Power(W) = abs(current_now_microA) * voltage_now_microV / 1e12`, using `abs()` to neutralise the kernel/OEM sign-convention divergence (positive-on-charge vs negative-on-discharge).

#### Scenario: discharging device (negative current convention)

- GIVEN `current_now = -350000` microA (discharging) and `voltage_now = 4100000` microV
- WHEN `FPowerParser.parseBatteryOutput("-350000", "4100000", fps = 60)` runs
- THEN the resulting `FPowerSnapshot.powerW` MUST equal `1.435` (within ±0.001)

#### Scenario: OEM positive-on-discharge variant

- GIVEN `current_now = 350000` microA (OEM positive-on-discharge variant) and `voltage_now = 4100000` microV
- WHEN the parser runs
- THEN `powerW` MUST equal `1.435` (same magnitude, sign neutralised by `abs`)

#### Scenario: zero current at non-zero voltage is implausible

- GIVEN `current_now = 0` and `voltage_now = 4100000`
- WHEN the parser runs
- THEN `powerW` MUST equal `0.0`
- AND the snapshot MUST carry `fpowerAvailable = false`, `diagnostic.reason = IMPLAUSIBLE_VALUE` (zero current at non-zero voltage during gameplay is a sensor fault)

---

### Requirement: FPW-003 — FPower (mW/frame) calculation

The system SHALL compute `FPower(mW/frame) = Power(W) * 1000.0 / fps` only when `fps > 0`. IF `fps <= 0`, THEN the system SHALL flag the snapshot unavailable with reason `FPS_ZERO`.

#### Scenario: typical 60 fps gameplay

- GIVEN `powerW = 2.4`, `fps = 60`
- WHEN the parser computes FPower
- THEN `fpowerMwPerFrame` MUST equal `40.0` (within ±0.01)

#### Scenario: capped 30 fps gameplay

- GIVEN `powerW = 4.8`, `fps = 30`
- WHEN the parser computes FPower
- THEN `fpowerMwPerFrame` MUST equal `160.0`

#### Scenario: fps = 0 yields FPS_ZERO

- GIVEN `powerW = 2.4`, `fps = 0`
- WHEN the parser computes FPower
- THEN the snapshot MUST carry `fpowerAvailable = false`
- AND `diagnostic.reason = FPS_ZERO`
- AND `fpowerMwPerFrame = -1.0` (sentinel)

#### Scenario: fps = -1 (FrameSnapshot capture-failure sentinel)

- GIVEN `powerW = 2.4`, `fps = -1` (FrameSnapshot sentinel for capture failure)
- WHEN the parser computes FPower
- THEN the snapshot MUST behave identically to the `fps = 0` case (reason `FPS_ZERO`)

---

### Requirement: FPW-004 — FPowerSnapshot model

The system SHALL expose an `@Serializable` `FPowerSnapshot` data class in `core/model/Metrics.kt`, carrying: `fpowerMwPerFrame: Double`, `powerW: Double`, `currentMicroA: Double`, `voltageMicroV: Double`, `fpowerAvailable: Boolean = true`, `diagnostic: FPowerDiagnostic? = null`. All numeric fields default to `-1.0` sentinel for unavailability and the boolean defaults to `true` for backward compatibility with pre-v4.4.1 `.gameperf` decoders (mirrors the `ThermalSnapshot` v4.4.1 widening at `core/model/Metrics.kt:65`).

#### Scenario: healthy capture populates all numeric fields

- GIVEN a healthy capture
- WHEN the parser returns
- THEN all four numeric fields MUST be populated (no `-1.0` sentinel)

#### Scenario: legacy decoder reads pre-v4.4.1 JSON

- GIVEN `kotlinx.serialization` deserialises a pre-v4.4.1 `.gameperf` JSON that has NO FPower fields
- WHEN the decoder runs
- THEN the resulting snapshot MUST decode with `fpowerAvailable = true`, `fpowerMwPerFrame = -1.0`, `diagnostic = null`
- AND the report HTML MUST render the legacy "no FPower data" cell without throwing

---

### Requirement: FPW-005 — FPowerDiagnostic and unavailability reasons

The system SHALL expose an `@Serializable` `FPowerDiagnostic(rawPathsTried: List<String>, lastReadout: Map<String,String>, reason: FPowerUnavailableReason)` data class in `core/model/FPowerDiagnostic.kt`.

The system SHALL expose an `@Serializable` `enum class FPowerUnavailableReason { BATTERY_PATH_MISSING, FPS_ZERO, IMPLAUSIBLE_VALUE, OEM_LOCKED, PERMISSION_DENIED, UNKNOWN }`.

#### Scenario: all catalog paths returned empty

- GIVEN all catalog paths returned empty strings via `adb shell`
- WHEN `FPowerParser` finishes
- THEN `diagnostic.reason` MUST be `BATTERY_PATH_MISSING`
- AND `diagnostic.rawPathsTried` MUST list each path attempted (capped at 8 entries)

#### Scenario: shell permission denied

- GIVEN the AOSP path returned `cat: permission denied` literal in stdout
- WHEN `FPowerParser` finishes
- THEN `diagnostic.reason` MUST be `PERMISSION_DENIED`

#### Scenario: out-of-window power reading

- GIVEN the parser computed `Power = 47.2 W` (outside plausibility window)
- WHEN it runs the FPW-011 check
- THEN `diagnostic.reason` MUST be `IMPLAUSIBLE_VALUE`
- AND `fpowerAvailable = false`

#### Scenario: fps zero diagnostic

- GIVEN `fps = 0`
- WHEN the parser runs
- THEN `diagnostic.reason` MUST be `FPS_ZERO`

---

### Requirement: FPW-006 — Stateful bridge cache

The system SHALL maintain a per-device cache `Map<DeviceId, FPowerPathTuple>` inside `AdbBridge`. Once a `(currentPath, voltagePath)` tuple yields a successful read, subsequent `captureFPower` calls for that device MUST skip the catalog fallback loop and read directly from the cached pair. The cache MUST be cleared by `resetSessionState()`.

#### Scenario: cache hit on second tick

- GIVEN a Samsung device where `batt_current_ua_now` was cached during tick 1
- WHEN tick 2 runs `captureFPower`
- THEN the bridge MUST issue exactly 2 `adb shell` calls (current + voltage)
- AND it MUST NOT iterate the catalog

#### Scenario: resetSessionState clears the cache

- GIVEN `resetSessionState()` is called between two sessions
- WHEN the next `captureFPower` runs
- THEN the catalog walk MUST restart from the AOSP-canonical path (cache cleared)

---

### Requirement: FPW-007 — Cadence every 4 ticks (~2 s)

The system SHALL poll `captureFPower` every fourth iteration of the `AppViewModel.startCapture` loop, alongside the existing thermal poll (`iterCount % 4 == 0`), maintaining a tiered-cadence parity with thermal sampling.

#### Scenario: 8-tick session triggers exactly 2 polls

- GIVEN a session running 8 ticks
- WHEN the loop completes
- THEN `captureFPower` MUST have been invoked exactly 2 times (at `iterCount == 0` and `iterCount == 4`)

#### Scenario: HUD shows last-known value between polls

- GIVEN the user-facing live HUD reads `LiveMetrics.fpower`
- WHEN a non-poll tick occurs (`iterCount % 4 != 0`)
- THEN the HUD MUST display the last-known `fpower` value, identical to the thermal sticky-last-value pattern at `AppViewModel.kt:1107`

---

### Requirement: FPW-008 — Persisted session payload

The system SHALL persist `fpowerHistory: List<Double>`, `fpowerTimed: List<TimedSample>`, `fpowerAvg: Double`, `fpowerPeak: Double`, `fpowerAvailable: Boolean = true`, `fpowerDiagnostic: FPowerDiagnostic? = null` into `SessionHistory.HistoryEntry` and `SessionResult`. All fields MUST default for pre-v4.4.1 read compatibility.

#### Scenario: SessionResult carries aggregates

- GIVEN a complete capture session
- WHEN `_result.value = SessionResult(...)` is assigned at `AppViewModel.kt:1735`
- THEN it MUST carry `fpowerAvg = fpowerHistory.average()`
- AND `fpowerPeak = fpowerHistory.maxOrNull() ?: 0.0`

#### Scenario: HistoryEntry serialises FPower fields

- GIVEN `SessionHistory.addEntry(pendingEntry)` at `AppViewModel.kt:1828`
- WHEN the entry serialises to disk
- THEN the JSON MUST contain `fpowerHistory`, `fpowerTimed`, `fpowerAvg`, `fpowerPeak`, `fpowerAvailable`, `fpowerDiagnostic`

#### Scenario: pre-v4.4.1 JSON decodes with defaults

- GIVEN a pre-v4.4.1 `.gameperf` JSON loaded via `SessionHistory.load()`
- WHEN decoded
- THEN it MUST decode cleanly with `fpowerHistory = emptyList()`, `fpowerAvg = 0.0`, `fpowerPeak = 0.0`, `fpowerAvailable = true`, `fpowerDiagnostic = null`

---

### Requirement: FPW-009 — Report HTML rendering

The system SHALL render an FPower card in the HTML report with: avg, peak, line chart of `fpowerHistory`, color-coded band based on PerfDog anchors (green `<50 mW/frame`, amber `50–65 mW/frame`, red `>65 mW/frame`). WHEN `fpowerAvailable == false`, the card MUST render an N/D placeholder plus a Spanish-tuteo-formal diagnostic banner listing the raw paths tried.

#### Scenario: green avg, amber peak

- GIVEN `fpowerAvg = 38.4`, `fpowerPeak = 51.2`
- WHEN `ReportGenerator.generate(...)` runs
- THEN the card avg cell MUST carry CSS class `fpower-green` (avg lands in `<50`)
- AND the peak cell MUST carry CSS class `fpower-amber` (peak lands in 50–65)

#### Scenario: unavailable renders N/D + diagnostic banner

- GIVEN `fpowerAvailable = false` and `fpowerDiagnostic.reason = BATTERY_PATH_MISSING`
- WHEN the report renders
- THEN the FPower card MUST display `"N/D"` for avg/peak
- AND a banner element MUST render the Spanish-tuteo-formal text "No pudimos leer el consumo de batería en este dispositivo. Probamos los siguientes paths sysfs: ..." listing `diagnostic.rawPathsTried`

#### Scenario: available but empty history (ultra-short capture)

- GIVEN `fpowerAvailable = true` and `fpowerHistory.isEmpty()` (edge case: ultra-short capture)
- WHEN the report renders
- THEN the card MUST display the legacy `"N/D"` placeholder WITHOUT the diagnostic banner (matches the v4.4.1 thermal `thermalAvailable = true` + empty history fallback)

---

### Requirement: FPW-010 — Vendor catalog

The system SHALL expose an `FPowerVendorCatalog` object listing battery sysfs path tuples in priority order: AOSP-canonical first, then OEM-specific fallbacks. Catalog entries MUST be private `val` sets of `(currentPath: String, voltagePath: String)` pairs, modelled on `ThermalZoneClassifier`.

#### Scenario: catalog contains the 5 baseline tuples

- GIVEN the catalog is initialised
- WHEN inspected
- THEN it MUST contain at minimum:
  - `("/sys/class/power_supply/battery/current_now", "/sys/class/power_supply/battery/voltage_now")` (AOSP, Pixel, Samsung, Xiaomi, OnePlus default)
  - `("/sys/class/power_supply/battery/batt_current_ua_now", "/sys/class/power_supply/battery/voltage_now")` (Samsung One UI 5+ alternate)
  - `("/sys/class/power_supply/Battery/current_now", "/sys/class/power_supply/Battery/voltage_now")` (Huawei capital-B branch)
  - `("/sys/class/power_supply/bms/current_now", "/sys/class/power_supply/bms/voltage_now")` (Xiaomi BMS-side reading)
  - `("/sys/class/power_supply/bq2589x_charger/current_now", "/sys/class/power_supply/bq2589x_charger/voltage_now")` (OnePlus/Realme charger IC reading)

#### Scenario: catalog is single source of truth

- GIVEN a unit test adds a new OEM tuple
- WHEN the test calls the catalog
- THEN the new tuple MUST be picked up without modifying any other source file (single source of truth at `FPowerVendorCatalog.kt`)

---

### Requirement: FPW-011 — Plausibility window

The system SHALL reject snapshots where `powerW <= 0`, `powerW >= 30`, `fpowerMwPerFrame <= 0`, or `fpowerMwPerFrame >= 500`, flagging them `fpowerAvailable = false` with `diagnostic.reason = IMPLAUSIBLE_VALUE` (mirrors `AdbThermalParser.withinPlausibilityWindow`).

#### Scenario: fast-charge mid-spike rejected

- GIVEN `powerW = 32.0` (fast-charge mid-spike post-`abs`)
- WHEN the parser checks plausibility
- THEN the snapshot MUST be marked unavailable with `IMPLAUSIBLE_VALUE`

#### Scenario: zero FPower from zero power

- GIVEN `fpowerMwPerFrame = 0.0` (caused by `powerW = 0` at `fps = 60`)
- WHEN the parser checks plausibility
- THEN the snapshot MUST be marked unavailable with `IMPLAUSIBLE_VALUE`

#### Scenario: 499.9 is within window (boundary exclusive)

- GIVEN `fpowerMwPerFrame = 499.9`
- WHEN the parser checks plausibility
- THEN the snapshot MUST be marked AVAILABLE (boundary is exclusive `>= 500`)

---

### Requirement: FPW-012 — Backward compat with v4.4.1 `.gameperf`

The system SHALL decode pre-v4.4.1 (and pre-this-change) `.gameperf` exports without throwing. All FPower-related fields MUST carry defaults preserving the legacy "no FPower" semantics.

#### Scenario: v4.4.1 export loads cleanly

- GIVEN a `.gameperf` export written by v4.4.1
- WHEN loaded by post-this-change `SessionHistory.load()`
- THEN it MUST decode cleanly
- AND the report regeneration MUST render the legacy view (no FPower card / FPower card with N/D placeholder)

#### Scenario: round-trip on current build

- GIVEN a `.gameperf` export written by post-this-change build, loaded back by the same build
- WHEN displayed
- THEN `fpowerHistory` and `fpowerTimed` MUST round-trip identically

---

### Requirement: FPW-013 — Detekt clean

The change SHALL introduce no new detekt warnings or suppressions. The existing detekt baseline (3 pre-existing items: `HomeScreen` 751/750, `HelperLogWatcherTest`, `MiniGraphWithEvents`) MUST NOT grow.

#### Scenario: detekt baseline unchanged

- GIVEN `./gradlew detekt` runs on the change branch
- WHEN it completes
- THEN the exit code MUST be `0`
- AND the baseline file MUST NOT have grown
