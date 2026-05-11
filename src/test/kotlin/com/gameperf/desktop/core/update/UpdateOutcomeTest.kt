package com.gameperf.desktop.core.update

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RED-first tests for [UpdateOutcome]. These tests describe the contract
 * before the production type exists.
 *
 * UpdateOutcome is a sealed class with 6 variants per design §3 + error matrix §6:
 *   - Success
 *   - FailedUacDenied
 *   - FailedWatchdogTimeout
 *   - FailedDownload(httpStatus: Int?, message: String)
 *   - FailedHelperCrash(exitCode: Int?)
 *   - FailedUnknown(message: String)
 *
 * Each variant must be @Serializable so it can roundtrip in [UpdateAttempt].
 */
class UpdateOutcomeTest {

    @Test
    fun `Success variant is constructible and singleton-like`() {
        val outcome: UpdateOutcome = UpdateOutcome.Success
        assertEquals(UpdateOutcome.Success, outcome)
    }

    @Test
    fun `FailedUacDenied variant is constructible`() {
        val outcome: UpdateOutcome = UpdateOutcome.FailedUacDenied
        assertEquals(UpdateOutcome.FailedUacDenied, outcome)
    }

    @Test
    fun `FailedWatchdogTimeout variant is constructible`() {
        val outcome: UpdateOutcome = UpdateOutcome.FailedWatchdogTimeout
        assertEquals(UpdateOutcome.FailedWatchdogTimeout, outcome)
    }

    @Test
    fun `FailedDownload carries httpStatus and message`() {
        val outcome = UpdateOutcome.FailedDownload(httpStatus = 404, message = "asset not found")
        assertEquals(404, outcome.httpStatus)
        assertEquals("asset not found", outcome.message)
    }

    @Test
    fun `FailedDownload allows null httpStatus`() {
        val outcome = UpdateOutcome.FailedDownload(httpStatus = null, message = "connection reset")
        assertEquals(null, outcome.httpStatus)
        assertEquals("connection reset", outcome.message)
    }

    @Test
    fun `FailedHelperCrash carries optional exitCode`() {
        val outcome = UpdateOutcome.FailedHelperCrash(exitCode = 1)
        assertEquals(1, outcome.exitCode)
    }

    @Test
    fun `FailedHelperCrash allows null exitCode`() {
        val outcome = UpdateOutcome.FailedHelperCrash(exitCode = null)
        assertEquals(null, outcome.exitCode)
    }

    @Test
    fun `FailedUnknown carries message`() {
        val outcome = UpdateOutcome.FailedUnknown(message = "spawn IOException: helper.ps1 missing")
        assertEquals("spawn IOException: helper.ps1 missing", outcome.message)
    }

    @Test
    fun `when over UpdateOutcome covers all 6 variants exhaustively`() {
        // This test will fail to compile if a new variant is added without
        // updating the when. That is the entire point of using a sealed class.
        val outcomes: List<UpdateOutcome> = listOf(
            UpdateOutcome.Success,
            UpdateOutcome.FailedUacDenied,
            UpdateOutcome.FailedWatchdogTimeout,
            UpdateOutcome.FailedDownload(500, "server error"),
            UpdateOutcome.FailedHelperCrash(42),
            UpdateOutcome.FailedUnknown("???"),
        )
        val labels = outcomes.map { outcome ->
            when (outcome) {
                is UpdateOutcome.Success -> "success"
                is UpdateOutcome.FailedUacDenied -> "uac"
                is UpdateOutcome.FailedWatchdogTimeout -> "watchdog"
                is UpdateOutcome.FailedDownload -> "download"
                is UpdateOutcome.FailedHelperCrash -> "crash"
                is UpdateOutcome.FailedUnknown -> "unknown"
            }
        }
        assertEquals(listOf("success", "uac", "watchdog", "download", "crash", "unknown"), labels)
    }

    @Test
    fun `UpdateOutcome roundtrips through polymorphic JSON`() {
        val json = Json { classDiscriminator = "kind" }
        val original: UpdateOutcome = UpdateOutcome.FailedDownload(httpStatus = 503, message = "service unavailable")

        val encoded = json.encodeToString(UpdateOutcome.serializer(), original)
        val decoded = json.decodeFromString(UpdateOutcome.serializer(), encoded)

        assertTrue(decoded is UpdateOutcome.FailedDownload)
        assertEquals(503, decoded.httpStatus)
        assertEquals("service unavailable", decoded.message)
    }
}
