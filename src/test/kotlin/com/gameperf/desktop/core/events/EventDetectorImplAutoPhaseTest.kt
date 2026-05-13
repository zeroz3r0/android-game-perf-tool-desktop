package com.gameperf.desktop.core.events

import com.gameperf.desktop.testing.FakeAdbBridge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 3 — Auto-phase secondary-event emission tests for
 * [EventDetectorImpl] (auto-phase-detection-from-engine-logs).
 *
 * Spec coverage:
 *  - AUTO-003 / AUTO-004 Unity + Unreal scene-name capture → classified
 *    secondary event.
 *  - AUTO-009 graceful degradation (obfuscated / unknown scene → LOADING
 *    only).
 *  - AUTO-008 INSTRUMENTED upgrade rule (Phase 4 — appended below in the
 *    same file because it shares the test harness).
 *
 * Drives the state machine directly via [EventDetectorImpl.handleLogLine]
 * (the same hook used by `EventDetectorImplTest`).
 */
class EventDetectorImplAutoPhaseTest {

    /** Standard test detector with foreground guard primed. */
    private fun newDetector(nowMs: Long): EventDetectorImpl {
        val det = EventDetectorImpl(
            bridge = FakeAdbBridge(),
            timeProvider = { nowMs },
        )
        det.setLastGameForegroundForTest(nowMs)
        return det
    }

    // ──────────────────── Phase 3 — Unity ────────────────────

    @Test
    fun `Unity LOADING with Boss_Arena scene emits both LOADING and COMBAT_PHASE`() {
        val det = newDetector(1_000L)

        det.handleLogLine(
            LogLine(
                tsMs = 1_000L, pid = 1, tid = 1, level = 'I',
                tag = "Unity",
                msg = "Loading scene: Boss_Arena_01",
            )
        )

        val events = det.events.value
        assertEquals(2, events.size, "must emit LOADING + COMBAT_PHASE")
        assertEquals(EventType.LOADING, events[0].type)
        assertEquals(EventType.COMBAT_PHASE, events[1].type)
        assertEquals(Confidence.MEDIUM, events[1].confidence, "AUTO is MEDIUM (D3)")
        assertEquals("Unity auto-phase", events[1].sdkSource)
        assertEquals("Unity Engine", events[0].sdkSource)
    }

    @Test
    fun `Unity LOADING with obfuscated scene name emits LOADING only`() {
        val det = newDetector(1_000L)

        det.handleLogLine(
            LogLine(
                tsMs = 1_000L, pid = 1, tid = 1, level = 'I',
                tag = "Unity",
                msg = "Loading scene: s001",
            )
        )

        val events = det.events.value
        assertEquals(1, events.size, "obfuscated scene → LOADING only (AUTO-009)")
        assertEquals(EventType.LOADING, events[0].type)
    }

    @Test
    fun `Unity LOADING with MainMenu scene emits LOADING plus MENU_NAV`() {
        val det = newDetector(1_000L)

        det.handleLogLine(
            LogLine(
                tsMs = 1_000L, pid = 1, tid = 1, level = 'I',
                tag = "Unity",
                msg = "Loading scene: MainMenu",
            )
        )

        val events = det.events.value
        assertEquals(2, events.size)
        assertEquals(EventType.LOADING, events[0].type)
        assertEquals(EventType.MENU_NAV, events[1].type)
        assertEquals(Confidence.MEDIUM, events[1].confidence)
    }

    @Test
    fun `Unity close-only Scene loaded line emits AUTO MENU_NAV standalone`() {
        // AUTO-003: scenePattern captures from both "Loading scene:" and
        // "Scene loaded successfully name=". The standalone case (no prior
        // open LOADING) still emits the AUTO phase event via the
        // fall-through scene-capture branch.
        val det = newDetector(1_000L)

        det.handleLogLine(
            LogLine(
                tsMs = 1_000L, pid = 1, tid = 1, level = 'I',
                tag = "UnityEngine",
                msg = "Scene loaded successfully name=MainMenu",
            )
        )

        val events = det.events.value
        assertEquals(1, events.size, "close-only line → standalone AUTO event")
        assertEquals(EventType.MENU_NAV, events[0].type)
    }

    @Test
    fun `Unity LOADING with ad-mediation scene name emits LOADING only (AUTO-010)`() {
        val det = newDetector(1_000L)

        det.handleLogLine(
            LogLine(
                tsMs = 1_000L, pid = 1, tid = 1, level = 'I',
                tag = "Unity",
                msg = "Loading scene: MainMenuAdLayout",
            )
        )

        val events = det.events.value
        assertEquals(1, events.size, "ad-mediation scene must NOT spawn MENU_NAV")
        assertEquals(EventType.LOADING, events[0].type)
    }

    // ──────────────────── Phase 3 — Unreal ────────────────────

    @Test
    fun `Unreal LOADING with Tutorial map emits LOADING plus TUTORIAL_PHASE`() {
        val det = newDetector(2_000L)

        det.handleLogLine(
            LogLine(
                tsMs = 2_000L, pid = 1, tid = 1, level = 'I',
                tag = "LogStreaming",
                msg = "LogStreaming: Loading package /Game/Maps/Tutorial_01",
            )
        )

        val events = det.events.value
        assertEquals(2, events.size)
        assertEquals(EventType.LOADING, events[0].type)
        assertEquals(EventType.TUTORIAL_PHASE, events[1].type)
        assertEquals("Unreal Engine", events[0].sdkSource)
        assertEquals("Unreal auto-phase", events[1].sdkSource)
        assertEquals(Confidence.MEDIUM, events[1].confidence)
    }

    @Test
    fun `Unreal LOADING with xyz_obfuscated map emits LOADING only`() {
        val det = newDetector(2_000L)

        det.handleLogLine(
            LogLine(
                tsMs = 2_000L, pid = 1, tid = 1, level = 'I',
                tag = "LogStreaming",
                msg = "LogStreaming: Loading package /Game/Maps/xyz_obfuscated",
            )
        )

        val events = det.events.value
        assertEquals(1, events.size, "no keyword match → LOADING only")
        assertEquals(EventType.LOADING, events[0].type)
    }

    @Test
    fun `Unreal LOADING with Cinematic_Intro package emits LOADING plus CUTSCENE`() {
        val det = newDetector(2_000L)

        det.handleLogLine(
            LogLine(
                tsMs = 2_000L, pid = 1, tid = 1, level = 'I',
                tag = "LogStreaming",
                msg = "LogStreaming: Loading package /Game/Maps/Cinematic_Intro",
            )
        )

        val events = det.events.value
        assertEquals(2, events.size)
        assertEquals(EventType.LOADING, events[0].type)
        assertEquals(EventType.CUTSCENE, events[1].type)
    }

    @Test
    fun `Unreal standalone TravelTo without LOADING still emits AUTO phase`() {
        // TravelTo form does NOT match any Unreal openPattern, so no LOADING
        // event fires. The fall-through scene-capture branch should still
        // emit the AUTO phase event so games that only emit TravelTo are
        // covered.
        val det = newDetector(2_000L)

        det.handleLogLine(
            LogLine(
                tsMs = 2_000L, pid = 1, tid = 1, level = 'I',
                tag = "Unreal",
                msg = "LogLevelSwitch: TravelTo Cinematic_Intro",
            )
        )

        val events = det.events.value
        assertEquals(1, events.size, "TravelTo standalone → AUTO event only")
        assertEquals(EventType.CUTSCENE, events[0].type)
    }

    // ──────────────────── Phase 4 — AUTO-008 INSTRUMENTED upgrade ────────────────────

    @Test
    fun `AUTO-008 INSTRUMENTED COMBAT within 1000ms replaces AUTO COMBAT_PHASE`() {
        // Use advancing clock so timeProvider() reflects the second event time.
        var current = 0L
        val det = EventDetectorImpl(
            bridge = FakeAdbBridge(),
            timeProvider = { current },
        )
        det.setLastGameForegroundForTest(0L)

        // 1) AUTO COMBAT_PHASE via Unity scene load at t=0.
        det.handleLogLine(
            LogLine(
                tsMs = 0L, pid = 1, tid = 1, level = 'I',
                tag = "Unity",
                msg = "Loading scene: BossArena",
            )
        )
        // Sanity: LOADING + COMBAT_PHASE present.
        assertEquals(2, det.events.value.size)
        assertEquals(EventType.COMBAT_PHASE, det.events.value[1].type)

        // 2) INSTRUMENTED COMBAT.Start at t=500ms (within 1000ms window).
        current = 500L
        det.handleLogLine(
            LogLine(
                tsMs = 500L, pid = 1, tid = 1, level = 'I',
                tag = "GamePerf",
                msg = "COMBAT.Start",
            )
        )

        // Final list should have LOADING + INSTRUMENTED (the AUTO COMBAT_PHASE
        // was upgraded away). The replacement preserves chronological order.
        val finalEvents = det.events.value
        assertEquals(2, finalEvents.size, "AUTO replaced by INSTRUMENTED")
        assertTrue(
            finalEvents.any { it.type == EventType.LOADING },
            "LOADING must survive",
        )
        assertTrue(
            finalEvents.any { it.type == EventType.INSTRUMENTED },
            "INSTRUMENTED must be present",
        )
        assertTrue(
            finalEvents.none { it.type == EventType.COMBAT_PHASE },
            "AUTO COMBAT_PHASE must have been replaced",
        )
    }

    @Test
    fun `AUTO-008 INSTRUMENTED COMBAT outside 1000ms window keeps both events`() {
        var current = 0L
        val det = EventDetectorImpl(
            bridge = FakeAdbBridge(),
            timeProvider = { current },
        )
        det.setLastGameForegroundForTest(0L)

        // 1) AUTO COMBAT_PHASE at t=0.
        det.handleLogLine(
            LogLine(
                tsMs = 0L, pid = 1, tid = 1, level = 'I',
                tag = "Unity",
                msg = "Loading scene: BossArena",
            )
        )
        assertEquals(2, det.events.value.size)

        // 2) INSTRUMENTED COMBAT.Start at t=1500ms (OUTSIDE 1000ms window).
        current = 1_500L
        det.handleLogLine(
            LogLine(
                tsMs = 1_500L, pid = 1, tid = 1, level = 'I',
                tag = "GamePerf",
                msg = "COMBAT.Start",
            )
        )

        val finalEvents = det.events.value
        assertEquals(3, finalEvents.size, "outside window: both events kept")
        assertNotNull(finalEvents.find { it.type == EventType.LOADING })
        assertNotNull(finalEvents.find { it.type == EventType.COMBAT_PHASE })
        assertNotNull(finalEvents.find { it.type == EventType.INSTRUMENTED })
    }

    @Test
    fun `AUTO-008 INSTRUMENTED non-matching tag does not upgrade AUTO COMBAT_PHASE`() {
        var current = 0L
        val det = EventDetectorImpl(
            bridge = FakeAdbBridge(),
            timeProvider = { current },
        )
        det.setLastGameForegroundForTest(0L)

        // AUTO COMBAT_PHASE.
        det.handleLogLine(
            LogLine(
                tsMs = 0L, pid = 1, tid = 1, level = 'I',
                tag = "Unity", msg = "Loading scene: BossArena",
            )
        )
        assertEquals(2, det.events.value.size)

        // INSTRUMENTED TUTORIAL.Start at t=500ms — different tag, must NOT
        // replace the AUTO COMBAT_PHASE.
        current = 500L
        det.handleLogLine(
            LogLine(
                tsMs = 500L, pid = 1, tid = 1, level = 'I',
                tag = "GamePerf", msg = "TUTORIAL.Start",
            )
        )

        val finalEvents = det.events.value
        assertEquals(3, finalEvents.size, "different-type INSTRUMENTED does not replace")
        assertNotNull(finalEvents.find { it.type == EventType.COMBAT_PHASE })
        assertNotNull(finalEvents.find { it.type == EventType.INSTRUMENTED })
    }
}
