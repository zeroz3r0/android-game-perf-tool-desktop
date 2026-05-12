# Proposal — CPU total vs app usage

**Change**: `cpu-total-vs-app-usage`
**Status**: PROPOSED
**Date**: 2026-05-12
**Effort**: ~1d
**Trigger**: User feedback (GameBench inspiration)

## Intent

Capture and render **two** CPU lines per session instead of one: **total device CPU** (sum across all processes, what `/proc/stat` returns) and **app CPU** (what `/proc/<pid>/stat` returns for the game process). This is GameBench's standard chart layout.

The dev gains an unambiguous answer to "is the device saturated by other processes or by my game?". When the total line spikes but the app line stays flat, the bottleneck is OS/background work, not the game.

## Why now

- User explicitly requested this after benchmarking GameBench (verbatim feedback above).
- Manifesto framework eval (obs #337): all 4 principles compatible, adopt direct.
- Both readers already exist in `AdbBridge` (v4.2.5 added app-specific; legacy total was kept). Zero new parsing.

## Scope

**In scope**:
1. New `AdbBridgeApi.captureCpuDual(deviceId, pkg): CpuDualSnapshot` convenience that calls BOTH existing methods and returns both values.
2. New `cpuTotalHistory: List<Int>` on `LiveMetrics`, `SessionResult`, `SerializableEntry`, `HistoryEntry` — defaulted empty for backward compat.
3. `AppViewModel` per-tick capture switches the single call to the new dual capture; appends app value to `cpuHistory` (unchanged) AND total value to new `cpuTotalHistory`.
4. `MiniGraph` extended with optional secondary series (additive overload OR defaulted null param).
5. `ReportGenerator` accepts new defaulted-empty `cpuTotalHistory` param; CPU Chart.js section emits 2 datasets when populated; legend labels "CPU total dispositivo" (indigo) vs "CPU app" (emerald).
6. CPU section caveat copy (Spanish tuteo-formal): "Total incluye OS, otros apps. Si total alto pero app bajo, el dispositivo está saturado por otros procesos."

**Out of scope**:
- New grading or threshold logic — `cpuGrade` keeps using app `avgCpu`.
- No change to existing `captureCpuPercent` signatures.
- No new sysfs parsing.
- No iOS path (the existing single CPU path stays — iOS doesn't expose per-process easily, will land in a future change if requested).

## Manifesto alignment

| Principle | Compliance |
|-----------|------------|
| 1. ADB-based | ✓ Reuses two existing adb shell paths (`/proc/stat`, `/proc/<pid>/stat`). |
| 2. No SDK | ✓ Same as today. |
| 3. No cloud | ✓ Same as today. |
| 4. Public/transparent formula | ✓ Formula documented inline in `AdbBridge.captureCpuPercent` KDoc. |

## Backward compatibility contract

- `.gameperf` history files saved before this change have no `cpuTotalHistory`. `Json { ignoreUnknownKeys = true }` + `cpuTotalHistory: List<Int> = emptyList()` default ⇒ they hydrate as "no total line" and the report falls back to the single-line legacy chart.
- All existing call sites of `MiniGraph` and `ReportGenerator.generate` stay byte-equivalent — new params are defaulted at the tail.

## Acceptance

- 8-13 new tests across 3 sprints.
- Full suite goes 1022 → ~1030-1035 GREEN.
- Detekt clean (no new baseline entries).
- No legacy `.gameperf` file fails to load.
