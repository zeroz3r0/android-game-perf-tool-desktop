# Exploration — dev-action-brief

Topic key: `sdd/dev-action-brief/explore`
Date: 2026-05-12
Status: complete (planning-only — no source code yet)

## Origin (user request, verbatim 2026-05-12)

> "Al final, damos mucho reporte pero cuando lo pasamos a los devs, a veces no tienen nada con lo que interpretar esos datos. Necesitamos que el programa nos deje un mensaje para los devs, claro y conciso de las cosas que ha analizado la herramienta de performance para que los devs ataquen el problema real que reporta el programa y saber más detalladamente qué y dónde tocar."

**Translated requirement**: bridge the data-to-action gap. Reports today list raw metrics + heuristic conclusions; devs receive a wall of numbers without a guided "here's what to fix and where" interpretation layer. Tool must emit a concise dev-actionable brief that points to the real problem and **what and where to touch**.

## Strategic context

User established (2026-05-12) the "best-of compilation" philosophy: adopt the best from every competitor (PerfDog deep-dive obs #312, Apptim deep-dive obs #331, GameBench) while preserving local-first / no-SDK / no-cloud / open-methodology principles. This feature is the CENTERPIECE — it's what bridges the "data overload" of competitors into actionable dev guidance.

Closest analogue in the market: **Apptim's `POST /reports/ai-generate` endpoint** (obs #331 §C row 9). LLM-summarises captured metrics into an 8-section markdown narrative with per-metric finding + remediation tips. Apptim ships this as cloud LLM call. We reject the cloud path on local-first grounds — our equivalent must be rule-based first, with optional local Ollama deferred to Sprint 4+.

PerfDog has nothing equivalent — their "Smooth Index" is a single number, not a narrative. GameBench likewise stops at metric thresholds. The dev-actionable brief is positioning-differentiating, not just feature-parity.

## Phase 1 — Audit of existing infrastructure

### Files inventoried under `src/main/kotlin/com/gameperf/desktop/core/conclusions/`

```
ConclusionEngine.kt       — pure-function rule executor, sorts CRITICAL > WARNING > INFO then by ruleId
Rule.kt                   — Rule interface + Severity enum + Conclusion data class + ConclusionInput
RuleRegistry.kt           — single source of truth listing 8 rules
rules/
  StableLowFpsRule.kt        — WARNING — low FPS w/ CPU + thermal headroom → code bottleneck
  ThermalThrottlingRule.kt   — CRITICAL — hot device + FPS collapse → throttling
  MemoryGrowthRule.kt        — WARNING — linear regression slope ≥ 0.5 MB/s → leak suspect
  JankWithGoodAvgRule.kt     — WARNING — avg ≥ 50fps but ≥ 30 jank/min → "good number bad feel"
  Capped30FpsRule.kt         — INFO — p99 ~30 on high-tier device → FPS cap suspect
  CpuSaturationRule.kt       — CRITICAL — avgCpu ≥ 85% → CPU saturated
  AdVsGameFpsGapRule.kt      — INFO — raw vs filtered FPS delta ≥ 15% → filtering matters
  LoadingThermalRecoveryRule.kt — INFO — loading screens act as thermal cooldown
```

### What exists today

| Capability | Status | Where | Notes |
|---|---|---|---|
| Rule interface (id, severity, matches, render) | EXISTS | `Rule.kt:108-126` | Pure function contract, no I/O |
| 8 production rules | EXISTS | `rules/*.kt` | Spanish tuteo-formal copy |
| Severity ordering (CRITICAL > WARNING > INFO) | EXISTS | `Rule.kt:18-23` + `ConclusionEngine.kt:43-45` | ordinal-based |
| Deterministic rule registry | EXISTS | `RuleRegistry.kt:23-34` | Single source of truth |
| Conclusion data class (ruleId, severity, headline, recommendation) | EXISTS | `Rule.kt:39-44` | `@Serializable` |
| Conclusion render in HTML report | EXISTS | `ReportGenerator.kt:1148-1188` (`sectionConclusions`) | Anchor `#sec-conclusions`, severity-badge cards, intro paragraph |
| Persistence in SessionResult | EXISTS | `AppViewModel.kt:182` (`val conclusions: List<Conclusion>`) | Defaulted empty |
| Persistence in SerializableEntry | EXISTS | `SessionHistory.kt:187` (`val conclusions: List<Conclusion> = emptyList()`) | Backward-compat through default |
| Persistence in HistoryEntry | EXISTS | `SessionHistory.kt:248` | Round-trip safe |
| Thermal-availability guard (skip thermal-derived rules when `thermalAvailable=false`) | EXISTS | All thermal rules `if (!input.thermalAvailable) return false` | v4.4.1 discovery #274 |
| FPower persistence (mW/frame) | EXISTS | `SessionHistory.kt:202-208`, defaulted | v4.5.0 |
| SDK / engine signatures (Unity / Unreal / Cocos2d / 5 ad SDKs) | EXISTS | `SdkSignatureCatalog.kt` (285 lines) | `SdkSignature` w/ `logcatTags`, `openPatterns`, `closePatterns`, `activityClasses` — Unity / Unreal / Cocos2d signatures added v4.4.1 quickfix per audit obs #308 |
| Engine detector | **MISSING** | — | Catalog has signatures but no entry-point answering "which engine is running" |
| Code-area hints per issue | **MISSING** | — | No mapping from rule id → "look in main thread / shaders / scripts / pools" |
| Suggested actionable steps per issue | **PARTIAL** | Rule render's `recommendation` field | Recommendations exist but are PROSE-form; no structured "step 1, step 2..." catalog. No per-engine variants. No links to docs (RenderDoc, Unity Profiler, etc.). |
| Top-N filtering | **MISSING** | — | Engine returns ALL firing rules; no "show top 3 critical" UX cap |
| Confidence model | **MISSING** | — | Rules fire boolean; no HIGH / MEDIUM / LOW confidence per finding |
| Evidence anchoring (metric values + segment + raw metric link) | **PARTIAL** | Rule render interpolates metric values into prose | No structured "evidence: avgFps=12 / p1=8 / segment=full-session" field. Hard for devs to scan. |
| Logcat evidence anchoring | **MISSING** | — | Depends on `logcat-event-stream` change (not yet shipped) |
| AI-generated narrative | **MISSING** | — | DEFERRED — Sprint 4. Local Ollama BYO-LLM path (no cloud) |
| HTML rendering at TOP of report | **NO** | `ReportGenerator.kt:355` places `#sec-conclusions` in main body, AFTER summary cards | Devs scroll past raw data first → defeats the user goal of "devs see the brief FIRST" |

### Audit conclusions

The existing **ConclusionEngine is the data spine** — it already produces the boolean rule-fires that the new brief needs. It does NOT need to be replaced. The new `DevActionEngine` will **wrap** it: feed `ConclusionInput` → get `List<Conclusion>` → enrich each conclusion with `codeAreaHints` + `suggestedActions` from static catalogs → optionally attach logcat evidence (when `logcat-event-stream` ships) → cap top N by severity → emit `List<DevActionItem>` as `DevActionBrief`.

Existing `Conclusion.recommendation` field is too coarse (prose only, no per-engine variation, no doc links, no structured steps). Two options: (1) extend `Conclusion` itself with optional fields — risks breaking backward compat with persisted v4.4.x sessions; (2) keep `Conclusion` immutable and produce a richer `DevActionItem` parallel to it — additive, zero-risk. **DECISION: Option 2.** Conclusion stays for legacy rendering; DevActionItem is the new dev-facing shape.

## Phase 2 — Gap analysis

| Capability needed for "Dev Action Brief" | Exists today | If yes how | If no needs |
|---|---|---|---|
| Detect issue (e.g. low FPS p1) | YES | `ConclusionEngine.run(input)` → 8 rules | — |
| Severity classification | YES | `Severity` enum (CRITICAL/WARNING/INFO); engine sorts | Extend to 4-tier? `CRITICAL > HIGH > MEDIUM > LOW`. Decision: **reuse existing 3-tier** to avoid migrating persisted sessions. Map: CRITICAL→CRITICAL, WARNING→HIGH (most warnings are real actionable issues), INFO→MEDIUM. Add LOW for not-an-issue confirmations (e.g. AdVsGameFpsGapRule). Decision will be revisited if it surfaces UX confusion. **HARD DECISION: keep existing 3-tier (CRITICAL/WARNING/INFO) for v1. Severity field on `DevActionItem` mirrors `Conclusion.severity` 1:1, no remapping.** |
| Human-readable diagnostic (Spanish tuteo-formal) | YES | `Conclusion.headline + recommendation` | Reformat as structured: `diagnostic` (root cause hypothesis), separate from evidence |
| "Where to look in code" guidance | NO | — | New: `CodeAreaCatalog` → Map<ConclusionRuleId, PerEngineHints>. Per detected engine (Unity / Unreal / Cocos2d / generic): `codeAreaHints: List<CodeAreaHint>` with `area: String` (e.g. "Unity main thread Update loop"), `whyHere: String` (e.g. "stable-low-fps-low-cpu fires when CPU has headroom — single-threaded engine work is the usual suspect"), and optional `docLink: URL` |
| "What action to take" suggestions | PARTIAL | `Conclusion.recommendation` | New: `ActionStepsCatalog` → Map<ConclusionRuleId, List<ActionStep>>. Per step: `description` (Spanish tuteo-formal), `tool: String?` (Unity Profiler / RenderDoc / Android Studio Profiler), `docLink: URL?`. Per-engine variants where the action differs (Unity Profiler vs Unreal Stat Unit) |
| Logcat evidence anchoring | NO | — | DEFER — depends on `logcat-event-stream` change. Add OPTIONAL `relatedLogcatLines: List<LogcatLineRef>` on DevActionItem, defaulted empty for v1. Reserved capability slot DAB-014 |
| Priority ranking across issues | YES | `ConclusionEngine.run` sorts | Reuse |
| Top-N filtering (don't drown devs) | NO | — | New: `DevActionEngine.topN(items, n=5)` filters by severity, ties broken by ruleId. UI exposes "Show all" toggle |
| Engine auto-detection | PARTIAL | `SdkSignatureCatalog` has Unity/Unreal/Cocos2d signatures + uses them in `EventDetectorImpl` | New `GameEngineDetector.detect(events: List<DetectedEvent>): GameEngine` — derives primary engine from already-detected LOADING events |
| AI-generated narrative summary (optional) | NO | — | DEFER — Sprint 4. Local Ollama HTTP client. Off by default. Reserved capability slot DAB-017 |
| Rendering placement (top of report) | NO | `#sec-conclusions` placed in main body | Move new `#sec-dev-action-brief` to TOP of body, BEFORE summary cards. Existing `#sec-conclusions` stays for backward-compat / drill-down. |

## Phase 3 — Hard decisions baked in

These were considered + closed in this exploration so the proposal/spec don't need to revisit them:

| # | Question | Decision | Rationale |
|---|---|---|---|
| Q1 | Subpackage layout? | FLAT under `core/devactions/` (new namespace) | Mirrors `core/conclusions/` — single anti-duplication source of truth per CLAUDE.md |
| Q2 | Reuse ConclusionEngine? | YES — DevActionEngine wraps it, does NOT replace | Zero breaking changes to existing conclusion output. Enrichment layer only. |
| Q3 | Engine detection source? | Existing logcat patterns in `SdkSignatureCatalog` (Unity / Unreal / Cocos2d) + frequency-rank on detected events | Already captured during session, zero new dependencies. Falls back to GENERIC when no engine event detected. |
| Q4 | Top-N cap? | 5 by default, "show all" toggle | Apptim deep-dive shows their AI report uses 6-8 sections — cognitive load study. Plus our existing rule registry caps at 8 today. |
| Q5 | Persistence shape? | New `devActionBrief: List<DevActionItem>` field in SessionResult + SerializableEntry + HistoryEntry, defaulted empty | Mirrors the v4.4.x conclusions / v4.5.0 fpower pattern (defaulted = backward compat through `Json { ignoreUnknownKeys = true }`) |
| Q6 | Severity model? | Reuse existing `Severity` (CRITICAL / WARNING / INFO) | Same rules already emit it; remapping to 4-tier adds churn without value in v1 |
| Q7 | Spanish style? | Tuteo-formal (matches v4.4.1 thermal banners + existing rule recommendations) | Single house style across the report |
| Q8 | AI integration? | Sprint 4 DEFER, optional Ollama wrapper. Default = rule-based only | Local-first principle. Ollama is the only acceptable LLM path. Cloud is OUT. |
| Q9 | Code-area hints source? | STATIC catalog per rule, per detected engine. Future: dynamic logcat stack-trace correlation when `logcat-event-stream M.x` ships | Static is cheap, well-bounded, deterministic, testable |
| Q10 | Rendering placement? | NEW section `#sec-dev-action-brief` at TOP of report, BEFORE summary cards | User mandate: "devs see it FIRST". Existing `#sec-conclusions` stays in main body for backward-compat / drill-down. |

## Engine coverage (Sprint 1 baseline)

| Engine | Detection patterns | Code-area hints quality | Action steps quality |
|---|---|---|---|
| Unity | YES — `Loading scene`, `AsyncOperation`, tags `Unity` / `UnityEngine` | HIGH — public Unity Profiler docs, Frame Debugger, Scripting Reference well-known | HIGH |
| Unreal | YES — `LogStreaming: Loading`, `LoadingScreen Shown`, tags `UE4` / `Unreal` | HIGH — public Stat Unit / Stat Game / Insights docs | HIGH |
| Cocos2d | YES — `Director::replaceScene`, `CCDirector.replaceScene`, tags `cocos2d` / `Cocos2dx` / `CCDirector` | MEDIUM — smaller ecosystem, weaker public profiler docs but Frame Debugger + sched docs exist | MEDIUM |
| Godot | NO (not in catalog yet) | LOW | LOW |
| Native (Android NDK / SDL / custom) | NO | GENERIC fallback | GENERIC |

**Decision**: Sprint 1 ships **Unity + Unreal + Cocos2d + GENERIC** fallback. Godot deferred (no real-world demand surfaced yet — add when an issue raises it). Adding a new engine = adding one row in `CodeAreaCatalog` + one row in `ActionStepsCatalog` + one signature group in `SdkSignatureCatalog`. Extensible by design.

## Risks (preview — full list in proposal)

1. **ConclusionEngine integration regression** — DevActionEngine must be strictly additive. Existing `#sec-conclusions` rendering MUST keep producing identical output for the same input. Mitigation: snapshot tests of current rule outputs in Sprint 0 BEFORE adding DevActionEngine.
2. **Code-area hint accuracy** — Static catalog per engine requires research per engine. Initial coverage = Unity + Unreal + Cocos2d + generic. Mitigation: Spanish tuteo-formal copy reviewed against existing v4.4.1 thermal banner tone; doc-link URLs validated against official Unity / Unreal docs at write-time.
3. **Action steps utility** — "Profile with RenderDoc" only useful if dev knows RenderDoc. Mitigation: every action step includes an optional `docLink` to first-party docs.
4. **Backward compat** — SessionHistory schema grows. Defaulted fields prevent breakage but JSON file size grows ~10-30% for typical session (5 DevActionItems × ~500 chars each = ~2.5KB on top of current ~10-30KB).
5. **AI Ollama integration Sprint 4 scope-creep** — MUST NOT bleed into Sprints 0-3 scope.
6. **Top-N=5 cognitive cap** — may hide a sixth issue that matters. Mitigation: severity ordering ensures CRITICAL never gets hidden; "show all" toggle exposes the rest.

## Approach summary

Additive enrichment layer. ConclusionEngine stays. New `core/devactions/` namespace mirrors `core/conclusions/` shape. New `DevActionEngine` wraps `ConclusionEngine.run` and enriches each `Conclusion` with `codeAreaHints` (per detected engine) + `suggestedActions` (per rule, per engine) from two static catalogs. Engine detection reuses already-captured `DetectedEvent` stream. Top-5 cap by severity. Persisted in SessionResult + SerializableEntry + HistoryEntry. Renders at top of report HTML.

## Next phase

→ Run `sdd-propose` next. Topic key: `sdd/dev-action-brief/proposal`.
