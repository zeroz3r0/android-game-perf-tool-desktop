# Tasks — CPU total vs app usage

**Change**: `cpu-total-vs-app-usage`
**Strategy**: 3 sprints, strict TDD (RED → GREEN per batch). After all 3 sprints complete, run full suite once; expect ~1032-1035 GREEN. Detekt clean.

## Sprint 0 — Bridge dual-capture (~0.25d)

- [x] T0.1 — RED: Create `src/test/kotlin/com/gameperf/desktop/core/AdbBridgeCpuDualTest.kt` with:
  - `happy path - scripted bridge returns 80 total and 30 app - snapshot fields match`
  - `sentinel - bridge returns -1 from underlying methods - dual snapshot preserves -1` (use subclass of FakeAdbBridge that overrides the two methods)
- [x] T0.2 — Run `./gradlew test --tests "*CpuDual*"` → confirm RED (CpuDualSnapshot + captureCpuDual don't exist).
- [x] T0.3 — GREEN: Add `data class CpuDualSnapshot(val totalDeviceCpuPct: Int, val appCpuPct: Int)` to `core/AdbBridge.kt` AND `fun captureCpuDual(deviceId, pkg)` that calls the two existing methods.
- [x] T0.4 — GREEN: Add `fun captureCpuDual(deviceId: String, pkg: String): CpuDualSnapshot` to `AdbBridgeApi` interface. Implement passthrough in `RealAdbBridge`.
- [x] T0.5 — GREEN: Add `captureCpuDual` override to `FakeAdbBridge` that delegates to `captureCpuPercent(deviceId)` + `captureCpuPercent(deviceId, pkg)` so subclasses can override either readout.
- [x] T0.6 — Run `./gradlew test --tests "*CpuDual*"` → GREEN.

## Sprint 1 — ViewModel + Persistence (~0.5d)

- [x] T1.1 — RED: Create `src/test/kotlin/com/gameperf/desktop/viewmodel/AppViewModelCpuDualTest.kt` modeled on `AppViewModelFPowerTest.kt`:
  - `LiveMetrics has cpuTotalHistory with empty default`
  - `LiveMetrics carries populated cpuTotalHistory`
  - `SessionResult has cpuTotalHistory with empty default`
  - `SessionResult carries populated cpuTotalHistory`
  - `HistoryEntry round-trip preserves cpuTotalHistory via SessionHistory`
  - `HistoryEntry defaults cpuTotalHistory to empty list when not provided`
- [x] T1.2 — Extend `SessionHistoryRoundTripTest.kt` with:
  - `round-trip preserves cpuTotalHistory populated`
  - `legacy v4_5_x JSON without cpuTotalHistory defaults to empty list`
- [x] T1.3 — Run `./gradlew test --tests "*CpuDual*"` + the round-trip tests → RED.
- [x] T1.4 — GREEN: Add `cpuTotalHistory: List<Int> = emptyList()` to `LiveMetrics` in `viewmodel/AppViewModel.kt`.
- [x] T1.5 — GREEN: Add `cpuTotalHistory: List<Int> = emptyList()` to `SessionResult` in `viewmodel/AppViewModel.kt`.
- [x] T1.6 — GREEN: Add `cpuTotalHistory: List<Int> = emptyList()` to `SessionHistory.SerializableEntry` AND `HistoryEntry`. Propagate via `toSerializable` and `toHistoryEntry`.
- [x] T1.7 — GREEN: In `AppViewModel.startCapture`:
  - Declare `val cpuTotalHistory = mutableListOf<Int>()` near `cpuHistory`.
  - Replace `cpu = adb.captureCpuPercent(device.id, pkg)` with `val cpuDual = adb.captureCpuDual(device.id, pkg); cpu = cpuDual.appCpuPct` (keep `cpu` int for compat with downstream code).
  - After the existing `if (cpu > 0) { cpuHistory.add(cpu); ... }` block, append `if (cpuDual.totalDeviceCpuPct > 0) { cpuTotalHistory.add(cpuDual.totalDeviceCpuPct); if (cpuTotalHistory.size > MAX_HISTORY_SIZE) cpuTotalHistory.removeFirst() }`.
  - In `_liveMetrics.value = prev.copy(...)` update, add `cpuTotalHistory = if (snapshotHistories) cpuTotalHistory.toList() else prev.cpuTotalHistory`.
  - In the `_result.value = SessionResult(...)` build, add `cpuTotalHistory = cpuTotalHistory.toList()`.
  - In the `pendingEntry = SessionHistory.HistoryEntry(...)` builder, add `cpuTotalHistory = _result.value.cpuTotalHistory`.
- [x] T1.8 — Run `./gradlew test` (FULL suite) → GREEN, no existing tests broken.

## Sprint 2 — UI + Report rendering (~0.25d)

- [x] T2.1 — RED: Create `src/test/kotlin/com/gameperf/desktop/report/ReportGeneratorCpuDualTest.kt`:
  - `dual CPU view - cpuTotalHistory non-empty - HTML contains both dataset labels`
  - `legacy single CPU view - cpuTotalHistory empty - HTML contains only legacy label`
  - `dual CPU view contains Spanish caveat about device saturation`
  - `legacy view does NOT contain the dual-view caveat`
- [x] T2.2 — Run `./gradlew test --tests "*ReportGeneratorCpuDual*"` → RED.
- [x] T2.3 — GREEN: Add `cpuTotalHistory: List<Int> = emptyList()` defaulted param at the end of `ReportGenerator.generate`.
- [x] T2.4 — GREEN: In the CPU Chart.js block, branch on `cpuTotalHistory.isNotEmpty()`:
  - When populated: 2 datasets ('CPU total dispositivo' first, 'CPU app' second).
  - When empty: keep existing single-dataset form.
- [x] T2.5 — GREEN: Add caveat copy to CPU section (only when dual view) — Spanish tuteo-formal sentence containing `saturado por otros procesos`.
- [x] T2.6 — Extend `MiniGraph.kt` with `secondaryValues: List<Number> = emptyList()` and `secondaryColor` defaulted params; draw secondary path FIRST (under primary). Existing call sites unaffected.
- [x] T2.7 — Run `./gradlew test --tests "*CpuDual*"` → GREEN. Then `./gradlew test` (FULL) → GREEN.
- [x] T2.8 — `./gradlew detekt` → clean (no new baseline entries).

## Done criteria

- 8-13 new tests (target ~10-12).
- Full suite 1022 → 1030-1035 GREEN.
- Detekt clean.
- Working tree dirty (no commits, no push).
- Apply-progress engram upserted (`topic_key sdd/cpu-total-vs-app-usage/apply-progress`).


---
SDD CHANGE COMPLETE. Shipped 2026-05-12 across commits 8afb794 (S0+S1) + 9fe0ac5 (S2). Suite 1043 / 0 fail / detekt clean. Archived 2026-05-12.

