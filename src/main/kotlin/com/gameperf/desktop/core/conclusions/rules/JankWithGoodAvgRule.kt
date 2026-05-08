package com.gameperf.desktop.core.conclusions.rules

import com.gameperf.desktop.core.conclusions.Conclusion
import com.gameperf.desktop.core.conclusions.ConclusionInput
import com.gameperf.desktop.core.conclusions.Rule
import com.gameperf.desktop.core.conclusions.Severity

/**
 * Detects sessions where the average FPS looks fine but jank events are
 * frequent — the classic "good number, bad feel" trap that pure averages
 * hide and that players consistently report as stutter.
 *
 * Predicate:
 *  - `filtered.avgFps ≥ 50` (otherwise the low average itself is the story
 *    and other rules cover it — we don't want double-firing).
 *  - `sessionDurationS > 0` (avoid division by zero).
 *  - `filtered.totalJank / sessionMin ≥ 30` jank events per minute.
 *
 * @since v4.4.0
 */
object JankWithGoodAvgRule : Rule {
    override val id: String = "jank-with-good-avg"
    override val severity: Severity = Severity.WARNING

    private const val GOOD_AVG_FPS_THRESHOLD = 50.0
    private const val JANK_PER_MINUTE_THRESHOLD = 30.0

    override fun matches(input: ConclusionInput): Boolean {
        val avgFps = input.filtered.avgFps
        if (avgFps < GOOD_AVG_FPS_THRESHOLD) return false
        val durationMin = input.sessionDurationS / 60.0
        if (durationMin <= 0) return false
        val jankPerMin = input.filtered.totalJank / durationMin
        return jankPerMin >= JANK_PER_MINUTE_THRESHOLD
    }

    override fun render(input: ConclusionInput): Conclusion {
        val avg = input.filtered.avgFps
        val durationMin = (input.sessionDurationS / 60.0).coerceAtLeast(0.1)
        val jankPerMin = "%.0f".format(input.filtered.totalJank / durationMin)
        return Conclusion(
            ruleId = id,
            severity = severity,
            headline = "Aunque la media de FPS es buena ($avg), el juego presenta tirones frecuentes " +
                "($jankPerMin eventos de jank por minuto).",
            recommendation = "La media oculta picos de mal rendimiento. Estos tirones se notan más " +
                "que un FPS bajo estable. Suelen deberse a carga de assets en runtime, GC pauses, " +
                "o frames pesados puntuales (efectos, físicas masivas). Revisa con el desarrollador " +
                "el frame time histogram y los hot frames.",
        )
    }
}
