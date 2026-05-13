# Spec — CPU Dual Usage

This capability covers the per-tick dual capture of **total device CPU** and **app-specific CPU** during an Android game-performance session, plus the persistence and report rendering paths that surface both lines side-by-side. iOS continues to expose a single CPU path (out of scope here).

Conventions:
- Requirement IDs are stable and code-referenceable. They map directly to test names.
- Requirement statements use EARS keywords (SHALL, MUST, WHEN, WHILE, WHERE, IF/THEN).
- Scenarios use Given/When/Then for testability.
- User-facing strings are in Castilian Spanish formal **tuteo** per project convention.

> **Source delta:** `cpu-total-vs-app-usage` (archived 2026-05-12). All requirements in this capability were added by that change. First capability landing under `cpu-dual-usage` — no prior requirements existed.

---

## ADDED Requirements

### Requirement: CDU-001 — Bridge dual-capture method

The `AdbBridgeApi` interface SHALL expose `fun captureCpuDual(deviceId: String, pkg: String): CpuDualSnapshot` returning the new `data class CpuDualSnapshot(val totalDeviceCpuPct: Int, val appCpuPct: Int)`. The implementation MUST internally call `captureCpuPercent(deviceId)` for `totalDeviceCpuPct` and `captureCpuPercent(deviceId, pkg)` for `appCpuPct`. The existing per-process / per-device `captureCpuPercent` signatures MUST NOT change.

#### Scenario: happy path

- GIVEN a real or fake bridge where `captureCpuPercent(deviceId)` returns 80 and `captureCpuPercent(deviceId, pkg)` returns 30
- WHEN `captureCpuDual(deviceId, pkg)` is called
- THEN the snapshot MUST carry `totalDeviceCpuPct = 80` AND `appCpuPct = 30`

#### Scenario: first-tick sentinel preserved

- GIVEN a bridge where either underlying method returns `-1` (first-tick sentinel)
- WHEN `captureCpuDual(deviceId, pkg)` is called
- THEN the corresponding field in the snapshot MUST equal `-1`
- AND the snapshot MUST NOT coerce sentinels to `0`

---

### Requirement: CDU-002 — LiveMetrics carries cpuTotalHistory

The `viewmodel.LiveMetrics` data class SHALL expose `val cpuTotalHistory: List<Int> = emptyList()`. The default empty list MUST keep every pre-this-change construction site byte-equivalent.

#### Scenario: default empty

- GIVEN `LiveMetrics()` constructed with no args
- WHEN inspected
- THEN `cpuTotalHistory` MUST equal `emptyList<Int>()`

#### Scenario: populated value round-trips

- GIVEN `LiveMetrics(cpuTotalHistory = listOf(70, 75, 80))`
- WHEN inspected
- THEN `cpuTotalHistory` MUST equal `listOf(70, 75, 80)`

---

### Requirement: CDU-003 — SessionResult carries cpuTotalHistory

The `viewmodel.SessionResult` data class SHALL expose `val cpuTotalHistory: List<Int> = emptyList()` defaulted for backward compatibility with pre-this-change consumers.

#### Scenario: default empty

- GIVEN `SessionResult()` constructed with no args
- WHEN inspected
- THEN `cpuTotalHistory` MUST equal `emptyList<Int>()`

#### Scenario: populated value round-trips

- GIVEN `SessionResult(cpuTotalHistory = listOf(70, 75, 80))`
- WHEN inspected
- THEN `cpuTotalHistory` MUST equal `listOf(70, 75, 80)`

---

### Requirement: CDU-004 — SerializableEntry and HistoryEntry persist cpuTotalHistory

Both `SessionHistory.SerializableEntry` and `SessionHistory.HistoryEntry` SHALL expose `val cpuTotalHistory: List<Int> = emptyList()`. The `toSerializable()` and `toHistoryEntry()` converters MUST propagate the field in both directions.

#### Scenario: round-trip preserves populated history

- GIVEN a `HistoryEntry` with `cpuTotalHistory = listOf(45, 55, 65)`
- WHEN saved via `SessionHistory.addEntry` and reloaded via `SessionHistory.load()`
- THEN the loaded entry MUST carry `cpuTotalHistory == listOf(45, 55, 65)`

#### Scenario: legacy v4.5.x `.gameperf` without cpuTotalHistory decodes with empty default

- GIVEN a hand-rolled JSON payload representing a v4.5.x session (no `cpuTotalHistory` field present)
- WHEN `SessionHistory.load()` parses it
- THEN the resulting entry's `cpuTotalHistory` MUST equal `emptyList<Int>()`
- AND no exception MUST be thrown
- AND `Json { ignoreUnknownKeys = true }` MUST continue to apply

---

### Requirement: CDU-005 — ViewModel populates cpuTotalHistory each tick

`AppViewModel.startCapture` SHALL, on Android, route its per-tick CPU acquisition through `captureCpuDual`. The app-CPU value MUST continue to flow into the existing `cpuHistory` accumulator (unchanged behaviour). The total-CPU value MUST be appended to a new local `cpuTotalHistory: MutableList<Int>` whenever it is `> 0`. The same `MAX_HISTORY_SIZE` cap as `cpuHistory` MUST apply (oldest entry dropped via `removeFirst()`).

On capture stop, the `_result.value = SessionResult(...)` assignment MUST populate `cpuTotalHistory = cpuTotalHistory.toList()`, and the `pendingEntry` `SessionHistory.HistoryEntry(...)` builder MUST source its `cpuTotalHistory` from `_result.value.cpuTotalHistory`.

#### Scenario: per tick both histories grow

- GIVEN a fake bridge scripted so `captureCpuDual` deterministically returns `CpuDualSnapshot(75, 35)` for three consecutive ticks
- WHEN the loop completes three ticks
- THEN BOTH `cpuHistory` AND `cpuTotalHistory` MUST have grown by exactly 3 entries
- AND the assertion MUST be evaluated at the `SessionResult` / `HistoryEntry` persistence boundary, not by spinning the real capture loop (mirroring `AppViewModelFPowerTest` precedent)

#### Scenario: zero-value reading is skipped

- GIVEN a tick where `cpuDual.totalDeviceCpuPct == 0`
- WHEN the appender runs
- THEN `cpuTotalHistory` MUST NOT grow on that tick (parity with the existing `cpuHistory` `> 0` filter)

---

### Requirement: CDU-006 — MiniGraph supports optional secondary series

The `MiniGraph` composable SHALL accept two new defaulted parameters at the tail of its signature:

```kotlin
secondaryValues: List<Number> = emptyList(),
secondaryColor: Color = /* sensible default */,
```

WHEN `secondaryValues` is non-empty, the composable MUST draw the secondary polyline UNDER the primary stroke using the same min / max / range scaling as the primary series. WHEN `secondaryValues` is empty, every existing call site MUST keep producing byte-equivalent output.

#### Scenario: legacy call sites unaffected

- GIVEN a call to `MiniGraph(label, values, color, maxValue, modifier)` without the new params
- WHEN it renders
- THEN the drawing MUST be identical to the pre-this-change behaviour (no Compose unit assertion; verified by smoke test + downstream report renderings)

---

### Requirement: CDU-007 — ReportGenerator renders both CPU lines when total history present

`ReportGenerator.generate` SHALL accept a new tail parameter `cpuTotalHistory: List<Int> = emptyList()`.

WHEN `cpuTotalHistory.isNotEmpty()` AND `cpuHistory.isNotEmpty()`:
- The CPU Chart.js block MUST emit exactly two datasets:
  - `{ label: 'CPU total dispositivo', data: [$cpuTotalD], borderColor: C.warn, ... }` (indigo/warn band, drawn first)
  - `{ label: 'CPU app', data: [$cpuD], borderColor: C.primary, ... }` (cyan/primary, existing behaviour)
- The CPU section stats-row MUST additively display a "Total prom" pill carrying the total-line average.

WHEN `cpuTotalHistory.isEmpty()`:
- The CPU Chart.js block MUST emit a single dataset labelled `CPU %`, byte-equivalent to pre-this-change output.

#### Scenario: two polylines when dual history populated

- GIVEN `generate(..., cpuHistory = listOf(20, 25, 30), cpuTotalHistory = listOf(60, 65, 70), ...)`
- WHEN the report renders
- THEN the resulting HTML MUST contain BOTH dataset labels `CPU total dispositivo` AND `CPU app`

#### Scenario: single legacy polyline when total history empty

- GIVEN `generate(..., cpuHistory = listOf(20, 25, 30))` with NO `cpuTotalHistory`
- WHEN the report renders
- THEN the resulting HTML MUST contain the legacy single label `CPU %`
- AND it MUST NOT contain `CPU total dispositivo`

---

### Requirement: CDU-008 — Spanish-tuteo-formal caveat copy in CPU section

The CPU section of the report SHALL include a Spanish-tuteo-formal caveat sentence ONLY when `cpuTotalHistory.isNotEmpty()`. The copy MUST communicate that the total line aggregates the OS and other processes — so a high total combined with a low app value indicates device saturation, not the game's bottleneck. The reference wording is:

> "El total incluye al sistema operativo y a los demás procesos. Si el total está alto pero la app baja, el dispositivo está saturado por otros procesos y no por tu juego."

Tests MUST assert the presence of the key phrase `saturado por otros procesos` (the rest of the sentence is approximate and allowed to evolve for copy polish).

#### Scenario: caveat present in dual view

- GIVEN `generate(..., cpuTotalHistory = listOf(60, 65))` (non-empty)
- WHEN the report renders
- THEN the resulting HTML MUST contain the substring `saturado por otros procesos`

#### Scenario: caveat absent in legacy view

- GIVEN `generate(...)` with no `cpuTotalHistory`
- WHEN the report renders
- THEN the resulting HTML MUST NOT contain the substring `saturado por otros procesos`
