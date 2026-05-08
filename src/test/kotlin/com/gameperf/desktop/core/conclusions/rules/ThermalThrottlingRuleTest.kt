package com.gameperf.desktop.core.conclusions.rules

import com.gameperf.desktop.core.conclusions.ConclusionTestFixtures.aggregates
import com.gameperf.desktop.core.conclusions.ConclusionTestFixtures.input
import com.gameperf.desktop.core.conclusions.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [ThermalThrottlingRule]. Pure, no mocks.
 *
 * Coverage:
 *  - Fires when device is hot AND p5 collapses.
 *  - Does NOT fire when device is cool even if FPS drops.
 *  - Does NOT fire when device is hot but FPS is stable.
 *  - Skin-only fixture (>=42°C skin, lower CPU temp) still fires.
 */
class ThermalThrottlingRuleTest {

    @Test
    fun `fires when CPU temp is high and FPS drops significantly`() {
        val agg = aggregates(avgFps = 50, p5 = 25, maxTempCpu = 47.0, maxTempSkin = 40.0)
        val input = input(filtered = agg)
        assertTrue(ThermalThrottlingRule.matches(input))
        val conclusion = ThermalThrottlingRule.render(input)
        assertEquals(Severity.CRITICAL, conclusion.severity)
        assertEquals("thermal-throttling", conclusion.ruleId)
        assertTrue(conclusion.headline.contains("47"))
    }

    @Test
    fun `fires on skin temperature alone`() {
        // CPU below 45 but skin reaches 42°C — still throttling.
        val agg = aggregates(avgFps = 50, p5 = 25, maxTempCpu = 40.0, maxTempSkin = 42.5)
        assertTrue(ThermalThrottlingRule.matches(input(filtered = agg)))
    }

    @Test
    fun `does not fire when device is cool`() {
        val agg = aggregates(avgFps = 50, p5 = 25, maxTempCpu = 38.0, maxTempSkin = 35.0)
        assertFalse(ThermalThrottlingRule.matches(input(filtered = agg)))
    }

    @Test
    fun `does not fire when FPS is stable even if hot`() {
        // p5 = 48, avg = 50 — well above the 0.6 × 50 = 30 floor.
        val agg = aggregates(avgFps = 50, p5 = 48, maxTempCpu = 47.0)
        assertFalse(ThermalThrottlingRule.matches(input(filtered = agg)))
    }
}
