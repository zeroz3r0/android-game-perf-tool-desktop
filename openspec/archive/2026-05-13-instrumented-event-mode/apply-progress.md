# Apply Progress: instrumented-event-mode

**Mode**: Strict TDD
**Scope**: ALL phases (1+2+3 from previous run, 4+5+6 this run)
**Result**: ALL 30 TASKS COMPLETE — `./gradlew check` green (1m 46s).
**Status**: Ready for `sdd-verify`.

## Phase 1: Pure parser foundation — COMPLETE ✅ (previous run)

- [x] 1.1.R `InstrumentedLineParserTest.kt` first RED test — Unresolved reference confirmed.
- [x] 1.2.G Created `InstrumentedHit.kt` + `InstrumentedLineParser.kt`. GREEN.
- [x] 1.3.R 7 triangulation tests (4 tags × Start/Stop). GREEN.
- [x] 1.4.R 7 negative tests (case, unknown tag, trailing, empty). GREEN.
- [x] 1.5 Detekt clean.

## Phase 2: Catalog wiring — COMPLETE ✅ (previous run)

- [x] 2.1.R Renamed catalog test to "eighteen catalogued SDKs and engines", bumped 17 → 18, added "GamePerf" to expected set. RED confirmed (AssertionError).
- [x] 2.2.G Appended 18th `SdkSignature("GamePerf", defaultType=INSTRUMENTED, …)`. Extended `noActivityRequired` bypass to include `EventType.INSTRUMENTED`. GREEN.
- [x] 2.3.R Added `logcatTagArgs includes GamePerf:D for instrumented opt-in mode` test. GREEN.

## Phase 3: Detector branch — COMPLETE ✅ (previous run)

- [x] 3.1.R `CINEMATIC.Start emits one INSTRUMENTED event` — RED.
- [x] 3.2.G Added `if (line.tag == "GamePerf")` fast-path branch in `handleLogLine`. Implemented `handleInstrumentedLine`, `openInstrumented` (per-tag key, EVT-009 cap, foreground-guard bypass per IEM-008). GREEN.
- [x] 3.3.R `CINEMATIC.Stop closes the matching open` — RED.
- [x] 3.4.G Wired `closeInstrumented`. GREEN.
- [x] 3.5.R Two tests: `TUTORIAL.Stop does not close CINEMATIC` + `overlapping CINEMATIC and TUTORIAL close independently` (IEM-004). GREEN.
- [x] 3.6.R `re-entrant CINEMATIC.Start does not open a second event` (IEM-006). GREEN.
- [x] 3.7.R `orphan GAMEPLAY_DENSE.Stop is ignored without warning` (IEM-005). GREEN.
- [x] 3.8.R `unknown UPPER_SNAKE tag MENU.Start emits nothing` (IEM-002). GREEN.
- [x] 3.9.R Two tests: lowercase + mixed-case `Cinematic` variants emit nothing (IEM-003). GREEN.
- [x] 3.10.R `foreground-stale CINEMATIC.Start still opens` (IEM-008). GREEN.
- [x] 3.11.R `detector stop() force-closes open INSTRUMENTED with endInferred=true`. GREEN.

## Phase 4: Fixture-driven smoke — COMPLETE ✅ (this run)

- [x] 4.1 Created `src/test/resources/logcat-fixtures/instrumented-opt-in.log` — 65 lines threadtime format. Contains 4 valid Start/Stop pairs (CINEMATIC at t=1.5s, TUTORIAL at t=6.5s, GAMEPLAY_DENSE at t=13.5s, SPECIAL_EVENT at t=20s) interleaved with realistic non-GamePerf game/engine noise (MyGame frames, Unity ticks), plus 2 negative noise lines:
    - `cinematic.Start` (lowercase variant, IEM-003 rejection)
    - `MENU.Start` (unknown tag, IEM-002 rejection)
- [x] 4.2.R Added `instrumented-opt-in fixture produces four INSTRUMENTED events` test to `EventDetectorImplInstrumentedTest.kt`. End-to-end: reads fixture via `BufferedReader(InputStreamReader(.., UTF_8))`, parses each line with `LogcatLineParser.parse`, feeds parsed `LogLine` to `EventDetectorImpl.handleLogLine`. Asserts:
    - events.size == 4
    - every event: type=INSTRUMENTED, sdkSource="GamePerf", confidence=HIGH, endMs != null, endInferred == false
    - distinct tags == {CINEMATIC, TUTORIAL, GAMEPLAY_DENSE, SPECIAL_EVENT}
    - warnings.value.isEmpty() (noise paths are silent per spec)
    - openEventCountForTest() == 0 (all events closed → map drained)
    - fixture length in 60..80 range (sanity-pins fixture identity)
  GREEN on first run — Phases 1-3 production code already covers all noise-rejection paths so no new production code needed.

## Phase 5: Documentation + changelog — COMPLETE ✅ (this run)

- [x] 5.1 README.md: new section "Modo instrumentado (opt-in)" inserted after "## Qué hace", before "## Instalación". ~32 lines, castellano tuteo formal (matches CLAUDE.md "Convención de idiomas": "tienes", "puedes", "emite", "está", NO voseo). Lists the 4 fixed tags with one-line domain description each (CINEMATIC = secuencias cinemáticas, TUTORIAL = pantallas de tutorial, GAMEPLAY_DENSE = combate denso/partículas, SPECIAL_EVENT = jefes/eventos puntuales). Includes both a Kotlin example (`Log.i("GamePerf", "CINEMATIC.Start")`) and a shell example (`adb shell log -t GamePerf -p i "CINEMATIC.Start"`). Documents strict case-sensitivity + silent drop of unknown tags + opt-in nature (no behaviour change if game doesn't emit the lines) + explains how the ranges are excluded from game FPS averages (same treatment as ads/loading screens).
- [x] 5.2 README_EN.md: mirror section "Instrumented mode (opt-in)" in English, section-by-section equivalent.
- [x] 5.3 CHANGELOG.md: appended to v4.5.0 unreleased section. 1 bullet added to "Que hay de nuevo" (user-facing intro). 7 bullets appended to existing "Detalles tecnicos" block (the FPower block) — parser file, hit class, catalog wiring (18th entry), detector branch (early-branch + per-tag keying + IEM-006 containsKey early-return + IEM-008 foreground bypass), deferred extensions, supersession note (ESC-INSTR-001..003 retire on archive), SDD change summary + 28-test count + fixture description.

## Phase 6: Verification gate — COMPLETE ✅ (this run)

- [x] 6.1 `./gradlew check` GREEN in **1m 46s**. Detekt clean, all tests pass.
- [x] 6.2 Test totals from gradle html report: **1092 passing / 0 failing / 10 ignored**. IEM change net contribution: **+28 new tests** (15 `InstrumentedLineParserTest` + 12 `EventDetectorImplInstrumentedTest` lifecycle + 1 `EventDetectorImplInstrumentedTest` fixture-smoke). Catalog test modifications: 1 renamed (`seventeen` → `eighteen`) + 1 added (`logcatTagArgs includes GamePerf:D`). Matches the expected `+~28` from the orchestrator brief exactly.
- [x] 6.3 This artifact = final apply-progress snapshot. Tasks observation #366 updated to mark all 30 tasks `[x]`. Ready for `sdd-verify`.

## Files Changed (cumulative, all phases)

| File | Action | What Was Done |
|------|--------|---------------|
| `src/main/kotlin/com/gameperf/desktop/core/events/InstrumentedHit.kt` | Created | `internal data class InstrumentedHit(val tag: String, val isStart: Boolean)`. |
| `src/main/kotlin/com/gameperf/desktop/core/events/InstrumentedLineParser.kt` | Created | Pure `internal object` parser. `ALLOWED_TAGS = setOf("CINEMATIC","TUTORIAL","GAMEPLAY_DENSE","SPECIAL_EVENT")`. Top-level `OPEN_RE = Regex("""^([A-Z_]+)\.Start$""")` and `CLOSE_RE` analog. `parse(msg): InstrumentedHit?` uses `matchEntire` + allowlist filter. Single source of truth for the protocol grammar. |
| `src/main/kotlin/com/gameperf/desktop/core/events/SdkSignatureCatalog.kt` | Modified | Inserted 18th entry "GamePerf" with `defaultType = INSTRUMENTED`, `activityClasses = emptyList()`, `logcatTags = listOf("GamePerf")`, permissive `[A-Z_]+\.{Start,Stop}$` open/close regexes. Entry is dormant in production — the detector special-cases the tag before reaching `matchOpen`. Patterns exist only for catalog invariants + `logcatTagArgs()` plumbing (IEM-007). |
| `src/main/kotlin/com/gameperf/desktop/core/events/EventDetectorImpl.kt` | Modified | (a) Added `if (line.tag == "GamePerf") { handleInstrumentedLine(line); return }` at the very top of `handleLogLine`, before `am_proc_start` check. (b) New `handleInstrumentedLine` delegates to `InstrumentedLineParser.parse` then routes to `openInstrumented` or `closeInstrumented`. (c) `openInstrumented`: key shape `"GamePerf:instrumented:$tag"`, EVT-009 cap respected, `containsKey` no-op (IEM-006), foreground-guard skipped (IEM-008), confidence HIGH, `metadata={"source":"logcat","tag":tag}`. (d) `closeInstrumented`: silent no-op on miss (IEM-005), `tryClose` on hit. |
| `src/test/kotlin/com/gameperf/desktop/core/events/InstrumentedLineParserTest.kt` | Created | 15 tests: 1 RED foundation + 7 triangulation + 7 negatives. Pure unit, no fakes. |
| `src/test/kotlin/com/gameperf/desktop/core/events/SdkSignatureCatalogTest.kt` | Modified | Renamed `seventeen` test → `eighteen`, bumped assertion, added "GamePerf" to expected set. Extended `noActivityRequired` bypass to include `EventType.INSTRUMENTED`. New test `logcatTagArgs includes GamePerf:D for instrumented opt-in mode`. |
| `src/test/kotlin/com/gameperf/desktop/core/events/EventDetectorImplInstrumentedTest.kt` | Created | 13 tests: 12 lifecycle (IEM-001..006 + IEM-008 + stop() force-close) + 1 fixture-smoke (Phase 4). Drives state machine directly via `internal` hooks; no coroutines, no mocks, `FakeAdbBridge`. |
| `src/test/resources/logcat-fixtures/instrumented-opt-in.log` | Created | 65-line threadtime fixture: 4 valid Start/Stop pairs + 2 noise lines + ambient game/engine log noise. |
| `README.md` | Modified | New section "Modo instrumentado (opt-in)" inserted after "Qué hace". Castellano tuteo formal. |
| `README_EN.md` | Modified | Mirror section "Instrumented mode (opt-in)" in English. |
| `CHANGELOG.md` | Modified | v4.5.0 unreleased: 1 user-facing bullet in "Que hay de nuevo" + 7 detail bullets appended to "Detalles tecnicos" FPower block. |

## Test Summary
- **New tests written (this change)**: 28 (15 parser + 12 detector lifecycle + 1 fixture-smoke)
- **Catalog tests modified**: 1 (renamed `seventeen` → `eighteen`)
- **Catalog tests added**: 1 (`logcatTagArgs includes GamePerf:D`)
- **Total new/modified test assertions**: ~30
- **Layers**: Unit (28), Integration (0), E2E (0). The fixture-smoke is technically a unit test driving the state machine synchronously via internal entry points — no real coroutines, no real `LogcatCapture` process.
- **Pure functions created**: 1 (`InstrumentedLineParser.parse`)
- **`./gradlew check`**: ✅ PASS (full project, 1m 46s, detekt clean)
- **Suite totals**: 1092 passing / 0 failing / 10 ignored

## Deviations from Design
None — implementation matches `sdd/instrumented-event-mode/design` (#365). The catalog entry uses the permissive `[A-Z_]+\.Start$` shape suggested by design; the allowlist enforcement is correctly delegated to `InstrumentedLineParser.ALLOWED_TAGS`. Documentation voice (castellano tuteo formal, no voseo) matches CLAUDE.md "Convención de idiomas".

## Status
**30/30 tasks complete.** Phases 1+2+3+4+5+6: ALL COMPLETE.
