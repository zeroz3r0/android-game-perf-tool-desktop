# Design — dev-action-brief

Topic key: `sdd/dev-action-brief/design`
Depends on: proposal `sdd/dev-action-brief/proposal`, spec `sdd/dev-action-brief/spec`

## Architecture summary

```
                  ┌─────────────────────────────────────────┐
                  │       AppViewModel.startCapture          │
                  │ ┌─────────────────────────────────────┐ │
                  │ │ existing finalization pipeline       │ │
                  │ │   → buildConclusionInput()           │ │
                  │ │   → ConclusionEngine.run(input)      │ │
                  │ └────────────────┬────────────────────┘ │
                  │                  │ List<Conclusion>       │
                  │                  ▼                        │
                  │   ┌────────────────────────────────┐     │
                  │   │ NEW: DevActionEngine.run(input) │     │
                  │   │  ─ wraps ConclusionEngine       │     │
                  │   │  ─ detects engine via events    │     │
                  │   │  ─ enriches w/ catalogs         │     │
                  │   │  ─ caps top-N by severity       │     │
                  │   └──────────┬─────────────────────┘     │
                  │              │ DevActionBrief             │
                  │              ▼                            │
                  │     SessionResult.devActionBrief          │
                  └──────────────┬──────────────────────────┘
                                 │
                                 ▼
                ┌─────────────────────────────────────┐
                │       SessionHistory persistence     │
                │   SerializableEntry.devActionBrief   │
                │   HistoryEntry.devActionBrief        │
                └───────────────┬─────────────────────┘
                                │
                                ▼
                ┌──────────────────────────────────────┐
                │     ReportGenerator.generate          │
                │   sectionDevActionBrief(brief, engine) │
                │   → rendered at TOP of body            │
                │   (BEFORE existing #sec-conclusions)   │
                └──────────────────────────────────────┘
```

The design is **strictly additive**. `ConclusionEngine` is unchanged. `DevActionEngine` is a NEW orchestrator that depends on `ConclusionEngine` (downstream consumer). Two static catalogs (`CodeAreaCatalog`, `ActionStepsCatalog`) provide the per-rule × per-engine enrichment data. `GameEngineDetector` derives the primary engine from already-captured `DetectedEvent` LOADING records.

## ADRs (Architecture Decision Records)

### ADR-1 — Wrap, don't replace, ConclusionEngine

**Decision**: `DevActionEngine` consumes the output of `ConclusionEngine.run` and enriches each `Conclusion` into a `DevActionItem`. `ConclusionEngine` stays unchanged.

**Alternatives considered**:
1. Extend `Conclusion` with new fields (`codeAreaHints`, `suggestedActions`, etc.) — REJECTED. Breaking change risk for v4.4.x persisted sessions; conflates two concerns (heuristic detection vs dev-actionable enrichment).
2. Fork `ConclusionEngine` into `DevActionEngine` — REJECTED. Duplicates rule registry maintenance.
3. Wrap (chosen) — clean separation, zero risk, easy rollback (just don't call `DevActionEngine.run`).

**Consequences**: `DevActionItem.ruleId` is the join key back to `Conclusion.ruleId`. Tests in `ConclusionEngineTest` keep working unchanged. New tests target `DevActionEngineTest`.

### ADR-2 — Static catalogs over generated/computed hints

**Decision**: `CodeAreaCatalog` and `ActionStepsCatalog` are hand-written Kotlin `object`s with `Map<ruleId, ...>` literals. NOT generated from rules, NOT loaded from external files.

**Alternatives considered**:
1. Per-rule method (`Rule.codeAreaHints(engine): List<CodeAreaHint>`) — REJECTED. Inflates `Rule` interface, ties rule logic to dev-action concern, makes per-engine variants awkward.
2. External YAML/JSON catalog file — REJECTED. Adds I/O dependency, breaks pure-function determinism, complicates tests.
3. Static Kotlin catalogs (chosen) — mirrors `RuleRegistry` + `SdkSignatureCatalog` patterns already established in the codebase. Detekt-friendly. Compile-time checked.

**Consequences**: Adding a new rule requires touching THREE files: register it in `RuleRegistry`, add a `CodeAreaCatalog` entry per engine, add an `ActionStepsCatalog` entry. This is by design (single source of truth per concern, plus catalog completeness guarantee).

### ADR-3 — Engine detection from already-captured events

**Decision**: `GameEngineDetector.detect(events: List<DetectedEvent>): GameEngine` reads the existing `DetectedEvent.sdkSource` field. No new logcat scanning, no new pattern matching, no new dependencies.

**Alternatives considered**:
1. APK manifest sniff (`adb shell pm dump <pkg>` + parse `meta-data` for `unity.build-id` / `com.unity3d.player.UnityPlayerActivity`) — REJECTED for v1. Adds adb call latency, requires APK to be installed at detection time, the LOADING-event path already covers most real games.
2. Native-library inspection (`adb shell run-as <pkg> ls lib/`) — REJECTED. Requires debuggable APK or root.
3. Reuse `DetectedEvent.sdkSource` (chosen) — zero new I/O, deterministic, testable with synthetic events.

**Consequences**: A game that runs for the full session WITHOUT triggering a LOADING event (e.g. a single-scene puzzle game) detects as `GameEngine.GENERIC`. This is acceptable — the GENERIC fallback hints/actions are still useful. Future enhancement (APK manifest sniff) tracked separately.

**Tie-break logic**:
1. Count events per engine `sdkSource`.
2. Highest count wins.
3. On equal counts, the engine whose **latest** event has the greater `startMs` wins.
4. No engine event at all → `GENERIC`.

### ADR-4 — DevActionBrief shape: items + topN field

**Decision**: `DevActionBrief` carries both `items: List<DevActionItem>` and `topN: Int = 5`. The full list is always persisted; the renderer is responsible for hiding items beyond `topN` behind a JS toggle.

**Alternatives considered**:
1. Pre-cap on the engine side (return only top 5) — REJECTED. Loses information; users can't "show all".
2. Always render all, no toggle — REJECTED. Cognitive overload defeats the user goal.
3. Items + topN with renderer hide (chosen) — keeps full info in persistence, controls UX cognitive load via CSS class `.dev-action-hidden` + JS toggle.

**Consequences**: Persisted `.gameperf` files grow by ~10-30% (one DevActionItem ~500 chars when serialised). Acceptable per proposal §Risks #5.

### ADR-5 — Reuse 3-tier Severity (no 4-tier migration)

**Decision**: `DevActionItem.severity: Severity` reuses the existing `Severity` enum from `core/conclusions/Rule.kt` (CRITICAL / WARNING / INFO). NO new 4-tier `DevActionSeverity` enum.

**Alternatives considered**:
1. New 4-tier (CRITICAL / HIGH / MEDIUM / LOW) — REJECTED. Migration churn for persisted v4.5.x sessions, dual-enum maintenance, ambiguous mapping (WARNING→HIGH? or →MEDIUM?).
2. Reuse 3-tier (chosen) — zero migration, single mental model.

**Consequences**: The mapping between user-stated severity buckets ("CRITICAL/HIGH/MEDIUM/LOW" in the proposal brief) and the implemented 3-tier is: CRITICAL→CRITICAL, HIGH/MEDIUM→WARNING/INFO depending on rule. Documented in spec DAB-002. UI labels match enum names directly: "Crítico", "Atención", "Información".

### ADR-6 — Confidence as separate enum (not derived)

**Decision**: `Confidence` is a NEW enum (HIGH / MEDIUM / LOW) attached to each `DevActionItem`, hand-set per rule based on heuristic robustness.

**Rationale**:
- A `cpu-saturated` rule firing at `avgCpu ≥ 85%` is HIGH confidence (almost certainly the bottleneck).
- A `fps-cap-suspect` firing at `p99 ≈ 30` is LOW confidence (might be intentional design choice).
- A `memory-leak-suspect` based on a 30-sample linear regression is MEDIUM (slope can be noise on short sessions).

**Per-rule baseline assignment** (Sprint 1 catalog work):
| ruleId | Confidence |
|---|---|
| `cpu-saturated` | HIGH |
| `thermal-throttling` | HIGH |
| `stable-low-fps-low-cpu` | MEDIUM |
| `memory-leak-suspect` | MEDIUM |
| `jank-with-good-avg` | MEDIUM |
| `fps-cap-suspect` | LOW |
| `ad-vs-game-fps-gap` | HIGH (informational, but the fact is verified) |
| `loading-thermal-recovery` | HIGH (likewise) |

**Consequences**: Adding a new rule requires choosing a confidence level. Catalog completeness check in Sprint 1 tests covers this.

### ADR-7 — Render at top of body, retain `#sec-conclusions` mid-body

**Decision**: New `<section id="sec-dev-action-brief">` renders at the TOP of the report body, BEFORE summary cards and `#sec-conclusions`. Existing `#sec-conclusions` rendering is **unchanged**.

**Alternatives considered**:
1. Move `#sec-conclusions` to the top, replace it visually — REJECTED. Breaking change for users / docs / screenshots referencing the old position. Also `#sec-dev-action-brief` is richer-typed; the existing prose-only conclusions still has value as drill-down.
2. Render at top, remove `#sec-conclusions` — REJECTED. Breaking change DAB-016 violation (must keep byte-equivalent).
3. Render at top, keep `#sec-conclusions` (chosen) — additive, no break.

**Consequences**: Users see TWO related sections in a long report:
1. `#sec-dev-action-brief` at top — structured, top-5, action-oriented
2. `#sec-conclusions` mid-body — prose, all firing rules, drill-down

They cross-reference via `ruleId`. Future change could consolidate, deferred.

## Detailed designs per artifact

### Data classes (Sprint 0)

```kotlin
// core/devactions/DevActionItem.kt
@Serializable
enum class GameEngine { UNITY, UNREAL, COCOS2D, GODOT, NATIVE, GENERIC }

@Serializable
enum class Confidence { HIGH, MEDIUM, LOW }

@Serializable
data class DevActionEvidence(
    val metric: String,           // "fps", "memory", "cpu", "thermal", "events"
    val segment: String,          // "RAW", "FILTERED", "EVENT_WINDOW"
    val values: Map<String, String> = emptyMap(),
)

@Serializable
data class CodeAreaHint(
    val engine: GameEngine,
    val area: String,             // e.g. "Unity main thread Update loop"
    val whyHere: String,          // Spanish tuteo-formal
    val docLink: String? = null,
)

@Serializable
data class ActionStep(
    val description: String,      // Spanish tuteo-formal
    val tool: String? = null,     // e.g. "Unity Profiler"
    val docLink: String? = null,
    val engineSpecific: GameEngine? = null,  // null = applies to all engines
)

@Serializable
data class LogcatLineRef(
    val timestampMs: Long,
    val tag: String,
    val excerpt: String,
)

@Serializable
data class DevActionItem(
    val ruleId: String,
    val severity: Severity,
    val title: String,
    val evidence: DevActionEvidence,
    val diagnostic: String,
    val codeAreaHints: List<CodeAreaHint>,
    val suggestedActions: List<ActionStep>,
    val relatedLogcatLines: List<LogcatLineRef> = emptyList(),
    val confidence: Confidence,
)

@Serializable
data class DevActionBrief(
    val items: List<DevActionItem> = emptyList(),
    val topN: Int = 5,
)
```

Note: `Severity` is **reused** from `core/conclusions/Rule.kt` (already `@Serializable`). No new severity enum.

### DevActionEngine (Sprint 0)

```kotlin
// core/devactions/DevActionEngine.kt
object DevActionEngine {
    fun run(input: ConclusionInput): DevActionBrief {
        val conclusions = ConclusionEngine.run(input)
        if (conclusions.isEmpty()) return DevActionBrief(emptyList())

        val engine = GameEngineDetector.detect(input.events)

        val items = conclusions.map { conclusion ->
            enrichToDevActionItem(conclusion, engine, input)
        }
        return DevActionBrief(items = items, topN = DEFAULT_TOP_N)
    }

    private const val DEFAULT_TOP_N = 5

    private fun enrichToDevActionItem(
        conclusion: Conclusion,
        engine: GameEngine,
        input: ConclusionInput,
    ): DevActionItem {
        val hints = CodeAreaCatalog.lookup(conclusion.ruleId, engine)
        val allActions = ActionStepsCatalog.lookup(conclusion.ruleId)
        val filteredActions = allActions.filter {
            it.engineSpecific == null || it.engineSpecific == engine
        }
        val evidence = EvidenceBuilder.build(conclusion.ruleId, input)
        val diagnostic = conclusion.recommendation ?: ""  // initial v1: reuse rule recommendation as diagnostic
        val confidence = ConfidenceLookup.forRule(conclusion.ruleId)

        return DevActionItem(
            ruleId = conclusion.ruleId,
            severity = conclusion.severity,
            title = conclusion.headline,
            evidence = evidence,
            diagnostic = diagnostic,
            codeAreaHints = hints,
            suggestedActions = filteredActions,
            relatedLogcatLines = emptyList(), // v1 — DAB-014 reserved
            confidence = confidence,
        )
    }
}
```

### GameEngineDetector (Sprint 2)

```kotlin
// core/devactions/GameEngineDetector.kt
object GameEngineDetector {
    private val SDK_TO_ENGINE = mapOf(
        "Unity Engine" to GameEngine.UNITY,
        "Unreal Engine" to GameEngine.UNREAL,
        "Cocos2d" to GameEngine.COCOS2D,
    )

    fun detect(events: List<DetectedEvent>): GameEngine {
        val byEngine = events.mapNotNull { ev ->
            SDK_TO_ENGINE[ev.sdkSource]?.let { it to ev }
        }
        if (byEngine.isEmpty()) return GameEngine.GENERIC

        val counts = byEngine.groupingBy { it.first }.eachCount()
        val maxCount = counts.values.max()
        val winners = counts.filterValues { it == maxCount }.keys
        if (winners.size == 1) return winners.first()

        // Tie-break: most recent
        return byEngine
            .filter { it.first in winners }
            .maxBy { it.second.startMs }
            .first
    }
}
```

### CodeAreaCatalog (Sprint 1)

Pattern (one entry shown — full catalog filled in Sprint 1 task):

```kotlin
// core/devactions/CodeAreaCatalog.kt
internal object CodeAreaCatalog {
    private val catalog: Map<String, Map<GameEngine, List<CodeAreaHint>>> = mapOf(
        "stable-low-fps-low-cpu" to mapOf(
            GameEngine.UNITY to listOf(
                CodeAreaHint(
                    engine = GameEngine.UNITY,
                    area = "MonoBehaviour.Update / LateUpdate del hilo principal",
                    whyHere = "Esta regla dispara cuando la CPU tiene margen pero el FPS no sube. " +
                        "En Unity, la causa más común es lógica de scripts cara por frame en el hilo principal.",
                    docLink = "https://docs.unity3d.com/Manual/ProfilerWindow.html",
                ),
                // ... more hints
            ),
            GameEngine.UNREAL to listOf(/* ... */),
            GameEngine.COCOS2D to listOf(/* ... */),
            GameEngine.GENERIC to listOf(/* ... */),
        ),
        // ... 7 more rule entries
    )

    fun lookup(ruleId: String, engine: GameEngine): List<CodeAreaHint> {
        val ruleEntry = catalog[ruleId] ?: return emptyList()
        return ruleEntry[engine] ?: ruleEntry[GameEngine.GENERIC] ?: emptyList()
    }
}
```

### ActionStepsCatalog (Sprint 1)

```kotlin
// core/devactions/ActionStepsCatalog.kt
internal object ActionStepsCatalog {
    private val catalog: Map<String, List<ActionStep>> = mapOf(
        "memory-leak-suspect" to listOf(
            ActionStep(
                description = "Captura un snapshot de memoria con Unity Memory Profiler antes y después del pico para comparar el delta de objetos.",
                tool = "Unity Memory Profiler",
                docLink = "https://docs.unity3d.com/Packages/com.unity.memoryprofiler@latest",
                engineSpecific = GameEngine.UNITY,
            ),
            ActionStep(
                description = "Revisa los pools de objetos: si los GameObjects pooled nunca vuelven al pool, son fugas funcionales.",
                tool = null,
                docLink = null,
                engineSpecific = null, // applies to all engines
            ),
            // ... up to 5 entries per rule
        ),
        // ... 7 more rule entries
    )

    fun lookup(ruleId: String): List<ActionStep> = catalog[ruleId] ?: emptyList()
}
```

### EvidenceBuilder (Sprint 0)

```kotlin
// core/devactions/EvidenceBuilder.kt
internal object EvidenceBuilder {
    fun build(ruleId: String, input: ConclusionInput): DevActionEvidence = when (ruleId) {
        "stable-low-fps-low-cpu" -> DevActionEvidence(
            metric = "fps",
            segment = "FILTERED",
            values = mapOf(
                "p50" to input.filtered.p50.toString(),
                "target" to input.targetFps.toString(),
                "avgCpu" to input.filtered.avgCpu.toString(),
                "maxTempCpu" to "%.1f".format(input.filtered.maxTempCpu),
            ),
        )
        "thermal-throttling" -> DevActionEvidence(
            metric = "thermal",
            segment = "FILTERED",
            values = mapOf(
                "maxTempCpu" to "%.1f".format(input.filtered.maxTempCpu),
                "maxTempSkin" to "%.1f".format(input.filtered.maxTempSkin),
                "p5Fps" to input.filtered.p5.toString(),
                "avgFps" to input.filtered.avgFps.toString(),
            ),
        )
        // ... per-rule entries for all 8 rules
        else -> DevActionEvidence(metric = "unknown", segment = "RAW", values = emptyMap())
    }
}
```

### ConfidenceLookup (Sprint 0)

```kotlin
// core/devactions/ConfidenceLookup.kt
internal object ConfidenceLookup {
    private val map = mapOf(
        "cpu-saturated" to Confidence.HIGH,
        "thermal-throttling" to Confidence.HIGH,
        "stable-low-fps-low-cpu" to Confidence.MEDIUM,
        "memory-leak-suspect" to Confidence.MEDIUM,
        "jank-with-good-avg" to Confidence.MEDIUM,
        "fps-cap-suspect" to Confidence.LOW,
        "ad-vs-game-fps-gap" to Confidence.HIGH,
        "loading-thermal-recovery" to Confidence.HIGH,
    )
    fun forRule(ruleId: String): Confidence = map[ruleId] ?: Confidence.MEDIUM
}
```

### Persistence (Sprint 3)

Three files touched:

1. `viewmodel/AppViewModel.kt` — `SessionResult` (data class) adds:
   ```kotlin
   val devActionBrief: DevActionBrief = DevActionBrief(),
   ```

2. `core/SessionHistory.kt` — `SerializableEntry` adds:
   ```kotlin
   val devActionBrief: DevActionBrief = DevActionBrief(),
   ```
   And `HistoryEntry` adds the same. `toSerializable` / `toHistoryEntry` round-trip the field 1:1 (no conversion needed — `DevActionBrief` is `@Serializable`).

3. AppViewModel's finalization site (where `conclusions` is currently computed) calls `DevActionEngine.run(input)` and assigns to `SessionResult.devActionBrief`. Co-located with existing conclusion call at finalization.

### Report rendering (Sprint 3)

Three changes to `report/ReportGenerator.kt`:

1. New method `sectionDevActionBrief(brief: DevActionBrief, engine: GameEngine): String` — produces the `<section id="sec-dev-action-brief">` HTML.
2. Inserted at the TOP of the body, BEFORE the existing summary cards. Insertion point identified by `// ══════════ summary ══════════` comment block.
3. Nav-link `<a href="#sec-dev-action-brief" class="nav-link">Acción Dev</a>` inserted as FIRST nav entry (before "Conclusiones") when `brief.items.isNotEmpty()`.

CSS additions (appended to existing `<style>` block at line ~1718 in current file):

```css
/* v4.6.0 — dev action brief */
.dev-action-brief { /* container */ }
.dev-action-item { /* per-item card */ }
.dev-action-severity-critical { border-left: 4px solid #d33; }
.dev-action-severity-warning  { border-left: 4px solid #fa3; }
.dev-action-severity-info     { border-left: 4px solid #38a; }
.dev-action-header { display: flex; gap: 8px; align-items: center; }
.dev-action-evidence dl { /* metric values list */ }
.dev-action-hint { /* code area hint card */ }
.dev-action-step { /* action step card */ }
.dev-action-logcat { /* optional logcat block — only when DAB-014 populated */ }
.dev-action-hidden { display: none; }
.dev-action-show-all-btn { /* toggle button */ }
```

JS toggle (minimal vanilla JS appended to the existing inline `<script>` block, or new inline script if none exists):

```javascript
document.querySelectorAll('.dev-action-show-all-btn').forEach(btn => {
  btn.addEventListener('click', () => {
    const section = btn.closest('section');
    section.querySelectorAll('.dev-action-hidden').forEach(el => el.classList.toggle('dev-action-hidden-collapsed'));
    btn.textContent = btn.textContent === 'Mostrar todo' ? 'Mostrar menos' : 'Mostrar todo';
  });
});
```

## Edge cases

| Case | Handling |
|---|---|
| No rules fire | `DevActionEngine.run` returns `DevActionBrief(emptyList())`. ReportGenerator omits the section entirely (spec DAB-008 negative case). |
| Single CRITICAL rule | Renders one item, no toggle (size ≤ topN). |
| 12 firing rules | First 5 visible, "Mostrar todo" toggle present. |
| Engine detection ties | Tie-break by most-recent `startMs` (ADR-3). |
| `thermalAvailable = false` | Existing rules already short-circuit (v4.4.1 discovery #274). DevActionEngine inherits this — those rules don't fire, so no DevActionItem is created for them. No new code path needed. |
| Catalog entry missing for a rule | `CodeAreaCatalog.lookup` returns empty list; DevActionItem gets `codeAreaHints = emptyList()`. Sprint 1 catalog-completeness test prevents this in practice. |
| `Conclusion.recommendation = null` (legacy fixture) | `diagnostic = ""`. UI renders the title only. |
| Persisted v4.5.0 row loaded | `devActionBrief = DevActionBrief()` (defaulted, empty). UI renders no section (DAB-010). |
| Unknown `sdkSource` in events | `GameEngineDetector` filters via `SDK_TO_ENGINE` map — unknown sources are ignored, may fall through to `GENERIC`. |

## File map

| File | Sprint | Status |
|---|---|---|
| `src/main/kotlin/com/gameperf/desktop/core/devactions/DevActionItem.kt` | 0 | NEW |
| `src/main/kotlin/com/gameperf/desktop/core/devactions/DevActionEngine.kt` | 0 | NEW |
| `src/main/kotlin/com/gameperf/desktop/core/devactions/EvidenceBuilder.kt` | 0 | NEW |
| `src/main/kotlin/com/gameperf/desktop/core/devactions/ConfidenceLookup.kt` | 0 | NEW |
| `src/main/kotlin/com/gameperf/desktop/core/devactions/CodeAreaCatalog.kt` | 0 (skeleton) → 1 (filled) | NEW |
| `src/main/kotlin/com/gameperf/desktop/core/devactions/ActionStepsCatalog.kt` | 0 (skeleton) → 1 (filled) | NEW |
| `src/main/kotlin/com/gameperf/desktop/core/devactions/GameEngineDetector.kt` | 2 | NEW |
| `src/main/kotlin/com/gameperf/desktop/core/devactions/package-info.kt` | 0 | NEW |
| `src/main/kotlin/com/gameperf/desktop/viewmodel/AppViewModel.kt` | 3 | EDIT — add `devActionBrief` field to `SessionResult`, call `DevActionEngine.run` at finalization |
| `src/main/kotlin/com/gameperf/desktop/core/SessionHistory.kt` | 3 | EDIT — add `devActionBrief` to `SerializableEntry` + `HistoryEntry` + converters |
| `src/main/kotlin/com/gameperf/desktop/report/ReportGenerator.kt` | 3 | EDIT — new `sectionDevActionBrief` method, insert at top of body, nav-link, CSS, JS |
| `src/test/kotlin/com/gameperf/desktop/core/devactions/DevActionEngineTest.kt` | 0 | NEW |
| `src/test/kotlin/com/gameperf/desktop/core/devactions/EvidenceBuilderTest.kt` | 0 | NEW |
| `src/test/kotlin/com/gameperf/desktop/core/devactions/CodeAreaCatalogTest.kt` | 1 | NEW |
| `src/test/kotlin/com/gameperf/desktop/core/devactions/ActionStepsCatalogTest.kt` | 1 | NEW |
| `src/test/kotlin/com/gameperf/desktop/core/devactions/GameEngineDetectorTest.kt` | 2 | NEW |
| `src/test/kotlin/com/gameperf/desktop/core/SessionHistoryDevActionBriefTest.kt` | 3 | NEW |
| `src/test/kotlin/com/gameperf/desktop/report/ReportDevActionBriefTest.kt` | 3 | NEW |
| `src/test/kotlin/com/gameperf/desktop/core/conclusions/ConclusionEngineSnapshotTest.kt` | 0 | NEW (baseline before changes — DAB-016) |

## Test strategy

- **Sprint 0**: Snapshot-test `ConclusionEngine.run` output for a representative fixture BEFORE any other Sprint 0 work — locks in the DAB-016 invariant. Then build out `DevActionEngineTest` against the same fixture.
- **Sprint 1**: Two `CompletenessTest`-style tests — one per catalog — iterating `RuleRegistry.all` and asserting an entry exists per ruleId × {UNITY, UNREAL, COCOS2D, GENERIC}.
- **Sprint 2**: `GameEngineDetectorTest` with synthetic event streams covering each engine + ties + empty + mixed.
- **Sprint 3**: Round-trip persistence + HTML snapshot of `sectionDevActionBrief` + backward compat with a captured pre-v4.6 fixture `history.json` file.

## Non-functional

- **Performance**: `DevActionEngine.run` is pure-function, in-memory, runs once at session finalization (not in the capture hot loop). Negligible cost (~8 rule evaluations + ~8 map lookups + ~8 string interpolations).
- **Determinism**: Same `ConclusionInput` → same `DevActionBrief` (catalogs are immutable, engine detection is pure over `List<DetectedEvent>`).
- **Backward compat**: Defaulted fields in `SerializableEntry`. Pre-v4.6 history.json hydrates with empty brief.
- **Localisation**: All Spanish copy lives in catalog files (single touch-point for tone/style review).
- **Detekt**: Magic numbers gated by `const val` (e.g. `DEFAULT_TOP_N`). Catalog literals are pure data, no detekt complexity issues expected.

## Next phase

→ Run `sdd-tasks`. Topic key: `sdd/dev-action-brief/tasks`.
