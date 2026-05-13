# Tasks: instrumented-event-mode

> Strict TDD enforced. Each `[ ] *.R` (RED) task writes a failing test FIRST. The next `*.G` (GREEN) task makes it pass with minimum code. Never write production before its RED gate is green-failing locally.

## Phase 1: Pure parser foundation

- [x] 1.1.R Add `InstrumentedLineParserTest.kt` with one test `parses CINEMATIC dot Start as open hit`; assert `parse("CINEMATIC.Start")` returns `InstrumentedHit("CINEMATIC", true)`. Run `./gradlew test` — confirm RED (class not found).
- [x] 1.2.G Create `core/events/InstrumentedHit.kt` (data class) and `core/events/InstrumentedLineParser.kt` with `ALLOWED_TAGS`, top-level `OPEN_RE`/`CLOSE_RE`, and `parse(msg)`. Make 1.1 green.
- [x] 1.3.R Add tests for the other 3 valid tags (TUTORIAL, GAMEPLAY_DENSE, SPECIAL_EVENT) × {Start, Stop}. Should pass already (regex covers them) — confirm green.
- [x] 1.4.R Add NEGATIVE tests. Confirm green.
- [x] 1.5 Run `./gradlew detekt` on touched files; fix any findings.

## Phase 2: Catalog wiring

- [x] 2.1.R In `SdkSignatureCatalogTest.kt`, change the `seventeen catalogued SDKs` test to `eighteen catalogued SDKs and engines`, add `"GamePerf"` to expected set. Confirm RED.
- [x] 2.2.G Append 18th entry to `SdkSignatureCatalog.ALL`: GamePerf with INSTRUMENTED defaultType, permissive open/close regexes. Make 2.1 green. Also extended `noActivityRequired` bypass to include `EventType.INSTRUMENTED`.
- [x] 2.3.R Add test `logcatTagArgs includes GamePerf:D`. Confirm green.

## Phase 3: Detector branch (lifecycle)

- [x] 3.1.R Create `EventDetectorImplInstrumentedTest.kt`. Test 1: `CINEMATIC.Start emits one INSTRUMENTED event`. Confirm RED.
- [x] 3.2.G In `EventDetectorImpl.handleLogLine`, add early branch `if (line.tag == "GamePerf") { handleInstrumentedLine(line); return }`. Implement `handleInstrumentedLine` + `openInstrumented`. Make 3.1 green.
- [x] 3.3.R Add test `CINEMATIC.Stop closes matching open`. Confirm RED.
- [x] 3.4.G Implement `closeInstrumented`. Make 3.3 green.
- [x] 3.5.R `TUTORIAL.Stop does not close CINEMATIC open` + overlapping independent close (IEM-004). Green.
- [x] 3.6.R `nested CINEMATIC.Start is no-op` (IEM-006). Green.
- [x] 3.7.R `orphan Stop ignored silently` (IEM-005). Green.
- [x] 3.8.R `unknown tag silently rejected` (IEM-002). Green.
- [x] 3.9.R `lowercase tag silently rejected` + mixed-case (IEM-003). Green.
- [x] 3.10.R `foreground-stale instrumented still opens` (IEM-008). Green.
- [x] 3.11.R `stop() force-closes open instrumented with endInferred=true`. Green.

## Phase 4: Fixture-driven smoke

- [x] 4.1 Created `src/test/resources/logcat-fixtures/instrumented-opt-in.log`: 65 lines in threadtime format. Includes 4 Start/Stop pairs (CINEMATIC, TUTORIAL, GAMEPLAY_DENSE, SPECIAL_EVENT) with realistic spacing, plus 2 negative noise lines (`cinematic.Start`, `MENU.Start`) plus surrounding non-GamePerf log noise.
- [x] 4.2.R Added test `instrumented-opt-in fixture produces four INSTRUMENTED events` in `EventDetectorImplInstrumentedTest.kt`: load fixture, parse each line with `LogcatLineParser`, feed all to a fresh `EventDetectorImpl`, assert events.size == 4, distinct tags in metadata, all closed (endInferred=false), no warnings, open map drained. GREEN on first run.

## Phase 5: Documentation + changelog

- [x] 5.1 Added §"Modo instrumentado (opt-in)" to `README.md` (~32 lines, castellano tuteo formal). Shows `Log.i("GamePerf", "CINEMATIC.Start")` Kotlin + `adb shell log -t GamePerf -p i` shell example. Lists the 4 fixed tags with one-line description each. Documents strict case-sensitivity and opt-in nature.
- [x] 5.2 Mirror section "Instrumented mode (opt-in)" added to `README_EN.md` (mirror in English).
- [x] 5.3 CHANGELOG entry under v4.5.0 unreleased: 1 bullet in "Que hay de nuevo" + 7 detail bullets appended to existing "Detalles tecnicos" block (parser, hit class, catalog wiring, detector branch, deferred extensions, supersession of ESC-INSTR-001..003, SDD artefacts + test count summary).

## Phase 6: Verification gate

- [x] 6.1 `./gradlew check` GREEN in **1m 46s** (test + detekt). Zero failures.
- [x] 6.2 Test count: total **1092 passing / 0 failing / 10 ignored**. IEM change contributed exactly **+28 new tests** (15 parser + 12 detector-lifecycle + 1 fixture-smoke). Catalog test modifications: 1 renamed (`seventeen` → `eighteen`) + 1 added (`logcatTagArgs includes GamePerf:D`).
- [x] 6.3 Final apply-progress saved to engram (`sdd/instrumented-event-mode/apply-progress` topic, this update). Ready for `sdd-verify`.

## Status

**30/30 tasks complete.** All phases passed `./gradlew check` (1m 46s). Verify gate PASS (see verify-report.md).
