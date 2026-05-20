package com.gameperf.desktop.report

import com.gameperf.desktop.core.SessionHistory
import com.gameperf.desktop.core.model.DeviceInfo
import com.gameperf.desktop.core.model.DevicePlatform
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Verifies the browser-print (Ctrl+P) hardening of both report flavors:
 *  - Single-session report (`ReportGenerator.generate`)
 *  - Comparativa report (`ReportGenerator.generateComparison`)
 *
 * Two failure modes are covered:
 *  1. Missing `@page` directive in the comparativa `@media print` block — the original
 *     one-liner skipped page sizing, header gradient flattening, and summary-row tints,
 *     producing illegible PDFs from Ctrl+P. Fix expands the block; this asserts on the
 *     resulting markers.
 *  2. Chart.js charts baked with dark-mode colors at construction time. CSS cannot reach
 *     inside `<canvas>`, so a `beforeprint` listener swaps `Chart.defaults` and calls
 *     `update('none')` on every instance. This test asserts the listener wiring exists in
 *     both report flavors.
 */
class ReportGeneratorPrintCssTest {

    private val device = DeviceInfo(
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

    private fun generateSingleSessionHtml(): String {
        val fps = (1..30).map { 55 }
        val mem = (1..30).map { 400L }
        val nat = (1..30).map { 200L }
        val jav = (1..30).map { 100L }
        val cpu = (1..30).map { 40 }
        val tcpu = (1..30).map { 35.0 }
        val tgpu = (1..30).map { 33.0 }
        val tskin = (1..30).map { 30.0 }
        val ft = (0 until 60).map { 18.0 + (it % 5) * 0.5 }
        ReportGenerator.generate(
            pkg = "com.test.printcss",
            info = device,
            grade = 'B', score = 70, duration = 30,
            fpsHistory = fps, memHistory = mem, nativeHistory = nat, javaHistory = jav,
            cpuHistory = cpu, tempCpuHistory = tcpu, tempGpuHistory = tgpu, tempSkinHistory = tskin,
            allFrameTimes = ft,
            avgFps = 55, minFps = 55, maxFps = 55, p1 = 55, p5 = 55, p50 = 55, p90 = 55, p99 = 55,
            avgFrameTime = ft.average(),
            p99FrameTime = ft.sorted().let { it[(it.size * 99 / 100).coerceIn(0, it.size - 1)] },
            peakMem = 600L, avgCpu = 40, maxCpu = 60,
            maxTempCpu = 38.0, maxTempGpu = 36.0,
            batteryStart = 90, batteryEnd = 88,
            frameDrops = 0, jank = 0, stutter = 0,
            problems = emptyList(),
            isWifi = true,
            deviceGrade = 'A', deviceScore = 90, deviceTier = "Mid-range",
        )
        val dir = File(System.getProperty("user.home"), "GamePerf Reports")
        val latest = dir.listFiles { f -> f.name.startsWith("informe_") && f.name.endsWith(".html") }
            ?.maxByOrNull { it.lastModified() }
            ?: error("expected generated single-session report file")
        return latest.readText(Charsets.UTF_8)
    }

    private fun makeEntry(
        id: String,
        tag: SessionHistory.SessionTag,
        competitor: String = "",
    ): SessionHistory.HistoryEntry = SessionHistory.HistoryEntry(
        id = id,
        name = id,
        gamePackage = "com.test.$id",
        deviceModel = "TestDevice",
        grade = 'B',
        deviceGrade = 'A',
        avgFps = 55,
        duration = 60,
        date = "2026-01-01",
        reportPath = "",
        videoPath = "",
        tag = tag,
        competitorName = competitor,
        p1Fps = 40, p5Fps = 45,
        avgFrameTime = 18.0, p95FrameTime = 22.0, p99FrameTime = 26.0,
        peakMemMb = 600L, avgCpu = 40, maxTemp = 38.0,
        score = 70,
    )

    private fun generateComparisonHtml(): String {
        val entries = listOf(
            makeEntry("ours", SessionHistory.SessionTag.OUR_GAME),
            makeEntry("rival", SessionHistory.SessionTag.COMPETITION, competitor = "Rival"),
        )
        val tmp = File(System.getProperty("java.io.tmpdir"), "gpt-print-css-test").apply { mkdirs() }
        val path = ReportGenerator.generateComparison(entries, tmp)
        return File(path).readText(Charsets.UTF_8)
    }

    @Test
    fun `comparativa report includes expanded print block with page directive`() {
        val html = generateComparisonHtml()
        assertTrue("@page" in html, "expected @page directive in comparativa @media print block")
        assertTrue(
            ".summary-row.win" in html,
            "expected summary-row.win selector targeted by print overrides",
        )
        assertTrue(
            "page-break-inside:avoid" in html,
            "expected card page-break-inside override in comparativa print block",
        )
    }

    @Test
    fun `comparativa report wires beforeprint listener for Chart_js repaint`() {
        val html = generateComparisonHtml()
        assertTrue("beforeprint" in html, "expected beforeprint listener in comparativa report JS")
        assertTrue("afterprint" in html, "expected afterprint listener in comparativa report JS")
        assertTrue(
            "__applyPrintPalette" in html,
            "expected __applyPrintPalette helper in comparativa report JS",
        )
    }

    @Test
    fun `single-session report wires beforeprint listener for Chart_js repaint`() {
        val html = generateSingleSessionHtml()
        assertTrue("beforeprint" in html, "expected beforeprint listener in single-session report JS")
        assertTrue("afterprint" in html, "expected afterprint listener in single-session report JS")
        assertTrue(
            "__applyPrintPalette" in html,
            "expected __applyPrintPalette helper in single-session report JS",
        )
    }

    // ── Per-instance repaint coverage ─────────────────────────────────────────
    // The previous tests only asserted listener presence — a half-done fix that
    // only mutated `Chart.defaults` would still pass them, but would NOT repaint
    // charts that pass explicit per-instance colors (legend labels, axis ticks,
    // radar pointLabels…). These tests pin the actual option paths the listener
    // must walk. Substring asserts on purpose: cheaper and stricter than Nashorn.

    @Test
    fun `print palette walks Chart_instances, not just defaults`() {
        val html = generateComparisonHtml()
        assertTrue(
            "Chart.instances" in html,
            "expected Chart.instances iteration so per-instance options get repainted",
        )
    }

    @Test
    fun `print palette mutates legend labels color path used by every chart`() {
        val html = generateSingleSessionHtml()
        assertTrue(
            "plugins.legend.labels.color" in html,
            "expected listener to mutate plugins.legend.labels.color (used by single-session B)",
        )
    }

    @Test
    fun `print palette mutates radar pointLabels color path used by comparativa`() {
        val html = generateComparisonHtml()
        assertTrue(
            "scales.r.pointLabels.color" in html,
            "expected listener to mutate scales.r.pointLabels.color (used by comparativa radar)",
        )
        assertTrue(
            "scales.r.angleLines.color" in html,
            "expected listener to mutate scales.r.angleLines.color (used by comparativa radar)",
        )
        assertTrue(
            "scales.r.ticks.color" in html,
            "expected listener to mutate scales.r.ticks.color (used by comparativa radar)",
        )
        assertTrue(
            "scales.r.grid.color" in html,
            "expected listener to mutate scales.r.grid.color (used by comparativa radar)",
        )
    }

    @Test
    fun `print palette mutates cartesian axis tick and grid color paths`() {
        val html = generateSingleSessionHtml()
        assertTrue(
            "scales.x.ticks.color" in html,
            "expected listener to mutate scales.x.ticks.color (used by single-session B)",
        )
        assertTrue(
            "scales.y.ticks.color" in html,
            "expected listener to mutate scales.y.ticks.color (used by single-session B)",
        )
        assertTrue(
            "scales.x.grid.color" in html,
            "expected listener to mutate scales.x.grid.color (used by single-session B)",
        )
        assertTrue(
            "scales.y.grid.color" in html,
            "expected listener to mutate scales.y.grid.color (used by single-session B)",
        )
    }

    @Test
    fun `print palette uses high-contrast foreground color for printing`() {
        val html = generateSingleSessionHtml()
        // #1e293b is the project's print foreground (single-session IS_PRINT branch
        // line ~957 + COLORS_PRINT). The palette must embed this exact value.
        assertTrue(
            "#1e293b" in html,
            "expected #1e293b high-contrast print foreground in PRINT_PALETTE_JS",
        )
    }

    @Test
    fun `print palette wires both beforeprint and afterprint listeners`() {
        val html = generateComparisonHtml()
        val beforeIdx = html.indexOf("beforeprint")
        val afterIdx  = html.indexOf("afterprint")
        assertTrue(beforeIdx >= 0, "expected beforeprint listener")
        assertTrue(afterIdx  >= 0, "expected afterprint listener")
        assertTrue(
            beforeIdx != afterIdx,
            "beforeprint and afterprint must be distinct listeners, not the same string",
        )
    }
}
