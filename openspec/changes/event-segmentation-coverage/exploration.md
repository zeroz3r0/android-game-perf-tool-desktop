# Exploration: event-segmentation-coverage

> NOTE: Orchestrator CWD = `firebase-remote-config-sync` (CWD basename bug). Artifact ACTUALLY belongs to project `android-game-perf-tool-desktop`. Engram observations saved under topic_keys `sdd/event-segmentation-coverage/*`.

## Context

Closes the auto-detection coverage matrix from `audit/event-segmentation-coverage-2026-05-12` (engram obs #308). Today the catalog has 9 SDK/engine signatures (`SdkSignatureCatalog.kt`) emitting 4 of the 6 user-visible `EventType` values — `INTERSTITIAL`, `REWARDED_VIDEO` (Unity Ads only), `IAP`, `LOADING` (Unity/Unreal/Cocos2d, wired in commit `7116786` quickfix). `FOREGROUND_LOSS` is iOS sidecar territory (EVT-010). `UNKNOWN` is a fallback bucket.

Coverage gaps blocking the KPI framework in `docs/competitive-analysis-and-kpis.md` §4:
1. **App startup / SDK init** — no `EventType.APP_STARTUP` or `EventType.SDK_INIT`, no cold-start sensor.
2. **Cinematics / Tutorials** — gameplay semantic, undetectable without instrumented hint from the game.
3. **Screen-to-screen navigation** — sensor data exists (dumpsys 1Hz top-stack) but classifier is missing.
4. **Rewarded vs Interstitial split** — `SdkSignature.type: EventType` is field-level, blocking AdMob/IS/AppLovin/Meta from emitting both INTERSTITIAL and REWARDED_VIDEO on shared activity classes.
5. **VR session** — completely unaddressed. PerfDog roadmap entry #312 / GameBench parity #289 surface VR thermal recovery as a critical post-VR phase. ANR is also passively detectable per PerfDog deep-dive (#312, item I.7).
6. **RATE_US** (Google Play In-App Review) — common pre-LTV interaction surface, undetected today.

This change is the structured roll-up of the audit findings (#308) + research state of the art (#306) + PerfDog deep-dive (#312, ANR-passive item) + GameBench-parity roadmap (#289).

## 7-Sprint plan (DAG order)

Hard order, with rationale:

```
Sprint 0 (SdkSignature refactor — BREAKING) ──┐
                                              ├──► Sprint 2b (rewarded vs interstitial split)
                                              │
Sprint 1 (APP_STARTUP + SDK_INIT + ANR)       │
                                              │
Sprint 2a (SCREEN_TRANSITION) ────────────────┤
                                              │
Sprint 3 (INSTRUMENTED mode opt-in tag)       │ ◄── independent
                                              │
Sprint 4 (VR_SESSION + VR_RETURN_TRANSITION)  │ ◄── independent, gated on research
                                              │
Sprint 5 (RATE_US)                            │ ◄── independent, trivial
                                              │
Sprint 6 (LEVEL_LOADING wire-up) ◄────────────┘ already DONE in commit 7116786
                                                 — mark `[x]` with reference
```

Sprint 0 MUST land first because Sprints 1, 2b, 4, 5 ALL need the new `openPatterns: List<Pair<Regex, EventType>>` shape to add multiple event types per SDK (e.g. AdMob INTERSTITIAL + REWARDED_VIDEO + SDK_INIT on the same `Ads`/`MobileAds` tags). Without it, every multi-type SDK collision falls back to the existing field-level single-type field — same trap that mistags AdMob rewarded as INTERSTITIAL today.

Hard decisions baked in (no escalation per the launch contract):

| Q | Decision |
|---|----------|
| Subpackage | FLAT under `core/events/` (existing location). |
| Sprint 0 scope | Single PR, atomic, all-or-nothing. NO incremental migration. |
| Instrumented protocol name | `GamePerf:I` tag (avoid GameBench trademark). |
| EventType for instrumented | Single `INSTRUMENTED` with `phase: String` field (don't pollute enum). |
| ANR severity | Separate `EventType.ANR`, HIGH confidence, NOT bundled with APP_STARTUP. |
| VR Sprint 4 effort | TBD post-research; this exploration fills it. May split 4a / 4b. |
| Sprint 6 | Mark complete, reference commit 7116786. |

## Repository state verified

| Item | Value |
|------|-------|
| Branch | `fix/autoupdater-resilience-v4-4-1` |
| HEAD | `f335444` docs(kpi): integrate PerfDog deep-dive findings |
| Commits ahead origin/main | 25 (NOT pushed) |
| Baseline test count | 837 (after LOADING quickfix commit `7116786`) |
| Working tree | clean except `GAMEBENCH-COMPARISON.md` untracked (working doc) |
| OpenSpec changes pending | `gpu-usage-percent` (paused) — independent, no conflict |
| Core spec | `openspec/specs/core/spec.md` contains EVT-001..EVT-010 (used as the parent baseline) |

## Files reviewed (relevant for this change)

| File | Why it matters |
|------|----------------|
| `src/main/kotlin/com/gameperf/desktop/core/events/DetectedEvent.kt` | Defines `EventType` enum (6 values today) + `Confidence` + `DetectedEvent` data class. Sprint 1/2a/2b/3/4/5 all add EventType variants. |
| `src/main/kotlin/com/gameperf/desktop/core/events/SdkSignature.kt` | THE breaking refactor target. `type: EventType` field → `openPatterns: List<Pair<Regex, EventType>>`. |
| `src/main/kotlin/com/gameperf/desktop/core/events/SdkSignatureCatalog.kt` | 9 catalog entries; Sprint 0 migrates ALL 9. Sprints 1/2b/4/5 add new entries. |
| `src/main/kotlin/com/gameperf/desktop/core/events/EventDetectorImpl.kt` | Houses `handleLogLine`, `handleActivityStack`, `handleGap`. Sprint 0 changes 2 call sites (`sig.type` lines 242, 263). Sprint 2a adds SCREEN_TRANSITION emission in `handleActivityStack` step 2 (line ~176-184). Sprint 1 adds cold-start sensor + PID-restart detector. |
| `src/main/kotlin/com/gameperf/desktop/core/events/DumpsysPoller.kt` | Provides 1Hz top-of-stack frames; SCREEN_TRANSITION consumer (Sprint 2a). No changes to poller itself. |
| `src/main/kotlin/com/gameperf/desktop/core/events/LogcatLineParser.kt` | Pure regex-based threadtime parser; no changes. |
| `src/test/kotlin/com/gameperf/desktop/core/events/SdkSignatureCatalogTest.kt` | 363 lines; baseline 9-SDK count assertion + ~5 `sig.type` usages — migrates in Sprint 0. |
| `src/test/kotlin/com/gameperf/desktop/core/events/EventDetectorImplTest.kt` | 338 lines; type/lifecycle/cap/gap/foreground-guard coverage. Sprint 0 migrates `EventType.INTERSTITIAL` etc. assertions on detector output (4 sites). |
| `src/test/kotlin/com/gameperf/desktop/core/events/LoadingSignaturesTest.kt` | 266 lines; reference for engine-tag-allowlist + fixture style (Sprint 6 already shipped). |
| `src/test/resources/logcat-fixtures/*.log` | 9 fixtures today (admob, applovin, cocos2d-loading, ironsource, meta-audience, play-billing-launch, unity-ads, unity-loading, unreal-loading). Sprint 1/2b/3/4/5 each add fixtures. |
| `src/main/kotlin/com/gameperf/desktop/report/ReportGenerator.kt` | `EventType → label` (L1225-1230) + `EventType → color` (L1233-1237). Every new EventType extends both `when` branches. |
| `src/main/kotlin/com/gameperf/desktop/core/conclusions/rules/LoadingThermalRecoveryRule.kt` | Reads `ev.type == EventType.LOADING`. VR_RETURN_TRANSITION rule will follow this pattern in Sprint 4 (thermal/RAM recovery post-VR). |
| `CLAUDE.md` | Operative SOP: single-source-of-truth catalog, regex top-level, tests puros sin mocks, fixtures `.log` style. All seven sprints honor these rules. |
| `docs/competitive-analysis-and-kpis.md` §4 | Source-of-truth for the gap matrix (audit obs #308 → §4.2 → §4.4 detection tiers). |

## Grep-confirmed SdkSignature refactor scope

`SdkSignature.type` callers in **production code**: **EXACTLY 2** sites, both inside `EventDetectorImpl.kt`:
- Line 242 `type = sig.type` inside `tryOpen()` (logcat-driven open).
- Line 263 `type = sig.type` inside `tryOpenActivity()` (dumpsys-driven open).

Sprint 0 replaces both with the resolved `(Regex, EventType)` match from `matchOpen` / `matchActivity`. The activity-level path matches against `activityClasses` (not a regex), so the catalog needs a parallel `activityEventTypes: List<Pair<String, EventType>>` if any SDK ever emits two types from its activity classes — for v1 we use a `defaultType: EventType` field on `SdkSignature` (the type emitted when the activity-class path opens; e.g. AdMob `AdActivity` always opens as INTERSTITIAL because the activity itself doesn't tell us which ad format is loaded — only the logcat patterns do).

**Test files referencing `EventType.X` directly** that touch event-detection contract (Sprint 0 migration scope):

| Test file | `sig.type` / `EventType` assertions | Migrates how |
|-----------|-------------------------------------|--------------|
| `SdkSignatureCatalogTest.kt` | 5 sites: `matched.first.type` assertions on AdMob/UnityAds/Play Billing (L100, L126, L227); `sig.type != EventType.LOADING` guard (L68); fixture loop | Replace direct `sig.type` reads with `assertContainsType(sig, EventType.X)` helper that scans `sig.openPatterns.map { it.second }`. |
| `EventDetectorImplTest.kt` | 4 sites on `events[0].type` (post-detection result) | NO CHANGE — `DetectedEvent.type` stays. Only `SdkSignature.type` is removed. |
| `LoadingSignaturesTest.kt` | 5 sites: `matched.first.type` assertions on Unity/Unreal/Cocos2d LOADING | Same migration as SdkSignatureCatalogTest — use helper. |

Test files referencing `EventType.X` that **do NOT touch the refactor** (continue to work unchanged):
- `LoadingThermalRecoveryRuleTest.kt` (5 sites) — reads `DetectedEvent.type` only.
- `ConclusionTestFixtures.kt` (1) — builds `DetectedEvent`.
- `SessionHistoryRoundTripTest.kt` (1) — builds `DetectedEvent`.
- `AppViewModelAggregationTest.kt` (1) — builds `DetectedEvent`.
- `FilteredMetricsCalculatorTest.kt` (3) — builds `DetectedEvent`.

**Overestimate margin**: 14 test methods total touch the refactor (5 + 4 + 5). Sprint 0 budgets 1.0 day with the helper.

`ReportGenerator.kt` `when (event.type)` branches (L1225 + L1233): both reference `event.type` (i.e. `DetectedEvent.type`), not `SdkSignature.type` — UNCHANGED.

## VR SDK research (15-min webfetch budget — actually consumed)

Logged in detail in engram obs #316 (`research/vr-sdk-android-logcat`). Summary:

| VR runtime | Logcat tag(s) | Detectability via passive logcat | Decision |
|------------|---------------|----------------------------------|----------|
| **Meta Quest (Horizon OS, VrApi)** | `VrApi` + `XrPerformanceManager` | ✅ Confirmed. VrApi emits **stats at 1Hz** with FPS/GPU%/CPU% inline. Works for native Quest, Unity Quest, Unreal Quest because the runtime emits the logs regardless of app engine. | **IN Sprint 4a.** |
| **OpenXR generic (Android XR, standalone HMDs)** | OpenXR Loader logging not standardized for session lifecycle. `XR_SESSION_STATE_*` events are in-process (`xrCreateSession` / `xrEndSession` man pages, Khronos). | ❌ Not reliably detectable without `org.khronos.openxr` loader emit-on-state-change which is not specified. | **Sprint 4b — DEFERRED** until vendor patterns emerge OR test device available. |
| **Unity OpenXR Plugin** | Tag `[XR]`, one-shot diagnostic blocks `==== Start Unity OpenXR Diagnostic Report ====`. | ⚠️ Marker-only (single line). Doesn't bracket the session. | **NOT in Sprint 4a** — diagnostic is not a session boundary. |
| **Unreal XR (non-Quest)** | `LogHMD`, `LogStereoRendering` (UE conventions). | ⚠️ Not verified emit at session enter/exit specifically. | **NOT in Sprint 4a.** |
| **Google Cardboard** | Open-source SDK; no documented logcat tags. | ❌ Deprecated by Google 2019-2020. | **OUT of scope.** |

**Confirmed sources**:
- `developers.meta.com/horizon/documentation/unreal/ts-logcat-stats/` (cached via DuckDuckGo HTML) — explicit "logcat tags VrApi and XrPerformanceManager".
- `developers.meta.com/horizon/documentation/native/android/ts-logcat/` (cached) — VrApi Stats Guide reference.
- `github-wiki-see.page/m/o3de/o3de-extras/wiki/Profiling-tools-for-Meta-Quest-2` — "Meta Quest apps always logs to logcat VrApi stats with basic performance statistics once per second."
- `github.com/iflow-mcp/meta-quest-agentic-tools/.../logcat-filtering.md` — `--tag VrApi`, `--tag Unity`, `--tag UnrealEngine`.
- `docs.unity3d.com/Packages/com.unity.xr.openxr@1.10/manual/index.html` — Unity OpenXR diagnostic log shape.
- `registry.khronos.org/OpenXR/specs/1.0/man/html/xrCreateSession.html` — session state lifecycle is in-process event delivery.

**Anti-bot block**: `developer.oculus.com` AND `developers.meta.com` directly return HTTP 400 on webfetch. Confirmed in PerfDog research too (#312). Cached aggregator snippets via DuckDuckGo HTML endpoint were the workable path.

**Sprint 4 effort split**:
- **Sprint 4a — Quest detection only (VrApi).** 1.0d. Single SDK signature, single fixture, open via first `VrApi` stats line in foreground game window, close via silent-gap heuristic (no `VrApi` lines for ≥5s). VR_RETURN_TRANSITION emitted as a separate event N seconds post-close (configurable window).
- **Sprint 4b — generic OpenXR / Cardboard / non-Quest Unreal XR.** DEFERRED. Risk: vendor-specific patterns require test devices we don't have. Document as known gap.

## Risks identified

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Sprint 0 breaking refactor de-stabilizes existing tests | Medium | Atomic single-PR; helper extraction; all 14 touch-points migrated together; per-batch `./gradlew test` green gate. |
| Sprint 0 introduces `defaultType` for activity-class path inconsistent with `openPatterns` discriminator | Low | Spec ESC-001 explicitly requires `defaultType` field; tests cover activity-only path stays MEDIUM confidence + correct type. |
| Sprint 1 cold-start sensor double-fires on PID restart | Medium | Suppress repeat APP_STARTUP within 10s; emit warning instead of duplicate event. |
| Sprint 1 SDK_INIT vs INTERSTITIAL collision on shared `Ads`/`MobileAds` tags | High (this is the whole reason for Sprint 0) | Sprint 0 enables `openPatterns: List<(Regex, EventType)>` — init patterns (`"Initializing"`, `"init success"`) discriminate within shared tag set. Time-window guard: classify as SDK_INIT only within first 10s post-APP_STARTUP. |
| Sprint 1 passive ANR detection (`am_anr` atom) may need additional logcat tags beyond catalog | Medium | Add tag-allowlist for `ActivityManager` (where `am_anr` logs) as new entry in catalog with tag `ActivityManager`. Document tag-allowlist size expansion in Sprint 1 task. |
| Sprint 2a SCREEN_TRANSITION floods reports on activity-heavy apps | Medium | Apply existing EVT-009 cap (500) + add per-type sub-cap of 100 transitions/session to prevent SCREEN_TRANSITION crowding out ad detection. |
| Sprint 2a NO-OP on single-activity games (Unity/Unreal) | High | Documented limitation. Workaround: opt-in instrumented tag (Sprint 3) `GamePerf:I Screen.Enter name="..."`. |
| Sprint 2b rewarded patterns produce false positives on AdMob "load" (vs "show") | Medium | Open patterns explicitly chosen to match show events only (`onUserEarnedReward`, `rewardedVideoDidOpen`, etc.); negative tests required for every SDK. |
| Sprint 3 instrumented protocol may collide with future GameBench-like adoption | Low | We chose `GamePerf:I` tag (avoid trademark). Protocol shape mirrors GameBench `gb_marker_*` for migration compatibility. |
| Sprint 4a VrApi heuristic silent-gap close threshold mis-tuned | Medium | Make close gap window a constant in EventDetector; start at 5s; document in spec ESC-VR-002. |
| Sprint 4a Quest VrApi tag NOT emitted on Android XR (pure OpenXR) devices | Confirmed | Documented as Sprint 4b boundary; Quest-only is the v1 claim. |
| Sprint 5 RATE_US activity name change in Play Core library across versions | Low | Catalog entry uses `com.google.android.play.core.review.ReviewActivity` + tag-allowlist `ReviewManager`, `PlayCore`. Documented as a watch-the-version-on-release item. |
| Total test count growth pushes CI over time budget | Low | Estimated +60-80 tests across 7 sprints; total ~915. Current CI passes 837 in ~3-4 min. |
| Engram CWD bug saves artifacts under wrong project | Confirmed | Topic_keys are source of truth (`sdd/event-segmentation-coverage/*`); cross-project search documented in launch contract. |

## Estimated effort (per-sprint, totals)

| Sprint | Description | Effort | TDD batches |
|--------|-------------|--------|-------------|
| 0 | `SdkSignature` refactor — BREAKING internal | 1.0d | 1 |
| 1 | APP_STARTUP + SDK_INIT + ANR | 2.0d | 3 (cold-start, SDK init, ANR) |
| 2a | SCREEN_TRANSITION | 0.5d | 1 |
| 2b | Rewarded vs Interstitial split | 1.0d | 2 (4 SDKs × catalog rewrite, fixtures) |
| 3 | INSTRUMENTED opt-in tag protocol | 1.0d | 2 |
| 4a | VR_SESSION + VR_RETURN_TRANSITION (Quest only) | 1.0d | 2 |
| 4b | Generic OpenXR / non-Quest VR | DEFERRED | — |
| 5 | RATE_US | 0.5d | 1 |
| 6 | LEVEL_LOADING wire-up | ALREADY SHIPPED commit `7116786` | mark `[x]` |
| **Total (4a in, 4b out)** | | **7.0d** | **12 batches** |

Budget for `./gradlew test` re-run at end of each sprint = +30 min total across the 7 sprints. Detekt clean per sprint.

## Risks NOT in this change (explicit)

- Manual `MarkerType` is preserved AS-IS. MAN-001 spec guarantees `addTimelineMarker` cosmetic behavior; we don't merge `EventType` and `MarkerType`.
- `FilteredMetricsCalculator` filtering policy stays unchanged — new EventTypes inherit the same filter mask if they overlap with current ad/IAP windows. KPI framework §5.2 in `docs/competitive-analysis-and-kpis.md` defines weights; we do NOT touch them here.
- iOS sidecar (`EventType.FOREGROUND_LOSS`) is untouched.
- VR Sprint 4b, OpenXR generic, Cardboard, RUM, cloud upload — all explicitly out.
- PerfDog Smooth Index, FPower, CPU-freq-normalized — separate SDD changes (`fpower-metric`, `cpu-freq-normalized`).

## Recommendation

Proceed with proposal + spec + design + tasks. Spec MUST include `defaultType` field on `SdkSignature` for the activity-class path (Sprint 0 not just `openPatterns`). VR Sprint 4 SHOULD be split as 4a (Quest only, in scope) and 4b (everything else, deferred with risk note).
