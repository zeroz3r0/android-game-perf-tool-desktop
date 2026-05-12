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
        SdkSignature(
            sdk = "AdMob",
            type = EventType.INTERSTITIAL,
            activityClasses = listOf(
                "com.google.android.gms.ads.AdActivity",
                "com.google.android.gms.ads.OutOfContextTestingActivity",
            ),
            logcatTags = listOf("Ads", "AdActivity", "MobileAds"),
            openPatterns = listOf(
                Regex("""(?i)\bShowing ad\b"""),
                Regex("""(?i)\bonAdShown\b"""),
                Regex("""(?i)\bad opened\b"""),
                Regex("""(?i)\bLoaded ad\b"""),
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
            type = EventType.REWARDED_VIDEO,
            activityClasses = listOf(
                "com.unity3d.services.ads.adunit.AdUnitActivity",
                "com.unity3d.services.ads.adunit.AdUnitTransparentActivity",
            ),
            logcatTags = listOf("UnityAds", "Unity"),
            openPatterns = listOf(
                Regex("""(?i)\bUnityAdsShowStart\b"""),
                Regex("""(?i)\bShow begin\b"""),
                Regex("""(?i)\bonUnityAdsShowStart\b"""),
            ),
            closePatterns = listOf(
                Regex("""(?i)\bUnityAdsShowComplete\b"""),
                Regex("""(?i)\bonUnityAdsFinish\b"""),
                Regex("""(?i)\bonUnityAdsShowComplete\b"""),
            ),
        ),
        // ── IronSource (now LevelPlay) ──────────────────────────────────
        SdkSignature(
            sdk = "IronSource",
            type = EventType.INTERSTITIAL,
            activityClasses = listOf(
                "com.ironsource.sdk.controller.ControllerActivity",
                "com.ironsource.sdk.controller.InterstitialActivity",
            ),
            logcatTags = listOf("IronSource", "ironSource"),
            openPatterns = listOf(
                Regex("""(?i)\binterstitialDidOpen\b"""),
                Regex("""(?i)\bonInterstitialAdShowSucceeded\b"""),
                Regex("""(?i)\bonInterstitialAdShown\b"""),
            ),
            closePatterns = listOf(
                Regex("""(?i)\binterstitialDidClose\b"""),
                Regex("""(?i)\bonInterstitialAdClosed\b"""),
            ),
        ),
        // ── AppLovin / MAX ──────────────────────────────────────────────
        SdkSignature(
            sdk = "AppLovin",
            type = EventType.INTERSTITIAL,
            activityClasses = listOf(
                "com.applovin.adview.AppLovinFullscreenActivity",
                "com.applovin.adview.AppLovinInterstitialActivity",
            ),
            logcatTags = listOf("AppLovinSdk", "MaxAds", "AppLovin"),
            openPatterns = listOf(
                Regex("""(?i)\bappLovinAdViewDidDisplay\b"""),
                Regex("""(?i)\bonAdDisplayed\b"""),
                Regex("""(?i)\bAd displayed\b"""),
            ),
            closePatterns = listOf(
                Regex("""(?i)\bappLovinAdViewDidDismiss\b"""),
                Regex("""(?i)\bonAdHidden\b"""),
                Regex("""(?i)\bAd hidden\b"""),
            ),
        ),
        // ── Meta Audience Network ───────────────────────────────────────
        SdkSignature(
            sdk = "Meta Audience Network",
            type = EventType.INTERSTITIAL,
            activityClasses = listOf(
                "com.facebook.ads.AudienceNetworkActivity",
            ),
            logcatTags = listOf("FBAudienceNetworkLog", "AudienceNetworkAds"),
            openPatterns = listOf(
                Regex("""(?i)\bonInterstitialDisplayed\b"""),
                Regex("""(?i)\bInterstitial impression logged\b"""),
            ),
            closePatterns = listOf(
                Regex("""(?i)\bonInterstitialDismissed\b"""),
            ),
        ),
        // ── Google Play Billing (IAP) ───────────────────────────────────
        SdkSignature(
            sdk = "Google Play Billing",
            type = EventType.IAP,
            activityClasses = listOf(
                "com.android.billingclient.api.ProxyBillingActivity",
                "com.android.vending",
            ),
            logcatTags = listOf("BillingClient", "Billing"),
            openPatterns = listOf(
                Regex("""(?i)\blaunchBillingFlow\b"""),
                Regex("""(?i)\bonBillingServiceConnected\b"""),
            ),
            closePatterns = listOf(
                Regex("""(?i)\bonPurchasesUpdated\b"""),
                Regex("""(?i)\bbilling flow finished\b"""),
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
            type = EventType.LOADING,
            activityClasses = emptyList(),
            logcatTags = listOf("Unity", "UnityEngine"),
            openPatterns = listOf(
                Regex("""(?i)\bLoading scene\b"""),
                Regex("""(?i)\bAsyncOperation\b"""),
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
            type = EventType.LOADING,
            activityClasses = emptyList(),
            logcatTags = listOf("UE4", "Unreal", "LogStreaming", "LoadingScreen"),
            openPatterns = listOf(
                Regex("""(?i)\bLogStreaming:\s*Loading\b"""),
                Regex("""(?i)\bLoadingScreen\s+Shown\b"""),
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
            type = EventType.LOADING,
            activityClasses = emptyList(),
            logcatTags = listOf("cocos2d", "Cocos2d", "Cocos2dx", "CCDirector"),
            openPatterns = listOf(
                Regex("""\bDirector::replaceScene\b"""),
                Regex("""\bCCDirector\.replaceScene\b"""),
            ),
            closePatterns = listOf(
                Regex("""\bonEnter\b"""),
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
     * @return The matched [SdkSignature] and which open-pattern matched, or
     *   `null` if no SDK in the catalog claims this line as an open signal.
     */
    fun matchOpen(line: LogLine): Pair<SdkSignature, Regex>? {
        for (sig in ALL) {
            if (sig.logcatTags.none { it.equals(line.tag, ignoreCase = true) }) continue
            for (pattern in sig.openPatterns) {
                if (pattern.containsMatchIn(line.msg)) return sig to pattern
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
