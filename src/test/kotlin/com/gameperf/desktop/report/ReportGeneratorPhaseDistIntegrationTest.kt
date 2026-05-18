package com.gameperf.desktop.report

import com.gameperf.desktop.core.kpi.Band
import com.gameperf.desktop.core.kpi.DeviceTier
import com.gameperf.desktop.core.kpi.KpiId
import com.gameperf.desktop.core.kpi.KpiScore
import com.gameperf.desktop.core.kpi.KpiScoreReport
import com.gameperf.desktop.core.kpi.Phase
import com.gameperf.desktop.core.kpi.PhaseScore
import com.gameperf.desktop.core.model.DeviceInfo
import com.gameperf.desktop.core.model.DevicePlatform
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 4 (sdd/html-report-rag-bands) — wire `renderPhaseDistributionBoxes`
 * into the end-to-end report.
 *
 * Verifies that the generated HTML contains the section header when phases
 * carry non-null FPS_AVG values, AND that it does NOT contain the header
 * when phases are empty (RAG-010 backward compat — pre-v4.7 reports must
 * render unchanged).
 */
class ReportGeneratorPhaseDistIntegrationTest {

    private val device = DeviceInfo(
        model = "TestPhase",
        manufacturer = "Test",
        cpu = "CPU",
        gpu = "GPU",
        ram = "8 GB",
        cores = 8,
        osVersion = "34",
        resolution = "1080x2400",
        platform = DevicePlatform.ANDROID,
    )

    private fun fpsScore(value: Double, phase: Phase): KpiScore =
        KpiScore(
            id = KpiId.FPS_AVG,
            phase = phase,
            rawValue = value,
            score = value.toInt(),
            delta = 0.0,
            band = Band.GREEN,
        )

    private fun buildReport(phases: List<PhaseScore>): KpiScoreReport =
        KpiScoreReport(
            sessionScore = 50,
            sessionBand = Band.AMBER,
            phases = phases,
            categories = emptyList(),
            deviceTier = DeviceTier.MID,
        )

    private fun generateHtml(pkgSuffix: String, kpiReport: KpiScoreReport): String {
        val fps = (1..30).map { 55 }
        val mem = (1..30).map { 400L }
        val nat = (1..30).map { 200L }
        val jav = (1..30).map { 100L }
        val cpu = (1..30).map { 40 }
        val tcpu = (1..30).map { 35.0 }
        val tgpu = (1..30).map { 33.0 }
        val tskin = (1..30).map { 30.0 }
        val ft = (0 until 60).map { 18.0 }
        ReportGenerator.generate(
            pkg = "com.test.phasedist.$pkgSuffix",
            info = device,
            grade = 'B', score = 70, duration = 30,
            fpsHistory = fps, memHistory = mem, nativeHistory = nat, javaHistory = jav,
            cpuHistory = cpu, tempCpuHistory = tcpu, tempGpuHistory = tgpu, tempSkinHistory = tskin,
            allFrameTimes = ft,
            avgFps = 55, minFps = 55, maxFps = 55, p1 = 55, p5 = 55, p50 = 55, p90 = 55, p99 = 55,
            avgFrameTime = ft.average(),
            p99FrameTime = ft.last(),
            peakMem = 600L, avgCpu = 40, maxCpu = 60,
            maxTempCpu = 38.0, maxTempGpu = 36.0,
            batteryStart = 90, batteryEnd = 88,
            frameDrops = 0, jank = 0, stutter = 0,
            problems = emptyList(),
            isWifi = true,
            deviceGrade = 'A', deviceScore = 90, deviceTier = "Mid-range",
            kpiReport = kpiReport,
            kpiInternalEnabled = true,
        )
        val dir = File(System.getProperty("user.home"), "GamePerf Reports")
        val latest = dir.listFiles { f -> f.name.contains("phasedist_$pkgSuffix") && f.name.endsWith(".html") }
            ?.maxByOrNull { it.lastModified() }
            ?: error("expected generated report file")
        return latest.readText(Charsets.UTF_8)
    }

    @Test
    fun `phases with FPS data render the distribution section`() {
        val report = buildReport(
            listOf(
                PhaseScore(
                    phase = Phase.GAMEPLAY,
                    score = 55,
                    band = Band.AMBER,
                    kpiScores = listOf(fpsScore(55.0, Phase.GAMEPLAY)),
                ),
            ),
        )
        val html = generateHtml("with", report)
        assertTrue(
            "Distribucion por fase" in html,
            "expected section header when phases carry FPS data",
        )
        assertTrue("phase-dist-box" in html, "expected per-phase box")
        assertTrue("GAMEPLAY" in html)
    }

    @Test
    fun `empty phases list does not render the distribution section`() {
        val report = buildReport(emptyList())
        val html = generateHtml("empty", report)
        assertFalse(
            "Distribucion por fase" in html,
            "RAG-010: empty phases must not produce the section header",
        )
    }
}
