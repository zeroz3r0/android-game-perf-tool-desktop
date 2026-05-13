# Tasks: event-segmentation-coverage

> NOTE: belongs to project `android-game-perf-tool-desktop` (orchestrator CWD = `firebase-remote-config-sync`). Engram topic_key `sdd/event-segmentation-coverage/tasks`.

Strict TDD red→green. Runner: `./gradlew test`. Each batch ends green. Each task atomic (<2min reviewer check). Spec refs `ESC-XXX`. Design refs `§N`.

**Baseline test count: 837** (verified post commit `7116786`). Final target: ~900 (+~63 over baseline; design est. 70-80 max).

**Sprint DAG (hard order)**:
```
Sprint 0  ─┬─► Sprint 2b (rewarded split — needs the new openPatterns shape)
           ├─► Sprint 1 (APP_STARTUP / SDK_INIT / ANR — needs new shape for SDK_INIT)
           ├─► Sprint 5 (RATE_US — uses new shape for clean entry)
           ├─► Sprint 3 (INSTRUMENTED — uses new shape)
           ├─► Sprint 4a (VR — uses new shape)
           └─► Sprint 2a (SCREEN_TRANSITION — independent of new shape, but ordered after Sprint 0 to keep refactor atomic)
```

Sprints 1, 2a, 2b, 3, 4a, 5 are mutually independent after Sprint 0 lands. They CAN ship in any order. Below is one valid linear order (1 → 2a → 2b → 3 → 4a → 5).

**Sprint 6 — LEVEL_LOADING wire-up — ALREADY SHIPPED in commit `7116786` (LOADING signatures for Unity/Unreal/Cocos2d). Marked `[x]` below for traceability; NO code work in this change.**

---

## Sprint 0 — SdkSignature refactor (BREAKING internal)

**Estimated effort: 1.0d. Critical path: helper extraction + 14 test method migrations.**

### Batch 0.1 — Add `MatchResult` data class + extend `EventType` enum (foundation, no behavior change)

- [ ] 0.1.1 RED: write a failing test in NEW `core/events/SdkSignaturePatternsTest.kt` asserting `SdkSignatureCatalog.matchOpen(line)` returns a `MatchResult(sig, pattern, resolvedType)` triple (not `Pair<SdkSignature, Regex>`). Test fails because `MatchResult` doesn't exist. [ESC-001]
- [ ] 0.1.2 GREEN: create `core/events/MatchResult.kt` with `internal data class MatchResult(val sig: SdkSignature, val pattern: Regex, val resolvedType: EventType)`.
- [ ] 0.1.3 Run `./gradlew test --tests "*SdkSignaturePatternsTest*"` → expect still RED (matchOpen still returns Pair).
- [ ] 0.1.4 RED: extend `core/events/DetectedEvent.kt`: add 7 new `EventType` values BEFORE `UNKNOWN` (APP_STARTUP, SDK_INIT, ANR, SCREEN_TRANSITION, INSTRUMENTED, VR_SESSION, VR_RETURN_TRANSITION, RATE_US). Run full test → some tests may fail on exhaustive `when` branches. [ESC-ENUM-001]
- [ ] 0.1.5 GREEN: extend `ReportGenerator.kt` `when (event.type)` branches (L1225 label + L1233 color) with the 7 new values per §2.8. Use `else ->` only as last resort — exhaustive preferred.
- [ ] 0.1.6 GREEN: extend `LoadingThermalRecoveryRule.kt:37` — no `when` here, just adds nothing if non-LOADING events are filtered already. Confirm via test run.
- [ ] 0.1.7 RED: write test in `SdkSignatureCatalogTest.kt` ` `EventType enum has 14 entries\`` asserting `EventType.entries.size == 14`. Run.
- [ ] 0.1.8 GREEN: implement the assertion (already covered by 0.1.4).
- [ ] 0.1.9 Run `./gradlew test` → full suite green. Commit: `feat(events): expand EventType + introduce MatchResult shell`.

### Batch 0.2 — Refactor SdkSignature shape

- [ ] 0.2.1 RED: write test in `SdkSignaturePatternsTest.kt` asserting `SdkSignature` exposes `defaultType: EventType` and `openPatterns: List<Pair<Regex, EventType>>`. Run → compile fails (still old shape).
- [ ] 0.2.2 GREEN: refactor `core/events/SdkSignature.kt` per §2.2 — remove `type: EventType`, add `defaultType: EventType` + change `openPatterns` to `List<Pair<Regex, EventType>>`.
- [ ] 0.2.3 GREEN: update `core/events/SdkSignatureCatalog.kt` — migrate ALL 9 existing entries to the new shape:
  - For each entry, replace `type = X` with `defaultType = X`.
  - Replace `openPatterns = listOf(Regex(...), Regex(...))` with `openPatterns = listOf(Regex(...) to X, Regex(...) to X)` where X is the entry's existing type.
  - This is a mechanical migration; no semantic change yet.
- [ ] 0.2.4 GREEN: refactor `SdkSignatureCatalog.matchOpen(line)` to return `MatchResult?` per §1: iterate `sig.openPatterns`, match by `(pattern, type)`, return the FIRST match wrapped as `MatchResult(sig, pattern, type)`. [ESC-001]
- [ ] 0.2.5 GREEN: update `core/events/EventDetectorImpl.kt`:
  - Line 142 area: `val openMatch = SdkSignatureCatalog.matchOpen(line)` — destructure new shape: `if (openMatch != null) { val (sig, pattern, resolvedType) = openMatch; tryOpen(sig, pattern.pattern, line.tsMs, line.tag, resolvedType, "logcat") }`.
  - Line 242 area: `tryOpen` signature gains `resolvedType: EventType`; `type = resolvedType` instead of `type = sig.type`.
  - Line 263 area: `tryOpenActivity` already uses `sig.type` semantics → change to `sig.defaultType`.
- [ ] 0.2.6 Run `./gradlew test --tests "*Catalog*" --tests "*Detector*" --tests "*LoadingSignatures*"` → many tests will RED on `matched.first.type` reads. Migrate test code:
  - `SdkSignatureCatalogTest.kt`:
    - L100, L126, L227: `matched.first.type` → `matched.resolvedType`. Migrate 3 sites.
    - L68: `sig.type != EventType.LOADING` → `sig.defaultType != EventType.LOADING`. Migrate 1 site.
    - Fixture loop helper `assertFixtureProducesOpenAndClose`: uses `matched.first.type` indirectly via `expectedSdk`; no change needed.
  - `LoadingSignaturesTest.kt`:
    - L37, L83, L115, L170, L199: `matched.first.type` → `matched.resolvedType`. Migrate 5 sites.
  - `EventDetectorImplTest.kt`: reads `events[0].type` (= `DetectedEvent.type`); UNCHANGED. 4 sites stay green.
- [ ] 0.2.7 Run `./gradlew test` → full 837 tests green. The refactor is byte-equivalent in behavior; only shapes changed.
- [ ] 0.2.8 RED: write test `\`matchOpen for AdMob with both INTERSTITIAL and SDK_INIT patterns resolves correctly\`` in `SdkSignaturePatternsTest.kt`. Build a synthetic AdMob signature with `openPatterns = listOf(Regex("Initializing AdMob SDK") to SDK_INIT, Regex("Showing ad") to INTERSTITIAL)`. Assert match on `"Initializing AdMob SDK"` resolves to `SDK_INIT` and match on `"Showing ad"` resolves to `INTERSTITIAL`. [ESC-001]
- [ ] 0.2.9 GREEN: assertion already satisfied by 0.2.4 implementation (first-match wins, types resolve from the matched pair).
- [ ] 0.2.10 Run `./gradlew test --tests "*SdkSignaturePatterns*"` → green.
- [ ] 0.2.11 Batch-end gate: `./gradlew test` full suite green (837 tests, same as baseline). Commit: `refactor(events): SdkSignature openPatterns now carry EventType per pattern`.

### Sprint 0 closure

- [ ] 0.3.1 Engram update: save `sdd/event-segmentation-coverage/apply-progress` observation with Sprint 0 status, files touched, test counts.
- [ ] 0.3.2 `./gradlew detekt` → clean on touched files (`SdkSignature.kt`, `SdkSignatureCatalog.kt`, `EventDetectorImpl.kt`, `MatchResult.kt`, `DetectedEvent.kt`, `ReportGenerator.kt`). Pre-existing warnings unchanged.
- [ ] 0.3.3 ✅ Sprint 0 done. Final test count: 837 (unchanged). Ready for Sprint 1.

---

## Sprint 1 — APP_STARTUP + SDK_INIT + ANR

**Estimated effort: 2.0d. Three sub-features, three batches.**

### Batch 1.1 — APP_STARTUP cold-start sensor + PID restart

- [ ] 1.1.1 RED: create `core/events/AppStartupDetectorTest.kt`. Test `\`first foreground emits APP_STARTUP\``: detector with `lastGameForegroundMs=-1`, fire `handleActivityStack` with game cmp at t=1000 → expect 1 event `type=APP_STARTUP, startMs=1000, confidence=MEDIUM, metadata["source"]=="dumpsys-firstforeground"`. [ESC-START-001]
- [ ] 1.1.2 GREEN: modify `EventDetectorImpl.handleActivityStack` — at top of function, after deriving `top` and `now`, add cold-start check: `if (lastGameForegroundMs == -1L && top.cmp.startsWith("$gamePackage/")) { emitAppStartup(now) }`. Implement `emitAppStartup(now: Long, restart: Boolean = false)` private method.
- [ ] 1.1.3 RED: test `\`subsequent foreground refreshes do not duplicate APP_STARTUP\``. [ESC-START-001 scenario 2]
- [ ] 1.1.4 GREEN: ensure `lastGameForegroundMs` is set AFTER emitAppStartup so the `== -1L` guard prevents re-emission.
- [ ] 1.1.5 RED: test `\`APP_STARTUP closes at 10s when no SDK_INIT fires\``. Add advancing-clock helper. After APP_STARTUP at t=1000, advance to t=11000 → assert event closed. [ESC-START-002]
- [ ] 1.1.6 GREEN: add `checkAppStartupAutoClose(now)` called from `handleActivityStack`. Auto-closes after `APP_STARTUP_CAP_MS` (10_000ms) or when first SDK_INIT closes (whichever later, capped at 30s = `APP_STARTUP_HARD_CAP_MS`).
- [ ] 1.1.7 RED: test `\`PID restart emits new APP_STARTUP with restart marker\``. Drive `checkPidRestart(currentPid)` API with PID change. [ESC-START-003]
- [ ] 1.1.8 GREEN: add `checkPidRestart(currentPid: Int?)` to `EventDetectorImpl`. Maintain `lastGamePid` field. On mismatch + outside 10s window, emit new APP_STARTUP with `metadata["restart"]="true"`.
- [ ] 1.1.9 RED: test `\`Rapid PID flicker is debounced\``. PID change within 10s of last APP_STARTUP → no new event. [ESC-START-003 scenario 2]
- [ ] 1.1.10 GREEN: guard `checkPidRestart` with `(now - lastAppStartupMs) <= APP_STARTUP_DEBOUNCE_MS` check.
- [ ] 1.1.11 Add fixture `src/test/resources/logcat-fixtures/app-startup-cold.log` (40 lines: `am_proc_start`, game foreground, Firebase init, gameplay begins).
- [ ] 1.1.12 Run `./gradlew test --tests "*AppStartup*"` → green (5 tests). Run full suite → green (~842 tests).
- [ ] 1.1.13 Commit: `feat(events): APP_STARTUP detection via dumpsys + PID restart watcher`.

### Batch 1.2 — SDK_INIT 6 signatures + 10s window gate

- [ ] 1.2.1 RED: create `core/events/SdkInitGateTest.kt`. Test `\`Firebase init line matches SDK_INIT\``: build line with `tag="Firebase", msg="FirebaseApp initialization successful"`. Drive `EventDetectorImpl.handleLogLine` after APP_STARTUP at t=1000, at t=2000 → expect 1 SDK_INIT event. [ESC-INIT-001]
- [ ] 1.2.2 GREEN: extend `SdkSignatureCatalog.ALL` with Firebase entry per §2.4 (`logcatTags=["Firebase", "FirebaseApp"]`, openPatterns SDK_INIT-only).
- [ ] 1.2.3 GREEN: extend `logcatTagArgs()` indirectly — derived from ALL entries. Verify test `\`logcatTagArgs ends with star colon S and contains all unique tags\`` still green.
- [ ] 1.2.4 RED: test `\`AppMeasurement init line matches SDK_INIT\`` with `tag="FA", msg="App measurement init"`.
- [ ] 1.2.5 GREEN: extend `ALL` with Google Analytics for Firebase entry (logcatTags=["FA", "FirebaseAnalytics"]).
- [ ] 1.2.6 RED: test `\`AdMob init line within 10s post-startup classifies as SDK_INIT\``. After APP_STARTUP at t=1000, line at t=4000 with `Initializing AdMob SDK` → SDK_INIT (NOT INTERSTITIAL). [ESC-INIT-001 scenario 2]
- [ ] 1.2.7 GREEN: add SDK_INIT openPatterns to existing AdMob entry (per §2.4 code block). Add post-startup gate in `EventDetectorImpl.handleLogLine` per §3.1:
  - After `matchOpen` returns a `MatchResult` with `resolvedType=SDK_INIT`, check `(now - lastAppStartupMs) <= SDK_INIT_WINDOW_MS` (10_000ms).
  - If outside window, fall through to the next non-SDK_INIT pattern in `sig.openPatterns` (linear scan; first match wins). If no fallback, drop.
- [ ] 1.2.8 RED: test `\`AdMob init pattern outside startup window does NOT fire\``. APP_STARTUP at t=1000, line at t=15000 matching init → expect NO SDK_INIT event AND no INTERSTITIAL fallback if message ONLY matches init pattern. [ESC-INIT-002]
- [ ] 1.2.9 GREEN: gate logic from 1.2.7 already handles this. Verify.
- [ ] 1.2.10 RED: test `\`Multiple SDK_INIT events fire independently per SDK\``: APP_STARTUP at t=1000; Firebase init at t=2000; AdMob init at t=3000 → expect 2 separate SDK_INIT events. [ESC-INIT-002 scenario 2]
- [ ] 1.2.11 GREEN: independent emission already holds via separate signatures.
- [ ] 1.2.12 GREEN: extend Unity Ads, IronSource, AppLovin, Meta Audience entries with their respective SDK_INIT patterns (4 entries × 1-2 patterns each). All inside existing logcatTags.
- [ ] 1.2.13 RED: test `\`SDK_INIT auto-closes after 5s when no close pattern fires\``. Open SDK_INIT at t=2000, advance to t=7000 → assert closed with `endInferred=true`. [ESC-INIT-003]
- [ ] 1.2.14 GREEN: add `SDK_INIT_AUTOCLOSE_MS = 5_000L` companion + per-tick check in `checkVrSilentGap`-equivalent helper `checkSdkInitAutoClose(now)`. Call from `handleActivityStack` (1Hz tick).
- [ ] 1.2.15 Add fixture `sdk-init-firebase.log` (30 lines: Firebase init + AppMeasurement init + AdMob init within 10s).
- [ ] 1.2.16 RED: fixture-driven test `\`sdk-init-firebase.log produces three SDK_INIT events\``.
- [ ] 1.2.17 GREEN: verify via fixture run.
- [ ] 1.2.18 Run `./gradlew test --tests "*SdkInit*"` → green (5 tests). Full suite green (~847 tests).
- [ ] 1.2.19 Commit: `feat(events): SDK_INIT detection for 6 SDKs with 10s post-startup window gate`.

### Batch 1.3 — ANR passive detection

- [ ] 1.3.1 RED: create `core/events/AnrDetectorTest.kt`. Test `\`am_anr line emits ANR event regardless of foreground guard\``: detector with `lastGameForegroundMs=-5000L` (game backgrounded 5s ago), fire `handleLogLine` with `tag="ActivityManager", msg="am_anr ... Process com.example.game"` → expect 1 ANR event with `confidence=HIGH`. [ESC-ANR-001]
- [ ] 1.3.2 GREEN: extend `SdkSignatureCatalog.ALL` with "System ANR" entry per §2.4. Verify `logcatTags=["ActivityManager"]`.
- [ ] 1.3.3 GREEN: update `EventDetectorImpl.tryOpen` to skip the EVT-008 foreground guard when `resolvedType == EventType.ANR`. Conditional bypass. [ESC-ANR-001]
- [ ] 1.3.4 RED: test `\`am_proc_died closes the open ANR\``: ANR opened at t=0, close line at t=5000 → expect closed with endMs=5000. [ESC-ANR-001 scenario 2]
- [ ] 1.3.5 GREEN: close pattern `am_proc_died` already in catalog; standard close path handles it.
- [ ] 1.3.6 RED: test `\`Logcat gap does NOT downgrade ANR confidence\``: ANR open with `confidence=HIGH`, fire `handleGap(10000)` → assert ANR confidence stays HIGH. [ESC-ANR-002]
- [ ] 1.3.7 GREEN: update `handleGap` to filter out ANR events from the downgrade list.
- [ ] 1.3.8 RED: test `\`logcatTagArgs includes ActivityManager\``. [ESC-ANR-003]
- [ ] 1.3.9 GREEN: implicit — ActivityManager tag is in System ANR signature's `logcatTags`, surfaces via `flatMap`.
- [ ] 1.3.10 Add fixture `anr-game.log` (20 lines: `am_anr ... Process com.example.game`, followed by `am_proc_died`).
- [ ] 1.3.11 RED: create `core/conclusions/rules/AnrSeverityRuleTest.kt`. Test 1: 1 ANR → 1 conclusion. Test 2: 3 ANRs → 1 conclusion listing all timestamps. Test 3: 0 ANRs → 0 conclusions. [ESC-CONCL-002]
- [ ] 1.3.12 GREEN: create `core/conclusions/rules/AnrSeverityRule.kt` per §2.7.
- [ ] 1.3.13 GREEN: wire `AnrSeverityRule` into the conclusions engine. Verify spec Spanish text matches scenario assertion exactly.
- [ ] 1.3.14 Run `./gradlew test --tests "*Anr*"` → green (4 detector + 3 rule = 7 tests). Full suite green (~854 tests).
- [ ] 1.3.15 Commit: `feat(events): passive ANR detection via ActivityManager + severity rule`.

### Sprint 1 closure

- [ ] 1.4.1 Engram update: `sdd/event-segmentation-coverage/apply-progress` Sprint 1 done.
- [ ] 1.4.2 `./gradlew detekt` on touched files → clean.
- [ ] 1.4.3 Full suite test count: ~854. ✅ Sprint 1 done.

---

## Sprint 2a — SCREEN_TRANSITION

**Estimated effort: 0.5d. One batch.**

### Batch 2a.1 — SCREEN_TRANSITION emission

- [ ] 2a.1.1 RED: create `core/events/ScreenTransitionTest.kt`. Test `\`Cmp change inside game package emits SCREEN_TRANSITION\``: gamePackage=`com.example.game`, lastTopCmp=MainActivity from t=0; at t=5000 dumpsys returns SettingsActivity → expect SCREEN_TRANSITION event with metadata `from=MainActivity, to=SettingsActivity`. [ESC-SCRN-001]
- [ ] 2a.1.2 GREEN: modify `EventDetectorImpl.handleActivityStack` per §3.2:
  - Add `private var lastTopCmp: String? = null`.
  - In the `elif top.cmp.startsWith("$gamePackage/")` branch (line ~176-184), check if `lastTopCmp != null && lastTopCmp != top.cmp`:
    - Close any open SCREEN_TRANSITION at `now`.
    - Emit new SCREEN_TRANSITION with `startMs=now, confidence=MEDIUM, metadata=mapOf("source" to "dumpsys-cmp-change", "from" to lastTopCmp!!, "to" to top.cmp)`.
  - Always `lastTopCmp = top.cmp` after the branch.
- [ ] 2a.1.3 RED: test `\`Sequential transitions close previous and open new\``: transition 1 at t=5000, transition 2 at t=10000 → assert first closed with endMs=10000, second open. [ESC-SCRN-001 scenario 2]
- [ ] 2a.1.4 GREEN: confirm logic from 2a.1.2 handles this.
- [ ] 2a.1.5 RED: test `\`No transitions emitted for single-activity Unity game\``: cmp stays at UnityPlayerActivity across 5 dumpsys ticks → assert 0 SCREEN_TRANSITION events. [ESC-SCRN-002]
- [ ] 2a.1.6 GREEN: guard `lastTopCmp != top.cmp` handles this naturally.
- [ ] 2a.1.7 RED: test `\`100 transitions emit cap warning\``: emit 100 transitions via repeated cmp toggling, attempt 101st → assert no new event AND warning present. [ESC-SCRN-003]
- [ ] 2a.1.8 GREEN: add `screenTransitionCount(): Int` helper + cap check (`MAX_SCREEN_TRANSITIONS = 100`) + `ensureWarning("Se alcanzó el tope de 100 cambios de pantalla — los siguientes se omiten para no inundar el reporte.")`.
- [ ] 2a.1.9 RED: test cross-collision: SCREEN_TRANSITION must NOT fire when cmp matches an SDK activity (e.g. game switches to AdActivity). [ESC-SCRN-001 condition "AND no SDK signature matches"]
- [ ] 2a.1.10 GREEN: confirm: the SCREEN_TRANSITION branch is in the `elif`, AFTER `sig = catalog.matchActivity(top.cmp); if (sig != null)`. So SDK activity precedence is preserved.
- [ ] 2a.1.11 Run `./gradlew test --tests "*ScreenTransition*"` → green (5 tests). Full suite green (~859 tests).
- [ ] 2a.1.12 Commit: `feat(events): SCREEN_TRANSITION detection on dumpsys cmp change`.

### Sprint 2a closure

- [ ] 2a.2.1 Engram update.
- [ ] 2a.2.2 Detekt clean.
- [ ] 2a.2.3 ✅ Sprint 2a done. Test count ~859.

---

## Sprint 2b — Rewarded vs Interstitial split

**Estimated effort: 1.0d. Four SDKs × catalog + fixtures + upgrade flow.**

### Batch 2b.1 — Rewarded openPatterns per SDK

- [ ] 2b.1.1 RED: create `core/events/RewardedSignaturesTest.kt`. Test `\`AdMob rewarded line classifies as REWARDED_VIDEO\``: line with `tag="Ads", msg="onUserEarnedReward type=coins"` → matchOpen resolves to REWARDED_VIDEO. [ESC-REW-001]
- [ ] 2b.1.2 GREEN: extend AdMob catalog entry with rewarded openPatterns per §2.4:
  - `Regex("""(?i)\\bonUserEarnedReward\\b""") to REWARDED_VIDEO`
  - `Regex("""(?i)\\bonRewardedAdLoaded\\b""") to REWARDED_VIDEO`
- [ ] 2b.1.3 RED: test `\`AdMob interstitial line still classifies as INTERSTITIAL\``. [ESC-REW-001 scenario 2]
- [ ] 2b.1.4 GREEN: order in openPatterns matters — INTERSTITIAL patterns retained after REWARDED. First-match wins; per §5.3 the REWARDED match comes first because `onUserEarnedReward` is more specific than `Showing ad`.
- [ ] 2b.1.5 Repeat 2b.1.1 → 2b.1.4 for IronSource:
  - `Regex("""(?i)\\brewardedVideoDidOpen\\b""") to REWARDED_VIDEO`
  - `Regex("""(?i)\\bonRewardedVideoAdShowSucceeded\\b""") to REWARDED_VIDEO`
- [ ] 2b.1.6 Repeat for AppLovin:
  - `Regex("""(?i)\\bonRewardedVideoStarted\\b""") to REWARDED_VIDEO`
  - `Regex("""(?i)\\bonRewardedAdReceivedReward\\b""") to REWARDED_VIDEO`
- [ ] 2b.1.7 Repeat for Meta Audience:
  - `Regex("""(?i)\\bonRewardedVideoCompleted\\b""") to REWARDED_VIDEO`
  - `Regex("""(?i)\\bonRewardedAdLoaded\\b""") to REWARDED_VIDEO`
- [ ] 2b.1.8 Run `./gradlew test --tests "*RewardedSignatures*"` → green (8 tests: 4 SDKs × open + interstitial-still-works).

### Batch 2b.2 — Activity-class path upgrade flow

- [ ] 2b.2.1 RED: test `\`AdActivity opens as INTERSTITIAL, then upgrades on rewarded pattern\``: dumpsys at t=1000 fires AdActivity → INTERSTITIAL event; logcat at t=3000 fires `onUserEarnedReward` on same open event's `sdkSource="AdMob"` window → assert event type upgrades to REWARDED_VIDEO with metadata `upgradedFrom=INTERSTITIAL, upgradedAtMs=3000`. [ESC-REW-002]
- [ ] 2b.2.2 GREEN: implement `upgradeEventType()` in `EventDetectorImpl` per §3.3:
  - In `handleLogLine`'s close-pattern loop, AFTER the close check, IF the line matches a REWARDED openPattern of the same SDK AND the open event's type == INTERSTITIAL → upgrade event in-place.
  - Use `events.value` replace-by-id pattern (already used in `tryClose`).
  - Update `openEvents` map key if the key encodes the type (it doesn't — keys are `"$sdk:$tag:$signature"` — no key change needed).
- [ ] 2b.2.3 Run `./gradlew test --tests "*RewardedSignatures*"` → green.
- [ ] 2b.2.4 RED: test that already-REWARDED events do NOT downgrade on subsequent INTERSTITIAL pattern fire.
- [ ] 2b.2.5 GREEN: upgrade condition is `entry.type == INTERSTITIAL` ONLY; REWARDED stays REWARDED.

### Batch 2b.3 — Per-SDK fixtures

- [ ] 2b.3.1 Add fixture `admob-rewarded.log` (50 lines: AdLoad → onUserEarnedReward → Ad dismissed sequence).
- [ ] 2b.3.2 RED: test `\`admob-rewarded.log fixture produces a REWARDED_VIDEO open\``. [ESC-REW-003]
- [ ] 2b.3.3 GREEN: fixture content needs to actually trigger the regex. Tune lines accordingly.
- [ ] 2b.3.4 Repeat 2b.3.1-3 for IronSource (`ironsource-rewarded.log`), AppLovin (`applovin-rewarded.log`), Meta Audience (`meta-rewarded.log`). 4 fixtures.
- [ ] 2b.3.5 Run `./gradlew test --tests "*Rewarded*"` → green (8 + 4 fixture tests = 12 tests). Full suite green (~871 tests).
- [ ] 2b.3.6 Commit: `feat(events): rewarded video classification for AdMob/IS/AppLovin/Meta + activity upgrade flow`.

### Sprint 2b closure

- [ ] 2b.4.1 Engram update.
- [ ] 2b.4.2 Detekt clean.
- [ ] 2b.4.3 ✅ Sprint 2b done. Test count ~871.

---

## Sprint 3 — INSTRUMENTED opt-in protocol

> **STATUS: ✅ SHIPPED & ARCHIVED 2026-05-13.** Sprint 3 was implemented as a separate, stricter change `instrumented-event-mode` (archived at `openspec/archive/2026-05-13-instrumented-event-mode/`). The implemented protocol uses a FIXED 4-tag allowlist (`CINEMATIC`, `TUTORIAL`, `GAMEPLAY_DENSE`, `SPECIAL_EVENT`) with CASE-SENSITIVE matching and per-tag-keyed lifecycle. The `name=` / `group=` parameter capture from the original Sprint 3 plan was intentionally DROPPED in favour of a minimal protocol. See archive folder for proposal/spec/design/tasks/apply-progress/verify-report. The 3.1.x and 3.2.x batches below are retained for historical traceability ONLY — do NOT execute them; the work is done. Real implementation tasks (1.1.R..6.3) live in `openspec/archive/2026-05-13-instrumented-event-mode/tasks.md`.

**Estimated effort: 1.0d.** *(superseded — actual effort ~0.5d on the archived change)*

### Batch 3.1 — GamePerf catalog + parsing (SUPERSEDED)

- [ ] 3.1.1 RED: create `core/events/InstrumentedProtocolTest.kt`. Test `\`GamePerf CINEMATIC.Start opens INSTRUMENTED event\``: line `tag="GamePerf", msg="CINEMATIC.Start name=\"intro_cutscene\""` at t=1000 → 1 INSTRUMENTED event with metadata `phase=CINEMATIC, name=intro_cutscene`. [ESC-INSTR-001]
- [ ] 3.1.2 GREEN: add "GamePerf" entry to `SdkSignatureCatalog.ALL` per §2.4. Open pattern is the named-capture regex. Close pattern is the Stop variant.
- [ ] 3.1.3 GREEN: modify `EventDetectorImpl.tryOpen` to extract metadata from `Regex.find().groupValues` for the GamePerf signature ONLY (other SDKs unchanged). Implement helper `extractInstrumentedMetadata(pattern: Regex, msg: String): Map<String, String>` returning `{phase, name, group}`.
- [ ] 3.1.4 GREEN: in `tryOpen`, when `sig.sdk == "GamePerf"`, override `metadata` with the extracted map (overriding the default `source/tag` metadata which still goes in).
- [ ] 3.1.5 RED: test `\`GamePerf CINEMATIC.Stop closes INSTRUMENTED event\``: open at t=1000, Stop at t=5000 → close. [ESC-INSTR-001 scenario 2]
- [ ] 3.1.6 GREEN: close pattern matches Stop; standard tryClose handles. Verify open-event lookup matches by phase (not just SDK).
- [ ] 3.1.7 RED: test `\`Stop without matching Start is ignored\``: fire only Stop line → no event, no warning. [ESC-INSTR-002 scenario 2]
- [ ] 3.1.8 GREEN: tryClose iterates over `openEvents.values`; if no open INSTRUMENTED for the same phase exists, silently no-op. Verify.
- [ ] 3.1.9 RED: test name and group captured correctly when present: `CINEMATIC.Start name="A" group="cutscenes"` → metadata has all three. [ESC-INSTR-002]
- [ ] 3.1.10 GREEN: regex group capture per §2.4.
- [ ] 3.1.11 RED: test name optional: `GAMEPLAY_DENSE.Start` (no name) → metadata.name="". [ESC-INSTR-002]
- [ ] 3.1.12 GREEN: regex group default to empty string.
- [ ] 3.1.13 RED: test `\`logcatTagArgs includes GamePerf\``. [ESC-INSTR-003]
- [ ] 3.1.14 GREEN: implicit (catalog entry has `logcatTags=["GamePerf"]`).
- [ ] 3.1.15 Add fixture `instrumented-protocol.log` (40 lines: 4 phases sequenced Start/Stop with various names + groups).
- [ ] 3.1.16 RED: fixture-driven smoke test.
- [ ] 3.1.17 GREEN.

### Batch 3.2 — README + docs (SUPERSEDED)

- [ ] 3.2.1 Update `README.md` (Spanish): new section "Marcadores instrumentados (opt-in)" with Kotlin/Java + Unity C# + Unreal C++ code snippets emitting `Log.i("GamePerf", "CINEMATIC.Start name=\"intro\"")` etc. Document the supported phases (CINEMATIC, TUTORIAL, GAMEPLAY_DENSE, SPECIAL_EVENT) but note arbitrary phases are accepted.
- [ ] 3.2.2 Update `README_EN.md` mirror section.
- [ ] 3.2.3 Update `CLAUDE.md`: append to §"Patrón operativo: cómo añadir un SDK nuevo" — note for SDKs that emit BOTH init and show variants, register multiple (Regex, EventType) pairs in the same `openPatterns` instead of new entries.
- [ ] 3.2.4 Run `./gradlew test` → green (~879 tests). Detekt clean.
- [ ] 3.2.5 Commit: `feat(events): GamePerf instrumented protocol + README docs`.

### Sprint 3 closure

- [ ] 3.3.1 Engram update. ✅ Sprint 3 done. Test count ~879.

---

## Sprint 4a — VR_SESSION + VR_RETURN_TRANSITION (Quest)

> **STATUS: SHIPPED & ARCHIVED 2026-05-13** as separate change `vr-event-detection` — see `openspec/archive/2026-05-13-vr-event-detection/`. The 24 task batches below are SUPERSEDED by the 17 tasks in the archived `tasks.md`. The shipped approach is multi-runtime (Oculus VrApi + OVRPlugin + OpenXR via a single combined "VRRuntime" `SdkSignature` row) with additive `dedupWindowMs` field, post-hoc 2s `VR_RETURN_TRANSITION` synthesis from BOTH `tryClose` and `stop()` paths, and HINT confidence in KDoc. The original `XrPerformanceManager` tag + silent-gap-close design was DROPPED. `PostVrRecoveryRule` (Batch 4a.3) is NOT part of this archive — it remains a backlog follow-up under Sprint 4a closure. The Quest-only caveat (Batch 4a.4.6/4a.4.7) was REPLACED by multi-runtime support (no in-report caveat is rendered today).
>
> The batches below are retained for historical traceability only — they are NOT active work. Do NOT execute them. New work on VR detection should branch from the archived `vr-event-detection` change.

**Estimated effort: 1.0d. Quest-only; generic OpenXR deferred to Sprint 4b.** *(Original estimate; superseded.)*

### Batch 4a.1 — Meta Quest VR signature + silent-gap close

- [ ] 4a.1.1 RED: create `core/events/QuestVrSessionTest.kt`. Test `\`First VrApi line opens VR_SESSION\``: detector with foreground guard primed, fire `handleLogLine` with `tag="VrApi", msg="FPS=72 ..."` at t=1000 → 1 VR_SESSION event. [ESC-VR-001]
- [ ] 4a.1.2 GREEN: add "Meta Quest VR" entry to `SdkSignatureCatalog.ALL` per §2.4. `openPatterns = listOf(Regex(""".+""") to VR_SESSION)`.
- [ ] 4a.1.3 GREEN: in `EventDetectorImpl.handleLogLine`, add early branch: `if (line.tag in setOf("VrApi", "XrPerformanceManager")) { handleVrApiLine(line); return }`. Implement `handleVrApiLine` per §3.4.
- [ ] 4a.1.4 RED: test `\`Same-tag duplicate VrApi lines do NOT duplicate VR_SESSION\``: multiple VrApi lines after open → still 1 event, `lastVrApiLineMs` updated. [ESC-VR-001 scenario 2]
- [ ] 4a.1.5 GREEN: `handleVrApiLine` tracks `lastVrApiLineMs`; opens VR_SESSION only if `openVrSession == null`.
- [ ] 4a.1.6 RED: test `\`VR_SESSION closes after 5s silent gap\``: VR_SESSION open from t=1000, `lastVrApiLineMs=10000`, drive `handleActivityStack` at t=16000 → assert closed. [ESC-VR-002]
- [ ] 4a.1.7 GREEN: implement `checkVrSilentGap(now)` per §3.4. Call from end of `handleActivityStack`.
- [ ] 4a.1.8 RED: test `\`Boundary — exactly 5000ms gap closes session\``: `lastVrApiLineMs=10000`, tick at t=15000 (gap exactly 5000ms) → close. Boundary inclusive. [ESC-VR-002 scenario 2]
- [ ] 4a.1.9 GREEN: condition `now - lastVrApiLineMs >= VR_SESSION_SILENT_GAP_MS` (inclusive >=).

### Batch 4a.2 — VR_RETURN_TRANSITION delayed emission

- [ ] 4a.2.1 RED: test `\`VR session close emits VR_RETURN_TRANSITION\``: trigger silent-gap close at t=16000 → assert immediate VR_RETURN_TRANSITION event with `startMs=16000, endMs=21000`. [ESC-VR-003]
- [ ] 4a.2.2 GREEN: in `closeVrSession(closeMs)`, AFTER closing the VR_SESSION, emit a new VR_RETURN_TRANSITION event with `startMs=closeMs, endMs=closeMs + VR_RETURN_TRANSITION_WINDOW_MS, confidence=MEDIUM, signatureMatched="vr-recovery-window"`.
- [ ] 4a.2.3 RED: test cross-collision with cap: drop VR_SESSION when EVT-009 cap hit; assert VR_RETURN_TRANSITION also NOT emitted. [ESC-VR-003 scenario 2]
- [ ] 4a.2.4 GREEN: emit only if `tryClose` succeeded (the original VR_SESSION existed in the published list).

### Batch 4a.3 — PostVrRecoveryRule

- [ ] 4a.3.1 RED: create `core/conclusions/rules/PostVrRecoveryRuleTest.kt`. Test `\`VR_RETURN_TRANSITION + temp rise emits conclusion\``: session with VR_RETURN_TRANSITION from t=10000 to t=15000, tempCpuMaxC rises 3.0°C → 1 conclusion with text matching Spanish template. [ESC-CONCL-001]
- [ ] 4a.3.2 GREEN: create `core/conclusions/rules/PostVrRecoveryRule.kt` per §2.6. Threshold default 2.0°C.
- [ ] 4a.3.3 GREEN: wire into conclusions engine.
- [ ] 4a.3.4 RED: test no-rise → no conclusion.
- [ ] 4a.3.5 GREEN: filter condition `rise < threshold` returns null.
- [ ] 4a.3.6 RED: test Spanish copy exact match (use the spec template substring).
- [ ] 4a.3.7 GREEN: copy from spec ESC-CONCL-001.

### Batch 4a.4 — Tag allowlist + fixture + report caveat

- [ ] 4a.4.1 RED: test `\`logcatTagArgs includes Quest VR tags\``: VrApi:D and XrPerformanceManager:D present. [ESC-VR-004]
- [ ] 4a.4.2 GREEN: implicit via catalog entry's `logcatTags`.
- [ ] 4a.4.3 Add fixture `quest-vrapi-session.log` (80 lines: 30+ VrApi lines at 100ms intervals, 6s silent gap, 30+ more VrApi lines = two distinct sessions).
- [ ] 4a.4.4 RED: fixture-driven smoke — 2 VR_SESSION events + 2 VR_RETURN_TRANSITION events.
- [ ] 4a.4.5 GREEN.
- [ ] 4a.4.6 RED: in `ReportGeneratorEventsTest.kt` (extend or new), test `\`VR caveat present when VR_SESSION exists\``: generated HTML contains Spanish caveat. [ESC-REPORT-002]
- [ ] 4a.4.7 GREEN: in `ReportGenerator.kt`, append footnote when `events.any { it.type == VR_SESSION }`. Spanish copy from §2.8.
- [ ] 4a.4.8 Run `./gradlew test --tests "*Vr*"` `*PostVr*"` → green (6 detector + 3 rule + 1 report = 10 tests). Full suite green (~889 tests).
- [ ] 4a.4.9 Commit: `feat(events): Meta Quest VR session detection + post-VR recovery rule`.

### Sprint 4a closure

- [ ] 4a.5.1 Engram update.
- [ ] 4a.5.2 Detekt clean.
- [ ] 4a.5.3 Document Sprint 4b deferral in `docs/competitive-analysis-and-kpis.md` §4.4 with risk note: "Generic OpenXR / Cardboard / non-Quest Unreal XR detection requires vendor-specific patterns + test devices — Sprint 4b backlog item."
- [ ] 4a.5.4 ✅ Sprint 4a done. Test count ~889.

---

## Sprint 5 — RATE_US

**Estimated effort: 0.5d.**

### Batch 5.1 — Google Play In-App Review signature

- [ ] 5.1.1 RED: create `core/events/RateUsSignaturesTest.kt`. Test `\`launchReviewFlow line opens RATE_US event\``: line `tag="PlayCore", msg="ReviewManager: launchReviewFlow invoked"` at t=1000 → 1 RATE_US event with `confidence=HIGH`. [ESC-RATE-001]
- [ ] 5.1.2 GREEN: add "Google Play In-App Review" entry to `SdkSignatureCatalog.ALL` per §2.4.
- [ ] 5.1.3 RED: test `\`ReviewActivity on top opens RATE_US via dumpsys path\``: dumpsys returns `com.example/com.google.android.play.core.review.ReviewActivity` → 1 event with `confidence=MEDIUM`. [ESC-RATE-001 scenario 2]
- [ ] 5.1.4 GREEN: activity-class path is automatic via `matchActivity`.
- [ ] 5.1.5 RED: test `\`onComplete closes RATE_US\``: open at t=1000, close line at t=4000 → close. [ESC-RATE-002]
- [ ] 5.1.6 GREEN: close pattern in catalog entry.
- [ ] 5.1.7 RED: test `\`logcatTagArgs includes ReviewManager and PlayCore\``. [ESC-RATE-003]
- [ ] 5.1.8 GREEN: implicit.
- [ ] 5.1.9 Add fixture `rate-us-play-core.log` (30 lines: launchReviewFlow + onComplete).
- [ ] 5.1.10 RED: fixture-driven smoke.
- [ ] 5.1.11 GREEN.
- [ ] 5.1.12 Run `./gradlew test --tests "*RateUs*"` → green (4 tests). Full suite green (~893 tests).
- [ ] 5.1.13 Commit: `feat(events): RATE_US detection for Google Play In-App Review`.

### Sprint 5 closure

- [ ] 5.2.1 Engram update.
- [ ] 5.2.2 Detekt clean.
- [ ] 5.2.3 ✅ Sprint 5 done. Test count ~893.

---

## Sprint 6 — LEVEL_LOADING wire-up

**ALREADY SHIPPED.**

- [x] 6.1 LOADING signatures for Unity Engine, Unreal Engine, Cocos2d added in commit `7116786` ("QUICKFIX: Unity/Unreal/Cocos2d LOADING signatures wired", 2026-05-12).
- [x] 6.2 Tests: `LoadingSignaturesTest.kt` (266 lines), `EventDetectorImplTest.kt` additions for Unity/Unreal/Cocos2d LOADING.
- [x] 6.3 Fixtures: `unity-loading.log`, `unreal-loading.log`, `cocos2d-loading.log`.
- [x] 6.4 `EventType.LOADING` now actively emitted by 3 engine signatures (audit obs #308 gap #4 closed).

NO additional work in this change. Marked complete for traceability.

---

## Final closure

- [ ] F.1 Update `docs/competitive-analysis-and-kpis.md` §4.2 coverage matrix:
  - Row 1 (App startup / SDK init): ❌ → ✅. Effort note updated.
  - Row 5 (Screen navigation): ⚠️ → ✅. Caveat preserved (single-activity engines).
  - Row 7 (Rewarded video): ⚠️ → ✅.
  - Add new rows for: ANR (✅ via Sprint 1), INSTRUMENTED (✅ via Sprint 3, opt-in), VR (✅ Quest-only via Sprint 4a), RATE_US (✅ via Sprint 5).
- [ ] F.2 Update `CHANGELOG.md` with v4.5.0 entry per design §10.
- [ ] F.3 Update `README.md` + `README_EN.md` with new auto-detected events section + instrumented protocol section (latter already added in Sprint 3 Batch 3.2).
- [ ] F.4 Update `CLAUDE.md` operative SOP (already done in Sprint 3 Batch 3.2 step 3.2.3).
- [ ] F.5 Final `./gradlew test` → ~893-900 tests green.
- [ ] F.6 Final `./gradlew detekt` → clean on all touched files.
- [ ] F.7 Engram: save `sdd/event-segmentation-coverage/verify-report` summarizing what shipped, what deferred (Sprint 4b), test count delta, commits.
- [ ] F.8 ✅ Change complete. Ready for `/sdd-verify` then `/sdd-archive`.

---

## Per-batch rules

- Each batch ends with `./gradlew test` green. Mid-batch red OK (TDD red phase).
- Each batch is one conventional commit (`feat(events):`, `refactor(events):`, `test(events):`, `chore(events):`, `docs(events):`).
- Update apply-progress observation `sdd/event-segmentation-coverage/apply-progress` after each Sprint: sprint number, files touched, test counts (added/total), detekt status.

## Effort estimate (refined)

| Sprint | Effort | Batches | Notes |
|--------|--------|---------|-------|
| 0 | 1.0d | 2 batches (0.1, 0.2) | Refactor + 14 test migrations |
| 1 | 2.0d | 3 batches (1.1, 1.2, 1.3) | 3 sub-features |
| 2a | 0.5d | 1 batch | Detector branch only |
| 2b | 1.0d | 3 batches (2b.1, 2b.2, 2b.3) | 4 SDKs + upgrade flow + fixtures |
| 3 | 1.0d | 2 batches (3.1, 3.2) | Protocol + docs |
| 4a | 1.0d | 4 batches (4a.1, 4a.2, 4a.3, 4a.4) | Catalog + close + rule + report |
| 5 | 0.5d | 1 batch | Single SDK signature |
| 6 | 0d | — | Already shipped |
| **Total** | **7.0d** | **16 batches** | |

Total commits expected: ~16 (one per batch + final closure docs commit). Test count delta: ~+56 (from baseline 837 → ~893).
