package com.gameperf.desktop.core

import com.gameperf.desktop.core.conclusions.Conclusion
import com.gameperf.desktop.core.conclusions.Severity
import com.gameperf.desktop.core.devactions.ActionStep
import com.gameperf.desktop.core.devactions.CodeAreaHint
import com.gameperf.desktop.core.devactions.DevActionBrief
import com.gameperf.desktop.core.devactions.DevActionEvidence
import com.gameperf.desktop.core.devactions.DevActionItem
import com.gameperf.desktop.core.devactions.GameEngine
import com.gameperf.desktop.core.events.Confidence
import com.gameperf.desktop.core.events.DetectedEvent
import com.gameperf.desktop.core.events.EventType
import com.gameperf.desktop.core.metrics.MetricsAggregates
import com.gameperf.desktop.core.model.FPowerDiagnostic
import com.gameperf.desktop.core.model.FPowerUnavailableReason
import com.gameperf.desktop.viewmodel.DetectionMode
import com.gameperf.desktop.viewmodel.TimedSample
import com.gameperf.desktop.core.devactions.Confidence as DevConfidence
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

    // ===== v4.5.0 — FPower fields round-trip =====

    @Test
    fun `round-trip preserves fpower happy path (available + history)`() {
        val history = listOf(38.4, 42.1, 50.0, 51.2)
        val timed = history.mapIndexed { i, v -> TimedSample(i * 2, v) }
        val entry = fullEntry().copy(
            id = "fpower-happy",
            fpowerAvailable = true,
            fpowerHistory = history,
            fpowerTimed = timed,
            fpowerAvg = history.average(),
            fpowerPeak = history.max(),
        )
        SessionHistory.addEntry(entry)

        val loaded = SessionHistory.load().firstOrNull { it.id == "fpower-happy" }
        assertNotNull(loaded)
        assertTrue(loaded.fpowerAvailable, "fpowerAvailable=true must round-trip")
        assertEquals(history, loaded.fpowerHistory, "fpowerHistory must round-trip")
        assertEquals(timed.size, loaded.fpowerTimed.size, "fpowerTimed arity must match")
        // TimedSample is a data class — element equality is structural.
        assertEquals(timed, loaded.fpowerTimed, "fpowerTimed must round-trip element-equal")
        assertEquals(history.average(), loaded.fpowerAvg, 0.0001)
        assertEquals(history.max(), loaded.fpowerPeak, 0.0001)
        assertNull(loaded.fpowerDiagnostic, "happy path has no diagnostic")
    }

    @Test
    fun `round-trip preserves fpower unavailable path with diagnostic`() {
        val diag = FPowerDiagnostic(
            rawPathsTried = listOf(
                "/sys/class/power_supply/battery/current_now",
                "/sys/class/power_supply/battery/voltage_now",
                "/sys/class/power_supply/battery/batt_current_ua_now",
            ),
            lastReadout = mapOf(
                "/sys/class/power_supply/battery/current_now" to "",
                "/sys/class/power_supply/battery/voltage_now" to "",
            ),
            reason = FPowerUnavailableReason.BATTERY_PATH_MISSING,
        )
        val entry = fullEntry().copy(
            id = "fpower-unavail",
            fpowerAvailable = false,
            fpowerDiagnostic = diag,
            fpowerHistory = emptyList(),
            fpowerTimed = emptyList(),
        )
        SessionHistory.addEntry(entry)

        val loaded = SessionHistory.load().firstOrNull { it.id == "fpower-unavail" }
        assertNotNull(loaded)
        assertFalse(loaded.fpowerAvailable, "fpowerAvailable=false must round-trip")
        val diag2 = loaded.fpowerDiagnostic
        assertNotNull(diag2)
        assertEquals(FPowerUnavailableReason.BATTERY_PATH_MISSING, diag2.reason)
        assertEquals(3, diag2.rawPathsTried.size)
        assertEquals(2, diag2.lastReadout.size)
        assertTrue(loaded.fpowerHistory.isEmpty())
    }

    @Test
    fun `legacy v4_4_1 JSON without fpower fields loads with defaults (FPW-012)`() {
        // Hand-rolled v4.4.1 payload — has thermalAvailable but no fpower fields.
        // FPW-012 backward compat: fpowerAvailable=true, history empty, diagnostic null.
        val legacyJson = """
            [
              {
                "id": "legacy-v441",
                "name": "legacy v4.4.1 session",
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
                "isFavorite": false,
                "thermalAvailable": true
              }
            ]
        """.trimIndent()
        tempFile.parentFile?.mkdirs()
        tempFile.writeText(legacyJson)

        val loaded = SessionHistory.load()
        assertEquals(1, loaded.size)
        val entry = loaded.first()
        assertTrue(
            entry.fpowerAvailable,
            "Missing fpowerAvailable defaults to true (v4.4.1 compat)",
        )
        assertNull(entry.fpowerDiagnostic, "Missing fpowerDiagnostic defaults to null")
        assertTrue(entry.fpowerHistory.isEmpty(), "Missing fpowerHistory defaults to empty")
        assertTrue(entry.fpowerTimed.isEmpty(), "Missing fpowerTimed defaults to empty")
        assertEquals(0.0, entry.fpowerAvg, "Missing fpowerAvg defaults to 0.0")
        assertEquals(0.0, entry.fpowerPeak, "Missing fpowerPeak defaults to 0.0")
    }

    // ===== v4.5.0 Sprint 3 — DevActionBrief round-trip =====

    private fun sampleBrief(): DevActionBrief = DevActionBrief(
        items = listOf(
            DevActionItem(
                ruleId = "stable-low-fps-low-cpu",
                severity = Severity.CRITICAL,
                title = "FPS bajo estable con CPU disponible.",
                evidence = DevActionEvidence(
                    metric = "fps",
                    segment = "FILTERED",
                    values = mapOf("p50" to "28", "avgCpu" to "45"),
                ),
                diagnostic = "Revisa el target FPS del motor.",
                codeAreaHints = listOf(
                    CodeAreaHint(
                        engine = GameEngine.UNITY,
                        area = "Application.targetFrameRate",
                        whyHere = "Revisa el frame-rate objetivo.",
                        docLink = "https://docs.unity3d.com/Manual/profiler.html",
                    ),
                ),
                suggestedActions = listOf(
                    ActionStep(
                        description = "Verifica el valor de Application.targetFrameRate.",
                        tool = "Unity Profiler",
                        docLink = "https://docs.unity3d.com/ScriptReference/Application-targetFrameRate.html",
                        engineSpecific = GameEngine.UNITY,
                    ),
                ),
                relatedLogcatLines = emptyList(),
                confidence = DevConfidence.HIGH,
            ),
        ),
        topN = 5,
    )

    @Test
    fun `DAB-007 round-trip preserves devActionBrief losslessly`() {
        val brief = sampleBrief()
        val entry = fullEntry().copy(id = "dab-rt", devActionBrief = brief)
        SessionHistory.addEntry(entry)

        val loaded = SessionHistory.load().firstOrNull { it.id == "dab-rt" }
        assertNotNull(loaded, "entry must round-trip")
        assertEquals(brief, loaded.devActionBrief, "devActionBrief must be byte-equal after round-trip")
        assertEquals(1, loaded.devActionBrief.items.size, "one item preserved")
        assertEquals(5, loaded.devActionBrief.topN, "topN preserved")
    }

    @Test
    fun `DAB-010 legacy v4_5_0-pre-Sprint3 JSON without devActionBrief defaults to empty brief`() {
        // Pre-Sprint 3 payload — has fpower fields but no devActionBrief. Defaulted
        // brief means items.isEmpty() && topN == DEFAULT_TOP_N (backward compat path).
        val legacyJson = """
            [
              {
                "id": "legacy-pre-sprint3",
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
                "isFavorite": false,
                "thermalAvailable": true,
                "fpowerAvailable": true
              }
            ]
        """.trimIndent()
        tempFile.parentFile?.mkdirs()
        tempFile.writeText(legacyJson)

        val loaded = SessionHistory.load()
        assertEquals(1, loaded.size)
        val entry = loaded.first()
        assertTrue(
            entry.devActionBrief.items.isEmpty(),
            "missing devActionBrief field defaults to empty items",
        )
        assertEquals(
            DevActionBrief.DEFAULT_TOP_N,
            entry.devActionBrief.topN,
            "missing devActionBrief field defaults topN to DEFAULT_TOP_N",
        )
    }

    @Test
    fun `DAB-007 round-trip with empty brief preserves empty items`() {
        val entry = fullEntry().copy(id = "dab-empty", devActionBrief = DevActionBrief(items = emptyList()))
        SessionHistory.addEntry(entry)

        val loaded = SessionHistory.load().firstOrNull { it.id == "dab-empty" }
        assertNotNull(loaded)
        assertTrue(loaded.devActionBrief.items.isEmpty(), "empty brief round-trips empty")
        assertEquals(DevActionBrief.DEFAULT_TOP_N, loaded.devActionBrief.topN)
    }

    // ===== v4.5.0 — cpu-total-vs-app-usage cpuTotalHistory round-trip (CDU-004) =====

    @Test
    fun `CDU-004 round-trip preserves cpuTotalHistory populated`() {
        val totals = listOf(55, 65, 75, 85)
        val entry = fullEntry().copy(id = "cpu-total-rt", cpuTotalHistory = totals)
        SessionHistory.addEntry(entry)

        val loaded = SessionHistory.load().firstOrNull { it.id == "cpu-total-rt" }
        assertNotNull(loaded)
        assertEquals(
            totals,
            loaded.cpuTotalHistory,
            "cpuTotalHistory must round-trip element-equal through SerializableEntry",
        )
    }

    @Test
    fun `CDU-004 legacy v4_5_x JSON without cpuTotalHistory defaults to empty list`() {
        // Hand-rolled v4.5.x-pre-cpu-dual payload — has all the v4.5.0 fields up to
        // devActionBrief but lacks cpuTotalHistory. Default empty preserves the
        // pre-cpu-dual semantics: report renders the legacy single CPU line.
        val legacyJson = """
            [
              {
                "id": "legacy-pre-cpu-dual",
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
                "isFavorite": false,
                "thermalAvailable": true,
                "fpowerAvailable": true
              }
            ]
        """.trimIndent()
        tempFile.parentFile?.mkdirs()
        tempFile.writeText(legacyJson)

        val loaded = SessionHistory.load()
        assertEquals(1, loaded.size)
        assertTrue(
            loaded.first().cpuTotalHistory.isEmpty(),
            "missing cpuTotalHistory field defaults to empty list for v4.5.x-pre-cpu-dual compat",
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
