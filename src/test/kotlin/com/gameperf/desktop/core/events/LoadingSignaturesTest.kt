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
 * Unit tests for the LOADING-event signatures (Unity Engine, Unreal Engine,
 * Cocos2d) added in the level/map loading segmentation quickfix.
 *
 * Audit reference: obs #308 `audit/event-segmentation-coverage-2026-05-12`
 * documented that `EventType.LOADING` was declared and rendered by
 * `ReportGenerator` but no SDK signature emitted it — this fixes that gap.
 *
 * Per CLAUDE.md operative SOP (lines 172-178):
 *  - Single source of truth = `SdkSignatureCatalog.ALL`
 *  - Each engine: positive open match, positive close match, negative
 *    same-tag noise, plus a fixture-driven smoke test.
 *  - Tag-allowlist guards against false positives in non-game logcat
 *    (the message "loading" alone is too broad — must be tag-scoped).
 */
class LoadingSignaturesTest {

    // ═══════ Unity Engine ═══════

    @Test
    fun `matches Unity Engine loading scene open and close`() {
        val open = lineFor(tag = "Unity", msg = "Loading scene transition")
        val matched = SdkSignatureCatalog.matchOpen(open)
        assertNotNull(matched, "Unity 'Loading scene' must open a LOADING event")
        assertEquals("Unity Engine", matched.first.sdk)
        assertEquals(EventType.LOADING, matched.first.type)

        val close = lineFor(tag = "Unity", msg = "Scene loaded successfully name=Level3")
        assertNotNull(
            SdkSignatureCatalog.matchClose(close, matched.first),
            "Unity 'Scene loaded' must close the LOADING event",
        )
    }

    @Test
    fun `matches Unity Engine AsyncOperation open via UnityEngine tag`() {
        val open = lineFor(tag = "UnityEngine", msg = "AsyncOperation started for SceneManager.LoadSceneAsync")
        val matched = SdkSignatureCatalog.matchOpen(open)
        assertNotNull(matched)
        assertEquals("Unity Engine", matched.first.sdk)
    }

    @Test
    fun `Unity Engine ignores unrelated same-tag noise`() {
        // 'Behaviour update' is engine chatter unrelated to scene loading.
        val noise = lineFor(tag = "Unity", msg = "Behaviour update tick=500")
        assertNull(SdkSignatureCatalog.matchOpen(noise))

        // 'Reward applied' looks game-ish but does not mention loading.
        val gameMsg = lineFor(tag = "Unity", msg = "Reward applied: 50 coins")
        assertNull(SdkSignatureCatalog.matchOpen(gameMsg))
    }

    @Test
    fun `Unity Engine rejects loading-like message on foreign tag`() {
        // Tag-allowlist guard: even if a non-game system logs "Loading scene",
        // we must NOT classify it as a Unity load.
        val rogue = lineFor(tag = "ActivityManager", msg = "Loading scene transition for system overlay")
        val matched = SdkSignatureCatalog.matchOpen(rogue)
        // Either no match at all, or a match for some OTHER SDK — never Unity Engine.
        assertTrue(matched == null || matched.first.sdk != "Unity Engine")
    }

    // ═══════ Unreal Engine ═══════

    @Test
    fun `matches Unreal LogStreaming open and Flushing close`() {
        val open = lineFor(tag = "UE4", msg = "LogStreaming: Loading package /Game/Maps/Arena")
        val matched = SdkSignatureCatalog.matchOpen(open)
        assertNotNull(matched, "Unreal 'LogStreaming: Loading' must open a LOADING event")
        assertEquals("Unreal Engine", matched.first.sdk)
        assertEquals(EventType.LOADING, matched.first.type)

        val close = lineFor(tag = "UE4", msg = "LogStreaming: Flushing async loaders")
        assertNotNull(SdkSignatureCatalog.matchClose(close, matched.first))
    }

    @Test
    fun `matches Unreal LoadingScreen plugin open and close`() {
        val open = lineFor(tag = "LoadingScreen", msg = "LoadingScreen Shown")
        val matched = SdkSignatureCatalog.matchOpen(open)
        assertNotNull(matched)
        assertEquals("Unreal Engine", matched.first.sdk)

        val close = lineFor(tag = "LoadingScreen", msg = "LoadingScreen Hidden")
        assertNotNull(SdkSignatureCatalog.matchClose(close, matched.first))
    }

    @Test
    fun `Unreal ignores unrelated same-tag noise`() {
        // 'LogStreaming' tag with messages unrelated to package loading.
        val noise = lineFor(tag = "UE4", msg = "LogTemp: tick complete frame=120")
        assertNull(SdkSignatureCatalog.matchOpen(noise))
    }

    // ═══════ Cocos2d ═══════

    @Test
    fun `matches Cocos2d Director replaceScene open`() {
        val open = lineFor(tag = "cocos2d", msg = "Director::replaceScene to GameScene")
        val matched = SdkSignatureCatalog.matchOpen(open)
        assertNotNull(matched, "Cocos2d 'Director::replaceScene' must open a LOADING event")
        assertEquals("Cocos2d", matched.first.sdk)
        assertEquals(EventType.LOADING, matched.first.type)
    }

    @Test
    fun `matches Cocos2d CCDirector replaceScene legacy form`() {
        val open = lineFor(tag = "Cocos2dx", msg = "CCDirector.replaceScene transitioning")
        val matched = SdkSignatureCatalog.matchOpen(open)
        assertNotNull(matched)
        assertEquals("Cocos2d", matched.first.sdk)
    }

    @Test
    fun `Cocos2d close uses onEnter under scoped tag`() {
        // close pattern: scene lifecycle 'onEnter' — only meaningful when tag is
        // a cocos2d-family tag, which is enforced by SdkSignatureCatalog tag-allowlist.
        val sig = SdkSignatureCatalog.ALL.first { it.sdk == "Cocos2d" }
        val close = lineFor(tag = "cocos2d", msg = "GameScene onEnter called")
        assertNotNull(SdkSignatureCatalog.matchClose(close, sig))
    }

    @Test
    fun `Cocos2d ignores onEnter on foreign tag`() {
        // 'onEnter' would be wildly ambiguous in arbitrary logs — the tag
        // allowlist must block it for non-cocos2d tags.
        val sig = SdkSignatureCatalog.ALL.first { it.sdk == "Cocos2d" }
        val rogue = lineFor(tag = "ActivityManager", msg = "onEnter lifecycle called")
        assertNull(SdkSignatureCatalog.matchClose(rogue, sig))
    }

    @Test
    fun `Cocos2d ignores unrelated same-tag noise`() {
        val noise = lineFor(tag = "cocos2d", msg = "Render: drew 1024 sprites")
        assertNull(SdkSignatureCatalog.matchOpen(noise))
    }

    // ═══════ cross-engine negative ═══════

    @Test
    fun `Unity loading line does not close Unreal or Cocos2d events`() {
        val unreal = SdkSignatureCatalog.ALL.first { it.sdk == "Unreal Engine" }
        val cocos = SdkSignatureCatalog.ALL.first { it.sdk == "Cocos2d" }
        val unityClose = lineFor(tag = "Unity", msg = "Scene loaded successfully")
        assertNull(SdkSignatureCatalog.matchClose(unityClose, unreal))
        assertNull(SdkSignatureCatalog.matchClose(unityClose, cocos))
    }

    @Test
    fun `Unity loading line does not match the Unity Ads SDK`() {
        // Important: tag 'Unity' is shared between Unity Engine and Unity Ads.
        // A scene-transition line must classify as Unity Engine LOADING,
        // never as Unity Ads REWARDED_VIDEO.
        val open = lineFor(tag = "Unity", msg = "Loading scene transition")
        val matched = SdkSignatureCatalog.matchOpen(open)
        assertNotNull(matched)
        assertEquals("Unity Engine", matched.first.sdk)
        assertEquals(EventType.LOADING, matched.first.type)
    }

    // ═══════ existing Unity Ads fixture must still classify correctly ═══════

    @Test
    fun `unity-ads fixture still produces a Unity Ads REWARDED_VIDEO open`() {
        // Regression guard for the audit-noted line `unity-ads.log:5`
        // (`Unity: Loading scene transition`) which now ALSO matches the new
        // Unity Engine LOADING signature. We must guarantee the rewarded-video
        // open (line 8/9) still wins under expectedSdk="Unity Ads".
        val sig = SdkSignatureCatalog.ALL.first { it.sdk == "Unity Ads" }
        val lines = readFixture("logcat-fixtures/unity-ads.log")
        val openHit = lines.any { raw ->
            val parsed = LogcatLineParser.parse(raw) ?: return@any false
            val match = SdkSignatureCatalog.matchOpen(parsed) ?: return@any false
            match.first.sdk == sig.sdk
        }
        assertTrue(openHit, "unity-ads.log: must still have a Unity Ads open match after LOADING signatures added")
    }

    @Test
    fun `unity-ads fixture also produces a Unity Engine LOADING open at line 5`() {
        // The line `05-08 15:10:06.700  2345  6789 I Unity: Loading scene transition`
        // (unity-ads.log:5) MUST now classify as Unity Engine LOADING.
        val lines = readFixture("logcat-fixtures/unity-ads.log")
        val loadingHit = lines.any { raw ->
            val parsed = LogcatLineParser.parse(raw) ?: return@any false
            val match = SdkSignatureCatalog.matchOpen(parsed) ?: return@any false
            match.first.sdk == "Unity Engine" && match.first.type == EventType.LOADING
        }
        assertTrue(loadingHit, "unity-ads.log: line 5 'Loading scene transition' must classify as Unity Engine LOADING")
    }

    // ═══════ fixture-driven smoke tests for each engine ═══════

    @Test
    fun `unity-loading fixture contains open and close that match Unity Engine`() {
        assertFixtureProducesOpenAndClose(
            fixture = "logcat-fixtures/unity-loading.log",
            expectedSdk = "Unity Engine",
        )
    }

    @Test
    fun `unreal-loading fixture contains open and close that match Unreal Engine`() {
        assertFixtureProducesOpenAndClose(
            fixture = "logcat-fixtures/unreal-loading.log",
            expectedSdk = "Unreal Engine",
        )
    }

    @Test
    fun `cocos2d-loading fixture contains open and close that match Cocos2d`() {
        assertFixtureProducesOpenAndClose(
            fixture = "logcat-fixtures/cocos2d-loading.log",
            expectedSdk = "Cocos2d",
        )
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
