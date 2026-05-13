package com.gameperf.desktop.core.events

/**
 * Single source of truth for SDK signatures.
 *
 * Adding a new SDK = adding ONE entry to [ALL]. No parallel definitions in
 * other files (per CLAUDE.md anti-duplication rule, the same trap that
 * v4.2.13 had to fix for `ToolResolver` candidates).
 *
 * Verified SDKs and engines (nine entries):
 *  - AdMob (Google Mobile Ads SDK) — interstitial
 *  - Unity Ads — rewarded video
 *  - IronSource (LevelPlay) — interstitial
 *  - AppLovin / MAX — interstitial
 *  - Meta Audience Network — interstitial
 *  - Google Play Billing — IAP launch
 *  - Unity Engine — scene/asset loading (v4.4.1 quickfix, audit obs #308)
 *  - Unreal Engine — package/level streaming (v4.4.1 quickfix, audit obs #308)
 *  - Cocos2d — scene transitions (v4.4.1 quickfix, audit obs #308)
 *
 * Sprint 1 of event-segmentation-coverage added seven more entries:
 *  - Firebase Init / AppMeasurement Init / AdMob Init / IronSource Init /
 *    Unity Ads Init / AppLovin Init — all `EventType.SDK_INIT` markers.
 *    Patterns are best-effort from public SDK sample code and may need
 *    empirical refinement during Sprint 4 PerfDog/Apptim lab comparison.
 *  - System ANR — `EventType.ANR` via the `am_anr` atom on the
 *    `ActivityManager` tag. Closes on `am_proc_died`. HIGH confidence.
 *
 * Patterns flagged "needs verification" should be confirmed against real device
 * recordings before relying on production. See `explore.md` "Risks" section
 * for known pattern caveats.
 *
 * Matching rules:
 *  - [matchOpen] / [matchClose] require the [LogLine.tag] to match one of the
 *    SDK's [SdkSignature.logcatTags] (case-insensitive, exact tag match — NOT
 *    substring) before evaluating regex patterns.
 *  - [matchActivity] does substring containment — the dumpsys "cmp=" field
 *    is `package/activity`, so we look for the activity class as a substring
 *    of the full component path.
 *
 * @since v4.4.0
 */
internal object SdkSignatureCatalog {

    val ALL: List<SdkSignature> = listOf(
        // ── AdMob (Google Mobile Ads SDK) ───────────────────────────────
        //
        // Sprint 2b — REWARDED_VIDEO discriminator patterns precede the
        // INTERSTITIAL patterns: `matchOpen` is first-match-wins, and
        // `onUserEarnedReward` is the strongest rewarded signal AdMob
        // emits. Patterns are best-effort from Google's official
        // `RewardedAd` sample code.
        SdkSignature(
            sdk = "AdMob",
            defaultType = EventType.INTERSTITIAL,
            activityClasses = listOf(
                "com.google.android.gms.ads.AdActivity",
                "com.google.android.gms.ads.OutOfContextTestingActivity",
            ),
            logcatTags = listOf("Ads", "AdActivity", "MobileAds"),
            openPatterns = listOf(
                // Sprint 2b — REWARDED (more specific, ordered first)
                Regex("""(?i)\bonUserEarnedReward\b""") to EventType.REWARDED_VIDEO,
                Regex("""(?i)\bRewardItem\b""") to EventType.REWARDED_VIDEO,
                // existing INTERSTITIAL patterns (unchanged)
                Regex("""(?i)\bShowing ad\b""") to EventType.INTERSTITIAL,
                Regex("""(?i)\bonAdShown\b""") to EventType.INTERSTITIAL,
                Regex("""(?i)\bad opened\b""") to EventType.INTERSTITIAL,
                Regex("""(?i)\bLoaded ad\b""") to EventType.INTERSTITIAL,
            ),
            closePatterns = listOf(
                Regex("""(?i)\bAd dismissed\b"""),
                Regex("""(?i)\bonAdClosed\b"""),
                Regex("""(?i)\bonAdDismissed\b"""),
            ),
        ),
        // ── Unity Ads ───────────────────────────────────────────────────
        SdkSignature(
            sdk = "Unity Ads",
            defaultType = EventType.REWARDED_VIDEO,
            activityClasses = listOf(
                "com.unity3d.services.ads.adunit.AdUnitActivity",
                "com.unity3d.services.ads.adunit.AdUnitTransparentActivity",
            ),
            logcatTags = listOf("UnityAds", "Unity"),
            openPatterns = listOf(
                Regex("""(?i)\bUnityAdsShowStart\b""") to EventType.REWARDED_VIDEO,
                Regex("""(?i)\bShow begin\b""") to EventType.REWARDED_VIDEO,
                Regex("""(?i)\bonUnityAdsShowStart\b""") to EventType.REWARDED_VIDEO,
            ),
            closePatterns = listOf(
                Regex("""(?i)\bUnityAdsShowComplete\b"""),
                Regex("""(?i)\bonUnityAdsFinish\b"""),
                Regex("""(?i)\bonUnityAdsShowComplete\b"""),
            ),
        ),
        // ── IronSource (now LevelPlay) ──────────────────────────────────
        //
        // Sprint 2b — REWARDED_VIDEO discriminator patterns precede the
        // INTERSTITIAL patterns. Patterns sourced from LevelPlay's
        // `RewardedVideoListener` callback names; best-effort.
        SdkSignature(
            sdk = "IronSource",
            defaultType = EventType.INTERSTITIAL,
            activityClasses = listOf(
                "com.ironsource.sdk.controller.ControllerActivity",
                "com.ironsource.sdk.controller.InterstitialActivity",
            ),
            logcatTags = listOf("IronSource", "ironSource"),
            openPatterns = listOf(
                // Sprint 2b — REWARDED
                Regex("""(?i)\brewardedVideoDidOpen\b""") to EventType.REWARDED_VIDEO,
                Regex("""(?i)\bonRewardedVideoAdOpened\b""") to EventType.REWARDED_VIDEO,
                Regex("""(?i)\bonRewardedVideoAdRewarded\b""") to EventType.REWARDED_VIDEO,
                // existing INTERSTITIAL patterns (unchanged)
                Regex("""(?i)\binterstitialDidOpen\b""") to EventType.INTERSTITIAL,
                Regex("""(?i)\bonInterstitialAdShowSucceeded\b""") to EventType.INTERSTITIAL,
                Regex("""(?i)\bonInterstitialAdShown\b""") to EventType.INTERSTITIAL,
            ),
            closePatterns = listOf(
                Regex("""(?i)\binterstitialDidClose\b"""),
                Regex("""(?i)\bonInterstitialAdClosed\b"""),
            ),
        ),
        // ── AppLovin / MAX ──────────────────────────────────────────────
        //
        // Sprint 2b — REWARDED_VIDEO discriminator patterns precede the
        // INTERSTITIAL patterns. Patterns sourced from AppLovin MAX
        // `MaxRewardedAdListener` and classic AppLovin
        // `AppLovinAdRewardListener` callback names; best-effort.
        SdkSignature(
            sdk = "AppLovin",
            defaultType = EventType.INTERSTITIAL,
            activityClasses = listOf(
                "com.applovin.adview.AppLovinFullscreenActivity",
                "com.applovin.adview.AppLovinInterstitialActivity",
            ),
            logcatTags = listOf("AppLovinSdk", "MaxAds", "AppLovin"),
            openPatterns = listOf(
                // Sprint 2b — REWARDED
                Regex("""(?i)\bonRewardedVideoStarted\b""") to EventType.REWARDED_VIDEO,
                Regex("""(?i)\bonUserRewarded\b""") to EventType.REWARDED_VIDEO,
                Regex("""(?i)\bonRewardedAdReceivedReward\b""") to EventType.REWARDED_VIDEO,
                // existing INTERSTITIAL patterns (unchanged)
                Regex("""(?i)\bappLovinAdViewDidDisplay\b""") to EventType.INTERSTITIAL,
                Regex("""(?i)\bonAdDisplayed\b""") to EventType.INTERSTITIAL,
                Regex("""(?i)\bAd displayed\b""") to EventType.INTERSTITIAL,
            ),
            closePatterns = listOf(
                Regex("""(?i)\bappLovinAdViewDidDismiss\b"""),
                Regex("""(?i)\bonAdHidden\b"""),
                Regex("""(?i)\bAd hidden\b"""),
            ),
        ),
        // ── Meta Audience Network ───────────────────────────────────────
        //
        // Sprint 2b — REWARDED_VIDEO discriminator patterns precede the
        // INTERSTITIAL patterns. Patterns sourced from Meta Audience
        // Network `RewardedVideoAdListener` / `RewardedAdServerListener`
        // callback names; best-effort.
        SdkSignature(
            sdk = "Meta Audience Network",
            defaultType = EventType.INTERSTITIAL,
            activityClasses = listOf(
                "com.facebook.ads.AudienceNetworkActivity",
            ),
            logcatTags = listOf("FBAudienceNetworkLog", "AudienceNetworkAds"),
            openPatterns = listOf(
                // Sprint 2b — REWARDED
                Regex("""(?i)\bonRewardedVideoCompleted\b""") to EventType.REWARDED_VIDEO,
                Regex("""(?i)\bonRewardedAdServerSucceeded\b""") to EventType.REWARDED_VIDEO,
                // existing INTERSTITIAL patterns (unchanged)
                Regex("""(?i)\bonInterstitialDisplayed\b""") to EventType.INTERSTITIAL,
                Regex("""(?i)\bInterstitial impression logged\b""") to EventType.INTERSTITIAL,
            ),
            closePatterns = listOf(
                Regex("""(?i)\bonInterstitialDismissed\b"""),
            ),
        ),
        // ── Google Play Billing (IAP) ───────────────────────────────────
        //
        // Scope: Google Play Billing only; alt-stores (Amazon Appstore /
        // Samsung Galaxy Store / Huawei AppGallery / direct sideload billing
        // SDKs) NOT covered — track via a separate change if a real use case
        // appears.
        //
        // Open: `launchBillingFlow` is the ONLY open pattern — it is emitted
        // exactly when the game invokes `BillingClient.launchBillingFlow()`,
        // so it pairs 1:1 with a real purchase intent. The earlier draft also
        // included `onBillingServiceConnected`, but that callback fires on
        // every billing-client reconnect (app boot, post-resume, network
        // restore) and would emit phantom IAP_FLOW events outside any actual
        // purchase. Removed in the Issue #2 D.9 hardening pass (audit obs
        // #399).
        //
        // Close: `onPurchasesUpdated` (successful purchase + the SUCCESS path
        // of cancelled flows on older clients), `billing flow finished`
        // (Play Store sheet dismissed regardless of outcome), and
        // `USER_CANCELED` / `responseCode=1` which is what Google Play
        // Billing v5+ surfaces when the user backs out of the purchase sheet
        // without buying anything. Without the USER_CANCELED close pattern
        // the cancelled-flow event used to rely on `stop()` to force-close
        // with `endInferred=true` — added in the same hardening pass.
        SdkSignature(
            sdk = "Google Play Billing",
            defaultType = EventType.IAP,
            activityClasses = listOf(
                "com.android.billingclient.api.ProxyBillingActivity",
                "com.android.vending",
            ),
            logcatTags = listOf("BillingClient", "Billing"),
            openPatterns = listOf(
                Regex("""(?i)\blaunchBillingFlow\b""") to EventType.IAP,
            ),
            closePatterns = listOf(
                Regex("""(?i)\bonPurchasesUpdated\b"""),
                Regex("""(?i)\bbilling flow finished\b"""),
                // USER_CANCELED close (Issue #2 D.9 hardening, audit obs #399)
                Regex("""(?i)\bUSER_CANCELED\b"""),
                Regex("""(?i)\bresponseCode=1\b"""),
            ),
        ),
        // ── Unity Engine (scene/asset loading) — v4.4.1 quickfix (audit obs #308) ─
        //
        // Wires `EventType.LOADING` (declared since v4.4.0 but never produced
        // by any signature) to real Unity engine output. The "Unity" tag is
        // shared with Unity Ads, but the open patterns here ("Loading scene",
        // "AsyncOperation") never overlap with the Unity Ads ad-show messages
        // ("Show begin", "UnityAdsShowStart"), so both signatures coexist on
        // the same tag without aliasing.
        //
        // note: activityClasses kept empty — Unity scene loads do NOT push a
        // new Android Activity (single-activity engine); only the logcat path
        // contributes here.
        SdkSignature(
            sdk = "Unity Engine",
            defaultType = EventType.LOADING,
            activityClasses = emptyList(),
            logcatTags = listOf("Unity", "UnityEngine"),
            openPatterns = listOf(
                Regex("""(?i)\bLoading scene\b""") to EventType.LOADING,
                Regex("""(?i)\bAsyncOperation\b""") to EventType.LOADING,
            ),
            closePatterns = listOf(
                Regex("""(?i)\bScene loaded\b"""),
                Regex("""(?i)\bAsyncOperation done\b"""),
            ),
        ),
        // ── Unreal Engine (package/level streaming) — v4.4.1 quickfix ───
        //
        // Sources: Unreal Engine LogCategories — `LogStreaming` is emitted by
        // the async package loader; `LoadingScreen Shown/Hidden` comes from
        // the Loading Screen plugin (most shipped Unreal mobile games include
        // it). Tag allowlist excludes generic `LogTemp` to avoid noise.
        SdkSignature(
            sdk = "Unreal Engine",
            defaultType = EventType.LOADING,
            activityClasses = emptyList(),
            logcatTags = listOf("UE4", "Unreal", "LogStreaming", "LoadingScreen"),
            openPatterns = listOf(
                Regex("""(?i)\bLogStreaming:\s*Loading\b""") to EventType.LOADING,
                Regex("""(?i)\bLoadingScreen\s+Shown\b""") to EventType.LOADING,
            ),
            closePatterns = listOf(
                Regex("""(?i)\bFlushing async loaders\b"""),
                Regex("""(?i)\bLoadingScreen\s+Hidden\b"""),
            ),
        ),
        // ── Cocos2d (scene transitions) — v4.4.1 quickfix ───────────────
        //
        // note: close pattern uses `onEnter` (new scene lifecycle) which is
        // intentionally broad — but the tag-allowlist (`cocos2d`/`Cocos2dx`
        // family only) prevents false positives on foreign components that
        // happen to call methods named `onEnter`. See `LoadingSignaturesTest`
        // negative case `Cocos2d ignores onEnter on foreign tag`.
        SdkSignature(
            sdk = "Cocos2d",
            defaultType = EventType.LOADING,
            activityClasses = emptyList(),
            logcatTags = listOf("cocos2d", "Cocos2d", "Cocos2dx", "CCDirector"),
            openPatterns = listOf(
                Regex("""\bDirector::replaceScene\b""") to EventType.LOADING,
                Regex("""\bCCDirector\.replaceScene\b""") to EventType.LOADING,
            ),
            closePatterns = listOf(
                Regex("""\bonEnter\b"""),
            ),
        ),
        // ────────────────────────────────────────────────────────────────
        //  Sprint 1 — SDK_INIT signatures (six SDKs)
        // ────────────────────────────────────────────────────────────────
        //
        // SDK_INIT entries are instantaneous markers — no natural close on
        // the logcat side. `closePatterns = emptyList()` is intentional and
        // permitted by the catalog invariant (`SdkSignatureCatalogTest.kt`
        // relaxes the close-pattern rule for `EventType.SDK_INIT`).
        //
        // Patterns are best-effort from public SDK sample code and may need
        // empirical refinement during Sprint 4 lab comparison (PerfDog /
        // Apptim baselines). The intent here is broad enough to catch the
        // canonical init line on a real device while staying narrow enough
        // to avoid collision with the ad-show patterns in the parent SDK's
        // entry (verified by `existing nine SDK signatures still match
        // after Sprint 1 catalog growth`).

        // ── Firebase Init ───────────────────────────────────────────────
        SdkSignature(
            sdk = "Firebase Init",
            defaultType = EventType.SDK_INIT,
            activityClasses = emptyList(),
            logcatTags = listOf("FirebaseApp", "Firebase", "GoogleAnalytics"),
            openPatterns = listOf(
                Regex("""(?i)\bFirebaseApp\b.*\binitialization\b""") to EventType.SDK_INIT,
                Regex("""(?i)\bFirebase\b.*\binitialize\b.*\bsuccess\b""") to EventType.SDK_INIT,
            ),
            closePatterns = emptyList(),
        ),
        // ── AppMeasurement (Google Analytics for Firebase) Init ─────────
        SdkSignature(
            sdk = "AppMeasurement Init",
            defaultType = EventType.SDK_INIT,
            activityClasses = emptyList(),
            logcatTags = listOf("FA-SVC", "FA", "FirebaseAnalytics"),
            openPatterns = listOf(
                Regex("""(?i)\bAppMeasurement\b.*\binitialize\b""") to EventType.SDK_INIT,
                Regex("""(?i)\bTag Manager\b.*\binitialized\b""") to EventType.SDK_INIT,
            ),
            closePatterns = emptyList(),
        ),
        // ── AdMob Init ──────────────────────────────────────────────────
        //
        // Distinct from the existing AdMob entry (which models ad-show
        // lifecycle). Tag overlap (`Ads`, `MobileAds`) is fine because the
        // init patterns do NOT collide with `Showing ad` / `onAdShown` /
        // `Loaded ad` etc.
        SdkSignature(
            sdk = "AdMob Init",
            defaultType = EventType.SDK_INIT,
            activityClasses = emptyList(),
            logcatTags = listOf("Ads", "MobileAds", "AdMob"),
            openPatterns = listOf(
                Regex("""(?i)\bMobileAds\b.*\binitialize\b""") to EventType.SDK_INIT,
                Regex("""(?i)\bAdMob SDK\b.*\binitialized\b""") to EventType.SDK_INIT,
                Regex("""(?i)\bInitializing AdMob SDK\b""") to EventType.SDK_INIT,
            ),
            closePatterns = emptyList(),
        ),
        // ── IronSource Init ─────────────────────────────────────────────
        SdkSignature(
            sdk = "IronSource Init",
            defaultType = EventType.SDK_INIT,
            activityClasses = emptyList(),
            logcatTags = listOf("IronSource", "IS_LOG", "ironSource"),
            openPatterns = listOf(
                Regex("""(?i)\bIronSource\b.*\binit\b.*\bsuccess\b""") to EventType.SDK_INIT,
                Regex("""(?i)\bIronSource\b.*\binit\b.*\bcompleted\b""") to EventType.SDK_INIT,
                Regex("""(?i)\binitIronSource\b.*\bsucceed""") to EventType.SDK_INIT,
            ),
            closePatterns = emptyList(),
        ),
        // ── Unity Ads Init ──────────────────────────────────────────────
        SdkSignature(
            sdk = "Unity Ads Init",
            defaultType = EventType.SDK_INIT,
            activityClasses = emptyList(),
            logcatTags = listOf("UnityAds", "Unity"),
            openPatterns = listOf(
                Regex("""(?i)\bUnityAds\b.*\bInitialized successfully\b""") to EventType.SDK_INIT,
                Regex("""(?i)\bUnityAdsInitializationListener\b.*\bonInitializationComplete\b""") to EventType.SDK_INIT,
            ),
            closePatterns = emptyList(),
        ),
        // ── AppLovin / MAX Init ─────────────────────────────────────────
        SdkSignature(
            sdk = "AppLovin Init",
            defaultType = EventType.SDK_INIT,
            activityClasses = emptyList(),
            logcatTags = listOf("AppLovinSdk", "AppLovin"),
            openPatterns = listOf(
                Regex("""(?i)\bAppLovin SDK\b.*\binitialized\b""") to EventType.SDK_INIT,
                Regex("""(?i)\bMaxMediation\b.*\binitialized\b""") to EventType.SDK_INIT,
                Regex("""(?i)\bMAX\b.*\bready\b""") to EventType.SDK_INIT,
            ),
            closePatterns = emptyList(),
        ),
        // ────────────────────────────────────────────────────────────────
        //  Sprint 5 — RATE_US (Google Play In-App Review API)
        // ────────────────────────────────────────────────────────────────
        //
        // Single SDK signature for Google's In-App Review API. The API is
        // exposed through `ReviewManager` / `ReviewManagerFactory` from the
        // `com.google.android.play:core` (or split `play-review`) module and
        // surfaces a system-managed sheet via `ReviewActivity`.
        //
        // RATE_US is instantaneous — there is no natural close signal on the
        // logcat side, so `closePatterns = emptyList()`. The catalog invariant
        // test (`every SDK has at least one open pattern…`) tolerates empty
        // closePatterns for SDK_INIT and RATE_US entries; the report renders
        // RATE_US as a point event (label + color wired in Sprint 0).
        //
        // note: patterns BEST-EFFORT from public docs — refine after empirical
        // capture. Tag allowlist (`PlayCore`, `ReviewManager`, `InAppReview`)
        // prevents false-positives from app-internal review screens.
        SdkSignature(
            sdk = "Google Play In-App Review",
            defaultType = EventType.RATE_US,
            activityClasses = listOf(
                "com.google.android.play.core.review.ReviewActivity",
            ),
            logcatTags = listOf("PlayCore", "ReviewManager", "InAppReview"),
            openPatterns = listOf(
                Regex("""(?i)\bReviewManager\b.*\b(launchReviewFlow|launch)""") to EventType.RATE_US,
                Regex("""(?i)\brequestReviewFlow\b""") to EventType.RATE_US,
                Regex("""(?i)\bInAppReview\b.*\bshown\b""") to EventType.RATE_US,
            ),
            closePatterns = emptyList(), // instantaneous event
        ),
        // ────────────────────────────────────────────────────────────────
        //  instrumented-event-mode (Sprint 3) — GamePerf opt-in
        // ────────────────────────────────────────────────────────────────
        //
        // Opt-in instrumented protocol on logcat tag `GamePerf` (level `I`).
        // The game emits `Log.i("GamePerf", "<TAG>.Start")` and
        // `<TAG>.Stop` for one of four fixed phase names. The catalog entry
        // exists for two reasons only:
        //  - Spec IEM-007: keep `GamePerf:D` in `logcatTagArgs()` so the
        //    `adb logcat` filter passes these lines to the detector.
        //  - Catalog invariants in `SdkSignatureCatalogTest` require every
        //    entry to declare at least one open + close pattern.
        //
        // ACTUAL classification and per-tag routing happens in
        // [EventDetectorImpl]'s instrumented branch, which delegates to
        // [InstrumentedLineParser] for case-sensitive matching against the
        // 4-tag allowlist. The patterns below are intentionally NOT used by
        // the generic `matchOpen` first-match-wins path for these lines —
        // the detector special-cases `line.tag == "GamePerf"` BEFORE the
        // generic scan so this entry is effectively dormant in production.
        //
        // The patterns therefore use a permissive `[A-Z_]+` shape (matches
        // any upper-snake tag) — the real allowlist check lives in
        // [InstrumentedLineParser.ALLOWED_TAGS]. This is the single source
        // of truth per CLAUDE.md anti-duplication rule.
        SdkSignature(
            sdk = "GamePerf",
            defaultType = EventType.INSTRUMENTED,
            activityClasses = emptyList(),
            logcatTags = listOf("GamePerf"),
            openPatterns = listOf(
                Regex("""^[A-Z_]+\.Start$""") to EventType.INSTRUMENTED,
            ),
            closePatterns = listOf(
                Regex("""^[A-Z_]+\.Stop$"""),
            ),
        ),
        // ────────────────────────────────────────────────────────────────
        //  Sprint 1 — ANR (Android system "Application Not Responding")
        // ────────────────────────────────────────────────────────────────
        //
        // Uses the `am_anr` atom emitted by Android's ActivityManager
        // service. Closes on the matching `am_proc_died` line that follows
        // when the user "Wait" / "Close app" dialog resolves.
        //
        // ANR severity is HIGH (per spec ESC-ANR-001) and the EVT-008
        // foreground proximity guard MUST NOT reject ANR events — the
        // detector's `tryOpen` carries a conditional bypass for
        // `resolvedType == EventType.ANR`. The EVT-007 logcat-gap-handler
        // also leaves ANR confidence untouched (spec ESC-ANR-002).
        SdkSignature(
            sdk = "System ANR",
            defaultType = EventType.ANR,
            activityClasses = emptyList(),
            logcatTags = listOf("ActivityManager"),
            openPatterns = listOf(
                Regex("""\bam_anr\b""") to EventType.ANR,
                Regex("""\bANR in\b""") to EventType.ANR,
            ),
            closePatterns = listOf(
                Regex("""\bam_proc_died\b"""),
            ),
        ),
    )

    /**
     * Convert the catalog into adb logcat tag-filter args:
     *   `Ads:D AdActivity:D MobileAds:D ... *:S`
     *
     * Use with `adb logcat -v threadtime <args>`. The trailing `*:S` silences
     * everything else, which is critical for performance — without it an
     * Android device under load can emit 500-2000 lines/sec.
     *
     * @return Ordered list ready to append to the adb command line.
     */
    fun logcatTagArgs(): List<String> {
        val tags = ALL.flatMap { it.logcatTags }.distinct()
        return tags.map { "$it:D" } + "*:S"
    }

    /**
     * Try to match a [LogLine] against any catalog "open" pattern.
     *
     * Sprint 0 shape change: returns [MatchResult] instead of
     * `Pair<SdkSignature, Regex>`. The new struct carries the per-pattern
     * [EventType] (`resolvedType`) so heterogeneous signatures — where
     * different open patterns in the same row map to different event
     * types — can be classified at match time.
     *
     * @return The matched [MatchResult] describing the signature, the
     *   exact open pattern that matched, and the resolved [EventType],
     *   or `null` if no SDK in the catalog claims this line as an open
     *   signal.
     */
    fun matchOpen(line: LogLine): MatchResult? {
        for (sig in ALL) {
            if (sig.logcatTags.none { it.equals(line.tag, ignoreCase = true) }) continue
            for ((pattern, type) in sig.openPatterns) {
                if (pattern.containsMatchIn(line.msg)) {
                    return MatchResult(sig = sig, pattern = pattern, resolvedType = type)
                }
            }
        }
        return null
    }

    /**
     * Try to match a [LogLine] against the "close" patterns of a specific SDK.
     *
     * Used by the event lifecycle state machine after an OPEN event has been
     * registered for [sig] — the detector then watches for a CLOSE specific
     * to that SDK to compute `endMs`.
     *
     * @return The matched close pattern, or `null` if the line is not a close
     *   signal for this SDK.
     */
    fun matchClose(line: LogLine, sig: SdkSignature): Regex? {
        if (sig.logcatTags.none { it.equals(line.tag, ignoreCase = true) }) return null
        for (pattern in sig.closePatterns) {
            if (pattern.containsMatchIn(line.msg)) return pattern
        }
        return null
    }

    /**
     * Try to match an activity class name (from `dumpsys activity activities`)
     * against the catalog.
     *
     * @param cmp The fully-qualified activity component (e.g.,
     *   `"com.example.app/com.google.android.gms.ads.AdActivity"`).
     * @return The matched [SdkSignature] or `null`.
     */
    fun matchActivity(cmp: String): SdkSignature? {
        for (sig in ALL) {
            for (cls in sig.activityClasses) {
                if (cmp.contains(cls)) return sig
            }
        }
        return null
    }
}
