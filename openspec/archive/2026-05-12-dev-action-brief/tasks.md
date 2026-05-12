# Tasks — dev-action-brief

Topic key: `sdd/dev-action-brief/tasks`
Depends on: spec `sdd/dev-action-brief/spec`, design `sdd/dev-action-brief/design`
Format: TDD red → green per checkbox. Each task ties to one or more requirement IDs.

Sprints are sequential (DAG order). Within a sprint, tasks are listed in suggested execution order.

---

## Sprint 0 — Data model + DevActionEngine foundation (~1d)

Goal: ship the data classes, engine wrapper, and baseline DAB-016 snapshot test BEFORE any catalog or enrichment work.

- [x] **S0-T1** Create new package `core/devactions/` with `package-info.kt` describing scope (mirror `core/conclusions/package-info.kt` style).
- [x] **S0-T2** Add `ConclusionEngineSnapshotTest.kt` capturing current `ConclusionEngine.run` output for a representative `ConclusionInput` fixture. Run, persist the snapshot, verify pass. Locks in DAB-016 invariant. (DAB-016)
- [x] **S0-T3** Define `GameEngine` enum (UNITY, UNREAL, COCOS2D, GODOT, NATIVE, GENERIC) — `@Serializable`. Test: enum serialises by name. (DAB-001, DAB-003)
- [x] **S0-T4** Define `Confidence` enum (HIGH, MEDIUM, LOW) — `@Serializable`. Test: serialises by name. (DAB-001)
- [x] **S0-T5** Define `DevActionEvidence` data class with `metric`, `segment`, `values: Map<String, String>`. Test: equality + serialisation round-trip. (DAB-001, DAB-013)
- [x] **S0-T6** Define `CodeAreaHint` data class. Test: equality + serialisation. (DAB-001, DAB-003)
- [x] **S0-T7** Define `ActionStep` data class with optional `tool`, `docLink`, `engineSpecific`. Test: equality + null-field handling in JSON. (DAB-001, DAB-004)
- [x] **S0-T8** Define `LogcatLineRef` data class. Test: equality + serialisation. (DAB-014)
- [x] **S0-T9** Define `DevActionItem` data class composing all the above. Test: full round-trip with non-default values for every field. (DAB-001)
- [x] **S0-T10** Define `DevActionBrief` data class with `items` + `topN` (default 5). Test: default value preserved on serialisation. (DAB-002)
- [x] **S0-T11** Implement `EvidenceBuilder.build(ruleId, input)` covering all 8 production rule ids. Use placeholder values for rules whose catalog entries land in Sprint 1. Test: each ruleId returns non-empty `values` map with expected keys. (DAB-013)
- [x] **S0-T12** Implement `ConfidenceLookup.forRule(ruleId)` with the 8 documented confidence levels from design ADR-6. Test: each known ruleId returns the documented `Confidence`. Unknown rule → `MEDIUM`. (DAB-001)
- [x] **S0-T13** Create EMPTY `CodeAreaCatalog` and `ActionStepsCatalog` (filled in Sprint 1). Both `lookup(...)` return `emptyList()`. Test: empty-state lookup behavior. (DAB-003, DAB-004)
- [x] **S0-T14** Implement `DevActionEngine.run(input: ConclusionInput): DevActionBrief` wrapping `ConclusionEngine.run`. Uses placeholder `GameEngine.GENERIC` until Sprint 2 wires the detector. Test: ruleIds match 1:1 with `ConclusionEngine.run` output. (DAB-005)
- [x] **S0-T15** Add `DevActionEngineTest` covering severity ordering + top-N + 1:1 ruleId mapping + empty input. (DAB-002, DAB-005)
- [x] **S0-T16** Re-run `ConclusionEngineSnapshotTest` from S0-T2 — must still pass byte-identical. (DAB-016)
- [x] **S0-T17** Detekt clean — run `./gradlew detekt`, fix any new findings. (DAB-015)

**Sprint 0 exit criteria**: all data classes shipped; `DevActionEngine.run` returns a brief with 1:1 conclusion mapping and `GameEngine.GENERIC` engine; +6-8 tests added; existing `ConclusionEngine` snapshot test still passes; detekt clean.

---

## Sprint 1 — Per-rule enrichment (~1.5d)

Goal: fill `CodeAreaCatalog` and `ActionStepsCatalog` with research-grade entries for all 8 production rules × {UNITY, UNREAL, COCOS2D, GENERIC}.

- [x] **S1-T1** Add `CodeAreaCatalogCompletenessTest` iterating `RuleRegistry.all` and asserting `CodeAreaCatalog.lookup(ruleId, engine)` returns non-empty for every (ruleId, engine ∈ {UNITY, UNREAL, COCOS2D, GENERIC}) combination. Will FAIL until catalog is filled — red phase. (DAB-003)
- [x] **S1-T2** Add `ActionStepsCatalogCompletenessTest` iterating `RuleRegistry.all` and asserting `ActionStepsCatalog.lookup(ruleId)` returns a list of size 1..5. Will FAIL until filled. (DAB-004)
- [x] **S1-T3** Fill `CodeAreaCatalog` for `stable-low-fps-low-cpu` × all 4 engines. Spanish tuteo-formal copy + validated `docLink`. (DAB-003, DAB-009)
- [x] **S1-T4** Fill `ActionStepsCatalog` for `stable-low-fps-low-cpu` with engine-specific + generic steps. (DAB-004, DAB-009)
- [x] **S1-T5** Fill `CodeAreaCatalog` + `ActionStepsCatalog` for `thermal-throttling`. (DAB-003, DAB-004, DAB-009)
- [x] **S1-T6** Fill catalogs for `memory-leak-suspect`. Specifically include Unity Memory Profiler / Unreal Insights Memory / Cocos2d ref-counting / Android Studio Memory Profiler doc-links. (DAB-003, DAB-004)
- [x] **S1-T7** Fill catalogs for `jank-with-good-avg`. Include frame-time histogram + GC pause + asset streaming hints per engine. (DAB-003, DAB-004)
- [x] **S1-T8** Fill catalogs for `fps-cap-suspect`. Include `Application.targetFrameRate` (Unity) / `t.MaxFPS` + `r.OneFrameThreadLag` (Unreal) / `Director::setAnimationInterval` (Cocos2d) / generic vsync hints. (DAB-003, DAB-004)
- [x] **S1-T9** Fill catalogs for `cpu-saturated`. Coroutine / async refactor + per-engine variants. (DAB-003, DAB-004)
- [x] **S1-T10** Fill catalogs for `ad-vs-game-fps-gap` (informational rule). Single action: "use filtered metric". (DAB-003, DAB-004)
- [x] **S1-T11** Fill catalogs for `loading-thermal-recovery` (informational rule). Single action: "preserve loading durations". (DAB-003, DAB-004)
- [x] **S1-T12** Verify Spanish tuteo-formal across all catalog strings — no `usted`, no `vosotros`, no English mixed into Spanish. Linter-style test: regex-grep for `\busted\b|\bvosotros\b` returns empty. (DAB-009)
- [x] **S1-T13** Verify every `docLink` is `https://`-prefixed and points to an official first-party doc (Unity / Unreal / Cocos2d / Android Studio / RenderDoc). Manual review checklist. (DAB-004)
- [x] **S1-T14** Re-run completeness tests from S1-T1 + S1-T2 — must now PASS (green phase). (DAB-003, DAB-004)
- [x] **S1-T15** Add per-rule enrichment tests: for each rule, build a `ConclusionInput` that triggers it, run `DevActionEngine.run`, assert resulting `DevActionItem` has non-empty `codeAreaHints` AND `suggestedActions`. 8 tests, one per rule. (DAB-005)
- [x] **S1-T16** Add `engineSpecific` filtering test: action step with `engineSpecific = UNITY` is filtered out when engine = UNREAL. (DAB-004)
- [x] **S1-T17** Re-run snapshot test from S0-T2 — `ConclusionEngine` output STILL byte-identical. (DAB-016)
- [x] **S1-T18** Detekt clean. (DAB-015)

**Sprint 1 exit criteria**: both catalogs filled for 8 rules × 4 engines; +15-20 tests added; Spanish copy linter passes; all doc-links validated; ConclusionEngine snapshot test still passes; detekt clean.

---

## Sprint 2 — Engine auto-detection (~0.5d)

Goal: wire `GameEngineDetector` and replace the Sprint 0 placeholder `GameEngine.GENERIC` with the real detection.

- [x] **S2-T1** Add `GameEngineDetectorTest` with five fixtures: Unity-only events, Unreal-only events, Cocos2d-only events, mixed (Unity wins by frequency), tied-frequency-Cocos2d-wins-by-recency. All initially FAIL — red phase. (DAB-006)
- [x] **S2-T2** Add a sixth `GameEngineDetectorTest` fixture: empty event list → returns `GameEngine.GENERIC`. (DAB-006)
- [x] **S2-T3** Add a seventh fixture: event list with only non-engine `sdkSource` values (e.g. only AdMob ads) → returns `GameEngine.GENERIC`. (DAB-006)
- [x] **S2-T4** Implement `GameEngineDetector.detect(events)` per design ADR-3 (frequency + recency tie-break). Tests from S2-T1..T3 must PASS (green). (DAB-006)
- [x] **S2-T5** Wire `DevActionEngine.run` to call `GameEngineDetector.detect(input.events)` and pass the result to `CodeAreaCatalog.lookup`. Replace the Sprint 0 placeholder. (DAB-005, DAB-006)
- [x] **S2-T6** Add an integration test: `ConclusionInput` with Unity LOADING events → `DevActionEngine.run` returns items whose `codeAreaHints` first entry has `engine = UNITY`. (DAB-005, DAB-006)
- [x] **S2-T7** Re-run all Sprint 0 + Sprint 1 tests — must all still pass. (DAB-016)
- [x] **S2-T8** Detekt clean. (DAB-015)

**Sprint 2 exit criteria**: `GameEngineDetector` implemented and wired; +5-7 tests added; full test suite green; detekt clean.

---

## Sprint 3 — Persistence + Report rendering (~1d)

Goal: persist the brief through SessionResult / SerializableEntry / HistoryEntry and render the HTML section at the top of the report.

### Persistence

- [x] **S3-T1** Extend `SessionResult` data class with `val devActionBrief: DevActionBrief = DevActionBrief()`. (DAB-007)
- [x] **S3-T2** Extend `SessionHistory.SerializableEntry` with `val devActionBrief: DevActionBrief = DevActionBrief()`. (DAB-007, DAB-010)
- [x] **S3-T3** Extend `SessionHistory.HistoryEntry` with `val devActionBrief: DevActionBrief = DevActionBrief()`. (DAB-007)
- [x] **S3-T4** Update `SessionHistory.toSerializable` to include `devActionBrief = devActionBrief`. (DAB-007)
- [x] **S3-T5** Update `SessionHistory.toHistoryEntry` to include `devActionBrief = devActionBrief`. (DAB-007)
- [x] **S3-T6** Wire `AppViewModel.startCapture` finalization to call `DevActionEngine.run(input)` and assign to `SessionResult.devActionBrief`. Co-locate next to the existing `conclusions = ConclusionEngine.run(input)` call. (DAB-007)
- [x] **S3-T7** Add `SessionHistoryDevActionBriefTest` covering: (a) round-trip with a non-empty brief, (b) field equality after toSerializable + toHistoryEntry, (c) defaulted empty brief on construction. (DAB-007)
- [x] **S3-T8** Add backward-compat test: load a captured pre-v4.6 `history.json` fixture (without `devActionBrief` field) and assert the resulting `HistoryEntry.devActionBrief.items.isEmpty()` AND `.topN == 5`. (DAB-010)
- [x] **S3-T9** Add `.gameperf` round-trip test via `SessionPack`: export a HistoryEntry with non-empty brief, import it, assert field equality. (DAB-007, DAB-010)

### Report rendering

- [x] **S3-T10** Add `ReportGenerator.sectionDevActionBrief(brief: DevActionBrief, engine: GameEngine): String` rendering the section. Initial scaffold (HTML structure + container) only. (DAB-008)
- [x] **S3-T11** Render per-item card with severity badge, title, evidence `<dl>`, diagnostic paragraph, code-area hints, suggested actions. Spanish tuteo-formal labels ("Crítico", "Atención", "Información", "Evidencia", "Dónde mirar", "Pasos sugeridos"). (DAB-008, DAB-009, DAB-011, DAB-013)
- [x] **S3-T12** Add CSS for `.dev-action-*` classes (see design). Appended to existing `<style>` block. (DAB-011)
- [x] **S3-T13** Add JS toggle handler for "Mostrar todo" / "Mostrar menos". Mark items beyond `topN` with `.dev-action-hidden`. (DAB-012)
- [x] **S3-T14** Conditional logcat block render: when `relatedLogcatLines.isNotEmpty()`, emit `<details class="dev-action-logcat">` with escaped excerpts. Otherwise omit. (DAB-014)
- [x] **S3-T15** Insert `sectionDevActionBrief(...)` call at TOP of body in `ReportGenerator.generate(...)`, BEFORE existing summary section. (DAB-008)
- [x] **S3-T16** Insert nav-link `<a href="#sec-dev-action-brief" class="nav-link">Acción Dev</a>` as FIRST nav entry when `brief.items.isNotEmpty()`. (DAB-008)
- [x] **S3-T17** When `brief.items.isEmpty()`, omit the section AND the nav-link entirely. (DAB-008 negative case)
- [x] **S3-T18** Add `ReportDevActionBriefTest` covering: (a) section appears at top with non-empty brief, (b) section + nav-link omitted with empty brief, (c) severity CSS classes correct, (d) toggle appears when items.size > topN, (e) evidence anchor link to existing metric sections, (f) logcat block omitted when relatedLogcatLines empty, (g) HTML escaping of logcat excerpts when present. (DAB-008, DAB-011, DAB-012, DAB-013, DAB-014)
- [x] **S3-T19** Verify HTML output: substring `<section id="sec-dev-action-brief"` appears BEFORE substring `<section id="sec-conclusions"` in the byte offset. Test asserts this ordering. (DAB-008)
- [x] **S3-T20** Re-run all Sprint 0 / 1 / 2 tests — must still pass. (DAB-016)
- [x] **S3-T21** Verify `ConclusionEngineSnapshotTest` from S0-T2 still byte-identical. (DAB-016)
- [x] **S3-T22** Detekt clean. (DAB-015)

**Sprint 3 exit criteria**: persistence round-trips brief through all three entry types; HTML section renders at top of report with all subsections; nav-link present; +8-10 tests added; backward compat verified with pre-v4.6 fixture; full test suite green; detekt clean.

---

## Sprint 4 — DEFER — Optional Ollama BYO-LLM narrative (~3-5d)

- [ ] **Sprint 4 — DEFERRED — Sprint 4 optional Ollama BYO-LLM, only on demand**

**Reserved capability slot DAB-017 — NOT implemented in this change.**

Tracked separately as future change `dev-action-brief-ollama-narrative`. Will require:
- Local Ollama HTTP client (`http://localhost:11434/api/generate`).
- New field on `DevActionItem` or `DevActionBrief` (`narrative: String?`).
- Opt-in UI toggle (disabled by default — local-first principle).
- Test fixtures using a `FakeOllamaClient`.
- Spec extension DAB-018..DAB-NNN for the narrative feature.

DO NOT implement in this change. Any work on Sprint 4 is a scope violation — file a new change instead.

---

## Cross-sprint invariants (verified at every sprint exit)

| Invariant | Verified by |
|---|---|
| `ConclusionEngine.run` byte-identical output | `ConclusionEngineSnapshotTest` (added S0-T2, re-run S1/S2/S3) |
| `#sec-conclusions` byte-identical HTML | Existing report rendering test continues to pass |
| Spanish tuteo-formal across new copy | S1-T12 regex linter test |
| Backward compat with pre-v4.6 `.gameperf` | S3-T8 fixture-based test |
| Detekt clean | `./gradlew detekt` exit code 0 each sprint |
| Catalog completeness (every rule × every engine has hints+actions) | S1-T1 + S1-T2 completeness tests |
| `DevActionItem.ruleId == Conclusion.ruleId` 1:1 mapping | S0-T15 + S1-T15 |
| Top-N = 5 default preserved | S0-T15 + S3-T18 |

## Effort summary

| Sprint | Effort | Tests added |
|---|---|---|
| 0 | ~1d | +6-8 |
| 1 | ~1.5d | +15-20 |
| 2 | ~0.5d | +5-7 |
| 3 | ~1d | +8-10 |
| **Total (0+1+2+3)** | **~4d TDD red→green** | **+34-45** |
| 4 (DEFERRED) | — | — |

## Risks (mirrored from proposal, monitored per sprint)

1. ConclusionEngine integration regression — Sprint 0 snapshot test gates this. Any Sprint 1/2/3 break causes immediate test failure.
2. Catalog accuracy — Sprint 1 doc-link manual review (S1-T13).
3. Spanish copy quality — Sprint 1 linter test (S1-T12).
4. Backward compat — Sprint 3 fixture test (S3-T8).
5. Sprint 4 scope-creep — explicit non-task list above.
6. Top-N=5 cognitive cap — "show all" toggle (S3-T13) plus severity ordering ensures CRITICAL never hidden.

## Next phase

→ Run `sdd-apply` Sprint 0 (or wait for any in-flight SDD change to land first). Topic key: `sdd/dev-action-brief/apply-progress`.


---

**SDD CHANGE COMPLETE** (3 of 4 sprints, Sprint 4 deferred).
Shipped 2026-05-12. Suite 1022 tests, 0 fail, detekt clean.
Commits: 933b46b / 02797db / 0ad856a / c12fcb0.
Archived 2026-05-12.

