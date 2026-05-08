package com.gameperf.desktop.core.conclusions.rules

import com.gameperf.desktop.core.conclusions.Conclusion
import com.gameperf.desktop.core.conclusions.ConclusionInput
import com.gameperf.desktop.core.conclusions.Rule
import com.gameperf.desktop.core.conclusions.Severity

/**
 * Flags sessions where the game process averages ≥85% CPU — almost certain
 * primary bottleneck. Critical because it cascades into FPS drops, battery
 * drain, and thermal pressure.
 *
 * Predicate:
 *  - `filtered.avgCpu ≥ 85` (Int comparison; `MetricsAggregates.avgCpu` is Int).
 *
 * @since v4.4.0
 */
object CpuSaturationRule : Rule {
    override val id: String = "cpu-saturated"
    override val severity: Severity = Severity.CRITICAL

    private const val CPU_SATURATION_THRESHOLD = 85

    override fun matches(input: ConclusionInput): Boolean {
        return input.filtered.avgCpu >= CPU_SATURATION_THRESHOLD
    }

    override fun render(input: ConclusionInput): Conclusion {
        val cpu = input.filtered.avgCpu
        val maxCpu = input.filtered.maxCpu
        return Conclusion(
            ruleId = id,
            severity = severity,
            headline = "La CPU del proceso está saturada (uso medio del $cpu%, pico del $maxCpu%).",
            recommendation = "El juego está pidiendo más CPU de la que el dispositivo puede dar. " +
                "Esto causa caídas de FPS impredecibles y reduce la batería rápido. Revisa con el " +
                "desarrollador la lógica del hilo principal: scripts pesados por frame, físicas " +
                "complejas, o cálculos que deberían moverse a hilos secundarios o coroutines.",
        )
    }
}
