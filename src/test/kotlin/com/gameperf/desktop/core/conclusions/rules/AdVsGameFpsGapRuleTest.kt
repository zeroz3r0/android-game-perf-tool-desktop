package com.gameperf.desktop.core.conclusions.rules

import com.gameperf.desktop.core.conclusions.ConclusionTestFixtures.aggregates
import com.gameperf.desktop.core.conclusions.ConclusionTestFixtures.event
import com.gameperf.desktop.core.conclusions.ConclusionTestFixtures.input
import com.gameperf.desktop.core.conclusions.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [AdVsGameFpsGapRule]. Pure, no mocks.
 *
 * Coverage:
 *  - Fires with events present and a delta ≥ 15%.
 *  - Does NOT fire with events present but a small delta (< 15%).
 *  - Does NOT fire when no events were detected (nothing to filter).
 */
class AdVsGameFpsGapRuleTest {

    @Test
    fun `fires when events present and delta exceeds threshold`() {
        // raw 50 → filtered 60 = 20% delta.
        val raw = aggregates(avgFps = 50)
        val filtered = aggregates(avgFps = 60)
        val input = input(
            filtered = filtered,
            raw = raw,
            events = listOf(event()),
        )
        assertTrue(AdVsGameFpsGapRule.matches(input))
        val conclusion = AdVsGameFpsGapRule.render(input)
        assertEquals(Severity.INFO, conclusion.severity)
        assertEquals("ad-vs-game-fps-gap", conclusion.ruleId)
        assertTrue(conclusion.headline.contains("50"))
        assertTrue(conclusion.headline.contains("60"))
    }

    @Test
    fun `does not fire when delta is below threshold`() {
        // raw 60 → filtered 62 ≈ 3% delta.
        val raw = aggregates(avgFps = 60)
        val filtered = aggregates(avgFps = 62)
        val input = input(
            filtered = filtered,
            raw = raw,
            events = listOf(event()),
        )
        assertFalse(AdVsGameFpsGapRule.matches(input))
    }

    @Test
    fun `does not fire when no events were detected`() {
        val raw = aggregates(avgFps = 50)
        val filtered = aggregates(avgFps = 60)
        val input = input(filtered = filtered, raw = raw, events = emptyList())
        assertFalse(AdVsGameFpsGapRule.matches(input))
    }
}
