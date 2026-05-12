# Design — CPU total vs app usage

**Change**: `cpu-total-vs-app-usage`
**Date**: 2026-05-12
**Author**: agent-team-lite

## Approach

Reuse the two CPU readers that already exist in `AdbBridge` and add one **thin convenience method** that fans out to both. Persist the new history series as a defaulted-empty list (mirroring the v4.5.0 `fpowerHistory` backward-compat precedent). Render two lines in the chart only when the new history is non-empty so legacy data and tests stay byte-equivalent.

## Architecture decisions

### ADR-1 — Add convenience method, do NOT mutate existing signatures

Two existing methods on `AdbBridgeApi` are battle-tested and used in 20+ call sites (production + tests):

```kotlin
fun captureCpuPercent(deviceId: String): Int        // total device CPU
fun captureCpuPercent(deviceId: String, pkg: String): Int  // app CPU
```

We add a NEW method that calls both:

```kotlin
fun captureCpuDual(deviceId: String, pkg: String): CpuDualSnapshot
```

**Why**: replacing the existing signatures would force a cascading rewrite of every test that scripts CPU values and would risk subtle behavior changes in the live loop. The convenience method is a 3-line composition that consumers can adopt at their own pace. iOS path can keep calling the single-arg method since iOS doesn't have a `pkg`-scoped CPU on the sidecar today.

### ADR-2 — Sentinel preservation

Both underlying methods return `-1` as a sentinel for "first tick, no delta yet". The dual snapshot **preserves** sentinels — it does NOT coerce -1 to 0 inside the snapshot. Callers (AppViewModel) already gate on `> 0` before recording history. This keeps the contract honest and lets test fakes script sentinel behavior.

### ADR-3 — Backward-compat strategy mirrors v4.5.0 fpower

Same playbook as `fpowerHistory` (v4.5.0):
- Add `cpuTotalHistory: List<Int> = emptyList()` defaulted at end of each persistence type's constructor parameter list.
- `Json { ignoreUnknownKeys = true }` (already configured) handles legacy `.gameperf` files automatically.
- Round-trip test in `SessionHistoryRoundTripTest.kt` confirms the field survives save→load AND a legacy v4.5.x payload (no field) loads with empty default.

### ADR-4 — MiniGraph extension via defaulted optional params

`MiniGraph` is a leaf Compose composable used by HOME/CAPTURE screens. Two options:
1. Add a new `MiniGraphDual` composable.
2. Add `secondaryValues: List<Number> = emptyList()` defaulted param to `MiniGraph`.

We choose **option 2** because:
- All 6+ existing call sites stay byte-equivalent (they don't pass the new param).
- The drawing code already handles `values.size < 2` → return early; same guard applied to secondary.
- Less code duplication.

### ADR-5 — Report chart: 2 Chart.js datasets

The Chart.js CPU block at `ReportGenerator.kt:768` currently has a single `datasets: [{label:'CPU %', ...}]` entry. We add a second dataset when `cpuTotalHistory.isNotEmpty()`:

```js
data:{labels:[$tL],datasets:[
  {label:'CPU total dispositivo', data:[$cpuTotalD], borderColor:C.warn, tension:0.3, pointRadius:0, borderWidth:1.5},
  {label:'CPU app', data:[$cpuD], borderColor:C.primary, ...current segment-color logic...}
]}
```

- **Total line uses `C.warn`** (amber/orange) to make it visually distinct AND signal "this includes OS noise, not strictly your fault".
- **App line keeps `C.primary`** (cyan) with the existing 70/85% saturation-color gradient — this is still the value the user grades against.
- The 85% threshold annotation line stays — it's about app saturation, not total.

When `cpuTotalHistory.isEmpty()` (legacy data or unit test fixtures): emit the OLD single-dataset form so `ReportRenderingTest` and pre-v4.5.x re-renders stay byte-equivalent. We do this by branching on `cpuTotalHistory.isNotEmpty()` inside the JS template literal.

### ADR-6 — Cost analysis

Per tick on Android fast tier (every ~500ms):
- BEFORE: 1 call → `captureCpuPercent(deviceId, pkg)` ≈ 10-15ms (cached PID + 2 shell reads).
- AFTER: 1 call to `captureCpuDual` → calls BOTH underlying methods → 1 extra `cat /proc/stat` (5-10ms).
- Net: ~+10ms per tick. Fast tier budget is ~50ms. Safe.

### ADR-7 — No grading changes

`cpuGrade = metricGrade(100 - avgCpu, ...)` continues to use `avgCpu` which is the APP CPU average. The total line is informational — it does not flip a session grade. This matches GameBench's UX (the total line is shown but not scored).

## Wiring map

```
AdbBridge.kt              → add CpuDualSnapshot data class + captureCpuDual fun
AdbBridgeApi.kt           → add captureCpuDual to interface
                          → RealAdbBridge passthrough impl
FakeAdbBridge.kt          → captureCpuDual override (delegates to existing
                            captureCpuPercent overrides; tests script values
                            via subclass)
viewmodel/AppViewModel.kt → LiveMetrics: + cpuTotalHistory: List<Int> = emptyList()
                          → SessionResult: + cpuTotalHistory: List<Int> = emptyList()
                          → startCapture loop: var cpuTotalHistory + per-tick
                            appends + final SessionResult population
core/SessionHistory.kt    → SerializableEntry: + cpuTotalHistory
                          → HistoryEntry: + cpuTotalHistory
                          → toSerializable + toHistoryEntry propagate
ui/components/MiniGraph.kt → add secondaryValues + secondaryColor defaulted params
                            (drawn UNDER primary stroke)
report/ReportGenerator.kt → + cpuTotalHistory: List<Int> = emptyList() at tail
                          → CPU Chart.js block: conditional 2-dataset form
                          → CPU section stats-row: conditional "Total prom" pill
                          → CPU section caveat copy when dual present
```

## Test strategy

| Sprint | Test file | What it locks down |
|--------|-----------|--------------------|
| 0 | `AdbBridgeCpuDualTest.kt` (NEW) | Happy path: scripted 80/30 → snapshot fields match. Sentinel: -1 preserved. (~2-3 tests) |
| 1 | `AppViewModelCpuDualTest.kt` (NEW) | LiveMetrics + SessionResult shape, HistoryEntry round-trip via SessionHistory.addEntry/load. (~3-4 tests) |
| 1 | `SessionHistoryRoundTripTest.kt` (EXTEND) | New legacy-JSON without cpuTotalHistory → empty default. Round-trip preserves populated list. (~2 tests) |
| 2 | `ReportGeneratorCpuDualTest.kt` (NEW) | 2 datasets when populated. 1 dataset when empty. Caveat string presence. (~3-4 tests) |

**Total target**: 10-13 new tests. Pushes suite from 1022 → ~1032-1035.

## Open questions

None — direct adoption from GameBench's well-understood UX.

## Risks

| Risk | Status |
|------|--------|
| Doubled shell cost slows fast tier | Mitigated — adds ~10ms, well under 50ms budget. |
| Legacy `.gameperf` rows fail to load | Mitigated — defaulted-empty field + `ignoreUnknownKeys = true`. |
| Report chart legend gets noisy | Acceptable — Chart.js default legend is already compact. |
| Detekt LongParameterList complaints | Acceptable — `ReportGenerator.generate` already `@Suppress("LongParameterList")`. |
