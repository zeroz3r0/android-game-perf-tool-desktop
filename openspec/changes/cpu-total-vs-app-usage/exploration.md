# Exploration — CPU total vs app usage

**Change**: `cpu-total-vs-app-usage`
**Date**: 2026-05-12
**Trigger**: User feedback after testing GameBench (verbatim):
> "He estado probando GameBench y me gusta mucho como diferencia, por ejemplo, la CPU usage aparecen dos líneas una de total usage y otra de app usage, me parecen muy buenos indicadores en las gráficas y me gusta como los muestran."

## Manifesto framework eval (obs #337)

1. **Mejora UX/insight**: SÍ ALTO. Dev distingue "mi app saturada" vs "device saturado por otros procesos" (background apps, OS work, sync).
2. **Compatible 4 principles**: SÍ. adb-based (`/proc/stat` + `/proc/<pid>/stat`), zero SDK, zero cloud, formulas already public.
3. **Adaptable**: N/A (adopt direct — concept maps 1:1 to existing two CPU paths).
4. **REJECT**: No.

**Decision**: ADOPT.

## Existing surface (verified)

### `AdbBridge.kt` — both CPU readers already exist

- `captureCpuPercent(deviceId: String): Int` — **device-wide total CPU%**. Reads `/proc/stat` `cpu ` line, computes `(deltaBusy / deltaTotal) * 100`. Returns -1 on first call (no delta yet) and on parse failure. Legacy pre-v4.2.5 default.
- `captureCpuPercent(deviceId: String, pkg: String): Int` — **per-process app CPU%**. Delegates to `captureProcessCpuPercent(deviceId, pkg)` which reads `/proc/<pid>/stat` (utime+stime) vs `/proc/stat` system jiffies. v4.2.5+.

Both are exposed via `AdbBridgeApi` interface and implemented in `RealAdbBridge` (1-line passthroughs). `FakeAdbBridge` has both overloads (line 98-99).

**No new parsing needed**. We just call BOTH per tick and persist both.

### `AppViewModel.kt` — per-tick capture site

Line 1215:
```kotlin
cpu = adb.captureCpuPercent(device.id, pkg)
```
Currently captures app-CPU only. The per-tick `cpu` variable lands in `cpuHistory` at line 1333 when `cpu > 0`. The history is persisted via `SessionResult.cpuHistory` (line 1846 via `avgCpu = if (cpuHistory.isNotEmpty()) cpuHistory.average().toInt() else 0`) and is referenced in `LiveMetrics.cpuHistory` (line 112).

### `SessionHistory.kt` — wire format

- `SerializableEntry` (lines 151-218) — `@Serializable` data class persisted to `history.json`. Pattern for backward-compat: all new fields default to empty/false/null + `Json { ignoreUnknownKeys = true }` ⇒ pre-v4.5.x rows hydrate cleanly. Mirror `fpowerHistory: List<Double> = emptyList()` precedent (line 206) — same shape we need.
- `HistoryEntry` (lines 220-279) — domain mirror; defaults mirror SerializableEntry.
- Converters `toSerializable()` (line 283) and `toHistoryEntry()` (line 317).

### `MiniGraph.kt` — chart rendering

Pure Compose Canvas. Single `values: List<Number>` + `color: Color`. Draws one Path stroked + a dot at the last value. **Needs additive overload** that accepts a secondary series (also `List<Number>`) drawn in a second color BEHIND/UNDER the primary line so legacy callers stay byte-equivalent.

### `ReportGenerator.kt` — HTML report

Line 47 `generate(...)` accepts `cpuHistory: List<Int>` (required). Chart.js block at line 768 draws a single dataset `{label:'CPU %', data:[$cpuD], borderColor:C.primary, ...}`. To render two lines we add a new defaulted-empty `cpuTotalHistory: List<Int> = emptyList()` param and emit a second dataset when non-empty.

CPU section at line 498 has a `stats-row` with avg + max. Will additively render `Total avg` / `Total max` pills only when `cpuTotalHistory.isNotEmpty()`.

## Risk profile

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Per-tick cost doubles (2 CPU shells instead of 1) | LOW | Both shells already exist — `captureCpuPercent(deviceId)` ≈ 5-10ms; `captureProcessCpuPercent` ≈ 10-15ms. Sum stays under fast-tier budget (~50ms). |
| Broken backward compat on `.gameperf` history | LOW | Mirror `fpowerHistory` defaulted-empty pattern — Json `ignoreUnknownKeys=true` covers all legacy rows. |
| `MiniGraph` API break for legacy callers (FPS, MEM) | LOW | Add NEW overload `MiniGraphDual` instead of mutating the single-series signature. Or default secondary series to null. |
| Detekt LongParameterList on `ReportGenerator.generate` | LOW | Already `@Suppress("LongParameterList")` (line 46). One more defaulted param is fine. |
| Chart legend gets cluttered | LOW | 2 datasets with distinct labels "Total dispositivo" and "App" + color-coded — clear. |

## Effort estimate

- Sprint 0 (Bridge dual-capture): ~0.25d
- Sprint 1 (ViewModel + Persistence): ~0.5d
- Sprint 2 (UI + Report rendering): ~0.25d
- **Total**: ~1d

## Out of scope

- No new sysfs parsing.
- No change to legacy `captureCpuPercent(deviceId)` / `captureCpuPercent(deviceId, pkg)` signatures.
- No new grading rule (`cpuGrade` keeps using `avgCpu` = app CPU).
- No report tooltip on the value chart — caveat lives in the CPU section description block.
