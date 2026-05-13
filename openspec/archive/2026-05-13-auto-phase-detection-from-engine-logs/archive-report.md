# Archive Report: auto-phase-detection-from-engine-logs

**Change**: `auto-phase-detection-from-engine-logs`
**Archived**: 2026-05-13
**Status**: COMPLETE (75 new tests, gradle check GREEN, detekt clean, CCN startCapture <=200 D7 preserved)

## Engram source-of-truth observations

| Artifact | Engram ID | Topic key |
|---|---|---|
| proposal | #441 | `sdd/auto-phase-detection-from-engine-logs/proposal` |
| spec | #442 | `sdd/auto-phase-detection-from-engine-logs/spec` |
| design | #443 | `sdd/auto-phase-detection-from-engine-logs/design` |
| tasks | #444 | `sdd/auto-phase-detection-from-engine-logs/tasks` |
| apply-progress | #445 | `sdd/auto-phase-detection-from-engine-logs/apply-progress` |
| verify-report | #448 | `sdd/auto-phase-detection-from-engine-logs/verify-report` |

## Change summary

Auto-detection of game phases (CUTSCENE / MENU_NAV / COMBAT_PHASE / TUTORIAL_PHASE)
from Unity and Unreal scene names emitted in logcat by default. Zero-touch (no source-
code access required), closes the gap left by Sprint 3 instrumented opt-in mode which
required dev cooperation.

## Stats

- 75 new tests across 5 test files
- 4 new main files + 4 modified main files
- 2 modified fixtures + 1 modified ReportGenerator
- CHANGELOG.md v4.6.0 unreleased section updated with full feature + technical details
- README.md + README_EN.md feature bullets added

## Key decisions (full design in engram #443)

- D1: 4 additive EventType variants (no breaking)
- D2: `EnginePhaseCatalog` single source (CLAUDE.md v4.2.13)
- D3: `Confidence.MEDIUM` default + mandatory banner disclaimer
- D5: Unity + Unreal scope v1 (Cocos2d/Godot/GameMaker out of scope)
- D6: CUTSCENE filtered from gameplay aggregates; MENU_NAV/COMBAT_PHASE/TUTORIAL_PHASE NOT filtered
- D7: `classifyAutoPhase` helper extracted to keep `handleLogLine` CCN <=200

## Follow-ups deferred

- Cocos2d / Godot / GameMaker support (engine doesn't emit scene names by default)
- Phase detection without scene change (e.g. boss in same scene)
- CUTSCENE filter user toggle
- Real-device lab verification of bilingual keywords

## Refs

- Engram #439 `project/qa-zero-touch-constraint` — the constraint that justified this change
- Engram #440 `sdd/auto-phase-detection-from-engine-logs/explore` — feasibility research
