package com.gameperf.desktop.report

import com.gameperf.desktop.core.GameTargets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [ReportGenerator.targetsSection] and the RAG helpers
 * [ReportGenerator.rateAgainstMin] / [ReportGenerator.rateAgainstMax].
 *
 * Tests call the helper directly via its `internal` visibility instead
 * of going through `generate(...)` end-to-end — the helper is pure and
 * the input fixture is much smaller.
 *
 * @since v5.1.0
 */
class TargetsSectionTest {

    private val baseMeasured = ReportGenerator.TargetsMeasured(
        avgFps = 60,
        p1Fps = 50,
        avgFrameTime = 16.6,
        maxTempSkin = 38.0,
        maxTempCpu = 80.0,
        peakMemMb = 1200L,
        avgCpu = 50,
        fpowerAvg = 40.0,
        batteryDrain = 5,
    )

    @Test
    fun `targets section returns empty string when targets is null`() {
        val html = ReportGenerator.targetsSection(baseMeasured, null)

        assertEquals("", html, "null targets must produce empty string (backward-compat)")
        assertFalse(html.contains("sec-targets"), "null targets must NOT emit sec-targets anchor")
    }

    @Test
    fun `all KPIs meeting target render with callout-info green class`() {
        val targets = GameTargets(
            targetAvgFps = 30,
            targetP1Fps = 25,
            maxAvgFrameTimeMs = 33.3,
            maxTempSkinC = 42.0,
            maxTempCpuC = 95.0,
            maxPeakRamMb = 1500L,
            maxAvgCpuPct = 60,
            maxFPowerMwFrame = 65.0,
            maxBatteryDrainPct = 15,
        )

        val html = ReportGenerator.targetsSection(baseMeasured, targets)

        assertTrue(html.contains("callout-info"), "all-beating measurements must use callout-info")
        assertFalse(html.contains("callout-warning"), "no card should be amber when all beat target")
        assertFalse(html.contains("callout-bad"), "no card should be red when all beat target")
        assertTrue(html.contains("sec-targets"), "section anchor must be present")
    }

    @Test
    fun `KPI 5 percent over max threshold renders with callout-warning amber`() {
        // RAM target 1000 MB, measured 1050 MB → +5% (within 10% tolerance).
        val measured = baseMeasured.copy(peakMemMb = 1050L)
        val targets = GameTargets(maxPeakRamMb = 1000L)

        val html = ReportGenerator.targetsSection(measured, targets)

        assertTrue(html.contains("callout-warning"), "5% over max must render amber")
        assertFalse(html.contains("callout-bad"), "5% over max must NOT render red")
    }

    @Test
    fun `KPI 20 percent over max threshold renders with callout-bad red`() {
        // RAM target 1000 MB, measured 1200 MB → +20% (beyond 10% tolerance).
        val measured = baseMeasured.copy(peakMemMb = 1200L)
        val targets = GameTargets(maxPeakRamMb = 1000L)

        val html = ReportGenerator.targetsSection(measured, targets)

        assertTrue(html.contains("callout-bad"), "20% over max must render red")
        assertFalse(html.contains("callout-warning"), "20% over max must NOT render amber")
    }

    @Test
    fun `null target KPI is silently skipped no row emitted`() {
        // targetAvgFps is null — the FPS card must NOT appear. Other KPIs
        // (RAM) DO appear since their target is set.
        val targets = GameTargets(
            targetAvgFps = null,
            maxPeakRamMb = 1500L,
        )

        val html = ReportGenerator.targetsSection(baseMeasured, targets)

        assertFalse(html.contains("FPS medio"), "null targetAvgFps must NOT render the FPS card")
        assertTrue(html.contains("RAM pico"), "non-null maxPeakRamMb must render the RAM card")
    }

    @Test
    fun `displayName appears in section title when provided`() {
        val targets = GameTargets(displayName = "Piece Out", targetAvgFps = 30)

        val html = ReportGenerator.targetsSection(baseMeasured, targets)

        assertTrue(html.contains("Piece Out"), "displayName must appear in section title")
    }

    @Test
    fun `section renders no cards when all target fields are null`() {
        // Edge case: GameTargets exists for the package but every KPI field
        // is null. We must NOT render an empty section header.
        val targets = GameTargets(displayName = "Empty")

        val html = ReportGenerator.targetsSection(baseMeasured, targets)

        assertEquals("", html, "empty target set must produce no HTML")
    }
}
