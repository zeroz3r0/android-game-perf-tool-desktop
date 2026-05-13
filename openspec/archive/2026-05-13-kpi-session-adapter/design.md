# Design: KPI Session Adapter

## Technical Approach

Single top-level pure function `toKpiInput(session: SessionResult): KpiInput` in new package `core.kpi.adapter`. Mirrors the project's "pure-functions-extracted" pattern (CLAUDE.md): no class wrapper, no DI, no state. The adapter:
1. Builds an ordered list of `(Phase, startSec, endSec)` windows from `session.events`.
2. Inverts the union of all event windows to produce `GAMEPLAY` windows.
3. For each phase, computes a `Map<KpiId, Double>` of available KPI values from `SessionResult` histories/aggregates.
4. Returns `KpiInput(session.deviceModel, rawByPhase)`.

The `KpiInput` shape comes from `KpiScoringFacade.kt` (already on disk). The adapter is the missing glue between captured sessions and the scoring pipeline.

## Architecture Decisions

| # | Decision | Rejected Alternatives | Rationale |
|---|----------|-----------------------|-----------|
| D1 | Top-level pure `fun toKpiInput(...)`, no class | `class SessionResultMapper(...)` with deps | Adapter has zero collaborators. Project convention (`inferGameTargetFps`, `computeFrameSnapshot`) prefers top-level pure functions. Easier to unit-test, no DI plumbing. |
| D2 | New subpackage `core.kpi.adapter` | Add to `core.kpi` or `viewmodel.kpi` | `core.kpi.adapter` keeps the scoring core import-clean (no `viewmodel.*` imports). The `adapter` subpackage is the ONLY place allowed to import both `viewmodel.SessionResult` and `core.kpi.KpiInput`. Reviewer-grep enforces this. |
| D3 | Private const `eventTypeToPhase: Map<EventType, Phase>` table at top of file | `when { }` switch inline | Table is the spec contract. Easier to diff when new event types arrive. Compile-time exhaustiveness checked via `EventType.values()`-driven test. |
| D4 | Unmapped event types (IAP, ANR, etc.) still consume time | Ignore entirely (count as GAMEPLAY) | Otherwise an IAP popup with a frozen UI would pollute GAMEPLAY FPS averages. We carve the window OUT of GAMEPLAY without creating a phase for it. |
| D5 | Use `fpsTimed: List<TimedSample(second, value)>` for per-phase FPS averaging | Re-slice raw `fpsHistory` by index | `fpsTimed` has explicit timestamps; `fpsHistory` is index-based and re-aligning to event ms windows would re-introduce the index-vs-time bug class from v4.2.5. Same for `fpowerTimed`. |
| D6 | Session-wide aggregates (`peakMemMb`, `avgCpu`, `maxCpu`, `maxTempCpu`) used for EVERY phase | Re-slice histories per phase | The session-level aggregates are ALREADY filtered by `FilteredMetricsCalculator` (v4.4.0). Re-slicing would compute different numbers and break the contract that "the same KPI value used in the report is what's scored". Future change can refine. |
| D7 | Skip KPIs whose source data is missing rather than emit 0.0 | Emit 0.0 sentinel | `KpiInput` contract says "missing = excluded from aggregation"; emitting 0 would tank the score. Matches kpi-scoring D4 ("renormalize on present KPIs"). |
| D8 | No CINEMATIC, TUTORIAL phases emitted in v1 | Heuristic detection | No detector exists. Emitting empty phases is silent breakage; emitting nothing is honest. |

## Data Flow

```
SessionResult
  ├─ deviceModel ────────────────────────────────► KpiInput.deviceModel
  ├─ events: List<DetectedEvent> ──┐
  │   (filter mappable types)      │
  │                                ▼
  │                        eventTypeToPhase       buildWindows()
  │                                │              splitGameplayWindows()
  │                                ▼                       │
  │                        List<PhaseWindow>               │
  │                                │                       │
  ├─ fpsTimed, fpowerTimed ────────┤   sliceTimed()        │
  ├─ avgFps, avgCpu, maxCpu, ...  ─┤   sessionScalars()    │
  ├─ memHistory ───────────────────┤   averageOrPeak()     │
  └─ thermalAvailable / fpowerAvailable ───────────────┐   │
                                                       │   │
                                                       ▼   ▼
                              Map<Phase, Map<KpiId, Double>>
                                                       │
                                                       ▼
                                              KpiInput.rawByPhase
```

## File Changes

| File | Action | Description |
|------|--------|-------------|
| `core/kpi/adapter/SessionResultToKpiInput.kt` | Create | Pure `toKpiInput` + private helpers + EventType→Phase table |
| `src/test/kotlin/.../core/kpi/adapter/SessionResultToKpiInputTest.kt` | Create | 6 test cases per spec scenarios + table exhaustiveness |

## Interfaces / Contracts

```kotlin
package com.gameperf.desktop.core.kpi.adapter

import com.gameperf.desktop.core.events.EventType
import com.gameperf.desktop.core.kpi.KpiId
import com.gameperf.desktop.core.kpi.KpiInput
import com.gameperf.desktop.core.kpi.Phase
import com.gameperf.desktop.viewmodel.SessionResult

/**
 * Pure mapping: captured [SessionResult] → scoring [KpiInput].
 *
 * EventType → Phase table (single source of truth):
 *  - APP_STARTUP        → APP_STARTUP
 *  - LOADING            → LEVEL_LOADING
 *  - SCREEN_TRANSITION  → SCREEN_NAV
 *  - INTERSTITIAL       → INTERSTITIAL_AD
 *  - REWARDED_VIDEO     → REWARDED_AD
 *  - others             → not mapped to a Phase, time still excluded from GAMEPLAY
 *
 * @since v4.5 (kpi-session-adapter)
 */
fun toKpiInput(session: SessionResult): KpiInput

// Private helpers (top-level, file-private):
private data class PhaseWindow(val phase: Phase, val startSec: Int, val endSec: Int)
private val eventTypeToPhase: Map<EventType, Phase>
private fun buildPhaseWindows(session: SessionResult): List<PhaseWindow>
private fun gameplayWindows(session: SessionResult, mappedWindows: List<PhaseWindow>, unmappedWindows: List<IntRange>): List<PhaseWindow>
private fun kpisForPhase(session: SessionResult, windows: List<PhaseWindow>): Map<KpiId, Double>
```

### Per-Phase KPI Mapping Rules

| KpiId | Source (when present) | Skip when |
|-------|-----------------------|-----------|
| FPS_AVG | mean of `fpsTimed` values in window (fallback `session.avgFps`) | both empty / 0 |
| FPS_P1 | session `p1Fps` | == 0 AND no history |
| FRAME_TIME_P99 | session `p99FrameTime` | == 0.0 |
| JANK_COUNT | session `totalJank` | n/a (always present, 0 is valid) |
| CPU_AVG_NORMALIZED | session `avgCpu` | == 0 |
| CPU_MAX | session `maxCpu` | == 0 |
| RAM_AVG | mean of `memHistory` (fallback `peakMemMb`) | both 0 |
| RAM_MAX | session `peakMemMb` | == 0 |
| TEMP_AVG | mean of `tempCpuHistory` in window | history empty / thermal unavailable |
| TEMP_MAX | session `maxTempCpu` | == 0.0 / thermal unavailable |
| FPOWER | session `fpowerAvg` | `fpowerAvailable == false` / history empty |
| BATTERY_DRAIN | session `batteryDrain` | == 0 |

CPU/RAM session-wide assignment is intentional (D6). Per-phase refinement is a follow-up.

## Testing Strategy

| Layer | Test | Approach |
|-------|------|----------|
| Unit | `toKpiInput - happy path with one interstitial` | Build `SessionResult` with FPS history + 1 interstitial event, assert both phases populated |
| Unit | `toKpiInput - missing thermal` | `maxTempCpu=0, tempCpuHistory=empty` → no TEMP_* in any phase |
| Unit | `toKpiInput - missing fpower` | `fpowerAvailable=false` → no FPOWER in any phase |
| Unit | `toKpiInput - deterministic` | invoke twice, assert structural equality |
| Unit | `toKpiInput - empty events` | `events=empty` → only GAMEPLAY phase, covers full duration |
| Unit | `toKpiInput - two interstitials` | events=[ad@10-20, ad@40-50], duration=60 → INTERSTITIAL_AD + GAMEPLAY only, gameplay windows = [0,10)∪[20,40)∪[50,60) |
| Unit | `toKpiInput - IAP carves gameplay but no phase` | IAP event → no IAP phase, GAMEPLAY excludes IAP window |
| Unit | `toKpiInput - does not mutate input` | snapshot `events.size`/`fpsHistory.size`, invoke, assert unchanged |

## Migration / Rollout

No migration. No call sites added. Pure new module. Detekt enforced via `./gradlew check`.

## Open Questions

- [ ] Should GAMEPLAY KPI values for FPS be window-sliced from `fpsTimed` even when one phase covers most of the session? Current design: yes (windowed mean of `fpsTimed`). Trade-off: slightly different from `session.avgFps` when many events. Acceptable for v1; documented.
- [ ] Whether to surface a `Map<EventType, List<Pair<Long, Long>>>` summary for downstream debugging. Deferred — not in scope.
