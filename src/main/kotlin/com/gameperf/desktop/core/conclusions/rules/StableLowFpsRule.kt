package com.gameperf.desktop.core.conclusions.rules

import com.gameperf.desktop.core.conclusions.Conclusion
import com.gameperf.desktop.core.conclusions.ConclusionInput
import com.gameperf.desktop.core.conclusions.Rule
import com.gameperf.desktop.core.conclusions.Severity

/**
 * Detects games stuck at low FPS while CPU and thermal headroom are still
 * available — a classic "the device can do more but the game isn't asking
 * for it" pattern.
 *
 * Predicate (all must be true):
 *  - `targetFps > 0` (avoids division by zero on degenerate inputs).
 *  - `filtered.p50 ≤ 0.7 × targetFps` (sustained low FPS, not transient dips).
 *  - `filtered.avgCpu < 50` (CPU is not the bottleneck — game logic is).
 *  - `filtered.maxTempCpu < 42°C` (device is cool, not throttling).
 *
 * Interpretation: the device has resources but the game is not using them.
 * Likely a code-side bottleneck — heavy main-thread work, inefficient scripts,
 * over-drawcall. The recommendation targets the developer.
 *
 * @since v4.4.0
 */
object StableLowFpsRule : Rule {
    override val id: String = "stable-low-fps-low-cpu"
    override val severity: Severity = Severity.WARNING

    private const val FPS_RATIO_THRESHOLD = 0.7
    private const val CPU_HEADROOM_THRESHOLD = 50.0
    private const val THERMAL_HEADROOM_THRESHOLD = 42.0

    override fun matches(input: ConclusionInput): Boolean {
        // v4.4.1 (discovery #274): without this guard a vendor-zone-catalog gap
        // that silently zeroes maxTempCpu produces a fabricated "device has
        // headroom" recommendation. Skip thermal-derived claims when the parser
        // could not produce a usable snapshot.
        if (!input.thermalAvailable) return false
        val targetFps = input.targetFps
        if (targetFps <= 0) return false
        return input.filtered.p50 <= FPS_RATIO_THRESHOLD * targetFps &&
            input.filtered.avgCpu < CPU_HEADROOM_THRESHOLD &&
            input.filtered.maxTempCpu < THERMAL_HEADROOM_THRESHOLD
    }

    override fun render(input: ConclusionInput): Conclusion {
        val p50 = input.filtered.p50
        val target = input.targetFps
        val cpu = input.filtered.avgCpu
        val temp = "%.1f".format(input.filtered.maxTempCpu)
        return Conclusion(
            ruleId = id,
            severity = severity,
            headline = "El juego se mantiene en torno a $p50 fps estables, " +
                "por debajo del objetivo de $target fps.",
            recommendation = "El dispositivo tiene margen (CPU al $cpu%, temperatura máxima ${temp}°C). " +
                "El cuello de botella probable está en el código del juego (lógica del hilo principal, " +
                "drawcalls excesivas o scripts pesados). Sugiere al desarrollador profilear el bucle " +
                "principal y revisar el coste por frame.",
        )
    }
}
