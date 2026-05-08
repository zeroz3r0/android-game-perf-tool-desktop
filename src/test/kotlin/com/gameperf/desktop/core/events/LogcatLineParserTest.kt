package com.gameperf.desktop.core.events

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [LogcatLineParser].
 *
 * Pure fixtures — no mocks (CLAUDE.md "tests puros sin mocks" rule). Each
 * scenario is a hand-built input string asserting either exact field
 * extraction or `null` on malformed input.
 *
 * Coverage targets EVT-002 spec scenarios.
 */
class LogcatLineParserTest {

    // ═══════ well-formed lines ═══════

    @Test
    fun `parses well-formed line with all fields`() {
        val line = "01-15 14:32:18.456  1234  5678 I AdActivity: Showing ad"
        val parsed = LogcatLineParser.parse(line)
        assertNotNull(parsed)
        assertEquals(1234, parsed.pid)
        assertEquals(5678, parsed.tid)
        assertEquals('I', parsed.level)
        assertEquals("AdActivity", parsed.tag)
        assertEquals("Showing ad", parsed.msg)
        assertTrue(parsed.tsMs > 0L, "timestamp must be a positive epoch-millis")
    }

    @Test
    fun `parses each log level character`() {
        for (level in listOf('V', 'D', 'I', 'W', 'E', 'F', 'A')) {
            val line = "01-15 14:32:18.456  1234  5678 $level Tag: msg"
            val parsed = LogcatLineParser.parse(line)
            assertNotNull(parsed, "level $level must parse")
            assertEquals(level, parsed.level)
        }
    }

    @Test
    fun `preserves colon characters inside message body`() {
        val line = "01-15 14:32:18.456  1234  5678 I MyTag: key=value: nested:thing"
        val parsed = LogcatLineParser.parse(line)
        assertNotNull(parsed)
        assertEquals("MyTag", parsed.tag)
        assertEquals("key=value: nested:thing", parsed.msg)
    }

    @Test
    fun `handles tag with trailing whitespace before colon`() {
        // Logcat sometimes pads the tag column; the parser must trim.
        val line = "01-15 14:32:18.456  1234  5678 I PaddedTag : the message"
        val parsed = LogcatLineParser.parse(line)
        assertNotNull(parsed)
        assertEquals("PaddedTag", parsed.tag)
        assertEquals("the message", parsed.msg)
    }

    @Test
    fun `parses very long message without truncation`() {
        val longMsg = "x".repeat(2000)
        val line = "01-15 14:32:18.456  1234  5678 I MyTag: $longMsg"
        val parsed = LogcatLineParser.parse(line)
        assertNotNull(parsed)
        assertEquals(2000, parsed.msg.length)
        assertEquals(longMsg, parsed.msg)
    }

    @Test
    fun `parses line at end-of-year boundary`() {
        // 12-31 23:59:59 — just before midnight. Must still produce a valid timestamp.
        val line = "12-31 23:59:59.999  1234  5678 W BoundaryTag: edge case"
        val parsed = LogcatLineParser.parse(line)
        assertNotNull(parsed)
        assertEquals("BoundaryTag", parsed.tag)
        assertTrue(parsed.tsMs > 0L)
    }

    @Test
    fun `parses padded pid and tid columns`() {
        // Logcat pads pid/tid with spaces; multi-space gaps must not break parsing.
        val line = "01-15 14:32:18.456    42      7 D MiniPids: short ids"
        val parsed = LogcatLineParser.parse(line)
        assertNotNull(parsed)
        assertEquals(42, parsed.pid)
        assertEquals(7, parsed.tid)
    }

    // ═══════ malformed input → null ═══════

    @Test
    fun `returns null on empty line`() {
        assertNull(LogcatLineParser.parse(""))
    }

    @Test
    fun `returns null on binary garbage`() {
        // Non-ASCII bytes that could leak through if the upstream UTF-8 decode
        // gave a partial character. Must not crash; must return null.
        val garbage = "\u0000\u00FF\u0001\u0002 random non-format bytes"
        assertNull(LogcatLineParser.parse(garbage))
    }

    @Test
    fun `returns null on partial line missing tid`() {
        // Only one numeric column (pid) before the level — invalid.
        val line = "01-15 14:32:18.456  1234 I AdActivity: message"
        assertNull(LogcatLineParser.parse(line))
    }

    @Test
    fun `returns null on missing timestamp`() {
        val line = "1234 5678 I AdActivity: message"
        assertNull(LogcatLineParser.parse(line))
    }

    @Test
    fun `returns null on missing level character`() {
        val line = "01-15 14:32:18.456  1234  5678 AdActivity: message"
        assertNull(LogcatLineParser.parse(line))
    }

    @Test
    fun `returns null on lowercase invalid level character`() {
        val line = "01-15 14:32:18.456  1234  5678 i AdActivity: message"
        assertNull(LogcatLineParser.parse(line))
    }

    @Test
    fun `returns null when tag is empty`() {
        val line = "01-15 14:32:18.456  1234  5678 I  : empty tag"
        assertNull(LogcatLineParser.parse(line))
    }

    @Test
    fun `returns null on plain text without log structure`() {
        assertNull(LogcatLineParser.parse("not a log line at all"))
        assertNull(LogcatLineParser.parse("--------- beginning of main"))
    }

    @Test
    fun `returns null on truncated timestamp without milliseconds`() {
        val line = "01-15 14:32:18  1234  5678 I AdActivity: message"
        assertNull(LogcatLineParser.parse(line))
    }
}
