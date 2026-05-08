# Exploration: auto-event-detection-and-clean-metrics

**Status**: Explored
**Date**: 2026-05-08
**Author**: SDD Explore agent

## Goal

Replace the user's manual "I just saw an interstitial / video reward / loading screen" button workflow with **automatic detection** based on `adb logcat` + `dumpsys activity` signatures of well-known ad SDKs and IAP activities. Use the detected event ranges to (a) compute a **filtered set of metrics** that excludes ad/IAP/loading periods from the FPS/CPU/memory averages, and (b) feed a **deterministic heuristic-rule engine** that emits qualitative conclusions ("game runs at 25 fps stable, insufficient for the device's hardware tier — recommend profiling scripts and reducing draw calls") into the HTML report.

The user's verbatim complaint: *"si durante un anuncio el FPS sube a 100, eso NO es del juego y NO debe contar para la media."* Today, when AdMob / IronSource / a Unity rewarded video kicks in, the ad's lightweight render contaminates the FPS sample (it's no longer the game) and ruins both the average and the conclusions. We currently solve this by asking the QA tester to manually press a button at the right moment — which is fragile, often forgotten, and useless during automated/unattended sessions.

## Current state

**Manual marker workflow today** (the surface area this change replaces):

- The `MarkerType` enum at `viewmodel/AppViewModel.kt:46` defines four event types (plus a free-text CUSTOM): `INTERSTITIAL`, `VIDEO_REWARD`, `LOADING`, `SCENE_CHANGE`.
- `SessionMarker` at `viewmodel/AppViewModel.kt:61` is the data carrier: `id`, `timestampMs`, `type`, `title`, `note`, `colorHex`. The `timestampSeconds` accessor (line 70) provides backward-compat for charts and reports.
- The QA tester clicks the buttons rendered at `ui/screens/CaptureScreen.kt:179-182`. Each click calls `vm.addMarker(MarkerType.X)` (the addMarker method itself is at `viewmodel/AppViewModel.kt:1567`). There is also a `Nota +` free-text path at `CaptureScreen.kt:185-196`.
- Markers are persisted in the session and rendered in two places in the report:
  1. As vertical lines on the FPS chart (`report/ReportGenerator.kt:124-130`, the `markerAnnotationsJs` block).
  2. As a table in `#sec-markers` (`report/ReportGenerator.kt:133-157`, the `markersHtml` block).
- Crucially, **markers are ZERO-INFLUENCE on metrics today**. They're cosmetic annotations on the chart and a table in the report. The averages, percentiles, peaks, and grading at `viewmodel/AppViewModel.kt:1322-1346` operate on the entire session unconditionally. The `FinalScoreCalculator.compute(...)` at `core/grading/FinalScoreCalculator.kt:85` receives the session-wide aggregates with no concept of "this period was an ad, exclude it".

**Where metrics are aggregated** (the surface area where filtering must hook in):

- `viewmodel/AppViewModel.kt:1322-1346` does the post-loop aggregation: `sorted = fpsHistory.sorted()`, p50/p1/p5/p90/p99 via `pct(...)`, `avgFps = sorted.average()`, `peakMem = memHistory.maxOrNull()`, `avgCpu = cpuHistory.average().toInt()`, `maxTempCpu = tempCpuHistory.maxOrNull()`. All of these use the unfiltered timed history lists (`fpsHistory`, `memHistory`, `cpuHistory`, `tempCpuHistory`, etc.) accumulated inside the capture loop at `viewmodel/AppViewModel.kt:949-963`.
- The history lists are timed via two parallel data structures: a plain `mutableListOf<Int>()` for `fpsHistory` (used for percentiles and the chart) AND `fpsTimed: mutableListOf<TimedSample>()` (with explicit `(second, value)` pairs — defined at `viewmodel/AppViewModel.kt:43`). `fpsTimed` is what enables timestamped overlays in the FPS chart. **This is the seam we need: every metric history MUST be timestamped or correlatable to a wall-clock offset.** Currently only FPS is fully timestamped; CPU/memory/thermal histories are positional (index = polling tick number ≈ seconds).
- The grading flows through `FinalScoreCalculator.compute(GradingInput(...))` at `viewmodel/AppViewModel.kt:1357-1374`. `GradingInput` (defined at `core/grading/FinalScoreCalculator.kt:32-43`) takes pre-aggregated scalars — it has NO concept of time series. Filtering must happen UPSTREAM before computing those scalars.

**Current ADB stack capabilities** (what the detection layer can build on):

- `core/AdbBridge.kt:144-147` exposes a generic `shell(deviceId, cmd, timeoutMs)` that invokes `adb -s <id> shell <cmd>` and returns stdout. This is reused throughout the file for `dumpsys ...`, `cat /proc/...`, `getprop ...`, etc.
- `core/AdbBridge.kt:225-241` already does some `dumpsys activity activities` parsing for game detection — extracting the foreground package via `RE_PACKAGE_NAME` and `RE_CMP` regex. This is the same `dumpsys` output we'll mine for ad-activity launches.
- `core/AdbBridge.kt:124-142` has a `exec(...)` helper that does redirected-stderr ProcessBuilder + timeout. For a streaming workload like `adb logcat`, we can't use this directly — we need a long-running `Process` whose `inputStream` we read line-by-line on a background coroutine. Pattern reference: `viewmodel/AppViewModel.kt:769-779` in `startScreenRecord` — that's how a long-lived adb child is currently managed.
- `viewmodel/AppViewModel.kt:1011-1235` is the main capture loop. Polling cadence is 500 ms with a tiered cost model (FPS+CPU+battery every tick, memory every 5 s, thermal every 2 s — see lines 988-991 and the `iterCount` gating). A `logcat` reader running in parallel as a sibling coroutine fits naturally here without disturbing the existing tiered cadence.
- iOS path: `sidecar/gameperf_sidecar/main.py` exposes a FastAPI app with `/devices`, `/device/{udid}/metrics`, `/device/{udid}/screen-record/start`, etc. The metrics module (`sidecar/gameperf_sidecar/metrics.py:108-115`) splits capture into "DVT path (FPS/CPU/memory) requires Developer Mode" vs "Diagnostics path (battery/temperature) works without Developer Mode". **iOS has no `adb logcat` equivalent and the closest thing — `OsTraceService`/`syslog` from pymobiledevice3 (already used at `sidecar/gameperf_sidecar/devices.py:218-234` for foreground-app detection) — DOES require Developer Mode for full app-level entries.** This is a meaningful capability gap we'll discuss honestly in the iOS section.

**Existing "in-app dependency bootstrap" change** (`openspec/changes/in-app-dep-bootstrap/proposal.md`) is the structural template we'll follow. It uses the fields `Intent / Scope (In/Out) / Approach / Affected Areas / Risks / Rollback Plan / Dependencies / Success Criteria` and Spanish prose for user-facing motivations + English code identifiers. Same convention applies here.

## Proposed approach

Three orthogonal pillars, each independently testable and shippable:

### Pillar 1 — Detection (auto-event recognition)

A new `core/events/EventDetector` subsystem that runs in parallel with the capture loop. It owns ONE long-running `adb logcat` child process per session plus periodic `dumpsys activity` polls (~1 Hz). Its sole output is a stream of `DetectedEvent(type, startMs, endMs?, source, signatureMatched)` objects pushed into a `MutableStateFlow<List<DetectedEvent>>` on the ViewModel. Manual markers (existing `MarkerType` buttons) remain — they become a **fallback when auto-detection misses something**, and the user can also dismiss / merge / split auto-detected events post-hoc from the report (out of scope for this change but worth designing the data model so it's possible later).

### Pillar 2 — Metrics filtering (clean averages)

A new `core/metrics/FilteredMetricsCalculator` that takes the raw timed histories + the list of detected event ranges and produces TWO sets of aggregates:

- **Primary (filtered)**: averages/percentiles computed over samples whose timestamp falls OUTSIDE any event range. This is what the report headlines, what `FinalScoreCalculator` consumes for the grade, and what the timeline chart uses for the green "real game" line.
- **Secondary (raw)**: the existing whole-session aggregates, kept intact and shown as a smaller "incl. ads/IAP/loading" comparison block in the report so the user can audit the filtering and verify it didn't lie to them.

The FPS chart adds shaded vertical bands over excluded regions (orange tint = ad, blue tint = IAP, gray tint = loading) so the user immediately SEES which slices were dropped.

### Pillar 3 — Conclusions (heuristic narrative)

A new `core/conclusions/ConclusionEngine` (pure object, fully unit-testable) that consumes the FILTERED aggregates + the device hardware tier (already inferred via `core.HardwareScoring.detectTier(gpu)` — already used at `viewmodel/AppViewModel.kt:861-867` for screen-record profile selection) and runs a deterministic catalog of rules. Each rule has a predicate (`matches(input): Boolean`) and a `render(input): Conclusion` that produces a localized text + a severity + an optional actionable recommendation. The engine returns the ordered list of fired conclusions; the report renders them as a new `#sec-conclusions` section between the existing executive summary and the metrics dashboard.

Crucial decision: **rules are codified in Kotlin, not in an external YAML/JSON config and NOT via an LLM**. Why:
1. **Testable**: each rule gets a unit test fixture.
2. **Versionable**: rule changes show up in git diffs alongside the code that consumes them.
3. **Explainable**: a rule's text in the report says EXACTLY what condition fired ("FPS p50=25 with target=60 → 41.6% of target"). No black-box probabilistic reasoning.
4. **Deterministic**: same inputs → same outputs, every time. No "it depends on which model the LLM was running".

## Technical investigation

### Detection pillar

#### `adb logcat` reliability

Verified facts:
- `adb logcat -d` dumps the current buffer and exits. `adb logcat` (no `-d`) streams continuously until killed. We need streaming.
- Filtering is via `tag:priority` pairs: `adb logcat AdActivity:D '*:S'` keeps `AdActivity` at debug level and silences everything else. This is critical for performance — without filtering, an Android device can emit 500-2000 lines/sec under load.
- Buffer overflow: when the logcat ring buffer fills (default 256 KB on the `main` buffer in older Android, larger now) it drops oldest entries. If our reader can't keep up with the producer, we'll miss events. Mitigation: use `-b main,system,events` only (skip `-b crash,kernel,radio` which we don't need), pre-filter heavily by tag, and read with a `BufferedReader` on a dedicated `Dispatchers.IO` coroutine.
- Killing: when the user stops the capture, we call `process.destroyForcibly()`. Same pattern as `stopScreenRecord` at `core/AdbBridge.kt:781-783`. The orphan `logcat` process on the device side dies via SIGPIPE when its stdout closes — same mechanism `screenrecord` uses.
- **Format**: `01-15 14:23:45.678  1234  5678 D AdActivity: onAdLoaded` — fields are date, time, pid, tid, level, tag, message. Each line is a distinct UTF-8 string terminated by `\n`. We can parse with a single regex per line.
- **Performance overhead**: `adb logcat` itself adds ≈1-3% CPU on the device (it's mostly I/O). The desktop side has to parse N lines per second; with aggressive tag filtering this is <50 lines/sec under normal gameplay → trivial.
- **Buffer overflow caveat needs verification during implementation**: under EXTREME log volume (e.g. some emulators, some debug builds with verbose Unity logs) we may still drop. Plan: track the timestamp delta between consecutive `logcat` lines we receive — if we ever see a gap > 5 s we log a warning and mark the affected detection window as "low confidence" in the report.

#### `dumpsys activity` reliability

Verified facts:
- `dumpsys activity activities` returns a structured dump that includes the entire activity stack with `taskAffinity`, `realActivity=cmp=<package>/<activity>`, lifecycle state. We already parse a slice of this (`detectGame` at `core/AdbBridge.kt:225-241`).
- `dumpsys activity recents` returns the recent task list with task IDs, package names, last-active timestamps. Useful as a sanity-check overlay on logcat detections (if logcat says "AdActivity opened" but dumpsys never sees it in the stack within 1 s, treat the detection as suspect).
- Polling cost: ~50-200 ms per shell-out. Can run at 1 Hz on a sibling coroutine without disturbing the 500 ms polling loop.
- We already use a similar technique (`dumpsys SurfaceFlinger`) at 500 ms cadence for FPS — so 1 Hz `dumpsys activity` is well within budget.

#### Ad SDK signatures (verified where possible, marked otherwise)

For each target SDK, the canonical signatures we'll match. **Bold** = high-confidence (verified from public docs / SDK source). _Italic_ = needs in-the-wild verification during implementation.

| SDK | Activity classes (dumpsys cmp=) | Logcat tags (high signal) | Signal pattern |
|---|---|---|---|
| **Google AdMob** | `com.google.android.gms.ads.AdActivity` (interstitial), `com.google.android.gms.ads.OutOfContextTestingActivity` (test mode), `com.google.android.gms.ads.rewarded.RewardedAd*` | `Ads`, `AdActivity`, `RewardedAd`, `InterstitialAd` | activity-launch start, "onAdShown" / "onRewarded" / "onAdDismissed" lifecycle |
| **Unity Ads** | `com.unity3d.services.ads.adunit.AdUnitActivity` | `UnityAds`, `UnityAdsInternal` | `"Showing ad with placementId"`, `"onUnityAdsFinish"` |
| **IronSource** | `com.ironsource.sdk.controller.ControllerActivity`, `com.ironsource.sdk.controller.InterstitialActivity` | `IronSource`, `ironSrc` | `"IS::onInterstitialAdShowSucceeded"`, `"IS::onInterstitialAdClosed"` |
| **AppLovin / MAX** | `com.applovin.adview.AppLovinFullscreenActivity`, `com.applovin.adview.AppLovinInterstitialActivity` | `AppLovinSdk`, `AppLovinAd`, _MaxAd_ | `"InterstitialAd: ad shown"`, `"InterstitialAd: ad hidden"` |
| **Meta Audience Network** | `com.facebook.ads.AudienceNetworkActivity` | `FB.Audience`, `FBAudienceNetworkLog` | _activity-launch + "ad shown"_ — verify exact log line |
| **Google Play Billing (IAP)** | `com.android.billingclient.api.ProxyBillingActivity`, activities under `com.android.vending.billing.InAppBillingService` | `BillingClient`, `BillingHelper`, `Finsky` | activity-launch is enough — IAP is inherently bracketed by a launch + dismiss |

Time correlation strategy:
- Each `DetectedEvent` carries `startMs` (clock time when the start signal fired), `endMs` (when the close signal fired or null if still open), and `source` (`LOGCAT_TAG_X` / `DUMPSYS_ACTIVITY_Y`).
- Map to relative session time via `startMs - captureStartTime` (the existing `captureStartTime` field at `viewmodel/AppViewModel.kt:375` is set when the capture clock starts — same reference used by `LiveMetrics.elapsed`).
- Filtering precision risk: FPS samples come from SurfaceFlinger every 500 ms, while logcat events come whenever the ad SDK decides to print. A 200-300 ms misalignment between "ad started" and "first ad-tainted FPS sample" is realistic. Mitigation: add a configurable `paddingMs = 500` symmetrical buffer around each detected range when computing the filtered metrics. Tunable via a constant in `FilteredMetricsCalculator`.

#### iOS equivalent — be honest

The iOS sidecar can poll `OsTraceService.syslog()` (already used at `sidecar/gameperf_sidecar/devices.py:218-234`) — this gives us a stream similar to logcat. BUT:

- It REQUIRES Developer Mode to see app-level NSLog/os_log entries. Without Developer Mode we get only system-level RunningBoard / SpringBoard messages (which DO show app activations and could let us detect Apple StoreKit IAP via `com.apple.storekit` mentions).
- Most ad SDKs on iOS log via `os_log` with their bundle identifier as the subsystem. Without Developer Mode those messages are gated.
- StoreKit IAP detection: feasible without Developer Mode by watching for `com.apple.storekit` subsystem mentions in syslog (verify during implementation).
- Foreground app changes via SpringBoard: works without Developer Mode (proven by `_get_foreground_app` at `sidecar/gameperf_sidecar/devices.py:181-239`). We can use this as a cruder fallback signal — "the foreground app changed away from the game and back" likely means an interstitial or system overlay.

**Honest recommendation**: ship this feature **Android-first** with a dedicated `EventDetector` that uses logcat + dumpsys. For iOS, ship a degraded version in the same release cycle:
- Detect IAP via StoreKit syslog mentions (best-effort, feasible without Developer Mode — needs verification).
- Detect foreground-app-loss windows as a generic "external event, possibly an ad" fallback.
- For full ad SDK detection, document in the report header that "iOS ad detection requires Developer Mode and is currently a manual-marker workflow".

Don't promise feature parity. Set the user's expectations explicitly in the UI: "Detección automática (Android completa, iOS parcial)".

### Metrics filtering pillar

#### How to exclude time ranges from FPS / CPU / memory averages

The capture loop at `viewmodel/AppViewModel.kt:1011-1235` accumulates parallel histories. We need the raw samples to be **timestamped** so the filter can decide per-sample whether to include or exclude. Today only FPS has a fully timestamped twin (`fpsTimed: List<TimedSample>` at line 950, with `TimedSample(second, value)` — defined at line 43).

Plan:
1. Add timestamp twins for the other histories: `cpuTimed`, `memTimed`, `tempCpuTimed`, etc. — same `TimedSample` shape. Cheap (a few KB even at 7200 max-samples per session — see `MAX_HISTORY_SIZE` cap at line 163).
2. Implement `FilteredMetricsCalculator.computeFiltered(rawTimed: List<TimedSample>, excludedRanges: List<TimeRange>): MetricsAggregates` as a pure function. It iterates samples, keeps those whose `secondMs ∈ [0, sessionEnd]` AND NOT inside any `(range.startMs - padding, range.endMs + padding)`, then computes avg/min/max/p1/p5/p50/p90/p99 over the kept set.
3. Compute `MetricsAggregates filtered = computeFiltered(...)` AND `MetricsAggregates raw = computeFiltered(..., excludedRanges = emptyList())` (i.e. the raw view is just the filter applied with no ranges = whole session).
4. Pass `filtered` to `FinalScoreCalculator.compute(...)` as the primary input. The raw aggregates flow into the report only as a secondary "incl. ads" comparison.
5. Edge case: if the filter excludes >70% of the session (something is very wrong, or the user ran a 5-min session that was 5 min of ads), fall back to raw + display a prominent warning in the report. The user shouldn't see a `0fps avg` because we filtered everything out.

#### Dual-view UX in the report

`report/ReportGenerator.kt:296-327` (the `metrics-dashboard` section) currently shows ONE row of metric cards. The plan:
- Each card gets a primary number (filtered) and a smaller subtitle "(raw: X)" when the two differ by more than a tunable threshold (e.g. 5%).
- The FPS chart at `report/ReportGenerator.kt:329-350` adds shaded `Chart.js` `box` annotations using the existing `chartjs-plugin-annotation` (already loaded at line 219) over excluded regions. Color-coded by event type.
- A new `#sec-events` section (analogous to the existing `#sec-markers`) lists detected events in a table with columns: type (badge), start, end, duration, source. Manual markers and auto-detected events live in the SAME table, distinguished by a "Source" column (`Manual` / `Auto: AdMob` / `Auto: Unity Ads` / etc.).

### Conclusions pillar

#### Heuristic rule catalog (initial set, expandable)

Each rule consumes the same `ConclusionInput` — a struct containing filtered aggregates + raw aggregates + device tier + thermal series + memory series + jank ratio. Examples (this is a starting catalog, not exhaustive):

1. **Stable low FPS, low CPU, low GPU temp** → "Game runs at {p50}fps stable. CPU load is {avgCpu}% (low) and the device tier is {tier}. The bottleneck is most likely the game's own logic / rendering scripts, not the device. Recommend profiling scripts and reducing draw calls."
2. **FPS drops correlate with thermal events** (skin > 42°C OR die > 95°C) → "Thermal throttling detected — peak skin {maxSkin}°C, peak die {maxDie}°C. FPS drops from {p99} to {p1} correlate with thermal warnings. Consider testing in a cooler environment to isolate thermal effects from logic effects."
3. **Memory grows linearly without GC drops** (slope of `memHistory.regress()` > 0.5 MB/sec sustained) → "Memory grew {totalGrowth}MB over {duration}s with no observed releases. Likely memory leak — recommend running a longer session and monitoring for OOM."
4. **High jank ratio with normal FPS average** (`jankRatio > 0.10` AND `avgFps > 0.85 * targetFps`) → "Average FPS is on-target ({avgFps}/{targetFps}) but {jankPct}% of frames are jank-flagged. Indicates intermittent stutters. Recommend frame-time analysis around scene transitions."
5. **FPS target = 60 but caps at 30** (`maxFps ≤ 32` AND `device tier supports 60+`) → "Game appears capped at 30fps despite the device supporting higher. Verify Application.targetFrameRate / vsync settings."
6. **CPU saturated** (`avgCpu > 85`) → already an existing penalty in `FinalScoreCalculator`. The conclusion adds: "CPU is the primary bottleneck — recommend native-thread profiling (Perfetto / systrace)."
7. **Ad-window FPS spike vs game FPS** (filtered avg < raw avg by > 15%) → "Ad SDK rendering averaged {rawAvg}fps vs your game's {filteredAvg}fps — confirms the filtering is working. Your game is {delta}fps slower than the lightweight ad surfaces, suggesting your scenes have heavier rendering than necessary."
8. **Loading-window thermal recovery** — if temp drops during detected loading periods and recovers afterward, indicates loading screens are the only periods where the device gets to cool down. Useful to flag for level-design discussions.

Each rule comes with a unit test (pure inputs → expected fired list). Ordering: rules are deterministically sorted by severity (`CRITICAL > WARNING > INFO`) and within a severity by stable rule ID, so the conclusion section is reproducible.

#### Report integration

A new `#sec-conclusions` section between `#sec-summary` and `#sec-dashboard`:

```
[Resumen Ejecutivo]
  → Conclusiones (NEW)
[Panel de Métricas]
[FPS / Frame Time / Memoria / CPU / Temp]
[Eventos detectados (NEW, replaces / merges with markers)]
[Problemas]
[Hardware]
```

Each conclusion is rendered as a card with: severity icon, one-sentence headline, supporting metrics quoted inline, and (where applicable) a one-sentence actionable recommendation. Localized to Castilian Spanish formal/tuteo per the project convention (CLAUDE.md). Conclusion IDs and rule names stay in English (they're code).

## Alternatives considered

| Decision | Chosen | Alternatives considered | Why rejected |
|---|---|---|---|
| Detection mechanism | adb logcat + dumpsys | (a) Computer vision on the recorded video to detect ad-shaped overlays; (b) accessibility-service-based foreground-detection; (c) Frida/instrumentation hook into the game process | (a) is fragile (every SDK looks different visually, ads can fill the entire screen and be indistinguishable from the game), expensive (full-frame analysis per recorded frame), and brittle to portrait/landscape changes. (b) requires the user to enable an accessibility service which is invasive and gets revoked between sessions. (c) requires either a rooted device or a debug build of the game — neither is available for typical QA scenarios where we test release APKs from a third party. logcat+dumpsys is observational, requires zero changes to the device or the game, and works on every Android API level we already support. |
| Conclusion engine | Deterministic rules in Kotlin | (a) LLM-based summarization (GPT-4 / Claude API call with the metrics JSON); (b) external YAML/JSON rule config | (a) introduces network dependency, API key management, latency, non-determinism, and cost-per-report. The user explicitly approved the deterministic-rules approach. (b) sounds nice but in practice rule predicates need access to derived data (regression slopes, cross-series correlation) that becomes painful to express in YAML — easier and more honest as Kotlin code with first-class types. The trade-off is "non-developer can't add rules without a release", but the benefit is rules are code-reviewed, type-safe, and unit-tested. |
| Two metric views vs. one | Both filtered (primary) AND raw (secondary) | (a) Only filtered (drop raw entirely); (b) only raw (drop filtering, keep events as annotations only) | (a) hides info from advanced users who want to audit the filter — they should always be able to see "what got dropped". (b) is the status quo and doesn't address the user's complaint. The dual view costs one extra row in the report and a few KB of additional aggregation, which is negligible. |
| Manual markers fate | Keep as fallback | (a) Remove entirely once auto-detection ships; (b) Replace with a "merge auto+manual" UI | (a) breaks the workflow for unknown SDKs (we only cover the top 5 ad networks initially; some games use exotic SDKs). (b) is desirable but out of scope for this change — defer to a follow-up that adds report-side editing. Manual markers in this change just keep working unchanged; they get unioned with auto-detected events in the same table. |
| Filter padding | Symmetric ±500 ms around event ranges, configurable constant | (a) No padding; (b) Asymmetric (e.g. only post-end padding) | (a) misses the "ad starting to render" frames before the SDK logs `onAdShown`. SDKs typically log AFTER the ad is visible. (b) mostly the same critique — pre-padding catches the transition into the ad. ±500 ms is conservative and revisitable. |
| iOS support | Ship Android-complete + iOS-best-effort in same release | (a) Wait until iOS achieves parity before shipping anything; (b) Ship Android only, defer iOS indefinitely | (a) penalizes Android users (the larger user base) for an iOS limitation that's outside our control. (b) leaves iOS users with a worse experience permanently. The chosen path is honest and incremental: Android gets the full feature, iOS gets what it can, the report header tells the user which mode they're in. |

## Risks and unknowns

1. **Logcat buffer drops under load** — On games that print verbose log spam (Unity DEBUG builds, some Unreal games), the device-side logcat ring buffer can drop messages before our reader pulls them. **Detection**: track gaps in incoming logcat timestamps; if > 5 s gap, mark detection-window as low-confidence. **Mitigation**: tag-filter aggressively (`AdActivity:D '*:S'`).
2. **Release builds strip ad SDK logs** — Some games/SDKs ship with `Log.d` / `Log.i` calls stripped via ProGuard / R8 in release. If a release-build AdMob ad runs without printing anything, our detector goes blind. **Mitigation**: dumpsys-activity polling catches the activity launch even when logs are silent. Activity launch is a system event, not a log statement, so it survives ProGuard. We can detect AdMob's `AdActivity` via dumpsys even with all SDK logs gone.
3. **Time correlation precision** — FPS samples are 500 ms-quantized; logcat timestamps are millisecond-precision; the desktop clock and the device clock can drift seconds apart. **Mitigation**: use the desktop's reception timestamp of the logcat line as the reference clock (consistent with how `captureStartTime` and `LiveMetrics.elapsed` work today). The drift between "device clock when SDK logged" and "desktop clock when adb forwarded the line" is sub-100 ms in practice. Acceptable.
4. **iOS sidecar lacks permissions** — Without Developer Mode, the syslog stream excludes app-level entries. **Mitigation**: explicitly downgrade iOS expectations in the UI. Document the limitation in the report header for iOS sessions. Don't claim parity.
5. **Heuristic rules give wrong advice in edge cases** — A single rule firing in isolation can be misleading ("CPU is 86% → bottleneck" might be true on a quad-core but irrelevant on an 8-core where the game uses 6 cores efficiently). **Mitigation**: rules consider device tier in their predicates, not just raw thresholds. Add a "rule combinations" layer in v2 — for now, keep each rule conservative and add a generic disclaimer in the section: "Conclusiones generadas por reglas heurísticas — interpretar como hipótesis, no diagnóstico definitivo".
6. **False positives from system-level activities** — `dumpsys activity` will see legitimate non-game activities (e.g. the user's home button press popping the game to the background). **Mitigation**: detector's predicates require BOTH a known-ad-SDK package AND temporal proximity to the foreground game (within 2 s of the game being on top). Foreground transitions far from the game are ignored.
7. **Long sessions accumulate huge event lists** — A 30-minute idle-game session with rewarded ads every 60 s produces ~30 events. Manageable. But a stress test could produce hundreds. **Mitigation**: cap at e.g. 500 events; if exceeded, switch the report from per-event listing to histogram aggregation.
8. **Concurrent-event handling** — Two ad SDKs could overlap (interstitial closing while a banner reload runs). The filter ranges union should handle this, but the report's UX needs to render overlapping bands cleanly. **Mitigation**: union ranges before drawing. Test with a synthetic fixture.
9. **First-run blindness** — On first session for a given device, the layer cache (`cachedCandidates` at `core/AdbBridge.kt:253-254`) is cold. The FPS layer rediscovery interaction with auto-detected ad close events (where `invalidateLayerCache` is called at `viewmodel/AppViewModel.kt:992-1009`) needs a regression test. The auto-detection might incidentally help here — knowing exactly when an ad closed gives us a precise moment to invalidate the layer cache instead of guessing via "K consecutive null frames".
10. **CLAUDE.md regression patterns** — This is a substantial new subsystem. Risk patterns to specifically prevent: (a) using `which` instead of `where` on Windows for any new external tool — N/A here, no new tools, but watch the iOS sidecar; (b) defaulting `String` charset on log-line parsing — use UTF-8 explicitly; (c) duplicating signature-list logic — keep it in ONE place (`SDKSignatures` constant table) to avoid the v4.2.13 ToolResolver-duplication trap.

## Success criteria

Measurable, testable definitions of "this works":

- Detector recognizes Google AdMob `AdActivity` via dumpsys + logcat with ≥90% precision on a fixture of 5 different real games (mix of casual + mid-core).
- Detector identifies a Google Play Billing IAP launch in <2 s of the activity going to top of stack.
- FilteredMetricsCalculator produces `filtered.avgFps != raw.avgFps` for every session that contains at least one detected event (i.e. filtering actually does something).
- Filtered+raw aggregates AGREE within ±0.1 fps on sessions with NO detected events (filtering is a no-op when nothing fires).
- `ConclusionEngine` fires at least one rule on >80% of test sessions; produces 0 false-CRITICAL conclusions on a fixture of "well-performing reference sessions".
- `#sec-conclusions` renders in the HTML report with at least 1 conclusion in user-visible Castilian Spanish text, distinct from the existing `#sec-problems`.
- All new pure objects (`EventDetector` parsing helpers, `FilteredMetricsCalculator`, `ConclusionEngine`, `SDKSignatures`) have ≥80% line coverage in unit tests with NO mocks (per CLAUDE.md "tests puros sin mocks" rule).
- `./gradlew check` passes (detekt + tests).
- iOS session with NO detected events still produces a valid report with the conclusions section noting "iOS detección parcial".

## Open questions for user

None — approach is clear and aligns with the user's verbatim approval of the four-pillar design. Proceeding to proposal.

## Files likely affected

**New files** (≈10 new):
- `src/main/kotlin/com/gameperf/desktop/core/events/EventDetector.kt` — orchestrator: owns logcat process + dumpsys polling, emits `DetectedEvent` stream.
- `src/main/kotlin/com/gameperf/desktop/core/events/SDKSignatures.kt` — pure const table of activity classes + log tags + signature regex per SDK. Easy to extend via a one-line PR for new SDKs.
- `src/main/kotlin/com/gameperf/desktop/core/events/LogcatLineParser.kt` — pure regex-based line parser, unit-testable.
- `src/main/kotlin/com/gameperf/desktop/core/events/DetectedEvent.kt` — data class.
- `src/main/kotlin/com/gameperf/desktop/core/metrics/FilteredMetricsCalculator.kt` — pure aggregation with optional excluded ranges.
- `src/main/kotlin/com/gameperf/desktop/core/metrics/MetricsAggregates.kt` — data class for the dual-view output.
- `src/main/kotlin/com/gameperf/desktop/core/conclusions/ConclusionEngine.kt` — pure rule runner.
- `src/main/kotlin/com/gameperf/desktop/core/conclusions/Rule.kt` — `Rule` interface + `Conclusion` data class.
- `src/main/kotlin/com/gameperf/desktop/core/conclusions/rules/*.kt` — one file per rule (8-10 initial), each a pure object implementing `Rule`.
- Tests mirroring each of the above under `src/test/kotlin/...`.

**Modified files** (≈8):
- `src/main/kotlin/com/gameperf/desktop/viewmodel/AppViewModel.kt` — wire EventDetector into `startCapture`, expose `events: StateFlow<List<DetectedEvent>>`, route `FilteredMetricsCalculator` output through `FinalScoreCalculator`. Add timed-history twins (`cpuTimed`, `memTimed`, etc.).
- `src/main/kotlin/com/gameperf/desktop/core/AdbBridge.kt` (or a new `AdbLogcat.kt`) — add `startLogcat(deviceId, tagFilter, lineSink)` returning a managed `Process` + a coroutine reader. Pattern mirrors `startScreenRecord`.
- `src/main/kotlin/com/gameperf/desktop/core/AdbBridgeApi.kt` — add the logcat method to the interface so `FakeAdbBridge` can fake it for tests.
- `src/main/kotlin/com/gameperf/desktop/report/ReportGenerator.kt` — render `#sec-conclusions`, `#sec-events` (or merge into existing `#sec-markers`), dual-view metric cards, FPS-chart shaded bands.
- `src/main/kotlin/com/gameperf/desktop/core/grading/FinalScoreCalculator.kt` — no logic change; just receives filtered values via existing `GradingInput` shape. Document that "values are post-filter".
- `sidecar/gameperf_sidecar/main.py` + a new `events.py` module — minimal iOS detection: StoreKit syslog watching + foreground-app-loss windows.
- `src/main/kotlin/com/gameperf/desktop/core/ios/SidecarClient.kt` — new endpoint for iOS event stream.
- `CLAUDE.md` — add a lessons-learned entry once shipped (post-implementation, in the apply phase).

**Test fixtures**:
- `src/test/resources/logcat-fixtures/admob-interstitial.log` — recorded real session, used by `LogcatLineParserTest`.
- Similar fixtures for unity-ads, ironsource, applovin, billing-launch.

## Estimated scope

**Medium-Large**. Reasoning:

- ≈10 new source files + ≈10 new test files = ~20 new files.
- ≈6-8 modified existing files, including a substantial pass over `AppViewModel.kt` and `ReportGenerator.kt`.
- ≈3 user-facing surface changes: capture screen (auto-detected events shown live), report layout (new conclusions + events sections), filtered metrics (dual view).
- 3 mostly-orthogonal pillars (detection / filtering / conclusions) so the change can be split into 3 sub-PRs if desired, but they are coupled at integration time (the dual-view in the report needs all three pieces).
- iOS sidecar work is real but scoped (just enough for StoreKit + foreground loss).
- Comparable in size to the v4.2.5-v4.2.7 reliability audit (CPU per-process, dynamic jank, App Summary parsing) plus the `in-app-dep-bootstrap` change combined.

Recommend splitting tasks across phases: (1) infrastructure (logcat plumbing + signatures + tests), (2) detection (EventDetector + ViewModel wiring + UI live indicator), (3) filtering (FilteredMetricsCalculator + dual-view aggregation), (4) conclusions (rule catalog + engine + report section), (5) iOS sidecar best-effort, (6) report integration polish + regression tests.
