package com.gameperf.desktop.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [NetworkBandwidthParser]. Pure object — no mocks, no I/O.
 *
 * Coverage:
 *  - NET-005 [parseDumpsysNetstats]: happy path multi-bucket, UID absent,
 *    malformed lines, empty, negative bytes rejected.
 *  - NET-006 [parseServiceCallResponse]: happy path 4×int64 hex, malformed,
 *    missing parcel, fewer than 4 tokens, non-hex.
 *  - NET-010 [isPlausibleBytes]: window `[0, 100 GB]`.
 *
 * Top-level regex tested implicitly via the entry-point methods.
 */
class NetworkBandwidthParserTest {

    // ── NET-005 parseDumpsysNetstats ─────────────────────────────────────

    @Test
    fun `parseDumpsysNetstats sums two buckets for the same UID`() {
        val raw = """
            Active interfaces:
            uid=10234
              0x10000000 wlan0 DEFAULT NO_ROAMING 1000 5 200 3
              0x10000000 wlan0 DEFAULT NO_ROAMING 2000 7 400 4
        """.trimIndent()
        val result = NetworkBandwidthParser.parseDumpsysNetstats(raw, 10234)
        assertEquals(3000L to 600L, result)
    }

    @Test
    fun `parseDumpsysNetstats returns null when no buckets parse`() {
        val raw = """
            Active interfaces:
            uid=10234
              this line does not match the bucket regex
              0xZZZZ malformed line
        """.trimIndent()
        val result = NetworkBandwidthParser.parseDumpsysNetstats(raw, 10234)
        assertNull(result, "no parseable bucket means null, not (0,0)")
    }

    @Test
    fun `parseDumpsysNetstats returns null on empty input`() {
        assertNull(NetworkBandwidthParser.parseDumpsysNetstats("", 10234))
        assertNull(NetworkBandwidthParser.parseDumpsysNetstats("   \n  \n", 10234))
    }

    @Test
    fun `parseDumpsysNetstats handles UID absent block`() {
        // When dumpsys returned no buckets for this UID, output may still
        // contain unrelated bucket lines from other UIDs. The needle
        // `uid=<uid>` is missing — parser walks the whole input but if no
        // bucket regex matches, returns null.
        val raw = """
            Active interfaces:
            (no per-uid data available)
        """.trimIndent()
        assertNull(NetworkBandwidthParser.parseDumpsysNetstats(raw, 99999))
    }

    @Test
    fun `parseDumpsysNetstats rejects negative byte values`() {
        // A vendor-shifted layout could put a negative-looking field in the
        // rx slot. Parser must skip such lines, not propagate.
        val raw = """
            uid=10234
              0x10000000 wlan0 DEFAULT NO_ROAMING -1 5 200 3
              0x10000000 wlan0 DEFAULT NO_ROAMING 1000 5 200 3
        """.trimIndent()
        val result = NetworkBandwidthParser.parseDumpsysNetstats(raw, 10234)
        // Negative line dropped; only the second bucket counts.
        assertEquals(1000L to 200L, result)
    }

    // ── NET-006 parseServiceCallResponse ─────────────────────────────────

    @Test
    fun `parseServiceCallResponse decodes 4 hex int64s into rx and tx pairs`() {
        // High|Low for rxBytes = 0x00000000_00000064 = 100
        // High|Low for txBytes = 0x00000000_00000200 = 512
        val raw = "Result: Parcel(00000000 00000064 00000000 00000200   '.....d...... ..')"
        val result = NetworkBandwidthParser.parseServiceCallResponse(raw)
        assertEquals(100L to 512L, result)
    }

    @Test
    fun `parseServiceCallResponse returns null when Parcel payload missing`() {
        assertNull(NetworkBandwidthParser.parseServiceCallResponse("Result: NULL"))
        assertNull(NetworkBandwidthParser.parseServiceCallResponse(""))
        assertNull(NetworkBandwidthParser.parseServiceCallResponse("garbage"))
    }

    @Test
    fun `parseServiceCallResponse returns null when fewer than 4 hex words`() {
        val raw = "Result: Parcel(00000000 00000064  '.....')"
        assertNull(NetworkBandwidthParser.parseServiceCallResponse(raw))
    }

    @Test
    fun `parseServiceCallResponse returns null when a token is not hex`() {
        val raw = "Result: Parcel(00000000 ZZZZZZZZ 00000000 00000200   '....')"
        assertNull(NetworkBandwidthParser.parseServiceCallResponse(raw))
    }

    // ── NET-010 isPlausibleBytes window ──────────────────────────────────

    @Test
    fun `isPlausibleBytes accepts the boundary values`() {
        assertTrue(NetworkBandwidthParser.isPlausibleBytes(0L), "0 is plausible")
        // 100 GB exactly = upper bound (inclusive per NET-010)
        val hundredGB = 100L * 1024L * 1024L * 1024L
        assertTrue(NetworkBandwidthParser.isPlausibleBytes(hundredGB), "100 GB is plausible (inclusive)")
    }

    @Test
    fun `isPlausibleBytes rejects negative and out-of-range`() {
        assertFalse(NetworkBandwidthParser.isPlausibleBytes(-1L))
        assertFalse(NetworkBandwidthParser.isPlausibleBytes(-100L))
        // 200 GB = beyond plausibility window
        val twoHundredGB = 200L * 1024L * 1024L * 1024L
        assertFalse(NetworkBandwidthParser.isPlausibleBytes(twoHundredGB))
    }

    @Test
    fun `isPlausibleBytes accepts a typical session size`() {
        val tenMB = 10L * 1024L * 1024L
        assertTrue(NetworkBandwidthParser.isPlausibleBytes(tenMB), "10 MB session size is plausible")
    }
}
