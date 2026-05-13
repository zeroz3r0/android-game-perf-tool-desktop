# Spec: Event Segmentation Coverage

> NOTE: artifact belongs to project `android-game-perf-tool-desktop` (orchestrator CWD = `firebase-remote-config-sync`). Engram topic_key `sdd/event-segmentation-coverage/spec`.

This spec defines the NEW capability `event-segmentation` and the EXTENDED behavior of the existing `core` capability for event detection (EVT-001..EVT-010 in `openspec/specs/core/spec.md`). It covers 7 sprints (Sprint 6 already shipped):

- **Sprint 0** — `SdkSignature` refactor (BREAKING internal): `ESC-001..ESC-003`.
- **Sprint 1** — APP_STARTUP + SDK_INIT + ANR: `ESC-START-*`, `ESC-INIT-*`, `ESC-ANR-*`.
- **Sprint 2a** — SCREEN_TRANSITION: `ESC-SCRN-*`.
- **Sprint 2b** — Rewarded vs Interstitial split: `ESC-REW-*`.
- **Sprint 3** — INSTRUMENTED opt-in protocol: `ESC-INSTR-*`.
- **Sprint 4a** — VR_SESSION + VR_RETURN_TRANSITION (Quest): `ESC-VR-*`.
- **Sprint 5** — RATE_US: `ESC-RATE-*`.
- **Sprint 6** — LEVEL_LOADING already shipped: marked as `[x]` in tasks; covered by existing `LoadingSignaturesTest` (NO new requirement ID).

Conventions (match `openspec/specs/core/spec.md`):
- Requirement IDs are stable and code-referenceable. They map directly to test names.
- Requirement statements use EARS keywords (SHALL, MUST, WHEN, WHILE, WHERE, IF/THEN).
- Scenarios use Given/When/Then for testability. All scenarios MUST be implementable as pure-state-machine tests against `EventDetectorImpl` or pure catalog tests against `SdkSignatureCatalog`, using `LogLine` / `ActivityFrame` inputs OR `FakeAdbBridge.shellResponses`-driven bridge tests. No mocks. No new test deps.
- User-facing copy in scenarios is in Castilian Spanish formal **tuteo** per project convention (`CLAUDE.md`).

---

## ADDED Requirements

## 1. SdkSignature refactor (Sprint 0)

### Requirement: ESC-001 — Discriminated openPatterns shape

The system SHALL refactor `SdkSignature.openPatterns` from `List<Regex>` to `List<Pair<Regex, EventType>>`, AND SHALL replace the single field `type: EventType` with a `defaultType: EventType` used only for the activity-class detection path. The discriminator MUST be resolved at match time per logcat-driven open.

#### Scenario: AdMob signature exposes INTERSTITIAL pattern and REWARDED pattern in one entry

- GIVEN a `SdkSignature` entry for AdMob with `openPatterns = listOf(Regex("Showing ad") to INTERSTITIAL, Regex("onUserEarnedReward") to REWARDED_VIDEO)`
- WHEN `SdkSignatureCatalog.matchOpen(line)` is invoked with `tag="Ads"`, `msg="Showing ad now"`
- THEN the returned match MUST resolve to `EventType.INTERSTITIAL`
- AND when the same call is invoked with `msg="onUserEarnedReward amount=10"`
- THEN the returned match MUST resolve to `EventType.REWARDED_VIDEO`

#### Scenario: Activity-class path uses defaultType

- GIVEN a `SdkSignature` entry for AdMob with `defaultType=INTERSTITIAL` and `activityClasses=["com.google.android.gms.ads.AdActivity"]`
- WHEN `SdkSignatureCatalog.matchActivity("com.example/com.google.android.gms.ads.AdActivity")` is invoked
- THEN the returned signature MUST be the AdMob entry
- AND when `EventDetectorImpl.tryOpenActivity()` emits the event for that match
- THEN the emitted `DetectedEvent.type` MUST equal `defaultType` (INTERSTITIAL in this example)

### Requirement: ESC-002 — Backwards-compatible detector output

WHILE the internal `SdkSignature` shape changes, the public output `DetectedEvent.type: EventType` SHALL continue to be the only type field consumers read. Existing tests that assert on `event.type` MUST stay green after Sprint 0 with NO modification.

#### Scenario: Existing INTERSTITIAL detection test stays green

- GIVEN the existing `EventDetectorImplTest.\`open then close pairs an event with endMs set\``
- WHEN Sprint 0 refactor is applied
- THEN that test MUST pass without modification
- AND `events[0].type == EventType.INTERSTITIAL` MUST still hold

### Requirement: ESC-003 — All catalog entries migrate atomically

The Sprint 0 commit MUST migrate ALL 9 existing `SdkSignatureCatalog.ALL` entries to the new shape in a single commit. NO entry MAY remain with the deprecated `type` field. The catalog-level invariant test (`every SDK has at least one open and one close pattern`) MUST remain valid against the new shape (now asserting at least one `(Regex, EventType)` pair).

#### Scenario: Catalog test asserts new openPatterns shape

- GIVEN the migrated catalog
- WHEN `SdkSignatureCatalogTest.\`every SDK has at least one open and one close pattern\`` runs
- THEN `sig.openPatterns.size >= 1` MUST hold for every entry
- AND for every entry where `sig.activityClasses.isNotEmpty()` (i.e. NOT the engine LOADING entries), `sig.defaultType` MUST equal the type emitted by activity-class match
- AND for the engine LOADING entries (Unity Engine, Unreal Engine, Cocos2d), `sig.defaultType` MUST equal `EventType.LOADING` AND every `(Regex, EventType)` pair MUST also resolve to `EventType.LOADING`

---

## 2. APP_STARTUP detection (Sprint 1)

### Requirement: ESC-START-001 — Cold-start sensor via dumpsys

The system SHALL detect application cold start by observing the first occurrence of the game package in the dumpsys top-of-stack during the capture session. WHEN `lastGameForegroundMs == -1L` AND the new `top.cmp` begins with `"$gamePackage/"`, the detector MUST emit an `EventType.APP_STARTUP` event with `Confidence.MEDIUM`, `startMs = now`, `metadata = mapOf("source" to "dumpsys-firstforeground")`, AND seed `lastGameForegroundMs = now`.

#### Scenario: First foreground emits APP_STARTUP

- GIVEN a detector started at t=0 with `gamePackage="com.example.game"` and `lastGameForegroundMs=-1`
- WHEN `handleActivityStack([ActivityFrame("com.example.game/.MainActivity")])` is invoked at t=1000
- THEN exactly ONE event MUST be emitted with `type=APP_STARTUP`, `startMs=1000`, `confidence=MEDIUM`, `metadata["source"]=="dumpsys-firstforeground"`

#### Scenario: Subsequent foreground refreshes do not duplicate APP_STARTUP

- GIVEN APP_STARTUP already emitted at t=1000
- WHEN `handleActivityStack([ActivityFrame("com.example.game/.MainActivity")])` is invoked again at t=2000
- THEN NO new event MUST be emitted
- AND `lastGameForegroundMs` MUST advance to 2000

### Requirement: ESC-START-002 — APP_STARTUP endMs synthesis

The APP_STARTUP event SHALL close (set `endMs`) either:
- 10000 ms after `startMs`, OR
- When the first SDK_INIT event closes (whichever later), capped at 30000 ms post-startup.

WHEN neither close condition fires before session end, `stop()` MUST set `endInferred=true` per existing EVT-006 semantics.

#### Scenario: APP_STARTUP closes at 10s when no SDK_INIT fires

- GIVEN APP_STARTUP emitted at t=1000
- AND no SDK_INIT event emitted within the 10s window
- WHEN time advances to t=11000
- THEN the APP_STARTUP event MUST be closed with `endMs=11000` AND `endInferred=false`

### Requirement: ESC-START-003 — PID restart detection

The system SHALL detect mid-session PID changes for the game process. WHEN the game's `/proc/<pid>` directory becomes inaccessible and a new PID appears (process restart), the detector MUST emit a warning AND a new APP_STARTUP event with `metadata["restart"]="true"`. Duplicate APP_STARTUP events MUST be suppressed within a 10000 ms window.

#### Scenario: PID restart emits new APP_STARTUP with restart marker

- GIVEN APP_STARTUP emitted at t=1000 with game pid 1234
- WHEN at t=30000 the game process restarts (pid changes from 1234 to 1234 reappearing OR 5678)
- THEN a new APP_STARTUP event MUST be emitted with `startMs=30000`, `metadata["restart"]=="true"`
- AND a warning MUST be added: `"Reinicio del proceso detectado en t=30000ms — se emite un nuevo evento de inicio."`

#### Scenario: Rapid PID flicker is debounced

- GIVEN APP_STARTUP emitted at t=1000
- WHEN PID restart is detected at t=5000 (within 10s of last APP_STARTUP)
- THEN NO new APP_STARTUP event MUST be emitted
- AND the warning still surfaces noting the flicker was suppressed

---

## 3. SDK_INIT detection (Sprint 1)

### Requirement: ESC-INIT-001 — SDK_INIT signatures for six SDKs

The catalog SHALL include `EventType.SDK_INIT` open patterns for six SDKs: Firebase (tag `Firebase`), Google AppMeasurement (tag `FA`), AdMob (added to existing `Ads`/`MobileAds` tags), IronSource (added to existing `IronSource` tag), Unity Ads (added to existing `UnityAds`/`Unity` tag set), AppLovin (added to existing `AppLovinSdk` tag), Meta Audience (added to existing `FBAudienceNetworkLog` tag).

Each SDK SHALL contribute at least one `(Regex, SDK_INIT)` pair to its catalog entry's `openPatterns`. The regex MUST match canonical init phrases (e.g. `(?i)\binitializing\b`, `(?i)\bsdk\s+init\b`, `(?i)\binit\s+success\b`).

#### Scenario: Firebase init line matches SDK_INIT

- GIVEN a Firebase catalog entry with `openPatterns` including `Regex("(?i)\\bFirebaseApp initialization successful\\b") to SDK_INIT`
- WHEN `matchOpen(LogLine(tag="Firebase", msg="FirebaseApp initialization successful for [DEFAULT]"))` is invoked
- THEN the match MUST resolve to `EventType.SDK_INIT`

#### Scenario: AdMob init line within 10s post-startup classifies as SDK_INIT

- GIVEN APP_STARTUP emitted at t=1000 AND the detector tracks `lastAppStartupMs=1000`
- AND AdMob catalog `openPatterns` includes `Regex("(?i)\\bInitializing AdMob SDK\\b") to SDK_INIT`
- WHEN at t=4000 a line arrives with `tag="Ads"`, `msg="Initializing AdMob SDK"`
- THEN ONE event MUST be emitted with `type=SDK_INIT`, `sdkSource="AdMob"`, `confidence=HIGH`

### Requirement: ESC-INIT-002 — Post-startup window discriminator

WHEN a logcat line matches BOTH an SDK_INIT pattern AND would otherwise match the SDK's INTERSTITIAL/REWARDED_VIDEO patterns, the resolved EventType MUST be SDK_INIT only if `(now - lastAppStartupMs) <= 10000`. Outside that window the SDK_INIT pattern MUST NOT fire, AND the fallback INTERSTITIAL/REWARDED match takes effect.

#### Scenario: AdMob init pattern outside startup window does NOT fire

- GIVEN APP_STARTUP at t=1000, `lastAppStartupMs=1000`
- WHEN at t=15000 a line arrives matching `Initializing AdMob SDK` (`(15000 - 1000) > 10000`)
- THEN NO SDK_INIT event MUST be emitted
- AND the line MUST fall through to the next matching pattern (if any) in the AdMob entry

#### Scenario: Multiple SDK_INIT events fire independently per SDK

- GIVEN APP_STARTUP at t=1000
- WHEN at t=2000 a Firebase init line fires SDK_INIT
- AND at t=3000 an AdMob init line fires SDK_INIT
- THEN TWO separate SDK_INIT events MUST be emitted, each with its own `sdkSource`

### Requirement: ESC-INIT-003 — SDK_INIT close patterns

Each SDK_INIT-capable signature SHALL define close patterns indicating init completion (e.g. `(?i)\binitialization complete\b`, `(?i)\binit success\b`). WHEN no close pattern matches within 5000 ms of the init open, the event MUST close synthetically with `endMs = startMs + 5000` AND `endInferred=true`.

#### Scenario: SDK_INIT auto-closes after 5s when no close pattern fires

- GIVEN an SDK_INIT event emitted at t=2000
- WHEN time advances to t=7000 without a matching close pattern
- THEN the event MUST be closed with `endMs=7000`, `endInferred=true`

---

## 4. ANR detection (Sprint 1)

### Requirement: ESC-ANR-001 — Passive ANR detection via ActivityManager tag

The catalog SHALL include a `SdkSignature("System ANR", defaultType=ANR, logcatTags=["ActivityManager"], activityClasses=emptyList(), openPatterns=listOf(Regex("am_anr") to ANR), closePatterns=listOf(Regex("am_proc_died")))`. The detector MUST emit ANR events with `Confidence.HIGH`. The foreground proximity guard EVT-008 SHALL NOT reject ANR events — ANRs are expected to fire while the game is unresponsive but still on top.

#### Scenario: am_anr line emits ANR event regardless of foreground guard

- GIVEN a detector with `lastGameForegroundMs=-5000L` (game appears backgrounded 5s ago)
- WHEN `handleLogLine(LogLine(tag="ActivityManager", msg="am_anr ... Process com.example.game"))` is invoked at t=0
- THEN ONE event MUST be emitted with `type=ANR`, `sdkSource="System ANR"`, `confidence=HIGH`

#### Scenario: am_proc_died closes the open ANR

- GIVEN an ANR event open from t=0
- WHEN `handleLogLine(LogLine(tag="ActivityManager", msg="am_proc_died ... pid=1234"))` is invoked at t=5000
- THEN the ANR event MUST be closed with `endMs=5000`

### Requirement: ESC-ANR-002 — ANR severity is HIGH, NOT bundled with APP_STARTUP

The ANR EventType is a distinct value from APP_STARTUP. ANR events MUST NOT be downgraded by the EVT-007 logcat-gap-handler (gap → LOW confidence). ANR is a security-critical signal; even if a logcat gap occurred, the ANR observation timestamp from `am_anr` is authoritative.

#### Scenario: Logcat gap does NOT downgrade ANR confidence

- GIVEN an ANR event emitted with `confidence=HIGH`
- WHEN `handleGap(gapMs=10000)` is invoked
- THEN the ANR event's confidence MUST remain `HIGH`
- AND only non-ANR open events are downgraded to LOW per EVT-007

### Requirement: ESC-ANR-003 — Tag allowlist expansion

The `logcatTagArgs()` MUST include `ActivityManager:D` (added in Sprint 1) to permit `am_anr` lines through the `*:S` filter.

#### Scenario: logcatTagArgs includes ActivityManager

- GIVEN the migrated catalog with the ANR signature added
- WHEN `SdkSignatureCatalog.logcatTagArgs()` is invoked
- THEN the returned list MUST include `"ActivityManager:D"`

---

## 5. SCREEN_TRANSITION detection (Sprint 2a)

### Requirement: ESC-SCRN-001 — Cmp-change emission in handleActivityStack

WHEN `handleActivityStack` receives a non-empty frame list AND `top.cmp` differs from the previously tracked `lastTopCmp` AND `top.cmp.startsWith("$gamePackage/")` AND no SDK signature matches the activity, the detector MUST emit an `EventType.SCREEN_TRANSITION` event with `Confidence.MEDIUM`, `startMs=now`, `signatureMatched="screen:$lastTopCmp->$top.cmp"`, `metadata=mapOf("source" to "dumpsys-cmp-change", "from" to lastTopCmp, "to" to top.cmp)`. The previous open SCREEN_TRANSITION (if any) MUST be closed at `now`.

#### Scenario: Cmp change inside game package emits SCREEN_TRANSITION

- GIVEN a detector with `gamePackage="com.example.game"` and `lastTopCmp="com.example.game/.MainActivity"` from t=0
- WHEN at t=5000 `handleActivityStack([ActivityFrame("com.example.game/.SettingsActivity")])` is invoked
- THEN ONE SCREEN_TRANSITION event MUST be emitted with `startMs=5000`, `metadata["from"]="com.example.game/.MainActivity"`, `metadata["to"]="com.example.game/.SettingsActivity"`

#### Scenario: Sequential transitions close previous and open new

- GIVEN SCREEN_TRANSITION emitted at t=5000 (`MainActivity → SettingsActivity`)
- WHEN at t=10000 `handleActivityStack([ActivityFrame("com.example.game/.MainActivity")])` is invoked
- THEN the previous SCREEN_TRANSITION MUST close with `endMs=10000`
- AND a new SCREEN_TRANSITION MUST open with `startMs=10000`, `metadata["from"]="com.example.game/.SettingsActivity"`, `metadata["to"]="com.example.game/.MainActivity"`

### Requirement: ESC-SCRN-002 — Single-activity engines emit no SCREEN_TRANSITION

WHEN the game uses a single-activity architecture (Unity/Unreal default), the `top.cmp` never changes. The detector MUST emit ZERO SCREEN_TRANSITION events in this case. Documented as a known limitation in the report's caveat tooltip.

#### Scenario: No transitions emitted for single-activity Unity game

- GIVEN a detector tracking `gamePackage="com.example.unitygame"` and the only ever cmp is `com.example.unitygame/com.unity3d.player.UnityPlayerActivity`
- WHEN multiple dumpsys ticks fire with the same cmp
- THEN ZERO SCREEN_TRANSITION events MUST be emitted

### Requirement: ESC-SCRN-003 — Per-type sub-cap of 100 transitions

WHEN the count of `SCREEN_TRANSITION` events in the current session reaches 100, further SCREEN_TRANSITION opens MUST be dropped silently AND a warning added: `"Se alcanzó el tope de 100 cambios de pantalla — los siguientes se omiten para no inundar el reporte."`. The global EVT-009 cap of 500 still applies on top.

#### Scenario: 100 transitions emit cap warning

- GIVEN 100 SCREEN_TRANSITION events already emitted in session
- WHEN the 101st cmp change happens
- THEN NO new event MUST be emitted
- AND the warning `"Se alcanzó el tope de 100 cambios de pantalla..."` MUST be present in `warnings.value`

---

## 6. Rewarded vs Interstitial split (Sprint 2b)

### Requirement: ESC-REW-001 — Rewarded open patterns for four SDKs

Each of AdMob, IronSource, AppLovin, Meta Audience SHALL have additional `openPatterns` entries mapping to `EventType.REWARDED_VIDEO`:

- AdMob: `Regex("(?i)\\bonUserEarnedReward\\b") to REWARDED_VIDEO`, `Regex("(?i)\\bonRewardedAdLoaded\\b") to REWARDED_VIDEO`.
- IronSource: `Regex("(?i)\\brewardedVideoDidOpen\\b") to REWARDED_VIDEO`, `Regex("(?i)\\bonRewardedVideoAdShowSucceeded\\b") to REWARDED_VIDEO`.
- AppLovin: `Regex("(?i)\\bonRewardedVideoStarted\\b") to REWARDED_VIDEO`, `Regex("(?i)\\bonRewardedAdReceivedReward\\b") to REWARDED_VIDEO`.
- Meta Audience: `Regex("(?i)\\bonRewardedVideoCompleted\\b") to REWARDED_VIDEO`, `Regex("(?i)\\bonRewardedAdLoaded\\b") to REWARDED_VIDEO`.

The interstitial open patterns SHALL be retained unchanged.

#### Scenario: AdMob rewarded line classifies as REWARDED_VIDEO

- GIVEN the migrated AdMob entry with rewarded patterns added
- WHEN `matchOpen(LogLine(tag="Ads", msg="onUserEarnedReward type=coins amount=10"))` is invoked
- THEN the resolved EventType MUST be `REWARDED_VIDEO`

#### Scenario: AdMob interstitial line still classifies as INTERSTITIAL

- GIVEN the migrated AdMob entry
- WHEN `matchOpen(LogLine(tag="Ads", msg="Showing ad now"))` is invoked
- THEN the resolved EventType MUST be `INTERSTITIAL`

### Requirement: ESC-REW-002 — Activity-class path retains defaultType

WHEN an ad activity is detected via the dumpsys activity-class path (e.g. AdMob `AdActivity` appears on top of stack), the emitted EventType MUST be the signature's `defaultType` (INTERSTITIAL for AdMob/IS/AppLovin/Meta). The activity class alone does NOT discriminate between INTERSTITIAL and REWARDED_VIDEO. Subsequent matching logcat patterns within the open event's lifetime MAY reclassify the event by upgrading the type.

#### Scenario: AdActivity opens as INTERSTITIAL, then upgrades on rewarded pattern

- GIVEN AdMob `defaultType=INTERSTITIAL`
- WHEN `handleActivityStack` fires with `top.cmp="com.example/com.google.android.gms.ads.AdActivity"` at t=1000
- THEN an event is emitted with `type=INTERSTITIAL`
- AND when within the same event's lifetime a logcat line matches `onUserEarnedReward` at t=3000
- THEN the open event's `type` MUST be upgraded to `REWARDED_VIDEO`
- AND the upgrade MUST add metadata `metadata["upgradedFrom"]="INTERSTITIAL"`, `metadata["upgradedAtMs"]="3000"`

### Requirement: ESC-REW-003 — Per-SDK fixture coverage

Each of the four rewarded-extended SDKs MUST have a dedicated `*-rewarded.log` fixture under `src/test/resources/logcat-fixtures/`, and a fixture-driven smoke test asserting that the rewarded open AND close patterns match.

#### Scenario: admob-rewarded.log fixture produces a REWARDED_VIDEO open

- GIVEN fixture `logcat-fixtures/admob-rewarded.log`
- WHEN every line is parsed and run through `SdkSignatureCatalog.matchOpen`
- THEN at least one line MUST resolve to `EventType.REWARDED_VIDEO`
- AND the resolved `SdkSignature.sdk` MUST be `"AdMob"`

---

## 7. INSTRUMENTED opt-in protocol (Sprint 3)

> **STATUS: SUPERSEDED & ARCHIVED 2026-05-13.** Sprint 3 was implemented as a separate, stricter change `instrumented-event-mode` (archived at `openspec/archive/2026-05-13-instrumented-event-mode/`). Its delta requirements `IEM-001..IEM-008` REPLACE the original `ESC-INSTR-001..003` stubs below — the implemented behaviour adopts a FIXED 4-tag allowlist (`CINEMATIC`, `TUTORIAL`, `GAMEPLAY_DENSE`, `SPECIAL_EVENT`), CASE-SENSITIVE matching, and per-tag-keyed lifecycle (a `CINEMATIC.Stop` only closes its matching `CINEMATIC.Start`, never a sibling `TUTORIAL.Start`). The `name=` / `group=` parameter capture from `ESC-INSTR-002` was intentionally dropped in favour of the minimal protocol. See the archive folder for proposal/spec/design/tasks/apply-progress/verify-report.
>
> The three stubs below are retained for historical traceability only — they are NOT active requirements. Do NOT add tests against them; tests live under `IEM-001..IEM-008` (see archive `spec.md`).

### Requirement: ESC-INSTR-001 — GamePerf protocol tag (SUPERSEDED by IEM-001)

(Original wording archived. Replaced by IEM-001 — see `openspec/archive/2026-05-13-instrumented-event-mode/spec.md`.)

### Requirement: ESC-INSTR-002 — Phase name and group capture (SUPERSEDED — DROPPED)

(`name=` / `group=` parameter capture intentionally NOT carried forward. The minimal protocol uses only `{Tag}.Start` / `{Tag}.Stop` with the tag itself as the only payload, surfaced via `metadata["tag"]`. If richer payloads are needed later, file a new change.)

### Requirement: ESC-INSTR-003 — Tag allowlist includes GamePerf (SUPERSEDED by IEM-007)

(Replaced by IEM-007 — same behaviour, different ID for clean cross-reference with the archived change.)

---

## 8. VR_SESSION + VR_RETURN_TRANSITION (Sprint 4a — Quest only)

### Requirement: ESC-VR-001 — Quest VrApi-tag-presence detection

The catalog SHALL include `SdkSignature("Meta Quest VR", defaultType=VR_SESSION, logcatTags=["VrApi", "XrPerformanceManager"], activityClasses=emptyList(), openPatterns=listOf(Regex(".+") to VR_SESSION), closePatterns=emptyList())`. The open regex `".+"` matches any non-empty line on those tags — the VR session is identified by the PRESENCE of `VrApi` logcat traffic, not by any specific message content.

#### Scenario: First VrApi line opens VR_SESSION

- GIVEN a detector with foreground guard primed (game in foreground)
- AND no open VR_SESSION exists
- WHEN `handleLogLine(LogLine(tag="VrApi", msg="FPS=72 Prd=33ms Tear=0"))` is invoked at t=1000
- THEN ONE event MUST be emitted with `type=VR_SESSION`, `sdkSource="Meta Quest VR"`, `confidence=HIGH`, `startMs=1000`

#### Scenario: Same-tag duplicate VrApi lines do NOT duplicate VR_SESSION

- GIVEN a VR_SESSION already open from t=1000
- WHEN additional VrApi-tagged lines arrive at t=1100, t=1200, t=2000
- THEN NO new event MUST be emitted
- AND the detector MUST update an internal `lastVrApiLineMs` to the most recent VrApi line timestamp

### Requirement: ESC-VR-002 — Silent-gap close heuristic

The system MUST close the open VR_SESSION when no VrApi-tagged line has arrived for a configurable silent-gap window (default `VR_SESSION_SILENT_GAP_MS = 5000`). The close MUST be invoked during the dumpsys tick (every 1Hz check) — the detector compares `now - lastVrApiLineMs` against the window.

#### Scenario: VR_SESSION closes after 5s silent gap

- GIVEN VR_SESSION open from t=1000 with `lastVrApiLineMs=10000`
- WHEN the 1Hz dumpsys tick fires at t=16000 (`(16000 - 10000) >= 5000`)
- THEN the VR_SESSION MUST be closed with `endMs=16000`

#### Scenario: Boundary — exactly 5000ms gap closes session

- GIVEN VR_SESSION open with `lastVrApiLineMs=10000`
- WHEN the tick fires at t=15000
- THEN the VR_SESSION MUST be closed (inclusive boundary)

### Requirement: ESC-VR-003 — VR_RETURN_TRANSITION delayed emission

WHEN a VR_SESSION closes, the system MUST emit a separate `EventType.VR_RETURN_TRANSITION` event with `startMs = vrSessionCloseMs`, `endMs = vrSessionCloseMs + VR_RETURN_TRANSITION_WINDOW_MS` (default 5000), `confidence=MEDIUM`, `metadata=mapOf("source" to "vr-recovery-window")`. This event captures the post-VR thermal/RAM/GPU recovery window flagged by user as critical.

#### Scenario: VR session close emits VR_RETURN_TRANSITION

- GIVEN VR_SESSION closed at t=16000
- THEN immediately afterward ONE `VR_RETURN_TRANSITION` event MUST be emitted with `startMs=16000`, `endMs=21000`, `confidence=MEDIUM`

#### Scenario: VR_RETURN_TRANSITION is independent from VR_SESSION cap

- GIVEN VR_SESSION reaches its cap (rare; usually 1-2 per session) and is dropped per EVT-009
- THEN VR_RETURN_TRANSITION SHALL NOT be emitted for the dropped session

### Requirement: ESC-VR-004 — Tag allowlist includes VrApi and XrPerformanceManager

The `logcatTagArgs()` MUST include `VrApi:D` AND `XrPerformanceManager:D`.

#### Scenario: logcatTagArgs includes Quest VR tags

- GIVEN the migrated catalog with the Quest VR signature
- WHEN `logcatTagArgs()` is invoked
- THEN the returned list MUST include `"VrApi:D"` AND `"XrPerformanceManager:D"`

### Requirement: ESC-VR-005 — Quest-only scope documentation

The detection logic in Sprint 4a applies ONLY to Meta Quest devices running Horizon OS. The system MUST NOT claim to detect VR sessions on Android XR (non-Quest), generic OpenXR runtimes, Google Cardboard, or non-Quest Unreal XR. The report and README MUST document this scope.

#### Scenario: Documentation lists Quest-only support

- GIVEN the report HTML is generated
- WHEN a VR_SESSION event appears in the report
- THEN the rendering MUST include a caveat: `"Detección VR limitada a dispositivos Meta Quest (Horizon OS). Otros runtimes XR no están soportados en esta versión."`

---

## 9. RATE_US detection (Sprint 5)

### Requirement: ESC-RATE-001 — Google Play In-App Review signature

The catalog SHALL include `SdkSignature("Google Play In-App Review", defaultType=RATE_US, logcatTags=["ReviewManager", "PlayCore"], activityClasses=listOf("com.google.android.play.core.review.ReviewActivity"), openPatterns=listOf(Regex("(?i)\\blaunchReviewFlow\\b") to RATE_US, Regex("(?i)\\bReviewManager:.*\\binvoked\\b") to RATE_US), closePatterns=listOf(Regex("(?i)\\bonComplete\\b"), Regex("(?i)\\bReviewActivity\\s+destroyed\\b")))`.

#### Scenario: launchReviewFlow line opens RATE_US event

- GIVEN the Play In-App Review catalog entry registered
- WHEN `handleLogLine(LogLine(tag="PlayCore", msg="ReviewManager: launchReviewFlow invoked"))` is invoked at t=1000
- THEN ONE event MUST be emitted with `type=RATE_US`, `sdkSource="Google Play In-App Review"`, `confidence=HIGH`, `startMs=1000`

#### Scenario: ReviewActivity on top opens RATE_US via dumpsys path

- GIVEN no logcat line yet
- WHEN `handleActivityStack([ActivityFrame("com.example/com.google.android.play.core.review.ReviewActivity")])` is invoked at t=2000
- THEN ONE event MUST be emitted with `type=RATE_US`, `confidence=MEDIUM` (dumpsys-only)

### Requirement: ESC-RATE-002 — Close on onComplete or activity exit

WHEN a logcat line matches `(?i)\bonComplete\b` or the ReviewActivity leaves the dumpsys stack, the open RATE_US event MUST close.

#### Scenario: onComplete closes RATE_US

- GIVEN RATE_US open from t=1000
- WHEN `handleLogLine(LogLine(tag="PlayCore", msg="onComplete result=SUCCESS"))` is invoked at t=4000
- THEN the event MUST be closed with `endMs=4000`

### Requirement: ESC-RATE-003 — Tag allowlist includes ReviewManager and PlayCore

The `logcatTagArgs()` MUST include `ReviewManager:D` AND `PlayCore:D`.

#### Scenario: logcatTagArgs includes RATE_US tags

- WHEN `logcatTagArgs()` is invoked after Sprint 5
- THEN the returned list MUST include `"ReviewManager:D"` AND `"PlayCore:D"`

---

## 10. EventType enum extension

### Requirement: ESC-ENUM-001 — Seven new EventType values

`EventType` (file `core/events/DetectedEvent.kt`) MUST be extended with the following values, declared after the existing six AND before `UNKNOWN`:

```kotlin
APP_STARTUP,
SDK_INIT,
ANR,
SCREEN_TRANSITION,
INSTRUMENTED,
VR_SESSION,
VR_RETURN_TRANSITION,
RATE_US,
```

(That is 8 additions because RATE_US is also new — total enum size goes from 6 to 14.)

#### Scenario: Enum has 14 values

- GIVEN the migrated `EventType` enum
- WHEN `EventType.entries.size` is read
- THEN it MUST equal 14

#### Scenario: Existing values keep ordinals

- GIVEN the migrated `EventType` enum
- WHEN ordinals are inspected
- THEN `INTERSTITIAL.ordinal == 0`, `REWARDED_VIDEO.ordinal == 1`, `IAP.ordinal == 2`, `LOADING.ordinal == 3`, `FOREGROUND_LOSS.ordinal == 4` (i.e. existing ordinals preserved; new values appended; `UNKNOWN` may shift to the last position).

### Requirement: ESC-ENUM-002 — Backward-compatible deserialization

The serialization framework (kotlinx.serialization) MUST preserve compatibility with existing `.gameperf` session files. WHEN an older session file containing only the six original EventType values is deserialized, the load MUST succeed without modification. WHEN a new session containing new EventType values is loaded by an older binary, unknown enum values MUST deserialize as `EventType.UNKNOWN` (kotlinx.serialization default fallback OR explicit `@Serializer` configuration).

#### Scenario: Old .gameperf file deserializes after enum extension

- GIVEN a `.gameperf` file written under v4.4.x containing `EventType.INTERSTITIAL`, `EventType.LOADING`, `EventType.IAP`
- WHEN the file is loaded by the new binary
- THEN all events MUST deserialize correctly
- AND no exceptions MUST be thrown

---

## 11. Report rendering extension

### Requirement: ESC-REPORT-001 — Label and color for each new EventType

`ReportGenerator.kt` `when (event.type)` branches (label at L1225, color at L1233) MUST be extended with all 7 new values:

| EventType | Label (Spanish tuteo) | Color hex |
|-----------|-----------------------|-----------|
| APP_STARTUP | "Inicio" | `#10b981` |
| SDK_INIT | "Inicialización SDK" | `#22d3ee` |
| ANR | "App no responde (ANR)" | `#dc2626` |
| SCREEN_TRANSITION | "Cambio de pantalla" | `#0891b2` |
| INSTRUMENTED | "Marcador instrumentado" | `#a855f7` |
| VR_SESSION | "Sesión VR" | `#7c3aed` |
| VR_RETURN_TRANSITION | "Recuperación post-VR" | `#c4b5fd` |
| RATE_US | "Solicitud de valoración" | `#f59e0b` |

#### Scenario: Label rendering for SDK_INIT

- GIVEN a generated report HTML
- AND an `EventType.SDK_INIT` event exists in the session
- WHEN the report's events table is rendered
- THEN the label for that event row MUST equal `"Inicialización SDK"`

#### Scenario: Color rendering for VR_RETURN_TRANSITION

- GIVEN a VR_RETURN_TRANSITION event in the timeline
- WHEN the FPS chart band is rendered
- THEN the band color MUST equal `#c4b5fd`

### Requirement: ESC-REPORT-002 — VR caveat in report

WHEN at least one VR_SESSION event exists in the report, the report HTML MUST include a footnote: `"Detección VR limitada a dispositivos Meta Quest (Horizon OS) en esta versión. Otros runtimes XR no se detectan automáticamente."`.

#### Scenario: VR caveat present when VR_SESSION exists

- GIVEN a session with one VR_SESSION event
- WHEN the report is generated
- THEN the HTML MUST contain the Spanish tuteo-formal caveat above

### Requirement: ESC-REPORT-003 — INSTRUMENTED phase in event row

WHEN an INSTRUMENTED event is rendered, the event row MUST display `metadata["phase"]` and optionally `metadata["name"]` (e.g. `"Marcador instrumentado — CINEMATIC (intro_cutscene)"`).

#### Scenario: INSTRUMENTED row shows phase and name

- GIVEN an INSTRUMENTED event with `metadata["phase"]="CINEMATIC"` and `metadata["name"]="intro_cutscene"`
- WHEN the report row is rendered
- THEN the label MUST contain `"CINEMATIC"` AND `"intro_cutscene"`

---

## 12. Conclusions rules extension

### Requirement: ESC-CONCL-001 — PostVrRecoveryRule

A new `PostVrRecoveryRule` (in `core/conclusions/rules/`) SHALL emit a conclusion WHEN at least one VR_RETURN_TRANSITION event exists AND the temperature delta during that window exceeds a configurable threshold (default 2.0°C). The conclusion MUST be in Spanish tuteo-formal: `"Tras cerrar la sesión VR la temperatura sube X°C en los siguientes Y segundos — revisa el manejo de la transición de salida de VR."`

#### Scenario: VR_RETURN_TRANSITION + temp rise emits conclusion

- GIVEN a session with a VR_RETURN_TRANSITION from t=10000 to t=15000
- AND `tempCpuMaxC` rises by 3.0°C between t=10000 and t=15000
- WHEN `PostVrRecoveryRule.evaluate()` runs
- THEN ONE conclusion MUST be returned with text matching the Spanish template above

### Requirement: ESC-CONCL-002 — AnrSeverityRule

A new `AnrSeverityRule` SHALL emit a conclusion WHEN at least one `EventType.ANR` event exists in the session. The conclusion MUST be in Spanish tuteo-formal: `"Se detectó al menos una ANR (App No Responde) durante la sesión. Revisa los logs del proceso afectado en torno a t=Xms — Vitals penaliza apps con tasa de ANR ≥0.47% de DAU."`

#### Scenario: One ANR emits high-severity conclusion

- GIVEN a session with one ANR event at t=5000
- WHEN `AnrSeverityRule.evaluate()` runs
- THEN ONE conclusion MUST be returned with text matching the template

#### Scenario: Multiple ANRs aggregate in one conclusion

- GIVEN a session with three ANR events
- WHEN `AnrSeverityRule.evaluate()` runs
- THEN ONE conclusion MUST be returned listing each ANR timestamp (e.g. `"... t=5000ms, t=12000ms, t=18000ms"`)

---

## 13. Catalog-level invariants extension

### Requirement: ESC-CATALOG-001 — Updated catalog size assertion

After all Sprints (0-5), `SdkSignatureCatalog.ALL.size` MUST equal the new total (9 existing + 8 new entries = 17 entries). The test `catalog contains exactly the seventeen catalogued SDKs and engines` MUST replace the existing 9-count assertion.

Note: the 8 new entries are: 6 SDK_INIT entries collapse into existing 6 ad/billing entries (extending their openPatterns) + 1 Firebase init entry + 1 AppMeasurement init entry + 1 System ANR + 1 GamePerf instrumented + 1 Meta Quest VR + 1 Google Play In-App Review.

That is:
- Existing 6 ad/billing entries (AdMob, Unity Ads, IronSource, AppLovin, Meta Audience, Google Play Billing) STAY at count 6 — their openPatterns just grow.
- Existing 3 engine entries (Unity Engine, Unreal Engine, Cocos2d) STAY at count 3.
- NEW 5 entries: Firebase, AppMeasurement (FA), System ANR, GamePerf, Meta Quest VR, Google Play In-App Review.

**Final catalog size: 9 + 5 = 14 entries.** (The 6 SDK_INIT additions to existing entries do NOT add to ALL.size; they extend existing entries' patterns.)

#### Scenario: Catalog size invariant updated

- GIVEN the fully migrated catalog after all Sprints 0-5
- WHEN `SdkSignatureCatalog.ALL.size` is read
- THEN it MUST equal 14

#### Scenario: Catalog SDK name set updated

- GIVEN the fully migrated catalog
- WHEN SDK names are collected
- THEN the set MUST equal: `{"AdMob", "Unity Ads", "IronSource", "AppLovin", "Meta Audience Network", "Google Play Billing", "Unity Engine", "Unreal Engine", "Cocos2d", "Firebase", "Google Analytics for Firebase", "System ANR", "GamePerf", "Meta Quest VR", "Google Play In-App Review"}`

(Note: AppMeasurement may be folded under Firebase as "Google Analytics for Firebase" sharing the `Firebase` and `FA` tags; final naming TBD in Sprint 1 design step.)

### Requirement: ESC-CATALOG-002 — Tag uniqueness preserved

After all sprints, `logcatTagArgs()` MUST end with `"*:S"`, MUST contain all unique tags exactly once, AND MUST contain at least these new tags: `Firebase`, `FA`, `ActivityManager`, `GamePerf`, `VrApi`, `XrPerformanceManager`, `ReviewManager`, `PlayCore`. Tag uniqueness invariant preserved (no duplicates).

#### Scenario: New tags added to logcatTagArgs

- WHEN `logcatTagArgs()` is invoked after Sprint 5
- THEN the returned list MUST contain every tag named above (each appearing exactly once with `:D` suffix)
- AND the list MUST terminate with `"*:S"`
