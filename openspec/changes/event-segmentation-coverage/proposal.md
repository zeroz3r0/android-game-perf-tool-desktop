# Proposal: event-segmentation-coverage

> NOTE: Orchestrator CWD = `firebase-remote-config-sync` (CWD basename bug). Artifact ACTUALLY belongs to project `android-game-perf-tool-desktop`. Engram topic_keys `sdd/event-segmentation-coverage/*` are source of truth.

## Intent

Close the auto-detection coverage gap surfaced by `audit/event-segmentation-coverage-2026-05-12` (obs #308). Move the catalog from **6 detected phases** (interstitial, rewarded — partial, IAP, loading, foreground loss, unknown) to **12 detected phases**: add APP_STARTUP, SDK_INIT, ANR (passive), SCREEN_TRANSITION, rewarded vs interstitial split (4 SDKs fixed), INSTRUMENTED (opt-in protocol), VR_SESSION + VR_RETURN_TRANSITION (Quest), and RATE_US. Foundation work: refactor `SdkSignature` from field-level `type: EventType` to discriminated `openPatterns: List<Pair<Regex, EventType>>`, atomic and breaking internally.

This is the structured response to audit #308 + research #306 + PerfDog #312 + GameBench parity #289, NOT the iOS sidecar work (out of scope) and NOT the KPI scoring framework (separate SDD).

## Positioning

- Local-first, adb-only, zero on-device install. Same constraint as v4.4.0 event detection.
- Validates the audit's recommendation: opt-in instrumented protocol is the only honest path to CINEMATIC/TUTORIAL semantic markers. We DO NOT lie about auto-detection where it's not feasible.
- Sprint 4a is **Quest-only**. We do NOT claim to detect "VR sessions" generically. Sprint 4b is explicitly deferred.
- ANR detection is **passive** — logcat `ActivityManager am_anr` atom scraping. Bundles PerfDog deep-dive item I.7 (obs #312).

## Scope

### In Scope (this change)

**Sprint 0 — `SdkSignature` refactor (BREAKING internal, 1.0d)**
- Refactor `data class SdkSignature(val type: EventType, ...)` → `data class SdkSignature(val defaultType: EventType, val openPatterns: List<Pair<Regex, EventType>>, ...)`.
- `defaultType` is the EventType emitted when the activity-class path opens an event (the activity classes themselves don't discriminate format — e.g. `AdActivity` for AdMob can be INTERSTITIAL or REWARDED at runtime, the logcat patterns tell us which). `defaultType` is the safe fallback.
- `openPatterns` discriminates the logcat-driven open: each entry is `(Regex, EventType)`. First-match wins.
- Migrate ALL 9 existing catalog entries atomically. No partial migration.
- Migrate 14 test methods (5 in `SdkSignatureCatalogTest`, 4 in `EventDetectorImplTest` event-result assertions stay the same — only `sig.type` reads change, 5 in `LoadingSignaturesTest`).
- Production code changes: 2 lines in `EventDetectorImpl.kt` (lines 242, 263).

**Sprint 1 — APP_STARTUP + SDK_INIT + ANR (2.0d)**
- New `EventType.APP_STARTUP` — emitted when game package first appears in dumpsys top-stack DURING capture. Sensor: dumpsys cmp first match of `gamePackage/`. PID restart detector: emit warning + new APP_STARTUP if `/proc/<pid>` changes mid-session.
- New `EventType.SDK_INIT` — 6 SDK signatures (Firebase / AppMeasurement, AdMob init, IronSource init, Unity Ads init, AppLovin init, Meta Audience init). Init patterns (`"Initializing"`, `"init success"`, `"SDK initialized"`) classify within first 10s post-APP_STARTUP; outside that window they downgrade to vendor's normal `defaultType`.
- New `EventType.ANR` — single catalog entry for `ActivityManager` tag with pattern `am_anr.*Process` (or similar — final regex empirically anchored in Sprint 1 tasks). HIGH confidence. NOT bundled with APP_STARTUP severity.
- 3 new fixtures: `app-startup-cold.log`, `sdk-init-firebase.log` (combined Firebase/AppMeasurement), `anr-game.log`.

**Sprint 2a — SCREEN_TRANSITION (0.5d)**
- New `EventType.SCREEN_TRANSITION`. Emitted in `EventDetectorImpl.handleActivityStack` step 2 (currently L176-184 only refreshes `lastGameForegroundMs`) when `top.cmp` changes AND new `cmp` starts with `$gamePackage/`. Confidence MEDIUM (dumpsys-only). startMs=now; endMs=next change.
- Caveat: single-activity games (Unity/Unreal) do not emit. Documented in spec scenario.
- Per-type sub-cap of 100 SCREEN_TRANSITIONs/session (in addition to EVT-009 global 500).

**Sprint 2b — Rewarded vs Interstitial split (1.0d)**
- Uses Sprint 0 refactor. Add rewarded-specific open patterns to AdMob (`onUserEarnedReward`, `onRewardedAdLoaded`, etc.), IronSource (`rewardedVideoDidOpen`, `onRewardedVideoAdShowSucceeded`), AppLovin (`onRewardedVideoStarted`, `onRewardedAdReceivedReward`), Meta Audience (`onRewardedVideoCompleted`).
- Each pattern maps to `EventType.REWARDED_VIDEO`. The existing INTERSTITIAL patterns stay with `EventType.INTERSTITIAL`.
- 4 new fixtures: `admob-rewarded.log`, `ironsource-rewarded.log`, `applovin-rewarded.log`, `meta-rewarded.log`.

**Sprint 3 — INSTRUMENTED opt-in tag protocol (1.0d)**
- New `EventType.INSTRUMENTED`. Single `DetectedEvent.metadata["phase"]` field holds the user-supplied name ("CINEMATIC", "TUTORIAL", "GAMEPLAY_DENSE", "SPECIAL_EVENT", arbitrary).
- Single catalog entry `SdkSignature("GamePerf", defaultType = INSTRUMENTED, logcatTags = ["GamePerf"], openPatterns = listOf(Regex(...) to INSTRUMENTED), ...)`.
- Protocol: `GamePerf:I {Tag}.Start name="..." [group="..."]` and `GamePerf:I {Tag}.Stop name="..."`.
- 1 new fixture: `instrumented-protocol.log` showing CINEMATIC + TUTORIAL + GAMEPLAY_DENSE + SPECIAL_EVENT in sequence.
- README snippet (English + Spanish) with copy-paste Kotlin/Java + Unity C# + Unreal C++ examples. Spanish doc tuteo-formal.

**Sprint 4a — VR_SESSION + VR_RETURN_TRANSITION (Quest only) (1.0d)**
- New `EventType.VR_SESSION` + `EventType.VR_RETURN_TRANSITION`.
- Single Meta Quest signature: tag `VrApi` (+ `XrPerformanceManager` allowlist), open pattern matches first `VrApi` stats line within game foreground guard window. Close = silent gap heuristic (5s configurable constant).
- VR_RETURN_TRANSITION emitted at VR_SESSION close + a `vrReturnTransitionMs` window (default 5000ms) — captures post-VR thermal/RAM/GPU recovery period flagged by user as critical performance regression surface.
- Confidence HIGH (specific to Quest runtime, low false-positive risk).
- 1 new fixture: `quest-vrapi-session.log`.

**Sprint 4b — Generic OpenXR / non-Quest VR — DEFERRED.** Documented in `risks` section. Not in this change.

**Sprint 5 — RATE_US (0.5d)**
- New `EventType.RATE_US`. Single signature for Google Play In-App Review. Activity class `com.google.android.play.core.review.ReviewActivity` (sometimes nested in `com.google.android.play.core.review.x.x`). Tag allowlist `ReviewManager`, `PlayCore`.
- Open patterns include the activity launch pattern AND `launchReviewFlow` logcat hint.
- 1 new fixture: `rate-us-play-core.log`.

**Sprint 6 — LEVEL_LOADING wire-up — ALREADY SHIPPED.** Commit `7116786` added Unity/Unreal/Cocos2d signatures. Tasks.md marks `[x]` with commit hash reference. NO additional work in this change.

### Out of Scope

- iOS sidecar `EventType.FOREGROUND_LOSS` — untouched. EVT-010 stays as-is.
- KPI scoring framework, `FilteredMetricsCalculator` filter changes — separate SDD (`kpi-scoring-framework`).
- PerfDog FPower, Smooth Index, CPU-freq-normalized — separate SDDs (`fpower-metric`, `cpu-freq-normalized`).
- Cloud upload, multi-device GUI, CLI headless — separate SDDs (`cli-headless-mode`, `multi-device-capture`).
- `MarkerType` enum reform — MAN-001 preserves it as-is.
- Generic OpenXR detection / Cardboard / non-Quest Unreal XR — Sprint 4b, deferred.
- Heuristic-only loading detection (frame-time signature) — explicitly rejected by audit #308 + research #306 as unreliable.
- Auto-detection of CINEMATIC/TUTORIAL without instrumented hint — confirmed infeasible by audit (gameplay semantic, not SDK-detectable).
- Hierarchical phase containers — flat `List<DetectedEvent>` stays.

## Capabilities

### New capability: `event-segmentation`

Adds 6 new `EventType` values + the refactored `SdkSignature` discrimination + the cold-start sensor + the passive ANR detector + the SCREEN_TRANSITION classifier + the rewarded/interstitial split + the GamePerf:I instrumented protocol + the Quest VR_SESSION/VR_RETURN_TRANSITION pair + the RATE_US detector. Requirement IDs `ESC-001..ESC-NNN` (final count: ~38 requirements).

### Modified capability: core

Extends EVT-003 (SDK signature matching) to accept the new discriminated `openPatterns` structure. Backwards-compat aspect: catalog entries keep the same logcat tag allowlist + activity classes. Detector tests for EVT-005..EVT-009 stay green by virtue of `DetectedEvent.type` continuing to be the result contract.

## Approach

### Detection tiers (mirrors `docs/competitive-analysis-and-kpis.md` §4.4)

- **TIER 1 (this change ships)**: catalog-driven signatures for SDK_INIT, ANR, RATE_US, Quest VR. Cold-start via dumpsys first-game-foreground. SCREEN_TRANSITION via dumpsys cmp change. Instrumented opt-in tag protocol.
- **TIER 2 (NOT in this change)**: frame-time signature heuristics for LOADING / SDK_INIT correlation. Deferred.
- **TIER 3 (NOT in this change)**: ad SDK auto-detection beyond catalog without empirical capture. Deferred.

### Sprint 0 refactor pattern

```kotlin
// Before
internal data class SdkSignature(
    val sdk: String,
    val type: EventType,                   // ◄── REMOVED
    val activityClasses: List<String>,
    val logcatTags: List<String>,
    val openPatterns: List<Regex>,         // ◄── CHANGES shape
    val closePatterns: List<Regex>,
)

// After
internal data class SdkSignature(
    val sdk: String,
    val defaultType: EventType,            // ◄── NEW: type used when activity-class path opens
    val activityClasses: List<String>,
    val logcatTags: List<String>,
    val openPatterns: List<Pair<Regex, EventType>>,   // ◄── NEW shape; first-match wins
    val closePatterns: List<Regex>,
)
```

`matchOpen()` returns `Triple<SdkSignature, Regex, EventType>` (or wraps in a `MatchResult` data class). `tryOpen()` uses the resolved EventType from the match, not `sig.type`. `tryOpenActivity()` uses `sig.defaultType`.

The detector flow stays UNCHANGED. Only the `type` source is now per-pattern instead of per-signature.

### Sprint 1 cold-start sensor

In `EventDetectorImpl.handleActivityStack`, when `lastGameForegroundMs == -1L` AND the new top.cmp first matches `gamePackage/`, emit an `APP_STARTUP` event with confidence MEDIUM (dumpsys-only) and source `dumpsys-firstforeground`. Mark `endMs` after the first 10s post-foreground OR when first SDK_INIT closes (whichever later, capped at 30s).

PID restart: lightweight per-tick `/proc/<gamePid>` existence check via `adb shell stat`. If it goes from present-to-absent-to-present mid-session, emit warning + new APP_STARTUP with `metadata["restart"]="true"`. Suppress duplicate APP_STARTUP emissions within 10s window.

### Sprint 1 SDK_INIT discrimination

Same shared tag (`Ads`, `MobileAds`, `UnityAds`, `IronSource`, `AppLovinSdk`, `FBAudienceNetworkLog`, plus new `Firebase`, `FA`) — Sprint 0's `openPatterns: List<Pair<Regex, EventType>>` lets us add init-specific patterns to the same SDK entry. Discriminator: classify as SDK_INIT only within 10s post-APP_STARTUP; outside that window, the same pattern downgrades to vendor default (INTERSTITIAL/REWARDED_VIDEO).

The 10s window is enforced inside the detector (`handleLogLine` checks against `lastAppStartupMs`) — it's a runtime gate, not a catalog change. Catalog stays declarative.

### Sprint 1 ANR detector

Single new catalog entry `SdkSignature("System ANR", defaultType = ANR, logcatTags = ["ActivityManager"], openPatterns = listOf(Regex("""am_anr""") to ANR), closePatterns = listOf(Regex("""am_proc_died""")), activityClasses = emptyList())`. Foreground guard relaxed for this type (ANR can fire from a frozen game still-on-top). Confidence HIGH.

### Sprint 2a SCREEN_TRANSITION emission

In `handleActivityStack` step 2, when `top.cmp.startsWith("$gamePackage/")` AND `top.cmp != lastTopCmp`, emit SCREEN_TRANSITION (instead of just refreshing `lastGameForegroundMs`). Maintain `lastTopCmp` field. Per-type sub-cap of 100/session prevents flooding.

### Sprint 3 INSTRUMENTED protocol

```
05-12 14:32:18.456  1234  5678 I GamePerf: I CINEMATIC.Start name="intro_cutscene"
05-12 14:33:22.111  1234  5678 I GamePerf: I CINEMATIC.Stop name="intro_cutscene"
```

Regex: `Regex("""(?i)^\s*([A-Z_]+)\.(Start|Stop)(?:\s+name="([^"]+)")?""")`. The catalog entry uses a SINGLE open pattern that captures `(phase, name?, group?)` into metadata. The `Start`/`Stop` discrimination falls under existing `openPatterns` / `closePatterns` lists.

### Sprint 4a Quest VR detection

```
05-12 14:50:00.100  1234  5678 I VrApi: FPS=72 Prd=33ms Tear=0 Early=0 Stale=0 ...
```

Open: first `VrApi`-tagged line within foreground guard. Close: 5s silent gap without `VrApi` lines (heuristic). VR_RETURN_TRANSITION emitted at VR_SESSION close + `vrReturnTransitionWindowMs` constant (default 5000).

Catalog entry has `defaultType = VR_SESSION`. `openPatterns = listOf(Regex(""".*""") to VR_SESSION)` — every VrApi-tagged line is treated as evidence the session is active. Detector internal: tracks `lastVrApiLineMs`; on silent gap emits close + VR_RETURN_TRANSITION.

### Sprint 5 RATE_US

```
05-12 14:55:00.000  1234  5678 I PlayCore: ReviewManager: launchReviewFlow invoked
05-12 14:55:01.000  1234  5678 I ActivityManager: Displayed com.example/com.google.android.play.core.review.ReviewActivity
```

Open: activity match `ReviewActivity` OR logcat pattern `launchReviewFlow`. Close: activity left stack OR `onComplete` log. Confidence MEDIUM (activity-driven typical).

## Affected areas

| Area | Impact | Description |
|------|--------|-------------|
| `core/events/DetectedEvent.kt` | Modified | Add 7 new `EventType` values: APP_STARTUP, SDK_INIT, ANR, SCREEN_TRANSITION, INSTRUMENTED, VR_SESSION, VR_RETURN_TRANSITION, RATE_US. (7 plus existing 6 = 13 enum values; reuses LOADING/INTERSTITIAL/REWARDED_VIDEO/IAP unchanged.) |
| `core/events/SdkSignature.kt` | Modified | Sprint 0 BREAKING refactor: `type: EventType` → `defaultType: EventType` + `openPatterns: List<Pair<Regex, EventType>>`. |
| `core/events/SdkSignatureCatalog.kt` | Modified | All 9 existing entries migrate to new shape (Sprint 0). Add ~10 new entries: 6 SDK_INIT (Sprint 1), 1 ANR (Sprint 1), 0 (SCREEN_TRANSITION is detector-internal), 1 INSTRUMENTED (Sprint 3), 1 Quest VR (Sprint 4a), 1 RATE_US (Sprint 5). Rewarded patterns merge into existing 4 SDKs (Sprint 2b). |
| `core/events/EventDetectorImpl.kt` | Modified | Sprint 0: 2 lines use resolved-match type instead of `sig.type`. Sprint 1: cold-start sensor in `handleActivityStack`; PID restart detector + 10s post-startup gate for SDK_INIT. Sprint 2a: SCREEN_TRANSITION emission in `handleActivityStack` step 2. Sprint 4a: VrApi silent-gap close logic + VR_RETURN_TRANSITION delayed emission. |
| `core/events/MatchResult.kt` (NEW) | New | Helper data class `internal data class MatchResult(val sig: SdkSignature, val pattern: Regex, val resolvedType: EventType)` for `matchOpen` return value. |
| `core/events/DumpsysPoller.kt` | UNCHANGED | Still 1Hz top-stack polling. No change. |
| `core/events/LogcatLineParser.kt` | UNCHANGED | Threadtime parser unchanged. |
| `report/ReportGenerator.kt` | Modified | Extend `when (event.type)` label mapping (L1225-1230) + color mapping (L1233-1237) with 7 new EventTypes. Spanish tuteo labels: APP_STARTUP="Inicio", SDK_INIT="Inicialización SDK", ANR="App no responde (ANR)", SCREEN_TRANSITION="Cambio de pantalla", INSTRUMENTED="Marcador instrumentado", VR_SESSION="Sesión VR", VR_RETURN_TRANSITION="Recuperación post-VR", RATE_US="Solicitud de valoración". |
| `core/conclusions/rules/*` | Modified (additive) | Add `PostVrRecoveryRule.kt` (analog to `LoadingThermalRecoveryRule`) consuming VR_RETURN_TRANSITION + thermal recovery window. Add `AnrSeverityRule.kt` flagging any ANR event. NO existing rule touched. |
| `viewmodel/AppViewModel.kt` | UNCHANGED | Consumes `DetectedEvent` opaquely via StateFlow. |
| `core/SessionHistory.kt` | UNCHANGED | `DetectedEvent` is `@Serializable`; new enum values deserialize cleanly. Defaults preserve backward-compat. |
| `src/test/resources/logcat-fixtures/` | New files | +8 fixtures: `app-startup-cold.log`, `sdk-init-firebase.log`, `anr-game.log`, `admob-rewarded.log`, `ironsource-rewarded.log`, `applovin-rewarded.log`, `meta-rewarded.log`, `instrumented-protocol.log`, `quest-vrapi-session.log`, `rate-us-play-core.log`. (Total ~10 new fixtures.) |
| `src/test/kotlin/com/gameperf/desktop/core/events/SdkSignatureCatalogTest.kt` | Modified | Sprint 0 migrates `sig.type` reads. Sprints 1-5 add positive/negative tests for new entries. Estimated +20 tests. |
| `src/test/kotlin/com/gameperf/desktop/core/events/EventDetectorImplTest.kt` | Modified | Sprint 0 unchanged (tests `DetectedEvent.type`). Sprints 1/2a/4a add lifecycle tests for cold-start, SCREEN_TRANSITION, VR session. Estimated +12 tests. |
| New test files | New | `SdkSignaturePatternsTest.kt` (Sprint 0 helper coverage), `AppStartupDetectorTest.kt` (Sprint 1), `ScreenTransitionTest.kt` (Sprint 2a), `RewardedSignaturesTest.kt` (Sprint 2b), `InstrumentedProtocolTest.kt` (Sprint 3), `QuestVrSessionTest.kt` (Sprint 4a), `RateUsSignaturesTest.kt` (Sprint 5), `PostVrRecoveryRuleTest.kt` (Sprint 4a — conclusions), `AnrSeverityRuleTest.kt` (Sprint 1 — conclusions). +9 test files. |
| `CLAUDE.md` | Modified | Append note to §"Patrón operativo: cómo añadir un SDK nuevo" — for SDKs that emit BOTH init and show variants, register multiple `(Regex, EventType)` pairs in `openPatterns` instead of multiple entries. |
| `README.md` + `README_EN.md` | Modified | New section "Eventos detectados automáticamente" / "Auto-detected events" listing the full set + instrumented protocol example. |
| `docs/competitive-analysis-and-kpis.md` §4.2 | Modified | Update coverage matrix rows 1, 5, 7 to ✅. |
| `CHANGELOG.md` | Modified | v4.5.x entry: "Detección de eventos ampliada: APP_STARTUP, SDK_INIT, ANR, SCREEN_TRANSITION, INSTRUMENTED, VR (Quest), RATE_US; refactor interno de `SdkSignature` para split rewarded vs interstitial." |

## Test strategy

- **TDD strict red→green** per sprint per batch. Mirror `gpu-usage-percent` workflow. Runner `./gradlew test`. Each batch ends green.
- **Tests puros sin mocks** (CLAUDE.md SOP). All detector tests drive `handleLogLine` / `handleActivityStack` synthetically. Bridge tests via `FakeAdbBridge.shellResponses`.
- **Fixture-driven smoke**: every new SDK signature gets a `.log` fixture matched by an `assertFixtureProducesOpenAndClose(...)` helper (already in `SdkSignatureCatalogTest`).
- **Negative tests mandatory** per CLAUDE.md SDK SOP: positive open, positive close, negative same-tag noise, negative foreign-tag with matching message, cross-SDK negative (SDK A's close does NOT close SDK B's event).
- **Sprint 0 invariant**: full suite (837 baseline) must stay green after refactor. Per-sprint test count growth:

| Sprint | Tests added (est) | Cumulative | Sprint end count |
|--------|-------------------|------------|------------------|
| 0 | +0 (migration) | 0 | 837 |
| 1 | +18 (3 features × ~6 tests) | 18 | 855 |
| 2a | +5 | 23 | 860 |
| 2b | +12 (4 SDKs × ~3 tests) | 35 | 872 |
| 3 | +8 | 43 | 880 |
| 4a | +10 (8 + 2 conclusions rule) | 53 | 890 |
| 5 | +6 | 59 | 896 |
| 6 | +0 (already shipped) | 59 | 896 |

Final test count target: ~896 (+59 over baseline 837). CI budget: ~3-4 min today, target ≤5 min.

- **Detekt clean** on touched files per sprint. Match `core/events/` baseline.
- **Zero new test deps.**

## Caveats (exposed in spec + report)

- Sprint 2a SCREEN_TRANSITION emits NOTHING for single-activity engines (Unity/Unreal). Documented in spec ESC-SCRN-002 + caveat tooltip in report.
- Sprint 3 INSTRUMENTED requires the game to ALREADY emit the `GamePerf:I` tag. Without it, no events. Documented in README + report banner if no INSTRUMENTED events fire during the capture window when `metadata` suggests instrumentation was expected.
- Sprint 4a Quest VR detection relies on the Horizon OS runtime emitting `VrApi` stats logs. On Android XR (non-Quest), VrApi may NOT be emitted; Sprint 4b is needed.
- VR_RETURN_TRANSITION default window 5s is arbitrary; spec ESC-VR-003 documents the constant.
- Sprint 1 ANR detection uses passive logcat scraping of `am_anr` atom; if `ActivityManager` tag is filtered out elsewhere (it's NOT in the current `logcatTagArgs()` allowlist — Sprint 1 adds it), no events fire. Tag allowlist expansion documented in spec.
- Sprint 1 SDK_INIT 10s post-startup window is heuristic; if SDK init genuinely happens later (e.g. lazy IronSource init triggered by user action), it falls back to vendor default classification. Documented in spec ESC-INIT-002.

## Perf budget

- Logcat tag allowlist grows from current 19 unique tags to ~25 (Firebase, FA, ReviewManager, PlayCore, VrApi, XrPerformanceManager, GamePerf, ActivityManager). adb logcat `*:S` performance unaffected — tag whitelisting is the hot path on the device side.
- No new poll loops. SCREEN_TRANSITION, cold-start, VR silent-gap all piggyback on existing 1Hz dumpsys callback. ANR / SDK_INIT piggyback on logcat.
- Test suite grows ~7%. CI budget headroom OK.

## Migration / breaking

**INTERNAL ONLY.** `SdkSignature` is `internal`. `DetectedEvent.type` continues as `EventType` — public output contract unchanged. New EventType values are additive and deserialize cleanly with kotlinx.serialization defaults (existing `.gameperf` files load without modification).

NO external API changes. NO behavior change for already-detected interstitial/rewarded (Sprint 2b corrects the misclassification but consumers reading `DetectedEvent.type` may see REWARDED_VIDEO where INTERSTITIAL was emitted before — by design).

## Estimated effort

**7.0 days TDD strict** (Sprint 0 + 1 + 2a + 2b + 3 + 4a + 5; Sprint 4b deferred; Sprint 6 already shipped).

Per-sprint:

| Sprint | Effort | Critical-path |
|--------|--------|---------------|
| 0 | 1.0d | Refactor + 14 test migrations |
| 1 | 2.0d | 3 sub-features (cold-start, SDK init, ANR) |
| 2a | 0.5d | One detector branch |
| 2b | 1.0d | 4 SDKs × catalog + fixtures |
| 3 | 1.0d | Protocol + docs |
| 4a | 1.0d | Quest VR runtime + recovery rule |
| 5 | 0.5d | One SDK signature |
| 6 | 0d | Already shipped (commit `7116786`) |
| **Total** | **7.0d** | |

## Dependencies

- None on other SDD changes. `gpu-usage-percent` is independent.
- Catalog tag allowlist expansion (Sprint 1 adds `ActivityManager`, `Firebase`, `FA`; Sprint 4a adds `VrApi`, `XrPerformanceManager`; Sprint 5 adds `ReviewManager`, `PlayCore`; Sprint 3 adds `GamePerf`).

## Risks

| Risk | Likelihood | Severity | Mitigation |
|------|------------|----------|------------|
| Sprint 0 atomic refactor breaks existing 837 tests | Medium | High | Helper extraction; all 14 touch-points migrated in single PR; per-batch `./gradlew test`; staged commits. |
| Sprint 0 `defaultType` ambiguity (which type should activity-class path emit for SDKs with rewarded variants?) | Low | Medium | Decision: `defaultType = INTERSTITIAL` for AdMob/IS/AppLovin/Meta because activity-class hit alone can't discriminate; logcat patterns reclassify post-open if rewarded patterns fire within open's lifetime. Spec ESC-001 documents this. |
| Sprint 1 SDK_INIT 10s window misses lazy inits | Medium | Low | Fall back to vendor default; documented in spec; reports show inits-as-interstitial when this happens (not wrong per se, just less granular). |
| Sprint 1 ANR detector misses ANRs on devices that filter `ActivityManager` tag | Low | Medium | Tag allowlist expansion is in our control (`logcatTagArgs()`). Negative impact only on device-level OEM logcat filtering, which we can't fix. |
| Sprint 2a SCREEN_TRANSITION cap collisions with EVT-009 global cap | Low | Low | Per-type sub-cap 100 PLUS global 500 = safe ceiling. |
| Sprint 2b ProGuard-stripped rewarded patterns | Medium | Medium | Activity-class path remains for ProGuard builds (`defaultType = INTERSTITIAL` activity hit + post-open reclassify via any logcat pattern that does survive). Documented in spec. |
| Sprint 3 INSTRUMENTED tag collision with GameBench `gb_marker_*` games | Low | Low | Different tag (`GamePerf` vs none / `Log.d`). No conflict. |
| Sprint 3 game devs don't adopt instrumented protocol | High | Low (this is opt-in) | Acceptable. Document in README that this is opt-in. Don't penalize non-adopters. |
| Sprint 4a Quest VrApi stream changes format in future Horizon OS update | Low | Medium | Sprint 4a uses generic VrApi-tag-presence detection, not field parsing. Resilient to stat format changes. |
| Sprint 4a VR_RETURN_TRANSITION 5s window mis-tuned | Medium | Low | Constant in code; easy to adjust post-release. Spec scenario covers boundary conditions. |
| Sprint 4b deferred → user expects VR detection on non-Quest | Medium | Medium | Document Sprint 4a is QUEST-ONLY in README + report banner. Sprint 4b risk + mitigation in this proposal. |
| Sprint 5 RATE_US activity name changes in Play Core library | Low | Low | Add fallback patterns; document in spec; revisit on Play Core major release. |
| All Sprints — Engram CWD bug | Confirmed | Low | topic_keys are SoT; cross-project search documented. |
| Total test count growth crosses CI time budget | Low | Low | ~+59 tests, current CI ~3-4 min, target ≤5 min. Plenty of headroom. |

## Rollback plan

Sprint-level revert. Each sprint is a self-contained commit batch. Worst case: revert Sprint 0 = revert entire change (it's the foundation). Each post-Sprint-0 sprint is rollback-independent (single git revert of its commits). New EventType values defaulting to UNKNOWN if deserialization runs on older binaries (kotlinx.serialization default).

## Success criteria

- [ ] `./gradlew test` green at end of each sprint (8 green gates: 0, 1, 2a, 2b, 3, 4a, 5, 6-marker).
- [ ] Final test count ~896 (+59 over 837 baseline).
- [ ] Detekt clean per sprint on touched files.
- [ ] `docs/competitive-analysis-and-kpis.md` §4.2 updated (rows 1, 5, 7 → ✅).
- [ ] README + CHANGELOG entries for v4.5.x.
- [ ] Sprint 6 task marked `[x]` with reference to commit `7116786`.
- [ ] Engram saves: `sdd/event-segmentation-coverage/{explore,proposal,spec,design,tasks}`.
- [ ] No untracked changes to source files in this proposal step — strictly planning artifacts.
