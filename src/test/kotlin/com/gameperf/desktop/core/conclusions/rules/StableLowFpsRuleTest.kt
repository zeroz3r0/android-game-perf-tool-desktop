package com.gameperf.desktop.core.conclusions.rules

import com.gameperf.desktop.core.conclusions.ConclusionTestFixtures.aggregates
import com.gameperf.desktop.core.conclusions.ConclusionTestFixtures.input
import com.gameperf.desktop.core.conclusions.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [StableLowFpsRule]. Pure, no mocks.
 *
 * Coverage:
 *  - Fires: low p50, low CPU, cool device.
 *  - Does NOT fire when CPU is the bottleneck (different rule).
 *  - Does NOT fire when device is hot (different rule).
 *  - Boundary: p50 exactly at threshold, CPU/temp just below threshold.
 *  - targetFps == 0 short-circuits to false.
 */
class StableLowFpsRuleTest {

    @Test
    fun `fires when p50 is low and device has headroom`() {
        val agg = aggregates(p50 = 20, avgCpu = 30, maxTempCpu = 38.0)
        val input = input(filtered = agg, targetFps = 30)
        assertTrue(StableLowFpsRule.matches(input))
        val conclusion = StableLowFpsRule.render(input)
        assertEquals(Severity.WARNING, conclusion.severity)
        assertEquals("stable-low-fps-low-cpu", conclusion.ruleId)
        assertTrue(conclusion.headline.contains("20"))
        assertTrue(conclusion.headline.contains("30"))
    }

    @Test
    fun `does not fire when CPU is the bottleneck`() {
        val agg = aggregates(p50 = 20, avgCpu = 80, maxTempCpu = 38.0)
        assertFalse(StableLowFpsRule.matches(input(filtered = agg, targetFps = 30)))
    }

    @Test
    fun `does not fire when device is hot`() {
        val agg = aggregates(p50 = 20, avgCpu = 30, maxTempCpu = 50.0)
        assertFalse(StableLowFpsRule.matches(input(filtered = agg, targetFps = 30)))
    }

    @Test
    fun `boundary - p50 exactly at threshold and just-below CPU and temp fires`() {
        // p50 = 21 = floor(0.7 × 30); avgCpu = 49 (< 50); maxTempCpu = 41.9 (< 42)
        val agg = aggregates(p50 = 21, avgCpu = 49, maxTempCpu = 41.9)
        assertTrue(StableLowFpsRule.matches(input(filtered = agg, targetFps = 30)))
    }

    @Test
    fun `does not fire when targetFps is zero`() {
        val agg = aggregates(p50 = 0, avgCpu = 0, maxTempCpu = 0.0)
        assertFalse(StableLowFpsRule.matches(input(filtered = agg, targetFps = 0)))
    }

    /**
     * v4.4.1 (discovery #274): when the parser flagged thermalAvailable=false,
     * the rule MUST NOT fire even if FPS+CPU+temp predicates would otherwise
     * pass. Without this guard the recommendation falsely tells the user
     * "the device has headroom" using a fabricated 0°C reading.
     */
    @Test
    fun `does not fire when thermalAvailable is false even if FPS and CPU predicates pass`() {
        val agg = aggregates(p50 = 20, avgCpu = 30, maxTempCpu = 0.0)
        val input = input(filtered = agg, targetFps = 30, thermalAvailable = false)
        assertFalse(StableLowFpsRule.matches(input))
    }

    /**
     * Triangulation: the same fixture WITH thermalAvailable=true (default) still
     * fires — proves the new guard is the single point of difference.
     */
    @Test
    fun `still fires when thermalAvailable is true and predicates pass`() {
        val agg = aggregates(p50 = 20, avgCpu = 30, maxTempCpu = 38.0)
        val input = input(filtered = agg, targetFps = 30, thermalAvailable = true)
        assertTrue(StableLowFpsRule.matches(input))
    }
}
