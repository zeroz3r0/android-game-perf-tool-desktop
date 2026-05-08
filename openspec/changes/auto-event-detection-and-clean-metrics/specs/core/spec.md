# Delta for Core — Auto Event Detection and Clean Metrics

This delta specifies the requirements for replacing the manual marker workflow with automatic ad/IAP/loading event detection, the dual-view filtered/raw metrics aggregation, the heuristic conclusion engine, and the related report integration. See `explore.md` for context, alternatives considered, and risk analysis.

Conventions:
- Requirement IDs are stable and code-referenceable. They map directly to test names.
- Requirement statements use EARS keywords (SHALL, MUST, WHEN, WHILE, WHERE, IF/THEN).
- Scenarios use Given/When/Then for testability.
- User-facing text in scenarios is in formal Castilian Spanish with **tuteo** per project convention.

---

## ADDED Requirements

## 1. Event Detection

### Requirement: EVT-001 — Logcat streaming capture

The system SHALL run a single long-running `adb logcat` child process per Android capture session and SHALL read its stdout line-by-line on a dedicated `Dispatchers.IO` coroutine.

#### Scenario: logcat process starts with capture

- GIVEN the user starts an Android capture session
- WHEN `AppViewModel.startCapture(...)` initializes the event detector
- THEN `EventDetector` MUST spawn `adb -s <id> logcat -b main,system,events -v threadtime AdActivity:D 'Ads:D' 'BillingClient:D' '*:S'` (or equivalent tag-filtered invocation)
- AND the process MUST be tracked so it can be terminated when capture stops

#### Scenario: logcat process terminates with capture

- GIVEN an Android capture session is running with `EventDetector` active
- WHEN the user stops the capture
- THEN `EventDetector.stop()` MUST call `process.destroyForcibly()` on the logcat child
- AND the line-reading coroutine MUST exit cleanly within 1 s
- AND no orphan `adb logcat` process MUST remain on the desktop

---

### Requirement: EVT-002 — Logcat line parsing

The system SHALL parse each logcat line using the `threadtime` format `MM-DD HH:MM:SS.mmm  PID  TID L TAG: MESSAGE` with explicit UTF-8 decoding.

#### Scenario: well-formed line is parsed

- GIVEN a logcat line `01-15 14:23:45.678  1234  5678 D AdActivity: onAdShown`
- WHEN `LogcatLineParser.parse(line)` is invoked
- THEN it MUST return a `LogcatLine` carrying `timestampMs`, `pid=1234`, `tid=5678`, `level='D'`, `tag="AdActivity"`, `message="onAdShown"`

#### Scenario: malformed line is skipped without crashing

- GIVEN a logcat line that does not match the expected regex (e.g. binary garbage, partial UTF-8)
- WHEN `LogcatLineParser.parse(line)` is invoked
- THEN it MUST return `null`
- AND the detector loop MUST continue reading subsequent lines

---

### Requirement: EVT-003 — SDK signature matching

The system SHALL match parsed logcat lines and `dumpsys activity` output against a centralized `SDKSignatures` table that covers, at minimum, Google AdMob, Unity Ads, IronSource, AppLovin/MAX, Meta Audience Network, and Google Play Billing.

#### Scenario: AdMob interstitial is recognized

- GIVEN a logcat line tagged `AdActivity` containing `"onAdShown"` arrives at relative session time T
- WHEN `EventDetector` evaluates the line against `SDKSignatures`
- THEN it MUST emit a `DetectedEvent(type=INTERSTITIAL, sdk=ADMOB, startMs=T, source=LOGCAT_TAG)`

#### Scenario: Google Play Billing IAP is recognized via dumpsys

- GIVEN `dumpsys activity activities` reports `cmp=com.android.billingclient.api.ProxyBillingActivity` on top of the stack
- WHEN the dumpsys poller next runs
- THEN it MUST emit a `DetectedEvent(type=IAP, sdk=PLAY_BILLING, startMs=T, source=DUMPSYS_ACTIVITY)`

#### Scenario: signature table is the single source of truth

- GIVEN the codebase
- WHEN any module needs the activity classes or log tags for a given SDK
- THEN it MUST read from `core/events/SDKSignatures.kt`
- AND no duplicate signature definitions MUST exist elsewhere (per CLAUDE.md anti-duplication rule)

---

### Requirement: EVT-004 — Dumpsys polling at 1 Hz

The system SHALL poll `dumpsys activity activities` once per second on a coroutine sibling to the 500 ms metrics loop, and SHALL use the result to corroborate logcat detections and detect activity launches even when SDK logs are stripped.

#### Scenario: dumpsys catches an ad activity when logs are silent

- GIVEN a release build of the game has stripped AdMob log statements via R8
- WHEN AdMob's `AdActivity` is launched and reaches the top of the activity stack
- THEN within 1 s the dumpsys poller MUST observe `cmp=com.google.android.gms.ads.AdActivity`
- AND `EventDetector` MUST emit a `DetectedEvent(type=INTERSTITIAL, sdk=ADMOB, source=DUMPSYS_ACTIVITY)` even with no logcat hit

#### Scenario: dumpsys cost stays within budget

- GIVEN the dumpsys poller is active during a capture session
- WHEN measuring poll latency
- THEN each `dumpsys activity activities` shell-out MUST complete in less than 250 ms on tier-3+ devices
- AND it MUST NOT delay or skip metric polling ticks

---

### Requirement: EVT-005 — Event lifecycle and end correlation

The system SHALL track each detected event through the lifecycle states `LOAD → SHOW → CLOSE` and SHALL emit `endMs` only when an explicit close signal is observed (logcat close tag OR the activity leaving the top of the stack).

#### Scenario: interstitial close is matched to its open

- GIVEN a `DetectedEvent(type=INTERSTITIAL, sdk=ADMOB, startMs=T0, endMs=null)` is open
- WHEN a logcat line matching the AdMob close pattern (`onAdDismissed`, `onAdClosed`) arrives at T1, OR the `AdActivity` leaves the activity stack at T1
- THEN the event's `endMs` MUST be set to T1
- AND the event MUST transition to state `CLOSED`

#### Scenario: still-open event at session end

- GIVEN a `DetectedEvent` is in state `SHOW` when the user stops the capture
- WHEN `EventDetector.stop()` is called
- THEN any open event MUST be closed with `endMs = sessionEndMs`
- AND it MUST be flagged with `endInferred = true` so the report can disclose the inference

---

### Requirement: EVT-006 — Time correlation with FPS samples

The system SHALL use the desktop's reception timestamp of each logcat line as the reference clock for `startMs`/`endMs`, consistent with `captureStartTime` and `LiveMetrics.elapsed`.

#### Scenario: device-clock drift does not affect correlation

- GIVEN the device clock and desktop clock differ by 3 s
- WHEN `EventDetector` records `startMs` for a detected event
- THEN it MUST use `System.currentTimeMillis() - captureStartTime` measured on the desktop at line reception
- AND it MUST NOT use the in-line `MM-DD HH:MM:SS.mmm` device timestamp for correlation

---

### Requirement: EVT-007 — Buffer drop detection

IF the gap between two consecutive received logcat lines exceeds 5 seconds while the capture is active, THEN the system SHALL log a warning and SHALL flag any `DetectedEvent` whose range overlaps the gap with `confidence = LOW`.

#### Scenario: gap > 5 s is reported in the session

- GIVEN logcat is active and lines have been arriving regularly
- WHEN no logcat line is received for 6 seconds
- THEN `EventDetector` MUST record a `LogcatGap(fromMs, toMs)` entry
- AND any `DetectedEvent` whose `[startMs, endMs]` intersects the gap MUST have `confidence = LOW`

---

### Requirement: EVT-008 — Foreground proximity guard

The system MUST only emit a `DetectedEvent` when the matched ad/IAP activity occurs within 2 seconds of the configured game package being foregrounded.

#### Scenario: home button transitions are ignored

- GIVEN the user presses Home and the launcher activity becomes foreground
- WHEN `dumpsys activity` reports a non-game, non-ad package on top
- THEN `EventDetector` MUST NOT emit a `DetectedEvent`
- AND the foreground transition MUST be classified as "user navigation, not ad"

---

### Requirement: EVT-009 — Event count cap

The system MUST cap detected events per session at 500. IF the cap is exceeded, THEN the system SHALL switch the report representation from per-event listing to histogram aggregation by SDK.

#### Scenario: 30-minute session with rewarded ads stays under cap

- GIVEN a 30-minute capture with one rewarded ad every 60 seconds
- WHEN the session ends
- THEN the events list MUST contain ~30 events
- AND the report MUST render the per-event list

#### Scenario: pathological session triggers histogram fallback

- GIVEN a stress-test capture that emits 600 detected events
- WHEN the session ends
- THEN the events list MUST be capped at 500
- AND the report MUST switch to histogram aggregation by SDK
- AND the report MUST disclose "Más de 500 eventos detectados — vista resumida"

---

### Requirement: EVT-010 — iOS sidecar best-effort detection

WHERE the active capture target is iOS, the system SHALL perform best-effort detection via the sidecar by watching `OsTraceService.syslog()` for StoreKit subsystem mentions and SpringBoard foreground-app changes.

#### Scenario: iOS without Developer Mode falls back to foreground transitions

- GIVEN an iOS device WITHOUT Developer Mode enabled
- WHEN the foreground app changes away from the game and back within a window of 2 to 90 seconds
- THEN the sidecar MUST emit a `DetectedEvent(type=EXTERNAL, sdk=UNKNOWN, source=IOS_FOREGROUND, confidence=LOW)`

#### Scenario: iOS StoreKit IAP is detected via syslog

- GIVEN an iOS device (Developer Mode optional)
- WHEN the syslog stream contains a `com.apple.storekit` subsystem entry indicating a purchase sheet presentation
- THEN the sidecar MUST emit a `DetectedEvent(type=IAP, sdk=STOREKIT, source=IOS_SYSLOG)`

---

## 2. Metric Filtering

### Requirement: FLT-001 — Timestamped histories for all metrics

The system MUST maintain a `TimedSample(secondMs, value)` parallel history for FPS, CPU, memory, CPU temperature, and GPU/skin temperature, in addition to the existing positional histories.

#### Scenario: timed twins are populated each tick

- GIVEN a capture session is running
- WHEN the polling loop completes a tick at relative time T
- THEN `cpuTimed`, `memTimed`, `tempCpuTimed`, and `fpsTimed` MUST each gain at most one `TimedSample(T, value)` entry per their respective polling cadence
- AND the in-memory size MUST stay within `MAX_HISTORY_SIZE`

---

### Requirement: FLT-002 — Pure filtered aggregation

The system SHALL provide a pure `FilteredMetricsCalculator.computeFiltered(rawTimed, excludedRanges, padding)` function that returns `MetricsAggregates` with avg, min, max, p1, p5, p50, p90, p99 computed only over samples whose timestamp falls outside any padded excluded range.

#### Scenario: filtered aggregates exclude ad-window samples

- GIVEN a 60 s session with FPS samples and one detected interstitial spanning T=20 s to T=30 s
- WHEN `computeFiltered(fpsTimed, [(20000, 30000)], padding=500)` is called
- THEN the returned aggregates MUST be computed over samples in `[0, 19500] ∪ [30500, 60000]`
- AND samples in `[19500, 30500]` MUST NOT contribute to avg/percentiles

#### Scenario: empty excluded ranges yields raw aggregates

- GIVEN any timed history `H`
- WHEN `computeFiltered(H, excludedRanges = emptyList(), padding = 500)` is called
- THEN the returned aggregates MUST equal the unfiltered whole-session aggregates within ±0.1 fps tolerance

---

### Requirement: FLT-003 — Symmetric ±500 ms padding

The system MUST apply a symmetric padding of 500 ms around each excluded range when computing filtered metrics. The padding MUST be centralized as a single named constant and MUST NOT be hardcoded at call sites.

#### Scenario: padding catches the pre-show transition

- GIVEN a detected event with `startMs = 10000` and `endMs = 15000`
- WHEN `computeFiltered` evaluates the effective excluded window
- THEN the effective window MUST be `[9500, 15500]`

---

### Requirement: FLT-004 — Dual-view aggregates

The system SHALL produce both a `filtered` and a `raw` `MetricsAggregates` for every session and SHALL pass `filtered` to `FinalScoreCalculator.compute(...)` as the primary input.

#### Scenario: both views are computed for every session

- GIVEN a session has ended and aggregation begins
- WHEN `AppViewModel` finalizes session metrics
- THEN it MUST compute `filtered = computeFiltered(..., excludedRanges = detectedRanges)`
- AND it MUST compute `raw = computeFiltered(..., excludedRanges = emptyList())`
- AND it MUST pass `filtered` (not `raw`) to `FinalScoreCalculator.compute(...)`

---

### Requirement: FLT-005 — Excessive exclusion fallback

IF the union of padded excluded ranges covers more than 70% of the session duration, THEN the system MUST fall back to using `raw` aggregates as the primary view AND MUST display a prominent warning in the report.

#### Scenario: 80% ad session falls back to raw

- GIVEN a 5-minute session in which 4 minutes are detected as ad windows
- WHEN aggregation runs
- THEN `filtered` MUST NOT be passed to `FinalScoreCalculator`
- AND `FinalScoreCalculator` MUST receive `raw` aggregates instead
- AND the report MUST display the warning "Más del 70% de la sesión fue excluida por eventos detectados — los promedios se calculan sobre la sesión completa"

---

### Requirement: FLT-006 — No-event sessions are no-ops

WHEN no events are detected in a session, the system SHALL produce `filtered` and `raw` aggregates that are equal within ±0.1 fps tolerance for FPS and within their respective natural precision for CPU, memory, and temperature.

#### Scenario: clean session produces matching views

- GIVEN a session with zero detected events
- WHEN aggregation runs
- THEN `filtered.avgFps` and `raw.avgFps` MUST agree within ±0.1
- AND the report MUST NOT show any "raw vs filtered" delta indicators

---

### Requirement: FLT-007 — Overlapping event ranges are unioned

The system MUST union overlapping detected event ranges before applying them to the filter.

#### Scenario: banner reload during interstitial close

- GIVEN two detected events with `[10000, 14000]` and `[13000, 16000]` (overlap at 13000–14000)
- WHEN `computeFiltered` is invoked
- THEN the effective excluded set (with padding) MUST be `[9500, 16500]`, computed as the union, not the sum

---

## 3. Heuristic Conclusions

### Requirement: CON-001 — Pure ConclusionEngine

The system SHALL provide a pure, side-effect-free `ConclusionEngine` that takes a `ConclusionInput` and returns an ordered `List<Conclusion>`.

#### Scenario: same input yields same output

- GIVEN a fixed `ConclusionInput`
- WHEN `ConclusionEngine.run(input)` is called twice
- THEN both calls MUST return identical `List<Conclusion>` (same elements, same order, same text)

---

### Requirement: CON-002 — Initial rule catalog

The system MUST ship at least the 8 initial heuristic rules described in `explore.md`, each implementing the `Rule` interface with `matches(input): Boolean` and `render(input): Conclusion`.

#### Scenario: all 8 initial rules are registered

- GIVEN the `ConclusionEngine` is initialized
- WHEN its registered rule list is inspected
- THEN it MUST contain at least the rules: `StableLowFps`, `ThermalThrottling`, `MemoryGrowth`, `JankWithGoodAvg`, `Capped30Fps`, `CpuSaturation`, `AdVsGameFpsDelta`, `LoadingThermalRecovery`
- AND each rule MUST have a unique stable string ID

---

### Requirement: CON-003 — Device-tier-aware predicates

Each rule's predicate MUST consider the device tier (already inferred via `core.HardwareScoring.detectTier(gpu)`) where applicable, and MUST NOT rely solely on raw absolute thresholds.

#### Scenario: 30 fps cap rule does not fire on tier-1 device

- GIVEN a session where `maxFps = 30` on a tier-1 (entry-level) device
- WHEN `Capped30Fps.matches(input)` is evaluated
- THEN it MUST return `false` because tier-1 hardware does not support higher than 30 fps reliably

#### Scenario: 30 fps cap rule fires on tier-3+ device

- GIVEN a session where `maxFps = 30` on a tier-3 (high-end) device
- WHEN `Capped30Fps.matches(input)` is evaluated
- THEN it MUST return `true`

---

### Requirement: CON-004 — Deterministic ordering

The system MUST sort fired conclusions by severity (`CRITICAL > WARNING > INFO`) and within a severity by stable rule ID, ascending.

#### Scenario: ordering is reproducible across runs

- GIVEN three rules fire with severities `INFO`, `CRITICAL`, `WARNING`
- WHEN `ConclusionEngine.run(input)` returns the list
- THEN the order MUST be `[CRITICAL, WARNING, INFO]`
- AND ties within a severity MUST be broken by ascending rule ID

---

### Requirement: CON-005 — Conclusion text format

Each rendered `Conclusion` MUST include a one-sentence Castilian Spanish (formal, **tuteo**) headline, supporting metric values quoted inline, an optional one-sentence actionable recommendation, and a severity icon hint for the report.

#### Scenario: stable low-fps conclusion includes actuals

- GIVEN `StableLowFps` fires with `p50=25`, `targetFps=60`, `tier=3`, `avgCpu=22%`
- WHEN `render(input)` is called
- THEN the resulting `Conclusion.headline` MUST mention `25`, `60`, the tier label, and `22%`
- AND `Conclusion.recommendation` MUST mention profiling scripts or reducing draw calls

---

### Requirement: CON-006 — Disclaimer in conclusions section

The report's `#sec-conclusions` MUST include a disclaimer noting the heuristic nature of the conclusions: "Conclusiones generadas por reglas heurísticas — interpreta estos resultados como hipótesis, no como diagnóstico definitivo."

#### Scenario: disclaimer is rendered above the conclusion list

- GIVEN any session with at least one fired conclusion
- WHEN the report is generated
- THEN the disclaimer text MUST appear inside `#sec-conclusions` above the conclusion cards

---

### Requirement: CON-007 — Zero-conclusion safety

WHEN no rules fire on a session, the system SHALL render the `#sec-conclusions` section with an explicit informational message rather than an empty section.

#### Scenario: clean reference session shows positive note

- GIVEN a session where zero rules match
- WHEN the report is generated
- THEN `#sec-conclusions` MUST contain "No se detectaron problemas heurísticos significativos en esta sesión."

---

## 4. Manual Markers Compatibility

### Requirement: MAN-001 — Manual markers preserved as fallback

The system MUST preserve the existing `MarkerType` workflow (`INTERSTITIAL`, `VIDEO_REWARD`, `LOADING`, `SCENE_CHANGE`, `CUSTOM`) and the `addMarker(...)` button paths in `CaptureScreen` unchanged in behavior.

#### Scenario: user can still add a manual marker

- GIVEN a capture session is running
- WHEN the user clicks the "Interstitial" button
- THEN `vm.addMarker(MarkerType.INTERSTITIAL)` MUST execute
- AND the resulting `SessionMarker` MUST be appended to the session marker list

---

### Requirement: MAN-002 — Unified events table

The report MUST render manual markers and auto-detected events in a single unified `#sec-events` table, with a "Source" column distinguishing them (`Manual` / `Auto: AdMob` / `Auto: Unity Ads` / etc.).

#### Scenario: mixed manual + auto events in one table

- GIVEN a session with 2 manual markers and 3 auto-detected events
- WHEN the report renders `#sec-events`
- THEN the table MUST contain 5 rows
- AND each row MUST include a "Source" column with one of the documented values

---

### Requirement: MAN-003 — Visual distinction in chart

The FPS chart MUST visually distinguish manual markers (vertical lines, existing behavior) from auto-detected events (shaded bands).

#### Scenario: chart renders both annotation styles

- GIVEN a session with 1 manual marker at T=15 s and 1 auto-detected interstitial spanning T=30 s to T=40 s
- WHEN the FPS chart is rendered
- THEN the chart MUST show a vertical annotation line at 15 s
- AND the chart MUST show a shaded box annotation between 30 s and 40 s
- AND the two annotation styles MUST NOT visually overlap or merge

---

### Requirement: MAN-004 — Manual markers do not affect filtering

Manual markers MUST NOT be applied to `FilteredMetricsCalculator.excludedRanges` unless the user explicitly converts a manual marker into an event range in a future workflow.

#### Scenario: manual marker is cosmetic only

- GIVEN a session with 1 manual marker at T=20 s and zero auto-detected events
- WHEN aggregation runs
- THEN `filtered.avgFps` MUST equal `raw.avgFps` (manual marker is ignored by the filter)

---

## 5. Report Integration

### Requirement: REP-001 — `#sec-conclusions` placement

The report MUST add a new `#sec-conclusions` section between `#sec-summary` (executive summary) and `#sec-dashboard` (metrics panel).

#### Scenario: section ordering in HTML output

- GIVEN a generated session report
- WHEN the HTML is parsed
- THEN the `#sec-conclusions` element MUST appear after `#sec-summary` and before `#sec-dashboard`

---

### Requirement: REP-002 — Dual-view metric cards

The report's metric dashboard MUST render each metric card with a primary value (filtered) and a smaller "(raw: X)" subtitle WHEN the filtered and raw values differ by more than 5%.

#### Scenario: small difference hides raw subtitle

- GIVEN `filtered.avgFps = 58.2` and `raw.avgFps = 58.5` (delta 0.5%)
- WHEN the FPS card is rendered
- THEN the card MUST show only the primary `58.2`
- AND it MUST NOT show a "(raw: ...)" subtitle

#### Scenario: large difference shows raw subtitle

- GIVEN `filtered.avgFps = 25.0` and `raw.avgFps = 35.0` (delta 40%)
- WHEN the FPS card is rendered
- THEN the card MUST show the primary `25.0`
- AND a smaller subtitle "(raw: 35.0)" MUST appear below it

---

### Requirement: REP-003 — Timeline annotations

The FPS chart MUST render shaded box annotations using `chartjs-plugin-annotation` over each excluded region, color-coded by event type: orange for ads, blue for IAP, gray for loading.

#### Scenario: ad band is orange

- GIVEN one detected ad event spanning T=10 s to T=20 s
- WHEN the FPS chart renders
- THEN a Chart.js `box` annotation MUST exist over `[10, 20]` with an orange-tinted fill

---

### Requirement: REP-004 — Castilian Spanish formal tuteo

All user-facing strings introduced by this change (section titles, conclusion text, disclaimers, warnings, table headers) MUST use Castilian Spanish formal **tuteo** per project convention. They MUST NOT use Rioplatense voseo.

#### Scenario: tuteo style in disclaimer

- GIVEN the conclusions disclaimer string
- WHEN inspected
- THEN it MUST use "interpreta" (tuteo imperative), NOT "interpretá" (voseo imperative)

---

### Requirement: REP-005 — Events section renders manual + auto

The report MUST replace the existing `#sec-markers` with `#sec-events` (or merge them) and MUST render columns: `Tipo` (badge), `Fuente`, `Inicio`, `Fin`, `Duración`.

#### Scenario: column set is exact

- GIVEN any session report containing at least one event or marker
- WHEN `#sec-events` is rendered
- THEN it MUST contain exactly the columns `Tipo`, `Fuente`, `Inicio`, `Fin`, `Duración`
- AND no other columns MUST be added without a follow-up spec change

---

## 6. iOS Best-Effort

### Requirement: IOS-001 — Capability disclosure in report header

WHERE the session was captured on iOS, the report header MUST disclose the detection mode: "Detección automática (Android completa, iOS parcial)" and MUST link to a one-paragraph explanation of iOS limitations.

#### Scenario: iOS report shows partial-mode banner

- GIVEN an iOS capture session
- WHEN the report is generated
- THEN the report header MUST contain the text "iOS parcial"
- AND a link or expandable note MUST explain that full ad SDK detection requires Developer Mode

---

### Requirement: IOS-002 — Developer Mode required for full detection

WHERE the iOS device has Developer Mode enabled, the sidecar SHALL include app-level `os_log` entries in its event detection. WHERE Developer Mode is NOT enabled, the sidecar MUST gracefully degrade to StoreKit + foreground-app-loss detection only.

#### Scenario: Developer Mode enabled enables app-level logs

- GIVEN an iOS device with Developer Mode ON
- WHEN the sidecar starts an event-detection stream
- THEN it MUST subscribe to app-level subsystems including ad SDK bundle identifiers
- AND ad-SDK detections MUST be possible

#### Scenario: Developer Mode disabled degrades cleanly

- GIVEN an iOS device with Developer Mode OFF
- WHEN the sidecar starts an event-detection stream
- THEN it MUST subscribe only to system-level subsystems (`com.apple.storekit`, SpringBoard)
- AND it MUST NOT raise an error to the desktop client
- AND the resulting events MUST carry `confidence = LOW` for foreground-loss-only detections

---

### Requirement: IOS-003 — Foreground-loss fallback events

WHERE iOS app-level detection is unavailable, the sidecar SHALL emit `DetectedEvent(type=EXTERNAL, sdk=UNKNOWN)` for foreground-app-loss windows of duration between 2 and 90 seconds.

#### Scenario: short foreground loss is treated as a likely ad

- GIVEN iOS without Developer Mode
- WHEN the foreground app changes away from the game at T=30 s and returns at T=45 s (15 s loss)
- THEN the sidecar MUST emit one `DetectedEvent(type=EXTERNAL, sdk=UNKNOWN, startMs=30000, endMs=45000, confidence=LOW)`

#### Scenario: very short transition is ignored

- GIVEN iOS without Developer Mode
- WHEN the foreground app changes away and returns within 1 second (likely a notification swipe)
- THEN the sidecar MUST NOT emit a `DetectedEvent`

#### Scenario: very long absence is ignored

- GIVEN iOS without Developer Mode
- WHEN the foreground app changes away and only returns after 120 seconds
- THEN the sidecar MUST NOT emit a `DetectedEvent` (likely the user backgrounded the app intentionally; out of scope for ad detection)

---
