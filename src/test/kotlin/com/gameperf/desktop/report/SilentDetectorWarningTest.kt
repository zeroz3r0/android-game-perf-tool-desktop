package com.gameperf.desktop.report

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * v4.8.0 — events-catalog-and-device-naming spec `event-detection-fidelity`
 * R2 scenarios. Covers the silent-detector warning helper that surfaces a
 * contextual callout when the detector ran for the full session but observed
 * zero meaningful events (a common signature of Unity / Unreal release
 * builds with `Debug.Log` stripped — the catalog has nothing to match).
 *
 * The helper is pure (no I/O, no mutable state), so the tests drive
 * [ReportGenerator.shouldShowSilentDetectorWarning] directly. The HTML
 * rendering integration is covered separately by [ReportRenderingTest].
 *
 * Scenario map (spec event-detection-fidelity R2.S1..R2.S5 + custom-threshold):
 *  - R2.S1: 5 min, 0 SDK events, 0 instrumented → warning visible
 *  - R2.S2: 5 min, 0 SDK events, 3 instrumented → warning NOT visible
 *  - R2.S3: 1 min (< 2 min threshold), 0 events → warning NOT visible
 *  - R2.S4: detector did NOT run → warning NOT visible
 *  - Custom threshold (5 min) on 3 min session with 0 events → NOT visible
 *
 * @since v4.8.0
 */
class SilentDetectorWarningTest {

    // R2.S1 — silent detector, full-session signal, zero events → warn.
    @Test
    fun `warns when detector ran 5 min and observed zero meaningful events`() {
        val show = ReportGenerator.shouldShowSilentDetectorWarning(
            detectorWasActive = true,
            sessionDurationMs = 5L * 60_000L,
            meaningfulEventsCount = 0,
        )
        assertTrue(show, "5min silent session with active detector must surface the callout")
    }

    // R2.S2 — instrumented events present → no warning (real detection signal).
    @Test
    fun `does not warn when at least one meaningful event was detected`() {
        val show = ReportGenerator.shouldShowSilentDetectorWarning(
            detectorWasActive = true,
            sessionDurationMs = 5L * 60_000L,
            meaningfulEventsCount = 3,
        )
        assertFalse(show, "3 instrumented events constitute real detection — no warning")
    }

    // R2.S3 — session shorter than 2 min threshold → inconclusive, no warning.
    @Test
    fun `does not warn when session shorter than default threshold`() {
        val show = ReportGenerator.shouldShowSilentDetectorWarning(
            detectorWasActive = true,
            sessionDurationMs = 60_000L, // 1 minute, below 2 min default
            meaningfulEventsCount = 0,
        )
        assertFalse(show, "1min session is too short to conclude — no warning")
    }

    // R2.S4 — detector did not run → no warning (the detection-mode banner
    // already discloses this).
    @Test
    fun `does not warn when detector was not active`() {
        val show = ReportGenerator.shouldShowSilentDetectorWarning(
            detectorWasActive = false,
            sessionDurationMs = 5L * 60_000L,
            meaningfulEventsCount = 0,
        )
        assertFalse(show, "inactive detector must not surface the silent-detector warning")
    }

    // Custom threshold variant — caller can opt into a longer window.
    @Test
    fun `respects custom threshold when caller overrides the default`() {
        val show = ReportGenerator.shouldShowSilentDetectorWarning(
            detectorWasActive = true,
            sessionDurationMs = 3L * 60_000L,
            meaningfulEventsCount = 0,
            thresholdMs = 4L * 60_000L,
        )
        assertFalse(show, "3min session under custom 4min threshold must not warn")
    }

    // Symmetric custom-threshold positive — confirms the threshold parameter
    // wires through both directions (above and below).
    @Test
    fun `warns when custom threshold is met exactly`() {
        val show = ReportGenerator.shouldShowSilentDetectorWarning(
            detectorWasActive = true,
            sessionDurationMs = 4L * 60_000L,
            meaningfulEventsCount = 0,
            thresholdMs = 4L * 60_000L,
        )
        assertTrue(show, "session matching the threshold exactly must trigger the warning")
    }
}
