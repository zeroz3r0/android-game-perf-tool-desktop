package com.gameperf.desktop.core.events

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [SdkSignatureCatalog].
 *
 * Per CLAUDE.md "tests puros sin mocks" — no mocks. Inputs are hand-built
 * [LogLine] instances plus six recorded fixtures under
 * `src/test/resources/logcat-fixtures/`.
 *
 * For EACH SDK we assert:
 *  1. `matchOpen` succeeds on a known open-pattern line for that SDK.
 *  2. `matchOpen` returns null on a same-tag line whose message is unrelated.
 *  3. `matchClose` succeeds on a known close-pattern line for that SDK.
 *  4. `matchClose` for SDK A returns null when given SDK B's close line.
 *  5. `matchActivity` returns the correct signature for at least one of its
 *     declared activity classes.
 *
 * Plus catalog-level invariants (size, distinct tags, complete tag args,
 * non-empty pattern lists).
 *
 * Coverage targets EVT-003 spec scenarios.
 */
class SdkSignatureCatalogTest {

    // ═══════ catalog-level invariants ═══════

    @Test
    fun `catalog contains exactly the nineteen catalogued SDKs and engines`() {
        // If anyone removes an SDK they MUST update this assertion deliberately.
        // v4.4.0 baseline: six ad/billing SDKs.
        // v4.4.1 quickfix (audit obs #308): added three engine LOADING signatures.
        // Sprint 1 (event-segmentation-coverage): added six SDK_INIT entries +
        // System ANR — `9 + 7 = 16`.
        // Sprint 5 (event-segmentation-coverage): added Google Play In-App
        // Review — `16 + 1 = 17`.
        // instrumented-event-mode (Sprint 3): added GamePerf opt-in signature —
        // `17 + 1 = 18`.
        // Sprint 4 (vr-event-detection): added VRRuntime VR_SESSION signature —
        // `18 + 1 = 19`.
        assertEquals(19, SdkSignatureCatalog.ALL.size, "expected 19 catalogued SDKs/engines")
        val sdkNames = SdkSignatureCatalog.ALL.map { it.sdk }.toSet()
        val expected = setOf(
            "AdMob",
            "Unity Ads",
            "IronSource",
            "AppLovin",
            "Meta Audience Network",
            "Google Play Billing",
            "Unity Engine",
            "Unreal Engine",
            "Cocos2d",
            // Sprint 1 — SDK_INIT signatures
            "Firebase Init",
            "AppMeasurement Init",
            "AdMob Init",
            "IronSource Init",
            "Unity Ads Init",
            "AppLovin Init",
            // Sprint 1 — ANR
            "System ANR",
            // Sprint 5 — RATE_US
            "Google Play In-App Review",
            // instrumented-event-mode (Sprint 3) — INSTRUMENTED opt-in
            "GamePerf",
            // Sprint 4 — VR_SESSION (vr-event-detection)
            "VRRuntime",
        )
        assertEquals(expected, sdkNames, "catalog SDK set drifted from spec")
    }

    @Test
    fun `every SDK has at least one open pattern with close patterns required for lifecycle entries`() {
        for (sig in SdkSignatureCatalog.ALL) {
            assertTrue(sig.openPatterns.isNotEmpty(), "${sig.sdk}: no open patterns")
            assertTrue(sig.logcatTags.isNotEmpty(), "${sig.sdk}: no logcat tags")

            // closePatterns invariant — required for entries that model a
            // bracketed lifecycle (ad/IAP/loading: every show has a dismiss;
            // ANR: am_anr eventually pairs with am_proc_died). SDK_INIT and
            // RATE_US entries are instantaneous markers (no natural close on
            // the logcat side; the report renders them as point events) so an
            // empty closePatterns list is acceptable for them.
            val instantaneous = sig.defaultType == EventType.SDK_INIT ||
                sig.defaultType == EventType.RATE_US
            if (!instantaneous) {
                assertTrue(
                    sig.closePatterns.isNotEmpty(),
                    "${sig.sdk}: no close patterns (only SDK_INIT/RATE_US entries may have empty closePatterns)",
                )
            }

            // activityClasses MAY be empty for engine-level signatures (Unity
            // Engine, Unreal Engine, Cocos2d), SDK_INIT signatures, the
            // System ANR signature, the instrumented-event-mode GamePerf
            // signature, and VR_SESSION signatures (VR runtimes don't push
            // their own Android Activity — they take over the active surface)
            // — none of these push their own Android Activity onto
            // the back stack. For ad/billing SDKs (which DO push activities)
            // the field must still be populated.
            val noActivityRequired = sig.defaultType == EventType.LOADING ||
                sig.defaultType == EventType.SDK_INIT ||
                sig.defaultType == EventType.ANR ||
                sig.defaultType == EventType.INSTRUMENTED ||
                sig.defaultType == EventType.VR_SESSION
            if (!noActivityRequired) {
                assertTrue(sig.activityClasses.isNotEmpty(), "${sig.sdk}: no activity classes")
            }
        }
    }

    @Test
    fun `SDK names are unique`() {
        val names = SdkSignatureCatalog.ALL.map { it.sdk }
        assertEquals(names.size, names.toSet().size, "duplicate SDK names found")
    }

    @Test
    fun `no VR regex literals leak outside SdkSignatureCatalog (single-source invariant)`() {
        // Sprint 4 (vr-event-detection) — spec VR-001 anti-duplication
        // invariant. The VR signatures live ONLY in
        // `SdkSignatureCatalog.kt`. Any VR regex literal in another file
        // under `core/events/` would split the single source of truth
        // (the same trap CLAUDE.md documents for `ToolResolver` in
        // v4.2.13). We scan the production source tree for any file that
        // is NOT `SdkSignatureCatalog.kt` but contains canonical VR
        // tokens (`vrapi_`, `xrBegin`, `xrEnd`, `HMDMounted`,
        // `XR_SESSION_STATE_`). Any hit fails the test.
        val coreEventsDir = java.io.File("src/main/kotlin/com/gameperf/desktop/core/events")
        assertTrue(coreEventsDir.isDirectory, "core/events/ source dir not found at expected path")
        val vrTokens = listOf("vrapi_", "xrBegin", "xrEnd", "HMDMounted", "XR_SESSION_STATE_")
        val offenders = coreEventsDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.name != "SdkSignatureCatalog.kt" }
            .flatMap { file ->
                val text = file.readText(StandardCharsets.UTF_8)
                vrTokens.filter { token -> text.contains(token) }
                    .map { token -> "${file.name}: contains '$token'" }
            }
            .toList()
        assertTrue(
            offenders.isEmpty(),
            "VR regex literals leaked outside SdkSignatureCatalog.kt — single-source invariant " +
                "broken (Sprint 4 vr-event-detection spec VR-001). Offenders:\n" +
                offenders.joinToString("\n"),
        )
    }

    @Test
    fun `logcatTagArgs ends with star colon S and contains all unique tags`() {
        val args = SdkSignatureCatalog.logcatTagArgs()
        assertEquals("*:S", args.last(), "tag args must terminate with *:S to silence everything else")
        val expectedUniqueTags = SdkSignatureCatalog.ALL.flatMap { it.logcatTags }.distinct()
        for (tag in expectedUniqueTags) {
            assertTrue(args.contains("$tag:D"), "missing tag-arg for $tag")
        }
        // Args length = unique tags + the trailing *:S
        assertEquals(expectedUniqueTags.size + 1, args.size)
    }

    @Test
    fun `logcatTagArgs includes GamePerf colon D for instrumented opt-in mode`() {
        // Spec IEM-007 — the instrumented opt-in protocol requires the
        // `GamePerf` tag to flow through the adb logcat filter so the
        // detector ever sees `<TAG>.Start` / `<TAG>.Stop` lines emitted
        // from the game process. This test pins that contract explicitly.
        val args = SdkSignatureCatalog.logcatTagArgs()
        assertTrue(
            args.contains("GamePerf:D"),
            "logcatTagArgs() must include `GamePerf:D` so adb logcat passes instrumented opt-in lines",
        )
    }

    // ═══════ AdMob ═══════

    @Test
    fun `matches AdMob open and close, ignores unrelated AdMob-tag noise`() {
        val open = lineFor(tag = "AdActivity", msg = "Showing ad")
        val matched = SdkSignatureCatalog.matchOpen(open)
        assertNotNull(matched)
        assertEquals("AdMob", matched.sig.sdk)
        assertEquals(EventType.INTERSTITIAL, matched.resolvedType)

        val close = lineFor(tag = "AdActivity", msg = "Ad dismissed by user")
        assertNotNull(SdkSignatureCatalog.matchClose(close, matched.sig))

        val noise = lineFor(tag = "AdActivity", msg = "internal: re-binding view holder")
        assertNull(SdkSignatureCatalog.matchOpen(noise))
    }

    @Test
    fun `matches AdMob activity component`() {
        val sig = SdkSignatureCatalog.matchActivity(
            "com.example.game/com.google.android.gms.ads.AdActivity",
        )
        assertNotNull(sig)
        assertEquals("AdMob", sig.sdk)
    }

    // ═══════ Unity Ads ═══════

    @Test
    fun `matches Unity Ads open and close, ignores unrelated Unity-tag noise`() {
        val open = lineFor(tag = "UnityAds", msg = "UnityAdsShowStart placement=rewardedVideo")
        val matched = SdkSignatureCatalog.matchOpen(open)
        assertNotNull(matched)
        assertEquals("Unity Ads", matched.sig.sdk)
        assertEquals(EventType.REWARDED_VIDEO, matched.resolvedType)

        val close = lineFor(tag = "UnityAds", msg = "UnityAdsShowComplete state=COMPLETED")
        assertNotNull(SdkSignatureCatalog.matchClose(close, matched.sig))

        val noise = lineFor(tag = "UnityAds", msg = "Cache: writing 4096 bytes")
        assertNull(SdkSignatureCatalog.matchOpen(noise))
    }

    @Test
    fun `matches Unity Ads activity component`() {
        val sig = SdkSignatureCatalog.matchActivity(
            "com.example.game/com.unity3d.services.ads.adunit.AdUnitActivity",
        )
        assertNotNull(sig)
        assertEquals("Unity Ads", sig.sdk)
    }

    // ═══════ IronSource ═══════

    @Test
    fun `matches IronSource open and close, ignores unrelated IronSource-tag noise`() {
        val open = lineFor(tag = "IronSource", msg = "interstitialDidOpen instanceId=42")
        val matched = SdkSignatureCatalog.matchOpen(open)
        assertNotNull(matched)
        assertEquals("IronSource", matched.sig.sdk)

        val close = lineFor(tag = "IronSource", msg = "interstitialDidClose instanceId=42")
        assertNotNull(SdkSignatureCatalog.matchClose(close, matched.sig))

        val noise = lineFor(tag = "IronSource", msg = "IS::init: starting bidder pool")
        assertNull(SdkSignatureCatalog.matchOpen(noise))
    }

    @Test
    fun `matches IronSource activity component`() {
        val sig = SdkSignatureCatalog.matchActivity(
            "com.example.game/com.ironsource.sdk.controller.ControllerActivity",
        )
        assertNotNull(sig)
        assertEquals("IronSource", sig.sdk)
    }

    // ═══════ AppLovin ═══════

    @Test
    fun `matches AppLovin open and close, ignores unrelated AppLovin-tag noise`() {
        val open = lineFor(tag = "AppLovinSdk", msg = "onAdDisplayed adUnitId=ca-app-xxx")
        val matched = SdkSignatureCatalog.matchOpen(open)
        assertNotNull(matched)
        assertEquals("AppLovin", matched.sig.sdk)

        val close = lineFor(tag = "AppLovinSdk", msg = "onAdHidden adUnitId=ca-app-xxx")
        assertNotNull(SdkSignatureCatalog.matchClose(close, matched.sig))

        val noise = lineFor(tag = "AppLovinSdk", msg = "SDK keys validated successfully")
        assertNull(SdkSignatureCatalog.matchOpen(noise))
    }

    @Test
    fun `matches AppLovin activity component`() {
        val sig = SdkSignatureCatalog.matchActivity(
            "com.example.game/com.applovin.adview.AppLovinFullscreenActivity",
        )
        assertNotNull(sig)
        assertEquals("AppLovin", sig.sdk)
    }

    // ═══════ Meta Audience Network ═══════

    @Test
    fun `matches Meta open and close, ignores unrelated Meta-tag noise`() {
        val open = lineFor(tag = "FBAudienceNetworkLog", msg = "Interstitial impression logged")
        val matched = SdkSignatureCatalog.matchOpen(open)
        assertNotNull(matched)
        assertEquals("Meta Audience Network", matched.sig.sdk)

        val close = lineFor(tag = "FBAudienceNetworkLog", msg = "onInterstitialDismissed event=user")
        assertNotNull(SdkSignatureCatalog.matchClose(close, matched.sig))

        val noise = lineFor(tag = "FBAudienceNetworkLog", msg = "Cache TTL refresh queued")
        assertNull(SdkSignatureCatalog.matchOpen(noise))
    }

    @Test
    fun `matches Meta activity component`() {
        val sig = SdkSignatureCatalog.matchActivity(
            "com.example.game/com.facebook.ads.AudienceNetworkActivity",
        )
        assertNotNull(sig)
        assertEquals("Meta Audience Network", sig.sdk)
    }

    // ═══════ Google Play Billing ═══════

    @Test
    fun `matches Play Billing open and close, ignores unrelated Billing-tag noise`() {
        val open = lineFor(tag = "BillingClient", msg = "launchBillingFlow: starting flow for sku=premium")
        val matched = SdkSignatureCatalog.matchOpen(open)
        assertNotNull(matched)
        assertEquals("Google Play Billing", matched.sig.sdk)
        assertEquals(EventType.IAP, matched.resolvedType)

        val close = lineFor(tag = "BillingClient", msg = "onPurchasesUpdated: result=OK")
        assertNotNull(SdkSignatureCatalog.matchClose(close, matched.sig))

        val noise = lineFor(tag = "BillingClient", msg = "Service status report posted")
        assertNull(SdkSignatureCatalog.matchOpen(noise))
    }

    @Test
    fun `matches Play Billing activity component`() {
        val sig = SdkSignatureCatalog.matchActivity(
            "com.example.game/com.android.billingclient.api.ProxyBillingActivity",
        )
        assertNotNull(sig)
        assertEquals("Google Play Billing", sig.sdk)
    }

    // ═══════ IAP hardening (Issue #2 task D.9, audit obs #399) ═══════
    //
    // Two theoretical risks from the iap-coverage-audit explore:
    //  - Risk #1: `onBillingServiceConnected` fires at app boot / billing
    //    client reconnect — NOT only on a real purchase flow. If left as an
    //    open pattern it produces phantom IAP_FLOW events whenever the SDK
    //    reconnects (post-resume, post-network-restore). The launchBillingFlow
    //    pattern remains as the SOLE open signal — it is emitted only when
    //    the game actually invokes `BillingClient.launchBillingFlow()`.
    //  - Risk #2: User-cancelled purchases (BillingResult code=1
    //    USER_CANCELED) historically left the IAP_FLOW event without an
    //    explicit close, relying on `stop()` to force-close with
    //    endInferred=true. The added USER_CANCELED close pattern lets the
    //    detector pair the cancellation explicitly so the report can render
    //    a precise endMs and avoid the "(cierre inferido)" tag.

    @Test
    fun `onBillingServiceConnected does NOT trigger IAP open (Issue #2 D9 - phantom reconnect guard)`() {
        // BillingClient.onBillingServiceConnected fires on every reconnect
        // (app resume, network restore, billing service rebind) — NOT only
        // when a purchase flow is launched. Keeping it as an open pattern
        // produced phantom IAP_FLOW events at app boot. Only
        // `launchBillingFlow` (the real flow-launch API) must open IAP_FLOW.
        val line = lineFor(tag = "BillingClient", msg = "onBillingServiceConnected")
        val result = SdkSignatureCatalog.matchOpen(line)
        assertNull(
            result,
            "onBillingServiceConnected must NOT open IAP_FLOW — it fires at boot/reconnect, not on a real purchase",
        )
    }

    @Test
    fun `USER_CANCELED line closes a Play Billing IAP flow`() {
        // BillingResult code=1 (USER_CANCELED) is what Play Billing surfaces
        // when the user backs out of the purchase sheet without buying.
        // Before the Issue #2 D.9 hardening this line was not a close
        // signature → the cancelled-flow event relied on `stop()` to
        // force-close with `endInferred=true`. The added close pattern makes
        // the close explicit and avoids the "(cierre inferido)" tag in the
        // report for the common "user dismissed the sheet" case.
        val sig = SdkSignatureCatalog.ALL.first { it.sdk == "Google Play Billing" }
        val line = lineFor(
            tag = "BillingClient",
            msg = "BillingResult: responseCode=1 debugMessage=USER_CANCELED",
        )
        assertNotNull(
            SdkSignatureCatalog.matchClose(line, sig),
            "USER_CANCELED BillingResult must match a Play Billing close pattern",
        )
    }

    @Test
    fun `cancelled-flow fixture produces ONE IAP open closed by USER_CANCELED, not by purchase success`() {
        // Replay the cancelled-flow fixture line by line against the catalog.
        // Acceptance criteria (audit obs #399 hardening):
        //   - Exactly ONE open match for Play Billing (the launchBillingFlow
        //     line). The earlier `onBillingServiceConnected` open is gone, so
        //     the cancelled fixture must NOT produce two opens.
        //   - The first close that pairs with the open is a USER_CANCELED /
        //     responseCode=1 BillingResult — NOT `onPurchasesUpdated` (which
        //     never appears in a cancelled flow).
        val sig = SdkSignatureCatalog.ALL.first { it.sdk == "Google Play Billing" }
        val lines = readFixture("logcat-fixtures/iap-cancelled-flow.log")

        var openCount = 0
        var sawOpen = false
        var firstCloseMsg: String? = null
        for (raw in lines) {
            val parsed = LogcatLineParser.parse(raw) ?: continue
            val open = SdkSignatureCatalog.matchOpen(parsed)
            if (open != null && open.sig.sdk == "Google Play Billing") {
                openCount += 1
                sawOpen = true
                continue
            }
            if (sawOpen && firstCloseMsg == null) {
                if (SdkSignatureCatalog.matchClose(parsed, sig) != null) {
                    firstCloseMsg = parsed.msg
                }
            }
        }

        assertEquals(
            1, openCount,
            "cancelled fixture must produce exactly ONE Play Billing open (launchBillingFlow); " +
                "more than one means a phantom open pattern leaked back in",
        )
        assertNotNull(firstCloseMsg, "cancelled fixture must produce a close match after the open")
        assertTrue(
            firstCloseMsg.contains("USER_CANCELED", ignoreCase = true) ||
                firstCloseMsg.contains("responseCode=1"),
            "first close after the open must be the USER_CANCELED BillingResult — got: $firstCloseMsg",
        )
        assertFalse(
            firstCloseMsg.contains("onPurchasesUpdated", ignoreCase = true),
            "cancelled flow must NOT close on onPurchasesUpdated (no successful purchase happened)",
        )
    }

    // ═══════ cross-SDK negative: SDK A close line should NOT match SDK B ═══════

    @Test
    fun `AdMob close line does not match Unity Ads close patterns`() {
        val unityAdsSig = SdkSignatureCatalog.ALL.first { it.sdk == "Unity Ads" }
        val admobClose = lineFor(tag = "AdActivity", msg = "Ad dismissed by user")
        assertNull(SdkSignatureCatalog.matchClose(admobClose, unityAdsSig))
    }

    @Test
    fun `IronSource close line does not match AppLovin close patterns`() {
        val applovinSig = SdkSignatureCatalog.ALL.first { it.sdk == "AppLovin" }
        val ironClose = lineFor(tag = "IronSource", msg = "interstitialDidClose")
        assertNull(SdkSignatureCatalog.matchClose(ironClose, applovinSig))
    }

    // ═══════ tag-mismatch must reject even matching message ═══════

    @Test
    fun `matchOpen rejects line whose tag is not in the SDK's tag list`() {
        // Message looks AdMob-y but tag is unrelated → must NOT match AdMob.
        val rogue = lineFor(tag = "Unrelated", msg = "Showing ad")
        assertNull(SdkSignatureCatalog.matchOpen(rogue))
    }

    // ═══════ fixtures-driven smoke tests ═══════

    @Test
    fun `admob fixture contains at least one open and one close that match`() {
        assertFixtureProducesOpenAndClose(
            fixture = "logcat-fixtures/admob-interstitial.log",
            expectedSdk = "AdMob",
        )
    }

    @Test
    fun `unity ads fixture contains at least one open and one close that match`() {
        assertFixtureProducesOpenAndClose(
            fixture = "logcat-fixtures/unity-ads.log",
            expectedSdk = "Unity Ads",
        )
    }

    @Test
    fun `ironsource fixture contains at least one open and one close that match`() {
        assertFixtureProducesOpenAndClose(
            fixture = "logcat-fixtures/ironsource-interstitial.log",
            expectedSdk = "IronSource",
        )
    }

    @Test
    fun `applovin fixture contains at least one open and one close that match`() {
        assertFixtureProducesOpenAndClose(
            fixture = "logcat-fixtures/applovin-interstitial.log",
            expectedSdk = "AppLovin",
        )
    }

    @Test
    fun `meta audience fixture contains at least one open and one close that match`() {
        assertFixtureProducesOpenAndClose(
            fixture = "logcat-fixtures/meta-audience.log",
            expectedSdk = "Meta Audience Network",
        )
    }

    @Test
    fun `play billing fixture contains at least one open match`() {
        // For Play Billing the "close" is `onPurchasesUpdated` which may or
        // may not appear in every fixture (purchase can be cancelled). Open
        // is mandatory.
        val sig = SdkSignatureCatalog.ALL.first { it.sdk == "Google Play Billing" }
        val lines = readFixture("logcat-fixtures/play-billing-launch.log")
        val openMatched = lines.any {
            val parsed = LogcatLineParser.parse(it) ?: return@any false
            val match = SdkSignatureCatalog.matchOpen(parsed) ?: return@any false
            match.sig.sdk == sig.sdk
        }
        assertTrue(openMatched, "play-billing-launch.log fixture had no open match")
    }

    // ═══════ Sprint 0 — per-pattern type discriminator ═══════
    //
    // Proves the new `openPatterns: List<Pair<Regex, EventType>>` shape
    // resolves heterogeneous patterns to their tagged type, not to the
    // signature's `defaultType`. The catalogued v4.4.0/v4.4.1 entries are
    // all homogeneous (every pattern → defaultType), so the test uses a
    // synthetic SdkSignature to assert the discriminator capability that
    // future sprints (2b: rewarded/interstitial split, 1: APP_STARTUP +
    // SDK_INIT cohabiting one SDK row) depend on.
    //
    // NOTE: matchOpen() is bound to the static catalog, so this test
    // exercises MatchResult construction directly against a custom
    // signature rather than going through the catalog — same code path
    // as the production matchOpen body.

    @Test
    fun `matchOpen on multi-type signature resolves each pattern to its tagged type`() {
        val sig = SdkSignature(
            sdk = "test-multi",
            defaultType = EventType.INTERSTITIAL,
            activityClasses = emptyList(),
            logcatTags = listOf("TestTag"),
            openPatterns = listOf(
                Regex("""\bInitializing SDK\b""") to EventType.SDK_INIT,
                Regex("""\bShowing ad\b""") to EventType.INTERSTITIAL,
            ),
            closePatterns = emptyList(),
        )

        // Replicate the matchOpen body against the synthetic signature.
        fun match(line: LogLine): MatchResult? {
            if (sig.logcatTags.none { it.equals(line.tag, ignoreCase = true) }) return null
            for ((pattern, type) in sig.openPatterns) {
                if (pattern.containsMatchIn(line.msg)) {
                    return MatchResult(sig = sig, pattern = pattern, resolvedType = type)
                }
            }
            return null
        }

        val initLine = lineFor(tag = "TestTag", msg = "Initializing SDK now")
        val initMatch = match(initLine)
        assertNotNull(initMatch, "Initializing SDK must match")
        assertEquals(EventType.SDK_INIT, initMatch.resolvedType,
            "multi-type signature must resolve 'Initializing SDK' to SDK_INIT, NOT defaultType")

        val adLine = lineFor(tag = "TestTag", msg = "Showing ad to user")
        val adMatch = match(adLine)
        assertNotNull(adMatch, "Showing ad must match")
        assertEquals(EventType.INTERSTITIAL, adMatch.resolvedType,
            "multi-type signature must resolve 'Showing ad' to INTERSTITIAL")

        // Sanity: defaultType is INTERSTITIAL, but a SDK_INIT pattern match
        // must NOT fall back to it.
        assertEquals(EventType.INTERSTITIAL, sig.defaultType)
    }

    // ═══════ Sprint 1 — SDK_INIT signatures (six SDKs) ═══════
    //
    // Six new catalog entries emit `EventType.SDK_INIT`. Patterns are
    // best-effort from public SDK sample code; they may need empirical
    // refinement post-PerfDog/Apptim Sprint 4 lab comparison. Tests assert
    // each entry matches a canonical init line on at least one of its
    // declared tags and resolves to `SDK_INIT`.
    //
    // Spec refs: ESC-INIT-001 (six signatures), ESC-CATALOG-001 (catalog
    // size invariant).

    @Test
    fun `Firebase Init signature matches FirebaseApp initialize log`() {
        val line = lineFor(tag = "FirebaseApp", msg = "FirebaseApp initialization successful for [DEFAULT]")
        val result = SdkSignatureCatalog.matchOpen(line)
        assertNotNull(result, "Firebase init log must match")
        assertEquals("Firebase Init", result.sig.sdk)
        assertEquals(EventType.SDK_INIT, result.resolvedType)
    }

    @Test
    fun `AppMeasurement Init signature matches FA tag init log`() {
        val line = lineFor(tag = "FA-SVC", msg = "AppMeasurement initialize, version=1.2.3")
        val result = SdkSignatureCatalog.matchOpen(line)
        assertNotNull(result, "AppMeasurement init log must match")
        assertEquals("AppMeasurement Init", result.sig.sdk)
        assertEquals(EventType.SDK_INIT, result.resolvedType)
    }

    @Test
    fun `AdMob Init signature matches MobileAds initialize log`() {
        val line = lineFor(tag = "Ads", msg = "MobileAds initialize complete")
        val result = SdkSignatureCatalog.matchOpen(line)
        assertNotNull(result, "AdMob init log must match")
        assertEquals("AdMob Init", result.sig.sdk)
        assertEquals(EventType.SDK_INIT, result.resolvedType)
    }

    @Test
    fun `IronSource Init signature matches init success log`() {
        val line = lineFor(tag = "IronSource", msg = "IronSource SDK init success")
        val result = SdkSignatureCatalog.matchOpen(line)
        assertNotNull(result, "IronSource init log must match")
        assertEquals("IronSource Init", result.sig.sdk)
        assertEquals(EventType.SDK_INIT, result.resolvedType)
    }

    @Test
    fun `Unity Ads Init signature matches Initialized successfully log`() {
        val line = lineFor(tag = "UnityAds", msg = "UnityAds Initialized successfully")
        val result = SdkSignatureCatalog.matchOpen(line)
        assertNotNull(result, "Unity Ads init log must match")
        assertEquals("Unity Ads Init", result.sig.sdk)
        assertEquals(EventType.SDK_INIT, result.resolvedType)
    }

    @Test
    fun `AppLovin Init signature matches AppLovin SDK initialized log`() {
        val line = lineFor(tag = "AppLovinSdk", msg = "AppLovin SDK v11.0.0 initialized successfully")
        val result = SdkSignatureCatalog.matchOpen(line)
        assertNotNull(result, "AppLovin init log must match")
        assertEquals("AppLovin Init", result.sig.sdk)
        assertEquals(EventType.SDK_INIT, result.resolvedType)
    }

    // ═══════ Sprint 1 — Regression: pre-existing ad/billing/loading SDKs still match ═══════
    //
    // After the catalog grew with seven Sprint 1 entries, the matchOpen
    // first-match-wins linear scan must still resolve to the canonical
    // entry for each pre-existing SDK. This guards against accidental
    // ordering breakage where a new init entry intercepts a non-init line.

    @Test
    fun `existing nine SDK signatures still match after Sprint 1 catalog growth`() {
        data class Fixture(val tag: String, val msg: String, val expectedSdk: String, val expectedType: EventType)
        val fixtures = listOf(
            Fixture("AdActivity", "Showing ad", "AdMob", EventType.INTERSTITIAL),
            Fixture("UnityAds", "UnityAdsShowStart placement=rewarded", "Unity Ads", EventType.REWARDED_VIDEO),
            Fixture("IronSource", "interstitialDidOpen instanceId=1", "IronSource", EventType.INTERSTITIAL),
            Fixture("AppLovinSdk", "onAdDisplayed adUnitId=xxx", "AppLovin", EventType.INTERSTITIAL),
            Fixture("FBAudienceNetworkLog", "Interstitial impression logged", "Meta Audience Network", EventType.INTERSTITIAL),
            Fixture("BillingClient", "launchBillingFlow starting", "Google Play Billing", EventType.IAP),
            Fixture("Unity", "Loading scene Foo", "Unity Engine", EventType.LOADING),
            Fixture("UE4", "LogStreaming: Loading package /Game/Maps/X", "Unreal Engine", EventType.LOADING),
            Fixture("cocos2d", "Director::replaceScene to GameScene", "Cocos2d", EventType.LOADING),
        )
        for (f in fixtures) {
            val line = lineFor(tag = f.tag, msg = f.msg)
            val result = SdkSignatureCatalog.matchOpen(line)
            assertNotNull(result, "${f.expectedSdk}: '${f.msg}' on tag=${f.tag} stopped matching")
            assertEquals(
                f.expectedSdk, result.sig.sdk,
                "${f.expectedSdk}: matched the wrong SDK (got ${result.sig.sdk}) — ordering broke after Sprint 1",
            )
            assertEquals(
                f.expectedType, result.resolvedType,
                "${f.expectedSdk}: resolved type drifted",
            )
        }
    }

    // ═══════ Sprint 2b — Rewarded discriminator patterns ═══════
    //
    // Ad-SDK rows (AdMob, IronSource, AppLovin, Meta Audience Network) gain
    // additional `openPatterns` entries that resolve to REWARDED_VIDEO. These
    // patterns are listed BEFORE the existing INTERSTITIAL patterns so the
    // first-match-wins linear scan in `matchOpen` classifies more-specific
    // rewarded callbacks (`onUserEarnedReward` etc.) as REWARDED_VIDEO and
    // leaves the generic show callbacks classifying as INTERSTITIAL. Patterns
    // are best-effort from public SDK sample code.
    //
    // Spec refs: ESC-REW-001 (per-SDK rewarded openPatterns), ESC-REW-003
    // (interstitial regression unaffected).

    @Test
    fun `AdMob rewarded line classifies as REWARDED_VIDEO`() {
        val line = lineFor(tag = "Ads", msg = "onUserEarnedReward type=coins amount=10")
        val result = SdkSignatureCatalog.matchOpen(line)
        assertNotNull(result, "AdMob rewarded callback must match")
        assertEquals("AdMob", result.sig.sdk)
        assertEquals(
            EventType.REWARDED_VIDEO, result.resolvedType,
            "onUserEarnedReward must resolve to REWARDED_VIDEO (not INTERSTITIAL defaultType)",
        )
    }

    @Test
    fun `AdMob interstitial line still classifies as INTERSTITIAL after rewarded patterns added`() {
        // Regression: the Sprint 2b additions must NOT intercept legacy
        // interstitial show callbacks. `onAdShown` is one of AdMob's
        // existing INTERSTITIAL patterns and must continue resolving to it.
        val line = lineFor(tag = "Ads", msg = "onAdShown adUnit=foo")
        val result = SdkSignatureCatalog.matchOpen(line)
        assertNotNull(result, "AdMob interstitial callback must still match")
        assertEquals("AdMob", result.sig.sdk)
        assertEquals(EventType.INTERSTITIAL, result.resolvedType)
    }

    @Test
    fun `IronSource rewarded line classifies as REWARDED_VIDEO`() {
        val line = lineFor(tag = "IronSource", msg = "rewardedVideoDidOpen instanceId=7")
        val result = SdkSignatureCatalog.matchOpen(line)
        assertNotNull(result, "IronSource rewarded callback must match")
        assertEquals("IronSource", result.sig.sdk)
        assertEquals(EventType.REWARDED_VIDEO, result.resolvedType)
    }

    @Test
    fun `AppLovin rewarded line classifies as REWARDED_VIDEO`() {
        val line = lineFor(tag = "AppLovinSdk", msg = "onRewardedVideoStarted adUnitId=ca-app-xxx")
        val result = SdkSignatureCatalog.matchOpen(line)
        assertNotNull(result, "AppLovin rewarded callback must match")
        assertEquals("AppLovin", result.sig.sdk)
        assertEquals(EventType.REWARDED_VIDEO, result.resolvedType)
    }

    @Test
    fun `Meta Audience rewarded line classifies as REWARDED_VIDEO`() {
        val line = lineFor(tag = "FBAudienceNetworkLog", msg = "onRewardedVideoCompleted placementId=1234")
        val result = SdkSignatureCatalog.matchOpen(line)
        assertNotNull(result, "Meta rewarded callback must match")
        assertEquals("Meta Audience Network", result.sig.sdk)
        assertEquals(EventType.REWARDED_VIDEO, result.resolvedType)
    }

    @Test
    fun `Meta Audience interstitial line still classifies as INTERSTITIAL after rewarded patterns added`() {
        // Regression: Meta's existing INTERSTITIAL show pattern stays.
        val line = lineFor(tag = "FBAudienceNetworkLog", msg = "Interstitial impression logged")
        val result = SdkSignatureCatalog.matchOpen(line)
        assertNotNull(result)
        assertEquals("Meta Audience Network", result.sig.sdk)
        assertEquals(EventType.INTERSTITIAL, result.resolvedType)
    }

    // ═══════ Sprint 5 — RATE_US (Google Play In-App Review API) ═══════
    //
    // Single catalog entry for the Google Play In-App Review API. RATE_US is
    // an instantaneous event (no natural close on the logcat side, similar to
    // SDK_INIT), so `closePatterns` is empty and the catalog invariant test
    // tolerates it. Patterns are best-effort from the public Google Play Core
    // Library docs and should be refined post empirical capture.
    //
    // Spec refs: ESC-RATE-001 (RATE_US signature), ESC-RATE-002 (tag-allowlist
    // guards against generic "Review" false-positives), ESC-RATE-003 (regression
    // — Sprint 1 catalog ordering preserved), ESC-RATE-004 (ReportGenerator
    // renders the label without an `else` fallback — already wired by Sprint 0).

    @Test
    fun `RATE_US signature matches ReviewManager launchReviewFlow log line`() {
        val line = lineFor(tag = "PlayCore", msg = "ReviewManager.launchReviewFlow(activity, info)")
        val result = SdkSignatureCatalog.matchOpen(line)
        assertNotNull(result, "ReviewManager.launchReviewFlow log must match")
        assertEquals("Google Play In-App Review", result.sig.sdk)
        assertEquals(EventType.RATE_US, result.resolvedType)
    }

    @Test
    fun `RATE_US signature matches requestReviewFlow log line`() {
        val line = lineFor(tag = "ReviewManager", msg = "requestReviewFlow: starting request")
        val result = SdkSignatureCatalog.matchOpen(line)
        assertNotNull(result, "requestReviewFlow log must match")
        assertEquals("Google Play In-App Review", result.sig.sdk)
        assertEquals(EventType.RATE_US, result.resolvedType)
    }

    @Test
    fun `RATE_US matches ReviewActivity component via matchActivity`() {
        val sig = SdkSignatureCatalog.matchActivity(
            "com.example.game/com.google.android.play.core.review.ReviewActivity",
        )
        assertNotNull(sig, "ReviewActivity component must resolve")
        assertEquals("Google Play In-App Review", sig.sdk)
        assertEquals(EventType.RATE_US, sig.defaultType)
    }

    @Test
    fun `RATE_US rejects generic Review token on unrelated tag (tag-allowlist)`() {
        // A random component or message containing "Review" on a tag that is
        // NOT in the In-App Review allowlist must NOT match RATE_US. This
        // guards against false-positives from app-internal review screens.
        val rogue = lineFor(tag = "Unrelated", msg = "User opened the Review screen")
        assertNull(SdkSignatureCatalog.matchOpen(rogue))
    }

    @Test
    fun `existing SDK signatures still match after Sprint 5 catalog growth`() {
        // Regression: the matchOpen first-match-wins linear scan must still
        // resolve to the canonical entry for each pre-existing SDK after the
        // RATE_US entry was added. Guards against accidental ordering breakage.
        data class Fixture(val tag: String, val msg: String, val expectedSdk: String, val expectedType: EventType)
        val fixtures = listOf(
            Fixture("AdActivity", "Showing ad", "AdMob", EventType.INTERSTITIAL),
            Fixture("UnityAds", "UnityAdsShowStart placement=rewarded", "Unity Ads", EventType.REWARDED_VIDEO),
            Fixture("IronSource", "interstitialDidOpen instanceId=1", "IronSource", EventType.INTERSTITIAL),
            Fixture("AppLovinSdk", "onAdDisplayed adUnitId=xxx", "AppLovin", EventType.INTERSTITIAL),
            Fixture("FBAudienceNetworkLog", "Interstitial impression logged", "Meta Audience Network", EventType.INTERSTITIAL),
            Fixture("BillingClient", "launchBillingFlow starting", "Google Play Billing", EventType.IAP),
            Fixture("Unity", "Loading scene Foo", "Unity Engine", EventType.LOADING),
            Fixture("UE4", "LogStreaming: Loading package /Game/Maps/X", "Unreal Engine", EventType.LOADING),
            Fixture("cocos2d", "Director::replaceScene to GameScene", "Cocos2d", EventType.LOADING),
            Fixture("FirebaseApp", "FirebaseApp initialization successful", "Firebase Init", EventType.SDK_INIT),
            Fixture("ActivityManager", "am_anr: pid 1234", "System ANR", EventType.ANR),
        )
        for (f in fixtures) {
            val line = lineFor(tag = f.tag, msg = f.msg)
            val result = SdkSignatureCatalog.matchOpen(line)
            assertNotNull(result, "${f.expectedSdk}: '${f.msg}' on tag=${f.tag} stopped matching after Sprint 5")
            assertEquals(
                f.expectedSdk, result.sig.sdk,
                "${f.expectedSdk}: matched the wrong SDK (got ${result.sig.sdk}) — ordering broke after Sprint 5",
            )
            assertEquals(
                f.expectedType, result.resolvedType,
                "${f.expectedSdk}: resolved type drifted after Sprint 5",
            )
        }
    }

    @Test
    fun `RATE_US is an instantaneous event with empty closePatterns`() {
        // RATE_US is a point event — there is no natural close signal on the
        // logcat side. The catalog invariant test tolerates empty
        // closePatterns for SDK_INIT and RATE_US entries; this test pins the
        // contract explicitly for RATE_US so a future refactor doesn't
        // silently introduce a stray close pattern.
        val sig = SdkSignatureCatalog.ALL.first { it.sdk == "Google Play In-App Review" }
        assertEquals(EventType.RATE_US, sig.defaultType)
        assertTrue(sig.closePatterns.isEmpty(), "RATE_US must remain instantaneous (no closePatterns)")
    }

    // ═══════ helpers ═══════

    private fun assertFixtureProducesOpenAndClose(fixture: String, expectedSdk: String) {
        val sig = SdkSignatureCatalog.ALL.first { it.sdk == expectedSdk }
        val lines = readFixture(fixture)
        var openHit = false
        var closeHit = false
        for (raw in lines) {
            val parsed = LogcatLineParser.parse(raw) ?: continue
            if (!openHit) {
                val open = SdkSignatureCatalog.matchOpen(parsed)
                if (open != null && open.sig.sdk == expectedSdk) {
                    openHit = true
                    continue
                }
            }
            if (openHit && !closeHit) {
                if (SdkSignatureCatalog.matchClose(parsed, sig) != null) {
                    closeHit = true
                }
            }
        }
        assertTrue(openHit, "$fixture: no open match for $expectedSdk")
        assertTrue(closeHit, "$fixture: no close match for $expectedSdk after open")
    }

    private fun readFixture(resourcePath: String): List<String> {
        val stream = javaClass.classLoader.getResourceAsStream(resourcePath)
            ?: error("missing fixture: $resourcePath")
        return BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).useLines {
            it.toList()
        }
    }

    private fun lineFor(tag: String, msg: String): LogLine =
        LogLine(tsMs = 0L, pid = 1234, tid = 5678, level = 'I', tag = tag, msg = msg)
}
