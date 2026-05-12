package com.gameperf.desktop.core.events

import com.gameperf.desktop.testing.FakeAdbBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-state-machine tests for [EventDetectorImpl]. No real ADB processes,
 * no coroutines for the unit assertions — the handlers are driven directly
 * via the `internal` test entry points.
 *
 * Covers EVT-005 (LOAD→SHOW→CLOSE lifecycle), EVT-006 (graceful close),
 * EVT-008 (foreground proximity guard), EVT-009 (event cap → histogram
 * fallback).
 */
class EventDetectorImplTest {

    private val captureScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun teardown() {
        captureScope.cancel()
    }

    /**
     * Build a detector with a manually-controlled clock and a primed
     * foreground timestamp so opens are NOT rejected by EVT-008.
     */
    private fun newDetectorAtTime(nowMs: Long): EventDetectorImpl {
        var current = nowMs
        val det = EventDetectorImpl(
            bridge = FakeAdbBridge(),
            timeProvider = { current },
        )
        det.setLastGameForegroundForTest(current)
        return det
    }

    /**
     * Build a detector whose clock advances on each call. Used for tests
     * that need close timestamps to differ from open timestamps.
     */
    private fun newDetectorWithAdvancingClock(start: Long, stepMs: Long): EventDetectorImpl {
        var current = start
        val det = EventDetectorImpl(
            bridge = FakeAdbBridge(),
            timeProvider = {
                val v = current
                current += stepMs
                v
            },
        )
        det.setLastGameForegroundForTest(start)
        return det
    }

    // ──────────────────────── EVT-005 ────────────────────────

    @Test
    fun `open then close pairs an event with endMs set`() {
        val det = newDetectorAtTime(1_000L)

        // OPEN: AdMob "Showing ad" on tag "Ads"
        det.handleLogLine(LogLine(tsMs = 1_000L, pid = 1, tid = 1, level = 'I',
            tag = "Ads", msg = "Showing ad now"))

        var events = det.events.value
        assertEquals(1, events.size, "open must add one event")
        assertNull(events[0].endMs, "open event has no endMs yet")
        assertEquals(Confidence.HIGH, events[0].confidence)
        assertEquals(EventType.INTERSTITIAL, events[0].type)
        assertEquals("AdMob", events[0].sdkSource)

        // CLOSE: AdMob "Ad dismissed"
        det.handleLogLine(LogLine(tsMs = 12_000L, pid = 1, tid = 1, level = 'I',
            tag = "Ads", msg = "Ad dismissed by user"))

        events = det.events.value
        assertEquals(1, events.size, "close must NOT add a new event")
        assertEquals(12_000L, events[0].endMs)
        assertEquals(0, det.openEventCountForTest(), "open map must be empty after close")
        assertFalse(events[0].endInferred, "explicit close → endInferred=false")
    }

    // ──────────────────────── EVT-008 ────────────────────────

    @Test
    fun `open is rejected when game has not been on top recently`() {
        val det = EventDetectorImpl(
            bridge = FakeAdbBridge(),
            timeProvider = { 10_000L },
        )
        // Game went to background long ago — well outside the 2s guard.
        det.setLastGameForegroundForTest(10_000L - 5_000L)

        det.handleLogLine(LogLine(tsMs = 10_000L, pid = 1, tid = 1, level = 'I',
            tag = "Ads", msg = "Showing ad"))

        assertEquals(
            0, det.events.value.size,
            "EVT-008 background ad reload must be rejected",
        )
    }

    @Test
    fun `open is accepted exactly at the foreground guard boundary`() {
        val det = EventDetectorImpl(
            bridge = FakeAdbBridge(),
            timeProvider = { 10_000L },
        )
        // Exactly at the boundary (2000 ms ago) — the guard is "<= 2s ago".
        det.setLastGameForegroundForTest(10_000L - EventDetectorImpl.FOREGROUND_GUARD_MS)

        det.handleLogLine(LogLine(tsMs = 10_000L, pid = 1, tid = 1, level = 'I',
            tag = "Ads", msg = "Showing ad"))

        assertEquals(1, det.events.value.size, "boundary case must be inclusive")
    }

    // ──────────────────────── EVT-006 ────────────────────────

    @Test
    fun `stop force-closes any still-open event with endInferred=true`() {
        val det = newDetectorWithAdvancingClock(start = 1_000L, stepMs = 10L)

        det.handleLogLine(LogLine(tsMs = 1_000L, pid = 1, tid = 1, level = 'I',
            tag = "Ads", msg = "Showing ad"))
        assertEquals(1, det.events.value.size)
        assertNull(det.events.value[0].endMs)

        det.stop()

        val events = det.events.value
        assertEquals(1, events.size)
        assertNotNull(events[0].endMs, "stop must synthesize endMs")
        assertTrue(events[0].endInferred, "stop must mark endInferred=true")
    }

    @Test
    fun `stop is idempotent`() {
        val det = newDetectorAtTime(1_000L)

        det.handleLogLine(LogLine(tsMs = 1_000L, pid = 1, tid = 1, level = 'I',
            tag = "Ads", msg = "Showing ad"))

        det.stop()
        val firstSize = det.events.value.size
        det.stop()  // second call — must not duplicate or throw
        assertEquals(firstSize, det.events.value.size)
    }

    // ──────────────────────── EVT-009 ────────────────────────

    @Test
    fun `event cap drops further opens and emits histogram-fallback warning`() {
        val det = newDetectorAtTime(1_000L)

        // Push MAX_EVENTS+1 unique opens via the test hook so we don't
        // depend on the real catalog's pattern variety to produce distinct
        // keys (real-world cap is rare; the test must isolate the limit).
        val cap = EventDetectorImpl.MAX_EVENTS
        for (i in 0..cap) {
            det.forceOpenForTest("synthetic-$i")
        }

        assertEquals(cap, det.events.value.size, "events list capped at MAX_EVENTS")
        assertTrue(
            det.warnings.value.any { it.contains("histograma") },
            "histogram-fallback warning must be present after cap hit",
        )
    }

    // ──────────────────────── EVT-007 ────────────────────────

    @Test
    fun `gap downgrades currently-open events to LOW confidence`() {
        val det = newDetectorAtTime(1_000L)

        det.handleLogLine(LogLine(tsMs = 1_000L, pid = 1, tid = 1, level = 'I',
            tag = "Ads", msg = "Showing ad"))
        assertEquals(Confidence.HIGH, det.events.value[0].confidence)

        det.handleGap(gapMs = 6_000L)

        assertEquals(Confidence.LOW, det.events.value[0].confidence)
        assertTrue(
            det.warnings.value.any { it.contains("Brecha de logcat") },
            "gap warning must be surfaced",
        )
    }

    // ──────────────────────── Activity-only detection ────────────────────────

    @Test
    fun `dumpsys-only path produces MEDIUM confidence event with dumpsys metadata`() {
        val det = newDetectorAtTime(5_000L)
        det.setGamePackageForTest("com.example.game")

        // Top of stack is AdMob's AdActivity hosted IN the game's own
        // process — typical for SDK-rendered interstitials. Activity-level
        // detection must still fire (ProGuard-stripped builds rely on this
        // path).
        det.handleActivityStack(
            listOf(ActivityFrame(cmp = "com.example.game/com.google.android.gms.ads.AdActivity"))
        )

        val events = det.events.value
        assertEquals(1, events.size)
        assertEquals(Confidence.MEDIUM, events[0].confidence)
        assertEquals("dumpsys", events[0].metadata["source"])
        assertEquals("AdMob", events[0].sdkSource)
    }

    @Test
    fun `activity-keyed event closes when the cmp leaves the stack`() {
        val det = newDetectorWithAdvancingClock(start = 5_000L, stepMs = 1_000L)
        det.setGamePackageForTest("com.example.game")
        val adCmp = "com.example.game/com.google.android.gms.ads.AdActivity"

        // OPEN via dumpsys
        det.handleActivityStack(listOf(ActivityFrame(cmp = adCmp)))
        assertEquals(1, det.events.value.size)
        assertNull(det.events.value[0].endMs)

        // Game returns to top — ad activity is gone from the stack.
        det.handleActivityStack(
            listOf(ActivityFrame(cmp = "com.example.game/com.example.game.MainActivity"))
        )

        val ev = det.events.value[0]
        assertNotNull(ev.endMs, "activity leaving stack must close the event")
    }

    // ──────────────────────── Same-SDK reopen guard ────────────────────────

    @Test
    fun `repeated same-signature opens do not create duplicate events`() {
        val det = newDetectorAtTime(1_000L)

        det.handleLogLine(LogLine(tsMs = 1_000L, pid = 1, tid = 1, level = 'I',
            tag = "Ads", msg = "Showing ad"))
        det.handleLogLine(LogLine(tsMs = 1_100L, pid = 1, tid = 1, level = 'I',
            tag = "Ads", msg = "Showing ad"))
        det.handleLogLine(LogLine(tsMs = 1_200L, pid = 1, tid = 1, level = 'I',
            tag = "Ads", msg = "Showing ad"))

        assertEquals(
            1, det.events.value.size,
            "duplicate same-key opens must be ignored",
        )
    }

    // ──────────────────────── LOADING signatures (v4.4.1 quickfix, audit obs #308) ───

    @Test
    fun `Unity Engine loading line emits a LOADING DetectedEvent`() {
        val det = newDetectorAtTime(1_000L)

        // OPEN: real Unity scene load line as observed in unity-ads.log:5
        det.handleLogLine(LogLine(tsMs = 1_000L, pid = 1, tid = 1, level = 'I',
            tag = "Unity", msg = "Loading scene transition"))

        var events = det.events.value
        assertEquals(1, events.size, "Unity 'Loading scene' must open one event")
        assertEquals(EventType.LOADING, events[0].type, "must classify as LOADING (not REWARDED_VIDEO)")
        assertEquals("Unity Engine", events[0].sdkSource)
        assertNull(events[0].endMs, "open event has no endMs until close")
        assertEquals(Confidence.HIGH, events[0].confidence)

        // CLOSE: Scene loaded
        det.handleLogLine(LogLine(tsMs = 3_000L, pid = 1, tid = 1, level = 'I',
            tag = "Unity", msg = "Scene loaded successfully"))

        events = det.events.value
        assertEquals(1, events.size, "close must NOT add a new event")
        assertEquals(3_000L, events[0].endMs)
        assertEquals(0, det.openEventCountForTest(), "open map must be empty after close")
    }

    @Test
    fun `Unreal Engine LogStreaming open and Flushing close emits LOADING`() {
        val det = newDetectorAtTime(2_000L)

        det.handleLogLine(LogLine(tsMs = 2_000L, pid = 1, tid = 1, level = 'I',
            tag = "UE4", msg = "LogStreaming: Loading package /Game/Maps/Arena"))
        det.handleLogLine(LogLine(tsMs = 5_000L, pid = 1, tid = 1, level = 'I',
            tag = "UE4", msg = "LogStreaming: Flushing async loaders"))

        val events = det.events.value
        assertEquals(1, events.size)
        assertEquals(EventType.LOADING, events[0].type)
        assertEquals("Unreal Engine", events[0].sdkSource)
        assertEquals(2_000L, events[0].startMs)
        assertEquals(5_000L, events[0].endMs)
    }

    @Test
    fun `Cocos2d replaceScene open and onEnter close emits LOADING`() {
        val det = newDetectorAtTime(3_000L)

        det.handleLogLine(LogLine(tsMs = 3_000L, pid = 1, tid = 1, level = 'I',
            tag = "cocos2d", msg = "Director::replaceScene to GameScene"))
        det.handleLogLine(LogLine(tsMs = 4_500L, pid = 1, tid = 1, level = 'I',
            tag = "cocos2d", msg = "GameScene onEnter called"))

        val events = det.events.value
        assertEquals(1, events.size)
        assertEquals(EventType.LOADING, events[0].type)
        assertEquals("Cocos2d", events[0].sdkSource)
        assertEquals(4_500L, events[0].endMs)
    }

    // ──────────────────────── Sprint 1 — APP_STARTUP cold-start sensor ────────

    /**
     * Build a detector with an UN-seeded foreground timestamp so the cold-start
     * sensor in [EventDetectorImpl.handleActivityStack] fires on the first
     * dumpsys snapshot that contains the game package.
     *
     * Production code seeds `lastGameForegroundMs` from `start()` — these tests
     * must avoid that path to exercise the `== -1L` precondition.
     */
    private fun newColdDetectorAtTime(nowMs: Long): EventDetectorImpl {
        return EventDetectorImpl(
            bridge = FakeAdbBridge(),
            timeProvider = { nowMs },
        )
        // intentionally NO setLastGameForegroundForTest() — keep -1L sentinel
    }

    /**
     * ESC-START-001 scenario 1 — first dumpsys frame whose top component
     * belongs to the game package MUST emit an APP_STARTUP event with the
     * `dumpsys-firstforeground` source tag.
     */
    @Test
    fun `first top-stack match of game package emits APP_STARTUP from dumpsys`() {
        val det = newColdDetectorAtTime(1_000L)
        det.setGamePackageForTest("com.example.game")

        det.handleActivityStack(
            listOf(ActivityFrame(cmp = "com.example.game/com.example.game.MainActivity"))
        )

        val events = det.events.value
        assertEquals(1, events.size, "first foreground must emit exactly one APP_STARTUP")
        assertEquals(EventType.APP_STARTUP, events[0].type)
        assertEquals(1_000L, events[0].startMs)
        assertEquals(Confidence.MEDIUM, events[0].confidence)
        val source = events[0].metadata["source"] ?: ""
        assertTrue(
            source.startsWith("dumpsys"),
            "APP_STARTUP from dumpsys must carry source starting with 'dumpsys', got: $source",
        )
    }

    /**
     * ESC-START-001 scenario 2 — subsequent dumpsys frames with the game in
     * foreground MUST NOT duplicate the APP_STARTUP event.
     */
    @Test
    fun `subsequent foreground refreshes do not duplicate APP_STARTUP`() {
        var clock = 1_000L
        val det = EventDetectorImpl(
            bridge = FakeAdbBridge(),
            timeProvider = { clock },
        )
        det.setGamePackageForTest("com.example.game")

        det.handleActivityStack(
            listOf(ActivityFrame(cmp = "com.example.game/com.example.game.MainActivity"))
        )
        assertEquals(1, det.events.value.size, "first frame emits APP_STARTUP")

        clock = 2_000L
        det.handleActivityStack(
            listOf(ActivityFrame(cmp = "com.example.game/com.example.game.MainActivity"))
        )
        assertEquals(
            1, det.events.value.size,
            "second foreground frame must NOT add another APP_STARTUP",
        )
    }

    /**
     * ESC-START-001 extension — the `am_proc_start` atom on the `ActivityManager`
     * tag emits APP_STARTUP via the logcat path BEFORE dumpsys catches up
     * (1Hz polling lag can miss launches inside the first second).
     */
    @Test
    fun `am_proc_start logcat line emits APP_STARTUP from logcat`() {
        val det = newColdDetectorAtTime(500L)
        det.setGamePackageForTest("com.example.game")

        det.handleLogLine(
            LogLine(
                tsMs = 500L, pid = 1, tid = 1, level = 'I',
                tag = "ActivityManager",
                msg = "Start proc 12345:com.example.game/u0a123 for top-activity {com.example.game/.MainActivity}",
            )
        )

        val events = det.events.value
        assertEquals(1, events.size, "am_proc_start must emit one APP_STARTUP")
        assertEquals(EventType.APP_STARTUP, events[0].type)
        assertEquals("logcat", events[0].metadata["source"])
    }

    /**
     * ESC-START-003 scenario 1 — PID restart outside the 10s debounce window
     * MUST emit a new APP_STARTUP event with the `restart` marker.
     */
    @Test
    fun `checkPidRestart with different PID after 10s emits APP_STARTUP restart`() {
        var clock = 1_000L
        val det = EventDetectorImpl(
            bridge = FakeAdbBridge(),
            timeProvider = { clock },
        )
        det.setGamePackageForTest("com.example.game")

        // Seed initial APP_STARTUP via dumpsys path.
        det.handleActivityStack(
            listOf(ActivityFrame(cmp = "com.example.game/com.example.game.MainActivity"))
        )
        assertEquals(1, det.events.value.size, "initial APP_STARTUP emitted")

        // First PID observation — establishes the baseline, no emission.
        det.checkPidRestart(1234)
        assertEquals(1, det.events.value.size, "first PID observation must not emit")

        // Advance past the 10s debounce window and report a different PID.
        clock = 15_000L
        det.checkPidRestart(5678)

        val events = det.events.value
        assertEquals(2, events.size, "PID change after 10s must emit a second APP_STARTUP")
        assertEquals(EventType.APP_STARTUP, events[1].type)
        assertEquals("true", events[1].metadata["restart"])
        assertEquals(15_000L, events[1].startMs)
    }

    /**
     * ESC-START-003 scenario 2 — PID flicker inside the 10s debounce window
     * MUST NOT emit a second APP_STARTUP.
     */
    @Test
    fun `checkPidRestart with PID change within 10s does not re-emit APP_STARTUP`() {
        var clock = 1_000L
        val det = EventDetectorImpl(
            bridge = FakeAdbBridge(),
            timeProvider = { clock },
        )
        det.setGamePackageForTest("com.example.game")

        det.handleActivityStack(
            listOf(ActivityFrame(cmp = "com.example.game/com.example.game.MainActivity"))
        )
        assertEquals(1, det.events.value.size)
        det.checkPidRestart(1234)

        // PID change but still inside the 10s debounce window.
        clock = 5_000L
        det.checkPidRestart(5678)

        assertEquals(
            1, det.events.value.size,
            "rapid PID flicker (under 10s) must be debounced",
        )
    }

    // ──────────────────────── Sprint 1 — ANR ──────────────────────────────────

    /**
     * ESC-ANR-001 — `am_anr` on the `ActivityManager` tag emits an ANR event
     * even when the foreground proximity guard would otherwise reject it
     * (game appears backgrounded but is actually frozen).
     */
    @Test
    fun `am_anr line emits ANR event regardless of foreground guard`() {
        val det = EventDetectorImpl(
            bridge = FakeAdbBridge(),
            timeProvider = { 10_000L },
        )
        // Game looks backgrounded 5s ago — well outside the 2s foreground guard.
        det.setLastGameForegroundForTest(5_000L)
        det.setGamePackageForTest("com.example.game")

        det.handleLogLine(
            LogLine(
                tsMs = 10_000L, pid = 1, tid = 1, level = 'E',
                tag = "ActivityManager",
                msg = "am_anr [12345,com.example.game,...] Input dispatching timed out",
            )
        )

        val events = det.events.value
        assertEquals(1, events.size, "ANR must fire even when foreground guard would reject")
        assertEquals(EventType.ANR, events[0].type)
        assertEquals("System ANR", events[0].sdkSource)
        assertEquals(Confidence.HIGH, events[0].confidence)
    }

    /**
     * ESC-ANR-003 tag-allowlist — the `am_anr` substring on a foreign tag
     * MUST NOT match the System ANR signature.
     */
    @Test
    fun `am_anr substring on foreign tag does NOT emit ANR`() {
        val det = newDetectorAtTime(1_000L)

        det.handleLogLine(
            LogLine(
                tsMs = 1_000L, pid = 1, tid = 1, level = 'I',
                tag = "Unrelated",
                msg = "am_anr-like text in some other component",
            )
        )

        assertEquals(
            0, det.events.value.size,
            "ANR tag-allowlist must reject am_anr text outside ActivityManager",
        )
    }

    // ──────────────────────── Sprint 2a — SCREEN_TRANSITION ───────────────────

    /**
     * ESC-SCRN-001 scenario 1 — first cmp change inside the game package emits
     * a SCREEN_TRANSITION event with `from`/`to` metadata.
     */
    @Test
    fun `cmp change inside game package emits SCREEN_TRANSITION with from-to metadata`() {
        var clock = 1_000L
        val det = EventDetectorImpl(
            bridge = FakeAdbBridge(),
            timeProvider = { clock },
        )
        det.setGamePackageForTest("com.example.game")

        // First frame seeds lastTopCmp (also emits APP_STARTUP cold-start).
        det.handleActivityStack(
            listOf(ActivityFrame(cmp = "com.example.game/com.example.game.MainActivity"))
        )
        val startupCount = det.events.value.count { it.type == EventType.APP_STARTUP }
        assertEquals(1, startupCount, "cold-start APP_STARTUP must fire on first frame")
        assertEquals(
            0, det.events.value.count { it.type == EventType.SCREEN_TRANSITION },
            "first frame must NOT emit SCREEN_TRANSITION (no prior cmp to transition from)",
        )

        // Second frame inside game package, different activity → SCREEN_TRANSITION.
        clock = 5_000L
        det.handleActivityStack(
            listOf(ActivityFrame(cmp = "com.example.game/com.example.game.SettingsActivity"))
        )

        val transitions = det.events.value.filter { it.type == EventType.SCREEN_TRANSITION }
        assertEquals(1, transitions.size, "cmp change must emit one SCREEN_TRANSITION")
        val st = transitions[0]
        assertEquals("com.example.game/com.example.game.MainActivity", st.metadata["from"])
        assertEquals("com.example.game/com.example.game.SettingsActivity", st.metadata["to"])
        assertEquals("dumpsys-cmp-change", st.metadata["source"])
        assertEquals(Confidence.MEDIUM, st.confidence)
        assertEquals(5_000L, st.startMs)
    }

    /**
     * ESC-SCRN-002 — Unity-style single-activity games (cmp never changes
     * across many ticks) MUST NOT emit any SCREEN_TRANSITION events.
     */
    @Test
    fun `no transitions emitted for single-activity Unity game`() {
        var clock = 1_000L
        val det = EventDetectorImpl(
            bridge = FakeAdbBridge(),
            timeProvider = { clock },
        )
        det.setGamePackageForTest("com.example.game")

        val unityCmp = "com.example.game/com.unity3d.player.UnityPlayerActivity"
        // 5 dumpsys ticks with the SAME cmp — single-activity engine.
        for (i in 0..4) {
            clock = 1_000L + i * 1_000L
            det.handleActivityStack(listOf(ActivityFrame(cmp = unityCmp)))
        }

        assertEquals(
            0, det.events.value.count { it.type == EventType.SCREEN_TRANSITION },
            "single-activity game must NOT emit any SCREEN_TRANSITION",
        )
    }

    /**
     * ESC-SCRN-001 scenario 2 — sequential transitions: the previous
     * SCREEN_TRANSITION's endMs is closed at the moment the next one opens.
     */
    @Test
    fun `sequential transitions close previous and open new`() {
        var clock = 1_000L
        val det = EventDetectorImpl(
            bridge = FakeAdbBridge(),
            timeProvider = { clock },
        )
        det.setGamePackageForTest("com.example.game")

        det.handleActivityStack(
            listOf(ActivityFrame(cmp = "com.example.game/com.example.game.MainActivity"))
        )
        clock = 5_000L
        det.handleActivityStack(
            listOf(ActivityFrame(cmp = "com.example.game/com.example.game.SettingsActivity"))
        )
        clock = 10_000L
        det.handleActivityStack(
            listOf(ActivityFrame(cmp = "com.example.game/com.example.game.LevelActivity"))
        )

        val transitions = det.events.value.filter { it.type == EventType.SCREEN_TRANSITION }
        assertEquals(2, transitions.size, "two cmp changes must emit two SCREEN_TRANSITION events")
        // First transition closed at the moment the second opened.
        assertEquals(10_000L, transitions[0].endMs, "first transition closes when second opens")
        assertNull(transitions[1].endMs, "second transition is still open")
    }

    /**
     * ESC-SCRN-001 condition — SCREEN_TRANSITION MUST NOT fire when the new
     * top component matches an SDK activity class (SDK precedence preserved).
     */
    @Test
    fun `SCREEN_TRANSITION does not fire when cmp matches SDK activity`() {
        var clock = 1_000L
        val det = EventDetectorImpl(
            bridge = FakeAdbBridge(),
            timeProvider = { clock },
        )
        det.setGamePackageForTest("com.example.game")

        // First frame: normal game activity.
        det.handleActivityStack(
            listOf(ActivityFrame(cmp = "com.example.game/com.example.game.MainActivity"))
        )

        // Second frame: AdMob's AdActivity hosted in game process → SDK path,
        // NOT a SCREEN_TRANSITION.
        clock = 5_000L
        det.handleActivityStack(
            listOf(ActivityFrame(cmp = "com.example.game/com.google.android.gms.ads.AdActivity"))
        )

        val transitions = det.events.value.filter { it.type == EventType.SCREEN_TRANSITION }
        assertEquals(
            0, transitions.size,
            "SDK activity match must take precedence over SCREEN_TRANSITION",
        )
        // SDK path fired instead.
        assertTrue(
            det.events.value.any { it.sdkSource == "AdMob" },
            "AdMob activity-path event must fire",
        )
    }

    /**
     * ESC-SCRN-003 — after MAX_SCREEN_TRANSITIONS (100) transitions emit a
     * cap warning and further cmp changes do NOT add more events.
     */
    @Test
    fun `100 transitions emit cap warning and further changes are dropped`() {
        var clock = 1_000L
        val det = EventDetectorImpl(
            bridge = FakeAdbBridge(),
            timeProvider = { clock },
        )
        det.setGamePackageForTest("com.example.game")

        // First frame seeds lastTopCmp (also emits APP_STARTUP).
        det.handleActivityStack(
            listOf(ActivityFrame(cmp = "com.example.game/com.example.game.Activity0"))
        )

        // Drive 101 cmp changes — each toggles between two activities so the
        // `lastTopCmp != top.cmp` guard fires every call.
        for (i in 1..101) {
            clock = 1_000L + i * 1_000L
            val cmp = "com.example.game/com.example.game.Activity${i % 2 + 1}"
            det.handleActivityStack(listOf(ActivityFrame(cmp = cmp)))
        }

        val transitions = det.events.value.filter { it.type == EventType.SCREEN_TRANSITION }
        assertEquals(
            EventDetectorImpl.MAX_SCREEN_TRANSITIONS, transitions.size,
            "SCREEN_TRANSITION must be capped at MAX_SCREEN_TRANSITIONS",
        )
        assertTrue(
            det.warnings.value.any { it.contains("cambios de pantalla") },
            "cap warning must be surfaced in Spanish",
        )
    }

    // ──────────────────────── Sprint 2b — INTERSTITIAL → REWARDED upgrade ─────

    /**
     * ESC-REW-002 — when an event opens via the activity-class path as
     * INTERSTITIAL (e.g. AdMob's AdActivity) and a REWARDED openPattern of
     * the SAME SDK fires within its open lifetime, the event upgrades to
     * REWARDED_VIDEO with `upgradedFrom=INTERSTITIAL, upgradedAtMs=<ts>`
     * metadata.
     */
    @Test
    fun `AdActivity opens as INTERSTITIAL then upgrades on rewarded pattern`() {
        var clock = 1_000L
        val det = EventDetectorImpl(
            bridge = FakeAdbBridge(),
            timeProvider = { clock },
        )
        det.setGamePackageForTest("com.example.game")
        det.setLastGameForegroundForTest(1_000L)

        // OPEN via dumpsys — AdMob AdActivity hosted in game process.
        det.handleActivityStack(
            listOf(ActivityFrame(cmp = "com.example.game/com.google.android.gms.ads.AdActivity"))
        )
        var ev = det.events.value.first { it.sdkSource == "AdMob" }
        assertEquals(EventType.INTERSTITIAL, ev.type, "AdActivity opens as INTERSTITIAL")

        // Rewarded pattern on AdMob tag → upgrade in-place.
        clock = 3_000L
        det.handleLogLine(LogLine(tsMs = 3_000L, pid = 1, tid = 1, level = 'I',
            tag = "Ads", msg = "onUserEarnedReward type=coins amount=10"))

        ev = det.events.value.first { it.sdkSource == "AdMob" }
        assertEquals(
            EventType.REWARDED_VIDEO, ev.type,
            "REWARDED openPattern of same SDK must upgrade the event",
        )
        assertEquals("INTERSTITIAL", ev.metadata["upgradedFrom"])
        assertEquals("3000", ev.metadata["upgradedAtMs"])
        // The same single event — no duplicate emitted.
        assertEquals(1, det.events.value.count { it.sdkSource == "AdMob" })
    }

    /**
     * ESC-REW-002 — once an event is REWARDED_VIDEO it MUST NOT downgrade
     * back to INTERSTITIAL when a subsequent INTERSTITIAL pattern fires.
     */
    @Test
    fun `already-REWARDED event does not downgrade on later INTERSTITIAL pattern`() {
        var clock = 1_000L
        val det = EventDetectorImpl(
            bridge = FakeAdbBridge(),
            timeProvider = { clock },
        )
        det.setGamePackageForTest("com.example.game")
        det.setLastGameForegroundForTest(1_000L)

        // Open Unity Ads (defaultType=REWARDED_VIDEO) via UnityAdsShowStart.
        det.handleLogLine(LogLine(tsMs = 1_000L, pid = 1, tid = 1, level = 'I',
            tag = "UnityAds", msg = "UnityAdsShowStart placement=rewarded"))
        val opened = det.events.value.firstOrNull { it.sdkSource == "Unity Ads" }
        assertNotNull(opened, "Unity Ads open must fire")
        assertEquals(EventType.REWARDED_VIDEO, opened.type)

        // Send an INTERSTITIAL-style line on the same SDK's tag — must NOT
        // mutate the type. (Synthesized line; the test asserts directionality.)
        clock = 2_000L
        det.handleLogLine(LogLine(tsMs = 2_000L, pid = 1, tid = 1, level = 'I',
            tag = "UnityAds", msg = "Show begin secondary"))

        val ev = det.events.value.first { it.sdkSource == "Unity Ads" }
        assertEquals(
            EventType.REWARDED_VIDEO, ev.type,
            "REWARDED→INTERSTITIAL downgrade is NOT supported",
        )
        assertNull(ev.metadata["upgradedFrom"], "no upgrade metadata for unchanged event")
    }

    // ──────────────────────── Lifecycle smoke test ────────────────────────

    @Test
    fun `start with no logcat fixture surfaces a warning but does not throw`() {
        val bridge = FakeAdbBridge()  // no fixture configured → startLogcat returns null
        val det = EventDetectorImpl(bridge = bridge)
        det.start(deviceId = "emu-5554", gamePackage = "com.example.game", scope = captureScope)

        // The detector must have flagged the missing logcat as a warning.
        assertTrue(
            det.warnings.value.any { it.contains("logcat") },
            "missing logcat process must be surfaced as a warning",
        )
        det.stop()
    }
}
