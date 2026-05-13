package com.gameperf.desktop.core.devactions

import com.gameperf.desktop.core.events.Confidence as EventConfidence
import com.gameperf.desktop.core.events.DetectedEvent
import com.gameperf.desktop.core.events.EventType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for `GameEngineDetector` — Sprint 2 of `dev-action-brief`.
 *
 * Spec: DAB-006 (engine detection from already-captured events).
 * Design: ADR-3 — frequency rank, tie-break by greatest `startMs`,
 * fallback to `GameEngine.GENERIC` when no engine events are present.
 *
 * Source SDK names ("Unity Engine", "Unreal Engine", "Cocos2d") MUST
 * match the `sdkSource` values produced by `SdkSignatureCatalog` LOADING
 * signatures (commit 7116786) — Sprint 2 adds ZERO new SDK signatures.
 *
 * @since v4.5.0
 */
class GameEngineDetectorTest {

    // ────────────────────────────────────────────────────────────────────
    // Fallbacks
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `DAB-006 - empty event list returns GENERIC`() {
        val engine = GameEngineDetector.detect(emptyList())
        assertEquals(GameEngine.GENERIC, engine)
    }

    @Test
    fun `DAB-006 - events with only non-engine sdkSource return GENERIC`() {
        val events = listOf(
            event(sdkSource = "AdMob", startMs = 1_000L, type = EventType.INTERSTITIAL),
            event(sdkSource = "Unity Ads", startMs = 2_000L, type = EventType.REWARDED_VIDEO),
            event(sdkSource = "Google Play Billing", startMs = 3_000L, type = EventType.IAP),
        )
        assertEquals(GameEngine.GENERIC, GameEngineDetector.detect(events))
    }

    // ────────────────────────────────────────────────────────────────────
    // Single-engine fixtures
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `DAB-006 - single Unity Engine event returns UNITY`() {
        val events = listOf(
            event(sdkSource = "Unity Engine", startMs = 1_000L, type = EventType.LOADING),
        )
        assertEquals(GameEngine.UNITY, GameEngineDetector.detect(events))
    }

    @Test
    fun `DAB-006 - single Unreal Engine event returns UNREAL`() {
        val events = listOf(
            event(sdkSource = "Unreal Engine", startMs = 1_000L, type = EventType.LOADING),
        )
        assertEquals(GameEngine.UNREAL, GameEngineDetector.detect(events))
    }

    @Test
    fun `DAB-006 - single Cocos2d event returns COCOS2D`() {
        val events = listOf(
            event(sdkSource = "Cocos2d", startMs = 1_000L, type = EventType.LOADING),
        )
        assertEquals(GameEngine.COCOS2D, GameEngineDetector.detect(events))
    }

    // ────────────────────────────────────────────────────────────────────
    // Frequency ranking
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `DAB-006 - majority Unity events win against one Unreal event`() {
        val events = listOf(
            event(sdkSource = "Unity Engine", startMs = 1_000L, type = EventType.LOADING),
            event(sdkSource = "Unity Engine", startMs = 2_000L, type = EventType.LOADING),
            event(sdkSource = "Unity Engine", startMs = 3_000L, type = EventType.LOADING),
            event(sdkSource = "Unreal Engine", startMs = 4_000L, type = EventType.LOADING),
        )
        // Unity 3 vs Unreal 1 — frequency wins regardless of Unreal being most recent.
        assertEquals(GameEngine.UNITY, GameEngineDetector.detect(events))
    }

    @Test
    fun `DAB-006 - non-engine events are ignored in the frequency rank`() {
        val events = listOf(
            event(sdkSource = "AdMob", startMs = 500L, type = EventType.INTERSTITIAL),
            event(sdkSource = "AdMob", startMs = 1_500L, type = EventType.INTERSTITIAL),
            event(sdkSource = "AdMob", startMs = 2_500L, type = EventType.INTERSTITIAL),
            event(sdkSource = "Cocos2d", startMs = 3_000L, type = EventType.LOADING),
        )
        // Three AdMob events do NOT outweigh one Cocos2d engine event.
        assertEquals(GameEngine.COCOS2D, GameEngineDetector.detect(events))
    }

    // ────────────────────────────────────────────────────────────────────
    // Tie-break by greatest startMs (design ADR-3)
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `DAB-006 - tied frequency breaks by greatest startMs - Unreal wins`() {
        val events = listOf(
            event(sdkSource = "Unity Engine", startMs = 1_000L, type = EventType.LOADING),
            event(sdkSource = "Unity Engine", startMs = 2_000L, type = EventType.LOADING),
            event(sdkSource = "Unreal Engine", startMs = 3_000L, type = EventType.LOADING),
            event(sdkSource = "Unreal Engine", startMs = 4_000L, type = EventType.LOADING),
        )
        // 2 Unity vs 2 Unreal — Unreal's latest startMs (4_000) beats Unity's (2_000).
        assertEquals(GameEngine.UNREAL, GameEngineDetector.detect(events))
    }

    @Test
    fun `DAB-006 - tied frequency breaks by greatest startMs - Cocos2d wins by recency`() {
        val events = listOf(
            event(sdkSource = "Cocos2d", startMs = 500L, type = EventType.LOADING),
            event(sdkSource = "Unity Engine", startMs = 1_000L, type = EventType.LOADING),
            event(sdkSource = "Unity Engine", startMs = 2_000L, type = EventType.LOADING),
            event(sdkSource = "Cocos2d", startMs = 9_000L, type = EventType.LOADING),
        )
        // 2 Unity vs 2 Cocos2d — Cocos2d's latest startMs (9_000) beats Unity's (2_000).
        // List position is intentionally NOT chronological — only startMs matters per ADR-3.
        assertEquals(GameEngine.COCOS2D, GameEngineDetector.detect(events))
    }

    // ────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────

    private fun event(
        sdkSource: String,
        startMs: Long,
        type: EventType,
    ): DetectedEvent = DetectedEvent(
        id = "engine-detector-test-${sdkSource.replace(' ', '-').lowercase()}-$startMs",
        type = type,
        sdkSource = sdkSource,
        startMs = startMs,
        endMs = startMs + 1_000L,
        confidence = EventConfidence.HIGH,
        signatureMatched = "test",
    )
}
