package com.gameperf.desktop.core.report.kpi

/**
 * Renders the user-facing caveats section that lives at the bottom of the
 * shareable HTML report.
 *
 * Three Spanish-tuteo-formal paragraphs explain the limitations of the
 * measurements so the reader interprets the numbers correctly:
 *  1. GPU sampling status (sysfs path support paused — Sprint 1 deferred).
 *  2. 1Hz sampling cadence and what that means for sub-second spikes.
 *  3. Device-tier defaults — blank tier renders as `MID (default)` so the
 *     reader knows the thresholds came from the catalog fallback chain
 *     (matches `ComparisonTable` tier resolution).
 *
 * Spec coverage: `sdd/shareable-html-report/spec` — Requirement: Caveats Section.
 *
 * Pure: deterministic, no I/O.
 *
 * @since v4.6 (shareable-html-report Block F)
 */
internal fun renderCaveats(deviceTier: String): String {
    val tierLabel = if (deviceTier.isBlank()) "MID (default)" else deviceTier
    return buildString {
        append("<section id=\"sec-caveats\" class=\"kpi-caveats\">")
        append("<h2>Notas y limitaciones</h2>")
        append(
            "<p>La métrica de GPU todavía no se incluye en la nota global: el muestreo " +
                "por sysfs depende del vendor y, mientras el Sprint 1 sigue pausado, este " +
                "valor se muestra como referencia pero no afecta el score.</p>",
        )
        append(
            "<p>Las muestras de CPU, RAM, temperatura y batería se capturan a 1Hz. " +
                "Picos de menos de un segundo pueden no aparecer reflejados; conviene " +
                "cruzar los valores con el gráfico de frame-time para detectar jank corto.</p>",
        )
        append(
            "<p>El reporte usa los umbrales del catálogo para la tier <strong>$tierLabel</strong>. " +
                "Si el dispositivo no fue identificado, se usan los umbrales MID por defecto. " +
                "Calibrá la tier desde la configuración para obtener un score más realista en " +
                "gama alta o gama baja.</p>",
        )
        append("</section>")
    }
}
