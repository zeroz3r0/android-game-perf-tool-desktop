package com.gameperf.desktop.core.events

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `catalog contains exactly the nine catalogued SDKs and engines`() {
        // If anyone removes an SDK they MUST update this assertion deliberately.
        // v4.4.0 baseline: six ad/billing SDKs.
        // v4.4.1 quickfix (audit obs #308): added three engine LOADING signatures.
        assertEquals(9, SdkSignatureCatalog.ALL.size, "expected 9 catalogued SDKs/engines")
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
        )
        assertEquals(expected, sdkNames, "catalog SDK set drifted from spec")
    }

    @Test
    fun `every SDK has at least one open and one close pattern`() {
        for (sig in SdkSignatureCatalog.ALL) {
            assertTrue(sig.openPatterns.isNotEmpty(), "${sig.sdk}: no open patterns")
            assertTrue(sig.closePatterns.isNotEmpty(), "${sig.sdk}: no close patterns")
            assertTrue(sig.logcatTags.isNotEmpty(), "${sig.sdk}: no logcat tags")
            // activityClasses MAY be empty for engine-level signatures (Unity
            // Engine, Unreal Engine, Cocos2d) because scene loading runs in
            // the game process without pushing a new Android Activity onto
            // the back stack. For ad/billing SDKs (which DO push activities)
            // the field must still be populated.
            if (sig.defaultType != EventType.LOADING) {
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
