# Spec — CPU dual usage capability

**Change**: `cpu-total-vs-app-usage`
**Capability**: `cpu-dual-usage`

## Requirements

### CDU-001 — Bridge dual-capture method

The `AdbBridgeApi` interface MUST expose a method:

```kotlin
fun captureCpuDual(deviceId: String, pkg: String): CpuDualSnapshot
```

Returning a new immutable data class:

```kotlin
data class CpuDualSnapshot(
    val totalDeviceCpuPct: Int,
    val appCpuPct: Int,
)
```

The implementation MUST internally call `captureCpuPercent(deviceId)` for `totalDeviceCpuPct` and `captureCpuPercent(deviceId, pkg)` for `appCpuPct`. The existing per-process / per-device methods MUST NOT change signature.

#### Scenario: happy path
- GIVEN a real or fake bridge where `captureCpuPercent(deviceId)` returns 80 and `captureCpuPercent(deviceId, pkg)` returns 30
- WHEN `captureCpuDual(deviceId, pkg)` is called
- THEN the snapshot has `totalDeviceCpuPct = 80` AND `appCpuPct = 30`

#### Scenario: sentinel preserved
- GIVEN a bridge where either underlying method returns -1 (first-tick sentinel)
- WHEN `captureCpuDual(deviceId, pkg)` is called
- THEN the corresponding field in the snapshot is -1 (the snapshot does NOT coerce sentinels to 0)

---

### CDU-002 — LiveMetrics carries cpuTotalHistory

The `viewmodel.LiveMetrics` data class MUST expose a new field:

```kotlin
val cpuTotalHistory: List<Int> = emptyList()
```

Defaulted empty so every legacy construction site stays byte-equivalent.

#### Scenario: default empty
- GIVEN `LiveMetrics()` constructed with no args
- THEN `cpuTotalHistory` is an empty list

---

### CDU-003 — SessionResult carries cpuTotalHistory

The `viewmodel.SessionResult` data class MUST expose:

```kotlin
val cpuTotalHistory: List<Int> = emptyList()
```

Defaulted empty for backward compat.

#### Scenario: default empty
- GIVEN `SessionResult()` constructed with no args
- THEN `cpuTotalHistory` is an empty list

#### Scenario: populated
- GIVEN `SessionResult(cpuTotalHistory = listOf(70, 75, 80))`
- THEN the field round-trips equal to the supplied list

---

### CDU-004 — SerializableEntry + HistoryEntry persist cpuTotalHistory

Both `SessionHistory.SerializableEntry` and `SessionHistory.HistoryEntry` MUST expose:

```kotlin
val cpuTotalHistory: List<Int> = emptyList()
```

The `toSerializable()` and `toHistoryEntry()` converters MUST propagate the field both ways.

#### Scenario: round-trip preserves cpuTotalHistory
- GIVEN a `HistoryEntry` with `cpuTotalHistory = listOf(45, 55, 65)`
- WHEN saved via `SessionHistory.addEntry` and loaded via `SessionHistory.load()`
- THEN the loaded entry has `cpuTotalHistory == listOf(45, 55, 65)`

#### Scenario: legacy v4.5.x .gameperf without cpuTotalHistory loads with empty default
- GIVEN a hand-rolled JSON payload representing a v4.5.x session (no `cpuTotalHistory` field)
- WHEN `SessionHistory.load()` parses it
- THEN the entry's `cpuTotalHistory` is an empty list AND no exception is thrown

---

### CDU-005 — ViewModel populates cpuTotalHistory each tick

`AppViewModel.startCapture` MUST, on Android, switch its per-tick CPU acquisition to call `captureCpuDual`. The app CPU value continues to land in `cpuHistory` (existing behavior, unchanged). The total CPU value MUST be appended to a new local `cpuTotalHistory: MutableList<Int>` whenever `> 0`. Same `MAX_HISTORY_SIZE` cap applies.

The final `_result.value = SessionResult(...)` MUST populate `cpuTotalHistory = cpuTotalHistory.toList()` and the `HistoryEntry` pendingEntry MUST source it from `_result.value.cpuTotalHistory`.

#### Scenario: per tick both histories grow
- GIVEN a fake bridge scripted so `captureCpuDual` returns `CpuDualSnapshot(75, 35)` deterministically
- WHEN three ticks elapse
- THEN BOTH `cpuHistory` and `cpuTotalHistory` have grown by 3 entries

(Test asserts the contract at the SessionResult/HistoryEntry persistence boundary, NOT by spinning the real loop — same precedent as `AppViewModelFPowerTest`.)

---

### CDU-006 — MiniGraph supports optional secondary series

The `MiniGraph` composable MUST accept an optional secondary series:

```kotlin
@Composable
fun MiniGraph(
    label: String,
    values: List<Number>,
    color: Color = Cyan,
    maxValue: Float? = null,
    modifier: Modifier = ...,
    secondaryValues: List<Number> = emptyList(),
    secondaryColor: Color = ...,
)
```

When `secondaryValues` is non-empty, it MUST be drawn UNDER the primary stroke using the same min/max/range scaling. Existing call sites that omit the new params MUST keep producing the exact same drawing.

(No unit assertions on Compose drawing — verified by smoke test that compiles + runs and the report path which IS testable.)

---

### CDU-007 — ReportGenerator renders both CPU lines when total history present

`ReportGenerator.generate` MUST accept:

```kotlin
cpuTotalHistory: List<Int> = emptyList()
```

When `cpuTotalHistory.isNotEmpty()` AND `cpuHistory.isNotEmpty()`:
- The CPU Chart.js block MUST emit two datasets:
  - `{label:'CPU total dispositivo', data:[$cpuTotalD], borderColor:C.warn, ...}` (indigo/warn band)
  - `{label:'CPU app', data:[$cpuD], borderColor:C.primary, ...}` (cyan/primary, current behavior)
- The CPU section stats-row MUST additively show a "Total prom" pill with the total-line average.

When `cpuTotalHistory.isEmpty()`:
- Single dataset (`CPU %`) renders exactly like today — legacy fixtures stay byte-equivalent.

#### Scenario: 2 polylines when dual history populated
- GIVEN `generate(..., cpuHistory = listOf(20, 25, 30), cpuTotalHistory = listOf(60, 65, 70), ...)`
- THEN the resulting HTML contains BOTH dataset labels "CPU total dispositivo" AND "CPU app"

#### Scenario: 1 polyline (legacy) when total history empty
- GIVEN `generate(..., cpuHistory = listOf(20, 25, 30))` with NO `cpuTotalHistory`
- THEN the resulting HTML contains "CPU %" (legacy single label) and NOT "CPU total dispositivo"

---

### CDU-008 — Spanish-tuteo-formal caveat copy in CPU section

The CPU section in the report MUST include the following caveat description text (Spanish tuteo-formal) ONLY when `cpuTotalHistory.isNotEmpty()`:

> "El total incluye al sistema operativo y a los demás procesos. Si el total está alto pero la app baja, el dispositivo está saturado por otros procesos y no por tu juego."

(Wording is approximate — the test only asserts that the resulting HTML contains the key phrase `saturado por otros procesos` when the dual view is rendered.)

#### Scenario: caveat present in dual view
- GIVEN `generate(..., cpuTotalHistory = listOf(60, 65))` (non-empty)
- THEN the HTML contains the substring `saturado por otros procesos`

#### Scenario: caveat absent in legacy view
- GIVEN `generate(...)` with no `cpuTotalHistory`
- THEN the HTML does NOT contain the substring `saturado por otros procesos`
