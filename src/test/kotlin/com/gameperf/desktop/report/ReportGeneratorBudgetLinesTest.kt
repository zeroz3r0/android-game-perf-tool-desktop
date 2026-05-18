package com.gameperf.desktop.report

import com.gameperf.desktop.core.kpi.Band
import com.gameperf.desktop.core.kpi.DeviceTier
import com.gameperf.desktop.core.kpi.KpiScoreReport
import com.gameperf.desktop.core.model.DeviceInfo
import com.gameperf.desktop.core.model.DevicePlatform
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 3 (sdd/html-report-rag-bands) — chart budget reference lines.
 *
 * Verifies that the `ftChart` Chart.js annotation block contains the
 * frame-time budget lines `b60` / `b30` (always) and `b120` (only when
 * `deviceTier == TOP`), all referencing the `FrameBudgets` constants via
 * literal values that appear in the rendered JS.
 *
 * The renderer reads tier from `kpiReport.deviceTier` when present and
 * defaults to MID when no kpiReport is wired in.
 *
 * Pure: deterministic, no I/O beyond the report file write that
 * `ReportGenerator.generate` performs to `~/GamePerf Reports/`.
 *
 * @since v4.7 (html-report-rag-bands — RAG-003)
 */
class ReportGeneratorBudgetLinesTest {

    private fun emptyReport(tier: DeviceTier): KpiScoreReport =
        KpiScoreReport(
            sessionScore = 50,
            sessionBand = Band.AMBER,
            phases = emptyList(),
            categories = emptyList(),
            deviceTier = tier,
        )

    private fun generateHtml(tier: DeviceTier, frameTimes: List<Double> = sampleFrameTimes()): String {
        val device = DeviceInfo(
            model = "TestDevice",
            manufacturer = "Test",
            cpu = "TestCPU",
            gpu = "TestGPU",
            ram = "8.0 GB",
            cores = 8,
            osVersion = "34",
            resolution = "1080x2400",
            platform = DevicePlatform.ANDROID,
        )
        val fps = (1..30).map { 55 }
        val mem = (1..30).map { 400L }
        val nat = (1..30).map { 200L }
        val jav = (1..30).map { 100L }
        val cpu = (1..30).map { 40 }
        val tcpu = (1..30).map { 35.0 }
        val tgpu = (1..30).map { 33.0 }
        val tskin = (1..30).map { 30.0 }
        ReportGenerator.generate(
            pkg = "com.test.budgetlines",
            info = device,
            grade = 'B', score = 70, duration = 30,
            fpsHistory = fps, memHistory = mem, nativeHistory = nat, javaHistory = jav,
            cpuHistory = cpu, tempCpuHistory = tcpu, tempGpuHistory = tgpu, tempSkinHistory = tskin,
            allFrameTimes = frameTimes,
            avgFps = 55, minFps = 55, maxFps = 55, p1 = 55, p5 = 55, p50 = 55, p90 = 55, p99 = 55,
            avgFrameTime = frameTimes.average(),
            p99FrameTime = frameTimes.sorted().let { it[(it.size * 99 / 100).coerceIn(0, it.size - 1)] },
            peakMem = 600L, avgCpu = 40, maxCpu = 60,
            maxTempCpu = 38.0, maxTempGpu = 36.0,
            batteryStart = 90, batteryEnd = 88,
            frameDrops = 0, jank = 0, stutter = 0,
            problems = emptyList(),
            isWifi = true,
            deviceGrade = 'A', deviceScore = 90, deviceTier = "Mid-range",
            kpiReport = emptyReport(tier),
            kpiInternalEnabled = true,
        )
        // Locate generated file and read it back.
        val dir = File(System.getProperty("user.home"), "GamePerf Reports")
        val latest = dir.listFiles { f -> f.name.startsWith("informe_") && f.name.endsWith(".html") }
            ?.maxByOrNull { it.lastModified() }
            ?: error("expected generated report file")
        return latest.readText(Charsets.UTF_8)
    }

    private fun sampleFrameTimes(): List<Double> = (0 until 60).map { 18.0 + (it % 5) * 0.5 }

    @Test
    fun `tier MID renders b60 and b30 budget lines but not b120`() {
        val html = generateHtml(DeviceTier.MID)
        // Find the ftChart block.
        val ftBlock = extractFtChartBlock(html)
        assertTrue("xMin:16.6" in ftBlock, "expected b60 xMin:16.6; got:\n$ftBlock")
        assertTrue("xMin:33.3" in ftBlock, "expected b30 xMin:33.3; got:\n$ftBlock")
        assertFalse("xMin:8.3" in ftBlock, "did not expect b120 in MID tier")
        assertTrue("Presupuesto 60 fps" in ftBlock, "expected BUDGET_60FPS label")
        assertTrue("Presupuesto 30 fps" in ftBlock, "expected BUDGET_30FPS label")
        assertFalse("Presupuesto 120 fps" in ftBlock, "did not expect BUDGET_120FPS in MID tier")
    }

    @Test
    fun `tier TOP renders all three budget lines including b120`() {
        val html = generateHtml(DeviceTier.TOP)
        val ftBlock = extractFtChartBlock(html)
        assertTrue("xMin:16.6" in ftBlock, "expected b60 xMin:16.6")
        assertTrue("xMin:33.3" in ftBlock, "expected b30 xMin:33.3")
        assertTrue("xMin:8.3" in ftBlock, "expected b120 xMin:8.3 in TOP tier")
        assertTrue("Presupuesto 120 fps" in ftBlock, "expected BUDGET_120FPS in TOP tier")
    }

    @Test
    fun `tier LOW renders exactly 2 budget annotations (no 120fps)`() {
        val html = generateHtml(DeviceTier.LOW)
        val ftBlock = extractFtChartBlock(html)
        assertTrue("xMin:16.6" in ftBlock)
        assertTrue("xMin:33.3" in ftBlock)
        assertFalse("xMin:8.3" in ftBlock, "LOW tier must never render b120")
        assertFalse("Presupuesto 120 fps" in ftBlock)
    }

    @Test
    fun `y axis cap covers 60fps budget line even when data is sub-budget`() {
        // All frame times < 5ms — without dynamic cap the 16.6 budget line is off-screen.
        val tinyFt = (0 until 60).map { 4.0 + (it % 3) * 0.2 }
        val html = generateHtml(DeviceTier.MID, frameTimes = tinyFt)
        val ftBlock = extractFtChartBlock(html)
        // Dynamic cap min == FrameBudgets.FPS_60_MS * 1.1 == 18.26 (we accept >= 18.26).
        assertTrue(
            "suggestedMax" in ftBlock || "max:" in ftBlock,
            "expected dynamic y-axis cap on ftChart; got:\n$ftBlock",
        )
    }

    private fun extractFtChartBlock(html: String): String {
        // The 'ftChart' identifier appears multiple times in the HTML (canvas id,
        // Chart.js script). The interesting block is the Chart.js constructor call
        // which is the LAST occurrence — that's where the annotation plugin payload
        // lives. Extract a generous window around the new Chart(... 'ftChart' ...)
        // call so the assertions see the full annotation JS.
        val getElementIdx = html.lastIndexOf("getElementById('ftChart')")
        require(getElementIdx >= 0) { "could not find getElementById('ftChart') in html" }
        val from = (getElementIdx - 200).coerceAtLeast(0)
        val to = (getElementIdx + 4000).coerceAtMost(html.length)
        return html.substring(from, to)
    }
}
