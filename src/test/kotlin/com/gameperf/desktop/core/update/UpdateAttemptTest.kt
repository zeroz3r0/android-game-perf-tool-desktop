package com.gameperf.desktop.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RED-first tests for [UpdateAttempt] + jsonl line writer/parser helpers.
 *
 * UpdateAttempt is a pure @Serializable data class describing a single
 * update attempt for [UpdateHistoryStore]'s jsonl persistence (cap-100).
 *
 * Helpers [writeJsonlLine] / [parseJsonlLine] sit alongside it: one attempt
 * per line, manual JSON line writer per design ADR-1.
 */
class UpdateAttemptTest {

    @Test
    fun `UpdateAttempt construction stores all fields`() {
        val attempt = UpdateAttempt(
            timestamp = 1_700_000_000_000L,
            fromVersion = "4.4.0",
            toVersion = "4.4.1",
            outcome = UpdateOutcome.Success,
            durationMs = 1234L,
            errorMessage = null,
            helperLogTail = null,
        )
        assertEquals(1_700_000_000_000L, attempt.timestamp)
        assertEquals("4.4.0", attempt.fromVersion)
        assertEquals("4.4.1", attempt.toVersion)
        assertEquals(UpdateOutcome.Success, attempt.outcome)
        assertEquals(1234L, attempt.durationMs)
        assertNull(attempt.errorMessage)
        assertNull(attempt.helperLogTail)
    }

    @Test
    fun `UpdateAttempt errorMessage and helperLogTail default to null`() {
        val attempt = UpdateAttempt(
            timestamp = 1L,
            fromVersion = "4.4.0",
            toVersion = "4.4.1",
            outcome = UpdateOutcome.Success,
            durationMs = 500L,
        )
        assertNull(attempt.errorMessage)
        assertNull(attempt.helperLogTail)
    }

    @Test
    fun `writeJsonlLine produces single line without trailing newline`() {
        val attempt = UpdateAttempt(
            timestamp = 1_700_000_000_000L,
            fromVersion = "4.4.0",
            toVersion = "4.4.1",
            outcome = UpdateOutcome.Success,
            durationMs = 1500L,
        )
        val line = writeJsonlLine(attempt)
        assertTrue(line.isNotEmpty(), "line must not be empty")
        assertTrue(!line.contains('\n'), "line must not contain embedded newline; got: $line")
    }

    @Test
    fun `writeJsonlLine then parseJsonlLine roundtrips Success outcome losslessly`() {
        val original = UpdateAttempt(
            timestamp = 1_700_000_000_000L,
            fromVersion = "4.4.0",
            toVersion = "4.4.1",
            outcome = UpdateOutcome.Success,
            durationMs = 1500L,
            errorMessage = null,
            helperLogTail = "===== UAC update helper started =====",
        )
        val line = writeJsonlLine(original)
        val parsed = parseJsonlLine(line)
        assertEquals(original, parsed)
    }

    @Test
    fun `writeJsonlLine then parseJsonlLine roundtrips FailedDownload with payload`() {
        val original = UpdateAttempt(
            timestamp = 1_700_000_000_000L,
            fromVersion = "4.3.8",
            toVersion = "4.4.0",
            outcome = UpdateOutcome.FailedDownload(httpStatus = 404, message = "asset not found"),
            durationMs = 250L,
            errorMessage = "no JAR asset for windows",
            helperLogTail = null,
        )
        val parsed = parseJsonlLine(writeJsonlLine(original))
        assertEquals(original, parsed)
    }

    @Test
    fun `writeJsonlLine then parseJsonlLine roundtrips FailedWatchdogTimeout`() {
        val original = UpdateAttempt(
            timestamp = 1_700_000_111_111L,
            fromVersion = "4.4.0",
            toVersion = "4.4.1",
            outcome = UpdateOutcome.FailedWatchdogTimeout,
            durationMs = 8000L,
            errorMessage = "watchdog timed out at 8000ms",
            helperLogTail = "[JVM 1700000111111] outer PS launching for v4.4.0->v4.4.1\n",
        )
        val parsed = parseJsonlLine(writeJsonlLine(original))
        assertEquals(original, parsed)
    }

    @Test
    fun `parseJsonlLine returns null for malformed JSON instead of throwing`() {
        val parsed = parseJsonlLine("this is not json")
        assertNull(parsed)
    }

    @Test
    fun `parseJsonlLine returns null for empty string instead of throwing`() {
        val parsed = parseJsonlLine("")
        assertNull(parsed)
    }

    @Test
    fun `writeJsonlLine escapes special characters in error fields`() {
        val original = UpdateAttempt(
            timestamp = 42L,
            fromVersion = "4.4.0",
            toVersion = "4.4.1",
            outcome = UpdateOutcome.FailedUnknown("error with \"quotes\" and \nnewline"),
            durationMs = 0L,
            errorMessage = "tab\there",
            helperLogTail = null,
        )
        val line = writeJsonlLine(original)
        // No raw newlines must leak into the jsonl line — they would corrupt the file.
        assertTrue(!line.contains('\n'), "newlines in payload must be escaped; got: $line")
        val parsed = parseJsonlLine(line)
        assertNotNull(parsed)
        assertEquals(original, parsed)
    }
}
