package com.gameperf.desktop.core.conclusions

import com.gameperf.desktop.core.conclusions.rules.AdVsGameFpsGapRule
import com.gameperf.desktop.core.conclusions.rules.Capped30FpsRule
import com.gameperf.desktop.core.conclusions.rules.CpuSaturationRule
import com.gameperf.desktop.core.conclusions.rules.JankWithGoodAvgRule
import com.gameperf.desktop.core.conclusions.rules.LoadingThermalRecoveryRule
import com.gameperf.desktop.core.conclusions.rules.MemoryGrowthRule
import com.gameperf.desktop.core.conclusions.rules.StableLowFpsRule
import com.gameperf.desktop.core.conclusions.rules.ThermalThrottlingRule

/**
 * Central registry of all rules considered by [ConclusionEngine].
 *
 * Adding a new rule = adding ONE line to [all]. Single source of truth
 * (CLAUDE.md anti-duplication rule).
 *
 * Order in the list does NOT affect output — [ConclusionEngine.run] sorts
 * deterministically by (severity, id).
 *
 * @since v4.4.0
 */
object RuleRegistry {
    /** All rules considered by the engine. Order is irrelevant for output. */
    val all: List<Rule> = listOf(
        StableLowFpsRule,
        ThermalThrottlingRule,
        MemoryGrowthRule,
        JankWithGoodAvgRule,
        Capped30FpsRule,
        CpuSaturationRule,
        AdVsGameFpsGapRule,
        LoadingThermalRecoveryRule,
    )
}
