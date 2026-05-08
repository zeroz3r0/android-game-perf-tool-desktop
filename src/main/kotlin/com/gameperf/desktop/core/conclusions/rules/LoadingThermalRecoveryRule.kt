package com.gameperf.desktop.core.conclusions.rules

import com.gameperf.desktop.core.conclusions.Conclusion
import com.gameperf.desktop.core.conclusions.ConclusionInput
import com.gameperf.desktop.core.conclusions.Rule
import com.gameperf.desktop.core.conclusions.Severity
import com.gameperf.desktop.core.events.EventType

/**
 * Informational rule that highlights how loading screens act as thermal
 * recovery windows — useful design feedback for sessions with throttling.
 *
 * Predicate:
 *  - At least one detected LOADING event with both `startMs` and `endMs`.
 *  - That loading event lasts ≥ [MIN_LOADING_DURATION_S] seconds.
 *  - The raw maximum CPU temperature exceeds the filtered maximum by
 *    ≥ [TEMP_DROP_DELTA] °C — i.e., dropping the loading window from the
 *    sample set lowered the visible peak, which means the device cooled
 *    during the loading.
 *
 * @since v4.4.0
 */
object LoadingThermalRecoveryRule : Rule {
    override val id: String = "loading-thermal-recovery"
    override val severity: Severity = Severity.INFO

    private const val MIN_LOADING_DURATION_S = 5
    private const val TEMP_DROP_DELTA = 1.5

    override fun matches(input: ConclusionInput): Boolean {
        val loadings = input.events.filter { ev ->
            ev.type == EventType.LOADING && ev.endMs != null
        }
        if (loadings.isEmpty()) return false
        val anyLong = loadings.any { ev ->
            val end = ev.endMs ?: return@any false
            val durationS = (end - ev.startMs) / 1000.0
            durationS >= MIN_LOADING_DURATION_S
        }
        if (!anyLong) return false
        return input.raw.maxTempCpu - input.filtered.maxTempCpu >= TEMP_DROP_DELTA
    }

    override fun render(input: ConclusionInput): Conclusion {
        val rawTemp = "%.1f".format(input.raw.maxTempCpu)
        val filteredTemp = "%.1f".format(input.filtered.maxTempCpu)
        return Conclusion(
            ruleId = id,
            severity = severity,
            headline = "Las pantallas de carga ofrecen al dispositivo un respiro térmico " +
                "(${rawTemp}°C de pico bruto, ${filteredTemp}°C filtrado).",
            recommendation = "Esto es una buena noticia para sesiones largas: las cargas actúan " +
                "como ventanas de enfriamiento. Considera no acortarlas demasiado en optimizaciones " +
                "futuras si el juego sufre throttling.",
        )
    }
}
