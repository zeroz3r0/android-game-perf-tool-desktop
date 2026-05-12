package com.gameperf.desktop.core.devactions

import com.gameperf.desktop.core.HardwareScoring
import com.gameperf.desktop.core.conclusions.ConclusionInput
import com.gameperf.desktop.core.metrics.MetricsAggregates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [EvidenceBuilder] — Sprint 0 stub implementation.
 *
 * Sprint 0 only requires the builder exist and produce a non-null
 * [DevActionEvidence] for each of the 8 production ruleIds; the exact
 * keys per rule are Sprint 1 polish.
 *
 * Spec: `sdd/dev-action-brief/spec` — DAB-013.
 * Design: `sdd/dev-action-brief/design` — EvidenceBuilder section.
 *
 * @since v4.5.0
 */
class EvidenceBuilderTest {

    @Test
    fun `DAB-013 - EvidenceBuilder returns DevActionEvidence for each production ruleId`() {
        val input = baselineInput()
        val productionRuleIds = listOf(
            "stable-low-fps-low-cpu",
            "thermal-throttling",
            "memory-leak-suspect",
            "jank-with-good-avg",
            "fps-cap-suspect",
            "cpu-saturated",
            "ad-vs-game-fps-gap",
            "loading-thermal-recovery",
        )

        productionRuleIds.forEach { ruleId ->
            val evidence = EvidenceBuilder.build(ruleId, input)
            assertTrue(evidence.metric.isNotEmpty(), "metric must be set for $ruleId")
            assertTrue(evidence.segment.isNotEmpty(), "segment must be set for $ruleId")
        }
    }

    @Test
    fun `DAB-013 - unknown ruleId produces a safe fallback evidence record`() {
        val input = baselineInput()
        val evidence = EvidenceBuilder.build("non-existent-rule", input)

        // Must not throw. Must produce a record where segment is one of the
        // 3 documented values (so the renderer never sees garbage).
        assertTrue(evidence.segment in setOf("RAW", "FILTERED", "EVENT_WINDOW"))
    }

    @Test
    fun `DAB-013 - cpu-saturated evidence references the cpu metric`() {
        val input = baselineInput()
        val evidence = EvidenceBuilder.build("cpu-saturated", input)
        assertEquals("cpu", evidence.metric)
    }

    private fun baselineInput(): ConclusionInput = ConclusionInput(
        filtered = aggregates(),
        raw = aggregates(),
        targetFps = 60,
        deviceTier = HardwareScoring.DeviceTier.MID,
        events = emptyList(),
        sessionDurationS = 120,
    )

    private fun aggregates(): MetricsAggregates = MetricsAggregates(
        avgFps = 60, minFps = 58, maxFps = 60,
        p1 = 58, p5 = 58, p50 = 60, p90 = 60, p99 = 60,
        avgFrameTime = 16.6, p99FrameTime = 16.6,
        peakMem = 200L, avgCpu = 30, maxCpu = 45,
        maxTempCpu = 35.0, maxTempGpu = 35.0, maxTempSkin = 32.0, maxTempDieCpu = 40.0,
        totalJank = 0L, totalStutter = 0, sampleCount = 120,
    )
}
