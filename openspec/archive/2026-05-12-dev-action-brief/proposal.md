# Proposal — dev-action-brief

Topic key: `sdd/dev-action-brief/proposal`
Depends on: exploration `sdd/dev-action-brief/explore`
Doc anchors: docs/competitive-analysis-and-kpis.md (planned positioning § "best-of compilation", obs #312 PerfDog, obs #331 Apptim AI Report row 9)

## Intent

Bridge the **data-to-action gap**. The tool today produces solid raw metrics + a heuristic conclusions section, but devs receiving those reports often see "a wall of numbers" without a guided "here's what to fix and where" interpretation layer.

This change ships a **Dev Action Brief** — a structured, dev-actionable interpretation of session findings rendered at the **top** of the report. Each item carries:

1. **Severity** (reuses CRITICAL / WARNING / INFO from existing `Severity` enum)
2. **Title** — one-line dev-readable headline (Spanish tuteo-formal)
3. **Evidence** — structured: which metric, segment (full-session / filtered / specific event), exact values
4. **Diagnostic** — root-cause hypothesis (the "why")
5. **Code-area hints** — per detected engine (Unity / Unreal / Cocos2d / generic): WHERE in the codebase to look first
6. **Suggested actions** — 1-5 structured actionable steps with optional tool name + doc-link
7. **Related logcat lines** — OPTIONAL, defaulted empty (filled when `logcat-event-stream M.x` ships)
8. **Confidence** — HIGH / MEDIUM / LOW

The brief is **additive** to the existing `ConclusionEngine`. The wrapping `DevActionEngine` feeds `ConclusionInput` to `ConclusionEngine.run`, then enriches each `Conclusion` with per-engine code-area hints + suggested actions from two static catalogs (`CodeAreaCatalog`, `ActionStepsCatalog`). Top-5 by severity rendered visible by default with a "show all" toggle.

Positioning: this is the **CENTERPIECE** of the "best-of compilation" strategy (user mandate 2026-05-12). PerfDog has nothing equivalent (their "Smooth Index" is a number, not a narrative). Apptim's `POST /reports/ai-generate` (obs #331 §C row 9) is the only competitor approximation — they ship a cloud LLM call. We reject the cloud path on local-first grounds and ship a rule-based brief in v1, with optional local Ollama BYO-LLM deferred to Sprint 4+.

## Scope

### IN

**Sprint 0 — Data model + DevActionEngine foundation (~1d)**

- New `core/devactions/` namespace mirroring `core/conclusions/` shape
- `DevActionItem` `@Serializable` data class with: `ruleId: String`, `severity: Severity`, `title: String`, `evidence: DevActionEvidence`, `diagnostic: String`, `codeAreaHints: List<CodeAreaHint>`, `suggestedActions: List<ActionStep>`, `relatedLogcatLines: List<LogcatLineRef> = emptyList()`, `confidence: Confidence`
- `Confidence` enum: HIGH / MEDIUM / LOW
- `DevActionEvidence` data class: `metric: String`, `segment: String` (RAW / FILTERED / EVENT_WINDOW), `values: Map<String, String>` (e.g. `{"avgFps":"12", "p1":"8"}`)
- `CodeAreaHint` data class: `engine: GameEngine` (enum: UNITY / UNREAL / COCOS2D / GODOT / NATIVE / GENERIC), `area: String`, `whyHere: String`, `docLink: String?`
- `ActionStep` data class: `description: String`, `tool: String?`, `docLink: String?`, `engineSpecific: GameEngine?` (null = applies to all engines)
- `LogcatLineRef` data class: `timestampMs: Long`, `tag: String`, `excerpt: String` — reserved for `logcat-event-stream` future integration
- `DevActionBrief` data class wrapper: `items: List<DevActionItem>`, `topN: Int = 5` (default visible)
- `DevActionEngine` object: `run(input: ConclusionInput): DevActionBrief` — wraps `ConclusionEngine.run`, enriches each `Conclusion` with hints+actions from catalogs, attaches detected engine
- `GameEngineDetector.detect(events: List<DetectedEvent>): GameEngine` — derives primary engine from already-detected LOADING events (frequency rank, fallback GENERIC)
- Empty (Sprint 0) `CodeAreaCatalog` and `ActionStepsCatalog` placeholders — filled in Sprint 1

**Sprint 1 — Per-rule enrichment (~1.5d)**

- For each of the 8 existing `ConclusionRule`s, define entries in:
  - `core/devactions/CodeAreaCatalog.kt` — `Map<String /* conclusion ruleId */, Map<GameEngine, List<CodeAreaHint>>>`
  - `core/devactions/ActionStepsCatalog.kt` — `Map<String /* conclusion ruleId */, List<ActionStep>>` (per-engine variants via `ActionStep.engineSpecific`)
- Coverage matrix (Sprint 1 must cover all 8):
  - `stable-low-fps-low-cpu` → Unity main thread / Unreal Game Thread / Cocos2d update loop / GENERIC main thread
  - `thermal-throttling` → reduce drawcalls / shadow quality / FPS cap (engine-agnostic + engine variants)
  - `memory-leak-suspect` → Unity profiler / Unreal Insights Memory / Cocos2d ref counting / GENERIC Android Studio Profiler
  - `jank-with-good-avg` → frame time histogram + hot frames; engine variants for GC / asset streaming
  - `fps-cap-suspect` → `Application.targetFrameRate` (Unity) / `r.OneFrameThreadLag` + `t.MaxFPS` (Unreal) / `Director::setAnimationInterval` (Cocos2d) / generic vsync
  - `cpu-saturated` → coroutine / async refactor; per-engine variants
  - `ad-vs-game-fps-gap` → informational; suggested action = "use filtered metric"
  - `loading-thermal-recovery` → informational; suggested action = "preserve loading durations on optimisation pass"
- Spanish tuteo-formal copy for every catalog entry, doc-links validated against official Unity / Unreal / Cocos2d docs

**Sprint 2 — Engine auto-detection (~0.5d)**

- New `core/devactions/GameEngineDetector.kt`
- Reuses `SdkSignatureCatalog` (no new dependencies) — counts LOADING events per `sdkSource` (`Unity Engine` / `Unreal Engine` / `Cocos2d`)
- Frequency rank → primary engine; tie-break by latest occurrence
- Fallback: `GameEngine.GENERIC` when no engine event detected
- Public function: `detect(events: List<DetectedEvent>): GameEngine`
- Tests: synthetic event streams (Unity-only, Unreal-only, Cocos2d-only, mixed, empty)

**Sprint 3 — Persistence + Report rendering (~1d)**

- `SessionResult` gains `val devActionBrief: DevActionBrief = DevActionBrief(emptyList())` (defaulted = pre-v4.6 backward compat)
- `SessionHistory.SerializableEntry` gains `val devActionBrief: DevActionBrief = DevActionBrief(emptyList())` (`@Serializable`)
- `SessionHistory.HistoryEntry` gains matching field
- `SessionHistory.toSerializable` / `toHistoryEntry` round-trip the field
- `ReportGenerator` extends with `sectionDevActionBrief(brief: DevActionBrief, engine: GameEngine): String` rendering at the **TOP of body**, BEFORE summary cards
- New CSS: `.dev-action-brief`, `.dev-action-item`, `.dev-action-severity-{critical,warning,info}`, `.dev-action-evidence`, `.dev-action-hint`, `.dev-action-step`, `.dev-action-expand` (collapsible per item), `.dev-action-show-all` toggle
- Spanish tuteo-formal copy mirroring v4.4.1 thermal banner tone
- Nav link "Acción Dev" added to the top-of-page nav alongside "Conclusiones"

### OUT (FIRM — no scope creep)

- **NO cloud LLM** (direct contradiction of local-first positioning, obs #312 §Positioning impact)
- **NO IDE plugin** — out of category (we're a profiling tool, not an IDE extension)
- **NO automatic code changes** — guidance only, never automated edits (preserves dev autonomy)
- **NO blame attribution** — no "developer X introduced this"; perf is team responsibility
- **NO 4-tier severity migration** — reuse existing 3-tier `Severity` enum (CRITICAL / WARNING / INFO) to avoid persisted-session migration churn
- **NO Godot / native NDK engine catalog entries** — DEFERRED until real-world demand surfaces; GENERIC fallback covers them
- **NO Sprint 4 Ollama integration** — DEFERRED to a separate change (`dev-action-brief-ollama-narrative` reserved capability slot DAB-017)
- **NO replacement of existing `ConclusionEngine`** — strictly additive layer; `#sec-conclusions` rendering MUST stay byte-identical for the same input

## Hard decisions baked in (no escalation needed)

| # | Question | Decision | Why |
|---|---|---|---|
| Q1 | Subpackage | FLAT `core/devactions/` | Mirrors `core/conclusions/`. Anti-duplication. |
| Q2 | Reuse ConclusionEngine? | YES — wrapper, not replacement | Zero risk to existing output. |
| Q3 | Engine detection source | `SdkSignatureCatalog` LOADING events | Already captured. Zero new dependencies. |
| Q4 | Top-N cap | 5 visible by default, "show all" toggle | Apptim narrative report uses ≤8. Cognitive load. |
| Q5 | Persistence shape | New `devActionBrief: DevActionBrief = DevActionBrief(emptyList())` in SessionResult + SerializableEntry + HistoryEntry | Mirrors v4.4.x conclusions / v4.5.0 fpower pattern. Defaulted = backward compat. |
| Q6 | Severity model | Reuse 3-tier `Severity` enum | Existing rules already emit it. No churn. |
| Q7 | Spanish style | Tuteo-formal | Matches v4.4.1 thermal banners + existing recommendations. House style. |
| Q8 | AI integration | DEFER Sprint 4+. Rule-based only in v1. Local Ollama only path acceptable. | Local-first principle. |
| Q9 | Code-area hint source | STATIC catalog per rule, per engine | Cheap, deterministic, testable. |
| Q10 | Rendering placement | TOP of body, BEFORE summary cards | User mandate: "devs see it FIRST". |

## Sprints (DAG order — sequential)

| Sprint | Title | Effort | Output |
|---|---|---|---|
| 0 | Data model + DevActionEngine foundation | ~1d | Data classes + engine wrapper + empty catalogs |
| 1 | Per-rule enrichment | ~1.5d | Filled CodeAreaCatalog + ActionStepsCatalog for all 8 rules × 4 engines |
| 2 | Engine auto-detection | ~0.5d | `GameEngineDetector` reading LOADING events |
| 3 | Persistence + Report rendering | ~1d | SessionResult/SerializableEntry/HistoryEntry fields + HTML section + CSS + nav link |
| 4 | DEFERRED — Optional Ollama BYO-LLM narrative | ~3-5d | Spec'd as reserved DAB-017, not implemented in v1 |

**Total Sprints 0+1+2+3 = ~4d TDD red→green.** Sprint 4 deferred.

## Test counts target

| Sprint | New tests |
|---|---|
| 0 | +6-8 (data class shapes, severity ranking preserved, engine wrap delegates correctly, top-N filtering) |
| 1 | +15-20 (one test per rule × engine variants — 8 rules × 2-3 critical engine paths = ~20) |
| 2 | +5-7 (engine detector: Unity-only, Unreal-only, Cocos2d-only, mixed, empty, tie-break) |
| 3 | +8-10 (round-trip persist + render snapshot + backward compat with pre-v4.6 history.json + nav link presence) |
| 0-3 total | **+34-45 tests** |

## Risks

1. **ConclusionEngine integration regression** — DevActionEngine must be STRICTLY additive. Existing `#sec-conclusions` rendering MUST keep producing identical output for the same input. **Mitigation**: snapshot test of current rule outputs baselined in Sprint 0 BEFORE adding DevActionEngine.
2. **Code-area hint accuracy** — Per-engine static catalog requires research per engine. Initial coverage = Unity + Unreal + Cocos2d + generic. Godot / native NDK engines = future. **Mitigation**: catalog file is single-source-of-truth, easy to iterate per engine without touching rule code.
3. **Spanish tuteo-formal copy quality** — game devs in Spain/LATAM read different tones. **Mitigation**: catalog file structure allows easy iteration; baseline copy mirrors v4.4.1 thermal banner tone reviewed during that release.
4. **Action steps utility** — "Profile with RenderDoc" only useful if dev knows RenderDoc. **Mitigation**: every `ActionStep` includes optional `docLink` to first-party docs (Unity Profiler, Unreal Insights, Cocos2d profiler, Android Studio Profiler, RenderDoc). Validated at write-time.
5. **Backward compat schema growth** — SessionHistory JSON files grow ~10-30% (~2.5KB on top of ~10-30KB typical). **Mitigation**: defaulted-field pattern matches v4.4.x conclusions + v4.5.0 fpower precedents. `Json { ignoreUnknownKeys = true }` already in place.
6. **Sprint 4 Ollama scope-creep** — risk that BYO-LLM design discussion bleeds into Sprints 0-3. **Mitigation**: DAB-017 reserved as a separate spec requirement marked DEFER. Not implementable in this change. Future change `dev-action-brief-ollama-narrative` will own it.
7. **Top-N=5 hiding** — sixth issue might matter. **Mitigation**: severity ordering ensures CRITICAL never hides; "show all" toggle exposes the rest.
8. **`#sec-dev-action-brief` placement at TOP** — risk of pushing existing summary cards below the fold on small displays. **Mitigation**: section is collapsible per-item; in the empty-state (no rule fires) it renders a compact "no se detectaron problemas críticos" card occupying <100px vertical.

## Constraints

- ZERO breaking changes to existing `ConclusionEngine` output (DAB-016)
- Backward compat with v4.5.0+ `.gameperf` files (pre-v4.6 SessionHistory rows without `devActionBrief` field hydrate via default empty `DevActionBrief`) (DAB-010)
- ZERO new external dependencies for Sprints 0-3 (Sprint 4 may add an Ollama HTTP client, deferred) (DAB-017)
- Detekt clean (DAB-015)
- Spanish tuteo-formal consistent with v4.4.1 thermal banners (DAB-009)
- HTML section rendered at TOP of body before summary cards (DAB-008)
- `DevActionItem` ID stability — `ruleId` MUST equal the source `Conclusion.ruleId` 1:1 for cross-referencing (DAB-005)

## Next phase

→ Run `sdd-spec`, `sdd-design`, `sdd-tasks` (parallelisable) next.
