# Archive Report: instrumented-event-mode

**Change**: `instrumented-event-mode`
**Archive date**: 2026-05-13
**Verify status**: PASS ✅ (engram #375)
**Backend**: engram (primary) + openspec archive folder (audit trail)
**Archived folder**: `openspec/archive/2026-05-13-instrumented-event-mode/`

## Engram artifact IDs (audit trail)

| Artifact | Topic Key | Observation ID |
|----------|-----------|----------------|
| Proposal | `sdd/instrumented-event-mode/proposal` | #362 |
| Spec | `sdd/instrumented-event-mode/spec` | #364 |
| Design | `sdd/instrumented-event-mode/design` | #365 |
| Tasks | `sdd/instrumented-event-mode/tasks` | #366 |
| Apply-progress | `sdd/instrumented-event-mode/apply-progress` | #371 |
| Verify-report | `sdd/instrumented-event-mode/verify-report` | #375 |
| Pattern (dormant catalog entry) | (untopic'd) | #372 |
| Archive-report (this) | `sdd/instrumented-event-mode/archive-report` | (this save) |

## Change Summary

Implemented an **opt-in instrumentation channel** so games can emit `GamePerf:I {Tag}.Start|Stop` logcat lines and the desktop perf tool will detect them as `EventType.INSTRUMENTED` events with `metadata["tag"]` set. Adopted a deliberately **minimal** protocol: a FIXED 4-tag allowlist (`CINEMATIC`, `TUTORIAL`, `GAMEPLAY_DENSE`, `SPECIAL_EVENT`), CASE-SENSITIVE matching, and per-tag-keyed lifecycle (so `TUTORIAL.Stop` never closes a parallel `CINEMATIC.Start`). Foreground-guard bypassed for these events (game is by definition in foreground when emitting from its own process). Orphan Stops and re-entrant Starts both silently no-op. This change SUPERSEDES the Sprint 3 stub `ESC-INSTR-001..003` from the parent `event-segmentation-coverage` change — the parent-change spec and tasks files have been annotated with supersession notices pointing at this archive.

## Files Added/Modified in this Change

### Production (4 files)

| File | Action | Brief |
|------|--------|-------|
| `src/main/kotlin/com/gameperf/desktop/core/events/InstrumentedHit.kt` | Created | `internal data class InstrumentedHit(val tag, val isStart)` |
| `src/main/kotlin/com/gameperf/desktop/core/events/InstrumentedLineParser.kt` | Created | Pure `internal object` with `ALLOWED_TAGS`, top-level `OPEN_RE`/`CLOSE_RE`, `parse(msg)`. Single source of truth for the case-sensitive 4-tag allowlist. |
| `src/main/kotlin/com/gameperf/desktop/core/events/SdkSignatureCatalog.kt` | Modified | Appended 18th DORMANT entry `"GamePerf"` (defaultType=INSTRUMENTED, permissive open/close regexes). Routing happens in detector branch, not via `matchOpen` — see #372 pattern observation. Also extended `noActivityRequired` test invariant bypass to include `EventType.INSTRUMENTED`. |
| `src/main/kotlin/com/gameperf/desktop/core/events/EventDetectorImpl.kt` | Modified | Added `if (line.tag == "GamePerf")` fast-path at top of `handleLogLine`. New `handleInstrumentedLine` → `openInstrumented` / `closeInstrumented`. Per-tag key shape `"GamePerf:instrumented:$tag"`. EVT-009 cap respected. `containsKey` no-op (IEM-006). Foreground-guard skipped (IEM-008). |

### Tests (3 files, 28 new tests)

| File | Action | Brief |
|------|--------|-------|
| `src/test/kotlin/com/gameperf/desktop/core/events/InstrumentedLineParserTest.kt` | Created | 15 tests: 1 RED foundation + 7 triangulation + 7 negatives. Pure unit. |
| `src/test/kotlin/com/gameperf/desktop/core/events/EventDetectorImplInstrumentedTest.kt` | Created | 13 tests: 12 lifecycle (IEM-001..006 + IEM-008 + stop() force-close) + 1 fixture-smoke. Direct state-machine drives. |
| `src/test/kotlin/com/gameperf/desktop/core/events/SdkSignatureCatalogTest.kt` | Modified | Renamed `seventeen` → `eighteen`. Added "GamePerf" to expected set. Extended `noActivityRequired` bypass. Added `logcatTagArgs includes GamePerf:D` test. |

### Fixture (1 file)

| File | Action | Brief |
|------|--------|-------|
| `src/test/resources/logcat-fixtures/instrumented-opt-in.log` | Created | 65 lines threadtime format: 4 valid Start/Stop pairs (CINEMATIC, TUTORIAL, GAMEPLAY_DENSE, SPECIAL_EVENT) + 2 noise lines (`cinematic.Start` lowercase, `MENU.Start` unknown tag) + ambient non-GamePerf log noise. |

### Documentation (3 files)

| File | Action | Brief |
|------|--------|-------|
| `README.md` | Modified | New §"Modo instrumentado (opt-in)" inserted after "Qué hace". ~32 lines, castellano tuteo formal. Kotlin `Log.i(...)` + `adb shell log` examples. Documents case-sensitivity + opt-in nature + how ranges are excluded from game FPS averages. |
| `README_EN.md` | Modified | Mirror section "Instrumented mode (opt-in)" in English, section-by-section equivalent. |
| `CHANGELOG.md` | Modified | v4.5.0 unreleased: 1 user bullet under "Que hay de nuevo" + 7 detail bullets appended to existing "Detalles tecnicos" FPower block. |

### OpenSpec audit-trail (this archive — 7 files)

- `openspec/archive/2026-05-13-instrumented-event-mode/proposal.md`
- `openspec/archive/2026-05-13-instrumented-event-mode/spec.md`
- `openspec/archive/2026-05-13-instrumented-event-mode/design.md`
- `openspec/archive/2026-05-13-instrumented-event-mode/tasks.md`
- `openspec/archive/2026-05-13-instrumented-event-mode/apply-progress.md`
- `openspec/archive/2026-05-13-instrumented-event-mode/verify-report.md`
- `openspec/archive/2026-05-13-instrumented-event-mode/archive-report.md` (this file)

### Parent-change annotations (2 files)

- `openspec/changes/event-segmentation-coverage/specs/event-segmentation/spec.md` — §7 "INSTRUMENTED opt-in protocol (Sprint 3)" annotated as SUPERSEDED. `ESC-INSTR-001`/`003` redirected to `IEM-001`/`IEM-007`. `ESC-INSTR-002` (name/group capture) marked DROPPED. Original wording preserved as historical traceability.
- `openspec/changes/event-segmentation-coverage/tasks.md` — Sprint 3 section header annotated with shipped/archived banner pointing at this archive. Batch 3.1 and 3.2 marked SUPERSEDED (kept for historical traceability).

## Tests Added

- **28 new tests**: 15 in `InstrumentedLineParserTest.kt` + 12 lifecycle in `EventDetectorImplInstrumentedTest.kt` + 1 fixture-smoke in same file.
- **2 catalog test edits**: 1 renamed (`seventeen` → `eighteen`) + 1 added (`logcatTagArgs includes GamePerf:D`).
- **Suite totals after change**: 1092 passing / 0 failing / 10 ignored.
- **`./gradlew check`**: PASS in 1m 46s. Detekt clean.

## Spec Requirements Implemented

| ID | Requirement | Test Class(es) |
|----|-------------|----------------|
| IEM-001 | Catalog entry `"GamePerf"` with defaultType=INSTRUMENTED, logcatTags=["GamePerf"], `{Tag}.Start`/`{Tag}.Stop` patterns | `SdkSignatureCatalogTest::eighteen catalogued SDKs and engines`; `EventDetectorImplInstrumentedTest::CINEMATIC.Start emits one INSTRUMENTED event` (+11 others) |
| IEM-002 | FIXED 4-tag allowlist; foreign tags silently rejected | `InstrumentedLineParserTest` (negative tests); `EventDetectorImplInstrumentedTest::unknown UPPER_SNAKE tag MENU.Start emits nothing` |
| IEM-003 | Case-sensitive matching | `InstrumentedLineParserTest` (lowercase / mixed-case negatives); `EventDetectorImplInstrumentedTest::lowercase tag silently rejected` + `mixed-case Cinematic rejected` |
| IEM-004 | Per-tag-keyed lifecycle; TUTORIAL.Stop never closes CINEMATIC | `EventDetectorImplInstrumentedTest::TUTORIAL.Stop does not close CINEMATIC open` + `overlapping CINEMATIC and TUTORIAL close independently` |
| IEM-005 | Orphan Stop silently ignored | `EventDetectorImplInstrumentedTest::orphan GAMEPLAY_DENSE.Stop ignored without warning` |
| IEM-006 | Re-entrant same-tag Start no-op | `EventDetectorImplInstrumentedTest::re-entrant CINEMATIC.Start does not open a second event` |
| IEM-007 | `logcatTagArgs()` includes `"GamePerf:D"` | `SdkSignatureCatalogTest::logcatTagArgs includes GamePerf:D for instrumented opt-in mode` |
| IEM-008 | Foreground-guard bypass for instrumented opens | `EventDetectorImplInstrumentedTest::foreground-stale CINEMATIC.Start still opens` |

## Lessons Learned

### 1. Dormant catalog entry pattern (engram #372)

When adding an event type that needs routing semantics incompatible with the generic `SdkSignatureCatalog.matchOpen` flow, prefer a **dedicated detector branch** that runs BEFORE the generic match path, and leave the catalog entry "dormant" (it satisfies catalog invariants — open pattern present, close pattern present, defaultType set — and feeds `logcatTagArgs()`, but is never actually consulted for classification at runtime). The catalog entry MUST carry a clear KDoc comment saying so, otherwise the next engineer will assume the regexes are what classifies and waste time reading the wrong code path. This was the only viable approach here: generalising the catalog to support per-tag-keyed close matching would have changed semantics for the 17 existing SDK entries (AdMob, Unity Ads, IronSource, …) and risked silent regressions in well-tested code paths.

**Corollary**: when extending invariant tests (`noActivityRequired`, `every SDK has at least one open and one close pattern`, etc.) for new event types, the bypass list grows. We added INSTRUMENTED to the `noActivityRequired` bypass — same trail as LOADING/SDK_INIT/ANR. Watch for this when adding any new EventType.

### 2. Strict TDD pays off for state machines

The detector branch came in clean on the first GREEN attempt for every test from 3.5 onward because Phase 1+2 had already nailed down the parser semantics and Phase 3.1-3.4 had nailed down the key-shape `"GamePerf:instrumented:$tag"`. Once those invariants were locked, the per-tag IEM-004/005/006/008 tests passed trivially. The 28-test count is real coverage, not redundancy — each test pins a distinct behavioural slice.

### 3. CLAUDE.md anti-duplication rule held up

The user's standing CLAUDE.md operative rule says: "la detección de eventos vive ÚNICAMENTE en `core/events/`". This change respected it — no detection logic leaked into the report layer, no second copy of the protocol grammar exists outside `InstrumentedLineParser.kt`. The grammar lives in ONE file (`InstrumentedLineParser.ALLOWED_TAGS` + `OPEN_RE` + `CLOSE_RE`); the detector consumes it via `parse(msg)`; the catalog references it implicitly via the dormant entry's permissive regex. Future engineers tempted to add a 5th tag have ONE place to edit.

### 4. Engram-only SDD change with openspec audit at archive time

This change was tracked entirely in engram during exploration → propose → spec → design → tasks → apply → verify (no `openspec/changes/instrumented-event-mode/` folder existed mid-flight). At archive time we materialised the artifacts as files under `openspec/archive/2026-05-13-instrumented-event-mode/` so the openspec audit trail is complete and self-contained. This is a valid pattern for small/focused changes — saves on filesystem churn during iteration but still produces a permanent audit artefact at the end. Worth doing the same for similar-sized future changes.

### 5. Parent-change supersession via spec annotation

Rather than deleting the original `ESC-INSTR-001..003` requirements from the parent change's spec (which would break historical traceability — the audit needs to know those stubs existed and what replaced them), we annotated each one with a `(SUPERSEDED by IEM-NNN)` heading and replaced the body with a one-line cross-reference. The parent-change tasks file got a banner under "Sprint 3 — INSTRUMENTED opt-in protocol" pointing at this archive. Future readers see the supersession at a glance without us losing the historical wording.

## Files Left for Follow-up

None. The change is fully shipped and tests are green. Parent change `event-segmentation-coverage` retains Sprints 0, 1, 2a, 2b, 4a, 5 as open work — those are outside this change's scope.

## Status

**FULLY ARCHIVED.** Ready for commit + PR. The orchestrator should now stage:
- the 4 production files under `src/main/kotlin/com/gameperf/desktop/core/events/`
- the 3 test files + 1 fixture under `src/test/`
- the 3 documentation files (`README.md`, `README_EN.md`, `CHANGELOG.md`)
- the 7 archive files under `openspec/archive/2026-05-13-instrumented-event-mode/`
- the 2 parent-change annotations under `openspec/changes/event-segmentation-coverage/`
