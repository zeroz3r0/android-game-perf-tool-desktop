package com.gameperf.desktop.report

import com.gameperf.desktop.core.kpi.Band
import com.gameperf.desktop.core.kpi.Category
import com.gameperf.desktop.core.kpi.CategoryScore
import com.gameperf.desktop.core.kpi.KpiId
import com.gameperf.desktop.core.kpi.KpiScore
import com.gameperf.desktop.core.kpi.KpiScoreReport
import com.gameperf.desktop.core.kpi.Phase
import com.gameperf.desktop.core.kpi.PhaseScore
import com.gameperf.desktop.core.model.DeviceInfo
import com.gameperf.desktop.core.model.DevicePlatform
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * T6.1 — Size budget for the full shareable HTML report with KPI sections
 * enabled. A 60-second synthetic session (60 fps history points, 3600 frame
 * times) + populated `KpiScoreReport` MUST produce HTML under 5 MB.
 *
 * This guards against:
 *  - base64-encoded CSV/JSON payload bloat
 *  - inadvertent doubling of embedded assets
 *  - per-phase / per-KPI table explosions
 *
 * Spec coverage: `sdd/shareable-html-report/spec` — non-functional size
 * constraint (§7.6 + design §risks).
 *
 * @since v4.6 (shareable-html-report Block F)
 */
class KpiReportSizeTest {

    private val device = DeviceInfo(
        model = "TestDevice",
        manufacturer = "TestMaker",
        cpu = "TestCPU",
        gpu = "TestGPU",
        ram = "8.0 GB",
        cores = 8,
        osVersion = "33",
        resolution = "1080x2400",
        platform = DevicePlatform.ANDROID,
    )

    private fun bigSyntheticReport(): KpiScoreReport {
        val allPhases = Phase.values()
        val allKpis = KpiId.values()
        val phases = allPhases.map { phase ->
            val scores = allKpis.map { id ->
                KpiScore(
                    id = id,
                    phase = phase,
                    rawValue = 60.0,
                    score = 80,
                    delta = 0.0,
                    band = Band.GREEN,
                )
            }
            PhaseScore(phase = phase, score = 80, band = Band.GREEN, kpiScores = scores)
        }
        val categories = Category.values().map {
            CategoryScore(category = it, score = 85, band = Band.GREEN)
        }
        return KpiScoreReport(
            sessionScore = 85,
            sessionBand = Band.GREEN,
            phases = phases,
            categories = categories,
        )
    }

    @Test
    fun `60s session with KPI flag on stays under 5MB`() {
        val fps60 = List(60) { 60 }
        val frames3600 = List(3600) { (15.0 + (it % 10) * 0.5) }
        val path = ReportGenerator.generate(
            pkg = "com.example.size.test",
            info = device,
            grade = 'A',
            score = 90,
            duration = 60,
            fpsHistory = fps60,
            memHistory = List(60) { 400L + it },
            nativeHistory = List(60) { 200L + it / 2 },
            javaHistory = List(60) { 100L + it / 3 },
            cpuHistory = List(60) { 40 + (it % 20) },
            tempCpuHistory = List(60) { 40.0 + (it % 10) * 0.5 },
            tempGpuHistory = List(60) { 35.0 + (it % 10) * 0.5 },
            tempSkinHistory = List(60) { 30.0 + (it % 10) * 0.3 },
            allFrameTimes = frames3600,
            avgFps = 60, minFps = 55, maxFps = 65,
            p1 = 55, p5 = 56, p50 = 60, p90 = 62, p99 = 64,
            avgFrameTime = 16.7, p99FrameTime = 19.0,
            peakMem = 460L, avgCpu = 50, maxCpu = 65,
            maxTempCpu = 50.0, maxTempGpu = 45.0,
            batteryStart = 100, batteryEnd = 95,
            frameDrops = 5, jank = 8, stutter = 0,
            problems = emptyList(), isWifi = true,
            kpiReport = bigSyntheticReport(),
            kpiInternalEnabled = true,
            kpiTier = "MID",
        )
        val bytes = File(path).readBytes().size
        assertTrue(
            bytes <= 5_000_000,
            "report HTML size $bytes bytes exceeds 5 MB budget (path: $path)",
        )
    }
}
