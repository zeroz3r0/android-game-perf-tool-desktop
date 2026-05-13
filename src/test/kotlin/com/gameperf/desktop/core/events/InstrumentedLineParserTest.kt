package com.gameperf.desktop.core.events

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [InstrumentedLineParser].
 *
 * Pure parser — no detector, no coroutines, no fakes. Covers spec IEM-002
 * (fixed 4-tag allowlist) and IEM-003 (case-sensitive matching). The parser
 * is the foundational layer that the detector branch (Phase 3) depends on.
 *
 * @since instrumented-event-mode change
 */
class InstrumentedLineParserTest {

    // ═══════ Phase 1.1.R — first RED test ═══════

    @Test
    fun `parses CINEMATIC dot Start as open hit`() {
        val hit = InstrumentedLineParser.parse("CINEMATIC.Start")
        assertEquals(InstrumentedHit("CINEMATIC", true), hit)
    }

    // ═══════ Phase 1.3.R — triangulate across all 4 tags x {Start, Stop} ═══════

    @Test
    fun `parses TUTORIAL dot Start`() {
        assertEquals(InstrumentedHit("TUTORIAL", true), InstrumentedLineParser.parse("TUTORIAL.Start"))
    }

    @Test
    fun `parses GAMEPLAY_DENSE dot Start`() {
        assertEquals(
            InstrumentedHit("GAMEPLAY_DENSE", true),
            InstrumentedLineParser.parse("GAMEPLAY_DENSE.Start"),
        )
    }

    @Test
    fun `parses SPECIAL_EVENT dot Start`() {
        assertEquals(
            InstrumentedHit("SPECIAL_EVENT", true),
            InstrumentedLineParser.parse("SPECIAL_EVENT.Start"),
        )
    }

    @Test
    fun `parses CINEMATIC dot Stop as close hit`() {
        assertEquals(InstrumentedHit("CINEMATIC", false), InstrumentedLineParser.parse("CINEMATIC.Stop"))
    }

    @Test
    fun `parses TUTORIAL dot Stop`() {
        assertEquals(InstrumentedHit("TUTORIAL", false), InstrumentedLineParser.parse("TUTORIAL.Stop"))
    }

    @Test
    fun `parses GAMEPLAY_DENSE dot Stop`() {
        assertEquals(
            InstrumentedHit("GAMEPLAY_DENSE", false),
            InstrumentedLineParser.parse("GAMEPLAY_DENSE.Stop"),
        )
    }

    @Test
    fun `parses SPECIAL_EVENT dot Stop`() {
        assertEquals(
            InstrumentedHit("SPECIAL_EVENT", false),
            InstrumentedLineParser.parse("SPECIAL_EVENT.Stop"),
        )
    }

    // ═══════ Phase 1.4.R — NEGATIVE tests (IEM-002 + IEM-003) ═══════

    @Test
    fun `rejects mixed-case Cinematic dot Start`() {
        // IEM-003 case-sensitive matching — Cinematic != CINEMATIC.
        assertNull(InstrumentedLineParser.parse("Cinematic.Start"))
    }

    @Test
    fun `rejects fully lowercase cinematic dot Start`() {
        assertNull(InstrumentedLineParser.parse("cinematic.Start"))
    }

    @Test
    fun `rejects unknown UPPER_SNAKE tag UNKNOWN_PHASE dot Start`() {
        // IEM-002 — UNKNOWN_PHASE is not in the allowlist; the regex shape
        // matches but the allowlist filter must still reject. (NOTE: MENU
        // was added to the allowlist by auto-phase-detection Phase 4 to
        // support AUTO-008 INSTRUMENTED-over-AUTO upgrades; this test now
        // uses an explicitly synthetic tag to keep the negative case alive.)
        assertNull(InstrumentedLineParser.parse("UNKNOWN_PHASE.Start"))
    }

    @Test
    fun `rejects CINEMATIC dot lowercase start`() {
        // The verb is required to be exactly `Start` or `Stop` (CapitalCase).
        assertNull(InstrumentedLineParser.parse("CINEMATIC.start"))
    }

    @Test
    fun `rejects CINEMATIC dot Start with trailing content`() {
        // matchEntire anchors both ends — trailing text must reject so
        // foreign log noise containing the literal as a substring cannot
        // accidentally open phases.
        assertNull(InstrumentedLineParser.parse("CINEMATIC.Start trailing"))
    }

    @Test
    fun `rejects unrelated short string foo`() {
        assertNull(InstrumentedLineParser.parse("foo"))
    }

    @Test
    fun `rejects empty message`() {
        assertNull(InstrumentedLineParser.parse(""))
    }
}
