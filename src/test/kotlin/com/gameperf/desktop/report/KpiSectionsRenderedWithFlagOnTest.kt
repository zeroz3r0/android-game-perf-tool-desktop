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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T5.2 — When `kpiInternalEnabled = true` AND a non-null [KpiScoreReport]
 * is passed, [ReportGenerator.generate] wires the 6 new KPI sections + CSS
 * bundle into the existing template.
 *
 * Sections wired (design §wiring map):
 *  - `sec-kpi-scoring` (KpiScoreSection)
 *  - `sec-vitals-banner` (AndroidVitalsBanners — only when breaches)
 *  - `sec-phase-breakdown` (PhaseBreakdown)
 *  - `sec-caveats` (KpiCaveats)
 *  - Notebookcheck `Ø<avg> (<min>-<max>)` substring in `#sec-fps`
 *  - CSV + JSON `data:` download anchors
 *  - p1 + p0.1 pills in `#sec-frametime` when frame-time samples ≥ 1000
 *  - CSS bundle (`.kpi-band-green`) appended to `<style>`
 *
 * @since v4.6 (shareable-html-report Block F)
 */
class KpiSectionsRenderedWithFlagOnTest {

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

    private fun syntheticReport(): KpiScoreReport = KpiScoreReport(
        sessionScore = 78,
        sessionBand = Band.AMBER,
        phases = listOf(
            PhaseScore(
                phase = Phase.APP_STARTUP,
                score = 60,
                band = Band.AMBER,
                kpiScores = listOf(
                    KpiScore(
                        id = KpiId.COLD_START_MS,
                        phase = Phase.APP_STARTUP,
                        rawValue = 6000.0,  // above 5000 → triggers vitals breach
                        score = 40,
                        delta = 1000.0,
                        band = Band.RED,
                    ),
                ),
            ),
            PhaseScore(
                phase = Phase.GAMEPLAY,
                score = 85,
                band = Band.GREEN,
                kpiScores = listOf(
                    KpiScore(
                        id = KpiId.FPS_AVG,
                        phase = Phase.GAMEPLAY,
                        rawValue = 58.0,
                        score = 90,
                        delta = -2.0,
                        band = Band.GREEN,
                    ),
                ),
            ),
        ),
        categories = listOf(
            CategoryScore(category = Category.Smoothness, score = 90, band = Band.GREEN),
        ),
    )

    private fun generate(
        kpiReport: KpiScoreReport?,
        kpiInternalEnabled: Boolean,
        kpiTier: String? = "MID",
        allFrameTimes: List<Double> = listOf(16.0, 17.0, 18.0),
    ): String {
        val path = ReportGenerator.generate(
            pkg = "com.example.game",
            info = device,
            grade = 'B',
            score = 78,
            duration = 60,
            fpsHistory = listOf(60, 58, 59),
            memHistory = listOf(400L, 410L, 420L),
            nativeHistory = listOf(200L, 205L, 210L),
            javaHistory = listOf(100L, 102L, 104L),
            cpuHistory = listOf(40, 45, 50),
            tempCpuHistory = listOf(40.0, 42.0, 45.0),
            tempGpuHistory = listOf(35.0, 37.0, 39.0),
            tempSkinHistory = emptyList(),
            allFrameTimes = allFrameTimes,
            avgFps = 60, minFps = 58, maxFps = 62,
            p1 = 55, p5 = 56, p50 = 60, p90 = 61, p99 = 62,
            avgFrameTime = 16.7, p99FrameTime = 18.0,
            peakMem = 420L, avgCpu = 45, maxCpu = 50,
            maxTempCpu = 45.0, maxTempGpu = 39.0,
            batteryStart = 90, batteryEnd = 85,
            frameDrops = 2, jank = 1, stutter = 0,
            problems = emptyList(), isWifi = true,
            kpiReport = kpiReport,
            kpiInternalEnabled = kpiInternalEnabled,
            kpiTier = kpiTier,
        )
        return File(path).readText(Charsets.UTF_8)
    }

    @Test
    fun `flag on with report wires all 6 sections`() {
        val html = generate(kpiReport = syntheticReport(), kpiInternalEnabled = true)
        assertTrue("""id="sec-kpi-scoring"""" in html, "sec-kpi-scoring missing")
        assertTrue("""id="sec-phase-breakdown"""" in html, "sec-phase-breakdown missing")
        assertTrue("""id="sec-caveats"""" in html, "sec-caveats missing")
        // Notebookcheck Ø prefix should appear in the FPS section.
        assertTrue("\u00D8" in html, "Notebookcheck Ø prefix missing")
        // Download buttons base64 prefixes
        assertTrue("data:text/csv;base64," in html, "CSV download URL missing")
        assertTrue("data:application/json;base64," in html, "JSON download URL missing")
        // CSS bundle injected
        assertTrue(".kpi-band-green" in html, "KPI CSS not injected when flag on")
    }

    @Test
    fun `vitals banner appears when cold-start breaches threshold`() {
        // syntheticReport has COLD_START_MS = 6000 ms, above the 5000 ms catalog floor.
        val html = generate(kpiReport = syntheticReport(), kpiInternalEnabled = true)
        assertTrue("""id="sec-vitals-banner"""" in html, "sec-vitals-banner must appear on breach")
        assertTrue("kpi-vitals-warn" in html, "kpi-vitals-warn class must appear on breach")
    }

    @Test
    fun `flag off with non-null report still emits no new sections`() {
        val html = generate(kpiReport = syntheticReport(), kpiInternalEnabled = false)
        assertFalse("""id="sec-kpi-scoring"""" in html, "must not emit sections when flag off")
        assertFalse("data:text/csv;base64," in html, "must not emit CSV URL when flag off")
        assertFalse(".kpi-band-green" in html, "must not inject CSS when flag off")
    }

    @Test
    fun `flag on with null report emits no new sections`() {
        val html = generate(kpiReport = null, kpiInternalEnabled = true)
        assertFalse("""id="sec-kpi-scoring"""" in html, "must not emit sections when report is null")
        assertFalse("data:text/csv;base64," in html, "must not emit CSV URL when report is null")
        assertFalse(".kpi-band-green" in html, "must not inject CSS when report is null")
    }

    @Test
    fun `p1 and p01 pills appear when allFrameTimes has at least 1000 samples`() {
        // Build 1000 frame times in ascending order so p1 and p0.1 are non-null and distinct.
        val frames = (1..1000).map { it.toDouble() }
        val html = generate(
            kpiReport = syntheticReport(),
            kpiInternalEnabled = true,
            allFrameTimes = frames,
        )
        // Pill labels: "p1 high" and "p0.1 high" — exact label is design-driven; assert on
        // markup that includes both percentiles by their values. p1 → 989th element of sorted
        // 1..1000 (index = 1000 - 1000/100 - 1 = 989) = 990.0; p0.1 → index 1000 - 1 - 1 = 998 = 999.0.
        // We match the literal "p1" and "p0.1" labels emitted by the renderer.
        assertTrue("kpi-frametime-p1" in html || "p1 high" in html, "p1 pill missing; html sample: " + html.substring(0, 200))
        assertTrue("kpi-frametime-p01" in html || "p0.1 high" in html, "p0.1 pill missing")
    }

    @Test
    fun `p1 and p01 pills absent when allFrameTimes is small`() {
        val html = generate(
            kpiReport = syntheticReport(),
            kpiInternalEnabled = true,
            allFrameTimes = listOf(16.0, 17.0, 18.0),  // only 3 samples
        )
        assertFalse("kpi-frametime-p1" in html, "p1 pill must not appear for tiny samples")
        assertFalse("kpi-frametime-p01" in html, "p0.1 pill must not appear for tiny samples")
    }
}
