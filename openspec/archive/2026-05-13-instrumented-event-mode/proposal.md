# Proposal: instrumented-event-mode

> Scope: Sprint 3 of Issue #2 / Block D. Narrower and stricter than the Sprint 3 stub in `event-segmentation-coverage` proposal — adopts FIXED 4-tag allowlist + CASE-SENSITIVE matching + tag-keyed open/close lifecycle. Supersedes `ESC-INSTR-001..003` for the four allowed tags.

## Intent

Auto-detection cannot infer in-game semantic phases (cinemáticas, tutoriales, gameplay denso, eventos especiales) from logcat or dumpsys — audit #308 confirmed this. Provide an **opt-in instrumentation channel**: the game emits `GamePerf:I {Tag}.Start|Stop` logcat lines, the tool detects them as `EventType.INSTRUMENTED` events with `metadata["tag"]` set, and downstream metric filtering / report segmentation pick them up like any other event.

## Scope

### In Scope
- Add ONE `SdkSignature` entry to `core/events/SdkSignatureCatalog.ALL` keyed on tag `GamePerf` (single source of truth — CLAUDE.md rule).
- Fixed allowlist of exactly four sub-tags: `CINEMATIC`, `TUTORIAL`, `GAMEPLAY_DENSE`, `SPECIAL_EVENT`. Case-sensitive.
- Lifecycle: `GamePerf:I {Tag}.Start` opens; matching `GamePerf:I {Tag}.Stop` closes ONLY the same-tag open event (per-tag keyed lifecycle).
- Wire INSTRUMENTED events to existing event timeline + `FilteredMetricsCalculator` (already EventType-agnostic; no change needed beyond emission).
- Fixture `src/test/resources/logcat-fixtures/instrumented-opt-in.log` covering all 4 tags Start→Stop.
- README snippet (≤20 lines, Spanish tuteo formal + English mirror) "Cómo instrumentar tu juego" with copy-paste Android `Log.i("GamePerf", "CINEMATIC.Start")` example.

### Out of Scope
- VR detection (Sprint 4, separate SDD).
- IAP coverage refinement (Sprint 3.5).
- Auto-detection without `GamePerf:I` log calls (explicitly opt-in only).
- Free-text user-defined tags (kept to fixed 4-list for deterministic grading).
- `name=` / `group=` parameter capture from parent `event-segmentation-coverage` ESC-INSTR-002 — NOT carried forward; minimal protocol only.

## Capabilities

### New Capabilities
- None — extends existing `event-segmentation` capability.

### Modified Capabilities
- `event-segmentation`: ADD requirements `IEM-001..IEM-006` (catalog entry, tag allowlist, case sensitivity, per-tag lifecycle, malformed-tag rejection, nested-same-tag handling). These OVERRIDE Sprint 3's ESC-INSTR-001..003 for the 4 fixed tags.

## Approach

1. New top-level `private val INSTRUMENTED_OPEN_RE` / `INSTRUMENTED_CLOSE_RE` in `core/events/` — case-sensitive, capture group for tag, anchored.
2. Catalog entry: `defaultType = INSTRUMENTED`, `logcatTags = ["GamePerf"]`, single open + single close pattern. Tag value extracted in detector path, validated against the 4-element allowlist; non-matching tags are silently rejected with a debug-level warning.
3. Detector adjustment: `handleLogLine` for the GamePerf signature uses a tag-keyed `openEvents` slot (`"GamePerf:instrumented:$tag"`) so `CINEMATIC.Stop` only closes the open `CINEMATIC` event — not other tags from same SDK.
4. Pure helper extracted: `parseInstrumentedLine(msg) -> InstrumentedHit?` (case-sensitive regex). Tested without detector.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `core/events/SdkSignatureCatalog.kt` | Modified | +1 catalog entry, +1 tag in `logcatTagArgs()` (already covered if reused). |
| `core/events/InstrumentedLineParser.kt` | New | Pure helper, top-level regex, returns `InstrumentedHit(tag, isStart)` or null. |
| `core/events/EventDetectorImpl.kt` | Modified | New branch in `handleLogLine` for GamePerf signature: parse → validate → open/close per-tag-keyed event. ~40 LOC. |
| `src/test/resources/logcat-fixtures/instrumented-opt-in.log` | New | Fixture with 4 Start/Stop pairs in sequence. |
| `src/test/kotlin/core/events/InstrumentedLineParserTest.kt` | New | Positive: 4 tags Start/Stop. Negative: lowercase, malformed, foreign tag, unknown sub-tag. |
| `src/test/kotlin/core/events/EventDetectorImplInstrumentedTest.kt` | New | Lifecycle: open→close pairing, nested same-tag, overlapping different tags, orphan Stop, no Start dropped. |
| `src/test/kotlin/core/events/SdkSignatureCatalogTest.kt` | Modified | Catalog size 17 → 18; invariant updates. |
| `README.md` + `README_EN.md` | Modified | "Modo instrumentado (opt-in)" subsection, 10-20 lines. |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Conflict with parent `event-segmentation-coverage` Sprint 3 spec (ESC-INSTR-001..003) | High | Document supersession in spec preamble; on apply, retire ESC-INSTR-* delta from parent if still in flight. |
| Detector currently uses non-per-tag close matching (`matchClose` closes any open of same SDK) | Confirmed | Special-case the GamePerf signature with per-tag-keyed open map; pattern already exists for `activityClasses` (`sdk:activity:cmp`). |
| Game devs forget Stop → orphaned open event | Medium | `EventDetectorImpl.stop()` already force-closes with `endInferred=true`. Existing behaviour covers it. |
| Tag typo (e.g. `Cinematic`) silently ignored due to case-sensitivity | Medium | Negative test asserts no event; documented in README troubleshooting. |
| 17→18 catalog size assertion drift | Low | Update existing test in same TDD batch. |

## Rollback Plan

Single revert of the change branch. INSTRUMENTED enum value pre-exists in `DetectedEvent.kt` (declared since v4.4.0 Sprint 0); leaving the enum is harmless. Catalog entry removal reverts `logcatTagArgs()` to prior set; existing tests for the parent Sprint 3 stub will fail until parent change either ships its own variant or stays unimplemented.

## Dependencies

- None on other SDD changes for compile. Logical dependency on `event-segmentation-coverage` Sprint 0 (refactor of `SdkSignature` to `openPatterns: List<Pair<Regex, EventType>>`) — already shipped per `SdkSignature.kt:46`.

## Success Criteria

- [x] `./gradlew check` green (test + detekt, `ignoreFailures=false`).
- [x] 4 tags × 2 (open/close) × positive tests + negative tests all green.
- [x] Fixture-driven smoke produces 4 distinct INSTRUMENTED events from one log.
- [x] README + README_EN section added; UI (Spanish formal) untouched (no UI change needed; events flow through existing report pipeline).
- [x] Engram saves: `sdd/instrumented-event-mode/{proposal,spec,design,tasks}`.
- [x] CHANGELOG entry referencing Issue #2 Block D Sprint 3.
