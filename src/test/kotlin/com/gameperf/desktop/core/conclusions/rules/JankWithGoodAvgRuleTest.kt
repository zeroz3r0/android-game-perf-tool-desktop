package com.gameperf.desktop.core.conclusions.rules

import com.gameperf.desktop.core.conclusions.ConclusionTestFixtures.aggregates
import com.gameperf.desktop.core.conclusions.ConclusionTestFixtures.input
import com.gameperf.desktop.core.conclusions.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [JankWithGoodAvgRule]. Pure, no mocks.
 *
 * Coverage:
 *  - Fires when avg FPS is good but jank/min ≥ 30.
 *  - Does NOT fire when avg FPS is low (other rules cover it).
 *  - Does NOT fire when jank is rare even at good avg FPS.
 *  - Session duration of 0 short-circuits safely.
 */
class JankWithGoodAvgRuleTest {

    @Test
    fun `fires when avg fps good and jank frequent`() {
        // 60s session, 60 jank events ⇒ 60 jank/min — well above the 30 threshold.
        val agg = aggregates(avgFps = 58, totalJank = 60L)
        val input = input(filtered = agg, sessionDurationS = 60)
        assertTrue(JankWithGoodAvgRule.matches(input))
        val conclusion = JankWithGoodAvgRule.render(input)
        assertEquals(Severity.WARNING, conclusion.severity)
        assertEquals("jank-with-good-avg", conclusion.ruleId)
        assertTrue(conclusion.headline.contains("58"))
    }

    @Test
    fun `does not fire when avg fps is low`() {
        val agg = aggregates(avgFps = 30, totalJank = 60L)
        assertFalse(JankWithGoodAvgRule.matches(input(filtered = agg, sessionDurationS = 60)))
    }

    @Test
    fun `does not fire when jank is rare`() {
        // 60s session, 5 jank events ⇒ 5/min, well below threshold.
        val agg = aggregates(avgFps = 58, totalJank = 5L)
        assertFalse(JankWithGoodAvgRule.matches(input(filtered = agg, sessionDurationS = 60)))
    }

    @Test
    fun `does not crash on zero session duration`() {
        val agg = aggregates(avgFps = 58, totalJank = 60L)
        assertFalse(JankWithGoodAvgRule.matches(input(filtered = agg, sessionDurationS = 0)))
    }
}
