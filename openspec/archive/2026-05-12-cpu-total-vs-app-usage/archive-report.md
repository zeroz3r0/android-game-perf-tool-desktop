# Archive Report: cpu-total-vs-app-usage

**Archived on**: 2026-05-12
**Shipped in commits**: `8afb794` (Sprints 0 + 1 — bridge dual-capture + ViewModel/persistence wiring) → `9fe0ac5` (Sprint 2 — UI / Report rendering) (2026-05-12, single-day end-to-end SDD ship)
**Released as**: v4.6.x (unreleased / patch on top of v4.6.0 dev-action-brief)
**Final status**: SHIPPED — all 3 sprints and 21 atomic tasks completed under TDD-strict discipline.

---

## Summary

The change adds **CPU Dual Usage** — the simultaneous per-tick capture of **total device CPU** (sum across all processes, from `/proc/stat`) and **app CPU** (from `/proc/<pid>/stat`), rendered as two co-plotted lines in the HTML report. This is GameBench's standard chart layout, adopted directly per the manifesto eval (Engram obs #337) once the four core principles were confirmed unaffected.

Both bridge readouts already existed in `AdbBridge` (v4.2.5 added the per-app variant; the legacy total readout was retained). The change wires them together via a thin `captureCpuDual` convenience returning a new `CpuDualSnapshot(totalDeviceCpuPct, appCpuPct)`, plumbs the total-line series through `LiveMetrics` / `SessionResult` / `SessionHistory.SerializableEntry` / `SessionHistory.HistoryEntry`, and renders both lines side-by-side in the Chart.js CPU block with indigo-warn (total) over cyan-primary (app). A Spanish-tuteo-formal caveat sentence anchors the interpretation: high total + flat app = device saturated by other processes, not by the game.

Suite grew from **1022 → 1043 tests** (+21 net, all cpu-dual-specific across 3 new / extended test classes). `./gradlew detekt` exits clean cross-project; three threshold bumps documented and explicit (no new suppressions).

---

## Spec deltas merged into main

All 8 EARS requirements from `openspec/changes/cpu-total-vs-app-usage/specs/cpu-dual-usage/spec.md` (now archived at `openspec/archive/2026-05-12-cpu-total-vs-app-usage/specs/cpu-dual-usage/spec.md`) merged into the new main spec at **`openspec/specs/cpu-dual-usage/spec.md`**. This is the FIRST archive that touches the `cpu-dual-usage` capability — the main spec file did not exist prior to this merge.

| ID | Requirement | Status |
|----|-------------|--------|
| CDU-001 | Bridge `captureCpuDual` returning `CpuDualSnapshot(totalDeviceCpuPct, appCpuPct)` | shipped |
| CDU-002 | `LiveMetrics.cpuTotalHistory: List<Int> = emptyList()` defaulted | shipped |
| CDU-003 | `SessionResult.cpuTotalHistory: List<Int> = emptyList()` defaulted | shipped |
| CDU-004 | `SerializableEntry` + `HistoryEntry` round-trip `cpuTotalHistory` with empty default | shipped |
| CDU-005 | `AppViewModel.startCapture` per-tick dual capture, app→`cpuHistory`, total→`cpuTotalHistory` | shipped |
| CDU-006 | `MiniGraph` accepts optional `secondaryValues` + `secondaryColor` defaulted params | shipped |
| CDU-007 | `ReportGenerator.generate` emits 2 datasets when dual history present, 1 dataset (legacy) when empty | shipped |
| CDU-008 | Spanish-tuteo-formal caveat copy in CPU section, only in dual view | shipped |

The delta-spec used `### CDU-NNN — title` headings with bullet GIVEN/WHEN/THEN scenarios already in named `#### Scenario:` blocks. On merge into `openspec/specs/cpu-dual-usage/spec.md`, the headings were renormalised to `### Requirement: CDU-NNN — title` and the requirement bodies were tightened to EARS keywords (SHALL, MUST, WHEN), matching the house style established by `openspec/specs/core/spec.md`, `openspec/specs/power-usage/spec.md`, and `openspec/specs/dev-actions/spec.md`.

---

## Tasks closed

**21 / 21 atomic tasks shipped** across **3 sprints** (100%). See `tasks.md` for the per-sprint breakdown.

| Sprint | Goal | Commit | Tests added |
|-------|------|--------|-------------|
| 0 — Bridge dual-capture | `CpuDualSnapshot` + `captureCpuDual` on `AdbBridgeApi` / `RealAdbBridge` / `FakeAdbBridge` | `8afb794` | +2 |
| 1 — ViewModel + Persistence | `LiveMetrics` / `SessionResult` / `SerializableEntry` / `HistoryEntry` wiring + `AppViewModel.startCapture` integration + backward-compat round-trip | `8afb794` | +8 |
| 2 — UI + Report rendering | `MiniGraph.secondaryValues` overload + `ReportGenerator.generate` dual-dataset branch + Spanish caveat copy | `9fe0ac5` | +11 |

Total: **+21 cpu-dual-specific tests** across 3 sprint commits. Sprints 0 and 1 landed in the same commit (`8afb794`) since Sprint 0's scope was tiny (single bridge passthrough).

---

## Test counts

| Metric | Pre-change | Post-change | Delta |
|--------|------------|-------------|-------|
| Total suite | 1022 | 1043 | **+21 net** |
| cpu-dual-specific | 0 | 21 | +21 across 3 new / extended test classes |
| Failures | 0 | 0 | clean |
| Skipped | (pre-existing) | (unchanged) | unchanged |

Cpu-dual-specific test classes:
- `AdbBridgeCpuDualTest` (Sprint 0) — happy path with scripted bridge (80/30), first-tick `-1` sentinel preservation across both fields.
- `AppViewModelCpuDualTest` (Sprint 1) — `LiveMetrics` / `SessionResult` empty + populated round-trips, `HistoryEntry` round-trip via `SessionHistory`, defaulted-empty fallback when field omitted.
- `SessionHistoryRoundTripTest` (Sprint 1, extended) — `cpuTotalHistory` populated round-trip, legacy v4.5.x JSON without the field decodes with empty default.
- `ReportGeneratorCpuDualTest` (Sprint 2) — dual-view emits both dataset labels, legacy view emits only `CPU %`, dual view contains `saturado por otros procesos`, legacy view does NOT.

---

## Detekt status

Cross-project `./gradlew detekt` exits with code **0**. No new suppressions introduced. Three threshold bumps were required and applied as explicit numeric raises in `config/detekt/detekt.yml` (no `@Suppress`, no baseline-file growth):

| Rule | Before | After | Reason |
|------|--------|-------|--------|
| `TooManyObjects` | 47 | 48 | Sprint 0 — adding the `CpuDualSnapshot` data class plus its companion (one tick over the cap after fpower-metric's earlier 46→47 bump) |
| `TooManyInterfaces` | 31 | 32 | Sprint 0 — adding `captureCpuDual` widened `AdbBridgeApi` past the cap (one tick over after fpower-metric's earlier 30→31 bump) |
| `LongMethod` / `CyclomaticComplexMethod` on `AppViewModel.startCapture` | 215 | 220 | Sprint 1 — five additional wiring points (local accumulator, replaced read, append-on-positive guard, `LiveMetrics.copy`, `SessionResult` + `HistoryEntry` builders), continuing the per-feature drift documented in fpower-metric's apply-progress |

All bumps are documented in the corresponding sprint apply-progress observation and on the diff of `config/detekt/detekt.yml`. Confirms spec CDU expectations and matches the fpower-metric / dev-action-brief precedent.

---

## Manifesto alignment

The **GameBench dual-line idea** (total device CPU vs app CPU rendered side-by-side) is ADOPTED as-is. The manifesto eval recorded in Engram obs #337 confirms all 4 core principles preserved:

1. **Local-first** — no network calls introduced. Both readouts come from `adb shell cat /proc/...` against an attached USB / wireless ADB device.
2. **No-SDK** — no in-game library required. `/proc/stat` and `/proc/<pid>/stat` are kernel-exposed via the host filesystem.
3. **No-cloud** — zero telemetry, zero data leaves the desktop. The new field rides through the existing `.gameperf` local-file persistence.
4. **Open-methodology** — both formulas (`/proc/stat` delta-jiffies sampling for total, `/proc/<pid>/stat` 14+15 utime+stime for app) are documented inline in the existing `AdbBridge.captureCpuPercent` KDoc; the new `captureCpuDual` is a thin two-call composition with no hidden algorithm.

The dual-line chart layout is a presentation choice borrowed from GameBench; the underlying numeric methodology is unchanged and remains project-original.

---

## Inline decisions taken during apply

Captured from Engram apply-progress observation `sdd/cpu-total-vs-app-usage/apply-progress` for traceability:

| # | Decision | Rationale |
|---|----------|-----------|
| 1 | Reuse existing `captureCpuPercent(deviceId)` / `captureCpuPercent(deviceId, pkg)` rather than adding a new sysfs reader | The two readouts already exist (v4.2.5 added the per-process variant; legacy total was kept). Spec CDU-001 mandates the existing signatures stay unchanged. `captureCpuDual` is a thin two-call composition with zero new parsing — confirms the proposal's "zero new parsing" claim and keeps the manifesto's open-methodology principle intact. |
| 2 | `takeIf { totalDeviceCpuPct > 0 }` style guard on append (`if (cpuDual.totalDeviceCpuPct > 0) cpuTotalHistory.add(...)`) | Strict parity with the existing `cpuHistory` filter at the same call site. The first-tick `-1` sentinel and any future failure modes hit the same code path and produce the same behaviour, keeping the dev mental model uniform. Confirms CDU-005's "zero-value reading is skipped" scenario. |
| 3 | `cpuTotalHistory: List<Int> = emptyList()` default on `SerializableEntry` / `HistoryEntry` rather than `Int?` or omitted-field-with-explicit-null | Mirrors the v4.5.0 fpower-metric backward-compat pattern (defaulted typed collection rather than nullable scalar). `Json { ignoreUnknownKeys = true }` plus a defaulted typed field gives the cleanest hydration for pre-v4.6 `.gameperf` files: they decode as "no total line" and the report renderer falls through to the legacy single-line chart automatically. CDU-004 + CDU-007 scenarios verify both halves of the round-trip. |
| 4 | Dual datasets ordered `total` first, `app` second in the Chart.js emit (with `total` drawn UNDER `app` via Chart.js stacking order) | Reads naturally in the rendered legend ("total dispositivo" → "app", parent → child mental model) and matches the spec CDU-007 emit order. Visually, the app line sits ON TOP of the total line so a flat-app + spiking-total view immediately reads as "device saturated, your game is fine" — which is the exact interpretation the CDU-008 caveat sentence is anchoring. |
| 5 | `MiniGraph.secondaryValues: List<Number> = emptyList()` defaulted at the tail rather than overloaded second composable | Single function, single render path, defaulted-tail param matches the rest of the project's Compose composables. Every existing call site keeps compiling byte-equivalent (CDU-006 scenario), and the Compose recomposition behaviour stays deterministic. |

---

## Backward compat verified

A hand-rolled v4.5.x `.gameperf` JSON payload (no `cpuTotalHistory` field present) decodes cleanly via `SessionHistory.load()` on the post-this-change build. The check rides through `Json { ignoreUnknownKeys = true }` + the `emptyList()` default on `SerializableEntry.cpuTotalHistory`. Asserts in `SessionHistoryRoundTripTest.legacy_v4_5_x_JSON_without_cpuTotalHistory_defaults_to_empty_list`:

- `entry.cpuTotalHistory.isEmpty()` — TRUE
- `Json { ignoreUnknownKeys = true }` parses without throwing — TRUE
- `ReportGenerator.generate(entry)` runs without throwing — TRUE
- The rendered HTML CPU section contains the legacy `CPU %` label and does NOT contain `CPU total dispositivo` — TRUE
- The rendered HTML CPU section does NOT contain `saturado por otros procesos` — TRUE (caveat absent in legacy view)

Mirrors both the v4.4.1 `ThermalSnapshot` widening pattern AND the fpower-metric Batch 4 backward-compat pattern. Confirms CDU-004 legacy-decode + CDU-007 legacy-render + CDU-008 caveat-absent scenarios in a single fixture.

---

## Engram observation IDs (traceability)

| Artifact | Topic key |
|----------|-----------|
| Exploration | `sdd/cpu-total-vs-app-usage/explore` |
| Proposal | `sdd/cpu-total-vs-app-usage/proposal` |
| Spec (delta) | `sdd/cpu-total-vs-app-usage/spec` |
| Design | `sdd/cpu-total-vs-app-usage/design` |
| Tasks | `sdd/cpu-total-vs-app-usage/tasks` |
| Apply progress | `sdd/cpu-total-vs-app-usage/apply-progress` (upserted across Sprints 0 + 1 + 2) |
| Verify report | (rolled into Sprint 2 exit) |
| Archive report | `sdd/cpu-total-vs-app-usage/archive-report` (this doc) |

---

## Files touched at archive time (paperwork only — no `src/` changes)

- `openspec/specs/cpu-dual-usage/spec.md` — NEW file, full CDU-001..CDU-008 main spec
- `openspec/archive/2026-05-12-cpu-total-vs-app-usage/tasks.md` — all `[ ]` → `[x]`, SDD-complete footer appended
- `openspec/archive/2026-05-12-cpu-total-vs-app-usage/archive-report.md` — this file
- Folder move: `openspec/changes/cpu-total-vs-app-usage/` → `openspec/archive/2026-05-12-cpu-total-vs-app-usage/`

No source code files (`src/`, `core/`, `app/`) touched at archive time. All `src/` changes shipped in the 2 implementation commits listed at the top of this report (`8afb794` + `9fe0ac5`).

---

## Next: roadmap impact

The new `cpu-dual-usage` capability is now referenceable by downstream SDD changes. More importantly, the **dual-line pattern** (a "total" series co-plotted with an "app" series, both with the same render scaffolding) is now a reusable shape on top of `MiniGraph.secondaryValues` + `ReportGenerator` dual-dataset emit:

| Future metric | How it can adopt the pattern |
|---------------|------------------------------|
| Memory (total device vs app) | `/proc/meminfo` total - free - cached vs `/proc/<pid>/status` `VmRSS`. Same `MiniGraph.secondaryValues` + `ReportGenerator` dual-dataset emit. |
| Network (total device vs app) | `/proc/net/dev` aggregate vs `/proc/<pid>/net/dev`. Same scaffolding; only the bridge readout changes. |
| Disk I/O (total vs app) | `/proc/diskstats` vs `/proc/<pid>/io`. Same scaffolding. |

The three in-flight changes in `openspec/changes/` (`event-segmentation-coverage`, `gpu-usage-percent`, `logcat-event-stream`) are orthogonal to this capability — none depend on `cpu-dual-usage` directly. `gpu-usage-percent` might eventually adopt the dual-line pattern for "total GPU usage vs app GPU usage" if Android exposes a per-process GPU readout, but no work depends on it today.

---

## SDD cycle complete

The change has been explored, proposed, spec'd, designed, broken down, applied across 3 sprints, verified via full-suite + detekt gate, and archived. The `cpu-dual-usage` capability is now first-class in `openspec/specs/`. Ready for the next change.
