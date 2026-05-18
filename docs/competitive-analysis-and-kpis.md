# Competitive Analysis + Performance KPI Framework

**Project**: android-game-perf-tool-desktop
**Date**: 2026-05-12
**Document status**: Research consolidated 2026-05-12, pending product decisions in §8
**Owner**: TBD

---

## 0. Executive Summary

This document consolidates 7 parallel research observations + 1 internal audit into a single decision-supporting reference for the android-game-perf-tool-desktop project. It covers:

- **Competitive positioning** vs 5 direct competitors (GameBench, PerfDog, Snapdragon Profiler, ARM Streamline, Unity Profiler, Android Studio Profiler) and 5 APM/RUM tools (Firebase Perf, Sentry, New Relic, Embrace, Android Vitals).
- **Market-standard KPIs** sourced from authoritative URLs — Google Android Vitals (Play Store ranking impact), Google RAIL, Apple iOS launch budget (WWDC 2019). Console certs and engine/vendor PDFs explicitly flagged as **not verified** this pass.
- **Event segmentation** for 8 game phases — current tool coverage matrix + state-of-the-art comparison + 3-tier detection plan.
- **KPI scoring framework** — 23 KPIs in a master catalog, per-phase relevance map, 3 scoring models compared (recommendation: Model A Linear anchored on Android Vitals thresholds).
- **Shareable HTML report design** — patterns to copy from Notebookcheck / Android Authority / GSMArena, anti-patterns to avoid, market gaps we can fill.
- **9 product decisions pending** (§8) and **7 next SDD changes** ranked by effort (§9).

Honesty notes preserved: console certs are NDA-bound, engine/vendor PDFs require manual download, ad SDK auto-detection beyond the 6 SDKs already in the catalog is **not verified** and would need an empirical capture lab.

---

## 1. Why this document exists

- **Product goal**: position our tool relative to GameBench, PerfDog, and APM/RUM tools — clarify where we compete, where we complement, and where we have unique value.
- **Scoring system goal**: deliver per-game performance scores anchored on objective, defensible KPIs sourced from official guidance (Google Vitals = Play Store ranking; RAIL = perception; Apple = launch budget).
- **Segmentation goal**: per-phase metric breakdown (cold start vs gameplay vs ads vs loading) — a single average FPS across a 10-minute session hides the problems that actually matter.
- **Decisions this doc unblocks**: see §8.

---

## 2. Competitive landscape

### 2.1 Direct competitors (game perf profilers)

| Tool | Mechanism | Vendor coverage | Price | Sampling rate | Segmentation | Overhead | Cloud required | Our gap vs them |
|------|-----------|-----------------|-------|---------------|--------------|----------|----------------|-----------------|
| **GameBench** | On-device native `.so` reads driver perfcounters + SDK/Injector | Mali + Adreno (~90% Android GPU market). PowerVR explicitly NOT supported. | Subscription (paid). Free Pro Android Lite for indie. | 1 Hz fixed | Programmatic Markers (SDK) + logcat protocol `gb_marker_start - <name>` / `gb_marker_stop - <name>` | 3.8% CPU full profiling Pixel 6 (per docs); 0.5% CPU SDK Subway Surfers S24U | Web Dashboard cloud default; self-hosted enterprise tier | GPU usage% (Sprint 1 in progress), network bandwidth, programmatic markers via SDK |
| **PerfDog (Tencent/WeTest)** | PerfDog Service on-device daemon + adb host client. Plug-and-play, no SDK/root. Mandatory cloud sync. | 11 platforms: Android, iOS, Win, Switch, VR (Quest/Pico), Wear (cross-platform, vendor-agnostic) | Free for non-profit / research; commercial via sales contact (perfdog_net@tencent.com, NDA pricing); RMB monthly subscription | 1 Hz default, configurable | Custom Data Extension SDK (instrumented, 7 langs, ~20k calls/sec, 50 metrics max) + Tags + Scenes annotations | <1% CPU marketing-claimed (NOT independently audited) | **YES MANDATORY** — uploads to perfdog.qq.com (China) or perfdog.wetest.net (Int'l). Data silos separated by compliance, no exchange between them. Tencent account required. Closed methodology. | **Closeable** (planned): FPower (§9 #8), CPU% freq-normalized (§9 #9), Jank formula (§9 #10), CLI/headless (§9 #11). **Explicitly NOT closing**: GPU HW counters (vendor partnerships), cloud dashboard (anti-positioning), engine SDK metrics (anti-no-SDK), production RUM, touch latency, 200k-app benchmark library. Also unique to them: multi-device GUI ≤3 (we plan match + unlimited via CLI). |
| **Snapdragon Profiler (Qualcomm)** | Desktop + adb + Qualcomm Adreno driver perfcounters | **SNAPDRAGON ONLY** (Adreno). Useless on Mali / PowerVR / Xclipse. | FREE (Qualcomm dev account) | HW-counter capable | Frame capture (GL/Vulkan), system trace | Negligible (HW counters) | NO (local) | Vendor-locked, dev-time deep-dive, not a QA harness. Needs `echo 1 > /sys/class/kgsl/kgsl-3d0/perfcounter` on Android 13+ |
| **ARM Streamline (Arm Performance Studio)** | gatord agent inside APK wrapper, OR Perfetto data sources on unrooted Mali | **MALI ONLY**. ~50% market. | FREE (Arm dev account) | HW-counter capable | Source-code-level | Negligible | NO (local) | Vendor-locked mirror of Snapdragon Profiler. Mali per-generation sampler quirks |
| **Unity Profiler + Profile Analyzer** | Built INTO Unity engine. Requires development build OR IL2CPP+development flag | **Unity games only**. Doesn't profile Unreal/native/Cocos/Godot. | FREE (Unity license) | Per-frame engine markers | ProfilerMarker (`Profiler.BeginSample`/`EndSample`) | Engine integration cost | NO | Engine-locked, requires dev build (NOT shippable APK), no device-level cost (temp, GPU clock, system mem, battery) |
| **Android Studio Profiler (Google)** | adb + Perfetto + Simpleperf; in-IDE | All Android | FREE | system trace | Trace events, atrace | Standard adb | NO | (1) Requires `debuggable=true` (can't profile shipped release APKs). (2) IDE-bound, no headless/CI. (3) No long-session compare. (4) No team dashboard. (5) Single-app focus, not fleet/lab. |
| **Ours (GamePerf Desktop v4.4.1)** | adb-only host-side. Pure /sys reads + dumpsys + logcat. No on-device install. | Multi-vendor CPU/RAM/thermal/FPS already. Mali+Adreno GPU **pending Sprint 1** (paused). PowerVR Sprint 1.5 candidate. | Free / internal MIT license | 0.5–1 Hz baseline | Auto event detection (6 SDKs: AdMob/UnityAds/IS/AppLovin/Meta/PlayBilling) + manual markers (5 types) | Target <2% CPU host (1-2% measured); device overhead TBD | **NO cloud, ever.** No account. 100% local. | GPU usage% (Sprint 1), network bandwidth (Sprint 2), per-core CPU freq, battery mA, build-over-build trends, headless CLI |

**Cross-cutting insights** (from obs #303 + #298 + #288):

1. **PerfDog is the only true cross-vendor + cross-platform adb-style tool** in the set. Closest functional competitor alongside GameBench.
2. **Snapdragon Profiler & ARM Streamline are vendor-locked deep-dive dev tools**, not QA harnesses. They give HW perfcounter detail we can't replicate via adb alone — complementary, not competing.
3. **Unity Profiler & Android Studio Profiler are engine/dev tools requiring dev builds.** Unusable on shipped release builds. This is the #1 reason GameBench/PerfDog have a market.
4. **None of the free tools** (Android Studio, Snapdragon, ARM Streamline, Unity) **provide a multi-session/multi-device fleet view.** Paid-tool feature. Our tool can hit a sweet spot: local sessions stored on disk + side-by-side comparison, without cloud.
5. **All 5 require a setup tax**. adb-only with `pm list packages` to pick any installed app on any device = the lowest setup friction possible.

**Our positioning moat**:
- **Data sovereignty** (no cloud, no account) — beats PerfDog, beats GameBench cloud-default.
- **Methodology transparency** — open-source MIT, documented /sys/dumpsys/Perfetto sources. Beats ALL competitors (closed).
- **Release-APK profiling** (no debuggable flag) — beats Unity & Android Studio Profilers. Parity with PerfDog/GameBench.
- **Auto event detection without SDK** (v4.4.0, 6 SDKs in catalog) — GameBench requires SDK or manual markers.
- **CI/headless capable** (planned) — beats AS Profiler and Unity Profiler.
- **Hardware-aware grading + qualitative conclusions** (v4.4.0) — GameBench gives data, we tell you what to do.

Reference observations: `research/competitive-analysis-direct-tools` (#303), `research/gamebench-comparison` (#288), `research/gamebench-docs-gpu-section` (#298), `roadmap/gamebench-parity` (#289).

### 2.2 Adjacent APM / RUM tools (production monitoring)

| Tool | Mechanism | KPI vocabulary exposed | Thresholds published? | Segmentation | Game-specific? |
|------|-----------|------------------------|------------------------|--------------|----------------|
| **Google Play Console — Android Vitals** | OS-level telemetry, no SDK | ANR rate, crash rate, slow cold/warm/hot start, slow frames (>16ms), frozen frames (>700ms), slow session 30/20 FPS, wake locks, wake-ups, bg network | **YES — authoritative** (see §3.1) | Per-device-model, per-version | YES (game-specific FPS thresholds) |
| **Sentry Performance / Mobile Vitals** | SDK | TTID (Time to Initial Display), TTFD (Time to Full Display, opt-in), Cold/Warm App Start (no hot), Slow frames (auto-adjusts to 60/120Hz), Frozen frames, Frames Delay | YES — Cold start Good <3s / Meh 3-5s / Poor >5s; Warm <1s / 1-2s / >2s; Slow frame >16ms@60fps, >8.33ms@120fps; Frozen >700ms | Per-transaction (root-span = transaction name), up to 10 custom measurements per transaction | Limited |
| **Firebase Performance Monitoring** | SDK (`Performance.startTrace(name)` / `trace.stop()`) | Slow frames (>16ms, bad if >50% slow), Frozen frames (>700ms, bad if >0.1%), Auto traces `_app_start`/`_app_in_foreground`/`_app_in_background`, screen rendering. Custom code traces (max 5 attrs, 32 metrics inc duration). | **No good/bad thresholds published** — historical baseline only | Custom traces; explicit warning: "Avoid creating custom code traces at high frequencies (e.g. per frame in games)" | No (general mobile) |
| **New Relic Mobile** | SDK | Launch times, crash analytics, network. Cites Google + Apple verbatim. | Cold <5s (Google), Hot <1.5s (Google), Apple cold ≤400ms / hard limit 20s | Per-screen, per-interaction | No |
| **Embrace** | SDK | Startup, crash rate, ANR rate, region, session duration, churn/retention, user termination, key user actions, memory, connectivity | NO hard thresholds — segmentation philosophy (segment by high-value users / region / device) | Per-event/per-screen | Some game support |

**Universal event vocabulary** across all 5 APM/RUM tools (obs #304):
- **Lifecycle**: cold/warm/hot start, foreground, background, session start/end, termination
- **Rendering**: slow frame, frozen frame, frame delay, TTID, TTFD, slow session (FPS for games)
- **Stability**: crash, user-perceived crash, ANR, user-perceived ANR, LMK, OOM, handled exception
- **Screen**: screen view/load/transition, Activity start
- **Network**: HTTP start/end, 4xx, 5xx, slow request
- **Resource**: memory pressure, wake lock, wake-up, Wi-Fi scan, bg network, battery
- **User**: permission grant/deny, custom user action, journey step

**Strategic takeaways for our tool** (obs #304):
1. Adopt Google Play Console thresholds verbatim — authoritative + tied to Play Store discoverability.
2. Use cold/warm/hot start trichotomy — universal.
3. Frame budget 16ms @60Hz, 8.33ms @120Hz, 33ms @30Hz (games).
4. Distinguish TTID vs TTFD (Sentry is the differentiator).
5. User-perceived filter for crashes/ANRs is critical.
6. Expose Google's render sub-metrics (Vsync, input latency, UI thread, draw, bitmap upload).
7. Game-specific FPS-based scoring (30 FPS bar, 20 FPS floor) is Play Console standard.

Reference observation: `research/competitive-analysis-apm-rum` (#304).

### 2.3 How games-press reports performance (gameplay-led reporting)

Public phone reviews are the most-read performance reference for end users. Even though their primary audience is shoppers, not developers, they anchor the public's expectations of what "good performance" looks like for a given device tier — which in turn shapes what QA labs and game studios get asked to defend. Outlets are peer-reviewed in their comment threads (GSMArena routinely gets 200-300+ comments per flagship review), so their methodology, while informal, is the closest thing to a community-accepted standard for mobile gaming benchmarks outside vendor-funded labs. Understanding their reporting vocabulary tells us which KPIs and visualizations our own HTML report will be measured against by non-developer readers.

This section catalogs **6 outlets**, with concrete dated sample URLs, focused on Android-mobile-game performance (not laptop/GPU coverage). §2.4 below covers public benchmark press more broadly.

| Outlet | Typical KPIs reported | Chart styles | Phase segmentation? | Tool disclosed? | Sample article (access date) |
|--------|----------------------|--------------|----------------------|------------------|------------------------------|
| **GSMArena** | GeekBench 6 single + multi, AnTuTu v10 + v11, 3DMark Wild Life Extreme (Highest), 3DMark Solar Bay (Ray Tracing). CPU + GPU stress-test screenshots over ~20 min. | Horizontal bar comparisons vs 6 peer devices per benchmark; each row annotated with SoC + RAM + native resolution. Sustained-perf shown as **screenshot of in-app stress-test graph** (not interactive). Prose verdict for thermal throttling. | **No** real-gameplay phase split. Synthetic-benchmark-bucketed only (CPU vs GPU vs ray-tracing). | Synthetic tools named (Geekbench, AnTuTu, 3DMark, in-app stress tests). **In-game FPS tool not disclosed** — no per-title gameplay numbers in performance section. | Samsung Galaxy S26 Ultra review, "Software and performance" (page 4 of 6), GSMArena Team, 06 March 2026. <https://www.gsmarena.com/samsung_galaxy_s26_ultra-review-2939p4.php> (accessed 2026-05-18) |
| **Notebookcheck** | Geekbench 6.6 single + multi, AnTuTu v10, **PCMark for Android**, CrossMark, BaseMark OS II, UL Procyon AI Inference, AImark, Geekbench AI, AI Benchmark. Display response times rise/fall in ms. PWM flicker (Hz). iperf3 Wi-Fi. Composite "very good (89%)" overall score. | Bar comparisons vs predecessor + class average + 4 named competitors, each with explicit %-delta. Downloadable **SVG/PNG award badge** for the rating. Per-benchmark detailed tables. | Per-benchmark, not gameplay. Camera-led reviews (X300 Ultra is camera-focused) may omit dedicated per-game FPS. | All synthetic tools named. **GameBench is explicitly named in their per-title FPS sections** (in non-camera-led reviews — see existing §2.4 reference and prior obs #305). Real-gameplay FPS section absent here. | Vivo X300 Ultra review, Marcus Herbrich, published 2026-05-12, updated 2026-05-15. <https://www.notebookcheck.net/Vivo-X300-Ultra-Review-Best-2026-camera-smartphone-with-a-surprising-number-of-weaknesses.1293093.0.html> (accessed 2026-05-18) |
| **Android Authority** | Geekbench 6 (CPU), 3DMark Solar Bay (ray tracing), **3DMark Wild Life Extreme Stress Test (20 loops, line graph)**, paired battery drain across 4K playback / 4K record / camera. Charging curve graph (time vs %). | Bar charts for peak; **line graph for stress test showing 20 loops with multiple device traces overlaid** (S26 Ultra vs OnePlus 15 vs iPhone 17 series vs Pixel 10 Pro). Charging-time bars. | **Per-usage-type only** (4K playback vs 4K record vs camera) in battery section. No per-gameplay-phase split. Sustained perf modeled as time-on-test, not in-game scene change. | Synthetic tools named. **No in-game FPS measurements published** in this review. | Samsung Galaxy S26 Ultra review, Ryan Haines, 31 March 2026. <https://www.androidauthority.com/samsung-galaxy-s26-ultra-review-3652705/> (accessed 2026-05-18). Deeper benchmark deep-dive linked: `samsung-galaxy-s26-ultra-benchmarks-3652232`. |
| **Tom's Guide** | Battery life, performance (synthetic), display (refresh rate, brightness, peak nits) as the three pillars for gaming-phone evaluation. Per-phone subjective verdict on "feel" while gaming. | "Best-of" roundup format — comparative scoring across categories with prose justification. Per-device specifications cards. | **No phase segmentation in performance section.** Game examples cited anecdotally (e.g. *Ex Astris* shown on ROG Phone 9 Pro). | Methodology referenced in "How we test gaming phones" section but specific FPS-capture tool not named in the article body. | "The best gaming phone 2026 — I tested them all to crown a winner", Richard Priday, last updated 30 March 2026. <https://www.tomsguide.com/best-picks/best-gaming-phones> (accessed 2026-05-18) |
| **XDA Developers** | Best-gaming-phones roundup format: SoC spec, display Hz, RAM, storage, battery as raw spec dump. Gaming-specific hardware (shoulder triggers, cooling fans, RGB) called out qualitatively. | Card-per-device with pros/cons. No comparative benchmark charts in the roundup format. | **No phase segmentation.** No per-game FPS or per-scene data in roundup. | **Methodology not disclosed** in the roundup; FPS measurement tooling not named. | "Best gaming phones in 2024", Ryan-Thomas Shaw, last meaningfully updated Nov 2023 (article still surfaced as current). <https://www.xda-developers.com/best-gaming-phones/> (accessed 2026-05-18). **Needs verification** — XDA's individual phone reviews (e.g. ROG Phone 9 Pro) returned 404 on direct URLs during research; recent dedicated phone-performance pieces by XDA could not be confirmed in 2 attempts. |
| **AnandTech (archived)** | **Needs verification — site is in archive-only mode since 2024; no recent gaming-perf reviews surfaced in 2 attempts.** Historical archives are known for rigorous CPU/GPU microarch deep-dives with HW perfcounter data; mobile gaming FPS coverage was less consistent. | Historical pattern: detailed per-test prose + per-benchmark line graphs. | Historical: per-benchmark, not gameplay-phase. | Historical: vendor tools (Snapdragon Profiler-style) named when used. | Archive only; no fresh URL captured this pass. |

**Patterns we can copy** (port to our HTML report):

- **`Ø avg (min-max)` shorthand for FPS** (Notebookcheck convention, confirmed in prior obs #305) — most information per pixel of any framerate visualization seen across outlets.
- **Paired score + temperature line graph on a shared time axis** (Android Authority's 3DMark stress-test overlay style) — causality between thermal envelope and perf collapse becomes visible at a glance.
- **Per-row device annotations on comparison bars** (GSMArena's "SoC + RAM + native resolution" labels) — readers immediately understand why two devices score differently.
- **Multi-competitor overlay on sustained perf** (Android Authority overlays 4 devices on the same 20-loop stress curve) — turns "is this device throttling?" from anecdote into ranking.
- **Composite score + explicit category bars** (Notebookcheck's "very good (89%)" header + per-category bars below) — gives both a single shareable number and an auditable breakdown. We already have hardware-aware grading + per-KPI category surface — formalize the dual presentation.
- **Downloadable rating badge** (Notebookcheck's SVG/PNG award) — turns the report into shareable social content for game studios that ship our scores externally. Low-cost feature, high marketing leverage.
- **Tool methodology disclosure block** (Notebookcheck explicitly names every benchmark used, with version) — our reports already track tool provenance internally; surfacing it visibly turns transparency from an architectural fact into a trust signal.

**Anti-patterns to avoid**:

- **Single-number averages across long sessions** (universal across all 6 outlets) — hides the very segmentation problem the v4.4.0 event detector exists to solve. Our report MUST default to per-phase splits, not a session-wide mean.
- **Synthetic-only methodology with no real-gameplay numbers** (GSMArena, Notebookcheck camera-led reviews, Android Authority's S26 Ultra piece all skip per-title in-game FPS). 3DMark Wild Life is a great worst-case probe but is not how the game your QA team is testing actually behaves.
- **Undisclosed tools for the gameplay portion** (Tom's Guide, XDA roundups) — readers cannot reproduce or audit the result. Our open methodology + GitHub-hosted spec is the inverse anti-position.
- **Screenshot-only stress graphs** (GSMArena's CPU/GPU stress test is a screenshot of an in-app graph) — non-interactive, non-zoomable, no time anchor. Our timeline player and CSV export close this gap.
- **Mismatched device tiers without explicit tier labels** (all outlets compare flagship-to-flagship implicitly but never anchor "expected FPS for tier X"). Our hardware-aware grading explicitly normalizes by SoC class — formalize this in the report header.

See §5 (KPI catalog) for how these reporting patterns map to which specific KPIs we already expose, and §7 (Shareable HTML report) for the implementation slot.

### 2.4 Public benchmark press (inspiration for our shareable report)

| Source | Metrics shown | Chart types | Scoring | Segmentation | Raw data | Lesson for us |
|--------|---------------|-------------|---------|--------------|----------|---------------|
| **GSMArena** | GeekBench 6, AnTuTu v10/v11, 3DMark Wild Life Extreme (Highest + Lowest sustained), 3DMark Solar Bay, thermal throttling % | Horizontal bar comparisons vs ~10 reference devices + phone thumbnail/chip/RAM/resolution per row. Throttling = screenshot. | Raw numeric, no letter grade. Prose verdict. | By benchmark type, NOT by gameplay phase | Screenshots only, no CSV/JSON | High info density via per-row device annotations. **Avoid**: screenshot-only graphs (non-interactive) |
| **Notebookcheck** | 3DMark (8 sub-tests), GFXBench Manhattan/Car Chase/Aztec. Real-gameplay FPS via **GameBench** integration (named). Format `Ø60 (59-61)` for avg(min-max). | Comparison bars vs predecessor + class avg + 4 competitors with %-delta. Per-game FPS as line graph. | Composite "very good (89%)" + downloadable SVG/PNG award badge | By GAME (Genshin, PUBG) AND quality preset (Smooth/HD/Ultra) | Not downloadable, but full inline tables | **Top pattern to copy**: `Ø avg (min-max)` shorthand. **Avoid**: opaque 89% composite |
| **Android Authority** | GeekBench 6, PCMark Work 3.0, 3DMark Wild Life Extreme Stress Test (20 runs), 3DMark Solar Bay Stress Test, peak temp | Bar comparisons + stress-test LINE GRAPHS per loop iteration. **Paired** score + temperature line graphs (causality at a glance) | %-deltas, no numeric rating | By SKU within family | No | **Top pattern to copy**: paired score+temperature timeline |
| **Eurogamer / Digital Foundry** | Capped vs uncapped framerate, frame-pacing, dropped frames per scene, visual feature comparisons vs console, thermal throttling | Real-time FPS overlay on captured footage, side-by-side video | Prose only | YES — by gameplay PHASE (interior/exterior, combat/traversal, cutscene). DF's signature | Never | **Top pattern to copy**: per-gameplay-phase segmentation |
| **NanoReview** | AnTuTu v11, GeekBench CPU/GPU v6, 3DMark Steel Nomad Light, Cinebench 2024 | Tables, composite SoC rating 0-100 | Ranked lists, crowdsourced | None — synthetic only | Per-submission entries public with timestamps | Closest to "downloadable raw" model. Possible **external anchor for device tier classification** (§6.3) |

**Recurring patterns across press** (obs #305):
1. FPS avg + min + max is universal. Notebookcheck `Ø60 (59-61)` is the cleanest shorthand.
2. **1% low / 0.1% low frame-time percentiles are PC-press standard, mobile press skips them** — opportunity for us to bring PC-grade rigor to mobile.
3. "Stability %" is GameBench-coined and now everywhere — Notebookcheck literally names GameBench in their reviews.
4. Thermal throttling curves over 20+ run stress loops are the standard for "sustained perf".
5. Per-quality-preset segmentation common; per-gameplay-phase rare outside Digital Foundry (and only on YouTube).
6. **No outlet publishes downloadable raw data.** CSV/JSON export is a market gap.
7. Composite ratings (Notebookcheck 89%) are editorial, not data-driven — opaque.

**Our 4 differentiators (market gaps)**:
- Downloadable raw CSV/JSON
- Frame-time percentiles (p1, p0.1) by default — PC-grade rigor on mobile
- Per-gameplay-phase segmentation (load vs combat vs cutscene)
- Joint FPS + thermal + power-draw timeline on a single shareable URL

Reference observation: `research/competitive-analysis-press-reports` (#305).

### 2.5 How competitor tools visually present performance data

Even though our tool ships a desktop GUI and a static HTML report (not a cloud dashboard), the **visual grammar** that QA leads, producers, and devs already speak comes from these competitors. Anyone who opens our report after using GameBench or PerfDog instantly compares: did we surface FPS the same way? Are jank events highlighted? Where's the comparison view? Borrowing the right patterns is shorthand for credibility — and avoiding the wrong ones (opaque composites, hidden tabs) keeps the report scannable. This section catalogs how 8 competitor tools present data and distills the patterns worth copying for §7 report design.

> **Scope note**: Many competitor product pages are JS-SPA marketing sites (PerfDog wetest, Snapdragon Profiler, AS Profiler) that don't expose dashboard layouts via static fetch. Where direct UI verification was impossible, the row is marked **"needs verification — public screenshots not found"** and inferred details are explicitly tagged as such. We never invent UI details we couldn't see in a public page.

#### Comparison table — visual presentation per tool

| Tool | Dashboard layout | KPI cards shown | Main chart types | Severity color coding | Drill-down? | Comparison view? | Sample URL (access date) |
|------|------------------|-----------------|-------------------|------------------------|-------------|------------------|--------------------------|
| **GameBench Web Dashboard** | Multi-page: Home → Trends Explorer → Sessions → Session Detail (Metrics Timeline / Summary / FPS / Power / CPU / GPU / Memory / Network / Markers / Comparison). Session detail uses a **screenshots strip on top + interactive timeline + per-region metrics summary table + stacked Metrics-Over-Time charts** below. | FPS Median + min-max range, FPS Stability %, FPS Variability, **1% Low FPS**, Frame Time (95th percentile), **Big Janks /10 min**, CPU avg, GPU avg, Memory avg with peak, Network In/Out | **Time-series line charts** (Primary + optional "Compare with" overlay), **screenshot thumbnails strip** synced to timeline, **region/marker colored blocks** on the timebar, **distribution charts** (median + Q1-Q3 box) on Trends Explorer per build, **device performance table** with trend % per device | Trend indicators per chart ("Declining" / "Trending Up"), **AFFECTED** and **OUTLIER** badges on device rows, quality-target threshold lines drawn on charts (e.g. "FPS Median ≥ 30") | YES — click any timeline point to see the screenshot at that moment; click region → metrics scoped to region; click metric chart → detailed module page | **YES, primary feature.** Select 2+ sessions in Sessions table → "Compare" → opens summary table (one row per session, index-numbered) + **overlay chart on shared timeline**, color-coded per session. Optional marker-region scoping. | <https://docs.gamebench.net/docs/web-dashboard/session-detail/metrics-timeline/> (2026-05-18); <https://docs.gamebench.net/docs/web-dashboard/session-detail/comparison/> (2026-05-18); <https://docs.gamebench.net/docs/web-dashboard/trends/> (2026-05-18) |
| **PerfDog Desktop + Web** | Real-time multi-pane during capture: live metric streams on top (FPS / CPU / Memory / Battery / Network) with shared X axis; Tags + Scenes overlayed as vertical markers; post-capture goes to Web Dashboard with project/case/task hierarchy. Marketing pages emphasize 5 product modules (General Test / In-depth / Network / PerfDogService / Web Dashboard). | Marketing pages list **FPS, Jank, Big Jank, Stutter, CPU, GPU, Memory, Battery (FPower mW/frame), Network, Temperature, Frame Time**. Specific KPI-card layout not verifiable from public pages (auth wall). | Public docs reference real-time **multi-stream line charts** with shared time axis and overlay **Tags/Scenes annotations**. Specific chart catalog not verifiable (engram obs #328: help center gated). | Not verifiable from public pages | Inferred from marketing: timeline click → metric snapshot. **Needs verification — public screenshots not found 2026-05-18.** | YES (marketing claim: "团队合作" cross-session and cross-device comparison via Web Dashboard). Specific UI not verifiable. | <https://perfdog.qq.com/> (2026-05-18); <https://perfdog.wetest.net/> (2026-05-18); marketing pages only — Help Center is auth-gated per engram obs #328 |
| **Snapdragon Profiler (Qualcomm)** | Desktop IDE-style: tree-style data source picker on the left, **multi-track timeline** (CPU per-core / GPU stages / driver perfcounters / system events) in the main canvas, frame-capture viewer in a separate window for GL/Vulkan frame dissection. | Live metric readouts (FPS, GPU utilization, CPU per-core %, memory) along the timeline header during capture — not "summary cards" in the dashboard sense. | **Stacked time-series tracks** (Perfetto-style), **GPU pipeline stage breakdown** bars, **frame capture flame view** (per draw call cost). | Threshold-based highlighting in metric tracks (red spikes for over-budget). Specific palette not verifiable from public docs. | YES — deep drill: click frame in trace → frame capture → per-draw-call cost / shader / texture binding. Vendor-deep-dive workflow. | NO multi-session compare baked in; user manually opens two captures side by side. **Needs verification — public screenshots not found 2026-05-18** (Qualcomm dev portal requires JS for full docs). | <https://www.qualcomm.com/developer/software/snapdragon-profiler> (2026-05-18, JS-SPA — landing only); inferred from documented capabilities |
| **ARM Streamline / Performance Studio** | Desktop app — multi-track timeline (Perfetto-derived layout) with **CPU per-core flame chart on top, GPU Mali counters below, source-code-level call sites linked at the bottom**. | Live counters in track headers (cycles/frame, cache miss %, GPU FMA / texture / load-store). Not "card-based summary." | **Per-core CPU flame chart**, **per-stage Mali GPU counter time-series**, **source-line attribution** (hot lines highlighted in source view). | Heat-coded source lines (red = hot path), color-graded counter tracks. | YES — click counter spike → source location of hot path (the signature ARM feature). | NO baked-in compare; manual side-by-side. **Needs verification — public screenshots not found 2026-05-18** (developer.arm.com is a JS app, no static screenshots reachable). | <https://developer.arm.com/Tools%20and%20Software/Streamline%20Performance%20Analyzer> (2026-05-18) |
| **Unity Profiler** | **Stacked module charts** in vertical strips inside the Unity Editor: CPU Usage / Rendering / Memory / Audio / Video / Physics / UI / Global Illumination / etc., each module is a colored area chart. Selecting a frame on any chart opens a **bottom details pane** with hierarchy / timeline view for that frame. | No top-level summary cards — every module *is* its own at-a-glance chart. Frame Count default 2,000, configurable 600-4,000. | **Stacked area charts per subsystem**, **per-frame flame chart** (Timeline view), **call hierarchy table** (Hierarchy view). | Module-specific color coding (CPU = blue/yellow/orange by category, Rendering = green/red); no global RAG. **No reference threshold line on frame-time chart** by default — user must read the absolute ms value. | YES — frame click → detail pane → hierarchy / timeline / module-specific drill. | NO multi-session compare in the Profiler itself. Separate **Profile Analyzer package** does this — distribution histograms across N frames, comparison of two captures. | <https://docs.unity3d.com/Manual/Profiler.html> (2026-05-18); <https://docs.unity3d.com/Manual/profiler-window-navigating.html> (2026-05-18) |
| **Android Studio Profiler** | **Vertically stacked metric strips** (CPU / Memory / Network / Energy) inside the Profile tool window, shared time axis. Click any strip to "expand" into its dedicated session — System Trace / Method Trace / Heap Dump / Network Inspector with their own deep views. | Live metric readouts along strip headers (CPU %, memory MB, KB/s up/down). No KPI summary card. | **Stacked time-series strips** in the unified view, **flame chart + call chart + top-down/bottom-up tables** in CPU drill-down, **heap object table + retention graph** in Memory drill-down. | Color coding per subsystem (CPU = thread colors, Memory = allocation type), red highlights for ANRs and OOM events. | YES — strip click → opens a full-screen tool with its own drill-down. **Critical info hidden behind clicks** is a known UX complaint. | NO. AS Profiler is single-session, IDE-bound. **Needs verification — public dashboard screenshots not found 2026-05-18** (developer.android.com fetch failed twice via the agent's webfetch). | <https://developer.android.com/studio/profile/> (2026-05-18 — fetch failed); inferred from widely-documented behavior |
| **Firebase Performance Monitoring** | **Web console** with two top-level views: (1) Dashboard / Issues, (2) On-device aggregate. Per-trace and per-network-request pages show distribution charts and threshold breaches. Console-driven, not session-driven (designed for production fleet aggregate). | Aggregate KPIs: % slow frames, % frozen frames, p50/p75/p90 startup time per platform/country/device/OS-version. **No per-session timeline** — Firebase is aggregate-by-design. | **Distribution histograms** (response times, frame times), **time-series of aggregate metric over date range**, **breakdown bar charts by attribute** (country / device / OS). | Threshold-based: e.g. "Bad if >50% slow frames" (Vitals-aligned). Color: red for breach, green for OK. Issues view surfaces threshold violations. | YES — click attribute breakdown → filter to that attribute → drill metric. | YES (cross-version): Firebase compares versions / dates natively for aggregate metrics. Not a per-session compare. | <https://firebase.google.com/docs/perf-mon> (2026-05-18). Note: explicit warning against per-frame custom traces in games. |
| **Sentry Mobile Vitals** | **Performance Score quadrant** (Good/Meh/Poor banding) on top, then **App Starts**, **Screen Loads**, **Slow & Frozen Frames**, **Frames Delay**, **TTID/TTFD** as separate widgets. Per-trace drill-down shows span waterfall with measurements in the right sidebar. | App Start Cold (<3s good, 3-5s meh, >5s poor), App Start Warm (<1s / 1-2s / >2s), Slow frame rate %, Frozen frame rate %, Frames Delay (ms), TTID, TTFD. **Explicit Good/Meh/Poor bands** on every vital. | **Aggregate time-series** with percentile bands (p50/p75/p95/p99), **distribution histograms**, **span waterfall** in trace detail (Suspect Spans highlighted in red). | **RAG bands** baked in: Good = green, Meh = amber, Poor = red — applied per-metric using the published thresholds. This is the clearest example of explicit RAG in any tool reviewed. | YES — click vital → screen-loads / app-starts feature page with the same metric filtered to a specific screen/transaction. Suspect Span highlight → root span. | YES (release-to-release): Sentry compares vital deltas across releases natively. Per-session traces also stackable. | <https://docs.sentry.io/product/insights/mobile/mobile-vitals/> (2026-05-18); <https://docs.sentry.io/product/dashboards/sentry-dashboards/mobile/mobile-vitals/> (2026-05-18) |

#### Best visualization patterns observed (worth borrowing)

- **Sentry's explicit RAG bands per vital with PUBLISHED thresholds** — every vital has Good/Meh/Poor cutoffs visible right next to the value. Zero ambiguity, instant scan. Best-in-class for "is this number good or bad?"
- **GameBench's screenshot strip synced to the metrics timeline** — visual context for every frame-rate dip. Eliminates the "what was happening on screen?" question that all other tools force you to guess.
- **GameBench's session-comparison overlay on a shared timeline** with color-coded per-session lines + index-numbered summary table — the cleanest implementation of multi-session compare seen.
- **GameBench's Trends Explorer distribution charts** (median + Q1-Q3 box per build) — surfaces variance across builds in a way single-number deltas hide. Pair with the **AFFECTED / OUTLIER badges** on the per-device table to call out regression hotspots.
- **PerfDog's marker/tag annotations overlaid on the metric timeline** — vertical lines tagged with the scene/event name turn raw curves into a narrative. (Pattern is also in GameBench markers — both tools converged on this.)
- **Sentry's Suspect Span highlighting in trace waterfalls** — auto-flags the span that caused the slow trace. Surfaces causality without forcing the user to scan a long list.
- **Unity Profiler's per-subsystem stacked area charts on a shared X axis** — at-a-glance correlation across CPU / GPU / Rendering / Memory. The shared time axis is essential.
- **Firebase's per-attribute breakdown bars** (country / device / OS) — the right pattern for "where is the problem worst?" Useful for our per-device-tier rollups in §6.3.
- **GameBench's `Big Janks /10 min` rate-normalized KPI card** — counts normalized to session length so short and long sessions compare directly. We should do the same for our jank counts.
- **ARM Streamline's source-line heat coding** — not directly portable (we don't have source attribution), but the principle of "color the line where the problem is" applies to our event ranges (red-tint the gameplay region where FPS p1 was bad).

#### Anti-patterns observed (avoid)

- **Unity Profiler's frame-time chart in microseconds with no 16ms / 33ms reference line** — user must mentally convert and remember the target. Always draw the budget line.
- **AS Profiler's "critical info hidden behind clicks"** — strips collapse the most important sub-metrics (ANRs in Memory, slow draw commands in CPU). Anything above the threshold should be visible without a click.
- **Opaque single-number composites with no breakdown** (Notebookcheck "89%", any non-transparent "performance score") — uninterpretable for engineers. If we publish a composite, always show the per-category breakdown next to it.
- **Aggregate-only views that erase per-session detail** (Firebase Perf, Embrace) — fine for production RUM, wrong for QA labs. Our positioning depends on per-session fidelity.
- **JS-rendered marketing pages that hide actual product UI behind login walls** (PerfDog wetest help center, Qualcomm dev docs) — anti-pattern in their *own positioning*; opportunity for us: publish the actual report screenshots in our docs, no login.
- **Color palettes that rely on red/green only** — accessibility fail for color-blind users. RAG must include shape or text label, not just color.

#### Recommendations for our HTML report (ordered by perceived value)

1. **Adopt Sentry-style RAG bands on EVERY KPI card** with the published threshold next to the value — anchor on §3.1 Vitals + §3.6 PerfDog thresholds. Use shape/text in addition to color (anti-pattern avoidance). Maps to §5.1 Master KPI catalog. **— Applied in v4.7.0**
2. **Add a screenshot strip synced to the FPS+Thermal timeline** (GameBench pattern). Screenshots can be sampled at 1 Hz via `adb screencap`; embed as inline base64 in the self-contained HTML (§7.6 decision). High visual impact, modest implementation cost.
3. **Add explicit reference budget lines on frame-time charts** (16.6 ms / 33.3 ms / 8.3 ms — §3.4) — eliminate the Unity Profiler anti-pattern. One-line render addition. **— Applied in v4.7.0**
4. **Add per-phase distribution boxes (median + p1 + p99 + min/max) on top of the existing per-phase tables** (GameBench Trends pattern) — surfaces variance that single-number averages hide. Bundle with the §9 #3 `shareable-html-report` change. **— Applied in v4.7.0**
5. **Add a session-comparison view** with overlaid timelines color-coded per session + an index-numbered summary table (GameBench pattern). Aligns with §9 #13 `multi-device-capture`. Defer to post-CLI.
6. **Add Suspect-Phase highlighting on the per-phase summary** (Sentry pattern) — auto-flag the phase with the worst KPI deltas vs the gameplay baseline. Aligns with the §4 event segmentation + qualitative conclusions (v4.4.0).

#### Closing line

These patterns feed directly into §7 (shareable HTML report design) — recommendations #1, #2, #3 are bundled into the `shareable-html-report` change (§9 #3), and #5 is bundled into `multi-device-capture` (§9 #13). The anti-patterns inform what NOT to ship even by accident.

---

## 3. Market-standard KPIs (official sources)

### 3.1 Google Android Vitals — direct ranking impact

> Source: <https://support.google.com/googleplay/android-developer/answer/9844486> (retrieved 2026-05-12). Google Play penalizes app discoverability when these thresholds are crossed. Evaluation window: last 28 days. Per-device-model bad behavior also tracked separately.

**Stability — Core Vitals (impacts Play Store discoverability)**

| KPI | Overall bad threshold | Per-device bad threshold | Notes |
|-----|-----------------------|--------------------------|-------|
| User-perceived ANR rate | **≥ 0.47%** of DAU experience ≥1 user-perceived ANR | **≥ 8%** on single device model | Only "input dispatching timed out" ANRs count |
| User-perceived crash rate | **≥ 1.09%** of DAU experience ≥1 user-perceived crash | **≥ 8%** on single device model | Foreground/foreground-service only |
| Multiple-ANR rate | (indicator, no published threshold) | — | ≥2 ANRs same day — flags loops |
| Multiple-crash rate | (indicator, no published threshold) | — | ≥2 crashes same day |
| User-perceived LMK rate | (no published threshold yet) | — | LMKs during foreground |

**Startup time — slow thresholds**

| Launch type | Slow threshold |
|-------------|----------------|
| Cold start | **≥ 5 seconds** |
| Warm start | **≥ 2 seconds** |
| Hot start | **≥ 1 second** |

Note: day's MAX per system state recorded (not average). Reported at 90th/99th percentile per session.

**Rendering — Games (NEW core vital)**

| KPI | Definition | Notes |
|-----|------------|-------|
| Slow session rate (30 FPS) | % daily sessions where **>25% of frames missed 30 FPS** (75th percentile) | "Most games should aim for 30 FPS+" |
| Slow session rate (20 FPS) | % daily sessions where **>25% of frames missed 20 FPS** (75th percentile) | **"Play will start steering users away from games that cannot achieve 20 FPS on their phones"** (exact quote) |

Frame data via SurfaceFlinger on Android 9+. Monitoring begins 1 minute after game start. Includes OpenGL, Vulkan, Android UI toolkit frames.

**Rendering — Apps (UI Toolkit)**

| KPI | Bad threshold |
|-----|---------------|
| Excessive slow frames | **>50% of frames had render time >16 ms** per session |
| Excessive frozen frames | **>0.1% of frames had render time >700 ms** per session |

Sub-metrics for frames >16ms:
- High input latency: input events **>24 ms**
- Slow UI thread: **>8 ms**
- Slow draw commands: GPU draw command submission **>12 ms**
- Slow bitmap uploads: bitmap upload to GPU **>3.2 ms**
- Missed Vsyncs (count per frame)

**Battery — Bad Behaviors**

| KPI | Bad threshold |
|-----|---------------|
| Stuck partial wake locks | ≥1 wake lock **>1 hour** per battery session |
| Excessive wake-ups | **>10 wake-ups/hour** per battery session |
| Excessive Wi-Fi scans (bg) | **>4 scans/hour** per battery session |
| Excessive network usage (bg) | **>50 MB/day** in background per battery session |
| Excessive battery usage (watch face) | **>4.44%/hour** |
| Excessive partial wake locks (BETA) | ≥1 wake lock totaling **>3 hours** per battery session |

**Core Value (retention)**

| KPI | Threshold |
|-----|-----------|
| DAU/MAU | **<8%** triggers warning |
| User loss rate | **>5%** triggers warning |

#### 3.1.1 Google Play Vitals 2024 thresholds — Google official source

> Source: Google Play Console — Android Vitals "bad behavior thresholds" (Octubre 2024, recopilado y confirmado vía Gemini deep-dive). Engram observation: `#424 — research/google-play-vitals-2024-thresholds`. These are the **store-gating** thresholds: crossing them triggers direct Play Store penalties (visibility reduction, removal from Top Charts, discovery throttling) — not advisory, not "best practice", but actual ranking signals applied by Google.

| Métrica (Vital) | Umbral Máximo General | Umbral Máximo Por Dispositivo | Penalización si lo superas |
|-----------------|-----------------------|-------------------------------|----------------------------|
| User-Perceived Crash Rate | < 1.09% de los usuarios | < 8.0% en un modelo específico | Reducción visibilidad + avisos tienda |
| User-Perceived ANR Rate | < 0.47% de los usuarios | < 8.0% en un modelo específico | Eliminación Top Charts + recomendaciones |
| Excessive Partial Wake Locks | < 5.0% sesiones (>2h en 24h screen-off) | — | Throttling descubrimiento app |
| Cold Start | < 5s | — | Pérdida prioridad algoritmo calidad |
| Warm Start | < 2s | — | Pérdida prioridad algoritmo calidad |
| Hot Start | < 1s | — | Pérdida prioridad algoritmo calidad |
| Slow UI Sessions (frames > 700ms) | < 0.1% sesiones | — | Peor posicionamiento "Apps similares" |

**How GamePerf maps these to v1 single-session KPIs** (engram `sdd/vitals-rate-and-wakelocks/spec`):

| Vital threshold (Google) | GamePerf v1 KPI | Single-session proxy |
|--------------------------|-----------------|----------------------|
| User-Perceived Crash Rate < 1.09% | `KpiId.CRASH_RATE_USERS` (Category: Stability) | `CRASH_COUNT > 0` flags banner |
| User-Perceived ANR Rate < 0.47% | `KpiId.ANR_RATE_USERS` (Category: Stability) | `ANR_COUNT > 0` flags banner |
| Excessive Partial Wake Locks < 5% sessions | `KpiId.WAKE_LOCKS_RATE` (Category: Resource, unit `h`) | `wakeLocksScreenOffMs >= 2h` flags banner |

**Wake locks measurement spec** (engram `#425 — research/wake-locks-measurement-spec`): GamePerf reads `adb shell dumpsys batterystats --charged <pkg>` and parses the "All partial wake locks:" section, summing durations attributed to the target package. Plausibility window: `0 ≤ ms ≤ 24*3600*1000`. Out-of-range entries are dropped with `OUT_OF_RANGE_VALUE` diagnostic. Polling cadence: 30 ticks (~15s) — wake locks are accumulator metrics where the final value is what matters, intermediate samples are advisory only.

**v1 confidence**: MEDIUM. Thresholds are Google official (above table). Measurement is **single-session**, which is a proxy for the cross-session rate Vitals actually computes (% of users / % of sessions). If a single session already crosses the 2h wake-locks gate, the cross-session rate is almost certainly above the 5% threshold. If a single session does NOT cross, we cannot confirm the real Vitals rate without cross-session aggregation (v2 deferred — would require `history.json` roll-up across N sessions of the same device model).

**v2 deferred work**: per-device-model 8% gate (Vitals penalizes each model separately), cross-session aggregation for true rate calculation, iOS support (no direct equivalent to `dumpsys batterystats`), wake-locks-by-component breakdown (which SDK is holding the lock).

### 3.2 Google RAIL Performance Model

> Source: <https://web.dev/articles/rail> (retrieved 2026-05-12). Core Web Vitals supersedes RAIL for web, but RAIL budgets remain canonical for user perception and engineering guidance for any interactive software.

**User perception of delay** (anchor numbers — unlikely to change):

| Delay range | User perception |
|-------------|-----------------|
| 0–16 ms | Smooth animation (60 FPS) |
| 0–100 ms | Feels instantaneous |
| 100–1000 ms | Feels like natural task progression |
| >1000 ms | User loses focus on task |
| >10000 ms | User frustration, likely abandonment |

**RAIL goals/budgets**:

| Aspect | Goal | Why |
|--------|------|-----|
| **R**esponse | Process user input in **<50 ms** | Leaves 50 ms for queued idle work, total ≤100 ms perceived |
| **A**nimation | Produce each frame in **≤10 ms** | 16 ms budget − ~6 ms browser/system render = 10 ms app budget |
| **I**dle | Idle tasks **≤50 ms** chunks | Larger blocks risk interfering with next input |
| **L**oad | Page interactive **≤5 seconds** on mid-range mobile + slow 3G | Subsequent loads target <2s |

Baseline test conditions Google recommends: Moto G4 + slow 3G (400 ms RTT, 400 kbps).

### 3.3 Console certification

> **NDA-bound, no public verifiable thresholds.** Use platform-holder generic guidance only.

What is publicly known and consistently reported by GDC talks and engine vendors (no single authoritative URL):
- **Sony PS5/PS4 (TRC)**: stable 30 FPS or 60 FPS depending on declared mode; sub-1-second resume from rest; no crashes during cert pass.
- **Microsoft Xbox (XR / Xbox Requirements)**: stable target frame rate, no hangs/ANRs, memory budget per SKU.
- **Nintendo Switch (Lotcheck)**: stability under stress (long-run, edge cases), localization compliance, no crashes during cert path.

**Recommendation for scoring system**: do NOT cite specific console numbers as "official" unless we obtain the actual TRC/XR/Lotcheck documents. Mark as "platform-holder cert" with general "stable frame rate, zero crashes during cert path" requirements.

### 3.4 Engine guidance (Unity / Unreal)

> **Requires manual e-book download from Unity/Unreal, not verified this pass.** Unity and Unreal mobile perf docs are JS-rendered SPAs — could not fetch numeric thresholds via WebFetch.

Authoritative sources (manual download needed):
- Unity: "Optimize your game performance for mobile, XR, and Web in Unity 6" (e-book)
- Unreal: "Performance Guidelines for Mobile Devices" (Epic docs PDF)

**DERIVED frame budgets (math, not vendor-published)**:

| Target FPS | Frame budget |
|------------|--------------|
| 30 FPS | **33.3 ms** |
| 60 FPS | **16.6 ms** |
| 120 FPS | **8.3 ms** |

Publicly cited (not single URL):
- Draw call budgets vary by tier (Unity rough: low ~50–100, mid ~200–300, high ~500+ per frame). Not verifiable to one URL.
- Memory budgets convention: 1 GB device → ~512 MB game budget; 2 GB → ~1 GB.

### 3.5 GPU vendor guidance (ARM Mali, Qualcomm Adreno, Apple Metal)

> **Manual PDF retrieval pending.** ARM, Qualcomm, Apple dev sites are all JS-SPA — could not extract numeric thresholds via WebFetch.

Authoritative sources (manual download needed):
- ARM Mali GPU Best Practices Guide (PDF, developer.arm.com)
- Qualcomm Adreno GPU Game Developer Guide (PDF)
- Apple "Metal Best Practices Guide" (developer.apple.com/documentation/metal)

**Only verified vendor-published quantitative target this pass**:
> Apple iOS launch budget: **total launch ≤ 400 ms** (first frame rendered). System-side init ~100 ms, developer-available ~300 ms. Source: <https://developer.apple.com/videos/play/wwdc2019/423/> — "Optimizing App Launch", Apple Performance Team WWDC 2019.

Apple iOS launch phase breakdown (6 phases): dyld → libSystemInit → Static Runtime Init → UIKit Init → Application Init (biggest dev impact) → First Frame Render. Optional Extended phase (async data loading after first frame).

Apple App Store Review Guideline 2.4.2: "Apps should not rapidly drain battery, generate excessive heat, or put unnecessary strain on device resources." No numeric thresholds — reviewer judgment.

Reference observation: `research/market-kpis-official-sources` (#309).

### 3.6 PerfDog-published metric formulas (industry references)

> Source: WeTest blog post #1189 (founding dev Awen Cao interview by Sr. PM Baojian Shen, March 2026) + <https://perfdog.wetest.net/> product page. Verified in obs #312.

#### Jank (PerfDog 2019)

```
FrameTime > 2 × avg(last 3 frames) AND FrameTime > 84 ms   (Single Jank)
FrameTime > 2 × avg(last 3 frames) AND FrameTime > 125 ms  (Big Jank)
```

Stricter than Android Vitals slow-frame (>16 ms). Our scoring will EXPOSE BOTH as separate KPI columns — see §5.1.

#### SmallJank (2020, 120 Hz+ displays)

Thresholds NOT public. Defer adoption until our tool supports 120 Hz target users.

#### Smooth Index

```
Smooth Index = 100 - weighted_jank_severity_score
```

Target >95 for AAA. Weighting NOT public.

Our take: implement equivalent in our scoring framework with PUBLIC weighting (transparency advantage vs PerfDog's closed weighting).

#### FPower (PerfDog industry-first)

```
FPower = Total Power (W) / FPS = mW per frame
```

Anchor thresholds (PerfDog case studies):
- **< 50 mW/frame**: excellent (60 mW → 46.7 mW gave 22% battery life gain at unchanged FPS)
- **50–65 mW/frame**: acceptable
- **> 65 mW/frame**: investigate

Implementation source for our tool: `/sys/class/power_supply/battery/current_now` + `voltage_now` via `adb shell cat`.

#### CPU% freq-normalized

```
CPU%_normalized = raw_cpu_pct × (current_freq / max_freq)
```

PerfDog default. Removes throttling distortion: a throttled CPU at 60% raw is closer to saturation than a non-throttled CPU at 60%. Critical for thermal-aware scoring.

---

## 4. Event segmentation framework

### 4.1 Game phases to track (user-requested)

1. App startup / SDK initialization
2. Cinematics
3. Tutorials
4. Level / map loading
5. Screen-to-screen navigation
6. Interstitial ads
7. Rewarded video ads
8. Gameplay (default / fallback)

### 4.2 Current coverage in our tool (audit 2026-05-12)

> Source: `audit/event-segmentation-coverage-2026-05-12` (#308). Verified against `core/events/SdkSignatureCatalog.kt`, `core/events/DetectedEvent.kt`, `AppViewModel.kt`.

| # | Phase | Auto-detected? | Manual marker? | Gap | Effort to close |
|---|-------|----------------|----------------|-----|-----------------|
| 1 | **App startup / SDK init** | ❌ NO. Zero signatures for Firebase/GA/AppMeasurement/init. No cold-vs-warm detector. | ⚠️ Only `CUSTOM` (post-capture, no real-time anchor). | **CRITICAL** — tool doesn't know when SDK arrived or how long init took. No `APP_STARTUP`/`SDK_INIT` events exist. | **2d** (new `EventType.APP_STARTUP`+`SDK_INIT`, 6 init signatures, cold-start sensor via dumpsys first match of gamePackage) |
| 2 | **Cinematics** | ❌ NO — impossible without instrumentation (gameplay semantic, not SDK detectable). | ✅ `MarkerType.SCENE_CHANGE` cosmetic. | Tool can't semantically distinguish cinematic from menu. | **1d** (opt-in tag protocol `GamePerf:I Cinematic.Start/End` + new `EventType.CINEMATIC`) |
| 3 | **Tutorials** | ❌ NO (same as cinematics — gameplay semantic). | ✅ `MarkerType.CUSTOM`. | Same as #2. | **1d** (opt-in tag protocol `GamePerf:I Tutorial.Step name="..."` + new `EventType.TUTORIAL`) |
| 4 | **Level / map loading** | ⚠️ `EventType.LOADING` **DECLARED + RENDERED** in `ReportGenerator` ("Carga"/`#f59e0b`) but **ZERO signatures emit it**. Auto-detection dead. | ✅ `MarkerType.LOADING`. | False negatives guaranteed in auto path. | **0.5d** (wire Unity/Unreal/Cocos2d signatures: `Unity:I Loading scene`, `UnityEngine:I AsyncOperation`, `UE4:I LogStreaming`/`LoadingScreen`, `cocos2d:I CCDirector.replaceScene`. Allowlist tags to limit false positives) |
| 5 | **Screen navigation** | ⚠️ Data **available but unclassified**. `DumpsysPoller` already monitors top-of-stack 1Hz; any `cmp` change inside game package is ignored today. | ✅ `MarkerType.SCENE_CHANGE`. | Sensor is there; classifier missing. | **0.5d** (new `EventType.SCREEN_TRANSITION` emitted when `top.cmp` changes & starts with `$gamePackage/`, confidence MEDIUM. Caveat: single-activity games like Unity/Unreal won't emit) |
| 6 | **Interstitial ads** | ✅ **COMPLETE**. 5 SDKs in catalog: AdMob, Unity Ads, IronSource, AppLovin/MAX, Meta Audience. Activity-level path survives ProGuard. | ✅ `MarkerType.INTERSTITIAL` redundant. | None critical. Potential gaps: Vungle, Chartboost, Mintegral, Yandex, Pangle. | **0d** (maintain; add Vungle/Chartboost/Pangle if AppMagic reveals usage in games to test) |
| 7 | **Rewarded video** | ⚠️ Only Unity Ads. AdMob/IS/AppLovin/Meta rewarded variants **mistagged as INTERSTITIAL** (same activity classes, current catalog only supports one `type` per signature). | ✅ `MarkerType.VIDEO_REWARD`. | Wrong classification in HTML report for 4 of 5 SDKs. | **1d** — BREAKING refactor: `SdkSignature.openPatterns: List<(Regex, EventType)>` instead of single `type` field |
| 8 | **Gameplay (default)** | ✅ Default — anything not classified as an event = gameplay. | N/A | None. | 0 |

**Architectural gaps** (from audit obs #308):
- `SdkSignature.type` fixes one EventType per signature → blocks rewarded/init split inside same SDK.
- No hook for "instrumented tag" from game (e.g. `GamePerf:I Tutorial.Start`).
- No notion of hierarchical "phase" containers. Everything is flat `List<DetectedEvent>`.
- No cold-vs-warm detection. PID restart sensor would work (`/proc/<pid>` reappears) — data exists, logic missing.

`EventType.FOREGROUND_LOSS` is declared for iOS sidecar (EVT-010, IOS-003) — Python sidecar not audited this pass.

### 4.3 State of the art — how others segment

> Source: `research/event-segmentation-state-of-art` (#306).

| Tool | Mechanism | Verified | Notes |
|------|-----------|----------|-------|
| **GameBench Programmatic Markers** | Logcat string scanning — `gb_marker_start - <label>` / `gb_marker_stop - <label>` in ANY logcat line. Optional `-group=<name>` suffix. | ✅ CONFIRMED via <https://docs.gamebench.net/general-information/programmatic-markers/> | **THE PATTERN.** Zero SDK overhead, language-agnostic. Validates our adb-only approach. |
| **Firebase Performance custom traces** | SDK calls `Performance.startTrace(name)` / `trace.stop()`. Supports custom metrics (`trace.incrementMetric`), custom attributes (max 5 K/V), 32 metrics including duration. | ✅ CONFIRMED | Explicit warning: **"Avoid creating custom code traces at high frequencies (for example, once per frame in games)"** |
| **Sentry transactions** | Root-span as transaction name. Custom performance measurements (up to 10/transaction). p50/p75/p95/p99/p100, throughput, Apdex, User Misery (% users >4×threshold). Suspect Spans. Self-time = span − child spans. | ✅ CONFIRMED | Mature aggregation model worth borrowing for our reports |
| **Perfetto Tracing SDK** | `TRACE_EVENT` macros, categories with filter, in-process or system backend (fuses app events with ftrace/scheduler/syscalls). | ✅ CONFIRMED | Android: `android.os.Trace` (Java) / `ATrace_*` (NDK). "Atrace-based instrumentation fully supported in Perfetto." |
| **AdMob lifecycle callbacks** | Java/Kotlin callbacks (`InterstitialAdLoadCallback`, `FullScreenContentCallback`). Sample-code `Log.d("MyActivity", "The ad was shown.")` strings are APP-CONTROLLED, NOT SDK-emitted. | ✅ CONFIRMED | **AdMob does NOT emit a stable default logcat tag for ad lifecycle.** Cannot reliably auto-detect AdMob ad show via logcat unless the game added logging. |
| **PerfDog** | Docs Chinese marketing only; technical scene/segmentation behavior NOT verified. | ❌ NOT VERIFIED | — |
| **IronSource, AppLovin, Vungle, Mintegral** | Logcat patterns NOT verified. IronSource docs link redirected to Unity LevelPlay marketing. No technical access this pass. | ❌ NOT VERIFIED | — |
| **Unity Profiler markers** | `Profiler.BeginSample`/`EndSample` | ❌ NOT FETCHED (404 on docs URLs) | — |

> ⚠️ **NOTE — reality check on ad SDK auto-detection**: Our current catalog detects AdMob/IS/AppLovin/Meta/UnityAds via known activity classes + heuristic log strings. These patterns work in practice but are **NOT vendor-stable contracts**. The research pass could only verify that AdMob's official docs do **not** publish stable logcat tags. Empirical capture is needed to validate our existing patterns and to extend to Vungle/Chartboost/Mintegral/Pangle. See §8 decision #9 and §9 change #6.

**Key cross-cutting insight**: **NO tool verified does auto-detection of ad lifecycle without game cooperation.** GameBench logcat protocol IS the pattern — but it relies on devs cooperating with `gb_marker_*` convention.

### 4.4 Proposed detection tiers

**TIER 1 — ship soon**:
- Adopt GameBench-style logcat protocol: `perf_phase_start - <name>` / `perf_phase_stop - <name>` (zero overhead, optional, works for any cooperative game).
- Android system signals via dumpsys + atoms: `am_proc_start` (cold start), `am_resume_activity` (screen nav), `dumpsys activity LaunchTime`.
- Wire existing `EventType.LOADING` to Unity/Unreal/Cocos2d signatures (audit gap #4).

**TIER 2 — medium term**:
- Frame-time signature heuristics for loading detection (sustained dropped-frame periods bracketed by recovery).
- atrace ingestion via Perfetto data sources (Mali kbase counters published via Perfetto on recent Android).

**TIER 3 — defer**:
- Ad SDK auto-detection beyond catalog (requires empirical capture lab first — §8 #9).
- Metric-signature heuristics (RAM jump + network spike correlated to ad load, cinematic via sustained GPU + audio).

---

## 5. KPI framework per phase

### 5.1 Master KPI catalog

Each KPI defined ONCE here, scored differently per phase in §5.2.

| KPI | Definition | Unit | Source metric | Sampling | Aggregation | Threshold anchor |
|-----|------------|------|---------------|----------|-------------|------------------|
| FPS avg | mean FPS over window | fps | `dumpsys SurfaceFlinger` | 1 Hz | mean | §3.1 game 30/20 FPS |
| FPS p1 | 1st percentile FPS | fps | derived | per-frame | percentile | press §2.3 PC-grade |
| FPS p0.1 | 0.1st percentile FPS | fps | derived | per-frame | percentile | press §2.3 PC-grade |
| FPS stability | % frames within ±10% of target | % | derived | per-frame | ratio | GameBench convention |
| Frame time p99 | 99th percentile frame time | ms | per-frame | per-frame | percentile | RAIL §3.2 16ms |
| Slow frames | count of frames > 16ms (60Hz) or >33ms (30Hz) | int | derived | per-frame | count | §3.1 >50% bad |
| Frozen frames | count of frames > 700ms | int | derived | per-frame | count | §3.1 >0.1% bad |
| CPU avg | mean CPU % | % | `adb top` / `dumpsys cpuinfo` | 0.5 Hz | mean | tier-dependent |
| CPU max | peak CPU % | % | same | same | max | tier-dependent |
| GPU avg | mean GPU usage % | % | sysfs (Sprint 1 paused) | 0.5 Hz | mean | tier-dependent |
| GPU max | peak GPU usage % | % | sysfs | same | max | tier-dependent |
| RAM avg | mean RSS | MB | `dumpsys meminfo` | 0.5 Hz | mean | tier-dependent (§3.4 convention) |
| RAM max | peak RSS | MB | same | same | max | tier-dependent |
| Temperature avg | mean skin temp | °C | sysfs thermal | 0.5 Hz | mean | vendor-specific |
| Temperature max | peak skin temp | °C | sysfs | same | max | vendor-specific |
| Throttling events | count of thermal throttle triggers | int | `dumpsys thermalservice` | event | count | 0 preferred |
| Network total | total bytes RX+TX during phase | MB | `dumpsys netstats` | per-phase | sum | §3.1 bg >50 MB/day bad |
| Network bandwidth | peak throughput | KB/s | derived | derived | max | — |
| Battery drain | mAh consumed during phase | mAh | `dumpsys battery` | per-phase | diff | §3.1 watch face 4.44%/h |
| **Cold start time** | time from launch to first frame | ms | ActivityManager events | event | duration | **§3.1 ≥5s SLOW** / GOOD <2s / OK 2-5s |
| **Warm start time** | time from background-to-foreground to first frame | ms | event | event | duration | **§3.1 ≥2s SLOW** / GOOD <0.5s / OK 0.5-2s |
| **Hot start time** | resume time | ms | event | event | duration | **§3.1 ≥1s SLOW** / GOOD <0.2s / OK 0.2-1s |
| **TTID** (Time to Initial Display) | first paint | ms | event | event | duration | Sentry convention §2.2 |
| **TTFD** (Time to Full Display) | content fully loaded | ms | event | event | duration | Sentry convention §2.2 (opt-in) |
| Loading time | time inside a loading phase | ms | phase boundaries | event | duration | — |
| **ANR count** | count of ANRs during phase | int | logcat | event | count | **§3.1 user-perceived ≥0.47% DAU bad** (zero per single session preferred) |
| Slow session rate (FPS-target-aware) | % frames missing target by tier | % | derived | per-frame | ratio | §3.1 >25% = slow session |
| Crash count | count of crashes during phase | int | logcat / tombstones | event | count | §3.1 user-perceived ≥1.09% DAU bad |
| Slow UI thread frame | input >24ms OR UI thread >8ms OR GPU draw >12ms OR bitmap upload >3.2ms | bool | derived | per-frame | count | §3.1 sub-budgets |
| **FPower** | mW per frame | mW/frame | `/sys/class/power_supply/battery/{current_now,voltage_now}` ÷ FPS | 1 Hz | mean / per-phase | §3.6 <50 / 50-65 / >65 |
| **CPU% normalized** | freq-adjusted CPU | % | raw_cpu × current_freq/max_freq | 0.5 Hz | mean | §3.6 (PerfDog default) |
| **PerfDog Jank count** | jank events per phase | int | FT > 2×avg(3) AND > 84 ms | per-frame | count | §3.6 |
| **PerfDog Big Jank count** | severe jank events per phase | int | FT > 2×avg(3) AND > 125 ms | per-frame | count | §3.6 |

**Differences from skeleton**:
- Cold/warm/hot start split (Sentry §2.2 + Vitals §3.1 trichotomy).
- TTID + TTFD as separate metrics (Sentry convention).
- ANR + crash as event counts.
- Slow-session-rate FPS-target-aware (Vitals games threshold).
- Slow-UI-thread-frame composite (Vitals sub-budgets §3.1).

### 5.2 KPI relevance per phase (refined from research)

Initial proposal — product can adjust weights in §8.

| Phase | **Critical KPIs** | Important | Nice-to-have | Irrelevant |
|-------|-------------------|-----------|--------------|------------|
| **App startup / SDK init** | Cold start time, TTID, RAM at boot, slow frame count first 5s | CPU peak (normalized), ANR count, crash count | Network bytes during init | GPU (idle), thermal, FPower |
| **Cinematics** | FPS stability, frame time p99, frozen frames | CPU avg (normalized), GPU avg, slow frames, FPower | RAM, temperature, battery | TTID, cold start, network |
| **Tutorials** | FPS stability, slow frames, TTID per screen | CPU avg (normalized), RAM, FPower | GPU, network, frame time p99 | Cold start, throttling |
| **Level / map loading** | Loading time, RAM peak, network total (loading bytes) | CPU peak (normalized), frame time p99 | GPU, temperature, FPower | FPS (loading screens often static), TTID |
| **Screen navigation** | TTID per transition, frame time p99 | CPU peak (normalized), RAM delta, slow frames | GPU, network, FPower | Cold start, throttling |
| **Interstitial ads** | RAM delta, network total during ad load, frame time on close, slow frames during ad | CPU avg (normalized) | GPU, battery drain | FPS (ad video FPS != game FPS), TTID, FPower |
| **Rewarded video** | Same as Interstitial + video FPS continuity | CPU (normalized), GPU avg | Temperature | Cold start, throttling, FPower |
| **Gameplay (default)** | FPS avg, FPS p1, FPS stability, temperature avg/max, throttling events, **FPower** | GPU avg, CPU avg (normalized), RAM, slow session rate, battery drain, PerfDog Jank count | Network | Cold start, TTID |

> **NOTE on CPU% normalized**: this is the REPLACEMENT for raw CPU% wherever raw CPU% is currently listed. Raw CPU% can be deprecated once the normalized version is validated against thermal-throttled captures.

Weights TBD per phase in §8 once product confirms which phases matter most for which game genres.

---

## 6. Scoring system

### 6.1 Goals

- Single 0-100 score per game session
- Sub-scores per phase (a game with bad loading but good gameplay shows the split)
- Sub-scores per KPI category (Smoothness, Resource use, Thermal, Stability)
- Comparable across devices (normalized by device class)

### 6.2 Scoring model — three options

**Model A — Linear threshold (simplest)**: Each KPI gets a score 0-100 by linear interpolation between "good" and "bad" thresholds. Phase score = weighted average. Session score = weighted average of phase scores.
- Pro: dead simple, explainable, easy to debug
- Con: cliff edges at thresholds, no recognition of "great" vs "good"

**Model B — Sigmoid (industry standard for benchmarks)**: Each KPI maps through a sigmoid. Tiny rewards for being slightly better than threshold, big penalty for being much worse.
- Pro: smooth, no cliffs, easy to tune curve shape per KPI
- Con: harder to explain to non-technical stakeholders, harder to debug

**Model C — Bucket + multiplier (gamification feel)**: Each KPI lands in a bucket (S/A/B/C/D/F). Buckets aggregate to a letter grade. Optional numeric score = bucket midpoint × weight.
- Pro: easy to communicate ("your game scored B+ on loading")
- Con: loses granularity, threshold cliffs return

**Recommendation for v1**: **Model A (Linear) anchored on Android Vitals thresholds.**

> **Note on threshold anchoring**: threshold anchor points come from §3.1 — they are NOT arbitrary, they're what Google penalizes apps on for Play Store discoverability. Using arbitrary thresholds would mean we're scoring something different from what Play ranks on. Evolve to Model B once ≥50 sessions are scored and we know which curves matter.

### 6.2.1 Composite scoring alternatives

Beyond Model A (Linear weighted), we evaluate two composites:

**Smooth Index (PerfDog convention)**: `100 - weighted_jank_severity_score`
- Pro: industry-recognizable, single number, comparable to PerfDog reports
- Con: weighting NOT public from PerfDog; we'd define our own (transparency advantage)
- Decision pending: §8 #10

**Vitals-aligned ranking signal**: pct daily sessions exceeding Play "slow session" thresholds (>25% frames below 30 FPS / 20 FPS)
- Pro: directly aligned with Play discoverability impact
- Con: requires multi-session aggregation (Sprint 3+ trends work)

### 6.3 Weighting by device class

Same KPI threshold differs by device tier:

| Tier | Definition | FPS target | Cold start budget | RAM headroom |
|------|------------|------------|-------------------|--------------|
| High-end | ≥ 2024 flagship | 60 fps | < 2s (GOOD), ≥5s SLOW (Vitals) | < 8 GB |
| Mid-range | 2022-2023 | 30-60 fps | < 3s | < 4 GB |
| Low-end | 2020-2021 or budget | 30 fps | < 5s | < 3 GB |

**Where to source device tier classification**: propose **internal vendor catalog** (single source of truth, mirroring `ThermalZoneClassifier` pattern). Populated from public SoC databases. **NanoReview** SoC ratings (§2.3) are one possible external anchor — composite SoC rating 0-100 across AnTuTu/GeekBench/3DMark, crowdsourced with timestamped per-submission public entries.

See §8 decision #4.

### 6.4 Aggregation formula

```
kpi_score(kpi, phase) = map_to_0_100(measured_value(kpi, phase), thresholds(kpi, device_tier))
phase_score(phase)    = weighted_sum(kpi_score(kpi, phase) * weight(kpi, phase) for kpi in kpis_for(phase))
session_score        = weighted_sum(phase_score(phase) * weight(phase) for phase in phases_present)
category_score(cat)  = weighted_sum(kpi_score(kpi, phase) for (kpi, phase) where category(kpi) == cat)
```

Categories proposed:
- **Smoothness** (FPS avg/p1/p0.1/stability, frame time p99, slow/frozen frames)
- **Resource use** (CPU, GPU, RAM, network)
- **Thermal** (temperature avg/max, throttling events)
- **Stability** (ANR count, crash count, slow UI thread frames, slow-session-rate)
- **Responsiveness** (Cold/Warm/Hot start, TTID, TTFD)

### 6.5 Open questions for product

- Single number per game or per (game, device, build)?
- Show evolution over time (regression detection)?
- Per-version comparison ("v3.4 vs v3.5 of same game")?
- Public-facing scores or internal-only?
- How to handle missing phases (game has no cinematics — penalize, ignore, or rebalance weights)?

---

## 7. Shareable HTML report

### 7.1 Requirements (user-stated)

- Shareable via link OR PDF
- HTML preferred (link sharing)
- Equivalent content to in-app report

### 7.2 Current report state

- `ReportGenerator.kt` — HTML output, self-contained
- v4.4.1 added Spanish tuteo-formal diagnostic banners
- v4.4.1 added thermal N/D rendering
- Unified `#sec-events` table with Source column (Auto / Manual)
- Render mapping for all `EventType` values including unused `LOADING` and `FOREGROUND_LOSS`

### 7.3 Patterns to COPY (from §2.3 press research)

1. **Notebookcheck `Ø60 (59-61)` per-game annotation** — cleanest avg(min-max) shorthand encountered.
2. **Android Authority paired score+temperature timeline** — causality at a glance.
3. **GSMArena per-row device annotation** (phone thumbnail + chip + RAM + resolution) — high info density without clutter.

### 7.4 Anti-patterns to AVOID

1. Opaque single-number composite ratings (Notebookcheck 89%).
2. Screenshot-only non-interactive graphs (GSMArena thermal throttling).
3. Missing percentile metrics (p1, p0.1 — most mobile press skips them).

### 7.5 Market gaps we MUST fill (our differentiators)

- **Downloadable raw data** (CSV + JSON export)
- **Frame-time percentiles p1, p0.1 by default** — PC-grade rigor on mobile
- **Per-gameplay-phase segmentation** (load vs combat vs cutscene) — only DF does this and only on YouTube
- **Joint FPS + thermal + power-draw timeline** on a single shareable URL

### 7.6 Hosting decision

**Recommendation: self-contained HTML** (CSS + JS inline, no external CDN, no telemetry) for privacy-safe link sharing.
- Privacy-safe, works offline, link = local file URL or email attachment, zero infra cost.
- Optional: company-hosted variant later if team needs versioned URLs.

See §8 decision #6.

---

## 8. Decisions pending (for product owner)

| # | Decision | Recommendation |
|---|----------|----------------|
| 1 | **Game list to test** (which of our games in scope) | Needs internal product list. No external recommendation possible. |
| 2 | **Competitor benchmark choice per game** | GameBench **paid trial 1 month** (sufficient for parity calibration) AND PerfDog free-research-tier where applicable. |
| 3 | **KPI weights per phase** (§5.2) | Use the proposed initial values in §5.2 as starting point. Product can adjust after first 10 sessions. |
| 4 | **Device tier table source** (§6.3) | Internal vendor catalog (mirror `ThermalZoneClassifier` pattern). NanoReview SoC ratings as external anchor for cold-start population. |
| 5 | **Scoring model** (§6.2) | **Model A (Linear)** v1, anchored on Android Vitals thresholds (§3.1). Evolve to Model B once ≥50 sessions scored. |
| 6 | **Report hosting** (§7.6) | **Self-contained inline HTML** (no CDN, no telemetry). Hosted variant deferred. |
| 7 | **GameBench paid trial account** | **YES** — for parity calibration. 1 month trial sufficient. |
| 8 | **Score visibility** | **Internal-only initially.** Public-facing requires legal review. |
| 9 | **Empirical ad SDK capture lab** (NEW) | **PROPOSE: YES — 1 day lab.** Capture default logcat tags from AdMob/IS/AppLovin/Meta/UnityAds/Vungle/Mintegral with real games. **IF YES** → unblocks Tier 3 auto-detection for ad events + validates existing 5-SDK catalog patterns. **IF NO** → ad event coverage stays at game-cooperation level (current state). |
| 10 | **Smooth Index implementation** (§6.2.1) | Adopt PerfDog Smooth Index alongside Linear Model A? If YES, define our own weighting — recommend transparent published weights (transparency advantage vs PerfDog's closed weighting). |
| 11 | **FPower anchor thresholds** (§3.6) | Confirm <50 / 50–65 / >65 mW/frame anchors from PerfDog case studies, or run our own baselines per game per device tier? Recommend running our own to ground anchors on Mali + Adreno + PowerVR independently. |

---

## 9. Next SDD changes proposed

Ranked by ROI × effort (audit obs #308 + roadmap obs #289):

| # | Change | Effort | Depends on | Notes |
|---|--------|--------|------------|-------|
| 1 | **`event-segmentation-coverage`** | **5-6d total** | None | Sprint 0 refactor `SdkSignature.openPatterns: List<(Regex, EventType)>` (1d) → Sprint 1 APP_STARTUP + SDK_INIT (2d) → Sprint 2 SCREEN_TRANSITION (0.5d) + LEVEL_LOADING signatures wire-up (0.5d) + Rewarded split (1d). Optional Sprint 3 instrumented mode CINEMATIC/TUTORIAL (1d). |
| 2 | **`kpi-scoring-framework`** | **4-5d** | This doc finalized + §8 product decisions | Catalog (§5.1) + Linear model + per-phase weights + device tier table |
| 3 | **`shareable-html-report`** | **2-3d** | #2 scoring implemented | Self-contained inline + CSV/JSON download + p1/p0.1 percentiles in renderer |
| 4 | **`gpu-usage-percent`** | **~4.25d** when retaken | None | Sprint 1 PAUSED. Tasks already exist in `openspec/changes/gpu-usage-percent/`. Mali+Adreno+PowerVR coverage. Mali post-Android 12 needs root for frequency; usage% does not. Adreno A13+ needs `echo 1 > /sys/class/kgsl/kgsl-3d0/perfcounter`. |
| 5 | **`network-bandwidth`** | **~3d** | #2 scoring decides priority | Sprint 2 from `roadmap/gamebench-parity`. `dumpsys netstats detail --uid <uid>` total bytes RX/TX. NO per-connection (requires libc hooks, traitor to zero-touch). |
| 6 | **`ad-sdk-empirical-capture-lab` (NEW)** | **1d research/lab** | §8 #9 = YES | Capture default logcat from 7 ad SDKs with real games + 2 devices (Mali + Adreno). Output: signature catalog updates + verification matrix per SDK. |
| 7 | **`loading-event-signatures-quickfix` (NEW)** | **0.5d standalone** | None | Wire Unity/Unreal/Cocos2d signatures to existing `EventType.LOADING` type. High ROI, low cost. Fixes audit gap #4 — auto-detection currently dead. |
| 8 | **`fpower-metric` (NEW, post-PerfDog)** | **2-3d HIGH ROI** | None | FPower (mW/frame) read from `/sys/class/power_supply/battery/{current_now,voltage_now}` ÷ FPS. Independent of KPI scoring framework, can ship first. Anchors §3.6 / §8 #11. |
| 9 | **`cpu-freq-normalized` (NEW, post-PerfDog)** | **0.5d** | Bundle into #2 `kpi-scoring-framework` | `raw_cpu × current_freq/max_freq`. PerfDog default; removes throttling distortion. §3.6. |
| 10 | **`perfdog-jank-formula` (NEW, post-PerfDog)** | **0.5d** | Bundle into #2 `kpi-scoring-framework` | Single Jank (>2×avg(3) AND >84 ms) + Big Jank (>125 ms) as separate KPI columns alongside Vitals slow-frame. §3.6. |
| 11 | **`cli-headless-mode` (NEW, post-PerfDog)** | **3-4d** | None | Picocli/kotlinx-cli entry. Reuses capture pipeline. JSON output. Exit code on threshold breach. **Unlocks #12 and #13.** |
| 12 | **`gh-action-wrapper` (NEW, post-PerfDog)** | **1-2d** | #11 | Wrap CLI in GitHub Action for CI. Direct counter to PerfDog Service enterprise pricing for CI/CD. |
| 13 | **`multi-device-capture` (NEW, post-PerfDog)** | **3-5d** | None | Tabbed sessions ≤3 in GUI (match PerfDog limit) + unlimited via CLI (#11). Independent per-device viewmodels. |
| 14 | **(DEFER) `engine-mode-perfetto-capture`** | **5-10d** | None | atrace capture via `adb shell perfetto`. Only on explicit user demand. Anti-no-SDK tension — defer until requested. |

**Recommended order** (impact × dependency):
1. #7 quick fix LOADING signatures (0.5d standalone, immediate ROI on existing dead code path)
2. #8 `fpower-metric` (2-3d, ships independently, closes top PerfDog gap)
3. #1 event-segmentation-coverage (unblocks scoring with proper phase boundaries)
4. #2 kpi-scoring-framework + bundled #9 + #10 (anchors §3.1 Vitals + §3.6 PerfDog formulas)
5. #11 `cli-headless-mode` (unlocks CI/CD positioning vs PerfDog Service)
6. #3 shareable-html-report (the user-facing payoff)
7. #12 `gh-action-wrapper` (after #11)
8. #13 `multi-device-capture` (after CLI baseline stable)
9. #4 gpu-usage-percent (retake Sprint 1 when GPU becomes scoring-critical)
10. #6 ad-sdk-empirical-capture-lab (if §8 #9 = YES)
11. #5 network-bandwidth (Sprint 2 from existing roadmap)
12. #14 DEFER `engine-mode-perfetto-capture` until requested

---

## 10. Positioning statement

> **Local-first, open-methodology, free Android performance profiler for QA teams who need to profile release builds without uploading IP to third-party cloud, without engine integration, without vendor lock-in. Complements (not replaces) vendor deep-dive tools (Snapdragon Profiler / ARM Streamline / Unity Profiler).**

Concrete differentiators reinforced by competitive analysis (2026-05-12):
- **Methodology transparency** — every formula and threshold our scoring uses is published in this doc and in the openspec specs. PerfDog Help Center confirmed gated 2026-05-12 (engram `research/perfdog-help-center-2026-05-12`, obs #328) — their measurement methodology requires login to access. Our advantage: reproducibility, auditability, no black-box scoring; any QA engineer can re-derive our numbers from the documented `/sys` / `dumpsys` / Perfetto endpoints without vendor onboarding.

Anchored on (refined post-PerfDog deep-dive, obs #312):
- **Data sovereignty** — no cloud, ever. PerfDog requires mandatory upload to perfdog.qq.com or perfdog.wetest.net with hard compliance silos between China and Int'l (no user choice). We never leave the host.
- **Open methodology** — every metric is sourced from public `/sys` / `dumpsys` / Perfetto endpoints and documented inline. PerfDog's Jank/Smooth Index/FPower formulas are partially public (§3.6) but weightings and SmallJank thresholds are closed.
- **Free for commercial use** — PerfDog is free for non-profit / research only; commercial requires sales contact with NDA pricing.
- **Release-APK profiling** — no `debuggable=true` required. Parity with PerfDog/GameBench, beats Unity Profiler + Android Studio Profiler.
- **CI/CD-first** — planned `cli-headless-mode` (§9 #11) + `gh-action-wrapper` (§9 #12) target the indie/mid-tier market PerfDog Service prices out.
- **Multi-device by design** — planned `multi-device-capture` (§9 #13) matches PerfDog GUI ≤3 limit + adds unlimited via CLI (parallel = OS-level, not adb-limited per Awen Cao Q&A).

Explicitly **NOT** competing on:
- GPU HW counters (vendor partnerships required, PerfDog moat — use Snapdragon Profiler / ARM Streamline as complements)
- Cloud dashboards / team collaboration features (anti-positioning)
- Engine SDK metrics (Unity Mono / UE stat / draw calls / texture memory — anti-no-SDK principle)
- Production RUM (different product category — use Firebase Perf / Sentry / Embrace)
- Touch input latency (needs UI automation or instrumented build)
- 200k-app benchmark library at network-effect scale (PerfDog's moat, not ours)

---

## 11. References

### Engram observations

- `research/competitive-analysis-direct-tools` (#303) — PerfDog, Snapdragon Profiler, ARM Streamline, Unity Profiler, Android Studio Profiler
- `research/competitive-analysis-apm-rum` (#304) — Firebase Perf, Sentry, New Relic, Embrace, Android Vitals
- `research/competitive-analysis-press-reports` (#305) — GSMArena, Notebookcheck, Android Authority, Digital Foundry, NanoReview
- `research/event-segmentation-state-of-art` (#306) — GameBench markers, Firebase traces, Sentry transactions, Perfetto, ad SDK reality check
- `research/gamebench-comparison` (#288) — GameBench full feature matrix
- `research/gamebench-docs-gpu-section` (#298) — GameBench GPU specifics
- `roadmap/gamebench-parity` (#289) — 3-sprint parity roadmap
- `audit/event-segmentation-coverage-2026-05-12` (#308) — internal audit of current tool coverage, gap matrix
- `research/market-kpis-official-sources` (#309) — Google Android Vitals, RAIL, Apple iOS launch budget
- `docs/competitive-analysis-skeleton-2026-05-12` (#307) — skeleton precursor of this doc

### Working documents

- `GAMEBENCH-COMPARISON.md` (project root)
- `openspec/specs/core/spec.md` (current capability surface)
- `openspec/changes/gpu-usage-percent/` (paused Sprint 1 tasks)
- `CLAUDE.md` (project rules)

### External URLs (cited inline above)

**Authoritative (verified this pass, 2026-05-12)**:
- <https://support.google.com/googleplay/android-developer/answer/9844486> — Android Vitals
- <https://web.dev/articles/rail> — RAIL Performance Model
- <https://developer.apple.com/videos/play/wwdc2019/423/> — Apple Optimizing App Launch (400 ms budget)
- <https://developer.apple.com/app-store/review/guidelines/> — Apple Store Review (qualitative)
- <https://firebase.google.com/docs/perf-mon> — Firebase Performance (no thresholds published)
- <https://firebase.google.com/docs/perf-mon/custom-code-traces> — Firebase custom traces
- <https://docs.sentry.io/product/dashboards/sentry-dashboards/transaction-summary/> — Sentry transactions
- <https://docs.sentry.io/product/dashboards/sentry-dashboards/mobile/mobile-vitals/> — Sentry Mobile Vitals
- <https://docs.newrelic.com/docs/mobile-monitoring/new-relic-mobile/get-started/introduction-app-launch-times/> — New Relic Mobile
- <https://embrace.io/blog/mobile-app-performance-metrics/> — Embrace top 10 metrics
- <https://perfetto.dev/docs/instrumentation/tracing-sdk> — Perfetto SDK
- <https://docs.gamebench.net/general-information/programmatic-markers/> — GameBench markers protocol
- <https://developers.google.com/admob/android/interstitial> — AdMob (no stable logcat tags)
- <https://docs.gamebench.net/> — GameBench docs root
- <https://docs.gamebench.net/docs/web-dashboard/session-detail/metrics-timeline/> — GameBench Metrics Timeline visual layout (§2.5, retrieved 2026-05-18)
- <https://docs.gamebench.net/docs/web-dashboard/session-detail/comparison/> — GameBench Session Comparison view (§2.5, retrieved 2026-05-18)
- <https://docs.gamebench.net/docs/web-dashboard/trends/> — GameBench Trends Explorer (distribution charts + device perf table) (§2.5, retrieved 2026-05-18)
- <https://docs.sentry.io/product/insights/mobile/mobile-vitals/> — Sentry Mobile Vitals dashboard (§2.5, retrieved 2026-05-18)
- <https://docs.sentry.io/product/dashboards/sentry-dashboards/mobile/mobile-vitals/> — Sentry Mobile Vitals (Good/Meh/Poor RAG bands) (§2.5, retrieved 2026-05-18)
- <https://docs.unity3d.com/Manual/profiler-window-navigating.html> — Unity Profiler window navigation (stacked module charts) (§2.5, retrieved 2026-05-18)
- <https://www.qualcomm.com/developer/software/snapdragon-profiler> — Snapdragon Profiler landing (§2.5, JS-SPA, only landing reachable, retrieved 2026-05-18)
- <https://perfdog.qq.com/> / <https://perfdog.wetest.net/> — PerfDog landing pages
- <https://perfdog.wetest.net/helpCenter> — PerfDog Help Center (verified gated 2026-05-12, engram obs #328: JS-SPA + likely auth wall, 12 fetches confirmed identical shell, zero new features extractable)
- <https://www.wetest.net/blog/mobile-game-performance-testing-2026-perfdog-guide-1189.html> — WeTest blog #1189: PerfDog founding dev (Awen Cao) interview by Sr. PM Baojian Shen, March 2026. Single highest-information public source on PerfDog (Jank formula, FPower formula, SmallJank, Smooth Index, 11-platform list, CI/CD plugins, 3-device GUI limit, Custom Data API). Cited in §3.6.
- <https://docs.unity3d.com/Manual/Profiler.html> — Unity Profiler manual
- <https://developer.arm.com/Tools%20and%20Software/Streamline%20Performance%20Analyzer> — ARM Streamline

**Press references** (verified):
- <https://www.gsmarena.com/vivo_x300_ultra-review-2957p4.php>
- <https://www.notebookcheck.net/Samsung-Galaxy-S25-review-The-star-among-compact-smartphones-is-losing-ground.989246.0.html>
- <https://www.androidauthority.com/galaxy-s25-series-performance-3521707/>
- <https://nanoreview.net/en>
- <https://quality.gamebench.net> (Notebookcheck's named tooling partner)

**Pending / not verified this pass** (manual retrieval required):
- Unity / Unreal mobile perf e-books (JS-SPA, manual download)
- ARM Mali Best Practices, Qualcomm Adreno Game Developer Guide, Apple Metal Best Practices (JS-SPA, PDF download)
- Sony TRC / Microsoft Xbox XR / Nintendo Lotcheck (NDA-bound)
- IronSource / AppLovin / Vungle / Mintegral logcat patterns (marketing-only docs, need empirical capture — §9 #6)
- PerfDog English technical docs (Chinese marketing only)

---

## 12. Changelog

- **2026-05-12** — Initial consolidation from 7 research observations + internal audit. Replaces skeleton (`docs/competitive-analysis-skeleton-2026-05-12` obs #307). All 22 `<!-- AWAITING -->` markers resolved; all 8 placeholder tables filled. Document status: research-complete, pending §8 product decisions before SDD changes #2/#3 can start. Honesty notes preserved on console certs (NDA), engine/vendor PDFs (manual download required), and ad SDK auto-detection (NOT verified beyond current 5-SDK catalog).
- **2026-05-12 (PM)** — PerfDog deep-dive integration. Added §3.6 PerfDog formulas (Jank / SmallJank / Smooth Index / FPower / CPU% freq-normalized), §5.1 +4 KPIs (FPower + CPU normalized + PerfDog Jank count + PerfDog Big Jank count), §5.2 phase relevance updated (FPower + CPU normalized weighting), §6.2.1 composite scoring alternatives (Smooth Index + Vitals-aligned ranking signal), §8 +2 decisions (#10 Smooth Index, #11 FPower anchors), §9 +7 SDD changes (#8 fpower-metric, #9 cpu-freq-normalized, #10 perfdog-jank-formula, #11 cli-headless-mode, #12 gh-action-wrapper, #13 multi-device-capture, #14 DEFER engine-mode-perfetto-capture), updated PerfDog row §2.1 with deep-dive findings (mandatory cloud, 11 platforms, 1 Hz default, Custom Data Extension SDK, gaps closeable vs not closing). New §10 Positioning statement. Renumbered References → §11, Changelog → §12. Source: engram obs #312 (`research/perfdog-deep-dive-2026-05-12`).
- **2026-05-12 (PM 2)** — PerfDog Help Center deep-dive (engram obs #328). Site confirmed gated; zero new features extractable. Methodology-transparency angle added to §10 positioning.
- **2026-05-18** — Added §2.5 "How competitor tools visually present performance data" (renumbered from §2.4 to avoid collision with parallel §2.3/§2.4 reordering committed in `770f3c4`). Covers 8 tools (GameBench, PerfDog, Snapdragon Profiler, ARM Streamline, Unity Profiler, Android Studio Profiler, Firebase Perf, Sentry Mobile Vitals). Distills dashboard layouts, KPI cards, chart types, severity coding, drill-down patterns, and comparison views. Surfaces 10 best patterns to copy, 6 anti-patterns to avoid, and 6 ranked recommendations for our HTML report (§7) — top 3 bundled into the `shareable-html-report` SDD change (§9 #3). Honesty notes preserved on tools whose UI is gated/JS-SPA (PerfDog wetest, Snapdragon Profiler, ARM Streamline, AS Profiler — marked "needs verification" where direct screenshot access failed). Engram obs `research/competitor-viz-patterns`.
