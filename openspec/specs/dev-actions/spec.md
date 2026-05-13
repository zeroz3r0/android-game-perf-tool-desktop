# Spec — Dev Actions

This capability covers the **Dev Action Brief**: an engine-aware, rule-driven enrichment layer on top of `ConclusionEngine` that maps each fired conclusion into a structured developer-facing payload (evidence, diagnostic, code-area hints, suggested actions, confidence). The brief is persisted in `SessionResult` / `SerializableEntry` / `HistoryEntry` and rendered as the FIRST section of the HTML report. Android-only scope; iOS not relevant (no iOS port yet).

Conventions:
- Requirement IDs are stable and code-referenceable. They map directly to test names.
- Requirement statements use EARS keywords (SHALL, MUST, WHEN, WHILE, WHERE, IF/THEN).
- Scenarios use Given/When/Then for testability.
- User-facing strings are in Castilian Spanish formal **tuteo** per project convention.

> **Source delta:** `dev-action-brief` (archived 2026-05-12). All requirements in this capability were added by that change. First capability landing under `dev-actions` — no prior requirements existed.

---

## ADDED Requirements

### Requirement: DAB-001 — DevActionItem data class

The system SHALL expose a `DevActionItem` `@Serializable` data class carrying severity, title, evidence, diagnostic, code-area hints, suggested actions, optional logcat references, and confidence — the dev-facing payload that the UI and report render.

#### Scenario: WARNING item for stable-low-fps-low-cpu on Unity

- GIVEN a fired rule with id `"stable-low-fps-low-cpu"`, severity `WARNING`, on a Unity game with `filtered.p50 = 18` against `targetFps = 60` and `filtered.avgCpu = 32%`
- WHEN `DevActionEngine.run(input)` enriches the corresponding `Conclusion` into a `DevActionItem`
- THEN the returned item MUST carry `ruleId = "stable-low-fps-low-cpu"` (1:1 with the source conclusion)
- AND `severity = WARNING`
- AND `title` MUST match the existing `Conclusion.headline` text (Spanish tuteo-formal)
- AND `evidence.metric = "fps"`, `evidence.segment = "FILTERED"`, `evidence.values` MUST contain at least `"p50" -> "18"`, `"target" -> "60"`, `"avgCpu" -> "32"`
- AND `diagnostic` MUST be a non-empty Spanish tuteo-formal root-cause hypothesis
- AND `codeAreaHints` MUST be a non-empty list with at least one entry where `engine = UNITY`
- AND `suggestedActions` MUST be a non-empty list (1..5 entries)
- AND `relatedLogcatLines` MUST be an empty list (default — `logcat-event-stream` not yet integrated)
- AND `confidence` MUST be one of HIGH / MEDIUM / LOW

#### Scenario: full round-trip through kotlinx.serialization JSON

- GIVEN a `DevActionItem` round-tripped through `kotlinx.serialization` JSON
- WHEN the JSON is decoded back
- THEN all 8 fields MUST be byte-equivalent to the source instance

---

### Requirement: DAB-002 — Severity ranking and Top-N cap

The system SHALL order `DevActionBrief.items` by `(severity ordinal ASC, ruleId ASC)` matching the existing `ConclusionEngine.run` ordering — CRITICAL before WARNING before INFO. The brief SHALL carry a `topN` integer (default 5) bounding how many items are visible by default; the report renderer SHALL expose a "Mostrar todo" toggle when `items.size > topN`.

#### Scenario: 7 firing rules ordered by severity then ruleId

- GIVEN 7 firing rules: 1 CRITICAL, 4 WARNING, 2 INFO
- WHEN `DevActionEngine.run(input)` returns `DevActionBrief`
- THEN `brief.items.size == 7`
- AND the first element MUST be the CRITICAL one
- AND elements 2..5 MUST be the 4 WARNING items sorted by `ruleId` ASC
- AND elements 6..7 MUST be the 2 INFO items sorted by `ruleId` ASC
- AND `brief.topN == 5`

#### Scenario: items beyond topN render hidden with toggle

- GIVEN `brief.items.size = 7` and `brief.topN = 5`
- WHEN `ReportGenerator.sectionDevActionBrief(brief, engine)` renders
- THEN the first 5 items MUST render expanded by default
- AND items 6..7 MUST render hidden behind a "Mostrar todo" toggle button

#### Scenario: no toggle when items fit under topN

- GIVEN `brief.items.size = 3`
- WHEN the section renders
- THEN no toggle MUST appear (all items fit under topN cap)

---

### Requirement: DAB-003 — CodeAreaCatalog per-engine hints

The system SHALL maintain `core/devactions/CodeAreaCatalog.kt` as the single source of truth mapping `conclusion ruleId → Map<GameEngine, List<CodeAreaHint>>`. Every rule in `RuleRegistry.all` SHALL have at least one entry per engine in {UNITY, UNREAL, COCOS2D, GENERIC}; entries for GODOT or NATIVE MAY fall through to GENERIC.

#### Scenario: every rule has at least one hint per supported engine

- GIVEN `RuleRegistry.all.map { it.id }` produces the 8 production rule ids
- WHEN the catalog is loaded
- THEN for every ruleId AND every engine in {UNITY, UNREAL, COCOS2D, GENERIC}, `CodeAreaCatalog.lookup(ruleId, engine)` MUST return a non-empty `List<CodeAreaHint>`

#### Scenario: Unity-specific hint for cpu-saturated cites Unity API

- GIVEN ruleId `"cpu-saturated"` and engine `UNITY`
- WHEN `CodeAreaCatalog.lookup("cpu-saturated", UNITY)` is called
- THEN the result MUST contain at least one hint with `area` mentioning `MonoBehaviour.Update` or `Coroutines` (Unity-specific terms)
- AND `whyHere` MUST explain why CPU saturation maps to that area
- AND `docLink` MUST be a `https://docs.unity3d.com/...` URL

#### Scenario: GODOT engine falls through to GENERIC

- GIVEN ruleId `"cpu-saturated"` and engine `GODOT`
- WHEN `CodeAreaCatalog.lookup("cpu-saturated", GODOT)` is called
- THEN the result MUST equal `CodeAreaCatalog.lookup("cpu-saturated", GENERIC)` (fall-through)

---

### Requirement: DAB-004 — ActionStepsCatalog per-rule and per-engine actions

The system SHALL maintain `core/devactions/ActionStepsCatalog.kt` as the single source of truth mapping `conclusion ruleId → List<ActionStep>`. Each `ActionStep` carries `description` (Spanish tuteo-formal), optional `tool` (e.g. `"Unity Profiler"`, `"RenderDoc"`), optional `docLink`, and optional `engineSpecific: GameEngine?` (null = applies to all engines).

#### Scenario: memory-leak-suspect on Unity returns 1..5 mixed steps

- GIVEN ruleId `"memory-leak-suspect"`
- WHEN `ActionStepsCatalog.lookup("memory-leak-suspect")` is called for engine `UNITY`
- THEN the result MUST contain between 1 and 5 entries
- AND at least one entry MUST have `engineSpecific = UNITY` mentioning Unity Memory Profiler
- AND at least one entry MUST have `engineSpecific = null` (generic step like "review object pools")
- AND every entry's `description` MUST be Spanish tuteo-formal

#### Scenario: engineSpecific filtering when mismatched engine detected

- GIVEN `ActionStep.engineSpecific = UNITY` AND a session detected as `engine = UNREAL`
- WHEN `DevActionEngine` builds the `suggestedActions` list for that item
- THEN the UNITY-specific step MUST be filtered out
- AND UNREAL-specific + null (generic) steps MUST be included

#### Scenario: every docLink is https-prefixed

- GIVEN every action step
- WHEN it has a `docLink` value
- THEN the URL MUST be `https://`-prefixed (no `http://`, no relative paths)

---

### Requirement: DAB-005 — DevActionEngine wraps ConclusionEngine

The system SHALL expose `DevActionEngine.run(input: ConclusionInput): DevActionBrief` as the orchestrator that wraps `ConclusionEngine.run(input)`, enriches each emitted `Conclusion` with engine-aware code-area hints + suggested actions, and returns a `DevActionBrief`. `DevActionItem.ruleId` SHALL equal the source `Conclusion.ruleId` 1:1.

#### Scenario: 1:1 mapping of fired rules to brief items

- GIVEN a `ConclusionInput` that makes 3 rules fire when passed to `ConclusionEngine.run`
- WHEN `DevActionEngine.run(input)` is called
- THEN `brief.items.size == 3`
- AND `brief.items.map { it.ruleId }` MUST equal `ConclusionEngine.run(input).map { it.ruleId }` element-for-element (same order, same ids)

#### Scenario: empty input returns empty brief with default topN

- GIVEN a `ConclusionInput` that makes ZERO rules fire
- WHEN `DevActionEngine.run(input)` is called
- THEN `brief.items` MUST be empty
- AND `brief.topN` MUST equal `5` (default preserved)

#### Scenario: no filtering or dropping of conclusions

- GIVEN the same `ConclusionInput` passed to BOTH engines
- WHEN `ConclusionEngine.run(input)` returns N conclusions
- THEN `DevActionEngine.run(input).items` MUST also return N items (1:1 enrichment, no filtering, no drop)

---

### Requirement: DAB-006 — GameEngineDetector logcat-pattern-based

The system SHALL expose `GameEngineDetector.detect(events: List<DetectedEvent>): GameEngine` that returns the primary engine derived from already-captured LOADING events whose `sdkSource` matches `"Unity Engine"`, `"Unreal Engine"`, or `"Cocos2d"` (the existing `SdkSignatureCatalog` engine signatures). Returns `GameEngine.GENERIC` when no engine LOADING event is detected. Ties broken by **highest frequency**; on equal frequency, by **most recent** occurrence.

#### Scenario: Unity-only events return UNITY

- GIVEN a session with 5 LOADING events from `sdkSource = "Unity Engine"` and 0 from other engines
- WHEN `GameEngineDetector.detect(events)` is called
- THEN the result MUST equal `GameEngine.UNITY`

#### Scenario: frequency tie-break with mixed engines

- GIVEN a session with 3 LOADING events from `"Unity Engine"` and 7 from `"Unreal Engine"`
- WHEN the detector runs
- THEN the result MUST equal `GameEngine.UNREAL` (frequency winner)

#### Scenario: tied frequency broken by recency

- GIVEN a session with 2 LOADING events from each of `"Unity Engine"` and `"Cocos2d"` (tied frequency)
- AND the most recent Cocos2d event occurred AFTER the most recent Unity event
- WHEN the detector runs
- THEN the result MUST equal `GameEngine.COCOS2D` (tie broken by recency)

#### Scenario: empty or non-engine events return GENERIC

- GIVEN an empty event list OR one with no engine-tagged events
- WHEN the detector runs
- THEN the result MUST equal `GameEngine.GENERIC`

---

### Requirement: DAB-007 — Persisted in SessionResult, SerializableEntry, HistoryEntry

The system SHALL persist the `DevActionBrief` in `SessionResult.devActionBrief`, `SessionHistory.SerializableEntry.devActionBrief`, and `SessionHistory.HistoryEntry.devActionBrief`. Each new field SHALL default to `DevActionBrief(items = emptyList(), topN = 5)` (or to `null` where the implementation models the empty-vs-never-computed distinction) to preserve backward compatibility. The `toSerializable` / `toHistoryEntry` converters in `SessionHistory.kt` SHALL round-trip the field verbatim.

#### Scenario: round-trip with 3 items through serializable + history

- GIVEN a `SessionResult` with a non-empty `devActionBrief` (3 items)
- WHEN the orchestrator persists it through `SessionHistory.toSerializable` and back via `toHistoryEntry`
- THEN the round-tripped `devActionBrief.items.size` MUST equal 3
- AND each `DevActionItem` MUST be field-equivalent to the source

#### Scenario: legacy history.json without devActionBrief decodes cleanly

- GIVEN a v4.5.x `history.json` row (created before this change) with no `devActionBrief` field
- WHEN `Json { ignoreUnknownKeys = true }.decodeFromString<List<SerializableEntry>>(...)` parses it
- THEN the resulting `SerializableEntry.devActionBrief` MUST equal `DevActionBrief(items = emptyList(), topN = 5)` or `null` per implementation choice — and downstream rendering MUST treat both equivalently as "empty brief"

#### Scenario: empty brief survives round-trip

- GIVEN a write of a `SerializableEntry` with `devActionBrief = DevActionBrief(emptyList())`
- WHEN re-read
- THEN the value MUST survive round-trip unchanged

---

### Requirement: DAB-008 — Report HTML rendering above raw metrics

The system SHALL render `<section id="sec-dev-action-brief">` at the TOP of the report body, BEFORE the existing summary cards and BEFORE `<section id="sec-conclusions">`. The nav-link `"Acción Dev"` SHALL be inserted as the first nav entry when the brief is non-empty.

#### Scenario: section appears first when brief non-empty

- GIVEN a session with a non-empty `DevActionBrief` (≥1 item)
- WHEN `ReportGenerator.generate(...)` produces HTML
- THEN the substring `<section id="sec-dev-action-brief"` MUST appear in the output
- AND its byte-offset MUST be LESS than the byte-offset of `<section id="sec-conclusions"`
- AND its byte-offset MUST be LESS than the byte-offset of the first summary card (`<section id="sec-summary"` if present)
- AND a `<a href="#sec-dev-action-brief" class="nav-link">Acción Dev</a>` MUST appear in the nav block

#### Scenario: section and nav-link omitted when brief empty

- GIVEN a session with an empty `DevActionBrief` (zero items)
- WHEN the report renders
- THEN `<section id="sec-dev-action-brief"` MUST NOT appear in the output
- AND the nav-link MUST NOT appear

---

### Requirement: DAB-009 — Spanish tuteo-formal copy

The system SHALL render all Dev Action Brief copy in Spanish using the tuteo-formal tone established by v4.4.1 thermal banners and existing `Conclusion.recommendation` text. No second-person plural (`vosotros`), no `usted` form, no English mixed into Spanish prose.

#### Scenario: catalog strings use tuteo and avoid usted/vosotros

- GIVEN any string literal in `CodeAreaCatalog.kt` / `ActionStepsCatalog.kt` / `ReportGenerator.sectionDevActionBrief`
- WHEN it ships in the report
- THEN it MUST use second-person singular tuteo (e.g. `"revisa"`, `"comprueba"`, `"considera"`, `"perfila"`)
- AND it MUST NOT contain `usted`, `ustedes`, `vosotros`, `vuestro`

#### Scenario: empty-state copy is Spanish tuteo-formal

- GIVEN the empty-state copy when no rules fire
- WHEN the report renders that empty state
- THEN it MUST contain a Spanish tuteo-formal "no se detectaron problemas críticos" equivalent
- AND it MUST NOT be a stack trace or technical-jargon fallback

---

### Requirement: DAB-010 — Backward compat default empty for legacy .gameperf

The system SHALL load `.gameperf` files created by v4.5.0 (no `devActionBrief` field) without error and SHALL treat the missing field as `DevActionBrief(items = emptyList(), topN = 5)`.

#### Scenario: v4.5.0 .gameperf without devActionBrief loads as empty

- GIVEN a v4.5.0 `.gameperf` file on disk with a SerializableEntry payload that omits `devActionBrief`
- WHEN `SessionPack.import(packFile, reportsDir)` is called
- THEN it MUST return a valid `HistoryEntry`
- AND `historyEntry.devActionBrief.items` MUST be empty (or `historyEntry.devActionBrief == null` per implementation, rendered equivalently to "no findings")
- AND when non-null, `historyEntry.devActionBrief.topN` MUST equal `5`

#### Scenario: v4.6 .gameperf round-trips losslessly

- GIVEN a v4.6.0 `.gameperf` file written by this change
- WHEN re-imported by the same v4.6.0
- THEN the round-tripped `devActionBrief` MUST be field-equivalent to the export

---

### Requirement: DAB-011 — Severity badges and CSS classes

The system SHALL render each `DevActionItem` with a severity badge using CSS classes `.dev-action-severity-critical`, `.dev-action-severity-warning`, `.dev-action-severity-info` mirroring the existing `.conclusion-critical` / `.conclusion-warning` / `.conclusion-info` style language for visual consistency.

#### Scenario: CRITICAL item carries dev-action-severity-critical class

- GIVEN a `DevActionItem` with `severity = CRITICAL`
- WHEN it renders
- THEN its outer div MUST carry the class `dev-action-severity-critical`

#### Scenario: WARNING item carries dev-action-severity-warning class

- GIVEN a `DevActionItem` with `severity = WARNING`
- WHEN it renders
- THEN the class MUST be `dev-action-severity-warning`

#### Scenario: INFO item carries dev-action-severity-info class

- GIVEN a `DevActionItem` with `severity = INFO`
- WHEN it renders
- THEN the class MUST be `dev-action-severity-info`

---

### Requirement: DAB-012 — Expandable item and "Mostrar todo" toggle

The system SHALL render each `DevActionItem` collapsible (header always visible, body — diagnostic + hints + actions — collapsed by default for items beyond the top `topN`). When `items.size > topN`, a "Mostrar todo" / "Mostrar menos" toggle button SHALL appear at the bottom of the section.

#### Scenario: no toggle when items fit topN

- GIVEN `brief.items.size == 5` AND `brief.topN == 5`
- WHEN the section renders
- THEN no "Mostrar todo" toggle MUST appear in the HTML

#### Scenario: toggle and hidden class present when items exceed topN

- GIVEN `brief.items.size == 7` AND `brief.topN == 5`
- WHEN the section renders
- THEN a button with text `Mostrar todo` MUST appear in the HTML
- AND items 6..7 MUST be marked with a CSS class indicating they are hidden by default (`.dev-action-hidden`)

#### Scenario: toggle reveals hidden items and flips its label

- GIVEN the toggle button is clicked client-side
- WHEN the JS handler fires
- THEN `.dev-action-hidden` items MUST become visible
- AND the button text MUST flip to `Mostrar menos`

---

### Requirement: DAB-013 — Evidence section with data values, segment, and raw metric link

The system SHALL render each `DevActionItem.evidence` as a structured `<dl>` listing `(metric label, value)` pairs with the segment (RAW / FILTERED / EVENT_WINDOW) clearly labelled. When the source metric is plotted elsewhere in the report (e.g. FPS line-chart, memory line-chart), the evidence block SHALL include an anchor link to that section.

#### Scenario: evidence renders dl with labelled segment chip

- GIVEN a `DevActionItem` for `"stable-low-fps-low-cpu"` with `evidence.values = {"p50": "18", "target": "60", "avgCpu": "32"}`
- WHEN it renders
- THEN the HTML MUST contain a `<dl>` element with `<dt>p50</dt><dd>18</dd>` (or equivalent label/value pairing)
- AND `evidence.segment` MUST render visibly as a chip with text `FILTERED` (or its Spanish-localised equivalent `Filtrada`)

#### Scenario: anchor link to existing metric section

- GIVEN the source metric is `"fps"` AND the report has `<section id="sec-fps">`
- WHEN the evidence block renders
- THEN it MUST contain `<a href="#sec-fps">` linking to the FPS chart section

#### Scenario: no broken anchor when target section absent

- GIVEN the source metric is `"memory"` AND the report has no `<section id="sec-memory">`
- WHEN the evidence block renders
- THEN no broken anchor MUST be emitted (only render the link when the target exists)

---

### Requirement: DAB-014 — Optional logcat lines section (gated on logcat-event-stream)

The system SHALL support an OPTIONAL per-item `<details class="dev-action-logcat">` block containing `relatedLogcatLines` excerpts. When `DevActionItem.relatedLogcatLines.isEmpty()` (the v1 default), the block SHALL be omitted entirely. The `logcat-event-stream` change is responsible for populating this list; this change reserves the capability slot and renders it correctly when present.

#### Scenario: logcat block omitted when list empty

- GIVEN a `DevActionItem` with `relatedLogcatLines = emptyList()`
- WHEN it renders
- THEN the substring `dev-action-logcat` MUST NOT appear in that item's HTML

#### Scenario: logcat block rendered with escaped excerpts when populated

- GIVEN a `DevActionItem` with `relatedLogcatLines` containing 3 entries (each with `timestampMs`, `tag`, `excerpt`)
- WHEN it renders
- THEN a `<details class="dev-action-logcat">` block MUST appear
- AND it MUST contain 3 `<li>` entries
- AND each `<li>` MUST show the formatted timestamp (relative to capture start) + tag + excerpt
- AND HTML entities in the excerpt MUST be escaped (no XSS via logcat content)

---

### Requirement: DAB-015 — Detekt clean

The system SHALL pass `./gradlew detekt` with zero violations after the change is applied. Magic numbers in catalog files SHALL be either constants with explanatory names or annotated `@Suppress` with a justification comment.

#### Scenario: detekt exits clean post-change

- GIVEN the change is fully implemented across Sprints 0-3
- WHEN `./gradlew detekt` runs in CI
- THEN exit code MUST equal `0`
- AND no new detekt findings MUST appear vs the baseline before this change

#### Scenario: no MagicNumber findings in catalog source

- GIVEN a new source file in `core/devactions/` containing a numeric literal not declared `const val`
- WHEN detekt runs with the project's `detekt.yml` configuration
- THEN no `MagicNumber` finding MUST be raised (constants or suppressions are mandatory)

---

### Requirement: DAB-016 — No breaking change to ConclusionEngine

The system SHALL keep `ConclusionEngine.run(input: ConclusionInput): List<Conclusion>` byte-equivalent to the pre-change behavior for every input. The existing `<section id="sec-conclusions">` rendering SHALL also stay byte-equivalent for the same input.

#### Scenario: ConclusionEngine.run output unchanged element-wise

- GIVEN any `ConclusionInput` that exists today (production sessions or fixtures)
- WHEN `ConclusionEngine.run(input)` is called after this change is applied
- THEN the returned `List<Conclusion>` MUST be element-wise equal (same order, same field values) to the pre-change result

#### Scenario: sec-conclusions HTML byte-equivalent

- GIVEN the same `ConclusionInput`
- WHEN `ReportGenerator` renders `#sec-conclusions`
- THEN the resulting HTML substring `<section id="sec-conclusions"...</section>` MUST be byte-equivalent to the pre-change output (strict byte match)

#### Scenario: Sprint 0 snapshot test continues passing through all sprints

- GIVEN a Sprint 0 snapshot test that captures the current `ConclusionEngine.run` output for a representative fixture
- WHEN Sprints 1-3 are layered on top
- THEN that snapshot test MUST continue to pass without modification

---

### Requirement: DAB-017 — Optional Ollama BYO-LLM narrative — DEFERRED

> **Status:** `[ ] DEFERRED — Sprint 4 optional Ollama BYO-LLM, only on demand`. Reserved capability slot. NOT implemented in this change. Tracked separately as a future change (proposed name: `dev-action-brief-ollama-narrative`).

The system SHALL reserve a capability slot for an optional local Ollama BYO-LLM narrative summary on top of the rule-based brief. This requirement is DEFERRED to a future change. No implementation in v1.

#### Scenario: no Ollama client or LLM key in v1 code paths

- GIVEN this change `dev-action-brief` is fully implemented (Sprints 0-3)
- WHEN any code path is examined
- THEN no Ollama HTTP client MUST be imported
- AND no LLM API key MUST be required or read from environment
- AND no `narrative: String?` field MUST be added to `DevActionItem` or `DevActionBrief` (reserved for the future change)

#### Scenario: AI narrative requests routed to separate change

- GIVEN a user-issued feature request for an AI narrative
- WHEN triaged
- THEN it MUST be tracked against the reserved capability `dev-action-brief-ollama-narrative` (separate change), not added to this change's scope
