package com.gameperf.desktop.core.conclusions.rules

import com.gameperf.desktop.core.conclusions.Conclusion
import com.gameperf.desktop.core.conclusions.ConclusionInput
import com.gameperf.desktop.core.conclusions.Rule
import com.gameperf.desktop.core.conclusions.Severity

/**
 * Detects sessions where the device reaches throttling temperatures AND the
 * 5th-percentile FPS collapses well below the average — strong indicator that
 * the SoC is being clocked down to protect itself.
 *
 * Predicate (all must be true):
 *  - `filtered.maxTempCpu ≥ 45°C` OR `filtered.maxTempSkin ≥ 42°C`.
 *  - `filtered.avgFps > 0` (avoid division by zero).
 *  - `filtered.p5 < filtered.avgFps × 0.6` (significant drops, not stable).
 *
 * Why the dual threshold: the user-facing peak (`maxTempCpu`) often falls back
 * to the die value when no skin sensor is available, so the rule fires both on
 * pure-skin readings (≥42°C, the realistic comfort ceiling) and on the higher
 * die values that some devices report (≥45°C is already throttling territory
 * for most SoCs once it surfaces to the OS thermal framework).
 *
 * @since v4.4.0
 */
object ThermalThrottlingRule : Rule {
    override val id: String = "thermal-throttling"
    override val severity: Severity = Severity.CRITICAL

    private const val THERMAL_HOT_CPU_THRESHOLD = 45.0
    private const val THERMAL_HOT_SKIN_THRESHOLD = 42.0
    private const val FPS_DROP_RATIO = 0.6

    override fun matches(input: ConclusionInput): Boolean {
        // v4.4.1 (discovery #274): when thermalAvailable=false, every
        // temperature-derived predicate is unreliable. Skip the rule entirely
        // so we do not silently emit "no throttling detected" for a vendor
        // whose zones never classified.
        if (!input.thermalAvailable) return false
        val tempHot = input.filtered.maxTempCpu >= THERMAL_HOT_CPU_THRESHOLD ||
            input.filtered.maxTempSkin >= THERMAL_HOT_SKIN_THRESHOLD
        if (!tempHot) return false
        val avgFps = input.filtered.avgFps
        if (avgFps <= 0) return false
        return input.filtered.p5 < avgFps * FPS_DROP_RATIO
    }

    override fun render(input: ConclusionInput): Conclusion {
        val tempCpu = "%.1f".format(input.filtered.maxTempCpu)
        val tempSkin = "%.1f".format(input.filtered.maxTempSkin)
        val p5 = input.filtered.p5
        val avg = input.filtered.avgFps
        return Conclusion(
            ruleId = id,
            severity = severity,
            headline = "El dispositivo está alcanzando temperaturas de throttling " +
                "(CPU ${tempCpu}°C, carcasa ${tempSkin}°C) y los FPS caen a $p5 (media de $avg).",
            recommendation = "El sistema operativo está reduciendo el rendimiento del SoC para proteger " +
                "el hardware. Optimiza el coste térmico del juego: reduce drawcalls, baja la calidad " +
                "de sombras dinámicas y considera un FPS cap más bajo en sesiones largas.",
        )
    }
}
