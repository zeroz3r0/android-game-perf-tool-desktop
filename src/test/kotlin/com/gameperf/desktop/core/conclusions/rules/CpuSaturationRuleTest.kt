package com.gameperf.desktop.core.conclusions.rules

import com.gameperf.desktop.core.conclusions.ConclusionTestFixtures.aggregates
import com.gameperf.desktop.core.conclusions.ConclusionTestFixtures.input
import com.gameperf.desktop.core.conclusions.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [CpuSaturationRule]. Pure, no mocks.
 */
class CpuSaturationRuleTest {

    @Test
    fun `fires when avg CPU is at or above 85`() {
        val agg = aggregates(avgCpu = 90, maxCpu = 99)
        val input = input(filtered = agg)
        assertTrue(CpuSaturationRule.matches(input))
        val conclusion = CpuSaturationRule.render(input)
        assertEquals(Severity.CRITICAL, conclusion.severity)
        assertEquals("cpu-saturated", conclusion.ruleId)
        assertTrue(conclusion.headline.contains("90"))
    }

    @Test
    fun `does not fire when avg CPU is moderate`() {
        val agg = aggregates(avgCpu = 70)
        assertFalse(CpuSaturationRule.matches(input(filtered = agg)))
    }

    @Test
    fun `boundary - exactly 85 percent fires`() {
        val agg = aggregates(avgCpu = 85, maxCpu = 95)
        assertTrue(CpuSaturationRule.matches(input(filtered = agg)))
    }
}
