package com.gameperf.desktop.core.conclusions.rules

import com.gameperf.desktop.core.HardwareScoring
import com.gameperf.desktop.core.conclusions.ConclusionTestFixtures.aggregates
import com.gameperf.desktop.core.conclusions.ConclusionTestFixtures.input
import com.gameperf.desktop.core.conclusions.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [Capped30FpsRule]. Pure, no mocks.
 *
 * Coverage (CON-003):
 *  - Fires on HIGH-tier device with p99 ≈ 30.
 *  - Fires on ULTRA_HIGH-tier device with p99 ≈ 30.
 *  - Does NOT fire on LOW-tier device with p99 ≈ 30 (cap is normal there).
 *  - Does NOT fire on MID-tier device (CON-003 explicitly limits to HIGH+).
 *  - Does NOT fire on HIGH-tier device with p99 = 60 (no cap).
 */
class Capped30FpsRuleTest {

    @Test
    fun `fires on HIGH tier with p99 near 30`() {
        val agg = aggregates(p99 = 30)
        val input = input(filtered = agg, deviceTier = HardwareScoring.DeviceTier.HIGH)
        assertTrue(Capped30FpsRule.matches(input))
        val conclusion = Capped30FpsRule.render(input)
        assertEquals(Severity.INFO, conclusion.severity)
        assertEquals("fps-cap-suspect", conclusion.ruleId)
        assertTrue(conclusion.headline.contains("30"))
    }

    @Test
    fun `fires on ULTRA_HIGH tier with p99 near 30`() {
        val agg = aggregates(p99 = 31)
        val input = input(filtered = agg, deviceTier = HardwareScoring.DeviceTier.ULTRA_HIGH)
        assertTrue(Capped30FpsRule.matches(input))
    }

    @Test
    fun `does not fire on LOW tier device`() {
        val agg = aggregates(p99 = 30)
        val input = input(filtered = agg, deviceTier = HardwareScoring.DeviceTier.LOW)
        assertFalse(Capped30FpsRule.matches(input))
    }

    @Test
    fun `does not fire on MID tier device`() {
        // CON-003: rule should ONLY consider devices that "should" do 60+.
        val agg = aggregates(p99 = 30)
        val input = input(filtered = agg, deviceTier = HardwareScoring.DeviceTier.MID)
        assertFalse(Capped30FpsRule.matches(input))
    }

    @Test
    fun `does not fire on HIGH tier when p99 is 60`() {
        val agg = aggregates(p99 = 60)
        val input = input(filtered = agg, deviceTier = HardwareScoring.DeviceTier.HIGH)
        assertFalse(Capped30FpsRule.matches(input))
    }
}
