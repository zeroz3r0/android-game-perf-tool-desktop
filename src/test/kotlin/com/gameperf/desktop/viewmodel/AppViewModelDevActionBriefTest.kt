package com.gameperf.desktop.viewmodel

import com.gameperf.desktop.core.SessionHistory
import com.gameperf.desktop.core.conclusions.Severity
import com.gameperf.desktop.core.devactions.ActionStep
import com.gameperf.desktop.core.devactions.CodeAreaHint
import com.gameperf.desktop.core.devactions.Confidence as DevConfidence
import com.gameperf.desktop.core.devactions.DevActionBrief
import com.gameperf.desktop.core.devactions.DevActionEvidence
import com.gameperf.desktop.core.devactions.DevActionItem
import com.gameperf.desktop.core.devactions.GameEngine
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * v4.5.0 Sprint 3 — boundary tests for the DevActionBrief wiring in
 * [AppViewModel]. Mirrors [AppViewModelFPowerTest]: we do NOT spin up
 * the real capture loop (1400+ LOC of ADB plumbing). Instead we exercise
 * the persistence boundary — what [SessionResult] / [SessionHistory.HistoryEntry]
 * hold AFTER the loop has run, and assert the new `devActionBrief` field
 * round-trips end-to-end via [SessionHistory.addEntry] → [SessionHistory.load].
 *
 * Coverage targets:
 *  - DAB-007 SessionResult + SerializableEntry + HistoryEntry shape with
 *    `devActionBrief` field, defaulted backward-compat behavior.
 *  - DAB-010 legacy pre-Sprint-3 entry loads with defaulted empty brief.
 *
 * Why we don't drive the live loop: the v4.4.1 + v4.5.0 precedents
 * (AppViewModelAggregationTest, AppViewModelFPowerTest) made this style
 * canonical for boundary-state assertions.
 */
class AppViewModelDevActionBriefTest {

    private lateinit var tempFile: File

    @BeforeTest
    fun setUp() {
        val dir = Files.createTempDirectory("devaction-brief-pending-").toFile()
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

    private fun sampleBrief(): DevActionBrief = DevActionBrief(
        items = listOf(
            DevActionItem(
                ruleId = "cpu-saturated",
                severity = Severity.CRITICAL,
                title = "CPU al límite durante todo el gameplay.",
                evidence = DevActionEvidence(
                    metric = "cpu",
                    segment = "FILTERED",
                    values = mapOf("avgCpu" to "95", "p99Cpu" to "100"),
                ),
                diagnostic = "Reduce el trabajo en el hilo principal.",
                codeAreaHints = listOf(
                    CodeAreaHint(
                        engine = GameEngine.UNREAL,
                        area = "Tick functions, AsyncTask",
                        whyHere = "Mira los Tick functions del hilo principal.",
                        docLink = "https://docs.unrealengine.com/5.0/en-US/profiling-tools-in-unreal-engine/",
                    ),
                ),
                suggestedActions = listOf(
                    ActionStep(
                        description = "Mueve los cálculos pesados a worker threads.",
                        tool = "Unreal Insights",
                        docLink = "https://docs.unrealengine.com/5.0/en-US/unreal-insights/",
                        engineSpecific = GameEngine.UNREAL,
                    ),
                ),
                relatedLogcatLines = emptyList(),
                confidence = DevConfidence.HIGH,
            ),
        ),
        topN = 5,
    )

    // ── SessionResult shape ─────────────────────────────────────────────

    @Test
    fun `SessionResult devActionBrief defaults to null`() {
        val r = SessionResult()
        assertEquals(null, r.devActionBrief, "default devActionBrief is null (backward compat)")
    }

    @Test
    fun `SessionResult carries devActionBrief when populated`() {
        val brief = sampleBrief()
        val r = SessionResult(devActionBrief = brief)
        assertEquals(brief, r.devActionBrief)
        val resultBrief = r.devActionBrief
        assertNotNull(resultBrief)
        assertEquals(1, resultBrief.items.size)
        assertEquals("cpu-saturated", resultBrief.items.first().ruleId)
    }

    // ── HistoryEntry round-trip ─────────────────────────────────────────

    private fun baseEntry(id: String = "dab-1"): SessionHistory.HistoryEntry =
        SessionHistory.HistoryEntry(
            id = id,
            name = "dev-action-brief session",
            gamePackage = "com.vivastudios.pieceout",
            deviceModel = "Samsung SM-X200",
            grade = 'A',
            deviceGrade = 'A',
            avgFps = 60,
            duration = 60,
            date = "12/05/2026 10:00",
            reportPath = "",
            videoPath = "",
        )

    @Test
    fun `pendingEntry default devActionBrief is empty brief shape`() {
        // Builder uses ZERO devActionBrief named-arg. Default must be the
        // backward-compat shape (items empty, topN=5).
        val entry = baseEntry("dab-defaults")
        SessionHistory.addEntry(entry)
        val loaded = SessionHistory.load().firstOrNull { it.id == "dab-defaults" }
        assertNotNull(loaded)
        assertTrue(loaded.devActionBrief.items.isEmpty(), "default brief items empty")
        assertEquals(DevActionBrief.DEFAULT_TOP_N, loaded.devActionBrief.topN, "default topN=5")
    }

    @Test
    fun `pendingEntry round-trips devActionBrief losslessly`() {
        val brief = sampleBrief()
        val entry = baseEntry("dab-rt").copy(devActionBrief = brief)
        SessionHistory.addEntry(entry)
        val loaded = SessionHistory.load().firstOrNull { it.id == "dab-rt" }
        assertNotNull(loaded)
        assertEquals(brief, loaded.devActionBrief, "devActionBrief round-trips byte-equal")
    }
}
