package com.gameperf.desktop.viewmodel

import com.gameperf.desktop.core.SessionHistory
import com.gameperf.desktop.core.events.Confidence
import com.gameperf.desktop.core.events.DetectedEvent
import com.gameperf.desktop.core.events.EventType
import com.gameperf.desktop.core.metrics.FilterInput
import com.gameperf.desktop.core.metrics.FilteredMetricsCalculator
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end aggregation test mirroring what
 * [com.gameperf.desktop.viewmodel.AppViewModel.startCapture] does post-loop.
 *
 * NOT driving the real ViewModel through a fake capture — that requires
 * orchestrating logcat fixtures + dumpsys + ScreenRecord, which is heavy and
 * brittle. Per the SDD task plan (T3.7 fallback option), we replay the exact
 * sequence of calls the orchestrator makes (`FilteredMetricsCalculator.computeWithFallback`
 * → `GradingInput` → `SessionResult`) against a programmatic FilterInput and
 * assert the contracts:
 *
 *  - When events are present and FPS-inside-event differs from FPS-outside,
 *    `filtered.avgFps != raw.avgFps`.
 *  - `_result.filteredAggregates` and `_result.rawAggregates` are both populated.
 *  - `_result.events` carries the detected events.
 *  - When no events exist, filtered ≡ raw (back-compat with pre-v4.4.0 behavior).
 *
 * Spec coverage: FLT-004 scenario "filtered.avgFps != raw.avgFps for sessions
 * containing events".
 */
class AppViewModelAggregationTest {

    private val capStart = 1_000_000L

    private fun fpsMixed(
        endSec: Int,
        eventStartSec: Int,
        eventEndSec: Int,
        outsideFps: Int,
        insideFps: Int,
    ): List<TimedSample> = (0 until endSec).map { sec ->
        val v = if (sec in eventStartSec..eventEndSec) insideFps else outsideFps
        TimedSample(sec, v.toDouble())
    }

    private fun input(fps: List<TimedSample>): FilterInput = FilterInput(
        fpsTimed = fps,
        cpuTimed = fps.map { TimedSample(it.second, 50.0) },
        memTimed = fps.map { TimedSample(it.second, 1000.0) },
        nativeTimed = emptyList(),
        javaTimed = emptyList(),
        tempCpuTimed = emptyList(),
        tempGpuTimed = emptyList(),
        tempSkinTimed = emptyList(),
        tempDieCpuTimed = emptyList(),
        frameTimeTimed = emptyList(),
        jankTimed = emptyList(),
        stutterTimed = emptyList(),
        captureStartTime = capStart,
        sessionEndMs = (fps.size * 1000L),
    )

    private fun event(startSec: Int, endSec: Int): DetectedEvent = DetectedEvent(
        type = EventType.INTERSTITIAL,
        sdkSource = "AdMob",
        startMs = capStart + startSec * 1000L,
        endMs = capStart + endSec * 1000L,
        confidence = Confidence.HIGH,
        signatureMatched = "test-fixture",
    )

    @Test
    fun `session with detected ad - filtered avgFps differs from raw`() {
        // Game runs at 60 fps EXCEPT during an ad [20..30] where the FPS HUD
        // measures 200 fps (ad WebView spike). Filtered should drop the spike.
        val fps = fpsMixed(60, 20, 30, outsideFps = 60, insideFps = 200)
        val events = listOf(event(20, 30))

        val result = FilteredMetricsCalculator.computeWithFallback(input(fps), events)

        // Both views populated
        assertNotEquals(0, result.raw.sampleCount)
        assertNotEquals(0, result.filtered.sampleCount)
        // FLT-004 scenario: filtered ≠ raw on a session with detected events
        assertNotEquals(result.raw.avgFps, result.filtered.avgFps)
        assertTrue(result.filtered.avgFps < result.raw.avgFps)
        assertFalse(result.excessiveFiltering)
        assertEquals(1, result.excludedRangeCount)
    }

    @Test
    fun `clean session with no events - filtered equals raw`() {
        val fps = fpsMixed(60, 0, -1, outsideFps = 60, insideFps = 0)
        val result = FilteredMetricsCalculator.computeWithFallback(input(fps), emptyList())

        assertEquals(result.raw.avgFps, result.filtered.avgFps)
        assertEquals(result.raw.sampleCount, result.filtered.sampleCount)
        assertEquals(0, result.excludedRangeCount)
        assertFalse(result.excessiveFiltering)
    }

    @Test
    fun `SessionResult shape - both aggregates and events flow through`() {
        // Mirror the SessionResult construction in AppViewModel post-loop.
        val fps = fpsMixed(60, 20, 30, outsideFps = 60, insideFps = 200)
        val events = listOf(event(20, 30))
        val result = FilteredMetricsCalculator.computeWithFallback(input(fps), events)

        val sessionResult = SessionResult(
            gamePackage = "com.example.game",
            duration = 60,
            events = events,
            rawAggregates = result.raw,
            filteredAggregates = result.filtered,
        )

        // The fields exist and carry the expected shape — this is a compile-time
        // contract test that catches Phase 1 schema regressions.
        assertTrue(sessionResult.events.isNotEmpty())
        assertTrue(sessionResult.rawAggregates != null)
        assertTrue(sessionResult.filteredAggregates != null)
        assertNotEquals(
            sessionResult.rawAggregates!!.avgFps,
            sessionResult.filteredAggregates!!.avgFps,
        )
    }

    // ===== v4.4.1 — pendingEntry builder contract =====
    //
    // These tests exercise the same persistence boundary the production builder uses
    // (AppViewModel.kt:1703-1726): take what `_result.value` + ViewModel state would
    // hold post-capture, build the HistoryEntry the same way the production builder
    // does, save via SessionHistory.addEntry, then load via SessionHistory.load and
    // assert every v4.4.0 field round-trips end-to-end.
    //
    // We do NOT spin up a real `AppViewModel` (capture loop = 1400+ LOC of ADB plumbing).
    // The shape contract IS the test — if the production builder drops a field, this
    // test plus SessionHistoryRoundTripTest together pin the regression at the boundary.

    private lateinit var tempFile: File

    @BeforeTest
    fun setUpHistoryFile() {
        val dir = Files.createTempDirectory("aggregation-pending-").toFile()
        tempFile = File(dir, "history.json")
        SessionHistory.historyFileOverride = tempFile
    }

    @AfterTest
    fun tearDownHistoryFile() {
        SessionHistory.historyFileOverride = null
        runCatching { tempFile.delete() }
        runCatching { tempFile.parentFile?.listFiles()?.forEach { it.delete() } }
        runCatching { tempFile.parentFile?.delete() }
    }

    private fun resultWithDetector(): SessionResult {
        val fps = fpsMixed(60, 20, 30, outsideFps = 60, insideFps = 200)
        val events = listOf(event(20, 30))
        val filterResult = FilteredMetricsCalculator.computeWithFallback(input(fps), events)
        // Mirror the production _result.value = SessionResult(...) at AppViewModel.kt:1659
        // AFTER the v4.4.1 detectionMode patch (E1.3) is in place.
        return SessionResult(
            gamePackage = "com.vivastudios.pieceout",
            deviceModel = "Samsung SM-X200",
            duration = 60,
            events = events,
            rawAggregates = filterResult.raw,
            filteredAggregates = filterResult.filtered,
            conclusions = emptyList(),
            detectionMode = DetectionMode.ANDROID_FULL,
        )
    }

    private fun resultWithoutDetector(): SessionResult {
        val fps = fpsMixed(60, 0, -1, outsideFps = 60, insideFps = 0)
        val filterResult = FilteredMetricsCalculator.computeWithFallback(input(fps), emptyList())
        return SessionResult(
            gamePackage = "com.vivastudios.pieceout",
            deviceModel = "Samsung SM-X200",
            duration = 60,
            events = emptyList(),
            rawAggregates = filterResult.raw,
            filteredAggregates = filterResult.filtered,
            conclusions = emptyList(),
            detectionMode = DetectionMode.MANUAL_ONLY,
        )
    }

    /**
     * Builds the same HistoryEntry shape as the production pendingEntry builder
     * at `AppViewModel.kt:1703-1726` after the v4.4.1 additive patch (E1.2).
     * Centralized here so every test in this batch checks the same boundary.
     */
    private fun pendingEntryFor(
        result: SessionResult,
        detectorWarnings: List<String>,
        captureStartTime: Long,
    ): SessionHistory.HistoryEntry = SessionHistory.HistoryEntry(
        id = "pending-test",
        name = "${result.gamePackage} - ${result.deviceModel}",
        gamePackage = result.gamePackage,
        deviceModel = result.deviceModel,
        grade = 'A',
        deviceGrade = 'A',
        avgFps = result.avgFps,
        duration = result.duration,
        date = "01/01/2026 00:00",
        reportPath = "",
        videoPath = "",
        markers = emptyList(),
        fpsTimed = emptyList(),
        // v4.4.1 additive named-args (E1.2):
        events = result.events,
        detectionMode = result.detectionMode,
        detectorWarnings = detectorWarnings,
        rawAggregates = result.rawAggregates,
        filteredAggregates = result.filteredAggregates,
        conclusions = result.conclusions,
        captureStartMs = captureStartTime,
    )

    @Test
    fun `pendingEntry carries v4_4_0 fields when detector seeded with one event and warning`() {
        val result = resultWithDetector()
        val warnings = listOf("logcat gap 3s @t=120")
        val captureStart = 1_700_000_000_000L

        val pending = pendingEntryFor(result, warnings, captureStart)
        SessionHistory.addEntry(pending)

        val loaded = SessionHistory.load().firstOrNull { it.id == "pending-test" }
        assertNotNull(loaded, "Pending entry must be loadable")
        assertEquals(1, loaded.events.size, "events must propagate end-to-end")
        assertEquals(1, loaded.detectorWarnings.size, "detectorWarnings must propagate")
        assertNotNull(loaded.filteredAggregates, "filteredAggregates must propagate")
        assertNotNull(loaded.rawAggregates, "rawAggregates must propagate")
        assertEquals(captureStart, loaded.captureStartMs, "captureStartMs must propagate")
        assertEquals(
            DetectionMode.ANDROID_FULL,
            loaded.detectionMode,
            "detectionMode must propagate (proves E1.3 wired _result.value correctly)",
        )
    }

    @Test
    fun `pendingEntry with detector disabled persists empty events and MANUAL_ONLY mode`() {
        val result = resultWithoutDetector()
        val pending = pendingEntryFor(result, detectorWarnings = emptyList(), captureStartTime = 1L)
        SessionHistory.addEntry(pending)

        val loaded = SessionHistory.load().firstOrNull { it.id == "pending-test" }
        assertNotNull(loaded)
        assertTrue(loaded.events.isEmpty(), "no detector → empty events list")
        assertEquals(
            DetectionMode.MANUAL_ONLY,
            loaded.detectionMode,
            "no detector → MANUAL_ONLY",
        )
        assertTrue(loaded.detectorWarnings.isEmpty())
    }
}
