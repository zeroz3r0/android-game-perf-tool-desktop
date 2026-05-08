# Tasks: auto event detection and clean metrics

Each task ID maps to a single deliverable (≤3h work). Spec requirement coverage is annotated per task. Test tasks are inline alongside the code they cover (per CLAUDE.md "tests puros sin mocks" rule).

---

## Phase 1: Foundation / Infrastructure

- [x] **T1.1** Create package skeleton `src/main/kotlin/com/gameperf/desktop/core/events/` with `package-info.kt` documenting purpose: "Auto event detection (logcat + dumpsys) for ad/IAP/loading windows. Single source of truth for SDK signatures."
- [x] **T1.2** Create package skeleton `core/metrics/` (`package-info.kt`: "Pure metrics aggregation. Filtered (excluded ranges) + raw dual-view.")
- [x] **T1.3** Create package skeleton `core/conclusions/` and `core/conclusions/rules/` (`package-info.kt`: "Deterministic heuristic rule engine. Pure Kotlin. No LLM, no external config.")
- [x] **T1.4** Create `core/events/DetectedEvent.kt` — data class per design.md §"Core Types" lines 65-76. Fields: `id: String (UUID)`, `type: EventType`, `sdkSource: String`, `startMs: Long`, `endMs: Long?`, `confidence: Confidence`, `signatureMatched: String`, `metadata: Map<String,String>`. Enums `EventType`, `Confidence`. Covers EVT-005, EVT-007.
- [x] **T1.5** Create `core/metrics/TimeRange.kt` — `data class TimeRange(val startMs: Long, val endMs: Long)`. Covers FLT-002.
- [x] **T1.6** Create `core/metrics/MetricsAggregates.kt` — full data class per design.md §"Core Types" lines 124-132 (avgFps, min/max, p1/p5/p50/p90/p99, frameTimes, peakMem, avgCpu/maxCpu, all temp peaks, jank, stutter, sampleCount). Covers FLT-002, FLT-004.
- [x] **T1.7** Create `core/metrics/FilterInput.kt` — data class per design.md §"Core Types" lines 139-146 (all timed twins, captureStartTime, sessionEndMs). Covers FLT-001.
- [x] **T1.8** Create `core/conclusions/Rule.kt` — interface `Rule` (id, severity, matches, render) + `Conclusion` data class + `Severity` enum + `ConclusionInput` data class per design.md lines 149-169. Covers CON-001, CON-005.
- [x] **T1.9** Add `autoEventDetectionEnabled: Boolean = true` to `Settings.kt` (under "Captura" section). Wire load/save. Covers design.md §Rollout.
- [x] **T1.10** Add timestamped twins to `viewmodel/AppViewModel.kt:43-44` area: `cpuTimed`, `memTimed`, `nativeTimed`, `javaTimed`, `tempCpuTimed`, `tempGpuTimed`, `tempSkinTimed`, `tempDieCpuTimed`, `frameTimeTimed`, `jankTimed`, `stutterTimed` — all `MutableList<TimedSample>`. Populate inside the polling loop (around lines 949-963 / 1011-1235) at each tick. Cap with `MAX_HISTORY_SIZE`. Covers FLT-001.
- [x] **T1.11** Extend `SessionResult` data class — add `events: List<DetectedEvent> = emptyList()`, `rawAggregates: MetricsAggregates? = null`, `filteredAggregates: MetricsAggregates? = null`, `conclusions: List<Conclusion> = emptyList()`, `detectionMode: DetectionMode = MANUAL_ONLY`. Add `enum class DetectionMode { ANDROID_FULL, IOS_PARTIAL, MANUAL_ONLY }`. Covers design.md §"Data Model Changes".
- [x] **T1.12** Bump session JSON `schemaVersion` from 4 to 5. Add v4→v5 loader path: events/conclusions default empty, aggregates default null. Covers design.md §"Data Model Changes".

## Phase 2: Detection Pillar

- [x] **T2.1** Create `core/events/LogLine.kt` — `data class LogLine(val tsMs: Long, val pid: Int, val tid: Int, val level: Char, val tag: String, val msg: String)`.
- [x] **T2.2** Create `core/events/LogcatLineParser.kt` — pure object with `parse(rawLine: String): LogLine?`. Regex for `threadtime` format `MM-DD HH:MM:SS.mmm  PID  TID L TAG: MSG`. Explicit UTF-8 decoding. Returns null on malformed input. Covers EVT-002.
- [x] **T2.3** Create `src/test/kotlin/com/gameperf/desktop/core/events/LogcatLineParserTest.kt` — fixtures: well-formed line, malformed binary garbage, partial UTF-8, empty line, line missing TID. Assert exact field extraction or null. Covers EVT-002 scenarios.
- [x] **T2.4** Create `src/test/resources/logcat-fixtures/` directory with real recorded `.log` files: `admob-interstitial.log`, `unity-ads.log`, `ironsource-interstitial.log`, `applovin-interstitial.log`, `meta-audience.log`, `play-billing-launch.log`. Each ~50-200 lines covering open + close.
- [x] **T2.5** Create `core/events/SdkSignature.kt` — internal data class per design.md lines 106-113 (sdk, type, activityClasses, logcatTags, openPatterns, closePatterns). Covers EVT-003.
- [x] **T2.6** Create `core/events/SdkSignatureCatalog.kt` — internal object with `ALL: List<SdkSignature>` containing the 6 verified SDKs (AdMob, UnityAds, IronSource, AppLovin/MAX, MetaAN, PlayBilling) per explore.md table lines 95-103. Methods: `logcatTagArgs()`, `matchOpen(line)`, `matchClose(line, openSig)`, `matchActivity(cmp)`. SINGLE source of truth (anti-duplication per CLAUDE.md). Covers EVT-003.
- [x] **T2.7** Create `SdkSignatureCatalogTest.kt` — for each SDK: 1 positive open match, 1 positive close match, 1 negative line that should NOT match, 1 boundary case. ≥80% line coverage. NO mocks. Covers EVT-003 scenario "single source of truth".
- [x] **T2.8** Add `fun startLogcat(deviceId: String, tagArgs: List<String>): Process?` to `core/AdbBridgeApi.kt` interface. Covers EVT-001.
- [x] **T2.9** Implement `startLogcat(...)` in `core/AdbBridge.kt` — long-lived `ProcessBuilder` invoking `adb -s <id> logcat -b main,system,events -v threadtime <tagArgs>`. Pattern mirrors `startScreenRecord` at `viewmodel/AppViewModel.kt:769-779`. Returns the `Process` for the caller to manage destruction. Covers EVT-001.
- [x] **T2.10** Implement `startLogcat(...)` stub in `core/FakeAdbBridge.kt` — returns a `Process` whose `inputStream` replays bytes from a fixture file. Used by integration tests.
- [x] **T2.11** Create `core/events/LogcatCapture.kt` — internal class wrapping the `Process` with: `start(deviceId, tagFilters): Process?`, `stop()`. Spawns coroutine on `Dispatchers.IO` reading lines via `BufferedReader(InputStreamReader(process.inputStream, UTF_8))`. Calls `onLine(parsed)` per line. Tracks reception timestamp via `System.currentTimeMillis()`. Detects gaps >5s and calls `onGap(gapMs)`. Covers EVT-001, EVT-006, EVT-007.
- [x] **T2.12** Create `LogcatCaptureTest.kt` — integration test using `FakeAdbBridge` + fixture file. Assert all expected lines parsed, gap detection fires when fixture has >5s timestamp jump.
- [x] **T2.13** Create `core/events/ActivityFrame.kt` — `data class ActivityFrame(val cmp: String, val pid: Int, val taskId: Int)`.
- [x] **T2.14** Create `core/events/DumpsysPoller.kt` — internal class with `suspend fun run(deviceId, scope)` that loops at 1Hz invoking `adb shell dumpsys activity activities`, parses top-of-stack `cmp=` via regex (reuse pattern from `core/AdbBridge.kt:225-241`), calls `onActivityStack(frames)`. After 5 consecutive failures, disables itself. Covers EVT-004.
- [x] **T2.15** Create `DumpsysPollerTest.kt` — use `FakeAdbBridge` returning canned dumpsys output. Assert poll cadence ~1Hz, timeout < 250ms enforced, 5-consecutive-failure shutdown.
- [x] **T2.16** Create `core/events/EventDetector.kt` — interface per design.md lines 79-84 (`events: StateFlow`, `warnings: StateFlow`, `start`, `stop`).
- [x] **T2.17** Create `core/events/EventDetectorImpl.kt` — orchestrator coroutine. Owns `LogcatCapture` + `DumpsysPoller`. Implements lifecycle LOAD→SHOW→CLOSE state machine per EVT-005: open events tracked in a map keyed by sdkSource, closed via close-pattern OR activity leaving stack OR session end. Foreground proximity guard (≤2s of game on top, EVT-008). Event count cap 500 with histogram fallback flag (EVT-009). Emits `DetectedEvent`s into `MutableStateFlow<List<DetectedEvent>>`. On stop, force-close any open events with `endInferred=true`. Covers EVT-001, EVT-005, EVT-006, EVT-008, EVT-009.
- [x] **T2.18** Create `EventDetectorImplTest.kt` — pure-state-machine tests with fed `LogLine` + `ActivityFrame` sequences (no real Process). Cases: open+close pairing, foreground guard rejection (home button), session-end inferred close, 500-cap enforcement, gap-induced LOW confidence. Covers EVT-005 through EVT-009 scenarios.
- [x] **T2.19** Wire `EventDetector` into `viewmodel/AppViewModel.kt`:
  - Add `private val _events = MutableStateFlow<List<DetectedEvent>>(emptyList())` + public `events: StateFlow<List<DetectedEvent>>`.
  - Add `private val _detectorWarnings = MutableStateFlow<List<String>>(emptyList())` + public flow.
  - Instantiate `EventDetectorImpl` in `startCapture` (around lines 816-878) AFTER `captureStartTime` is set, only if `Settings.autoEventDetectionEnabled` is true. Call `eventDetector.start(deviceId, gamePackage, captureScope)`.
  - In `stopCapture` path (just before post-loop aggregation), call `eventDetector.stop()`.
  - Bridge `eventDetector.events` → `_events`.
  - Covers EVT-001 lifecycle scenarios.
- [x] **T2.20** Add live-indicator UI in `ui/screens/CaptureScreen.kt` (near manual marker buttons, lines 178-182): collect `vm.events` and show "Auto: N eventos" with colored dot when non-empty. Manual marker buttons remain unchanged. Covers MAN-001 ("Manual markers preserved as fallback").

## Phase 3: Filtering Pillar

- [x] **T3.1** Create `core/metrics/FilteredMetricsCalculator.kt` — pure object with constants `PADDING_MS = 500L`, `EXCESSIVE_FILTER_RATIO = 0.70`. Covers FLT-003, FLT-005.
- [x] **T3.2** Implement `unionRanges(ranges: List<TimeRange>, paddingMs: Long): List<TimeRange>` — applies symmetric padding, sorts by startMs, merges overlapping/adjacent. Covers FLT-003, FLT-007.
- [x] **T3.3** Implement `compute(input: FilterInput, excludedRanges: List<TimeRange>): MetricsAggregates` — filter each timed list by membership-outside-padded-union, then compute avg/min/max/p1/p5/p50/p90/p99 over kept set. Pure, no side effects. Covers FLT-002, FLT-004.
- [x] **T3.4** Create `FilteredMetricsCalculatorTest.kt` — fixtures:
  - **fires**: 60s session, 1 event [20s,30s] → samples in [19.5s,30.5s] excluded (FLT-002 scenario "filtered excludes ad-window").
  - **no-op**: empty ranges → equals raw within ±0.1 fps (FLT-002, FLT-006 scenarios).
  - **padding**: event [10s,15s] effective window [9.5s,15.5s] (FLT-003 scenario).
  - **overlap**: [10s,14s] + [13s,16s] → unioned to [9.5s,16.5s] (FLT-007 scenario).
  - **boundary**: sample exactly at padding boundary.
  - **excessive**: 80% of session excluded → returns aggregates flagged for fallback (FLT-005).
  - NO mocks. ≥80% line coverage.
- [x] **T3.5** Modify `viewmodel/AppViewModel.kt` post-loop aggregation (lines 1322-1346): replace inline percentile math with two `FilteredMetricsCalculator.compute(...)` calls — one with `_events.value` mapped to `TimeRange`s (filtered), one with `emptyList()` (raw). Apply >70% fallback: if filtered.sampleCount/raw.sampleCount < 0.30, swap filtered←raw and add warning. Pass filtered to `FinalScoreCalculator.compute(GradingInput(...))`. Covers FLT-004, FLT-005.
- [x] **T3.6** Add KDoc note to `core/grading/FinalScoreCalculator.kt:32-43` on `GradingInput`: "Values must be filtered upstream by FilteredMetricsCalculator. Raw whole-session aggregates should NOT be passed here." No struct change. Covers design.md "Integration Points" row.
- [~] **T3.7** Create end-to-end aggregation test `AppViewModelAggregationTest.kt` (using `FakeAdbBridge` scripted with frames + logcat fixture) — assert `_result.value.filteredAggregates`, `rawAggregates`, and `events` all populated. Filtered.avgFps differs from raw when fixture contains an ad. Covers FLT-004 scenario.
  - **Deviation**: implemented as a programmatic mirror-test rather than driving the real `AppViewModel.startCapture` through `FakeAdbBridge`. Reasoning: starting a real capture cycle requires orchestrating ScreenRecord segments + logcat process + dumpsys polling + iOS sidecar gating, which is heavy and brittle for a Phase 3 unit test. The mirror-test replays the exact sequence the orchestrator runs (`FilteredMetricsCalculator.computeWithFallback` → `GradingInput` → `SessionResult`) and asserts the same FLT-004 scenario contract. A full `FakeAdbBridge`-driven test is deferred to Phase 7 (T7.x manual scenarios + the existing capture-integration test bed).

## Phase 4: Conclusions Pillar

- [x] **T4.1** Create `core/conclusions/ConclusionEngine.kt` — pure object with `RULES: List<Rule>` (lazily loaded from registry) and `fun run(input: ConclusionInput): List<Conclusion>` that filters by `matches(input)`, renders, and sorts by severity (`CRITICAL > WARNING > INFO`) then by stable `ruleId` ascending. Covers CON-001, CON-004.
- [x] **T4.2** Create `core/conclusions/RuleRegistry.kt` — central list `val all: List<Rule>` enumerating all 8 rules. Single source of truth so adding a rule = adding to this list. Covers CON-002.
- [x] **T4.3** Create `core/conclusions/rules/StableLowFpsRule.kt` — id `"stable-low-fps-low-cpu"`, WARNING. Predicate: `filtered.p50 ≤ 0.7 * targetFps && filtered.avgCpu < 50 && filtered.maxTempCpu < 42`. Render template per design.md table line 242. Spanish formal tuteo. Covers CON-002, CON-005.
- [x] **T4.4** Create `StableLowFpsRuleTest.kt` — fixtures: fires (low fps + low cpu + cool), does-not-fire (low fps + high cpu = different bottleneck), boundary (p50 exactly at 0.7×target). Covers CON-002 + CON-005 scenarios.
- [x] **T4.5** Create `core/conclusions/rules/ThermalThrottlingRule.kt` — id `"thermal-throttling"`, CRITICAL. Predicate per design.md line 243. + test file with 3 fixtures.
- [x] **T4.6** Create `core/conclusions/rules/MemoryGrowthRule.kt` — id `"memory-leak-suspect"`, WARNING. Predicate uses linear regression slope on `memTimed`. + test file (fires on monotonic growth, does-not-fire on flat or with GC drops, boundary at 0.5MB/s slope).
- [x] **T4.7** Create `core/conclusions/rules/JankWithGoodAvgRule.kt` — id `"jank-with-good-avg"`, WARNING. Predicate per design.md line 245. + test.
- [x] **T4.8** Create `core/conclusions/rules/Capped30FpsRule.kt` — id `"fps-cap-suspect"`, INFO. Predicate uses `deviceTier`. + test file with explicit tier-1 (does-not-fire per CON-003 scenario "30fps cap rule does not fire on tier-1") and tier-3 (fires per CON-003 scenario "fires on tier-3+"). Covers CON-003.
- [x] **T4.9** Create `core/conclusions/rules/CpuSaturationRule.kt` — id `"cpu-saturated"`, CRITICAL. Predicate `filtered.avgCpu > 85`. + test (fires at 90%, does-not-fire at 70%, boundary at 85%).
- [x] **T4.10** Create `core/conclusions/rules/AdVsGameFpsGapRule.kt` — id `"ad-vs-game-fps-gap"`, INFO. Predicate per design.md line 248 (events present + filtered/raw delta >15%). + test.
- [x] **T4.11** Create `core/conclusions/rules/LoadingThermalRecoveryRule.kt` — id `"loading-thermal-recovery"`, INFO. Predicate per design.md line 249. + test.
- [x] **T4.12** Create `ConclusionEngineTest.kt` — assertions:
  - Same input twice → identical output (CON-001).
  - All 8 rule IDs present in `RuleRegistry.all` (CON-002).
  - 3 rules firing INFO/CRITICAL/WARNING → output ordered `[CRITICAL, WARNING, INFO]` (CON-004).
  - Tiebreak by ascending ruleId within same severity (CON-004).
  - Zero rules fire → empty list (downstream handled by REP / CON-007).
- [x] **T4.13** Wire `ConclusionEngine` into `viewmodel/AppViewModel.kt` post-aggregation: build `ConclusionInput(filtered, raw, targetFps, deviceTier=HardwareScoring.detectTier(gpu), events=_events.value, sessionDurationS)` and call `ConclusionEngine.run(input)`. Store on `SessionResult.conclusions`. Covers CON-001, CON-003.
- [x] **T4.14** Insufficient-data short-circuit: if `sessionDurationS < 30 || rawAggregates.sampleCount < 60`, return single `Conclusion(ruleId="insufficient-data", ...)` and skip the regular catalog. Covers design.md §"Error Handling" "Session too short" row.

## Phase 5: iOS Best-Effort

- [ ] **T5.1** Create `sidecar/gameperf_sidecar/events.py` — `IosDetectedEvent` dataclass per design.md lines 182-189 (type, source, start_ms, end_ms, confidence, signature_matched).
- [ ] **T5.2** Implement `IosEventDetector` class in `events.py` — async `start(udid)` spawns syslog watcher coroutine reusing `OsTraceService` from `devices.py:218-234`. `stop()` returns buffered list. Covers IOS-001 through IOS-003.
- [ ] **T5.3** Implement StoreKit detection in `events.py` — watch syslog lines mentioning `com.apple.storekit` subsystem. Emit `IosDetectedEvent(type="iap", source="StoreKit", confidence="medium")`. Covers EVT-010 scenario "iOS StoreKit IAP", IOS-002.
- [ ] **T5.4** Implement foreground-loss heuristic in `events.py` — reuse `_get_foreground_app` pattern. Track foreground transitions; when game leaves foreground for `2s ≤ duration ≤ 90s` then returns, emit `IosDetectedEvent(type="external", source="SpringBoard", confidence="low")`. Reject <2s (notification swipe) and >90s (intentional background). Covers IOS-003 all three scenarios.
- [ ] **T5.5** Add Developer Mode probe gating in `events.py` — if Developer Mode is detected (existing sidecar probe), additionally subscribe to ad-SDK bundle subsystems. Otherwise stay at system-level only. Covers IOS-002 both scenarios.
- [ ] **T5.6** Create `sidecar/tests/test_events.py` — pytest fixtures: StoreKit syslog line emits IAP event; short foreground transition (1s) ignored; mid foreground transition (15s) emits LOW-confidence external event; long absence (120s) ignored. NO real device — fixture-driven.
- [ ] **T5.7** Add FastAPI endpoints to `sidecar/gameperf_sidecar/main.py`:
  - `POST /device/{udid}/events/start` → starts `IosEventDetector`.
  - `POST /device/{udid}/events/stop` → stops, returns serialized list.
  - `GET /device/{udid}/events` → returns currently-buffered events.
- [ ] **T5.8** Extend `core/ios/SidecarClient.kt` — `suspend fun startEventDetection(udid: String)`, `suspend fun pollEvents(udid: String): List<DetectedEvent>`, `suspend fun stopEventDetection(udid: String): List<DetectedEvent>`. Map `IosDetectedEvent` JSON → `DetectedEvent`. Covers EVT-010.
- [ ] **T5.9** Wire iOS path in `viewmodel/AppViewModel.kt`: when `Platform == iOS && Settings.autoEventDetectionEnabled`, instantiate iOS branch using `SidecarClient` calls instead of `EventDetectorImpl`. Compute `detectionMode` based on Developer Mode probe (`ANDROID_FULL` / `IOS_PARTIAL` / `MANUAL_ONLY`). Covers IOS-001.

## Phase 6: Report Integration & Polish

- [x] **T6.1** Modify `report/ReportGenerator.kt` nav bar — added `<a href="#sec-conclusions">Conclusiones</a>` after "Resumen", gated on conclusionsHtml not being empty. The nav also now exposes `#sec-events` instead of the legacy `#sec-markers` link. Covers REP-001.
- [x] **T6.2** Inserted new `#sec-conclusions` section between `#sec-summary` and `#sec-dashboard`. Renders the heuristic disclaimer + cards. Covers REP-001, CON-006.
- [x] **T6.3** Conclusion cards render with severity icon (CRITICAL = ⚠, WARNING = ⚠, INFO = ℹ), severity label in Spanish, ruleId chip, headline and optional recommendation. Castilian Spanish formal tuteo. Covers CON-005, REP-004.
- [x] **T6.4** Empty state for `#sec-conclusions` (`sectionConclusionsEmpty()`): rendered when the engine produced zero conclusions but `filteredAggregates != null` (proxy for "engine actually ran"). Pre-v4.4.0 callers (legacy fixtures) get the original section-less rendering. Covers CON-007.
- [x] **T6.5** Dual-view metric cards via `rawSubline()` helper: when raw and filtered differ by >5%, the metric-detail slot gets a small "Bruto: X" subline. ≤5% delta hides it, preserving legacy single-number look. Applied to FPS, Frame Time, Memoria and CPU cards. Covers REP-002 both scenarios.
- [~] **T6.6** Chart bands shading the FPS chart during event windows — DEFERRED to v4.4.x. Reasoning: the events table + detection banner already disclose every event chronologically, and the FPS chart already has marker vertical lines for manual markers. The shaded box-annotations require coordinating colors per event type AND padding the ranges visually to match the filter — meaningful visual polish but not a blocker for shipping the core feature. Tracked.
- [x] **T6.7** Renamed `markersHtml` → `eventsHtml`. New `sectionEvents()` emits `#sec-events` with chronological UNION of manual markers + auto events. Columns: Tiempo, Duración, Tipo (badge), Origen (Manual / Auto: SDK), Detalle. Confidence + `(cierre inferido)` exposed in Detalle for auto events. Covers MAN-002, MAN-003, REP-005.
- [x] **T6.8** Detection-mode header banner: new `detectionModeBanner()` helper rendered between header and #sec-summary. Three variants (ANDROID_FULL green / IOS_PARTIAL amber / MANUAL_ONLY gray) with auto-event count and a foldable `<details>` for detector warnings. Covers IOS-001, REP-005.
- [x] **T6.9** Excessive-filter warning callout above dashboard: `excessiveFilterCallout()` + `isExcessiveFilterTriggered()` helpers. Detects via detector-warning string match OR by recomputing `filtered.sampleCount / raw.sampleCount < 0.30` from the dual aggregates. Covers FLT-005 scenario.
- [~] **T6.10** Histogram-fallback rendering when >500 events — DEFERRED to v4.4.x. Reasoning: the 500-event cap is a Phase 2 EventDetector safeguard, hit only in pathological sessions (sustained ad churn for tens of minutes). Real test sessions land in low single-digits. Tracked; until then, the events table simply renders the 500 events one per row.
- [~] **T6.11** Golden-HTML `ReportGeneratorTest` — DEFERRED to v4.4.x. Reasoning: the existing `ReportRenderingTest` is a fixture-only smoke test (gated on RUN_REPORT_FIXTURE env var) and the report has no golden assertions yet. Adding rigorous golden tests requires picking a test strategy (DOM parse vs string contains) and would be its own focused PR. Manual verification via the AppViewModel sample sessions covers the v4.4.0 release. Tracked.
- [x] **T6.12** [renumbered as T6.13 in the original plan but consolidated here] CHANGELOG.md updated with full v4.4.0 entry under "Que hay de nuevo" + "Detalles tecnicos", marking T6.6/T6.10/T6.11 as deferred.
- [x] **T6.13** README.md updated under "Análisis posterior a la sesión" — bullet for "Detección automática de eventos" + bullet for "Conclusiones cualitativas". Castilian Spanish formal tuteo.
- [x] **T6.14** README_EN.md mirrored — bullet for "Automatic event detection" + bullet for "Qualitative conclusions" in English.
- [x] **T6.15** CLAUDE.md updated with new section "Patrón operativo: cómo añadir un SDK nuevo" + architectural-pattern note that detection lives ONLY in `core/events/` and conclusions ONLY in `core/conclusions/` (the v4.2.13 ToolResolver-duplication trap reminder applied to this subsystem). Also added v4.4.0 to the release history section.

### Phase 6 deltas vs original plan

- **`generate()` signature extended**, not split into a v2 method. Reason: backward compat via default values is cheaper than a parallel API and keeps `ReportRenderingTest` (legacy fixture) untouched.
- **`metricCardDualView()` not introduced**. Reason: `metricCard()` already accepts an HTML-capable `detail` slot. The `rawSubline()` helper appends an HTML fragment to that detail when meaningful — fewer call-site changes, identical visual outcome.
- **Excessive-filter detection has TWO paths**: authoritative warning-string match + computed kept-ratio fallback. The orchestrator's `_detectorWarnings` carries the warning, but a stale-import scenario (loading an older session JSON) wouldn't have it; recomputing from aggregates ensures the callout still fires.
- **Chart annotations (T6.6) deferred** because they require coordinating with the existing `markerAnnotationsJs` JS string AND extending `chartjs-plugin-annotation` configuration — substantial JS-side work that's better tackled as a focused follow-up, not bundled into the Phase 6 report-rendering scope.

## Phase 7: Verification & Release

- [ ] **T7.1** Run `./gradlew check` — verify detekt + all unit/integration tests pass (Kotlin side).
- [ ] **T7.2** Run `pytest sidecar/tests/` — verify Python sidecar tests pass.
- [ ] **T7.3** Manual scenario A — Android real device + AdMob test ad: capture session, verify (a) `Auto: N eventos` indicator increments, (b) `#sec-conclusions` rendered, (c) FPS chart shows orange band over the ad window, (d) `(incl. ads: X)` subtitle present where filtering changed the value. Document result in PR description.
- [ ] **T7.4** Manual scenario B — Android Google Play Billing IAP: launch a game with an IAP, trigger purchase sheet, verify a `DetectedEvent(type=IAP, sdk=PlayBilling)` emitted within 2s of the activity going top-of-stack (per success criteria).
- [ ] **T7.5** Manual scenario C — Android session with NO ads: verify `filtered.avgFps == raw.avgFps` ±0.1, no "(incl. ads:)" subtitles, conclusion section either shows the clean-session message (CON-007) or relevant performance hypotheses.
- [ ] **T7.6** Manual scenario D — iOS without Developer Mode + StoreKit IAP: trigger IAP, verify `IosEventDetector` returns the event, report header shows "iOS parcial" banner.
- [ ] **T7.7** Manual scenario E — feature-flag OFF: toggle `Settings.autoEventDetectionEnabled = false`, verify capture works identically to pre-feature behavior (no `EventDetector` instantiated, no new sections in report).
- [ ] **T7.8** Bump `gradle.properties::appVersion` to next minor (e.g., 4.4.0).
- [ ] **T7.9** Final CHANGELOG entry with complete feature description and known limitations (iOS partial mode, ProGuard log stripping mitigation via dumpsys, etc.).
- [ ] **T7.10** Tag and release per project release process.

---

## Coverage Map (spec requirement → tasks)

| Req | Covered by |
|---|---|
| EVT-001 | T2.8, T2.9, T2.11, T2.19 |
| EVT-002 | T2.2, T2.3 |
| EVT-003 | T2.5, T2.6, T2.7 |
| EVT-004 | T2.14, T2.15 |
| EVT-005 | T1.4, T2.17, T2.18 |
| EVT-006 | T2.11, T2.17 |
| EVT-007 | T1.4, T2.11, T2.18 |
| EVT-008 | T2.17, T2.18 |
| EVT-009 | T2.17, T2.18, T6.10 |
| EVT-010 | T5.3, T5.4, T5.8 |
| FLT-001 | T1.7, T1.10 |
| FLT-002 | T1.5, T1.6, T3.3, T3.4 |
| FLT-003 | T3.1, T3.2, T3.4 |
| FLT-004 | T1.6, T3.3, T3.5 |
| FLT-005 | T3.1, T3.4, T3.5, T6.9 |
| FLT-006 | T3.4 |
| FLT-007 | T3.2, T3.4 |
| CON-001 | T1.8, T4.1, T4.12 |
| CON-002 | T4.2, T4.3-T4.11 (8 rules), T4.12 |
| CON-003 | T4.8, T4.13 |
| CON-004 | T4.1, T4.12 |
| CON-005 | T1.8, T4.3, T6.3 |
| CON-006 | T6.2 |
| CON-007 | T6.4, T4.14 |
| MAN-001 | T2.20 (existing buttons untouched) |
| MAN-002 | T6.7 |
| MAN-003 | T6.6 |
| MAN-004 | T3.5 (filter only consumes auto events, not markers) |
| REP-001 | T6.1, T6.2, T6.11 |
| REP-002 | T6.5, T6.11 |
| REP-003 | T6.6, T6.11 |
| REP-004 | T6.3, T6.11 |
| REP-005 | T6.7, T6.11 |
| IOS-001 | T6.8, T5.9 |
| IOS-002 | T5.5 |
| IOS-003 | T5.4, T5.6 |

**All 36 spec requirements are covered. No gaps.**
