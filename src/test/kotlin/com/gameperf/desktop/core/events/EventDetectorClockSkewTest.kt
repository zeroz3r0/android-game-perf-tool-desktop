package com.gameperf.desktop.core.events

import com.gameperf.desktop.testing.FakeAdbBridge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Clock-skew regression tests for [EventDetectorImpl] — pinpoints the v4.9.0
 * fix for the dual-clock drift bug documented in engram #503.
 *
 * Pre-v4.9.0, [DetectedEvent.startMs] / `endMs` / `upgradedAtMs` were sourced
 * from [LogLine.tsMs] — the DEVICE clock parsed from `adb logcat -v threadtime`.
 * The desktop pipeline (`AppViewModel.captureStartTime`) used
 * `System.currentTimeMillis()` — the DESKTOP clock. When the two clocks
 * drifted (normal: NTP gaps, USB-debugging clock skew), the report showed
 * events with a constant N-second offset relative to the recorded video.
 *
 * v4.9.0 aligns every event timestamp with `timeProvider()` (defaults to
 * `System.currentTimeMillis`), making `event.startMs - captureStartMs`
 * coherent regardless of device-clock drift.
 *
 * Each test below feeds a [LogLine] with [LogLine.tsMs] DELIBERATELY DIVERGENT
 * from the controlled `timeProvider()` value. The assertion is that the
 * emitted event carries the `timeProvider()` value, NOT `line.tsMs`.
 *
 * Spec scenarios covered: ESC-CLK-001 .. ESC-CLK-007.
 */
class EventDetectorClockSkewTest {

    /**
     * Build a detector whose clock is mutable per-tick via the shared `clock`
     * closure cell. Returns the detector AND the mutable cell so tests can
     * advance the clock between handleLogLine calls.
     */
    private fun newDetectorWithMutableClock(initialMs: Long): Pair<EventDetectorImpl, LongArray> {
        val clock = longArrayOf(initialMs)
        val det = EventDetectorImpl(
            bridge = FakeAdbBridge(),
            timeProvider = { clock[0] },
        )
        det.setGamePackageForTest("com.example.game")
        det.setLastGameForegroundForTest(initialMs)
        return det to clock
    }

    // ──────────────────────── ESC-CLK-001 ────────────────────────

    /**
     * ESC-CLK-001: generic SDK open uses reception-time (desktop clock)
     * when the device clock is 5 seconds AHEAD. This is the regression
     * target — the original AppLovin-events-shifted-vs-video bug.
     */
    @Test
    fun `generic open uses reception-time when device clock is ahead`() {
        val (det, clock) = newDetectorWithMutableClock(100_000L)

        // Device clock 5s ahead of desktop reception clock.
        det.handleLogLine(LogLine(
            tsMs = 105_000L, // device says "now" is 105s
            pid = 1, tid = 1, level = 'I',
            tag = "Ads",
            msg = "Showing ad now",
        ))

        val events = det.events.value
        assertEquals(1, events.size, "open must add one event")
        assertEquals(
            100_000L, events[0].startMs,
            "startMs MUST be reception-time (timeProvider), NOT line.tsMs (device clock)",
        )
    }

    // ──────────────────────── ESC-CLK-002 ────────────────────────

    /**
     * ESC-CLK-002: same as 001 but with the device clock 3 seconds BEHIND
     * the desktop clock. Sign of drift is irrelevant — reception-time wins.
     */
    @Test
    fun `generic open uses reception-time when device clock is behind`() {
        val (det, clock) = newDetectorWithMutableClock(100_000L)

        // Device clock 3s BEHIND desktop reception clock.
        det.handleLogLine(LogLine(
            tsMs = 97_000L,
            pid = 1, tid = 1, level = 'I',
            tag = "Ads",
            msg = "Showing ad now",
        ))

        val events = det.events.value
        assertEquals(1, events.size)
        assertEquals(
            100_000L, events[0].startMs,
            "startMs MUST be reception-time regardless of drift direction",
        )
    }

    // ──────────────────────── ESC-CLK-003 ────────────────────────

    /**
     * ESC-CLK-003: event close uses reception-time, NOT line.tsMs. After
     * open at clock=100_000, advance clock to 105_000 then feed a close
     * whose device timestamp is wildly skewed — the close MUST stamp
     * endMs == 105_000 (reception-time at close observation).
     */
    @Test
    fun `close uses reception-time independent of line tsMs`() {
        val (det, clock) = newDetectorWithMutableClock(100_000L)

        // OPEN at desktop clock = 100_000, device says 999_999 (huge drift).
        det.handleLogLine(LogLine(
            tsMs = 999_999L,
            pid = 1, tid = 1, level = 'I',
            tag = "Ads", msg = "Showing ad now",
        ))
        assertEquals(100_000L, det.events.value[0].startMs)

        // Advance desktop clock by 5s; CLOSE arrives with another bogus
        // device timestamp.
        clock[0] = 105_000L
        det.handleLogLine(LogLine(
            tsMs = 12L, // device clock nonsense
            pid = 1, tid = 1, level = 'I',
            tag = "Ads", msg = "Ad dismissed by user",
        ))

        val events = det.events.value
        assertEquals(1, events.size)
        assertEquals(
            105_000L, events[0].endMs,
            "endMs MUST be reception-time at close observation",
        )
    }

    // ──────────────────────── ESC-CLK-004 ────────────────────────

    /**
     * ESC-CLK-004: APP_STARTUP fired from the logcat `am_proc_start`
     * fast path uses reception-time, NOT the device timestamp on the
     * `ActivityManager: Start proc <pkg>` line.
     */
    @Test
    fun `am_proc_start APP_STARTUP uses reception-time`() {
        val (det, clock) = newDetectorWithMutableClock(200_000L)

        // Device clock 10s ahead.
        det.handleLogLine(LogLine(
            tsMs = 210_000L,
            pid = 1, tid = 1, level = 'I',
            tag = "ActivityManager",
            msg = "Start proc 1234:com.example.game/u0a99 for activity {com.example.game/.MainActivity}",
        ))

        val events = det.events.value
        assertEquals(1, events.size, "am_proc_start must emit one APP_STARTUP")
        assertEquals(EventType.APP_STARTUP, events[0].type)
        assertEquals(
            200_000L, events[0].startMs,
            "APP_STARTUP startMs MUST be reception-time (timeProvider), not line.tsMs",
        )
    }

    // ──────────────────────── ESC-CLK-005 ────────────────────────

    /**
     * ESC-CLK-005: INTERSTITIAL → REWARDED upgrade stamps `upgradedAtMs`
     * with reception-time, NOT the rewarded line's device timestamp.
     */
    @Test
    fun `INTERSTITIAL to REWARDED upgrade uses reception-time`() {
        val (det, clock) = newDetectorWithMutableClock(50_000L)

        // OPEN AdMob via dumpsys AdActivity (defaultType=INTERSTITIAL).
        det.handleActivityStack(
            listOf(ActivityFrame(cmp = "com.example.game/com.google.android.gms.ads.AdActivity"))
        )
        val opened = det.events.value.first { it.sdkSource == "AdMob" }
        assertEquals(EventType.INTERSTITIAL, opened.type)

        // Advance desktop clock; rewarded callback arrives with skewed tsMs.
        clock[0] = 55_000L
        det.handleLogLine(LogLine(
            tsMs = 999_999L, // device clock nonsense
            pid = 1, tid = 1, level = 'I',
            tag = "Ads", msg = "onUserEarnedReward type=coins amount=10",
        ))

        val ev = det.events.value.first { it.sdkSource == "AdMob" }
        assertEquals(EventType.REWARDED_VIDEO, ev.type)
        assertEquals(
            "55000", ev.metadata["upgradedAtMs"],
            "upgradedAtMs MUST reflect reception-time, NOT device tsMs",
        )
    }

    // ──────────────────────── ESC-CLK-006 ────────────────────────

    /**
     * ESC-CLK-006: instrumented open + close (`GamePerf` tag) uses
     * reception-time on both ends. The game emits these from its own
     * process so adb lag is small, but the report still needs same-clock
     * coherence with the rest of the timeline.
     */
    @Test
    fun `instrumented open and close uses reception-time`() {
        val (det, clock) = newDetectorWithMutableClock(10_000L)

        // OPEN: GamePerf tag with skewed device tsMs.
        det.handleLogLine(LogLine(
            tsMs = 88_888L,
            pid = 1, tid = 1, level = 'I',
            tag = "GamePerf",
            msg = "CINEMATIC.Start",
        ))
        var events = det.events.value
        assertEquals(1, events.size, "instrumented Start must open one event")
        assertEquals(EventType.INSTRUMENTED, events[0].type)
        assertEquals(
            10_000L, events[0].startMs,
            "instrumented open startMs MUST be reception-time",
        )

        // CLOSE: advance desktop clock, feed Stop with different skew.
        clock[0] = 13_500L
        det.handleLogLine(LogLine(
            tsMs = 77_777L,
            pid = 1, tid = 1, level = 'I',
            tag = "GamePerf",
            msg = "CINEMATIC.Stop",
        ))

        events = det.events.value
        assertEquals(1, events.size)
        assertEquals(
            13_500L, events[0].endMs,
            "instrumented close endMs MUST be reception-time",
        )
    }

    // ──────────────────────── ESC-CLK-007 ────────────────────────

    /**
     * ESC-CLK-007: AUTO-phase events synthesised by [EnginePhaseClassifier]
     * (Unity / Unreal scene-name capture) carry reception-time startMs.
     * Unity Engine signature opens a LOADING event AND, when the scene name
     * classifies, emits an AUTO-phase secondary event — both must use
     * timeProvider().
     */
    @Test
    fun `AUTO-phase event uses reception-time`() {
        val (det, clock) = newDetectorWithMutableClock(70_000L)

        // Unity scene load with a classifiable scene name ("MainMenu" →
        // MENU_NAV). Device clock 8s ahead.
        det.handleLogLine(LogLine(
            tsMs = 78_000L,
            pid = 1, tid = 1, level = 'I',
            tag = "Unity",
            msg = "Loading scene: MainMenu",
        ))

        val events = det.events.value
        // LOADING (primary) + AUTO-phase (secondary).
        val auto = events.firstOrNull { it.sdkSource.endsWith("auto-phase") }
        assertNotNull(auto, "AUTO-phase secondary event must be emitted")
        assertEquals(
            70_000L, auto.startMs,
            "AUTO-phase startMs MUST be reception-time, not device tsMs",
        )

        // Also verify the primary LOADING event carries reception-time.
        val loading = events.first { it.type == EventType.LOADING }
        assertEquals(
            70_000L, loading.startMs,
            "primary LOADING startMs MUST also be reception-time",
        )
        assertNull(loading.endMs, "primary LOADING still open until close arrives")
    }
}
