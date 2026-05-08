package com.gameperf.desktop.core.conclusions

import com.gameperf.desktop.core.HardwareScoring
import com.gameperf.desktop.core.conclusions.ConclusionTestFixtures.aggregates
import com.gameperf.desktop.core.conclusions.ConclusionTestFixtures.event
import com.gameperf.desktop.core.conclusions.ConclusionTestFixtures.input
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Engine-level tests for [ConclusionEngine] + [RuleRegistry].
 *
 * Pure, no mocks. Verifies the spec contracts:
 *  - CON-001: same input → same output (determinism).
 *  - CON-002: all 8 rules registered with unique IDs.
 *  - CON-004: severity ordering CRITICAL > WARNING > INFO, then ruleId asc.
 *  - CON-007: no rules firing returns an empty list.
 */
class ConclusionEngineTest {

    @Test
    fun `CON-001 - same input yields identical output across runs`() {
        // Pick an input that fires at least one rule (CpuSaturation).
        val agg = aggregates(avgCpu = 95)
        val input = input(filtered = agg, raw = agg)
        val first = ConclusionEngine.run(input)
        val second = ConclusionEngine.run(input)
        assertEquals(first, second)
    }

    @Test
    fun `CON-002 - all 8 rules registered with unique IDs`() {
        assertEquals(8, RuleRegistry.all.size)
        val ids = RuleRegistry.all.map { it.id }
        assertEquals(ids.size, ids.distinct().size, "Rule IDs must be unique")
        // Spot-check the expected catalog ids exist.
        val expected = setOf(
            "stable-low-fps-low-cpu",
            "thermal-throttling",
            "memory-leak-suspect",
            "jank-with-good-avg",
            "fps-cap-suspect",
            "cpu-saturated",
            "ad-vs-game-fps-gap",
            "loading-thermal-recovery",
        )
        assertEquals(expected, ids.toSet())
    }

    @Test
    fun `CON-004 - severity ordering puts CRITICAL before WARNING before INFO`() {
        // Build an input that fires:
        //  - CRITICAL: cpu-saturated (avgCpu = 95)
        //  - WARNING:  stable-low-fps-low-cpu (low p50 + low CPU + cool)
        //
        // ad-vs-game-fps-gap (INFO) needs raw != filtered AND events. We feed
        // an event with a 20% delta — that gives us a third rule firing.
        //
        // Note: we set avgCpu=95 for the CRITICAL rule, but StableLowFps needs
        // avgCpu < 50. They CAN'T fire on the same `filtered`. So we use
        // separate inputs and a manual sort assertion instead.
        val criticalInput = input(filtered = aggregates(avgCpu = 95))
        val warningInput = input(
            filtered = aggregates(p50 = 18, avgCpu = 30, maxTempCpu = 35.0),
            targetFps = 30,
        )
        val infoInput = input(
            filtered = aggregates(avgFps = 60),
            raw = aggregates(avgFps = 50),
            events = listOf(event()),
        )

        // Each input fires its corresponding severity:
        val criticalOut = ConclusionEngine.run(criticalInput)
        val warningOut = ConclusionEngine.run(warningInput)
        val infoOut = ConclusionEngine.run(infoInput)
        assertTrue(criticalOut.any { it.severity == Severity.CRITICAL })
        assertTrue(warningOut.any { it.severity == Severity.WARNING })
        assertTrue(infoOut.any { it.severity == Severity.INFO })

        // Now feed a single input where multiple severities can co-fire and
        // verify ordering. Use:
        //  - CpuSaturationRule (CRITICAL): avgCpu=95
        //  - JankWithGoodAvgRule (WARNING): avgFps>=50 + jank/min>=30
        //  - AdVsGameFpsGapRule (INFO): events + delta >=15%
        val mixed = input(
            filtered = aggregates(avgFps = 58, avgCpu = 95, totalJank = 60L),
            raw = aggregates(avgFps = 48, avgCpu = 95, totalJank = 60L),
            events = listOf(event()),
            sessionDurationS = 60,
        )
        val mixedOut = ConclusionEngine.run(mixed)
        // We expect at least one of each severity. Verify ordering: CRITICAL
        // entries come before WARNING, WARNING before INFO.
        val severities = mixedOut.map { it.severity }
        val firstWarningIdx = severities.indexOf(Severity.WARNING)
        val firstInfoIdx = severities.indexOf(Severity.INFO)
        val lastCriticalIdx = severities.lastIndexOf(Severity.CRITICAL)
        if (lastCriticalIdx >= 0 && firstWarningIdx >= 0) {
            assertTrue(
                lastCriticalIdx < firstWarningIdx,
                "CRITICAL must come before WARNING. Got order: $severities",
            )
        }
        if (firstWarningIdx >= 0 && firstInfoIdx >= 0) {
            assertTrue(
                severities.lastIndexOf(Severity.WARNING) < firstInfoIdx,
                "WARNING must come before INFO. Got order: $severities",
            )
        }
    }

    @Test
    fun `CON-004 - tie within severity is broken by ascending ruleId`() {
        // Two INFO rules can both fire on the same input:
        //  - ad-vs-game-fps-gap : events + delta >=15%
        //  - fps-cap-suspect    : HIGH tier + p99 ≈ 30
        val input = input(
            filtered = aggregates(avgFps = 60, p99 = 30),
            raw = aggregates(avgFps = 50, p99 = 30),
            events = listOf(event()),
            deviceTier = HardwareScoring.DeviceTier.HIGH,
        )
        val out = ConclusionEngine.run(input)
        val infoIds = out.filter { it.severity == Severity.INFO }.map { it.ruleId }
        // Expect alphabetical: "ad-vs-game-fps-gap" before "fps-cap-suspect".
        assertTrue(
            infoIds.containsAll(listOf("ad-vs-game-fps-gap", "fps-cap-suspect")),
            "Expected both INFO rules to fire, got $infoIds",
        )
        assertEquals(infoIds.sorted(), infoIds, "INFO rules must be sorted alphabetically by id")
    }

    @Test
    fun `CON-007 - empty list when no rules fire on a perfect session`() {
        // The default ConclusionTestFixtures.input() represents a flawless
        // 60 fps, low-CPU, cool, no-event session. Nothing should fire.
        val out = ConclusionEngine.run(input())
        assertTrue(out.isEmpty(), "Expected no conclusions, got: ${out.map { it.ruleId }}")
    }
}
