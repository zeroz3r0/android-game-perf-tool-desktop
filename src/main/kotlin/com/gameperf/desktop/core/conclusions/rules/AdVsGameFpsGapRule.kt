package com.gameperf.desktop.core.conclusions.rules

import com.gameperf.desktop.core.conclusions.Conclusion
import com.gameperf.desktop.core.conclusions.ConclusionInput
import com.gameperf.desktop.core.conclusions.Rule
import com.gameperf.desktop.core.conclusions.Severity
import kotlin.math.abs

/**
 * Informational rule that fires when the filtered average FPS differs from
 * the raw average by more than [DELTA_THRESHOLD_RATIO] (15%).
 *
 * Purpose: tell the user "yes, filtering changed the picture, and here's by
 * how much" — reassures them that the events were worth filtering. If the
 * delta is small, the rule stays silent (the dual-view UI already hides the
 * sub-line in that case).
 *
 * Predicate:
 *  - At least one event detected (no events ⇒ nothing to filter ⇒ no story).
 *  - `raw.avgFps > 0` (avoid division by zero).
 *  - `|filtered.avgFps - raw.avgFps| / raw.avgFps ≥ 0.15`.
 *
 * @since v4.4.0
 */
object AdVsGameFpsGapRule : Rule {
    override val id: String = "ad-vs-game-fps-gap"
    override val severity: Severity = Severity.INFO

    private const val DELTA_THRESHOLD_RATIO = 0.15

    override fun matches(input: ConclusionInput): Boolean {
        if (input.events.isEmpty()) return false
        val rawAvg = input.raw.avgFps.toDouble()
        if (rawAvg <= 0) return false
        val delta = (input.filtered.avgFps - input.raw.avgFps) / rawAvg
        return abs(delta) >= DELTA_THRESHOLD_RATIO
    }

    override fun render(input: ConclusionInput): Conclusion {
        val rawAvg = input.raw.avgFps
        val filteredAvg = input.filtered.avgFps
        val eventCount = input.events.size
        val deltaPct = "%.0f".format(
            ((input.filtered.avgFps - input.raw.avgFps) / input.raw.avgFps.toDouble()) * 100
        )
        return Conclusion(
            ruleId = id,
            severity = severity,
            headline = "Los $eventCount eventos detectados (anuncios, IAP, cargas) sesgan la media " +
                "de FPS: bruta $rawAvg, filtrada $filteredAvg ($deltaPct%).",
            recommendation = "La métrica filtrada refleja el rendimiento real del juego, sin la " +
                "contribución de las pantallas de anuncios o IAP. Úsala como referencia principal " +
                "y consulta la métrica bruta solo para auditar.",
        )
    }
}
