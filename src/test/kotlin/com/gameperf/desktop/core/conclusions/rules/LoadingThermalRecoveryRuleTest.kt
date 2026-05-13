package com.gameperf.desktop.core.conclusions.rules

import com.gameperf.desktop.core.conclusions.ConclusionTestFixtures.aggregates
import com.gameperf.desktop.core.conclusions.ConclusionTestFixtures.event
import com.gameperf.desktop.core.conclusions.ConclusionTestFixtures.input
import com.gameperf.desktop.core.conclusions.Severity
import com.gameperf.desktop.core.events.EventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [LoadingThermalRecoveryRule]. Pure, no mocks.
 *
 * Coverage:
 *  - Fires when there's a long-enough LOADING event AND the raw thermal peak
 *    exceeds the filtered peak by ≥ 1.5°C.
 *  - Does NOT fire without LOADING events (interstitial alone doesn't count).
 *  - Does NOT fire when the loading is too short to be a recovery window.
 *  - Does NOT fire when raw and filtered temps are nearly identical.
 */
class LoadingThermalRecoveryRuleTest {

    @Test
    fun `fires with long loading and meaningful temp drop`() {
        val raw = aggregates(maxTempCpu = 45.0)
        val filtered = aggregates(maxTempCpu = 42.0)
        val loading = event(
            type = EventType.LOADING,
            sdkSource = "Heuristic",
            startMs = 1_000L,
            endMs = 8_000L, // 7s loading
        )
        val input = input(filtered = filtered, raw = raw, events = listOf(loading))
        assertTrue(LoadingThermalRecoveryRule.matches(input))
        val conclusion = LoadingThermalRecoveryRule.render(input)
        assertEquals(Severity.INFO, conclusion.severity)
        assertEquals("loading-thermal-recovery", conclusion.ruleId)
        assertTrue(conclusion.headline.contains("45"))
    }

    @Test
    fun `does not fire without LOADING events`() {
        val raw = aggregates(maxTempCpu = 45.0)
        val filtered = aggregates(maxTempCpu = 42.0)
        // Interstitial — not a loading window.
        val input = input(filtered = filtered, raw = raw, events = listOf(event()))
        assertFalse(LoadingThermalRecoveryRule.matches(input))
    }

    @Test
    fun `does not fire when loading is too short`() {
        val raw = aggregates(maxTempCpu = 45.0)
        val filtered = aggregates(maxTempCpu = 42.0)
        // 2s loading — below MIN_LOADING_DURATION_S.
        val loading = event(type = EventType.LOADING, startMs = 1_000L, endMs = 3_000L)
        val input = input(filtered = filtered, raw = raw, events = listOf(loading))
        assertFalse(LoadingThermalRecoveryRule.matches(input))
    }

    @Test
    fun `does not fire when temp drop is negligible`() {
        val raw = aggregates(maxTempCpu = 45.0)
        val filtered = aggregates(maxTempCpu = 44.5) // 0.5°C — below threshold
        val loading = event(type = EventType.LOADING, startMs = 1_000L, endMs = 8_000L)
        val input = input(filtered = filtered, raw = raw, events = listOf(loading))
        assertFalse(LoadingThermalRecoveryRule.matches(input))
    }

    /**
     * v4.4.1 (discovery #274): when thermalAvailable=false, the rule MUST NOT
     * emit a thermal-derived recovery claim. raw vs filtered max temps would
     * both be 0 anyway, but the guard makes the intent explicit and prevents
     * false positives if either aggregate later changes shape.
     */
    @Test
    fun `does not fire when thermalAvailable is false even with valid loading event`() {
        val raw = aggregates(maxTempCpu = 45.0)
        val filtered = aggregates(maxTempCpu = 42.0)
        val loading = event(type = EventType.LOADING, startMs = 1_000L, endMs = 8_000L)
        val input = input(
            filtered = filtered, raw = raw,
            events = listOf(loading), thermalAvailable = false,
        )
        assertFalse(LoadingThermalRecoveryRule.matches(input))
    }

    /**
     * Triangulation: same fixture WITH thermalAvailable=true still fires.
     */
    @Test
    fun `still fires when thermalAvailable is true and predicates pass`() {
        val raw = aggregates(maxTempCpu = 45.0)
        val filtered = aggregates(maxTempCpu = 42.0)
        val loading = event(type = EventType.LOADING, startMs = 1_000L, endMs = 8_000L)
        val input = input(
            filtered = filtered, raw = raw,
            events = listOf(loading), thermalAvailable = true,
        )
        assertTrue(LoadingThermalRecoveryRule.matches(input))
    }
}
