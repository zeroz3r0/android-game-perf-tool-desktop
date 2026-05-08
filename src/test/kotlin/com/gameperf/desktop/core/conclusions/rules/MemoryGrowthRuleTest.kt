package com.gameperf.desktop.core.conclusions.rules

import com.gameperf.desktop.core.conclusions.ConclusionTestFixtures.flatMemSeries
import com.gameperf.desktop.core.conclusions.ConclusionTestFixtures.input
import com.gameperf.desktop.core.conclusions.ConclusionTestFixtures.memSeries
import com.gameperf.desktop.core.conclusions.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [MemoryGrowthRule]. Pure, no mocks.
 *
 * Coverage:
 *  - Fires on monotonic linear growth at 1 MB/s over 60 samples.
 *  - Does NOT fire on flat series (slope ≈ 0).
 *  - Does NOT fire on too-short series (< 30 samples) even with steep slope.
 *  - Boundary: slope exactly 0.5 MB/s fires (≥ threshold).
 */
class MemoryGrowthRuleTest {

    @Test
    fun `fires on sustained linear growth`() {
        val series = memSeries(durationS = 60, startMb = 200.0, slopeMbPerS = 1.0)
        val input = input(memTimedFiltered = series)
        assertTrue(MemoryGrowthRule.matches(input))
        val conclusion = MemoryGrowthRule.render(input)
        assertEquals(Severity.WARNING, conclusion.severity)
        assertEquals("memory-leak-suspect", conclusion.ruleId)
        assertTrue(conclusion.headline.contains("MB"))
    }

    @Test
    fun `does not fire on flat memory`() {
        val series = flatMemSeries(durationS = 60, valueMb = 200.0)
        assertFalse(MemoryGrowthRule.matches(input(memTimedFiltered = series)))
    }

    @Test
    fun `does not fire when series is too short`() {
        // Only 10 samples — below MIN_SAMPLES — even though slope is steep.
        val series = memSeries(durationS = 10, startMb = 100.0, slopeMbPerS = 5.0)
        assertFalse(MemoryGrowthRule.matches(input(memTimedFiltered = series)))
    }

    @Test
    fun `boundary - slope exactly at threshold fires`() {
        // 30 samples at exactly 0.5 MB/s slope.
        val series = memSeries(durationS = 30, startMb = 100.0, slopeMbPerS = 0.5)
        assertTrue(MemoryGrowthRule.matches(input(memTimedFiltered = series)))
    }
}
