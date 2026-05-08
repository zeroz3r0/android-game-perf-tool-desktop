# Design: auto-event-detection-and-clean-metrics

## Technical Approach

Three orthogonal pillars, wired together at session-end aggregation:

1. **Detection** — `core/events/EventDetector` runs alongside the capture loop, owning ONE long-lived `adb logcat` child + a 1 Hz `dumpsys activity` poll. Emits `DetectedEvent`s through a `MutableStateFlow<List<DetectedEvent>>` exposed by `AppViewModel`.
2. **Filtering** — `core/metrics/FilteredMetricsCalculator` is a pure function: `(timedSamples, ranges) -> MetricsAggregates`. Computed twice per session — once with detected ranges (filtered/primary), once with no ranges (raw/audit).
3. **Conclusions** — `core/conclusions/ConclusionEngine` runs a deterministic Kotlin rule catalog over filtered aggregates + device tier + raw aggregates, returning ordered `Conclusion`s rendered in a new `#sec-conclusions` report block.

Manual `MarkerType` markers (`viewmodel/AppViewModel.kt:46`) stay — they coexist with auto-events and feed the same rendering paths.

## Architecture Decisions

| Decision | Choice | Alternative | Rationale |
|---|---|---|---|
| Detection mechanism | `adb logcat` + `dumpsys activity` | Frida instrumentation; CV on recorded video; AccessibilityService | Observational, zero device modification, works on release APKs and any Android API level we already support. CV is brittle and expensive. Frida needs root or a debug build. |
| Conclusion engine | Deterministic Kotlin rules | LLM API call; YAML config | Testable, versionable, explainable, no network/cost/latency. Rules need cross-series math (regression slope, jank ratio) painful to express in YAML. |
| Two metric views | Filtered (primary) + raw (secondary) | Filtered only; raw only | Filtered fixes the user's complaint; raw stays so the user can audit "what got dropped". Cost = ~6 extra fields in `MetricsAggregates` and a comparison row in the report. |
| Filter padding | Symmetric ±500 ms around event ranges | None; asymmetric post-only | SDKs log AFTER the ad starts rendering; pre-padding catches the transition frames. ±500 ms is conservative and configurable via `FilteredMetricsCalculator.PADDING_MS`. |
| Manual markers fate | Kept as fallback | Removed entirely | Auto-detection only covers top-5 ad SDKs; exotic SDKs need manual fallback. Markers and auto-events live in the same union table in the report. |
| Feature flag | ON by default + settings toggle | Off-by-default opt-in | Solves the user's primary complaint; the toggle exists as escape hatch when a specific game's logs misbehave. |
| iOS support | Android-complete + iOS best-effort same release | Wait for parity; ship Android only | Honest incremental: iOS gets StoreKit IAP + foreground-loss detection, Android gets the full pipeline. Report header advertises the mode. |

## Data Flow

```
                    ┌── adb logcat (filtered tags) ──► LogcatLineParser ──┐
                    │                                                     ▼
 startCapture ──►   │                                              SdkSignatureCatalog
                    │                                                     │
                    └── dumpsys activity (1 Hz poller) ────────────► EventDetector
                                                                          │
                                                                          ▼
                                                           events: StateFlow<List<DetectedEvent>>
                                                                          │
   capture loop ──► fpsTimed/cpuTimed/memTimed/...                        │
                                  │                                       │
                                  └──────────────┬────────────────────────┘
                                                 ▼
                                  FilteredMetricsCalculator
                                                 │
                                  ┌──────────────┼──────────────┐
                                  ▼              ▼              ▼
                            MetricsAggregates    MetricsAggregates       (paddingMs ±500)
                              (filtered)             (raw)
                                  │                  │
                                  ▼                  │
                        FinalScoreCalculator         │
                                  │                  │
                                  ▼                  │
                          ConclusionEngine ◄─────────┘
                                  │
                                  ▼
                          ReportGenerator → #sec-conclusions
                                          → #sec-events (union with markers)
                                          → metric cards (filtered primary / raw secondary)
                                          → FPS chart shaded bands
```

## Core Types and Interfaces

```kotlin
// core/events/DetectedEvent.kt
data class DetectedEvent(
    val id: String = java.util.UUID.randomUUID().toString(),
    val type: EventType,           // INTERSTITIAL, REWARDED_VIDEO, IAP, LOADING, FOREGROUND_LOSS
    val sdkSource: String,         // "AdMob" | "UnityAds" | "IronSource" | "AppLovin" | "MetaAN" | "PlayBilling" | "StoreKit" | "ForegroundLoss" | "Unknown"
    val startMs: Long,             // wall-clock start (relative to captureStartTime)
    val endMs: Long?,              // null while still open
    val confidence: Confidence,    // HIGH (logcat+dumpsys agree), MEDIUM (logcat only), LOW (gap-suspect or fallback)
    val signatureMatched: String,  // for debugging: which regex/cmp= triggered
    val metadata: Map<String, String> = emptyMap()
)
enum class EventType { INTERSTITIAL, REWARDED_VIDEO, IAP, LOADING, FOREGROUND_LOSS, UNKNOWN }
enum class Confidence { HIGH, MEDIUM, LOW }

// core/events/EventDetector.kt
interface EventDetector {
    val events: StateFlow<List<DetectedEvent>>
    val warnings: StateFlow<List<String>>   // e.g. "logcat gap detected", "dumpsys timeout"
    suspend fun start(deviceId: String, gamePackage: String, scope: CoroutineScope)
    fun stop()
}

// core/events/LogcatCapture.kt
internal class LogcatCapture(
    private val adb: AdbBridgeApi,
    private val onLine: (LogLine) -> Unit,    // pushes parsed lines into the matcher pipeline
    private val onGap: (gapMs: Long) -> Unit  // marks LOW confidence on detections inside the gap
) {
    fun start(deviceId: String, tagFilters: List<String>): Process? // mirrors startScreenRecord pattern
    fun stop()
}
data class LogLine(val tsMs: Long, val pid: Int, val level: Char, val tag: String, val msg: String)

// core/events/DumpsysPoller.kt
internal class DumpsysPoller(
    private val adb: AdbBridgeApi,
    private val pollMs: Long = 1_000L,
    private val onActivityStack: (List<ActivityFrame>) -> Unit
) { suspend fun run(deviceId: String, scope: CoroutineScope) }
data class ActivityFrame(val cmp: String, val pid: Int, val taskId: Int)

// core/events/SdkSignatureCatalog.kt
internal data class SdkSignature(
    val sdk: String,
    val type: EventType,
    val activityClasses: List<String>,           // dumpsys cmp= matches
    val logcatTags: List<String>,                // -s <tag>:D filtering
    val openPatterns: List<Regex>,               // logcat msg regex → start
    val closePatterns: List<Regex>               // logcat msg regex → end
)
internal object SdkSignatureCatalog {
    val ALL: List<SdkSignature> = listOf(/* AdMob, UnityAds, IronSource, AppLovin, MetaAN, PlayBilling */)
    fun logcatTagArgs(): List<String>  // expands to "AdActivity:D Ads:D ... *:S"
    fun matchOpen(line: LogLine): SdkSignature?
    fun matchClose(line: LogLine, openSig: SdkSignature): Boolean
    fun matchActivity(cmp: String): SdkSignature?
}

// core/metrics/FilteredMetricsCalculator.kt
data class TimeRange(val startMs: Long, val endMs: Long)
data class MetricsAggregates(
    val avgFps: Int, val minFps: Int, val maxFps: Int,
    val p1: Int, val p5: Int, val p50: Int, val p90: Int, val p99: Int,
    val avgFrameTime: Double, val p99FrameTime: Double,
    val peakMem: Long, val avgCpu: Int, val maxCpu: Int,
    val maxTempCpu: Double, val maxTempGpu: Double, val maxTempSkin: Double, val maxTempDieCpu: Double,
    val totalJank: Long, val totalStutter: Int,
    val sampleCount: Int                  // # of samples that survived the filter
)
object FilteredMetricsCalculator {
    const val PADDING_MS: Long = 500L
    const val EXCESSIVE_FILTER_RATIO: Double = 0.70  // > 70% filtered → fallback to raw
    fun compute(input: FilterInput, excludedRanges: List<TimeRange>): MetricsAggregates
    fun unionRanges(ranges: List<TimeRange>, paddingMs: Long = PADDING_MS): List<TimeRange>
}
data class FilterInput(
    val fpsTimed: List<TimedSample>, val cpuTimed: List<TimedSample>,
    val memTimed: List<TimedSample>, val tempCpuTimed: List<TimedSample>,
    val tempGpuTimed: List<TimedSample>, val tempSkinTimed: List<TimedSample>,
    val tempDieCpuTimed: List<TimedSample>, val frameTimes: List<TimedSample>,
    val jankTimed: List<TimedSample>, val stutterTimed: List<TimedSample>,
    val captureStartTime: Long, val sessionEndMs: Long
)

// core/conclusions/Rule.kt
data class ConclusionInput(
    val filtered: MetricsAggregates,
    val raw: MetricsAggregates,
    val targetFps: Int,
    val deviceTier: HardwareScoring.DeviceTier,
    val events: List<DetectedEvent>,
    val sessionDurationS: Int
)
enum class Severity { INFO, WARNING, CRITICAL }
data class Conclusion(
    val ruleId: String,        // stable, e.g. "stable-low-fps-low-cpu"
    val severity: Severity,
    val headline: String,      // Castilian Spanish, formal tuteo
    val recommendation: String? // optional actionable line
)
interface Rule {
    val id: String
    val severity: Severity
    fun matches(input: ConclusionInput): Boolean
    fun render(input: ConclusionInput): Conclusion
}

// core/conclusions/ConclusionEngine.kt
object ConclusionEngine {
    val RULES: List<Rule> = RuleRegistry.all
    fun run(input: ConclusionInput): List<Conclusion>  // sorted CRITICAL > WARNING > INFO, then by ruleId
}
```

Python sidecar (iOS):

```python
# sidecar/gameperf_sidecar/events.py
@dataclass
class IosDetectedEvent:
    type: str          # "iap" | "foreground_loss" | "unknown"
    source: str        # "StoreKit" | "SpringBoard"
    start_ms: int
    end_ms: int | None
    confidence: str    # "high" | "medium" | "low"
    signature_matched: str

class IosEventDetector:
    async def start(self, udid: str) -> None: ...   # spawns syslog watcher task
    async def stop(self) -> list[IosDetectedEvent]: ...
```

## Integration Points

| File | Lines | Change |
|---|---|---|
| `viewmodel/AppViewModel.kt` | 949-963 | Add timed twins: `cpuTimed`, `memTimed`, `nativeTimed`, `javaTimed`, `tempCpuTimed`, `tempGpuTimed`, `tempSkinTimed`, `tempDieCpuTimed`, `frameTimeTimed`, `jankTimed`, `stutterTimed` (all `MutableList<TimedSample>`). |
| `viewmodel/AppViewModel.kt` | 816-878 (`startCapture`) | Instantiate `EventDetector`, call `.start(...)` after `captureStartTime` is set. |
| `viewmodel/AppViewModel.kt` | 1322-1346 (post-loop aggregation) | Replace inline percentile math with two calls to `FilteredMetricsCalculator.compute` (filtered + raw). Pass filtered values to `FinalScoreCalculator.compute(GradingInput(...))`. Build `ConclusionInput` and call `ConclusionEngine.run(...)`. |
| `viewmodel/AppViewModel.kt` | new state flows | Add `private val _events = MutableStateFlow<List<DetectedEvent>>(emptyList())` + public `events: StateFlow<List<DetectedEvent>>`. Add `_conclusions: StateFlow<List<Conclusion>>` populated at session end. |
| `viewmodel/AppViewModel.kt` | `stopCapture` path | Call `eventDetector.stop()` BEFORE the post-loop aggregation. |
| `core/AdbBridge.kt` (or new `AdbLogcat.kt`) | after line 147 | Add `fun startLogcat(deviceId: String, tagArgs: List<String>): Process` — long-lived child following the `startScreenRecord` pattern at line 769-779 of `AppViewModel.kt`. |
| `core/AdbBridgeApi.kt` | new method | Add `fun startLogcat(...)`; `FakeAdbBridge` returns a fixture-fed mock process. |
| `report/ReportGenerator.kt` | 296-327 (metrics-dashboard) | Each `metricCard(...)` call gains a `subLineRaw` parameter. When `filtered.field != raw.field` by >5%, sub-line shows "(incl. ads: X)". |
| `report/ReportGenerator.kt` | 124-130 (`markerAnnotationsJs`) | Add `eventBandsJs` block emitting `box`-type Chart.js annotations for each excluded range, color-coded by `EventType`. |
| `report/ReportGenerator.kt` | 133-157 (`markersHtml`) | Rename to `eventsHtml`, render union of `markers` + auto `events` with a "Source" column ("Manual" / "Auto: AdMob" / etc.). |
| `report/ReportGenerator.kt` | new section | Insert `#sec-conclusions` between `#sec-summary` (line 265) and `#sec-dashboard` (line 296). Card-per-conclusion with severity icon + headline + optional recommendation. |
| `report/ReportGenerator.kt` | nav bar 232-245 | Insert `<a href="#sec-conclusions">Conclusiones</a>` after Resumen. |
| `core/grading/FinalScoreCalculator.kt` | 32-43 (`GradingInput`) | NO struct change. Behavior change: caller passes filtered values instead of raw. Add KDoc note "values must be filtered upstream". |
| `ui/screens/CaptureScreen.kt` | 178-182 | Manual marker buttons stay. Add a small live indicator next to the markers row: "Auto: 3 eventos" with a colored dot when `events.value.isNotEmpty()`. |
| `sidecar/gameperf_sidecar/main.py` | new endpoint | `GET /device/{udid}/events` returns the running `IosEventDetector`'s buffered list. `POST /device/{udid}/events/start`, `POST /device/{udid}/events/stop`. |
| `sidecar/gameperf_sidecar/devices.py` | 218-234 (existing syslog scanner) | Generalize for `events.py` to reuse the `OsTraceService` connection. |
| `core/ios/SidecarClient.kt` | new methods | `suspend fun startEventDetection(udid)`, `suspend fun stopEventDetection(udid): List<IosDetectedEvent>`. |

Backwards compatibility: manual markers route through the existing `_markers` flow untouched. Legacy `.gameperf` exports without an `events` field load with `events = emptyList()` (the conclusions section then says "datos insuficientes").

## Data Model Changes

```kotlin
// SessionResult — additive only
data class SessionResult(
    /* ... existing fields ... */
    val markers: List<SessionMarker> = emptyList(),
    // NEW
    val events: List<DetectedEvent> = emptyList(),
    val rawAggregates: MetricsAggregates? = null,         // null on legacy reload
    val filteredAggregates: MetricsAggregates? = null,    // null on legacy reload
    val conclusions: List<Conclusion> = emptyList(),
    val detectionMode: DetectionMode = DetectionMode.MANUAL_ONLY  // ANDROID_FULL | IOS_PARTIAL | MANUAL_ONLY
)
```

JSON session export schema bump: bump `schemaVersion` to `5` (current is `4` after v4.3.x history persistence). Loader handles v4 → v5 by populating empty events/conclusions and treating raw as `null`. The session-history persistence layer (introduced in v4.3.x) already supports forward-compat unknown-field tolerance.

## Initial 8 Heuristic Rules

| ID | Severity | Predicate | Output template (Spanish, formal tuteo) |
|---|---|---|---|
| `stable-low-fps-low-cpu` | WARNING | `filtered.p50 ≤ 0.7 * targetFps && filtered.avgCpu < 50 && filtered.maxTempCpu < 42` | "El juego corre a {p50}fps estable. La carga de CPU es {avgCpu}% y la temperatura es {temp}°C — el dispositivo no está saturado. El cuello de botella probablemente está en la lógica del juego o en el renderizado. Recomendación: perfilar scripts y revisar draw calls." |
| `thermal-throttling` | CRITICAL | `(filtered.maxTempSkin > 42 \|\| filtered.maxTempDieCpu > 95) && (filtered.p99 - filtered.p1) > 15` | "Detectado throttling térmico — piel {maxSkin}°C, die {maxDie}°C. Los drops de FPS de {p99} a {p1} correlacionan con eventos térmicos. Probá en un ambiente más fresco para aislar el efecto térmico." |
| `memory-leak-suspect` | WARNING | `linearSlope(memTimed) > 0.5 MB/s && sessionDurationS > 60 && noMemoryDropsObserved` | "La memoria creció {totalGrowth}MB en {duration}s sin liberaciones observadas. Posible memory leak — recomendación: ejecutar una sesión más larga y monitorear OOM." |
| `jank-with-good-avg` | WARNING | `jankRatio > 0.10 && filtered.avgFps > 0.85 * targetFps` | "El FPS promedio está en target ({avgFps}/{targetFps}) pero {jankPct}% de los frames son jank. Indica stutters intermitentes. Recomendación: análisis de frame-time alrededor de transiciones de escena." |
| `fps-cap-suspect` | INFO | `filtered.maxFps ≤ 32 && deviceTier in [MID, HIGH, FLAGSHIP] && targetFps ≥ 60` | "El juego parece estar limitado a 30fps a pesar de que el dispositivo soporta más. Verificá los settings de Application.targetFrameRate / vsync." |
| `cpu-saturated` | CRITICAL | `filtered.avgCpu > 85` | "La CPU está saturada (promedio {avgCpu}%). Es el cuello de botella principal — recomendación: profiling de threads nativos (Perfetto / systrace)." |
| `ad-vs-game-fps-gap` | INFO | `events.any { it.type in [INTERSTITIAL, REWARDED_VIDEO] } && (raw.avgFps - filtered.avgFps) > 0.15 * filtered.avgFps` | "El render del SDK de ads promedió {rawAvg}fps vs {filteredAvg}fps de tu juego — confirma que el filtrado funciona. Tu juego corre {delta}fps por debajo de las superficies livianas del ad, lo que sugiere escenas con render más pesado del necesario." |
| `loading-thermal-recovery` | INFO | `events.any { it.type == LOADING } && tempDuringLoading < tempBeforeLoading - 2 && tempAfterLoading > tempDuringLoading + 2` | "Las pantallas de carga son los únicos momentos donde el dispositivo se enfría. Considerá si el level design depende de loadings frecuentes para evitar throttling." |

Test fixtures: each rule gets `<RuleId>Test.kt` with at minimum: 1 fixture that fires it, 1 fixture that doesn't, 1 boundary fixture (just below threshold).

## Performance Considerations

| Component | Budget | Mitigation |
|---|---|---|
| `LogcatCapture` reader | <50 lines/s parsed | Aggressive `tag:D *:S` filtering. Reader on `Dispatchers.IO`. |
| Logcat process startup | <200 ms | Started AFTER `captureStartTime` is set so warmup doesn't pollute samples. |
| `DumpsysPoller` | 1 Hz × 50-200 ms shell-out | Sibling coroutine on `Dispatchers.IO`, doesn't block 500 ms capture loop. |
| `EventDetector` matching | <1 ms per line | Pure regex via `LogcatLineParser`. Catalog precomputes `tagArgs()` once. |
| `FilteredMetricsCalculator.compute` | <50 ms total | Runs ONCE at end of session over <7,200 samples. Pure O(n log n) sort. |
| `ConclusionEngine.run` | <10 ms total | 8-12 rules × O(n) predicates over already-aggregated scalars. |

Capture-loop overhead from this change: 0 (event detection runs in parallel coroutines). Device-side `adb logcat` adds ~1-3% CPU on the device per the exploration's investigation.

## Error Handling

| Failure | Response |
|---|---|
| `adb logcat` fails to start | `EventDetector.warnings` += "Detección automática no disponible (logcat falló). Usando marcadores manuales." Capture continues normally; manual markers still work. |
| Logcat reader gap > 5 s | Mark all `DetectedEvent`s during the gap as `Confidence.LOW`. Report header notes the gap. |
| `dumpsys activity` returns garbage / empty | Skip that poll cycle. Log to `System.err`. After 5 consecutive failures, disable the dumpsys poller for the rest of the session and rely on logcat-only detection. |
| Filter excludes >70% of session | Fall back to raw aggregates as primary. Show prominent warning: "El filtrado eliminó la mayoría de la sesión — mostrando métricas sin filtrar. Revisá los eventos detectados en la sección Eventos." |
| Session too short (<30 s OR <60 samples) | `ConclusionEngine` returns `[Conclusion(ruleId="insufficient-data", ...)]` only. Hide other rules. |
| iOS sidecar without Developer Mode | `detectionMode = IOS_PARTIAL`. Report header banner: "iOS — detección parcial (sin Developer Mode). Solo IAP y pérdida de foreground se detectan automáticamente." |
| `EventDetector.stop()` after `captureJob` cancellation | Idempotent; safe to call from `finally` block. |

## Test Strategy

| Layer | Component | Approach |
|---|---|---|
| Unit (pure) | `LogcatLineParser` | Recorded `.log` fixtures under `src/test/resources/logcat-fixtures/` (admob-interstitial, unity-ads, ironsource, applovin, billing-launch). Parse → assert structured `LogLine` shape. |
| Unit (pure) | `SdkSignatureCatalog` | Each signature → 1 positive line + 1 negative line + 1 boundary case. ≥80% line coverage. |
| Unit (pure) | `FilteredMetricsCalculator` | Hand-built `FilterInput` + `excludedRanges` → assert exact aggregates. Edge cases: empty ranges, ranges overlapping fully, ranges outside session, >70% exclusion fallback, sample exactly on padding boundary. |
| Unit (pure) | Each `Rule` (8+) | `<RuleId>Test.kt` with fires/no-fires/boundary fixtures. NO mocks per CLAUDE.md "tests puros sin mocks" rule. |
| Unit (pure) | `ConclusionEngine.run` ordering | Construct multiple rules firing simultaneously → assert CRITICAL > WARNING > INFO ordering and stable ruleId tiebreak. |
| Integration | `EventDetector` | Inject `FakeAdbBridge.startLogcat` returning a `Process` whose `inputStream` replays a fixture log file. Assert detected events match expected list. |
| Integration | `AppViewModel.startCapture` end-to-end | `FakeAdbBridge` scripted with frames + logcat fixture. Run capture, assert `_conclusions.value`, `_events.value`, and `_result.value.filteredAggregates` populated correctly. |
| E2E manual | Real device + AdMob test ad | Record session, verify `#sec-conclusions` rendered, FPS chart bands shown, `(incl. ads: X)` sub-lines present where filtering changed values. |
| E2E manual | iOS device with StoreKit IAP | Trigger an IAP, verify `IosEventDetector` returns the event. |
| Regression | Layer-cache invalidation | Capture with auto-detected ad close → assert `cachedCandidates` invalidated at the precise close moment (improvement over the v4.3.5 K-consecutive-nulls heuristic). |

## iOS-Specific Design

What the Python sidecar **CAN** see without Developer Mode:
- `OsTraceService.syslog()` system-level entries (RunningBoard / SpringBoard / kernel).
- StoreKit-related lines mentioning `com.apple.storekit` subsystem (verify during implementation).
- Foreground app changes via the SpringBoard scanning pattern already in `devices.py:181-239`.

What the sidecar **CANNOT** see without Developer Mode:
- App-level NSLog / os_log entries (where ad SDKs typically log).
- Anything below the `<Notice>` priority threshold.

**Degradation path**:
1. If Developer Mode is detected (already exposed via the sidecar's developer-mode probe), enable full os_log scanning for known ad SDK subsystems (Google-Mobile-Ads, com.unity3d.ads, etc.).
2. Otherwise: detect IAP via `com.apple.storekit` syslog mentions; detect generic "external event" windows via foreground-app-loss (game leaves foreground for >2 s, then returns).
3. Mark all iOS auto-events with `Confidence.MEDIUM` at best.
4. The report header for iOS sessions includes `<div class="info-banner">iOS — detección parcial. Para detección completa de ads, activá Developer Mode.</div>`.

The `ConclusionEngine` runs identically on iOS — it doesn't care about platform, just consumes `MetricsAggregates`. iOS-specific limitation rules (`ios-limited-detection`, `ios-iap-only`) are added to the catalog and only fire when `detectionMode == IOS_PARTIAL`.

## Rollout / Feature Flag

**Recommendation: ON by default, with a settings toggle to disable.**

- New setting: `Settings.autoEventDetectionEnabled: Boolean = true` (under "Captura" section).
- When `false`, `EventDetector` is never instantiated and the report renders the same way as a manual-markers-only session (no `#sec-conclusions`, no `#sec-events`, no dual-view metric cards).
- Toggling mid-session is NOT supported (the toggle reads on `startCapture` only; explained in tooltip).
- The toggle exists as an escape hatch when a specific game's logs misbehave (e.g. spammy verbose Unity DEBUG builds where logcat drops).
- After 2-3 release cycles with no reported issues, the toggle can be removed (deferred decision).

## Migration / Rollout

Not a data migration — additive only. New session JSON exports include `events` + `conclusions` + `filteredAggregates` + `rawAggregates`. Old session JSON files load with those fields defaulting to empty/null and the report falls back to legacy single-view rendering with no conclusions section.

## Open Questions

- [ ] Confirm Meta Audience Network's exact logcat tag during implementation (exploration marked it as "needs in-the-wild verification").
- [ ] Decide whether `ConclusionEngine` rule order should be configurable per-game in a follow-up (e.g. per-game weight overrides) or stay deterministic-by-severity.
- [ ] iOS: confirm `com.apple.storekit` is visible in syslog without Developer Mode on iOS 16/17/18 — needs spike during implementation phase 5.
- [ ] Decide whether to persist the per-event `Confidence` in JSON exports or compute it at load time from `signatureMatched` (favor: persist, costs ~20 bytes per event).
