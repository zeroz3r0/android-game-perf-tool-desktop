package com.gameperf.desktop.core

import com.gameperf.desktop.core.conclusions.Conclusion
import com.gameperf.desktop.core.conclusions.Severity
import com.gameperf.desktop.core.events.Confidence
import com.gameperf.desktop.core.events.DetectedEvent
import com.gameperf.desktop.core.events.EventType
import com.gameperf.desktop.core.metrics.MetricsAggregates
import com.gameperf.desktop.viewmodel.DetectionMode
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v4.4.1 permanent regression gate for [SessionHistory] round-trip preservation
 * of v4.4.0 fields (`events`, `detectionMode`, `detectorWarnings`, `rawAggregates`,
 * `filteredAggregates`, `conclusions`, `captureStartMs`).
 *
 * Bug 2 (auto-event-detection-not-marking) hinged on these fields being present
 * in [SessionHistory.SerializableEntry] / [SessionHistory.HistoryEntry] and
 * surviving a save → load cycle. Prior to v4.4.1 the schema was bumped to v5
 * but the data classes still carried only v4.3.x fields. The encoder silently
 * dropped them.
 *
 * If a future change removes one of the v4.4.0 fields, this test fails at the
 * field-equality assert and forces the author to make the deletion explicit.
 */
class SessionHistoryRoundTripTest {

    private lateinit var tempFile: File

    @BeforeTest
    fun setUp() {
        val dir = Files.createTempDirectory("sessionhistory-roundtrip-").toFile()
        tempFile = File(dir, "history.json")
        SessionHistory.historyFileOverride = tempFile
    }

    @AfterTest
    fun tearDown() {
        SessionHistory.historyFileOverride = null
        runCatching { tempFile.delete() }
        runCatching { tempFile.parentFile?.listFiles()?.forEach { it.delete() } }
        runCatching { tempFile.parentFile?.delete() }
    }

    private fun sampleEvent(): DetectedEvent = DetectedEvent(
        id = "ev-1",
        type = EventType.INTERSTITIAL,
        sdkSource = "AppLovin",
        startMs = 45_400L,
        endMs = 60_400L,
        confidence = Confidence.MEDIUM,
        signatureMatched = "applovin.show",
        metadata = mapOf("adUnitId" to "test-unit"),
        endInferred = false,
    )

    private fun sampleAggregates(avgFps: Int = 58): MetricsAggregates = MetricsAggregates(
        avgFps = avgFps,
        minFps = 12,
        maxFps = 61,
        p1 = 14,
        p5 = 22,
        p50 = 60,
        p90 = 60,
        p99 = 61,
        avgFrameTime = 16.95,
        p99FrameTime = 35.4,
        peakMem = 812L,
        avgCpu = 42,
        maxCpu = 85,
        maxTempCpu = 41.5,
        maxTempGpu = 40.0,
        maxTempSkin = 39.2,
        maxTempDieCpu = 88.0,
        totalJank = 27L,
        totalStutter = 4,
        sampleCount = 600,
    )

    private fun sampleConclusion(
        ruleId: String = "stable-low-fps-low-cpu",
        severity: Severity = Severity.WARNING,
    ): Conclusion = Conclusion(
        ruleId = ruleId,
        severity = severity,
        headline = "FPS bajo estable con CPU disponible.",
        recommendation = "Revisa el target FPS del juego.",
    )

    private fun fullEntry(): SessionHistory.HistoryEntry = SessionHistory.HistoryEntry(
        id = "round-1",
        name = "round-trip session",
        gamePackage = "com.vivastudios.pieceout",
        deviceModel = "Samsung SM-X200",
        grade = 'A',
        deviceGrade = 'A',
        avgFps = 58,
        duration = 600,
        date = "11/05/2026 10:20",
        reportPath = "/tmp/informe_round_1.html",
        videoPath = "/tmp/video_round_1.mp4",
        events = listOf(sampleEvent()),
        detectionMode = DetectionMode.ANDROID_FULL,
        detectorWarnings = listOf("logcat gap 3s @t=120"),
        rawAggregates = sampleAggregates(avgFps = 50),
        filteredAggregates = sampleAggregates(avgFps = 58),
        conclusions = listOf(sampleConclusion()),
        captureStartMs = 1_700_000_000_000L,
    )

    @Test
    fun `round-trip preserves v4_4_0 fields losslessly`() {
        val original = fullEntry()
        SessionHistory.addEntry(original)

        val loaded = SessionHistory.load().firstOrNull { it.id == original.id }
        assertNotNull(loaded, "Saved entry must be loadable by id")

        assertEquals(original.events, loaded.events, "events must round-trip")
        assertEquals(DetectionMode.ANDROID_FULL, loaded.detectionMode, "detectionMode must round-trip")
        assertEquals(original.detectorWarnings, loaded.detectorWarnings, "detectorWarnings must round-trip")
        assertEquals(original.rawAggregates, loaded.rawAggregates, "rawAggregates must round-trip")
        assertEquals(original.filteredAggregates, loaded.filteredAggregates, "filteredAggregates must round-trip")
        assertEquals(original.conclusions, loaded.conclusions, "conclusions must round-trip")
        assertEquals(original.captureStartMs, loaded.captureStartMs, "captureStartMs must round-trip")
    }

    @Test
    fun `legacy v4_3_x JSON without new fields loads with defaults`() {
        // Hand-rolled v4.3.x payload (no events / detectionMode / aggregates / conclusions / captureStartMs).
        val legacyJson = """
            [
              {
                "id": "legacy-1",
                "name": "legacy session",
                "gamePackage": "com.legacy.game",
                "deviceModel": "Pixel 6",
                "grade": "B",
                "deviceGrade": "B",
                "avgFps": 55,
                "duration": 300,
                "date": "01/01/2026 00:00",
                "reportPath": "",
                "videoPath": "",
                "tag": "OUR_GAME",
                "competitorName": "",
                "p1Fps": 30,
                "p5Fps": 40,
                "avgFrameTime": 18.0,
                "p95FrameTime": 25.0,
                "p99FrameTime": 30.0,
                "peakMemMb": 700,
                "avgCpu": 35,
                "maxTemp": 41.0,
                "score": 80,
                "markers": [],
                "isFavorite": false,
                "fpsTimed": []
              }
            ]
        """.trimIndent()
        tempFile.parentFile?.mkdirs()
        tempFile.writeText(legacyJson)

        val loaded = SessionHistory.load()
        assertEquals(1, loaded.size)
        val entry = loaded.first()
        assertEquals("legacy-1", entry.id)
        assertTrue(entry.events.isEmpty(), "Missing events field defaults to empty list")
        assertNull(entry.detectionMode, "Missing detectionMode defaults to null")
        assertTrue(entry.detectorWarnings.isEmpty(), "Missing detectorWarnings defaults to empty list")
        assertNull(entry.rawAggregates, "Missing rawAggregates defaults to null")
        assertNull(entry.filteredAggregates, "Missing filteredAggregates defaults to null")
        assertTrue(entry.conclusions.isEmpty(), "Missing conclusions defaults to empty list")
        assertNull(entry.captureStartMs, "Missing captureStartMs defaults to null")
    }

    @Test
    fun `round-trip preserves Severity enum on Conclusion`() {
        val high = sampleConclusion(ruleId = "rule-high", severity = Severity.CRITICAL)
        val low = sampleConclusion(ruleId = "rule-low", severity = Severity.INFO)
        val entry = fullEntry().copy(
            id = "sev-1",
            conclusions = listOf(high, low),
        )
        SessionHistory.addEntry(entry)

        val loaded = SessionHistory.load().firstOrNull { it.id == "sev-1" }
        assertNotNull(loaded)
        assertEquals(2, loaded.conclusions.size)
        assertEquals(Severity.CRITICAL, loaded.conclusions[0].severity)
        assertEquals(Severity.INFO, loaded.conclusions[1].severity)
        assertEquals("rule-high", loaded.conclusions[0].ruleId)
        assertEquals("rule-low", loaded.conclusions[1].ruleId)
    }

    @Test
    fun `round-trip with empty events list still produces valid JSON`() {
        val entry = fullEntry().copy(
            id = "empty-events",
            events = emptyList(),
            detectorWarnings = emptyList(),
            conclusions = emptyList(),
        )
        SessionHistory.addEntry(entry)

        val loaded = SessionHistory.load().firstOrNull { it.id == "empty-events" }
        assertNotNull(loaded)
        assertTrue(loaded.events.isEmpty())
        assertTrue(loaded.detectorWarnings.isEmpty())
        assertTrue(loaded.conclusions.isEmpty())
        // Aggregates and detectionMode were populated — must still survive.
        assertEquals(DetectionMode.ANDROID_FULL, loaded.detectionMode)
        assertNotNull(loaded.rawAggregates)
        assertNotNull(loaded.filteredAggregates)
    }

    @Test
    fun `round-trip preserves thermalAvailable=true (v4_4_1 happy path)`() {
        val entry = fullEntry().copy(
            id = "thermal-true",
            // Default true is the v4.3.x-compatible value — assert it survives the round trip.
        )
        SessionHistory.addEntry(entry)

        val loaded = SessionHistory.load().firstOrNull { it.id == "thermal-true" }
        assertNotNull(loaded)
        assertTrue(loaded.thermalAvailable, "thermalAvailable=true must round-trip")
    }

    @Test
    fun `round-trip preserves thermalAvailable=false (v4_4_1 unsupported vendor)`() {
        val entry = fullEntry().copy(
            id = "thermal-false",
            thermalAvailable = false,
        )
        SessionHistory.addEntry(entry)

        val loaded = SessionHistory.load().firstOrNull { it.id == "thermal-false" }
        assertNotNull(loaded)
        assertFalse(loaded.thermalAvailable, "thermalAvailable=false must round-trip")
    }

    @Test
    fun `legacy v4_3_x JSON without thermalAvailable defaults to true`() {
        // Hand-rolled v4.3.x payload (lacks thermalAvailable). Default true preserves the
        // pre-v4.4.1 semantics: report renders the numeric temp instead of the N/D banner.
        val legacyJson = """
            [
              {
                "id": "legacy-thermal",
                "name": "legacy session",
                "gamePackage": "com.legacy.game",
                "deviceModel": "Pixel 6",
                "grade": "B",
                "deviceGrade": "B",
                "avgFps": 55,
                "duration": 300,
                "date": "01/01/2026 00:00",
                "reportPath": "",
                "videoPath": "",
                "tag": "OUR_GAME",
                "competitorName": "",
                "isFavorite": false
              }
            ]
        """.trimIndent()
        tempFile.parentFile?.mkdirs()
        tempFile.writeText(legacyJson)

        val loaded = SessionHistory.load()
        assertEquals(1, loaded.size)
        assertTrue(
            loaded.first().thermalAvailable,
            "Missing thermalAvailable field defaults to true for v4.3.x compat",
        )
    }

    @Test
    fun `unknown detectionMode string decodes to null`() {
        // Hand-rolled payload with a detectionMode string we don't recognize
        // (simulates a future enum value or a hand-edited file).
        val payload = """
            [
              {
                "id": "future-1",
                "name": "future mode",
                "gamePackage": "com.future.game",
                "deviceModel": "Pixel 99",
                "grade": "A",
                "deviceGrade": "A",
                "avgFps": 60,
                "duration": 60,
                "date": "01/01/2030 00:00",
                "reportPath": "",
                "videoPath": "",
                "tag": "OUR_GAME",
                "competitorName": "",
                "isFavorite": false,
                "detectionMode": "FUTURE_MODE_XYZ"
              }
            ]
        """.trimIndent()
        tempFile.parentFile?.mkdirs()
        tempFile.writeText(payload)

        val loaded = SessionHistory.load()
        assertEquals(1, loaded.size)
        assertNull(loaded.first().detectionMode, "Unknown enum string falls back to null")
    }
}
