# Tasks: auto event detection and clean metrics

Each task ID maps to a single deliverable (≤3h work). Spec requirement coverage is annotated per task. Test tasks are inline alongside the code they cover (per CLAUDE.md "tests puros sin mocks" rule).

---

## Phase 1: Foundation / Infrastructure

- [ ] **T1.1** Create package skeleton `src/main/kotlin/com/gameperf/desktop/core/events/` with `package-info.kt` documenting purpose: "Auto event detection (logcat + dumpsys) for ad/IAP/loading windows. Single source of truth for SDK signatures."
- [ ] **T1.2** Create package skeleton `core/metrics/` (`package-info.kt`: "Pure metrics aggregation. Filtered (excluded ranges) + raw dual-view.")
- [ ] **T1.3** Create package skeleton `core/conclusions/` and `core/conclusions/rules/` (`package-info.kt`: "Deterministic heuristic rule engine. Pure Kotlin. No LLM, no external config.")
- [ ] **T1.4** Create `core/events/DetectedEvent.kt` — data class per design.md §"Core Types" lines 65-76. Fields: `id: String (UUID)`, `type: EventType`, `sdkSource: String`, `startMs: Long`, `endMs: Long?`, `confidence: Confidence`, `signatureMatched: String`, `metadata: Map<String,String>`. Enums `EventType`, `Confidence`. Covers EVT-005, EVT-007.
- [ ] **T1.5** Create `core/metrics/TimeRange.kt` — `data class TimeRange(val startMs: Long, val endMs: Long)`. Covers FLT-002.
- [ ] **T1.6** Create `core/metrics/MetricsAggregates.kt` — full data class per design.md §"Core Types" lines 124-132 (avgFps, min/max, p1/p5/p50/p90/p99, frameTimes, peakMem, avgCpu/maxCpu, all temp peaks, jank, stutter, sampleCount). Covers FLT-002, FLT-004.
- [ ] **T1.7** Create `core/metrics/FilterInput.kt` — data class per design.md §"Core Types" lines 139-146 (all timed twins, captureStartTime, sessionEndMs). Covers FLT-001.
- [ ] **T1.8** Create `core/conclusions/Rule.kt` — interface `Rule` (id, severity, matches, render) + `Conclusion` data class + `Severity` enum + `ConclusionInput` data class per design.md lines 149-169. Covers CON-001, CON-005.
- [ ] **T1.9** Add `autoEventDetectionEnabled: Boolean = true` to `Settings.kt` (under "Captura" section). Wire load/save. Covers design.md §Rollout.
- [ ] **T1.10** Add timestamped twins to `viewmodel/AppViewModel.kt:43-44` area: `cpuTimed`, `memTimed`, `nativeTimed`, `javaTimed`, `tempCpuTimed`, `tempGpuTimed`, `tempSkinTimed`, `tempDieCpuTimed`, `frameTimeTimed`, `jankTimed`, `stutterTimed` — all `MutableList<TimedSample>`. Populate inside the polling loop (around lines 949-963 / 1011-1235) at each tick. Cap with `MAX_HISTORY_SIZE`. Covers FLT-001.
- [ ] **T1.11** Extend `SessionResult` data class — add `events: List<DetectedEvent> = emptyList()`, `rawAggregates: MetricsAggregates? = null`, `filteredAggregates: MetricsAggregates? = null`, `conclusions: List<Conclusion> = emptyList()`, `detectionMode: DetectionMode = MANUAL_ONLY`. Add `enum class DetectionMode { ANDROID_FULL, IOS_PARTIAL, MANUAL_ONLY }`. Covers design.md §"Data Model Changes".
- [ ] **T1.12** Bump session JSON `schemaVersion` from 4 to 5. Add v4→v5 loader path: events/conclusions default empty, aggregates default null. Covers design.md §"Data Model Changes".

## Phase 2: Detection Pillar

- [ ] **T2.1** Create `core/events/LogLine.kt` — `data class LogLine(val tsMs: Long, val pid: Int, val tid: Int, val level: Char, val tag: String, val msg: String)`.
- [ ] **T2.2** Create `core/events/LogcatLineParser.kt` — pure object with `parse(rawLine: String): LogLine?`. Regex for `threadtime` format `MM-DD HH:MM:SS.mmm  PID  TID L TAG: MSG`. Explicit UTF-8 decoding. Returns null on malformed input. Covers EVT-002.
- [ ] **T2.3** Create `src/test/kotlin/com/gameperf/desktop/core/events/LogcatLineParserTest.kt` — fixtures: well-formed line, malformed binary garbage, partial UTF-8, empty line, line missing TID. Assert exact field extraction or null. Covers EVT-002 scenarios.
- [ ] **T2.4** Create `src/test/resources/logcat-fixtures/` directory with real recorded `.log` files: `admob-interstitial.log`, `unity-ads.log`, `ironsource-interstitial.log`, `applovin-interstitial.log`, `meta-audience.log`, `play-billing-launch.log`. Each ~50-200 lines covering open + close.
- [ ] **T2.5** Create `core/events/SdkSignature.kt` — internal data class per design.md lines 106-113 (sdk, type, activityClasses, logcatTags, openPatterns, closePatterns). Covers EVT-003.
- [ ] **T2.6** Create `core/events/SdkSignatureCatalog.kt` — internal object with `ALL: List<SdkSignature>` containing the 6 verified SDKs (AdMob, UnityAds, IronSource, AppLovin/MAX, MetaAN, PlayBilling) per explore.md table lines 95-103. Methods: `logcatTagArgs()`, `matchOpen(line)`, `matchClose(line, openSig)`, `matchActivity(cmp)`. SINGLE source of truth (anti-duplication per CLAUDE.md). Covers EVT-003.
- [ ] **T2.7** Create `SdkSignatureCatalogTest.kt` — for each SDK: 1 positive open match, 1 positive close match, 1 negative line that should NOT match, 1 boundary case. ≥80% line coverage. NO mocks. Covers EVT-003 scenario "single source of truth".
- [ ] **T2.8** Add `fun startLogcat(deviceId: String, tagArgs: List<String>): Process?` to `core/AdbBridgeApi.kt` interface. Covers EVT-001.
- [ ] **T2.9** Implement `startLogcat(...)` in `core/AdbBridge.kt` — long-lived `ProcessBuilder` invoking `adb -s <id> logcat -b main,system,events -v threadtime <tagArgs>`. Pattern mirrors `startScreenRecord` at `viewmodel/AppViewModel.kt:769-779`. Returns the `Process` for the caller to manage destruction. Covers EVT-001.
- [ ] **T2.10** Implement `startLogcat(...)` stub in `core/FakeAdbBridge.kt` — returns a `Process` whose `inputStream` replays bytes from a fixture file. Used by integration tests.
- [ ] **T2.11** Create `core/events/LogcatCapture.kt` — internal class wrapping the `Process` with: `start(deviceId, tagFilters): Process?`, `stop()`. Spawns coroutine on `Dispatchers.IO` reading lines via `BufferedReader(InputStreamReader(process.inputStream, UTF_8))`. Calls `onLine(parsed)` per line. Tracks reception timestamp via `System.currentTimeMillis()`. Detects gaps >5s and calls `onGap(gapMs)`. Covers EVT-001, EVT-006, EVT-007.
- [ ] **T2.12** Create `LogcatCaptureTest.kt` — integration test using `FakeAdbBridge` + fixture file. Assert all expected lines parsed, gap detection fires when fixture has >5s timestamp jump.
- [ ] **T2.13** Create `core/events/ActivityFrame.kt` — `data class ActivityFrame(val cmp: String, val pid: Int, val taskId: Int)`.
- [ ] **T2.14** Create `core/events/DumpsysPoller.kt` — internal class with `suspend fun run(deviceId, scope)` that loops at 1Hz invoking `adb shell dumpsys activity activities`, parses top-of-stack `cmp=` via regex (reuse pattern from `core/AdbBridge.kt:225-241`), calls `onActivityStack(frames)`. After 5 consecutive failures, disables itself. Covers EVT-004.
- [ ] **T2.15** Create `DumpsysPollerTest.kt` — use `FakeAdbBridge` returning canned dumpsys output. Assert poll cadence ~1Hz, timeout < 250ms enforced, 5-consecutive-failure shutdown.
- [ ] **T2.16** Create `core/events/EventDetector.kt` — interface per design.md lines 79-84 (`events: StateFlow`, `warnings: StateFlow`, `start`, `stop`).
- [ ] **T2.17** Create `core/events/EventDetectorImpl.kt` — orchestrator coroutine. Owns `LogcatCapture` + `DumpsysPoller`. Implements lifecycle LOAD→SHOW→CLOSE state machine per EVT-005: open events tracked in a map keyed by sdkSource, closed via close-pattern OR activity leaving stack OR session end. Foreground proximity guard (≤2s of game on top, EVT-008). Event count cap 500 with histogram fallback flag (EVT-009). Emits `DetectedEvent`s into `MutableStateFlow<List<DetectedEvent>>`. On stop, force-close any open events with `endInferred=true`. Covers EVT-001, EVT-005, EVT-006, EVT-008, EVT-009.
- [ ] **T2.18** Create `EventDetectorImplTest.kt` — pure-state-machine tests with fed `LogLine` + `ActivityFrame` sequences (no real Process). Cases: open+close pairing, foreground guard rejection (home button), session-end inferred close, 500-cap enforcement, gap-induced LOW confidence. Covers EVT-005 through EVT-009 scenarios.
- [ ] **T2.19** Wire `EventDetector` into `viewmodel/AppViewModel.kt`:
  - Add `private val _events = MutableStateFlow<List<DetectedEvent>>(emptyList())` + public `events: StateFlow<List<DetectedEvent>>`.
  - Add `private val _detectorWarnings = MutableStateFlow<List<String>>(emptyList())` + public flow.
  - Instantiate `EventDetectorImpl` in `startCapture` (around lines 816-878) AFTER `captureStartTime` is set, only if `Settings.autoEventDetectionEnabled` is true. Call `eventDetector.start(deviceId, gamePackage, captureScope)`.
  - In `stopCapture` path (just before post-loop aggregation), call `eventDetector.stop()`.
  - Bridge `eventDetector.events` → `_events`.
  - Covers EVT-001 lifecycle scenarios.
- [ ] **T2.20** Add live-indicator UI in `ui/screens/CaptureScreen.kt` (near manual marker buttons, lines 178-182): collect `vm.events` and show "Auto: N eventos" with colored dot when non-empty. Manual marker buttons remain unchanged. Covers MAN-001 ("Manual markers preserved as fallback").

## Phase 3: Filtering Pillar

- [ ] **T3.1** Create `core/metrics/FilteredMetricsCalculator.kt` — pure object with constants `PADDING_MS = 500L`, `EXCESSIVE_FILTER_RATIO = 0.70`. Covers FLT-003, FLT-005.
- [ ] **T3.2** Implement `unionRanges(ranges: List<TimeRange>, paddingMs: Long): List<TimeRange>` — applies symmetric padding, sorts by startMs, merges overlapping/adjacent. Covers FLT-003, FLT-007.
- [ ] **T3.3** Implement `compute(input: FilterInput, excludedRanges: List<TimeRange>): MetricsAggregates` — filter each timed list by membership-outside-padded-union, then compute avg/min/max/p1/p5/p50/p90/p99 over kept set. Pure, no side effects. Covers FLT-002, FLT-004.
- [ ] **T3.4** Create `FilteredMetricsCalculatorTest.kt` — fixtures:
  - **fires**: 60s session, 1 event [20s,30s] → samples in [19.5s,30.5s] excluded (FLT-002 scenario "filtered excludes ad-window").
  - **no-op**: empty ranges → equals raw within ±0.1 fps (FLT-002, FLT-006 scenarios).
  - **padding**: event [10s,15s] effective window [9.5s,15.5s] (FLT-003 scenario).
  - **overlap**: [10s,14s] + [13s,16s] → unioned to [9.5s,16.5s] (FLT-007 scenario).
  - **boundary**: sample exactly at padding boundary.
  - **excessive**: 80% of session excluded → returns aggregates flagged for fallback (FLT-005).
  - NO mocks. ≥80% line coverage.
- [ ] **T3.5** Modify `viewmodel/AppViewModel.kt` post-loop aggregation (lines 1322-1346): replace inline percentile math with two `FilteredMetricsCalculator.compute(...)` calls — one with `_events.value` mapped to `TimeRange`s (filtered), one with `emptyList()` (raw). Apply >70% fallback: if filtered.sampleCount/raw.sampleCount < 0.30, swap filtered←raw and add warning. Pass filtered to `FinalScoreCalculator.compute(GradingInput(...))`. Covers FLT-004, FLT-005.
- [ ] **T3.6** Add KDoc note to `core/grading/FinalScoreCalculator.kt:32-43` on `GradingInput`: "Values must be filtered upstream by FilteredMetricsCalculator. Raw whole-session aggregates should NOT be passed here." No struct change. Covers design.md "Integration Points" row.
- [ ] **T3.7** Create end-to-end aggregation test `AppViewModelAggregationTest.kt` (using `FakeAdbBridge` scripted with frames + logcat fixture) — assert `_result.value.filteredAggregates`, `rawAggregates`, and `events` all populated. Filtered.avgFps differs from raw when fixture contains an ad. Covers FLT-004 scenario.

## Phase 4: Conclusions Pillar

- [ ] **T4.1** Create `core/conclusions/ConclusionEngine.kt` — pure object with `RULES: List<Rule>` (lazily loaded from registry) and `fun run(input: ConclusionInput): List<Conclusion>` that filters by `matches(input)`, renders, and sorts by severity (`CRITICAL > WARNING > INFO`) then by stable `ruleId` ascending. Covers CON-001, CON-004.
- [ ] **T4.2** Create `core/conclusions/RuleRegistry.kt` — central list `val all: List<Rule>` enumerating all 8 rules. Single source of truth so adding a rule = adding to this list. Covers CON-002.
- [ ] **T4.3** Create `core/conclusions/rules/StableLowFpsRule.kt` — id `"stable-low-fps-low-cpu"`, WARNING. Predicate: `filtered.p50 ≤ 0.7 * targetFps && filtered.avgCpu < 50 && filtered.maxTempCpu < 42`. Render template per design.md table line 242. Spanish formal tuteo. Covers CON-002, CON-005.
- [ ] **T4.4** Create `StableLowFpsRuleTest.kt` — fixtures: fires (low fps + low cpu + cool), does-not-fire (low fps + high cpu = different bottleneck), boundary (p50 exactly at 0.7×target). Covers CON-002 + CON-005 scenarios.
- [ ] **T4.5** Create `core/conclusions/rules/ThermalThrottlingRule.kt` — id `"thermal-throttling"`, CRITICAL. Predicate per design.md line 243. + test file with 3 fixtures.
- [ ] **T4.6** Create `core/conclusions/rules/MemoryGrowthRule.kt` — id `"memory-leak-suspect"`, WARNING. Predicate uses linear regression slope on `memTimed`. + test file (fires on monotonic growth, does-not-fire on flat or with GC drops, boundary at 0.5MB/s slope).
- [ ] **T4.7** Create `core/conclusions/rules/JankWithGoodAvgRule.kt` — id `"jank-with-good-avg"`, WARNING. Predicate per design.md line 245. + test.
- [ ] **T4.8** Create `core/conclusions/rules/Capped30FpsRule.kt` — id `"fps-cap-suspect"`, INFO. Predicate uses `deviceTier`. + test file with explicit tier-1 (does-not-fire per CON-003 scenario "30fps cap rule does not fire on tier-1") and tier-3 (fires per CON-003 scenario "fires on tier-3+"). Covers CON-003.
- [ ] **T4.9** Create `core/conclusions/rules/CpuSaturationRule.kt` — id `"cpu-saturated"`, CRITICAL. Predicate `filtered.avgCpu > 85`. + test (fires at 90%, does-not-fire at 70%, boundary at 85%).
- [ ] **T4.10** Create `core/conclusions/rules/AdVsGameFpsGapRule.kt` — id `"ad-vs-game-fps-gap"`, INFO. Predicate per design.md line 248 (events present + filtered/raw delta >15%). + test.
- [ ] **T4.11** Create `core/conclusions/rules/LoadingThermalRecoveryRule.kt` — id `"loading-thermal-recovery"`, INFO. Predicate per design.md line 249. + test.
- [ ] **T4.12** Create `ConclusionEngineTest.kt` — assertions:
  - Same input twice → identical output (CON-001).
  - All 8 rule IDs present in `RuleRegistry.all` (CON-002).
  - 3 rules firing INFO/CRITICAL/WARNING → output ordered `[CRITICAL, WARNING, INFO]` (CON-004).
  - Tiebreak by ascending ruleId within same severity (CON-004).
  - Zero rules fire → empty list (downstream handled by REP / CON-007).
- [ ] **T4.13** Wire `ConclusionEngine` into `viewmodel/AppViewModel.kt` post-aggregation: build `ConclusionInput(filtered, raw, targetFps, deviceTier=HardwareScoring.detectTier(gpu), events=_events.value, sessionDurationS)` and call `ConclusionEngine.run(input)`. Store on `SessionResult.conclusions`. Covers CON-001, CON-003.
- [ ] **T4.14** Insufficient-data short-circuit: if `sessionDurationS < 30 || rawAggregates.sampleCount < 60`, return single `Conclusion(ruleId="insufficient-data", ...)` and skip the regular catalog. Covers design.md §"Error Handling" "Session too short" row.

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

- [ ] **T6.1** Modify `report/ReportGenerator.kt` nav bar (lines 232-245): insert `<a href="#sec-conclusions">Conclusiones</a>` after "Resumen". Covers REP-001.
- [ ] **T6.2** Insert new `#sec-conclusions` section between `#sec-summary` (line 265) and `#sec-dashboard` (line 296). Render disclaimer "Conclusiones generadas por reglas heurísticas — interpreta estos resultados como hipótesis, no como diagnóstico definitivo." Covers REP-001, CON-006.
- [ ] **T6.3** Render conclusion cards in `#sec-conclusions` — one per `Conclusion`: severity icon (CRITICAL=⚠/WARNING=!/INFO=ℹ), headline, optional recommendation. Castilian Spanish formal tuteo per REP-004. Covers CON-005, REP-004.
- [ ] **T6.4** Empty-state in `#sec-conclusions` — when zero rules fired (or insufficient-data), render "No se detectaron problemas heurísticos significativos en esta sesión." Covers CON-007.
- [ ] **T6.5** Modify `report/ReportGenerator.kt:296-327` (metrics-dashboard) — each `metricCard(...)` accepts `subLineRaw: String?`. When `filtered.field` and `raw.field` differ by >5%, sub-line shows "(incl. ads: X)". When ≤5%, hide sub-line entirely. Covers REP-002 both scenarios.
- [ ] **T6.6** Modify `report/ReportGenerator.kt:124-130` (`markerAnnotationsJs`) — keep manual-marker vertical lines. Add `eventBandsJs` block emitting `chartjs-plugin-annotation` `box`-type annotations for each padded excluded range. Color: orange for INTERSTITIAL/REWARDED_VIDEO, blue for IAP, gray for LOADING. Covers REP-003, MAN-003.
- [ ] **T6.7** Rename `markersHtml` (lines 133-157) to `eventsHtml` and emit `#sec-events` containing UNION of `markers` + `events`, with columns exactly `Tipo`, `Fuente`, `Inicio`, `Fin`, `Duración`. Source values: "Manual" / "Auto: AdMob" / "Auto: Unity Ads" / etc. Covers MAN-002, REP-005.
- [ ] **T6.8** Detection-mode header banner in `ReportGenerator.kt` — `<div class="detection-mode-banner">` rendering one of: "Detección automática (Android completa)" / "Detección automática (Android completa, iOS parcial)" with expandable note "iOS detección parcial. Para detección completa de ads, activá Developer Mode." / "Detección manual (auto-detección desactivada)". Covers IOS-001.
- [ ] **T6.9** Excessive-filter warning banner — when fallback was triggered (>70% excluded), emit prominent `<div class="warning-banner">Más del 70% de la sesión fue excluida por eventos detectados — los promedios se calculan sobre la sesión completa</div>` above the dashboard. Covers FLT-005 scenario.
- [ ] **T6.10** Histogram-fallback rendering — when `events.size == 500` and the cap was hit, replace per-event listing with histogram aggregation (count per SDK) + disclosure "Más de 500 eventos detectados — vista resumida". Covers EVT-009 scenario.
- [ ] **T6.11** Create `ReportGeneratorTest.kt` (or extend existing) — golden-HTML assertions: `#sec-conclusions` between `#sec-summary` and `#sec-dashboard` (REP-001), tuteo verb "interpreta" not "interpretá" (REP-004), columns of `#sec-events` exactly `Tipo|Fuente|Inicio|Fin|Duración` (REP-005), small delta hides "(incl. ads:)" subtitle (REP-002), large delta shows it (REP-002), orange band for ad event (REP-003).
- [ ] **T6.12** Update `CHANGELOG.md` — new entry under next minor version describing auto event detection, dual-view metrics, conclusions section, iOS best-effort.
- [ ] **T6.13** Update `README.md` (Castilian Spanish formal tuteo) — add section "Detección automática de eventos" describing the feature, the toggle, and Android/iOS capability split.
- [ ] **T6.14** Update `README_EN.md` mirroring the new section in English.
- [ ] **T6.15** Update `CLAUDE.md` — add architectural pattern note: "Event detection lives ONLY in `core/events/`. Conclusions ONLY in `core/conclusions/`. Never hand-roll detection or rules outside these packages. `SDKSignatureCatalog` is the single source of truth — extending = adding to its `ALL` list (no parallel definitions)." This is the v4.2.13 ToolResolver-duplication-trap reminder applied to this subsystem.

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
