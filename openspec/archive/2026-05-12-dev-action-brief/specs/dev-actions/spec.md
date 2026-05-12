# Spec — dev-actions capability (dev-action-brief change)

Topic key: `sdd/dev-action-brief/spec`
Change: `dev-action-brief`
Capability: `dev-actions`
Scope: Android desktop. iOS not relevant (no iOS port yet).

Stable requirement IDs DAB-001..DAB-017. EARS keyword style + GIVEN/WHEN/THEN scenarios.

---

## DAB-001 — DevActionItem data class

The system **shall** expose a `DevActionItem` `@Serializable` data class carrying severity, title, evidence, diagnostic, code-area hints, suggested actions, optional logcat references, and confidence — the dev-facing payload that the UI / report render.

### Scenarios

GIVEN a fired rule with id `"stable-low-fps-low-cpu"`, severity `WARNING`, on a Unity game with `filtered.p50 = 18` against `targetFps = 60` and `filtered.avgCpu = 32%`
WHEN `DevActionEngine.run(input)` enriches the corresponding `Conclusion` into a `DevActionItem`
THEN the returned item **shall** carry `ruleId = "stable-low-fps-low-cpu"` (1:1 with the source conclusion)
AND `severity = WARNING`
AND `title` **shall** match the existing `Conclusion.headline` text (Spanish tuteo-formal)
AND `evidence.metric = "fps"`, `evidence.segment = "FILTERED"`, `evidence.values` **shall** contain at least `"p50" -> "18"`, `"target" -> "60"`, `"avgCpu" -> "32"`
AND `diagnostic` **shall** be non-empty Spanish tuteo-formal root-cause hypothesis
AND `codeAreaHints` **shall** be a non-empty list with at least one entry where `engine = UNITY`
AND `suggestedActions` **shall** be a non-empty list (1..5 entries)
AND `relatedLogcatLines` **shall** be an empty list (default — `logcat-event-stream` not yet integrated)
AND `confidence` **shall** be one of HIGH / MEDIUM / LOW.

GIVEN a `DevActionItem` round-tripped through `kotlinx.serialization` JSON
WHEN the JSON is decoded back
THEN all 8 fields **shall** be byte-equivalent to the source instance.

---

## DAB-002 — Severity ranking + Top-N cap

The system **shall** order `DevActionBrief.items` by `(severity ordinal ASC, ruleId ASC)` matching the existing `ConclusionEngine.run` ordering — CRITICAL before WARNING before INFO. The brief **shall** carry a `topN` integer (default 5) bounding how many items are visible by default; the report renderer **shall** expose a "show all" toggle when `items.size > topN`.

### Scenarios

GIVEN 7 firing rules: 1 CRITICAL, 4 WARNING, 2 INFO
WHEN `DevActionEngine.run(input)` returns `DevActionBrief`
THEN `brief.items.size == 7`
AND the first element **shall** be the CRITICAL one
AND elements 2..5 **shall** be the 4 WARNING items sorted by `ruleId` ASC
AND elements 6..7 **shall** be the 2 INFO items sorted by `ruleId` ASC
AND `brief.topN == 5`.

GIVEN `brief.items.size = 7` and `brief.topN = 5`
WHEN `ReportGenerator.sectionDevActionBrief(brief, engine)` renders
THEN the first 5 items **shall** render expanded by default
AND items 6..7 **shall** render hidden behind a "Mostrar todo" toggle button.

GIVEN `brief.items.size = 3`
WHEN the section renders
THEN no toggle **shall** appear (all items fit under topN cap).

---

## DAB-003 — CodeAreaCatalog per-engine hints

The system **shall** maintain `core/devactions/CodeAreaCatalog.kt` as the single source of truth mapping `conclusion ruleId → Map<GameEngine, List<CodeAreaHint>>`. Every rule in `RuleRegistry.all` **shall** have at least one entry per engine in {UNITY, UNREAL, COCOS2D, GENERIC}; entries for GODOT or NATIVE **may** fall through to GENERIC.

### Scenarios

GIVEN `RuleRegistry.all.map { it.id }` produces the 8 production rule ids
WHEN the catalog is loaded
THEN for every ruleId AND every engine in {UNITY, UNREAL, COCOS2D, GENERIC}, `CodeAreaCatalog.lookup(ruleId, engine)` **shall** return a non-empty `List<CodeAreaHint>`.

GIVEN ruleId `"cpu-saturated"` and engine `UNITY`
WHEN `CodeAreaCatalog.lookup("cpu-saturated", UNITY)` is called
THEN the result **shall** contain at least one hint with `area` mentioning `MonoBehaviour.Update` or `Coroutines` (Unity-specific terms)
AND `whyHere` **shall** explain why CPU saturation maps to that area
AND `docLink` **shall** be a `https://docs.unity3d.com/...` URL.

GIVEN ruleId `"cpu-saturated"` and engine `GODOT`
WHEN `CodeAreaCatalog.lookup("cpu-saturated", GODOT)` is called
THEN the result **shall** equal `CodeAreaCatalog.lookup("cpu-saturated", GENERIC)` (fall-through).

---

## DAB-004 — ActionStepsCatalog per-rule + per-engine actions

The system **shall** maintain `core/devactions/ActionStepsCatalog.kt` as the single source of truth mapping `conclusion ruleId → List<ActionStep>`. Each `ActionStep` carries `description` (Spanish tuteo-formal), optional `tool` (e.g. `"Unity Profiler"`, `"RenderDoc"`), optional `docLink`, and optional `engineSpecific: GameEngine?` (null = applies to all engines).

### Scenarios

GIVEN ruleId `"memory-leak-suspect"`
WHEN `ActionStepsCatalog.lookup("memory-leak-suspect")` is called for engine `UNITY`
THEN the result **shall** contain between 1 and 5 entries
AND at least one entry **shall** have `engineSpecific = UNITY` mentioning Unity Memory Profiler
AND at least one entry **shall** have `engineSpecific = null` (generic step like "review object pools")
AND every entry's `description` **shall** be Spanish tuteo-formal.

GIVEN `ActionStep.engineSpecific = UNITY` AND a session detected as `engine = UNREAL`
WHEN `DevActionEngine` builds the `suggestedActions` list for that item
THEN the UNITY-specific step **shall** be filtered out
AND UNREAL-specific + null (generic) steps **shall** be included.

GIVEN every action step
WHEN it has a `docLink` value
THEN the URL **shall** be `https://`-prefixed (no `http://`, no relative paths).

---

## DAB-005 — DevActionEngine wraps ConclusionEngine

The system **shall** expose `DevActionEngine.run(input: ConclusionInput): DevActionBrief` as the orchestrator that wraps `ConclusionEngine.run(input)`, enriches each emitted `Conclusion` with engine-aware code-area hints + suggested actions, and returns a `DevActionBrief`. `DevActionItem.ruleId` **shall** equal the source `Conclusion.ruleId` 1:1.

### Scenarios

GIVEN a `ConclusionInput` that makes 3 rules fire when passed to `ConclusionEngine.run`
WHEN `DevActionEngine.run(input)` is called
THEN `brief.items.size == 3`
AND `brief.items.map { it.ruleId }` **shall** equal `ConclusionEngine.run(input).map { it.ruleId }` element-for-element (same order, same ids).

GIVEN a `ConclusionInput` that makes ZERO rules fire
WHEN `DevActionEngine.run(input)` is called
THEN `brief.items` **shall** be empty
AND `brief.topN` **shall** equal `5` (default preserved).

GIVEN the same `ConclusionInput` passed to BOTH engines
WHEN `ConclusionEngine.run(input)` returns N conclusions
THEN `DevActionEngine.run(input).items` **shall** also return N items (1:1 enrichment, no filtering, no drop).

---

## DAB-006 — GameEngineDetector logcat-pattern-based

The system **shall** expose `GameEngineDetector.detect(events: List<DetectedEvent>): GameEngine` that returns the primary engine derived from already-captured LOADING events whose `sdkSource` matches `"Unity Engine"`, `"Unreal Engine"`, or `"Cocos2d"` (the existing `SdkSignatureCatalog` engine signatures). Returns `GameEngine.GENERIC` when no engine LOADING event is detected. Ties broken by **highest frequency**; on equal frequency, by **most recent** occurrence.

### Scenarios

GIVEN a session with 5 LOADING events from `sdkSource = "Unity Engine"` and 0 from other engines
WHEN `GameEngineDetector.detect(events)` is called
THEN the result **shall** equal `GameEngine.UNITY`.

GIVEN a session with 3 LOADING events from `"Unity Engine"` and 7 from `"Unreal Engine"`
WHEN the detector runs
THEN the result **shall** equal `GameEngine.UNREAL` (frequency winner).

GIVEN a session with 2 LOADING events from each of `"Unity Engine"` and `"Cocos2d"` (tied frequency)
AND the most recent Cocos2d event occurred AFTER the most recent Unity event
WHEN the detector runs
THEN the result **shall** equal `GameEngine.COCOS2D` (tie broken by recency).

GIVEN an empty event list OR one with no engine-tagged events
WHEN the detector runs
THEN the result **shall** equal `GameEngine.GENERIC`.

---

## DAB-007 — Persisted in SessionResult + SerializableEntry + HistoryEntry

The system **shall** persist the `DevActionBrief` in `SessionResult.devActionBrief`, `SessionHistory.SerializableEntry.devActionBrief`, and `SessionHistory.HistoryEntry.devActionBrief`. Each new field **shall** default to `DevActionBrief(items = emptyList(), topN = 5)` to preserve backward compatibility. The `toSerializable` / `toHistoryEntry` converters in `SessionHistory.kt` **shall** round-trip the field verbatim.

### Scenarios

GIVEN a `SessionResult` with a non-empty `devActionBrief` (3 items)
WHEN the orchestrator persists it through `SessionHistory.toSerializable` and back via `toHistoryEntry`
THEN the round-tripped `devActionBrief.items.size` **shall** equal 3
AND each `DevActionItem` **shall** be field-equivalent to the source.

GIVEN a v4.5.x `history.json` row (created before this change) with no `devActionBrief` field
WHEN `Json { ignoreUnknownKeys = true }.decodeFromString<List<SerializableEntry>>(...)` parses it
THEN the resulting `SerializableEntry.devActionBrief` **shall** equal `DevActionBrief(items = emptyList(), topN = 5)`.

GIVEN a write of a `SerializableEntry` with `devActionBrief = DevActionBrief(emptyList())`
WHEN re-read
THEN the value **shall** survive round-trip unchanged.

---

## DAB-008 — Report HTML rendering above raw metrics

The system **shall** render `<section id="sec-dev-action-brief">` at the TOP of the report body, BEFORE the existing summary cards and BEFORE `#sec-conclusions`. The nav-link `"Acción Dev"` **shall** be inserted as the first nav entry when the brief is non-empty.

### Scenarios

GIVEN a session with a non-empty `DevActionBrief` (≥1 item)
WHEN `ReportGenerator.generate(...)` produces HTML
THEN the substring `<section id="sec-dev-action-brief"` **shall** appear in the output
AND its byte-offset **shall** be LESS than the byte-offset of `<section id="sec-conclusions"`
AND its byte-offset **shall** be LESS than the byte-offset of the first summary card (`<section id="sec-summary"` if present)
AND a `<a href="#sec-dev-action-brief" class="nav-link">Acción Dev</a>` **shall** appear in the nav block.

GIVEN a session with an empty `DevActionBrief` (zero items)
WHEN the report renders
THEN `<section id="sec-dev-action-brief"` **shall** NOT appear in the output
AND the nav-link **shall** NOT appear.

---

## DAB-009 — Spanish tuteo-formal copy

The system **shall** render all Dev Action Brief copy in Spanish using the tuteo-formal tone established by v4.4.1 thermal banners and existing `Conclusion.recommendation` text. No second-person plural (vosotros), no usted-form, no English mixed into Spanish prose.

### Scenarios

GIVEN any string literal in `CodeAreaCatalog.kt` / `ActionStepsCatalog.kt` / `ReportGenerator.sectionDevActionBrief`
WHEN it ships in the report
THEN it **shall** use second-person singular tuteo (e.g. "revisa", "comprueba", "considera", "perfila")
AND it **shall NOT** contain `usted`, `ustedes`, `vosotros`, `vuestro`.

GIVEN the empty-state copy when no rules fire
WHEN the report renders that empty state
THEN it **shall** contain a Spanish tuteo-formal "no se detectaron problemas críticos" equivalent
AND it **shall NOT** be a stack trace or technical-jargon fallback.

---

## DAB-010 — Backward compat default empty for legacy .gameperf

The system **shall** load `.gameperf` files created by v4.5.0 (no `devActionBrief` field) without error and **shall** treat the missing field as `DevActionBrief(items = emptyList(), topN = 5)`.

### Scenarios

GIVEN a v4.5.0 `.gameperf` file on disk with a SerializableEntry payload that omits `devActionBrief`
WHEN `SessionPack.import(packFile, reportsDir)` is called
THEN it **shall** return a valid `HistoryEntry`
AND `historyEntry.devActionBrief.items` **shall** be empty
AND `historyEntry.devActionBrief.topN` **shall** equal `5`.

GIVEN a v4.6.0 `.gameperf` file written by this change
WHEN re-imported by the same v4.6.0
THEN the round-tripped `devActionBrief` **shall** be field-equivalent to the export.

---

## DAB-011 — Severity badges + CSS classes

The system **shall** render each `DevActionItem` with a severity badge using CSS classes `.dev-action-severity-critical`, `.dev-action-severity-warning`, `.dev-action-severity-info` mirroring the existing `.conclusion-critical` / `.conclusion-warning` / `.conclusion-info` style language for visual consistency.

### Scenarios

GIVEN a `DevActionItem` with `severity = CRITICAL`
WHEN it renders
THEN its outer div **shall** carry the class `dev-action-severity-critical`.

GIVEN a `DevActionItem` with `severity = WARNING`
WHEN it renders
THEN the class **shall** be `dev-action-severity-warning`.

GIVEN a `DevActionItem` with `severity = INFO`
WHEN it renders
THEN the class **shall** be `dev-action-severity-info`.

---

## DAB-012 — Expandable item + "show all" toggle

The system **shall** render each `DevActionItem` collapsible (header always visible, body — diagnostic + hints + actions — collapsed by default for items beyond the top `topN`). When `items.size > topN`, a "Mostrar todo" / "Mostrar menos" toggle button **shall** appear at the bottom of the section.

### Scenarios

GIVEN `brief.items.size == 5` AND `brief.topN == 5`
WHEN the section renders
THEN no "Mostrar todo" toggle **shall** appear in the HTML.

GIVEN `brief.items.size == 7` AND `brief.topN == 5`
WHEN the section renders
THEN a button with text `Mostrar todo` **shall** appear in the HTML
AND items 6..7 **shall** be marked with a CSS class indicating they are hidden by default (`.dev-action-hidden`).

GIVEN the toggle button is clicked client-side
WHEN the JS handler fires
THEN `.dev-action-hidden` items **shall** become visible
AND the button text **shall** flip to `Mostrar menos`.

---

## DAB-013 — Evidence section: data values + segment + raw metric link

The system **shall** render each `DevActionItem.evidence` as a structured `<dl>` listing `(metric label, value)` pairs with the segment (RAW / FILTERED / EVENT_WINDOW) clearly labelled. When the source metric is plotted elsewhere in the report (e.g. FPS line-chart, memory line-chart), the evidence block **shall** include an anchor link to that section.

### Scenarios

GIVEN a `DevActionItem` for `"stable-low-fps-low-cpu"` with `evidence.values = {"p50": "18", "target": "60", "avgCpu": "32"}`
WHEN it renders
THEN the HTML **shall** contain a `<dl>` element with `<dt>p50</dt><dd>18</dd>` (or equivalent label/value pairing)
AND `evidence.segment` **shall** render visibly as a chip with text `FILTERED` (or its Spanish-localised equivalent `Filtrada`).

GIVEN the source metric is `"fps"` AND the report has `<section id="sec-fps">`
WHEN the evidence block renders
THEN it **shall** contain `<a href="#sec-fps">` linking to the FPS chart section.

GIVEN the source metric is `"memory"` AND the report has no `<section id="sec-memory">`
WHEN the evidence block renders
THEN no broken anchor **shall** be emitted (only render the link when the target exists).

---

## DAB-014 — Optional logcat lines section (gated on logcat-event-stream M.x)

The system **shall** support an OPTIONAL per-item `<details class="dev-action-logcat">` block containing `relatedLogcatLines` excerpts. When `DevActionItem.relatedLogcatLines.isEmpty()` (the v1 default), the block **shall** be omitted entirely. The `logcat-event-stream` change is responsible for populating this list; this change reserves the capability slot and renders it correctly when present.

### Scenarios

GIVEN a `DevActionItem` with `relatedLogcatLines = emptyList()`
WHEN it renders
THEN the substring `dev-action-logcat` **shall** NOT appear in that item's HTML.

GIVEN a `DevActionItem` with `relatedLogcatLines` containing 3 entries (each with `timestampMs`, `tag`, `excerpt`)
WHEN it renders
THEN a `<details class="dev-action-logcat">` block **shall** appear
AND it **shall** contain 3 `<li>` entries
AND each `<li>` **shall** show the formatted timestamp (relative to capture start) + tag + excerpt
AND HTML entities in the excerpt **shall** be escaped (no XSS via logcat content).

---

## DAB-015 — Detekt clean

The system **shall** pass `./gradlew detekt` with zero violations after the change is applied. Magic numbers in catalog files **shall** be either constants with explanatory names or annotated `@Suppress` with a justification comment.

### Scenarios

GIVEN the change is fully implemented across Sprints 0-3
WHEN `./gradlew detekt` runs in CI
THEN exit code **shall** equal `0`
AND no new detekt findings **shall** appear vs the baseline before this change.

GIVEN a new source file in `core/devactions/` containing a numeric literal not declared `const val`
WHEN detekt runs with the project's `detekt.yml` configuration
THEN no `MagicNumber` finding **shall** be raised (constants or suppressions are mandatory).

---

## DAB-016 — No breaking change to ConclusionEngine

The system **shall** keep `ConclusionEngine.run(input: ConclusionInput): List<Conclusion>` byte-equivalent to the pre-change behavior for every input. The existing `<section id="sec-conclusions">` rendering **shall** also stay byte-equivalent for the same input.

### Scenarios

GIVEN any `ConclusionInput` that exists today (production sessions or fixtures)
WHEN `ConclusionEngine.run(input)` is called after this change is applied
THEN the returned `List<Conclusion>` **shall** be element-wise equal (same order, same field values) to the pre-change result.

GIVEN the same `ConclusionInput`
WHEN `ReportGenerator` renders `#sec-conclusions`
THEN the resulting HTML substring `<section id="sec-conclusions"...</section>` **shall** be byte-equivalent to the pre-change output (modulo nothing — strict byte match).

GIVEN a Sprint 0 snapshot test that captures the current `ConclusionEngine.run` output for a representative fixture
WHEN Sprints 1-3 are layered on top
THEN that snapshot test **shall** continue to pass without modification.

---

## DAB-017 — (Sprint 4 DEFER) Optional Ollama BYO-LLM integration — RESERVED, NOT IMPLEMENTED

The system **shall** reserve a capability slot for an optional local Ollama BYO-LLM narrative summary on top of the rule-based brief. This requirement is **DEFERRED** to a future change (proposed name: `dev-action-brief-ollama-narrative`). No implementation in v1.

### Scenarios

GIVEN this change `dev-action-brief` is fully implemented (Sprints 0-3)
WHEN any code path is examined
THEN no Ollama HTTP client **shall** be imported
AND no LLM API key **shall** be required or read from environment
AND no `narrative: String?` field **shall** be added to `DevActionItem` or `DevActionBrief` (reserved for the future change).

GIVEN a user-issued feature request for an AI narrative
WHEN triaged
THEN it **shall** be tracked against the reserved capability `dev-action-brief-ollama-narrative` (separate change), not added to this change's scope.
