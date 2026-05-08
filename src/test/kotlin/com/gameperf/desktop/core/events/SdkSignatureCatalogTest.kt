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
    fun `catalog contains exactly the six initial SDKs`() {
        // If anyone removes an SDK they MUST update this assertion deliberately.
        assertEquals(6, SdkSignatureCatalog.ALL.size, "expected 6 verified SDKs")
        val sdkNames = SdkSignatureCatalog.ALL.map { it.sdk }.toSet()
        val expected = setOf(
            "AdMob",
            "Unity Ads",
            "IronSource",
            "AppLovin",
            "Meta Audience Network",
            "Google Play Billing",
        )
        assertEquals(expected, sdkNames, "catalog SDK set drifted from spec")
    }

    @Test
    fun `every SDK has at least one open and one close pattern`() {
        for (sig in SdkSignatureCatalog.ALL) {
            assertTrue(sig.openPatterns.isNotEmpty(), "${sig.sdk}: no open patterns")
            assertTrue(sig.closePatterns.isNotEmpty(), "${sig.sdk}: no close patterns")
            assertTrue(sig.activityClasses.isNotEmpty(), "${sig.sdk}: no activity classes")
            assertTrue(sig.logcatTags.isNotEmpty(), "${sig.sdk}: no logcat tags")
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
        assertEquals("AdMob", matched.first.sdk)
        assertEquals(EventType.INTERSTITIAL, matched.first.type)

        val close = lineFor(tag = "AdActivity", msg = "Ad dismissed by user")
        assertNotNull(SdkSignatureCatalog.matchClose(close, matched.first))

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
        assertEquals("Unity Ads", matched.first.sdk)
        assertEquals(EventType.REWARDED_VIDEO, matched.first.type)

        val close = lineFor(tag = "UnityAds", msg = "UnityAdsShowComplete state=COMPLETED")
        assertNotNull(SdkSignatureCatalog.matchClose(close, matched.first))

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
        assertEquals("IronSource", matched.first.sdk)

        val close = lineFor(tag = "IronSource", msg = "interstitialDidClose instanceId=42")
        assertNotNull(SdkSignatureCatalog.matchClose(close, matched.first))

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
        assertEquals("AppLovin", matched.first.sdk)

        val close = lineFor(tag = "AppLovinSdk", msg = "onAdHidden adUnitId=ca-app-xxx")
        assertNotNull(SdkSignatureCatalog.matchClose(close, matched.first))

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
        assertEquals("Meta Audience Network", matched.first.sdk)

        val close = lineFor(tag = "FBAudienceNetworkLog", msg = "onInterstitialDismissed event=user")
        assertNotNull(SdkSignatureCatalog.matchClose(close, matched.first))

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
        assertEquals("Google Play Billing", matched.first.sdk)
        assertEquals(EventType.IAP, matched.first.type)

        val close = lineFor(tag = "BillingClient", msg = "onPurchasesUpdated: result=OK")
        assertNotNull(SdkSignatureCatalog.matchClose(close, matched.first))

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
            match.first.sdk == sig.sdk
        }
        assertTrue(openMatched, "play-billing-launch.log fixture had no open match")
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
                if (open != null && open.first.sdk == expectedSdk) {
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
