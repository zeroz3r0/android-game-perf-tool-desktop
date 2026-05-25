package com.gameperf.desktop.core.events

import com.gameperf.desktop.testing.FakeAdbBridge
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-state-machine tests for [EventDetectorImpl]'s instrumented opt-in
 * branch (logcat tag `GamePerf`).
 *
 * Covers IEM-001 (GamePerf entry classification), IEM-002 (fixed 4-tag
 * allowlist), IEM-003 (case-sensitive matching), IEM-004 (per-tag-keyed
 * lifecycle), IEM-005 (orphan Stop silent), IEM-006 (nested Start no-op),
 * and IEM-008 (foreground-guard bypass).
 *
 * No mocks per CLAUDE.md "tests puros sin mocks". The handlers are driven
 * directly via `internal` test entry points to avoid spinning up real
 * `LogcatCapture` / `DumpsysPoller`.
 *
 * @since instrumented-event-mode change
 */
class EventDetectorImplInstrumentedTest {

    /**
     * Build a detector with a manually-controlled clock and a primed
     * foreground timestamp. Equivalent to the helper in [EventDetectorImplTest]
     * but kept local so this file is self-contained.
     */
    private fun newDetectorAtTime(nowMs: Long): EventDetectorImpl {
        val det = EventDetectorImpl(
            bridge = FakeAdbBridge(),
            timeProvider = { nowMs },
        )
        det.setGamePackageForTest("com.example.game")
        det.setLastGameForegroundForTest(nowMs)
        return det
    }

    /**
     * v4.9.0 — Build a detector with a clock the test can advance between
     * calls via the returned [LongArray] cell. Required after the reception-
     * time fix (engram #503) for tests that pin open vs close timestamps.
     */
    private fun newDetectorWithControlledClock(initialMs: Long): Pair<EventDetectorImpl, LongArray> {
        val clock = longArrayOf(initialMs)
        val det = EventDetectorImpl(
            bridge = FakeAdbBridge(),
            timeProvider = { clock[0] },
        )
        det.setGamePackageForTest("com.example.game")
        det.setLastGameForegroundForTest(initialMs)
        return det to clock
    }

    private fun instrumentedLine(tsMs: Long, msg: String): LogLine =
        LogLine(tsMs = tsMs, pid = 1, tid = 1, level = 'I', tag = "GamePerf", msg = msg)

    // ═══════ Phase 3.1.R — first RED test (IEM-001 + IEM-002 happy path) ═══════

    @Test
    fun `CINEMATIC dot Start emits one INSTRUMENTED event`() {
        val det = newDetectorAtTime(nowMs = 1_000L)

        det.handleLogLine(instrumentedLine(tsMs = 1_000L, msg = "CINEMATIC.Start"))

        val events = det.events.value
        assertEquals(1, events.size, "CINEMATIC.Start must open exactly one event")
        val ev = events[0]
        assertEquals(EventType.INSTRUMENTED, ev.type)
        assertEquals("GamePerf", ev.sdkSource)
        assertEquals(Confidence.HIGH, ev.confidence)
        assertEquals(1_000L, ev.startMs)
        assertNull(ev.endMs, "open event has no endMs yet")
        assertEquals("CINEMATIC", ev.metadata["tag"], "metadata.tag must carry the parsed phase tag")
    }

    // ═══════ Phase 3.3.R — close matching open (IEM-004 happy path) ═══════

    @Test
    fun `CINEMATIC dot Stop closes the matching open event with endMs set`() {
        val det = newDetectorAtTime(nowMs = 5_000L)

        det.handleLogLine(instrumentedLine(tsMs = 1_000L, msg = "CINEMATIC.Start"))
        det.handleLogLine(instrumentedLine(tsMs = 5_000L, msg = "CINEMATIC.Stop"))

        val events = det.events.value
        assertEquals(1, events.size, "Stop must NOT add a new event")
        assertEquals(5_000L, events[0].endMs, "Stop must stamp endMs")
        assertEquals(0, det.openEventCountForTest(), "open map must be empty after close")
    }

    // ═══════ Phase 3.5.R — IEM-004 per-tag-keyed lifecycle ═══════

    @Test
    fun `TUTORIAL dot Stop does not close a CINEMATIC open`() {
        // Spec IEM-004: a Stop for tag X must NOT close an open event of
        // tag Y, even when both share sdkSource="GamePerf". The per-tag
        // key `"GamePerf:instrumented:$tag"` in [openEvents] guarantees
        // this isolation.
        val det = newDetectorAtTime(nowMs = 2_000L)

        det.handleLogLine(instrumentedLine(tsMs = 1_000L, msg = "CINEMATIC.Start"))
        det.handleLogLine(instrumentedLine(tsMs = 1_500L, msg = "TUTORIAL.Start"))
        det.handleLogLine(instrumentedLine(tsMs = 2_000L, msg = "TUTORIAL.Stop"))

        val events = det.events.value
        assertEquals(2, events.size, "two distinct events must exist (CINEMATIC + TUTORIAL)")

        val cinematic = events.first { it.metadata["tag"] == "CINEMATIC" }
        val tutorial = events.first { it.metadata["tag"] == "TUTORIAL" }

        assertNull(cinematic.endMs, "CINEMATIC must remain open — TUTORIAL.Stop is not for it")
        assertEquals(2_000L, tutorial.endMs, "TUTORIAL must be closed at t=2000")
        assertEquals(1, det.openEventCountForTest(), "only CINEMATIC remains open")
    }

    @Test
    fun `overlapping CINEMATIC and TUTORIAL close independently`() {
        // Spec IEM-004 follow-up: two parallel opens, two parallel closes,
        // each preserves its own startMs/endMs.
        // v4.9.0 — controlled clock for reception-time semantics.
        val (det, clock) = newDetectorWithControlledClock(1_000L)

        det.handleLogLine(instrumentedLine(tsMs = 99_999L, msg = "CINEMATIC.Start"))
        clock[0] = 1_500L
        det.handleLogLine(instrumentedLine(tsMs = 99_999L, msg = "TUTORIAL.Start"))
        clock[0] = 2_000L
        det.handleLogLine(instrumentedLine(tsMs = 99_999L, msg = "CINEMATIC.Stop"))
        clock[0] = 2_500L
        det.handleLogLine(instrumentedLine(tsMs = 99_999L, msg = "TUTORIAL.Stop"))

        val events = det.events.value
        assertEquals(2, events.size)
        val cinematic = events.first { it.metadata["tag"] == "CINEMATIC" }
        val tutorial = events.first { it.metadata["tag"] == "TUTORIAL" }
        assertEquals(1_000L, cinematic.startMs)
        assertEquals(2_000L, cinematic.endMs)
        assertEquals(1_500L, tutorial.startMs)
        assertEquals(2_500L, tutorial.endMs)
    }

    // ═══════ Phase 3.6.R — IEM-006 re-entrant Start is no-op ═══════

    @Test
    fun `re-entrant CINEMATIC dot Start does not open a second event`() {
        // Spec IEM-006: the second Start for the same tag must be ignored;
        // the existing open keeps its original startMs.
        // v4.9.0 — controlled clock for reception-time semantics.
        val (det, clock) = newDetectorWithControlledClock(1_000L)

        det.handleLogLine(instrumentedLine(tsMs = 99_999L, msg = "CINEMATIC.Start"))
        clock[0] = 1_500L
        det.handleLogLine(instrumentedLine(tsMs = 99_999L, msg = "CINEMATIC.Start"))

        val events = det.events.value
        assertEquals(1, events.size, "nested Start must NOT open a second event")
        assertEquals(1_000L, events[0].startMs, "original startMs preserved")
        assertNull(events[0].endMs, "original event still open")
    }

    // ═══════ Phase 3.7.R — IEM-005 orphan Stop silent ═══════

    @Test
    fun `orphan GAMEPLAY_DENSE dot Stop is ignored without warning`() {
        // Spec IEM-005: a Stop with no matching open MUST NOT emit an event
        // and MUST NOT add a warning.
        val det = newDetectorAtTime(nowMs = 1_000L)

        det.handleLogLine(instrumentedLine(tsMs = 1_000L, msg = "GAMEPLAY_DENSE.Stop"))

        assertEquals(0, det.events.value.size, "orphan Stop emits no event")
        assertTrue(
            det.warnings.value.isEmpty(),
            "orphan Stop emits no warning (would spam reports per design)",
        )
    }

    // ═══════ Phase 3.8.R — IEM-002 unknown tag silently rejected ═══════

    @Test
    fun `unknown UPPER_SNAKE tag UNKNOWN_PHASE dot Start emits nothing`() {
        // Spec IEM-002: only allowlisted tags are recognised. Anything
        // else is silently rejected — no event, no warning. (NOTE: MENU
        // was added to the allowlist by auto-phase-detection Phase 4 to
        // back the AUTO-008 INSTRUMENTED-over-AUTO upgrade rule; this
        // test now uses an explicitly synthetic tag.)
        val det = newDetectorAtTime(nowMs = 1_000L)

        det.handleLogLine(instrumentedLine(tsMs = 1_000L, msg = "UNKNOWN_PHASE.Start"))

        assertEquals(0, det.events.value.size, "unknown tag emits no event")
        assertTrue(det.warnings.value.isEmpty(), "unknown tag emits no warning")
    }

    // ═══════ Phase 3.9.R — IEM-003 case-sensitive matching ═══════

    @Test
    fun `lowercase cinematic dot Start emits nothing`() {
        // Spec IEM-003: CINEMATIC != cinematic. The parser's `[A-Z_]+`
        // char class rejects lowercase variants outright.
        val det = newDetectorAtTime(nowMs = 1_000L)

        det.handleLogLine(instrumentedLine(tsMs = 1_000L, msg = "cinematic.Start"))

        assertEquals(0, det.events.value.size, "lowercase tag emits no event")
    }

    @Test
    fun `mixed-case Cinematic dot Start emits nothing`() {
        // Spec IEM-003 follow-up: Cinematic (CamelCase) also rejected.
        val det = newDetectorAtTime(nowMs = 1_000L)

        det.handleLogLine(instrumentedLine(tsMs = 1_000L, msg = "Cinematic.Start"))

        assertEquals(0, det.events.value.size, "mixed-case tag emits no event")
    }

    // ═══════ Phase 3.10.R — IEM-008 foreground-guard bypass ═══════

    @Test
    fun `foreground-stale CINEMATIC dot Start still opens an event`() {
        // Spec IEM-008: instrumented opens MUST NOT be rejected by the
        // FOREGROUND_GUARD_MS proximity check. The game is in foreground
        // by definition when emitting from its own process.
        //
        // Set up a clock at t=10_000 with the last-foreground stamp at
        // t=0L — that is `10_000ms` stale, well outside the 2_000ms guard.
        val det = EventDetectorImpl(
            bridge = FakeAdbBridge(),
            timeProvider = { 10_000L },
        )
        det.setGamePackageForTest("com.example.game")
        det.setLastGameForegroundForTest(0L) // far older than FOREGROUND_GUARD_MS

        det.handleLogLine(instrumentedLine(tsMs = 10_000L, msg = "CINEMATIC.Start"))

        val events = det.events.value
        assertEquals(
            1, events.size,
            "instrumented open must bypass EVT-008 foreground guard (IEM-008)",
        )
        val ev = events[0]
        assertEquals(EventType.INSTRUMENTED, ev.type)
        assertEquals("CINEMATIC", ev.metadata["tag"])
    }

    // ═══════ Phase 3.11.R — stop() force-closes with endInferred=true ═══════

    @Test
    fun `detector stop force-closes open INSTRUMENTED event with endInferred true`() {
        // The existing EVT-006 stop() logic synthesises endMs+endInferred=true
        // for every still-open event. This test pins the instrumented
        // branch into that contract so a future refactor doesn't quietly
        // exempt it.
        val det = newDetectorAtTime(nowMs = 9_000L)

        det.handleLogLine(instrumentedLine(tsMs = 1_000L, msg = "CINEMATIC.Start"))
        det.stop()

        val events = det.events.value
        assertEquals(1, events.size)
        val ev = events[0]
        assertNotNull(ev.endMs, "stop() must synthesise endMs")
        assertEquals(9_000L, ev.endMs)
        assertTrue(ev.endInferred, "synthesised close must set endInferred=true")
    }

    // ═══════ Phase 4 — fixture-driven smoke ═══════

    @Test
    fun `instrumented-opt-in fixture produces four INSTRUMENTED events`() {
        // End-to-end smoke: feed the recorded threadtime fixture through the
        // real [LogcatLineParser] + [EventDetectorImpl] instrumented branch.
        // The fixture contains 4 valid Start/Stop pairs (CINEMATIC, TUTORIAL,
        // GAMEPLAY_DENSE, SPECIAL_EVENT) plus 2 negative noise lines
        // (`cinematic.Start` lowercase per IEM-003 + `MENU.Start` unknown tag
        // per IEM-002) plus surrounding non-GamePerf log noise. We expect
        // exactly 4 events, all closed, distinct tags, no warnings raised.
        //
        // Drives detection synchronously by calling [handleLogLine] for each
        // parsed line — matches the pattern used in the other tests in this
        // file (no coroutines, no real LogcatCapture process).
        val det = EventDetectorImpl(
            bridge = FakeAdbBridge(),
            timeProvider = { Long.MAX_VALUE },
        )
        det.setGamePackageForTest("com.example.game")
        // Seed foreground stamp at far-future so the EVT-008 guard is
        // satisfied for any non-instrumented opens that might fire from
        // the noise lines (defensive — none should match the catalog).
        det.setLastGameForegroundForTest(Long.MAX_VALUE)

        val lines = readFixtureLines("logcat-fixtures/instrumented-opt-in.log")
        assertTrue(lines.size in 60..80, "fixture has expected length (got ${lines.size})")

        for (raw in lines) {
            val parsed = LogcatLineParser.parse(raw) ?: continue
            det.handleLogLine(parsed)
        }

        val events = det.events.value
        assertEquals(
            4, events.size,
            "fixture must produce exactly 4 INSTRUMENTED events " +
                "(noise lines `cinematic.Start` + `UNKNOWN_PHASE.Start` must be silently rejected)",
        )

        // All four events MUST be INSTRUMENTED and sourced from GamePerf.
        for (ev in events) {
            assertEquals(EventType.INSTRUMENTED, ev.type, "every event must be INSTRUMENTED")
            assertEquals("GamePerf", ev.sdkSource, "every event must be sourced from GamePerf")
            assertEquals(Confidence.HIGH, ev.confidence, "instrumented opens are HIGH confidence")
            assertNotNull(ev.endMs, "every event must be closed by its matching Stop line")
            assertEquals(false, ev.endInferred, "stop-line closes are not inferred")
        }

        // Distinct tags from the 4-tag allowlist (IEM-002).
        val tags = events.mapNotNull { it.metadata["tag"] }.toSet()
        assertEquals(
            setOf("CINEMATIC", "TUTORIAL", "GAMEPLAY_DENSE", "SPECIAL_EVENT"),
            tags,
            "all four fixed tags must appear exactly once each",
        )

        // No warnings — orphan/unknown/case-mismatch paths are silent per
        // IEM-002/003/005.
        assertTrue(
            det.warnings.value.isEmpty(),
            "no warnings expected; rejection paths are silent (got ${det.warnings.value})",
        )

        // Open map must be drained.
        assertEquals(0, det.openEventCountForTest(), "all events closed → open map empty")
    }

    private fun readFixtureLines(resourcePath: String): List<String> {
        val stream = javaClass.classLoader.getResourceAsStream(resourcePath)
            ?: error("missing fixture: $resourcePath")
        return BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).useLines {
            it.toList()
        }
    }
}
