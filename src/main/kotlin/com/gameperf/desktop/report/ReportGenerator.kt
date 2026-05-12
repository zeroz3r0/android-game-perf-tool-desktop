@file:Suppress("MaxLineLength") // HTML template strings are inherently long
package com.gameperf.desktop.report

import com.gameperf.desktop.core.AppVersion
import com.gameperf.desktop.core.SessionHistory
import com.gameperf.desktop.core.conclusions.Conclusion
import com.gameperf.desktop.core.conclusions.Severity
import com.gameperf.desktop.core.events.Confidence
import com.gameperf.desktop.core.events.DetectedEvent
import com.gameperf.desktop.core.events.EventType
import com.gameperf.desktop.core.metrics.MetricsAggregates
import com.gameperf.desktop.core.model.DeviceInfo
import com.gameperf.desktop.core.model.FPowerDiagnostic
import com.gameperf.desktop.core.model.FPowerUnavailableReason
import com.gameperf.desktop.core.model.ThermalDiagnostic
import com.gameperf.desktop.core.model.ThermalUnavailableReason
import com.gameperf.desktop.viewmodel.DetectionMode
import com.gameperf.desktop.viewmodel.MarkerType
import com.gameperf.desktop.viewmodel.SessionMarker
import com.gameperf.desktop.ui.util.fmtUS
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object ReportGenerator {

    // ══════════ EMBEDDED ASSETS (offline Chart.js) ══════════
    // Loaded once per process via `by lazy` (thread-safe, SYNCHRONIZED mode by default).
    // Keeps generated HTML fully offline-capable: no CDN references, no runtime network.
    private object Assets {
        val chartJs: String by lazy { loadResource("/web/chart.umd.js") }
        val annotationPlugin: String by lazy { loadResource("/web/chartjs-plugin-annotation.min.js") }
        val zoomPlugin: String by lazy { loadResource("/web/chartjs-plugin-zoom.min.js") }

        private fun loadResource(path: String): String =
            javaClass.getResourceAsStream(path)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                ?: error("Missing classpath resource: $path")
    }

    @Suppress("LongParameterList")
    fun generate(
        pkg: String, info: DeviceInfo?, grade: Char, score: Int, duration: Int,
        fpsHistory: List<Int>, memHistory: List<Long>, nativeHistory: List<Long>, javaHistory: List<Long>,
        cpuHistory: List<Int>, tempCpuHistory: List<Double>, tempGpuHistory: List<Double>, tempSkinHistory: List<Double>,
        allFrameTimes: List<Double>,
        avgFps: Int, minFps: Int, maxFps: Int, p1: Int, p5: Int, p50: Int, p90: Int, p99: Int,
        avgFrameTime: Double, p99FrameTime: Double,
        peakMem: Long, avgCpu: Int, maxCpu: Int, maxTempCpu: Double, maxTempGpu: Double,
        batteryStart: Int, batteryEnd: Int, frameDrops: Int, jank: Int, stutter: Int,
        problems: List<String>, isWifi: Boolean,
        deviceGrade: Char = ' ', deviceScore: Int = 0, deviceTier: String = "",
        fpsTimestamps: List<Pair<Int, Int>> = emptyList(),
        markers: List<SessionMarker> = emptyList(),
        // v4.3.6: target FPS used by [ReportGrading] for proportional metric-card
        // grading (Path C of the dual-grading fix). Defaults to 60 for back-compat
        // with the only legacy fixture caller (`ReportRenderingTest`).
        targetFps: Int = 60,
        // v4.3.6: peak skin (case) temperature. When > 0 the temperature card
        // shows "Skin" + sub-line "Die máx: X°C". When ≤ 0 the card falls back
        // to die-CPU semantics (legacy behavior).
        maxTempSkin: Double = 0.0,
        // v4.4.0: auto event detection / dual-view metrics / conclusions payload.
        // All defaults preserve pre-v4.4.0 rendering when the caller has nothing
        // to pass (legacy fixtures, ReportRenderingTest, sessions captured before
        // the upgrade): no banner, no #sec-events, no #sec-conclusions, no raw
        // sub-lines on metric cards.
        events: List<DetectedEvent> = emptyList(),
        conclusions: List<Conclusion> = emptyList(),
        filteredAggregates: MetricsAggregates? = null,
        rawAggregates: MetricsAggregates? = null,
        detectionMode: DetectionMode = DetectionMode.MANUAL_ONLY,
        detectorWarnings: List<String> = emptyList(),
        captureStartMs: Long = 0L,
        // v4.4.1 -- thermal pipeline availability flag + diagnostic payload.
        // When `thermalAvailable = false` the temperature card renders "N/D"
        // instead of "0°C" (which the user would otherwise read as "device is
        // cold") and the temp section gets a Spanish-tuteo-formal banner
        // listing the raw vendor zone names that the classifier could not
        // bucket. Defaults preserve pre-v4.4.1 rendering for legacy fixtures
        // (ReportRenderingTest, ReportGradingTest) and re-renders of v4.3.x
        // sessions reloaded from `.gameperf` history files.
        thermalAvailable: Boolean = true,
        thermalDiagnostic: ThermalDiagnostic? = null,
        // v4.5.0 -- FPower (mW/frame) pipeline payload. All defaulted so
        // legacy fixtures (ReportRenderingTest, ReportGradingTest) and pre-
        // v4.5.0 history re-renders skip the FPower section entirely. The
        // card is rendered only when (fpowerAvailable && fpowerHistory.isNotEmpty())
        // OR (!fpowerAvailable && fpowerDiagnostic != null) per spec FPW-009.
        // See design §10 + ADR-5 for the Spanish-tuteo-formal banner copy.
        fpowerHistory: List<Double> = emptyList(),
        fpowerAvg: Double = 0.0,
        fpowerPeak: Double = 0.0,
        fpowerAvailable: Boolean = true,
        fpowerDiagnostic: FPowerDiagnostic? = null,
    ): String {
        val dir = File(System.getProperty("user.home"), "GamePerf Reports")
        dir.mkdirs()
        val date = SimpleDateFormat("yyyy-MM-dd_HHmm").format(Date())
        val safePkg = pkg.replace(".", "_").takeLast(30)
        val deviceName = (info?.model ?: "Unknown").replace(" ", "_")
        val file = File(dir, "informe_${safePkg}_${deviceName}_$date.html")

        val gc = gradeColor(grade)
        val gcBg = gradeColorBg(grade)
        val dateDisplay = SimpleDateFormat("dd/MM/yyyy HH:mm").format(Date())
        val dateISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(Date())
        val durStr = "${duration / 60}m ${duration % 60}s"
        val batteryDrain = batteryStart - batteryEnd
        val sessionId = UUID.randomUUID().toString().take(8).uppercase()
        val stability = if (avgFps > 0 && fpsHistory.size > 1) {
            val range = (maxFps - minFps).toDouble()
            ((1 - range / avgFps / 2).coerceIn(0.0, 1.0) * 100).toInt()
        } else 100

        val verdict = when (grade) {
            'A' -> "Rendimiento excelente. El juego corre de forma fluida y estable."
            'B' -> "Buen rendimiento con areas menores de mejora."
            'C' -> "Rendimiento aceptable. Hay caidas notables que afectan la experiencia."
            'D' -> "Rendimiento deficiente. Problemas frecuentes de fluidez."
            else -> "Rendimiento critico. El juego no es jugable de forma aceptable."
        }

        // Metric grades
        // v4.3.6: FPS + frame-time cards now use proportional helpers from
        // [ReportGrading]. A 30-fps Unity-vsync game on an S23 hits its own
        // target → A on both cards instead of the legacy hardcoded C/B.
        val fpsGrade = ReportGrading.fpsCardGrade(avgFps = avgFps, targetFps = targetFps)
        val ftGrade = ReportGrading.frameTimeCardGrade(avgFrameTime = avgFrameTime, targetFps = targetFps)
        val memGrade = metricGrade(100 - (peakMem.toInt() / 30).coerceIn(0, 100), 80, 60, 40, 20)
        val cpuGrade = metricGrade(100 - avgCpu, 70, 55, 40, 20)
        // v4.3.6: temperature card now grades against the SKIN temp when available.
        // Skin throttling is ~42°C, die throttling is ~95°C — separate yardsticks.
        val showSkinAsPrimary = maxTempSkin > 0
        val tempForGrade = if (showSkinAsPrimary) maxTempSkin else maxTempCpu
        val tempGrade = if (tempForGrade <= 0) 'A' else metricGrade(
            100 - ((tempForGrade - 30).toInt().coerceIn(0, 30) * 100 / 30), 80, 60, 40, 20
        )

        // Chart data
        val fpsD = if (fpsTimestamps.isNotEmpty()) fpsTimestamps.joinToString(",") { "${it.second}" }
        else fpsHistory.joinToString(",")
        val fpsL = if (fpsTimestamps.isNotEmpty()) fpsTimestamps.joinToString(",") { "\"${it.first}s\"" }
        else fpsHistory.indices.joinToString(",") { "\"${it + 1}s\"" }
        val memD = memHistory.joinToString(",")
        val natD = nativeHistory.joinToString(",")
        val javD = javaHistory.joinToString(",")
        val memL = memHistory.indices.joinToString(",") { "\"${it + 1}s\"" }
        val cpuD = cpuHistory.joinToString(",")
        val tcD = tempCpuHistory.joinToString(",") { fmtUS("%.1f", it) }
        val tgD = tempGpuHistory.joinToString(",") { fmtUS("%.1f", it) }
        val tsD = tempSkinHistory.joinToString(",") { fmtUS("%.1f", it) }
        val tL = (1..maxOf(cpuHistory.size, tempCpuHistory.size, 1)).joinToString(",") { "\"${it}s\"" }

        val ftBuckets = listOf(
            allFrameTimes.count { it < 8.0 },
            allFrameTimes.count { it in 8.0..16.66 },
            allFrameTimes.count { it in 16.67..33.32 },
            allFrameTimes.count { it in 33.33..49.99 },
            allFrameTimes.count { it in 50.0..99.99 },
            allFrameTimes.count { it >= 100.0 }
        ).joinToString(",")

        // Marker annotations for FPS chart — use custom color if available
        val markerAnnotationsJs = if (markers.isNotEmpty()) {
            markers.mapIndexed { i, m ->
                val labelText = m.title.ifEmpty { m.note.ifEmpty { m.type.label } }
                val color = m.colorHex.ifEmpty { markerColorHex(m.type) }
                """m$i:{type:'line',xMin:'${m.timestampSeconds}s',xMax:'${m.timestampSeconds}s',borderColor:'$color',borderWidth:2,borderDash:[4,4],label:{content:'${escJs(labelText)}',display:true,position:'start',backgroundColor:'$color',color:'#fff',font:{size:10,weight:'bold'},padding:4,borderRadius:4}}"""
            }.joinToString(",")
        } else ""

        // v4.4.0: unified events + manual markers section. Replaces the legacy
        // markers-only section with a chronological table that distinguishes
        // manual entries (orange) from auto-detected ones (cyan) via the source
        // column. Hidden when both lists are empty.
        val eventsHtml = sectionEvents(markers, events, captureStartMs)

        // v4.4.0: detection-mode banner + auto-event live count.
        val detectionBannerHtml = detectionModeBanner(detectionMode, events.size, detectorWarnings)

        // v4.4.0: excessive-filter callout (>70% of session excluded by events).
        val excessiveCalloutHtml = excessiveFilterCallout(
            isExcessiveFilterTriggered(detectorWarnings, filteredAggregates, rawAggregates)
        )

        // v4.4.0: conclusions section. Three states:
        //   - non-empty list → render cards
        //   - empty list with sufficient data → "no issues" empty state
        //   - empty list with no aggregates → omit (legacy / pre-v4.4.0 callers)
        val conclusionsHtml = when {
            conclusions.isNotEmpty() -> sectionConclusions(conclusions)
            // Only show the "no issues" empty state when we have evidence the engine
            // actually ran (filteredAggregates non-null is the proxy). Pre-v4.4.0
            // callers (legacy fixtures, old session re-renders) get the original
            // section-less rendering.
            filteredAggregates != null -> sectionConclusionsEmpty()
            else -> ""
        }

        // v4.5.0 — FPower section (spec FPW-009). Empty string when there's
        // nothing to render (legacy callers, ultra-short captures, defaulted
        // args). Injected after the temperature section in the template.
        val fpowerSectionHtml = fpowerSection(
            history = fpowerHistory,
            avg = fpowerAvg,
            peak = fpowerPeak,
            available = fpowerAvailable,
            diagnostic = fpowerDiagnostic,
        )

        // Problems HTML
        val problemsHtml = if (problems.isEmpty()) {
            """<div class="status-box status-ok"><span class="status-icon">&#10003;</span> Sin problemas criticos detectados. Rendimiento optimo.</div>"""
        } else {
            problems.mapIndexed { i, p ->
                val severity = when {
                    p.contains("severa", true) || p.contains("critico", true) || p.contains("saturada", true) -> "critical"
                    p.contains("alto", true) || p.contains("alta", true) || p.contains("thermal", true) || p.contains("Temperatura", true) -> "warning"
                    else -> "info"
                }
                val icon = when (severity) { "critical" -> "&#9888;"; "warning" -> "&#9888;"; else -> "&#8505;" }
                """<div class="problem-row problem-$severity"><span class="problem-icon">$icon</span><span class="problem-num">#${i + 1}</span><span class="problem-text">$p</span></div>"""
            }.joinToString("\n    ")
        }

        // FPS per-second expandable table
        val fpsTableHtml = if (fpsTimestamps.isNotEmpty()) {
            """
    <div class="expandable">
        <button class="expand-btn" onclick="this.parentElement.classList.toggle('open')">
            <span class="expand-icon">&#9654;</span> FPS por segundo (correlacion con video)
        </button>
        <div class="expand-content">
            <p class="card-desc">Cada fila corresponde al segundo exacto de la captura. Usa estos timestamps para localizar caidas en el video.</p>
            <div class="table-scroll">
            <table class="data-table compact">
                <thead><tr><th>Seg</th><th>FPS</th><th>Estado</th><th style="width:45%">Visual</th></tr></thead>
                <tbody>
                ${fpsTimestamps.joinToString("\n                ") { (sec, fps) ->
                val c = when { fps < 25 -> "bad"; fps < 40 -> "warn"; else -> "good" }
                val pct = (fps.coerceIn(0, 65) * 100 / 65)
                val barColor = when { fps < 25 -> "#ef4444"; fps < 40 -> "#f59e0b"; else -> "#10b981" }
                val label = when { fps < 20 -> "Critico"; fps < 30 -> "Bajo"; fps < 45 -> "Medio"; fps < 55 -> "Bueno"; else -> "Excelente" }
                """<tr><td class="mono">${sec}s</td><td class="$c">${fps}</td><td class="$c small-text">${label}</td><td><div class="bar-track"><div class="bar-fill" style="width:${pct}%;background:$barColor"></div></div></td></tr>"""
            }}
                </tbody>
            </table>
            </div>
        </div>
    </div>"""
        } else ""

        // JSON session data
        val jsonData = buildJsonData(
            pkg, info, grade, score, duration, avgFps, minFps, maxFps,
            p1, p5, p50, p90, p99, avgFrameTime, p99FrameTime,
            peakMem, avgCpu, maxCpu, maxTempCpu, maxTempGpu,
            batteryStart, batteryEnd, frameDrops, jank, stutter,
            problems, isWifi, deviceGrade, deviceScore, deviceTier,
            stability, sessionId, dateISO
        )

        val html = buildString {
            append("""<!DOCTYPE html>
<html lang="es">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1.0">
<title>Informe de Rendimiento — ${esc(pkg)}</title>
<script>${Assets.chartJs}</script>
<script>${Assets.annotationPlugin}</script>
<script>${Assets.zoomPlugin}</script>
<style>
$CSS
</style>
</head>
<body>

<div class="fab-group">
    <button class="fab" onclick="window.print()" title="Descargar PDF">&#128424;</button>
    <button class="fab fab-secondary" onclick="copyJson()" title="Copiar JSON" id="jsonBtn">&#128203;</button>
</div>

<nav class="topnav" id="topnav">
    <div class="nav-inner">
        <a href="#sec-summary" class="nav-link">Resumen</a>
        ${if (conclusionsHtml.isNotEmpty()) """<a href="#sec-conclusions" class="nav-link">Conclusiones</a>""" else ""}
        <a href="#sec-dashboard" class="nav-link">Metricas</a>
        <a href="#sec-fps" class="nav-link">FPS</a>
        <a href="#sec-frametime" class="nav-link">Frame Time</a>
        <a href="#sec-memory" class="nav-link">Memoria</a>
        <a href="#sec-cpu" class="nav-link">CPU</a>
        <a href="#sec-temp" class="nav-link">Temp</a>
        <a href="#sec-problems" class="nav-link">Problemas</a>
        ${if (eventsHtml.isNotEmpty()) """<a href="#sec-events" class="nav-link">Eventos</a>""" else ""}
        <a href="#sec-device" class="nav-link">Hardware</a>
    </div>
</nav>

<div class="container">

<header class="report-header" id="sec-header">
    <div class="header-bg"></div>
    <div class="header-content">
        <div class="header-badge">Game Performance Tool</div>
        <h1 class="header-title">Informe de Rendimiento</h1>
        <p class="header-pkg">${esc(pkg)}</p>
        <div class="header-meta">
            <span>&#128197; ${dateDisplay}</span><span class="meta-sep">|</span>
            <span>&#9201; ${durStr}</span><span class="meta-sep">|</span>
            <span>${if (isWifi) "&#128246; WiFi" else "&#128268; USB"}</span><span class="meta-sep">|</span>
            <span>&#128241; ${esc(info?.model ?: "Desconocido")}</span>
        </div>
        <div class="header-session">Session ID: $sessionId</div>
    </div>
</header>

$detectionBannerHtml

<section id="sec-summary" class="card card-summary">
    <div class="summary-grid">
        <div class="summary-grade">
            <div class="grade-ring" style="--grade-color:$gc;--grade-bg:$gcBg">
                <div class="grade-ring-inner">
                    <span class="grade-letter" style="color:$gc">$grade</span>
                    <span class="grade-score">${score}/100</span>
                </div>
            </div>
            ${if (deviceGrade != ' ') """
            <div class="device-grade-pill" style="border-color:${gradeColor(deviceGrade)}40">
                <span class="device-grade-label">Ajustada ($deviceTier)</span>
                <span class="device-grade-letter" style="color:${gradeColor(deviceGrade)}">$deviceGrade</span>
                <span class="device-grade-score">${deviceScore}/100</span>
            </div>""" else ""}
        </div>
        <div class="summary-info">
            <h2>Resumen Ejecutivo</h2>
            <p class="verdict">$verdict</p>
            <div class="summary-stats">
                <div class="summary-stat"><span class="summary-stat-value ${fpsClass(avgFps)}">${avgFps}</span><span class="summary-stat-label">FPS Prom.</span></div>
                <div class="summary-stat"><span class="summary-stat-value ${fpsClass(p1)}">${p1}</span><span class="summary-stat-label">P1 FPS</span></div>
                <div class="summary-stat"><span class="summary-stat-value">${fmtUS("%.1f", avgFrameTime)}ms</span><span class="summary-stat-label">Frame Time</span></div>
                <div class="summary-stat"><span class="summary-stat-value">${peakMem}MB</span><span class="summary-stat-label">Mem. Pico</span></div>
                <div class="summary-stat"><span class="summary-stat-value ${cls(avgCpu, 85, 70)}">${avgCpu}%</span><span class="summary-stat-label">CPU Prom.</span></div>
                <div class="summary-stat"><span class="summary-stat-value">${stability}%</span><span class="summary-stat-label">Estabilidad</span></div>
            </div>
        </div>
    </div>
</section>

$conclusionsHtml

$excessiveCalloutHtml

<section id="sec-dashboard" class="metrics-dashboard">
    <h2 class="section-title">&#128202; Panel de Metricas</h2>
    <div class="metrics-grid">
        ${metricCard(
            "FPS", "$avgFps", "fps", fpsGrade, gradeColor(fpsGrade),
            "Min $minFps / Max $maxFps / P1 $p1" +
                rawSubline(avgFps.toDouble(), rawAggregates?.avgFps?.toDouble(), "")
        )}
        ${metricCard(
            "Frame Time", "${fmtUS("%.1f", avgFrameTime)}ms", "frametime", ftGrade, gradeColor(ftGrade),
            "P99 ${fmtUS("%.1f", p99FrameTime)}ms / Jank $jank" +
                rawSubline(avgFrameTime, rawAggregates?.avgFrameTime, "ms")
        )}
        ${metricCard(
            "Memoria", "${peakMem}MB", "memory", memGrade, gradeColor(memGrade),
            "Inicio ${memHistory.firstOrNull() ?: "?"}MB / Final ${memHistory.lastOrNull() ?: "?"}MB" +
                rawSubline(peakMem.toDouble(), rawAggregates?.peakMem?.toDouble(), "MB")
        )}
        ${metricCard(
            "CPU", "${avgCpu}%", "cpu", cpuGrade, gradeColor(cpuGrade),
            "Max ${maxCpu}%" +
                rawSubline(avgCpu.toDouble(), rawAggregates?.avgCpu?.toDouble(), "%")
        )}
        ${
            // v4.3.6: card title + value + sub-line all depend on whether the
            // device exposed a skin sensor. Skin = case temp the user feels.
            // Die = silicon temp, routinely 80-95°C under load (NORMAL).
            //
            // v4.4.1 (temperature-not-shown): when the thermal pipeline reports
            // unavailable (!thermalAvailable) the card renders "N/D" + sub-line
            // "Sensor no disponible" with a neutral grade ('A' to avoid the
            // misleading red/yellow visual). The user would otherwise read the
            // legacy "0°C" / "N/A" fallback as "device is cold".
            run {
                if (!thermalAvailable) {
                    metricCard(
                        title = "Temperatura",
                        value = "N/D",
                        icon = "temp",
                        grade = 'A',
                        gc = "#94a3b8",
                        detail = "Sensor no disponible",
                    )
                } else {
                    val cardTitle = if (showSkinAsPrimary) "Temperatura piel" else "Temperatura die"
                    val primaryValue = when {
                        showSkinAsPrimary -> "${maxTempSkin.toInt()}\u00B0C"
                        maxTempCpu > 0 -> "${maxTempCpu.toInt()}\u00B0C"
                        else -> "N/A"
                    }
                    val subLine = when {
                        showSkinAsPrimary && maxTempCpu > 0 -> "Die máx: ${maxTempCpu.toInt()}\u00B0C"
                        maxTempGpu > 0 -> "GPU ${maxTempGpu.toInt()}\u00B0C"
                        else -> "Solo CPU"
                    }
                    metricCard(cardTitle, primaryValue, "temp", tempGrade, gradeColor(tempGrade), subLine)
                }
            }
        }
        ${metricCard("Bateria", "${batteryDrain}%", "battery",
                if (batteryDrain <= 3) 'A' else if (batteryDrain <= 6) 'B' else if (batteryDrain <= 10) 'C' else 'D',
                gradeColor(if (batteryDrain <= 3) 'A' else if (batteryDrain <= 6) 'B' else if (batteryDrain <= 10) 'C' else 'D'),
                "${batteryStart}% \u2192 ${batteryEnd}% (${if (isWifi) "WiFi" else "USB"})")}
    </div>
</section>

<section id="sec-fps" class="card">
    <div class="card-header">
        <h2>&#127918; FPS — Frames por Segundo</h2>
        <span class="card-badge" style="background:${gradeColor(fpsGrade)}20;color:${gradeColor(fpsGrade)}">${fpsGrade}</span>
    </div>
    <p class="card-desc">Medido desde SurfaceFlinger con ventana temporal de 1 segundo. Objetivo: 60 FPS estable.</p>
    <div class="stats-row">
        <div class="stat-pill"><span class="stat-pill-label">P1</span><span class="stat-pill-value ${cls(p1, 20, 30, "r")}">${p1}</span></div>
        <div class="stat-pill"><span class="stat-pill-label">P5</span><span class="stat-pill-value ${cls(p5, 25, 35, "r")}">${p5}</span></div>
        <div class="stat-pill"><span class="stat-pill-label">P50</span><span class="stat-pill-value">${p50}</span></div>
        <div class="stat-pill"><span class="stat-pill-label">P90</span><span class="stat-pill-value">${p90}</span></div>
        <div class="stat-pill"><span class="stat-pill-label">P99</span><span class="stat-pill-value">${p99}</span></div>
        <div class="stat-pill"><span class="stat-pill-label">Min</span><span class="stat-pill-value bad">${minFps}</span></div>
        <div class="stat-pill"><span class="stat-pill-label">Max</span><span class="stat-pill-value good">${maxFps}</span></div>
        <div class="stat-pill stat-pill-accent"><span class="stat-pill-label">Promedio</span><span class="stat-pill-value" style="color:$gc;font-size:1.2em">${avgFps}</span></div>
        <div class="stat-pill"><span class="stat-pill-label">Estabilidad</span><span class="stat-pill-value ${if (stability < 70) "warn" else "good"}">${stability}%</span></div>
    </div>
    <p class="hint">P1 = el peor 1% de lecturas. Si P1 es bajo, hay tirones puntuales aunque el promedio sea bueno.</p>
    <div class="chart-container"><canvas id="fpsChart"></canvas></div>
    <p class="hint" style="margin-top:8px">Usa scroll del raton para hacer zoom. Arrastra para desplazarte.</p>
    $fpsTableHtml
</section>

<section id="sec-frametime" class="card">
    <div class="card-header">
        <h2>&#9201; Distribucion de Frame Time</h2>
        <span class="card-badge" style="background:${gradeColor(ftGrade)}20;color:${gradeColor(ftGrade)}">${ftGrade}</span>
    </div>
    <p class="card-desc">Tiempo que tarda cada frame en renderizarse. Menos = mejor. >16.67ms = por debajo de 60fps.</p>
    <div class="stats-row">
        <div class="stat-pill"><span class="stat-pill-label">Promedio</span><span class="stat-pill-value">${fmtUS("%.1f", avgFrameTime)}ms</span></div>
        <div class="stat-pill"><span class="stat-pill-label">P99</span><span class="stat-pill-value ${cls(p99FrameTime.toInt(), 50, 17, "r")}">${fmtUS("%.1f", p99FrameTime)}ms</span></div>
        <div class="stat-pill"><span class="stat-pill-label">Jank (&gt;16ms)</span><span class="stat-pill-value warn">${jank}</span></div>
        <div class="stat-pill"><span class="stat-pill-label">Stutter (&gt;100ms)</span><span class="stat-pill-value bad">${stutter}</span></div>
    </div>
    <div class="chart-container"><canvas id="ftChart"></canvas></div>
</section>

<section id="sec-memory" class="card">
    <div class="card-header">
        <h2>&#128190; Memoria</h2>
        <span class="card-badge" style="background:${gradeColor(memGrade)}20;color:${gradeColor(memGrade)}">${memGrade}</span>
    </div>
    <p class="card-desc">Total PSS: memoria real usada por el juego. Native Heap: texturas, meshes (C++). Java Heap: logica del juego.</p>
    <div class="stats-row">
        <div class="stat-pill"><span class="stat-pill-label">Inicio</span><span class="stat-pill-value">${memHistory.firstOrNull() ?: "?"}MB</span></div>
        <div class="stat-pill"><span class="stat-pill-label">Final</span><span class="stat-pill-value">${memHistory.lastOrNull() ?: "?"}MB</span></div>
        <div class="stat-pill"><span class="stat-pill-label">Pico</span><span class="stat-pill-value ${cls(peakMem.toInt(), 2000, 1500)}">${peakMem}MB</span></div>
        <div class="stat-pill"><span class="stat-pill-label">Crecimiento</span><span class="stat-pill-value">${if (memHistory.size >= 2) "${memHistory.last() - memHistory.first()}" else "?"}MB</span></div>
    </div>
    <div class="chart-container"><canvas id="memChart"></canvas></div>
</section>

<section id="sec-cpu" class="card">
    <div class="card-header">
        <h2>&#9881; CPU</h2>
        <span class="card-badge" style="background:${gradeColor(cpuGrade)}20;color:${gradeColor(cpuGrade)}">${cpuGrade}</span>
    </div>
    <div class="stats-row">
        <div class="stat-pill"><span class="stat-pill-label">Promedio</span><span class="stat-pill-value ${cls(avgCpu, 85, 70)}">${avgCpu}%</span></div>
        <div class="stat-pill"><span class="stat-pill-label">Maximo</span><span class="stat-pill-value">${maxCpu}%</span></div>
    </div>
    <div class="chart-container"><canvas id="cpuChart"></canvas></div>
</section>

<section id="sec-temp" class="card">
    <div class="card-header">
        <h2>&#127777; Temperatura</h2>
        <span class="card-badge" style="background:${gradeColor(tempGrade)}20;color:${gradeColor(tempGrade)}">${tempGrade}</span>
    </div>
    ${thermalDiagnosticBanner(thermalAvailable, thermalDiagnostic)}
    <p class="card-desc">${
        // v4.3.6: copy depends on whether we have skin or only die. Skin throttle
        // ~42°C; die throttle ~95°C. Mixing them was the v4.3.5 UX bug that made
        // a 93°C die reading look catastrophic.
        if (showSkinAsPrimary) {
            "Temperatura piel (case): por encima de ~42&deg;C se activa el thermal throttling que reduce CPU/GPU. La temperatura del die de CPU rutinariamente alcanza 80-95&deg;C bajo carga y NO indica un problema salvo que supere los 95&deg;C."
        } else {
            "Temperatura del die de CPU. El silicio rutinariamente alcanza 80-95&deg;C bajo carga sostenida y carga USB simultanea; solo se considera throttling severo por encima de los 95&deg;C. Si tu dispositivo no expone un sensor de piel, este es el unico valor disponible."
        }
    }</p>
    <div class="stats-row">
        ${
            if (showSkinAsPrimary) {
                """<div class="stat-pill"><span class="stat-pill-label">Piel Max</span><span class="stat-pill-value ${cls(maxTempSkin.toInt(), 45, 40)}">${maxTempSkin.toInt()}&deg;C</span></div>"""
            } else ""
        }
        <div class="stat-pill"><span class="stat-pill-label">CPU die Max</span><span class="stat-pill-value ${cls(maxTempCpu.toInt(), 95, 85)}">${if (maxTempCpu > 0) "${maxTempCpu.toInt()}\u00B0C" else "N/A"}</span></div>
        <div class="stat-pill"><span class="stat-pill-label">GPU Max</span><span class="stat-pill-value">${if (maxTempGpu > 0) "${maxTempGpu.toInt()}\u00B0C" else "N/A"}</span></div>
    </div>
    <div class="chart-container"><canvas id="tempChart"></canvas></div>
</section>

$fpowerSectionHtml

<section class="card">
    <div class="card-header"><h2>&#128267; Bateria</h2></div>
    <div class="stats-row">
        <div class="stat-pill"><span class="stat-pill-label">Inicio</span><span class="stat-pill-value">${batteryStart}%</span></div>
        <div class="stat-pill"><span class="stat-pill-label">Final</span><span class="stat-pill-value">${batteryEnd}%</span></div>
        <div class="stat-pill"><span class="stat-pill-label">Consumo</span><span class="stat-pill-value ${cls(batteryDrain, 10, 5)}">${batteryDrain}%</span></div>
        <div class="stat-pill"><span class="stat-pill-label">Consumo/min</span><span class="stat-pill-value">${if (duration > 0) fmtUS("%.2f", batteryDrain.toDouble() / (duration / 60.0)) else "0"}%</span></div>
    </div>
    ${if (!isWifi) """<p class="hint">&#9888; Medido con USB conectado. Para consumo real de bateria, usa modo WiFi.</p>""" else """<p class="hint good">&#10003; Medido via WiFi — consumo real de bateria sin carga USB.</p>"""}
</section>

<section id="sec-problems" class="card ${if (problems.isNotEmpty()) "card-problems" else ""}">
    <div class="card-header">
        <h2>${if (problems.isEmpty()) "&#9989;" else "&#9888;"} Problemas Detectados</h2>
        ${if (problems.isNotEmpty()) """<span class="card-badge badge-red">${problems.size}</span>""" else ""}
    </div>
    $problemsHtml
</section>

$eventsHtml

<section id="sec-stats" class="card">
    <div class="card-header"><h2>&#128200; Estadisticas Detalladas</h2></div>
    <div class="expandable open">
        <button class="expand-btn" onclick="this.parentElement.classList.toggle('open')">
            <span class="expand-icon">&#9654;</span> Percentiles FPS
        </button>
        <div class="expand-content">
            <table class="data-table">
                <thead><tr><th>P1</th><th>P5</th><th>P50</th><th>P90</th><th>P99</th><th>Min</th><th>Max</th><th>Promedio</th></tr></thead>
                <tbody><tr>
                    <td class="${cls(p1, 20, 30, "r")}">${p1}</td><td class="${cls(p5, 25, 35, "r")}">${p5}</td>
                    <td>${p50}</td><td>${p90}</td><td>${p99}</td>
                    <td class="bad">${minFps}</td><td class="good">${maxFps}</td>
                    <td style="color:$gc;font-weight:800;font-size:1.1em">${avgFps}</td>
                </tr></tbody>
            </table>
        </div>
    </div>
    <div class="expandable">
        <button class="expand-btn" onclick="this.parentElement.classList.toggle('open')">
            <span class="expand-icon">&#9654;</span> Desglose de Puntuacion
        </button>
        <div class="expand-content">
            <p class="card-desc">Puntuacion base: 100 puntos. Se restan penalizaciones por problemas de rendimiento.</p>
            <div class="grade-bar-wrap">
                <div class="grade-bar"><div class="grade-fill" style="width:${score.coerceIn(0, 100)}%;background:linear-gradient(90deg,$gc,$gcBg)"></div></div>
                <div class="grade-labels"><span>0</span><span>F</span><span>D</span><span>C</span><span>B</span><span>A</span><span>100</span></div>
            </div>
            <div class="final-score">
                <span class="final-score-num" style="color:$gc">${score}</span><span class="final-score-sep">/</span>
                <span class="final-score-den">100</span><span class="final-score-eq">=</span>
                <span class="final-score-grade" style="color:$gc">${grade}</span>
            </div>
        </div>
    </div>
    <div class="expandable">
        <button class="expand-btn" onclick="this.parentElement.classList.toggle('open')">
            <span class="expand-icon">&#9654;</span> Metodologia
        </button>
        <div class="expand-content">
            <p class="card-desc">Como se recopilan y calculan las metricas de este informe.</p>
            <div class="method-grid">
                <div class="method-item"><span class="method-label">FPS</span><span class="method-value">SurfaceFlinger --latency, ventana 1s, filtro IQR</span></div>
                <div class="method-item"><span class="method-label">Frame Times</span><span class="method-value">Delta entre timestamps de presentacion</span></div>
                <div class="method-item"><span class="method-label">Memoria</span><span class="method-value">dumpsys meminfo (PSS + Native + Java)</span></div>
                <div class="method-item"><span class="method-label">CPU</span><span class="method-value">/proc/stat delta entre muestras</span></div>
                <div class="method-item"><span class="method-label">Temperatura</span><span class="method-value">dumpsys thermalservice + thermal zones</span></div>
                <div class="method-item"><span class="method-label">Bateria</span><span class="method-value">dumpsys battery${if (isWifi) " (WiFi, sin carga)" else " (USB charging disabled)"}</span></div>
                <div class="method-item"><span class="method-label">Nota</span><span class="method-value">Base 100 - penalizaciones (FPS/P1/problemas)</span></div>
            </div>
        </div>
    </div>
</section>

<section id="sec-device" class="card">
    <div class="card-header"><h2>&#128241; Hardware del Dispositivo</h2></div>
    <div class="hw-grid">
        <div class="hw-item"><span class="hw-label">Modelo</span><span class="hw-value">${esc(info?.model ?: "?")}</span></div>
        <div class="hw-item"><span class="hw-label">Fabricante</span><span class="hw-value">${esc(info?.manufacturer ?: "?")}</span></div>
        <div class="hw-item"><span class="hw-label">CPU</span><span class="hw-value">${esc(info?.cpu ?: "?")}</span></div>
        <div class="hw-item"><span class="hw-label">GPU</span><span class="hw-value">${esc((info?.gpu ?: "?").take(60))}</span></div>
        <div class="hw-item"><span class="hw-label">RAM</span><span class="hw-value">${info?.ram ?: "?"}</span></div>
        <div class="hw-item"><span class="hw-label">Cores</span><span class="hw-value">${info?.cores ?: "?"}</span></div>
        <div class="hw-item"><span class="hw-label">Plataforma</span><span class="hw-value">${info?.platform?.name ?: "?"}</span></div>
        <div class="hw-item"><span class="hw-label">${if (info?.platform == com.gameperf.desktop.core.model.DevicePlatform.IOS) "iOS" else "SDK"}</span><span class="hw-value">${info?.osVersion ?: "?"}</span></div>
        <div class="hw-item"><span class="hw-label">Resolucion</span><span class="hw-value">${esc(info?.resolution ?: "?")}</span></div>
        ${if (deviceTier.isNotEmpty()) """<div class="hw-item"><span class="hw-label">GPU Tier</span><span class="hw-value hw-tier">${esc(deviceTier)}</span></div>""" else ""}
        ${if (deviceScore > 0) """<div class="hw-item"><span class="hw-label">Hardware Score</span><span class="hw-value">${deviceScore}/100</span></div>""" else ""}
    </div>
</section>

${if (info?.platform == com.gameperf.desktop.core.model.DevicePlatform.IOS) """
<section class="card" style="border-left: 3px solid #007AFF; margin-top: 16px;">
    <div class="card-header"><h2>&#9432; Notas sobre iOS</h2></div>
    <div style="padding: 12px 16px; color: #94a3b8; font-size: 12px;">
        <p>&#8226; La temperatura de skin no esta disponible en iOS.</p>
        <p>&#8226; La memoria muestra el total (physFootprint). No se puede separar nativa/Java en iOS.</p>
        <p>&#8226; GPU% es estimado desde el frame timing, no una lectura directa del hardware.</p>
        <p>&#8226; El video se captura mediante screenshots (15fps Mac / 8fps Windows), no grabacion nativa.</p>
    </div>
</section>
""" else ""}
<footer class="report-footer">
    <div class="footer-logo">Game Performance Tool</div>
    <p>v${AppVersion.NAME} — Informe generado el ${SimpleDateFormat("dd/MM/yyyy 'a las' HH:mm:ss").format(Date())}</p>
    <p class="footer-session">Session: $sessionId</p>
</footer>

</div>

<script id="sessionData" type="application/json">$jsonData</script>

<script>
// Detect print mode: PdfExporter passes ?print=1 in the file:// URL when generating a PDF.
// When in print mode, we use a high-contrast palette so charts are legible on white paper.
// This MUST run before any chart is instantiated, because Chart.js bakes the colors into the
// canvas at construction time and CSS @media print cannot reach inside a <canvas>.
var IS_PRINT = (function(){
  try { return window.location.search.indexOf('print=1') >= 0; } catch(e) { return false; }
})();

Chart.defaults.font.family='-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Helvetica Neue",sans-serif';
Chart.defaults.color = IS_PRINT ? '#1e293b' : '#94a3b8';

// Chart base options. The two halves (interactive vs print) share the same shape so the
// `{...B, ...}` spread further down works for both modes.
var B = IS_PRINT ? {
  responsive: true,
  maintainAspectRatio: false,
  animation: false,                                  // PDFs do not need animation; faster + deterministic capture
  interaction: { mode: 'index', intersect: false },
  plugins: {
    legend: { labels: { color: '#1e293b', font: { size: 12, weight: '600' }, usePointStyle: true, pointStyle: 'circle', padding: 16 } },
    tooltip: { enabled: false }                       // tooltips never appear in PDF anyway
  },
  scales: {
    x: { ticks: { color: '#334155', maxTicksLimit: 12, font: { size: 11, weight: '500' } }, grid: { color: 'rgba(15,23,42,0.12)' }, border: { color: '#94a3b8' } },
    y: { ticks: { color: '#334155', font: { size: 11, weight: '500' } }, grid: { color: 'rgba(15,23,42,0.10)' }, border: { color: '#94a3b8' } }
  }
} : {
  responsive: true,
  maintainAspectRatio: false,
  interaction: { mode: 'index', intersect: false },
  plugins: {
    legend: { labels: { color: '#94a3b8', font: { size: 11 }, usePointStyle: true, pointStyle: 'circle', padding: 16 } },
    tooltip: { mode: 'index', intersect: false, backgroundColor: 'rgba(15,23,42,0.95)', titleColor: '#e2e8f0', bodyColor: '#cbd5e1', borderColor: 'rgba(148,163,184,0.2)', borderWidth: 1, padding: 12, cornerRadius: 8, titleFont: { weight: '600' } }
  },
  scales: {
    x: { ticks: { color: '#64748b', maxTicksLimit: 20, font: { size: 10 } }, grid: { color: 'rgba(148,163,184,0.06)' } },
    y: { ticks: { color: '#94a3b8', font: { size: 11 } }, grid: { color: 'rgba(148,163,184,0.08)' } }
  }
};

var ZP = IS_PRINT
  ? { zoom: { wheel: { enabled: false }, pinch: { enabled: false } }, pan: { enabled: false } }
  : { zoom: { wheel: { enabled: true }, pinch: { enabled: true }, mode: 'x' }, pan: { enabled: true, mode: 'x' } };

// Print-mode color overrides for line/area charts.
// We need stronger borders and darker backgrounds because Chrome print rendering tends to
// flatten alpha channels and gradients become washed out on white paper.
var COLORS_PRINT = { primary: '#0369a1', accent: '#9a3412', good: '#15803d', warn: '#b45309', bad: '#b91c1c' };
var COLORS_DARK  = { primary: '#38bdf8', accent: '#f97316', good: '#10b981', warn: '#f59e0b', bad: '#ef4444' };
var C = IS_PRINT ? COLORS_PRINT : COLORS_DARK;
""")
            // FPS Chart — uses C.primary/bad/warn so colors swap automatically in print mode
            if (fpsD.isNotEmpty()) append("""
(function(){
  var c=document.getElementById('fpsChart').getContext('2d');
  var g;
  if (IS_PRINT) {
    // Solid light fill in print mode — gradients with alpha get washed out
    g = 'rgba(3,105,161,0.10)';
  } else {
    g = c.createLinearGradient(0,0,0,300);
    g.addColorStop(0,'rgba(16,185,129,0.25)');
    g.addColorStop(0.5,'rgba(245,158,11,0.10)');
    g.addColorStop(1,'rgba(239,68,68,0.05)');
  }
  new Chart(c,{
    type:'line',
    data:{labels:[$fpsL],datasets:[{
      label:'FPS',data:[$fpsD],
      borderColor:C.primary,backgroundColor:g,fill:true,tension:0.3,
      pointRadius:0,pointHoverRadius:5,pointHoverBackgroundColor:C.primary,
      borderWidth: IS_PRINT ? 2 : 2.5,
      segment:{borderColor:function(ctx){var v=ctx.p1.parsed.y;if(v<20)return C.bad;if(v<30)return C.warn;return C.primary}}
    }]},
    options:{...B,scales:{...B.scales,y:{...B.scales.y,min:0,suggestedMax:65}},plugins:{...B.plugins,zoom:ZP,annotation:{annotations:{
      zr:{type:'box',yMin:0,yMax:20,backgroundColor: IS_PRINT ? 'rgba(185,28,28,0.06)' : 'rgba(239,68,68,0.04)',borderWidth:0},
      zy:{type:'box',yMin:20,yMax:30,backgroundColor: IS_PRINT ? 'rgba(180,83,9,0.05)' : 'rgba(245,158,11,0.03)',borderWidth:0},
      l30:{type:'line',yMin:30,yMax:30,borderColor: IS_PRINT ? 'rgba(180,83,9,0.7)' : 'rgba(245,158,11,0.4)',borderWidth:1,borderDash:[6,4],label:{content:'30 FPS',display:true,color: C.warn,font:{size:10,weight:'600'},backgroundColor: IS_PRINT ? '#fff' : 'rgba(15,23,42,0.8)',padding:4}},
      l60:{type:'line',yMin:60,yMax:60,borderColor: IS_PRINT ? 'rgba(21,128,61,0.7)' : 'rgba(16,185,129,0.4)',borderWidth:1,borderDash:[6,4],label:{content:'60 FPS',display:true,color: C.good,font:{size:10,weight:'600'},backgroundColor: IS_PRINT ? '#fff' : 'rgba(15,23,42,0.8)',padding:4}}${if (markerAnnotationsJs.isNotEmpty()) ",$markerAnnotationsJs" else ""}
    }}}}
  });
})();
""")
            // Frame Time Histogram — print uses darker bar colors for contrast on white
            if (ftBuckets.isNotEmpty()) append("""
(function(){
  var barColors = IS_PRINT
    ? ['#15803d','#65a30d','#b45309','#c2410c','#b91c1c','#7f1d1d']
    : ['#10b981','#84cc16','#f59e0b','#f97316','#ef4444','#dc2626'];
  new Chart(document.getElementById('ftChart').getContext('2d'),{
    type:'bar',
    data:{
      labels:['<8ms (>120fps)','8-16ms (60-120fps)','16-33ms (30-60fps)','33-50ms (20-30fps)','50-100ms (<20fps)','>100ms (stutter)'],
      datasets:[{label:'Frames',data:[$ftBuckets],backgroundColor:barColors,borderRadius:6,borderSkipped:false,maxBarThickness: IS_PRINT ? 40 : 60}]
    },
    options:{indexAxis:'y',...B,plugins:{...B.plugins,legend:{display:false}},scales:{x:{...B.scales.x},y:{...B.scales.y,ticks:{...B.scales.y.ticks,font:{size: IS_PRINT ? 10 : 11}}}}}
  });
})();
""")
            // Memory Chart — print uses solid dark borders + light fills
            if (memD.isNotEmpty()) append("""
(function(){
  new Chart(document.getElementById('memChart').getContext('2d'),{
    type:'line',
    data:{labels:[$memL],datasets:[
      {label:'Total PSS (MB)',data:[$memD],borderColor:C.primary,backgroundColor: IS_PRINT ? 'rgba(3,105,161,0.08)' : 'rgba(56,189,248,0.08)',fill:true,tension:0.3,pointRadius:0,borderWidth: IS_PRINT ? 2 : 2.5},
      {label:'Native Heap (MB)',data:[$natD],borderColor:C.accent,tension:0.3,pointRadius:0,borderWidth:1.5},
      {label:'Java Heap (MB)',data:[$javD],borderColor:C.good,tension:0.3,pointRadius:0,borderWidth:1.5}
    ]},
    options:{...B,plugins:{...B.plugins,zoom:ZP}}
  });
})();
""")
            // CPU Chart — same color treatment as FPS
            if (cpuD.isNotEmpty()) append("""
(function(){
  var c=document.getElementById('cpuChart').getContext('2d');
  var g;
  if (IS_PRINT) {
    g = 'rgba(3,105,161,0.08)';
  } else {
    g = c.createLinearGradient(0,0,0,300);
    g.addColorStop(0,'rgba(56,189,248,0.2)');
    g.addColorStop(1,'rgba(56,189,248,0.01)');
  }
  new Chart(c,{
    type:'line',
    data:{labels:[$tL],datasets:[{
      label:'CPU %',data:[$cpuD],
      borderColor:C.primary,backgroundColor:g,fill:true,tension:0.3,pointRadius:0,
      borderWidth: IS_PRINT ? 2 : 2.5,
      segment:{borderColor:function(ctx){var v=ctx.p1.parsed.y;if(v>85)return C.bad;if(v>70)return C.warn;return C.primary}}
    }]},
    options:{...B,scales:{...B.scales,y:{...B.scales.y,min:0,max:100}},plugins:{...B.plugins,annotation:{annotations:{
      w:{type:'line',yMin:85,yMax:85,borderColor: IS_PRINT ? 'rgba(185,28,28,0.6)' : 'rgba(239,68,68,0.3)',borderWidth:1,borderDash:[6,4],label:{content:'85% Saturacion',display:true,color: C.bad,font:{size:9},backgroundColor: IS_PRINT ? '#fff' : 'rgba(15,23,42,0.8)',padding:3}}
    }}}}
  });
})();
""")
            // Temperature Chart — print uses darker red/orange/amber palette
            if (tcD.isNotEmpty()) append("""
(function(){
  new Chart(document.getElementById('tempChart').getContext('2d'),{
    type:'line',
    data:{labels:[$tL],datasets:[
      {label:'CPU',data:[$tcD],borderColor:C.bad,tension:0.3,pointRadius:0,borderWidth: IS_PRINT ? 2 : 2.5},
      {label:'GPU',data:[$tgD],borderColor:C.accent,tension:0.3,pointRadius:0,borderWidth:1.5},
      {label:'Skin',data:[$tsD],borderColor:C.warn,tension:0.3,pointRadius:0,borderWidth:1.5}
    ]},
    options:{...B,plugins:{...B.plugins,annotation:{annotations:{
      t:{type:'line',yMin:42,yMax:42,borderColor: IS_PRINT ? 'rgba(185,28,28,0.6)' : 'rgba(239,68,68,0.4)',borderWidth:1,borderDash:[6,4],label:{content:'Thermal Throttle (~42\u00B0C)',display:true,color: C.bad,font:{size:9},backgroundColor: IS_PRINT ? '#fff' : 'rgba(15,23,42,0.8)',padding:3}}
    }}}}
  });
})();
""")
            append("""
function copyJson(){var d=document.getElementById('sessionData').textContent;navigator.clipboard.writeText(d).then(function(){var b=document.getElementById('jsonBtn');b.innerHTML='&#10003;';b.classList.add('fab-success');setTimeout(function(){b.innerHTML='&#128203;';b.classList.remove('fab-success')},2000)})}
document.querySelectorAll('.nav-link').forEach(function(a){a.addEventListener('click',function(e){e.preventDefault();var t=document.querySelector(this.getAttribute('href'));if(t)t.scrollIntoView({behavior:'smooth',block:'start'})})});
var secs=document.querySelectorAll('section[id]');var nls=document.querySelectorAll('.nav-link');
window.addEventListener('scroll',function(){var cur='';secs.forEach(function(s){if(window.scrollY>=s.offsetTop-120)cur=s.id});nls.forEach(function(l){l.classList.remove('active');if(l.getAttribute('href')==='#'+cur)l.classList.add('active')})});
</script>
</body>
</html>""")
        }

        file.writeText(html)
        return file.absolutePath
    }

    /**
     * Generates an HTML comparison report for multiple sessions.
     * Produces a side-by-side table with color-coded metrics (green=better, red=worse),
     * a Chart.js radar chart, and a per-metric winner summary.
     * Returns the file path to the generated report.
     */
    fun generateComparison(
        entries: List<SessionHistory.HistoryEntry>,
        outputDir: File = File(System.getProperty("java.io.tmpdir"))
    ): String {
        outputDir.mkdirs()
        val date = SimpleDateFormat("yyyy-MM-dd_HHmm").format(Date())
        val file = File(outputDir, "comparativa_$date.html")

        val dateDisplay = SimpleDateFormat("dd/MM/yyyy HH:mm").format(Date())
        val oursEntries = entries.filter { it.tag == SessionHistory.SessionTag.OUR_GAME }
        val compEntries = entries.filter { it.tag == SessionHistory.SessionTag.COMPETITION }

        // Metric definitions: name, extractor, higherBetter, unit, formatter
        data class MetricDef(
            val name: String,
            val extract: (SessionHistory.HistoryEntry) -> Double,
            val higherBetter: Boolean,
            val unit: String,
            val format: (Double) -> String = { v -> if (unit == "ms" || unit == "°C") fmtUS("%.1f", v) else "${v.toInt()}" }
        )

        val metrics = listOf(
            MetricDef("FPS Promedio", { it.avgFps.toDouble() }, true, "", { "${it.toInt()}" }),
            MetricDef("P1 FPS", { it.p1Fps.toDouble() }, true, "", { "${it.toInt()}" }),
            MetricDef("P5 FPS", { it.p5Fps.toDouble() }, true, "", { "${it.toInt()}" }),
            MetricDef("Frame Time Avg", { it.avgFrameTime }, false, "ms", { fmtUS("%.1f", it) }),
            MetricDef("P95 Frame Time", { it.p95FrameTime }, false, "ms", { fmtUS("%.1f", it) }),
            MetricDef("P99 Frame Time", { it.p99FrameTime }, false, "ms", { fmtUS("%.1f", it) }),
            MetricDef("Memoria Pico", { it.peakMemMb.toDouble() }, false, "MB", { "${it.toLong()}" }),
            MetricDef("CPU Promedio", { it.avgCpu.toDouble() }, false, "%", { "${it.toInt()}" }),
            MetricDef("Temp Max", { it.maxTemp }, false, "°C", { fmtUS("%.0f", it) }),
            MetricDef("Puntuacion", { it.score.toDouble() }, true, "/100", { "${it.toInt()}" })
        )

        // Build comparison table rows
        val tableRows = buildString {
            // Info rows (no comparison coloring)
            append(comparisonInfoRow("Juego", entries) { it.gamePackage.substringAfterLast('.') })
            append(comparisonInfoRow("Dispositivo", entries) { it.deviceModel })
            append(comparisonInfoRow("Nota", entries) { "${it.grade}" })
            append(comparisonInfoRow("Duracion", entries) { "${it.duration / 60}m ${it.duration % 60}s" })

            // Metric rows with coloring
            for (m in metrics) {
                val allVals = entries.map { m.extract(it) }
                val best = if (m.higherBetter) allVals.maxOrNull() else allVals.minOrNull()
                val worst = if (m.higherBetter) allVals.minOrNull() else allVals.maxOrNull()
                val allSame = allVals.distinct().size == 1

                append("<tr><td class=\"metric-label\">${esc(m.name)}</td>")
                for (e in entries) {
                    val v = m.extract(e)
                    val cls = if (allSame) "" else if (v == best) "best" else if (v == worst) "worst" else ""
                    append("<td class=\"metric-val $cls\">${m.format(v)}${m.unit}")
                    if (!allSame && v == best) append(" <span class=\"win-icon\">★</span>")
                    append("</td>")
                }
                append("</tr>\n")
            }
        }

        // Radar chart data
        val radarLabels = listOf("FPS", "P1 FPS", "Estabilidad", "Memoria", "CPU", "Temperatura")
        fun normalizeForRadar(entry: SessionHistory.HistoryEntry): List<Int> {
            val fps = (entry.avgFps.coerceIn(0, 65) * 100 / 65)
            val p1fps = (entry.p1Fps.coerceIn(0, 60) * 100 / 60)
            val stability = (100 - entry.avgFrameTime.coerceIn(0.0, 100.0)).toInt()
            val mem = (100 - (entry.peakMemMb.toInt() / 30).coerceIn(0, 100))
            val cpu = 100 - entry.avgCpu.coerceIn(0, 100)
            val temp = if (entry.maxTemp <= 0) 100 else (100 - ((entry.maxTemp - 25).coerceIn(0.0, 30.0) * 100 / 30).toInt())
            return listOf(fps, p1fps, stability, mem, cpu, temp)
        }

        val radarDatasets = buildString {
            for ((i, e) in entries.withIndex()) {
                val isOurs = e.tag == SessionHistory.SessionTag.OUR_GAME
                val color = if (isOurs) "#38bdf8" else "#f97316"
                val bgColor = if (isOurs) "rgba(56,189,248,0.15)" else "rgba(249,115,22,0.15)"
                val label = if (isOurs) "Nuestro juego" else e.competitorName.ifEmpty { "Competencia" }
                val vals = normalizeForRadar(e).joinToString(",")
                if (i > 0) append(",")
                append("""{label:'${escJs(label)}',data:[$vals],borderColor:'$color',backgroundColor:'$bgColor',borderWidth:2.5,pointRadius:4,pointBackgroundColor:'$color',pointBorderColor:'$color'}""")
            }
        }

        // Winner summary
        val summaryHtml = if (oursEntries.isNotEmpty() && compEntries.isNotEmpty()) {
            val ours = oursEntries.first()
            val comp = compEntries.first()
            var weWin = 0; var theyWin = 0; var tied = 0
            val summaryRows = StringBuilder()
            for (m in metrics) {
                val oV = m.extract(ours); val cV = m.extract(comp)
                val we = if (m.higherBetter) oV > cV else oV < cV
                val they = if (m.higherBetter) cV > oV else cV < oV
                if (we) weWin++ else if (they) theyWin++ else tied++
                val icon = if (oV == cV) "&#8860;" else if (we) "&#10003;" else "&#10007;"
                val cls = if (oV == cV) "tie" else if (we) "win" else "lose"
                summaryRows.append("""<div class="summary-row $cls"><span class="summary-icon">$icon</span><span class="summary-metric">${esc(m.name)}</span><span class="summary-result">${if (oV == cV) "Empate" else if (we) "Ganamos" else "Competencia gana"}</span></div>""")
            }
            val competitorLabel = comp.competitorName.ifEmpty { comp.gamePackage.substringAfterLast('.') }
            val overallColor = if (weWin > theyWin) "#10b981" else if (theyWin > weWin) "#ef4444" else "#f59e0b"
            val overallText = if (weWin > theyWin) "Nuestro juego gana" else if (theyWin > weWin) "${esc(competitorLabel)} gana" else "Empate"
            """
            <section class="card">
                <h2>&#127942; Resultado</h2>
                <div class="scoreboard">
                    <div class="score-team"><span class="score-label" style="color:#38bdf8">Nuestro juego</span><span class="score-num" style="color:#38bdf8">$weWin</span></div>
                    <div class="score-vs">VS</div>
                    <div class="score-team"><span class="score-label" style="color:#f97316">${esc(competitorLabel)}</span><span class="score-num" style="color:#f97316">$theyWin</span></div>
                </div>
                <div class="overall" style="border-color:$overallColor;color:$overallColor">$overallText${if (tied > 0) " ($tied empate${if (tied > 1) "s" else ""})" else ""}</div>
                <div class="summary-grid">$summaryRows</div>
            </section>"""
        } else ""

        // Column header classes
        val colHeaders = entries.joinToString("\n") { e ->
            val isOurs = e.tag == SessionHistory.SessionTag.OUR_GAME
            val color = if (isOurs) "#38bdf8" else "#f97316"
            val label = if (isOurs) "NUESTRO" else e.competitorName.ifEmpty { "COMP" }
            """<th class="col-header" style="background:${color}15;color:$color;border-bottom:3px solid $color">$label</th>"""
        }

        val html = """<!DOCTYPE html>
<html lang="es">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1.0">
<title>Comparativa de Rendimiento</title>
<script>${Assets.chartJs}</script>
<style>
*,*::before,*::after{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'Helvetica Neue',sans-serif;background:#0f172a;color:#e2e8f0;line-height:1.6;font-size:14px;-webkit-font-smoothing:antialiased}
.container{max-width:960px;margin:0 auto;padding:24px 20px 40px}
.report-header{text-align:center;padding:40px 32px 32px;margin-bottom:32px;background:linear-gradient(135deg,#1e293b,#0f172a 50%,#1e1b4b);border-radius:0 0 24px 24px;position:relative}
.report-header::after{content:'';position:absolute;inset:0;background:radial-gradient(ellipse at 50% 0%,rgba(56,189,248,0.08),transparent 60%);border-radius:0 0 24px 24px}
.report-header *{position:relative;z-index:1}
.header-badge{display:inline-block;background:rgba(56,189,248,0.1);border:1px solid rgba(56,189,248,0.2);color:#38bdf8;font-size:11px;font-weight:700;letter-spacing:1.5px;text-transform:uppercase;padding:6px 16px;border-radius:100px;margin-bottom:12px}
.header-title{font-size:2rem;font-weight:800;background:linear-gradient(135deg,#e2e8f0,#94a3b8);-webkit-background-clip:text;-webkit-text-fill-color:transparent;background-clip:text;margin-bottom:6px}
.header-sub{color:#64748b;font-size:13px}
.card{background:linear-gradient(135deg,rgba(30,41,59,0.5),rgba(15,23,42,0.5));border:1px solid rgba(148,163,184,0.08);border-radius:16px;padding:24px;margin-bottom:20px}
.card h2{color:#e2e8f0;font-size:1.1rem;font-weight:700;margin-bottom:16px}
table{width:100%;border-collapse:collapse}
th,td{padding:10px 14px;text-align:center;font-size:13px}
th{background:#1e293b;color:#94a3b8;font-size:11px;font-weight:700;letter-spacing:0.5px;text-transform:uppercase;position:sticky;top:0}
td{border-bottom:1px solid rgba(148,163,184,0.04);color:#cbd5e1}
.metric-label{text-align:left;color:#94a3b8;font-weight:600;font-size:12px}
.metric-val{font-weight:700;color:#e2e8f0}
.metric-val.best{color:#10b981;background:rgba(16,185,129,0.08)}
.metric-val.worst{color:#ef4444;background:rgba(239,68,68,0.06)}
.win-icon{color:#fbbf24;font-size:11px}
.col-header{font-weight:800;font-size:12px;letter-spacing:0.5px}
.chart-container{height:360px;background:rgba(0,0,0,0.2);border:1px solid rgba(148,163,184,0.04);border-radius:12px;padding:20px;margin-top:12px}
.scoreboard{display:flex;justify-content:center;align-items:center;gap:32px;margin-bottom:16px}
.score-team{text-align:center}
.score-label{display:block;font-size:12px;font-weight:700;text-transform:uppercase;letter-spacing:0.5px;margin-bottom:4px}
.score-num{font-size:3rem;font-weight:900;line-height:1}
.score-vs{color:#475569;font-size:1.2rem;font-weight:800}
.overall{text-align:center;font-size:1rem;font-weight:800;padding:12px;border:2px solid;border-radius:12px;margin-bottom:20px}
.summary-grid{display:flex;flex-direction:column;gap:6px}
.summary-row{display:flex;align-items:center;gap:10px;padding:10px 16px;border-radius:10px;font-size:13px}
.summary-row.win{background:rgba(16,185,129,0.06);border:1px solid rgba(16,185,129,0.12)}
.summary-row.lose{background:rgba(239,68,68,0.06);border:1px solid rgba(239,68,68,0.12)}
.summary-row.tie{background:rgba(245,158,11,0.06);border:1px solid rgba(245,158,11,0.12)}
.summary-icon{font-size:16px;flex-shrink:0}
.summary-row.win .summary-icon{color:#10b981}
.summary-row.lose .summary-icon{color:#ef4444}
.summary-row.tie .summary-icon{color:#f59e0b}
.summary-metric{flex:1;color:#e2e8f0;font-weight:600}
.summary-result{font-weight:700;font-size:12px}
.summary-row.win .summary-result{color:#10b981}
.summary-row.lose .summary-result{color:#ef4444}
.summary-row.tie .summary-result{color:#f59e0b}
.footer{text-align:center;padding:24px 0 8px;margin-top:32px;border-top:1px solid rgba(148,163,184,0.06);color:#475569;font-size:11px}
.footer-logo{font-size:13px;font-weight:800;letter-spacing:1px;text-transform:uppercase;background:linear-gradient(135deg,#38bdf8,#818cf8);-webkit-background-clip:text;-webkit-text-fill-color:transparent;background-clip:text;margin-bottom:6px}
@media print{body{background:#fff!important;color:#1e293b!important;-webkit-print-color-adjust:exact;print-color-adjust:exact}.card{background:#fafafa!important;border:1px solid #e2e8f0!important}.metric-val.best{color:#059669!important}.metric-val.worst{color:#dc2626!important}th{background:#f1f5f9!important;color:#475569!important}td{color:#1e293b!important}}
</style>
</head>
<body>

<div class="report-header">
    <div class="header-badge">Game Performance Tool</div>
    <h1 class="header-title">Comparativa de Rendimiento</h1>
    <p class="header-sub">&#128197; $dateDisplay &nbsp;|&nbsp; ${entries.size} sesiones comparadas</p>
</div>

<div class="container">

<section class="card">
    <h2>&#128202; Tabla Comparativa</h2>
    <div style="overflow-x:auto;border-radius:10px">
    <table>
        <thead>
            <tr><th class="metric-label" style="text-align:left">Metrica</th>$colHeaders</tr>
        </thead>
        <tbody>
$tableRows
        </tbody>
    </table>
    </div>
</section>

<section class="card">
    <h2>&#128205; Radar de Rendimiento</h2>
    <p style="color:#64748b;font-size:12px;margin-bottom:8px">Valores normalizados 0-100. Mayor area = mejor rendimiento global.</p>
    <div class="chart-container"><canvas id="radarChart"></canvas></div>
</section>

$summaryHtml

<footer class="footer">
    <div class="footer-logo">Game Performance Tool</div>
    <p>v${AppVersion.NAME} — Comparativa generada el ${SimpleDateFormat("dd/MM/yyyy 'a las' HH:mm:ss").format(Date())}</p>
</footer>

</div>

<script>
Chart.defaults.font.family='-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif';
Chart.defaults.color='#94a3b8';
new Chart(document.getElementById('radarChart').getContext('2d'),{
    type:'radar',
    data:{
        labels:[${radarLabels.joinToString(",") { "'$it'" }}],
        datasets:[$radarDatasets]
    },
    options:{
        responsive:true,
        maintainAspectRatio:false,
        plugins:{
            legend:{labels:{color:'#94a3b8',font:{size:12},usePointStyle:true,pointStyle:'circle',padding:16}},
            tooltip:{backgroundColor:'rgba(15,23,42,0.95)',titleColor:'#e2e8f0',bodyColor:'#cbd5e1',borderColor:'rgba(148,163,184,0.2)',borderWidth:1,padding:12,cornerRadius:8}
        },
        scales:{
            r:{
                min:0,max:100,
                ticks:{stepSize:20,color:'#475569',backdropColor:'transparent',font:{size:10}},
                grid:{color:'rgba(148,163,184,0.1)'},
                angleLines:{color:'rgba(148,163,184,0.08)'},
                pointLabels:{color:'#94a3b8',font:{size:12,weight:'600'}}
            }
        }
    }
});
</script>
</body>
</html>"""

        file.writeText(html)
        return file.absolutePath
    }

    /** Helper: builds an info-only row (no color comparison) for the comparison table. */
    private fun comparisonInfoRow(
        label: String,
        entries: List<SessionHistory.HistoryEntry>,
        extractor: (SessionHistory.HistoryEntry) -> String
    ): String {
        val cells = entries.joinToString("") { """<td class="metric-val">${esc(extractor(it))}</td>""" }
        return """<tr><td class="metric-label">${esc(label)}</td>$cells</tr>"""  + "\n"
    }

    // ══════════ HELPERS ══════════

    private fun gradeColor(g: Char) = when (g) {
        'A' -> "#10b981"; 'B' -> "#84cc16"; 'C' -> "#f59e0b"; 'D' -> "#f97316"; else -> "#ef4444"
    }

    private fun gradeColorBg(g: Char) = when (g) {
        'A' -> "#059669"; 'B' -> "#65a30d"; 'C' -> "#d97706"; 'D' -> "#ea580c"; else -> "#dc2626"
    }

    private fun markerColorHex(type: MarkerType) = when (type) {
        MarkerType.INTERSTITIAL -> "#f97316"
        MarkerType.VIDEO_REWARD -> "#a855f7"
        MarkerType.LOADING -> "#f59e0b"
        MarkerType.SCENE_CHANGE -> "#38bdf8"
        MarkerType.CUSTOM -> "#10b981"
    }

    private fun fpsClass(fps: Int) = when {
        fps >= 50 -> "good"; fps >= 30 -> "warn"; else -> "bad"
    }

    private fun metricGrade(normalized: Int, a: Int, b: Int, c: Int, d: Int) = when {
        normalized >= a -> 'A'; normalized >= b -> 'B'; normalized >= c -> 'C'; normalized >= d -> 'D'; else -> 'F'
    }

    private fun cls(v: Int, bad: Int, warn: Int, dir: String = "n") =
        if (dir == "r") { if (v < bad) "bad" else if (v < warn) "warn" else "good" }
        else { if (v > bad) "bad" else if (v > warn) "warn" else "good" }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    // M-6: escJs must also escape `/` as `\/` to prevent </script> breakout when values
    // are embedded inside <script> blocks. Also escape \r, \t, and backtick for robustness.
    private fun escJs(s: String) = s
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\"", "\\\"")
        .replace("/", "\\/")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
        .replace("`", "\\`")

    private fun metricCard(title: String, value: String, icon: String, grade: Char, gc: String, detail: String): String {
        val emoji = when (icon) {
            "fps" -> "&#127918;"; "frametime" -> "&#9201;"; "memory" -> "&#128190;"
            "cpu" -> "&#9881;"; "temp" -> "&#127777;"; "battery" -> "&#128267;"; else -> "&#128202;"
        }
        return """
        <div class="metric-card">
            <div class="metric-card-top"><span class="metric-icon">$emoji</span><span class="metric-grade" style="color:$gc">$grade</span></div>
            <div class="metric-value" style="color:$gc">$value</div>
            <div class="metric-title">$title</div>
            <div class="metric-detail">$detail</div>
        </div>"""
    }

    // ══════════ v4.4.0 — Auto detection / dual-view / conclusions helpers ══════════

    /**
     * Renders an optional "raw" subline on a metric card when the filtered (game-only)
     * value differs from the raw (full-session) value by more than 5%. The subline is
     * intentionally smaller and dimmer so the filtered figure remains the headline.
     *
     * Returns an empty string when no contrast is meaningful (raw absent, equal values,
     * or delta below the 5% threshold) — the card then renders identically to legacy.
     */
    private fun rawSubline(filtered: Double, raw: Double?, unit: String, prefix: String = "Bruto"): String {
        if (raw == null) return ""
        if (filtered == 0.0) return ""
        val delta = kotlin.math.abs(raw - filtered) / filtered
        if (delta <= 0.05) return ""
        val rawStr = if (unit == "ms" || unit == "\u00B0C") fmtUS("%.1f", raw) else "${raw.toInt()}"
        return """<div class="metric-raw">$prefix: $rawStr$unit</div>"""
    }

    private fun sectionConclusions(conclusions: List<Conclusion>): String {
        if (conclusions.isEmpty()) return ""
        val isInsufficientData = conclusions.size == 1 && conclusions[0].ruleId == "insufficient-data"
        val cards = conclusions.joinToString("\n") { c ->
            val severityClass = c.severity.name.lowercase()
            val (icon, label) = when (c.severity) {
                Severity.CRITICAL -> "&#9888;" to "Crítico"
                Severity.WARNING -> "&#9888;" to "Atención"
                Severity.INFO -> "&#8505;" to "Información"
            }
            val recHtml = c.recommendation
                ?.takeIf { it.isNotBlank() }
                ?.let { """<p class="conclusion-rec">${esc(it)}</p>""" } ?: ""
            val idChip = if (c.ruleId == "insufficient-data") "" else
                """<span class="conclusion-id">${esc(c.ruleId)}</span>"""
            """
            <div class="conclusion-card conclusion-$severityClass">
                <div class="conclusion-header">
                    <span class="conclusion-icon">$icon</span>
                    <span class="conclusion-severity">$label</span>
                    $idChip
                </div>
                <p class="conclusion-headline">${esc(c.headline)}</p>
                $recHtml
            </div>
            """.trimIndent()
        }
        val intro = if (isInsufficientData) {
            "Esta sesión es demasiado corta para extraer conclusiones fiables. Captura una sesión más larga para obtener un análisis completo."
        } else {
            "Análisis automático del rendimiento. Interpreta estas recomendaciones como hipótesis para investigar, no como diagnóstico definitivo."
        }
        return """
<section id="sec-conclusions" class="card">
    <div class="card-header"><h2>&#128270; Conclusiones</h2></div>
    <p class="card-desc">$intro</p>
    <div class="conclusions-list">
$cards
    </div>
</section>"""
    }

    /**
     * Empty-state for `#sec-conclusions` (REP-001 + CON-007). Rendered only when
     * the catalog produced ZERO conclusions AND we have enough samples (i.e. the
     * insufficient-data short-circuit didn't fire). The orchestrator hands us an
     * empty list in this case so we surface a neutral "no issues" card instead of
     * silently hiding the section — users that scrolled to find conclusions
     * deserve confirmation that the analysis ran.
     */
    @Suppress("FunctionOnlyReturningConstant")
    private fun sectionConclusionsEmpty(): String =
        """
<section id="sec-conclusions" class="card">
    <div class="card-header"><h2>&#128270; Conclusiones</h2></div>
    <p class="card-desc">Análisis automático del rendimiento.</p>
    <div class="status-box status-ok"><span class="status-icon">&#10003;</span> No se detectaron problemas heurísticos significativos en esta sesión.</div>
</section>"""

    /**
     * Unified events + manual markers table (REP-005 + MAN-002 + MAN-003).
     * Renders both kinds of timeline events in one chronological table, with
     * the source column distinguishing manual entries from auto-detected ones.
     * When BOTH lists are empty the section is omitted entirely.
     *
     * captureStartMs is the wall-clock start of the session — auto events carry
     * absolute timestamps (System.currentTimeMillis at detection), so we subtract
     * captureStartMs to align them with the manual markers' relative seconds.
     */
    private fun sectionEvents(
        markers: List<SessionMarker>,
        events: List<DetectedEvent>,
        captureStartMs: Long,
    ): String {
        if (markers.isEmpty() && events.isEmpty()) return ""

        data class Row(
            val tsMs: Long,
            val durationMs: Long?,
            val type: String,
            val typeColor: String,
            val source: String,
            val sourceClass: String,
            val detail: String,
        )

        val rows = mutableListOf<Row>()
        for (m in markers) {
            rows.add(
                Row(
                    tsMs = m.timestampMs,
                    durationMs = null,
                    type = m.type.label,
                    typeColor = m.colorHex.ifEmpty { markerColorHex(m.type) },
                    source = "Manual",
                    sourceClass = "source-manual",
                    detail = m.note.ifBlank { m.title.ifBlank { "\u2014" } },
                )
            )
        }
        for (e in events) {
            val durationMs = e.endMs?.let { it - e.startMs }
            val typeLabel = when (e.type) {
                EventType.INTERSTITIAL -> "Intersticial"
                EventType.REWARDED_VIDEO -> "Vídeo recompensado"
                EventType.IAP -> "Compra (IAP)"
                EventType.LOADING -> "Carga"
                EventType.FOREGROUND_LOSS -> "Pérdida de foreground"
                EventType.UNKNOWN -> "Desconocido"
            }
            val typeColor = when (e.type) {
                EventType.INTERSTITIAL, EventType.REWARDED_VIDEO -> "#f97316"
                EventType.IAP -> "#38bdf8"
                EventType.LOADING -> "#f59e0b"
                EventType.FOREGROUND_LOSS -> "#a855f7"
                EventType.UNKNOWN -> "#94a3b8"
            }
            val confidenceTag = when (e.confidence) {
                Confidence.HIGH -> "alta"
                Confidence.MEDIUM -> "media"
                Confidence.LOW -> "baja"
            }
            val inferredTag = if (e.endInferred) " (cierre inferido)" else ""
            // Auto events use absolute wall-clock ms; convert to capture-relative.
            val relativeMs = (e.startMs - captureStartMs).coerceAtLeast(0L)
            rows.add(
                Row(
                    tsMs = relativeMs,
                    durationMs = durationMs,
                    type = typeLabel,
                    typeColor = typeColor,
                    source = "Auto: ${e.sdkSource}",
                    sourceClass = "source-auto",
                    detail = "Confianza $confidenceTag$inferredTag",
                )
            )
        }
        rows.sortBy { it.tsMs }

        val tbody = rows.joinToString("\n            ") { row ->
            val ts = formatTimestamp(row.tsMs)
            val dur = row.durationMs?.let { formatDuration(it) } ?: "\u2014"
            val typeBadge = """<span class="marker-badge" style="background:${row.typeColor}20;color:${row.typeColor};border:1px solid ${row.typeColor}40">${esc(row.type)}</span>"""
            """<tr><td class="mono">$ts</td><td class="mono">${esc(dur)}</td><td>$typeBadge</td><td class="${row.sourceClass}">${esc(row.source)}</td><td>${esc(row.detail)}</td></tr>"""
        }

        // Type legend: distinct types present in the table
        val typesPresent = rows.map { it.type to it.typeColor }.distinct()
        val legend = typesPresent.joinToString("") { (label, color) ->
            """<span class="marker-badge" style="background:${color}20;color:$color;border:1px solid ${color}40">${esc(label)}</span>"""
        }

        return """
<section id="sec-events" class="card">
    <div class="card-header"><h2>&#128205; Eventos detectados</h2></div>
    <p class="card-desc">Marcadores manuales y eventos detectados automáticamente combinados cronológicamente. Las métricas filtradas excluyen estos rangos para reflejar el rendimiento real del juego.</p>
    <div style="display:flex;gap:8px;flex-wrap:wrap;margin-bottom:16px">
        $legend
    </div>
    <div class="table-wrap">
    <table class="data-table events-table">
        <thead><tr><th>Tiempo</th><th>Duración</th><th>Tipo</th><th>Origen</th><th>Detalle</th></tr></thead>
        <tbody>
            $tbody
        </tbody>
    </table>
    </div>
</section>"""
    }

    private fun formatTimestamp(ms: Long): String {
        val totalSec = ms / 1000
        val mm = totalSec / 60
        val ss = totalSec % 60
        return "%d:%02d".format(mm, ss)
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000.0
        return when {
            totalSec < 1.0 -> "${ms} ms"
            totalSec < 60 -> fmtUS("%.1f s", totalSec)
            else -> fmtUS("%.1f min", totalSec / 60.0)
        }
    }

    /**
     * Detection-mode banner (REP-005 + IOS-001). Renders a compact strip near the
     * top of the report disclosing what level of automatic detection produced the
     * events list. Also folds detector-quality warnings into a `<details>` so the
     * user can expand them without cluttering the header.
     */
    private fun detectionModeBanner(
        mode: DetectionMode,
        eventCount: Int,
        warnings: List<String>,
    ): String {
        val (icon, label, color) = when (mode) {
            DetectionMode.ANDROID_FULL -> Triple("&#128994;", "Detección automática Android (completa)", "#10b981")
            DetectionMode.IOS_PARTIAL -> Triple("&#128993;", "Detección automática iOS (parcial — sin Modo de Desarrollador)", "#f59e0b")
            DetectionMode.MANUAL_ONLY -> Triple("&#9898;", "Marcadores manuales únicamente (auto-detección desactivada o no disponible)", "#94a3b8")
        }
        val warningsHtml = if (warnings.isEmpty()) "" else {
            val items = warnings.joinToString("") { "<li>${esc(it)}</li>" }
            """<details class="detection-warnings"><summary>${warnings.size} aviso(s) de detección</summary><ul>$items</ul></details>"""
        }
        return """
<div class="detection-banner" style="border-left:4px solid $color">
    <span class="detection-banner-icon">$icon</span>
    <span class="detection-banner-label">$label</span>
    <span class="detection-banner-count">$eventCount evento(s)</span>
    $warningsHtml
</div>"""
    }

    /**
     * Excessive-filter callout. Surfaces the FLT-005 fallback when more than 70%
     * of the session was excluded by event windows. The orchestrator detects this
     * either via a detectorWarnings string match OR by recomputing the kept-ratio
     * from the dual aggregates, depending on which signal is available.
     */
    private fun excessiveFilterCallout(triggered: Boolean): String {
        if (!triggered) return ""
        return """
<div class="callout callout-warning">
    <strong>&#9888; Aviso:</strong> Más del 70% de esta sesión fue excluido por eventos detectados (anuncios, IAP o cargas).
    Las métricas filtradas pueden no ser representativas, así que las cifras mostradas son las brutas (toda la sesión) como salvaguarda.
    Considera capturar una sesión más larga sin tantas interrupciones para un análisis más fiable.
</div>"""
    }

    /**
     * v4.4.1 -- Diagnostic banner emitted at the top of the temperature section
     * when the thermal pipeline reported unavailable (`!thermalAvailable`) AND
     * the parser populated a [ThermalDiagnostic] payload. The banner explains
     * (in Spanish tuteo-formal) WHY the pipeline failed and lists the raw
     * vendor zone names so users / devs can file a vendor-catalog bug.
     *
     * Returns an empty string on the happy path (thermalAvailable=true) and
     * when the caller did not pass a diagnostic — keeps the legacy temp
     * section markup identical for v4.3.x re-renders.
     *
     * Reason copy is intentionally short (one sentence) so the banner stays
     * visually balanced against the rest of the section. Zone names are
     * `take(10)` capped defensively even though [com.gameperf.desktop.core.AdbThermalParser]
     * already truncates the list at the source — `escapeHtml`-style escaping
     * via [esc] protects against the (theoretical) vendor that puts HTML
     * special chars in a sysfs node name.
     */
    private fun thermalDiagnosticBanner(
        thermalAvailable: Boolean,
        diagnostic: ThermalDiagnostic?,
    ): String {
        if (thermalAvailable) return ""
        if (diagnostic == null) return ""
        val reasonText = when (diagnostic.reason) {
            ThermalUnavailableReason.NO_ZONES_DETECTED ->
                "El dispositivo no reportó zonas térmicas legibles. Suele ocurrir cuando el acceso a sysfs queda bloqueado por permisos."
            ThermalUnavailableReason.ALL_ZONES_UNCLASSIFIED ->
                "No pudimos clasificar las zonas térmicas que expuso el fabricante. El catálogo de sensores no las reconoce."
            ThermalUnavailableReason.ALL_TEMPS_INVALID ->
                "Las temperaturas reportadas están fuera del rango plausible del sensor (probablemente lecturas corruptas o en otra escala)."
            ThermalUnavailableReason.PERMISSION_DENIED ->
                "El sistema operativo negó los permisos necesarios para leer los sensores térmicos. Probá habilitar adb root si tu dispositivo lo permite."
            ThermalUnavailableReason.UNKNOWN ->
                "El motivo exacto es desconocido. Reportá este caso adjuntando las zonas listadas debajo para que podamos extender el catálogo."
        }
        val zoneItems = diagnostic.rawZoneNames.take(10).joinToString("") { name ->
            "<li><code>${esc(name)}</code></li>"
        }
        val zonesBlock = if (zoneItems.isEmpty()) "" else """
        <p class="thermal-diag-zones-label">Zonas detectadas:</p>
        <ul class="thermal-diag-zones">$zoneItems</ul>"""
        return """
    <div class="callout callout-warning thermal-diag-banner">
        <strong>&#9888; Datos térmicos no disponibles.</strong>
        <p>$reasonText</p>$zonesBlock
    </div>"""
    }

    /**
     * v4.5.0 -- FPower color band classifier per spec FPW-009. PerfDog
     * anchors: green `< 50 mW/frame`, amber `50 <= x < 65`, red `>= 65`.
     * Boundaries are inclusive-lower / exclusive-upper for the amber band
     * (same shape as the thermal `cls` helper at this file).
     *
     * Negative input is treated as "no data" and bucketed as the same neutral
     * gray class the unavailable card uses. The renderer guards against
     * negative avg upstream; this is a defensive fallback.
     */
    private fun fpowerBand(value: Double): String = when {
        value < 0 -> "fpower-unknown"
        value < 50.0 -> "fpower-green"
        value < 65.0 -> "fpower-amber"
        else -> "fpower-red"
    }

    /**
     * v4.5.0 -- Spanish-tuteo-formal diagnostic banner for the FPower card.
     * Mirrors [thermalDiagnosticBanner] exactly (design ADR-1, ADR-5).
     *
     * Each [FPowerUnavailableReason] maps to a distinct one-sentence reason
     * copy. Raw `rawPathsTried` strings are listed as a `<code>`-wrapped
     * list so users (or devs) can file an issue identifying the missing
     * vendor tuple for [com.gameperf.desktop.core.FPowerVendorCatalog].
     *
     * Path strings are escaped via [esc] defensively in case a vendor ever
     * exposes HTML-special chars in a sysfs node name; [rawPathsTried] is
     * already capped at the source by [com.gameperf.desktop.core.FPowerParser].
     */
    private fun fpowerDiagnosticBanner(diagnostic: FPowerDiagnostic): String {
        val reasonText = when (diagnostic.reason) {
            FPowerUnavailableReason.BATTERY_PATH_MISSING ->
                "No pudimos leer el consumo de batería en este dispositivo. Probamos los siguientes paths sysfs sin éxito; probablemente el vendor todavía no está en nuestro catálogo."
            FPowerUnavailableReason.FPS_ZERO ->
                "No hay FPS válidos en esta sesión, por lo que no se puede calcular mW/frame. Capturá una sesión con gameplay activo."
            FPowerUnavailableReason.IMPLAUSIBLE_VALUE ->
                "Los valores leídos del sensor de batería están fuera del rango plausible (probable bug del kernel del dispositivo). Reportá la marca, el modelo y la versión de Android."
            FPowerUnavailableReason.OEM_LOCKED ->
                "Este OEM (Huawei Knox, Xiaomi GameTurbo o equivalente) bloquea el acceso al sensor de batería. No hay workaround sin root."
            FPowerUnavailableReason.PERMISSION_DENIED ->
                "El sistema operativo negó los permisos necesarios para leer el sensor de batería. Probá habilitar adb root si tu dispositivo lo permite."
            FPowerUnavailableReason.UNKNOWN ->
                "El motivo exacto es desconocido. Reportá este caso adjuntando los paths listados debajo para que podamos extender el catálogo."
        }
        val pathItems = diagnostic.rawPathsTried.take(10).joinToString("") { p ->
            "<li><code>${esc(p)}</code></li>"
        }
        val pathsBlock = if (pathItems.isEmpty()) """
        <p class="fpower-diag-paths-label">Paths probados: <code>ninguno</code></p>""" else """
        <p class="fpower-diag-paths-label">Paths probados:</p>
        <ul class="fpower-diag-paths">$pathItems</ul>"""
        return """
    <div class="callout callout-warning fpower-diag-banner">
        <strong>&#9888; Datos de FPower no disponibles.</strong>
        <p>$reasonText</p>$pathsBlock
    </div>"""
    }

    /**
     * v4.5.0 -- Build the FPower `<section>` HTML conditionally per spec
     * FPW-009. Returns the empty string when there's nothing to render so
     * the caller can splat it unconditionally into the template.
     *
     * Render matrix:
     *  - `!available && diagnostic != null` → N/D card + diagnostic banner.
     *  - `available && history.isNotEmpty()` → numeric card with avg/peak/
     *    band CSS class.
     *  - everything else (legacy callers, ultra-short captures, etc.) →
     *    empty string (no card). Matches the v4.4.1 thermal precedent of
     *    `thermalAvailable=true + empty history` → legacy rendering.
     */
    private fun fpowerSection(
        history: List<Double>,
        avg: Double,
        peak: Double,
        available: Boolean,
        diagnostic: FPowerDiagnostic?,
    ): String {
        if (!available && diagnostic != null) {
            return """
<section id="sec-fpower" class="card fpower-card fpower-unavailable">
    <div class="card-header">
        <h2>&#9889; FPower (mW/frame)</h2>
    </div>
    <p class="card-desc">Energía consumida por frame renderizado. Útil para comparar eficiencia entre builds.</p>
    <div class="stats-row">
        <div class="stat-pill"><span class="stat-pill-label">Promedio</span><span class="stat-pill-value">N/D</span></div>
        <div class="stat-pill"><span class="stat-pill-label">Pico</span><span class="stat-pill-value">N/D</span></div>
        <div class="stat-pill"><span class="stat-pill-label">Mediciones</span><span class="stat-pill-value">0</span></div>
    </div>
    ${fpowerDiagnosticBanner(diagnostic)}
</section>"""
        }
        if (available && history.isNotEmpty()) {
            val avgBand = fpowerBand(avg)
            val peakBand = fpowerBand(peak)
            return """
<section id="sec-fpower" class="card fpower-card $avgBand">
    <div class="card-header">
        <h2>&#9889; FPower (mW/frame)</h2>
    </div>
    <p class="card-desc">Energía consumida por frame renderizado (PerfDog anchors: &lt;50 verde, 50-65 amber, &gt;=65 rojo).</p>
    <div class="stats-row">
        <div class="stat-pill"><span class="stat-pill-label">Promedio</span><span class="stat-pill-value $avgBand">${fmtUS("%.1f", avg)} mW/frame</span></div>
        <div class="stat-pill"><span class="stat-pill-label">Pico</span><span class="stat-pill-value $peakBand">${fmtUS("%.1f", peak)} mW/frame</span></div>
        <div class="stat-pill"><span class="stat-pill-label">Mediciones</span><span class="stat-pill-value">${history.size}</span></div>
    </div>
</section>"""
        }
        return ""
    }

    /**
     * Detect whether the FLT-005 excessive-filter fallback was triggered. Tries the
     * authoritative signal first (a warning string seeded by [com.gameperf.desktop.core.metrics.FilteredMetricsCalculator]),
     * then falls back to recomputing the kept-ratio from the dual aggregates so
     * pre-v4.4.0 sessions or callers that didn't pass detectorWarnings still surface
     * the callout when appropriate.
     */
    private fun isExcessiveFilterTriggered(
        warnings: List<String>,
        filtered: MetricsAggregates?,
        raw: MetricsAggregates?,
    ): Boolean {
        if (warnings.any { it.contains("70%") || it.contains("70 %", ignoreCase = false) }) return true
        if (filtered != null && raw != null && raw.sampleCount > 0) {
            val kept = filtered.sampleCount.toDouble() / raw.sampleCount.toDouble()
            return kept < 0.30
        }
        return false
    }

    private fun buildJsonData(
        pkg: String, info: DeviceInfo?, grade: Char, score: Int, duration: Int,
        avgFps: Int, minFps: Int, maxFps: Int,
        p1: Int, p5: Int, p50: Int, p90: Int, p99: Int,
        avgFrameTime: Double, p99FrameTime: Double,
        peakMem: Long, avgCpu: Int, maxCpu: Int, maxTempCpu: Double, maxTempGpu: Double,
        batteryStart: Int, batteryEnd: Int, frameDrops: Int, jank: Int, stutter: Int,
        problems: List<String>, isWifi: Boolean,
        deviceGrade: Char, deviceScore: Int, deviceTier: String,
        stability: Int, sessionId: String, dateISO: String
    ): String {
        val problemsJson = problems.joinToString(",") { "\"${escJs(it)}\"" }
        return """{
  "sessionId": "$sessionId",
  "date": "$dateISO",
  "package": "${escJs(pkg)}",
  "device": {"model":"${escJs(info?.model ?: "?")}","manufacturer":"${escJs(info?.manufacturer ?: "?")}","cpu":"${escJs(info?.cpu ?: "?")}","gpu":"${escJs((info?.gpu ?: "?").take(60))}","ram":"${escJs(info?.ram ?: "?")}","cores":${info?.cores ?: 0},"sdk":"${escJs(info?.osVersion ?: "0")}","resolution":"${escJs(info?.resolution ?: "?")}","tier":"${escJs(deviceTier)}"},
  "grade": "$grade", "score": $score, "deviceGrade": "$deviceGrade", "deviceScore": $deviceScore, "duration": $duration,
  "fps": {"avg":$avgFps,"min":$minFps,"max":$maxFps,"p1":$p1,"p5":$p5,"p50":$p50,"p90":$p90,"p99":$p99,"stability":$stability},
  "frameTime": {"avg":${fmtUS("%.2f", avgFrameTime)},"p99":${fmtUS("%.2f", p99FrameTime)},"jank":$jank,"stutter":$stutter},
  "memory": {"peakMb":$peakMem}, "cpu": {"avg":$avgCpu,"max":$maxCpu},
  "temperature": {"cpuMax":${fmtUS("%.1f", maxTempCpu)},"gpuMax":${fmtUS("%.1f", maxTempGpu)}},
  "battery": {"start":$batteryStart,"end":$batteryEnd,"drain":${batteryStart - batteryEnd},"isWifi":$isWifi},
  "frameDrops": $frameDrops, "problems": [$problemsJson]
}"""
    }

    // ══════════ CSS ══════════

    private val CSS = """
*,*::before,*::after{box-sizing:border-box;margin:0;padding:0}
html{scroll-behavior:smooth;scroll-padding-top:72px}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif;background:#0f172a;color:#e2e8f0;line-height:1.6;font-size:14px;min-height:100vh;-webkit-font-smoothing:antialiased}
.container{max-width:960px;margin:0 auto;padding:0 20px 40px}
.fab-group{position:fixed;top:16px;right:16px;display:flex;gap:8px;z-index:200}
.fab{width:44px;height:44px;border:none;border-radius:12px;font-size:18px;cursor:pointer;display:flex;align-items:center;justify-content:center;background:linear-gradient(135deg,#38bdf8,#818cf8);color:#fff;box-shadow:0 4px 16px rgba(56,189,248,0.3);transition:all 0.2s ease}
.fab:hover{transform:translateY(-2px);box-shadow:0 6px 24px rgba(56,189,248,0.4)}
.fab-secondary{background:linear-gradient(135deg,#475569,#334155);box-shadow:0 4px 16px rgba(0,0,0,0.3)}
.fab-secondary:hover{box-shadow:0 6px 24px rgba(0,0,0,0.4)}
.fab-success{background:linear-gradient(135deg,#10b981,#059669)!important;box-shadow:0 4px 16px rgba(16,185,129,0.4)!important}
.topnav{position:sticky;top:0;z-index:100;background:rgba(15,23,42,0.85);backdrop-filter:blur(16px);-webkit-backdrop-filter:blur(16px);border-bottom:1px solid rgba(148,163,184,0.08);padding:0 16px}
.nav-inner{max-width:960px;margin:0 auto;display:flex;gap:4px;overflow-x:auto;scrollbar-width:none;padding:8px 0}
.nav-inner::-webkit-scrollbar{display:none}
.nav-link{color:#64748b;font-size:12px;font-weight:600;text-decoration:none;padding:6px 12px;border-radius:8px;white-space:nowrap;transition:all 0.2s ease}
.nav-link:hover{color:#e2e8f0;background:rgba(148,163,184,0.08)}
.nav-link.active{color:#38bdf8;background:rgba(56,189,248,0.08)}
.report-header{position:relative;overflow:hidden;border-radius:0 0 24px 24px;margin-bottom:32px;padding:48px 32px 40px;text-align:center}
.header-bg{position:absolute;inset:0;background:linear-gradient(135deg,#1e293b 0%,#0f172a 40%,#1e1b4b 70%,#172554 100%);z-index:0}
.header-bg::after{content:'';position:absolute;inset:0;background:radial-gradient(ellipse at 50% 0%,rgba(56,189,248,0.08) 0%,transparent 60%)}
.header-content{position:relative;z-index:1}
.header-badge{display:inline-block;background:linear-gradient(135deg,rgba(56,189,248,0.15),rgba(129,140,248,0.15));border:1px solid rgba(56,189,248,0.2);color:#38bdf8;font-size:11px;font-weight:700;letter-spacing:1.5px;text-transform:uppercase;padding:6px 16px;border-radius:100px;margin-bottom:16px}
.header-title{font-size:2.2rem;font-weight:800;letter-spacing:-0.5px;background:linear-gradient(135deg,#e2e8f0,#94a3b8);-webkit-background-clip:text;-webkit-text-fill-color:transparent;background-clip:text;margin-bottom:8px}
.header-pkg{color:#94a3b8;font-size:15px;font-family:monospace;margin-bottom:12px}
.header-meta{display:flex;flex-wrap:wrap;justify-content:center;gap:4px 0;color:#64748b;font-size:13px;margin-bottom:12px}
.meta-sep{margin:0 10px;opacity:0.3}
.header-session{color:#475569;font-size:11px;font-family:monospace;letter-spacing:0.5px}
.card-summary{background:linear-gradient(135deg,rgba(30,41,59,0.8),rgba(15,23,42,0.8));border:1px solid rgba(148,163,184,0.1);padding:0;overflow:hidden}
.summary-grid{display:grid;grid-template-columns:auto 1fr;min-height:200px}
.summary-grade{display:flex;flex-direction:column;align-items:center;justify-content:center;padding:32px 40px;background:rgba(0,0,0,0.15);border-right:1px solid rgba(148,163,184,0.06)}
.grade-ring{width:140px;height:140px;border-radius:50%;background:conic-gradient(var(--grade-color) 0deg,var(--grade-bg) 120deg,rgba(71,85,105,0.2) 120deg);display:flex;align-items:center;justify-content:center;padding:6px}
.grade-ring-inner{width:100%;height:100%;border-radius:50%;background:#0f172a;display:flex;flex-direction:column;align-items:center;justify-content:center}
.grade-letter{font-size:3.5rem;font-weight:900;line-height:1}
.grade-score{color:#64748b;font-size:13px;font-weight:600;margin-top:2px}
.device-grade-pill{margin-top:16px;padding:10px 16px;border-radius:12px;border:1px solid;background:rgba(0,0,0,0.2);text-align:center}
.device-grade-label{display:block;color:#64748b;font-size:10px;letter-spacing:0.5px;text-transform:uppercase}
.device-grade-letter{font-size:2rem;font-weight:900;line-height:1.2}
.device-grade-score{display:block;color:#64748b;font-size:11px}
.summary-info{padding:32px;display:flex;flex-direction:column;justify-content:center}
.summary-info h2{color:#e2e8f0;font-size:1.3rem;font-weight:700;margin-bottom:8px}
.verdict{color:#94a3b8;font-size:14px;line-height:1.7;margin-bottom:20px}
.summary-stats{display:grid;grid-template-columns:repeat(3,1fr);gap:12px}
.summary-stat{background:rgba(30,41,59,0.6);border:1px solid rgba(148,163,184,0.06);border-radius:10px;padding:12px;text-align:center}
.summary-stat-value{display:block;font-size:1.3rem;font-weight:800;color:#e2e8f0}
.summary-stat-label{display:block;color:#64748b;font-size:11px;margin-top:2px}
.section-title{color:#e2e8f0;font-size:1.15rem;font-weight:700;margin-bottom:16px;padding:0 4px}
.metrics-dashboard{margin-bottom:24px}
.metrics-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:16px}
.metric-card{background:linear-gradient(135deg,rgba(30,41,59,0.7),rgba(15,23,42,0.7));border:1px solid rgba(148,163,184,0.08);border-radius:16px;padding:20px;transition:all 0.2s ease}
.metric-card:hover{border-color:rgba(148,163,184,0.15);transform:translateY(-2px);box-shadow:0 8px 32px rgba(0,0,0,0.2)}
.metric-card-top{display:flex;justify-content:space-between;align-items:center;margin-bottom:12px}
.metric-icon{font-size:20px}
.metric-grade{font-size:16px;font-weight:900}
.metric-value{font-size:2rem;font-weight:900;line-height:1;margin-bottom:4px}
.metric-title{color:#94a3b8;font-size:12px;font-weight:600;text-transform:uppercase;letter-spacing:0.5px;margin-bottom:6px}
.metric-detail{color:#475569;font-size:11px;line-height:1.4}
.card{background:linear-gradient(135deg,rgba(30,41,59,0.5),rgba(15,23,42,0.5));border:1px solid rgba(148,163,184,0.08);border-radius:16px;padding:24px;margin-bottom:20px;transition:border-color 0.2s ease}
.card:hover{border-color:rgba(148,163,184,0.12)}
.card-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:16px}
.card-header h2{color:#e2e8f0;font-size:1.1rem;font-weight:700;letter-spacing:-0.3px;margin:0}
.card-badge{font-size:13px;font-weight:800;padding:4px 10px;border-radius:8px;line-height:1}
.badge-red{background:rgba(239,68,68,0.15);color:#ef4444}
.card-desc{color:#64748b;font-size:0.82em;margin-bottom:16px;line-height:1.7}
.card-problems{border-left:3px solid #ef4444}
.stats-row{display:flex;flex-wrap:wrap;gap:8px;margin-bottom:16px}
.stat-pill{display:flex;align-items:center;gap:8px;background:rgba(30,41,59,0.6);border:1px solid rgba(148,163,184,0.06);border-radius:10px;padding:8px 14px;font-size:13px}
.stat-pill-accent{background:rgba(56,189,248,0.06);border-color:rgba(56,189,248,0.15)}
.stat-pill-label{color:#64748b;font-size:11px;font-weight:600;text-transform:uppercase;letter-spacing:0.3px}
.stat-pill-value{font-weight:800;color:#e2e8f0}
.good{color:#10b981}.warn{color:#f59e0b}.bad{color:#ef4444}
.hint{color:#475569;font-size:0.78em;margin-top:10px;font-style:italic;line-height:1.6}
.hint.good{color:#10b981}
.chart-container{height:280px;background:rgba(0,0,0,0.2);border:1px solid rgba(148,163,184,0.04);border-radius:12px;padding:16px;margin-top:12px}
.table-wrap,.table-scroll{max-height:420px;overflow-y:auto;border-radius:10px}
.data-table{width:100%;border-collapse:collapse}
.data-table th{position:sticky;top:0;background:#1e293b;color:#94a3b8;font-size:11px;font-weight:700;letter-spacing:0.5px;text-transform:uppercase;padding:10px 14px;text-align:center;border-bottom:2px solid rgba(148,163,184,0.1)}
.data-table td{padding:10px 14px;text-align:center;border-bottom:1px solid rgba(148,163,184,0.04);font-weight:600;font-size:13px;color:#cbd5e1}
.data-table.compact td{padding:6px 10px;font-size:12px}
.data-table tbody tr:hover{background:rgba(56,189,248,0.03)}
.mono{font-family:'SF Mono',Consolas,'Courier New',monospace;font-weight:700}
.small-text{font-size:11px}
.bar-track{background:rgba(148,163,184,0.06);border-radius:4px;height:14px;overflow:hidden}
.bar-fill{height:100%;border-radius:4px;transition:width 0.3s ease}
.marker-badge{display:inline-block;padding:3px 10px;border-radius:6px;font-size:11px;font-weight:700;letter-spacing:0.3px}
.status-box{padding:16px 20px;border-radius:12px;font-size:14px;line-height:1.6;display:flex;align-items:center;gap:10px}
.status-ok{background:rgba(16,185,129,0.06);border:1px solid rgba(16,185,129,0.15);color:#10b981}
.status-icon{font-size:18px}
.problem-row{display:flex;align-items:flex-start;gap:10px;padding:14px 18px;margin:8px 0;border-radius:12px;font-size:13px;line-height:1.6}
.problem-critical{background:rgba(239,68,68,0.06);border:1px solid rgba(239,68,68,0.12);color:#fca5a5}
.problem-warning{background:rgba(245,158,11,0.06);border:1px solid rgba(245,158,11,0.12);color:#fcd34d}
.problem-info{background:rgba(56,189,248,0.06);border:1px solid rgba(56,189,248,0.12);color:#7dd3fc}
.problem-icon{font-size:16px;flex-shrink:0;margin-top:1px}
.problem-num{color:#475569;font-weight:700;font-size:11px;flex-shrink:0;margin-top:2px}
.problem-text{flex:1}
.expandable{border-top:1px solid rgba(148,163,184,0.06);margin-top:12px}
.expandable:first-child{border-top:none;margin-top:0}
.expand-btn{display:flex;align-items:center;gap:8px;width:100%;background:none;border:none;color:#94a3b8;font-size:13px;font-weight:600;padding:14px 0;cursor:pointer;text-align:left;transition:color 0.2s ease}
.expand-btn:hover{color:#e2e8f0}
.expand-icon{font-size:10px;transition:transform 0.2s ease;display:inline-block}
.expandable.open .expand-icon{transform:rotate(90deg)}
.expand-content{display:none;padding-bottom:16px}
.expandable.open .expand-content{display:block}
.hw-grid{display:grid;grid-template-columns:1fr 1fr;gap:2px}
.hw-item{display:flex;justify-content:space-between;align-items:center;padding:12px 16px;background:rgba(30,41,59,0.3);transition:background 0.2s}
.hw-item:hover{background:rgba(30,41,59,0.5)}
.hw-item:nth-child(1){border-radius:10px 0 0 0}.hw-item:nth-child(2){border-radius:0 10px 0 0}
.hw-item:nth-last-child(2){border-radius:0 0 0 10px}.hw-item:last-child{border-radius:0 0 10px 0}
.hw-label{color:#64748b;font-size:12px;font-weight:600;text-transform:uppercase;letter-spacing:0.3px}
.hw-value{color:#e2e8f0;font-weight:700;font-size:13px;text-align:right}
.hw-tier{color:#818cf8}
.grade-bar-wrap{margin:16px 0}
.grade-bar{height:10px;background:rgba(148,163,184,0.08);border-radius:5px;overflow:hidden}
.grade-fill{height:100%;border-radius:5px;transition:width 0.8s cubic-bezier(0.4,0,0.2,1)}
.grade-labels{display:flex;justify-content:space-between;color:#475569;font-size:10px;margin-top:6px;font-weight:600}
.final-score{display:flex;align-items:baseline;justify-content:center;gap:6px;margin-top:16px}
.final-score-num{font-size:2.5rem;font-weight:900}
.final-score-sep{font-size:1.5rem;color:#475569}
.final-score-den{font-size:1.5rem;color:#475569;font-weight:700}
.final-score-eq{font-size:1.5rem;color:#475569;margin-left:8px}
.final-score-grade{font-size:2.5rem;font-weight:900}
.method-grid{display:grid;gap:2px}
.method-item{display:flex;gap:16px;align-items:baseline;padding:10px 14px;background:rgba(30,41,59,0.3);border-radius:4px}
.method-label{color:#94a3b8;font-size:12px;font-weight:700;min-width:90px;text-transform:uppercase;letter-spacing:0.3px}
.method-value{color:#64748b;font-size:12px;line-height:1.5}
.report-footer{text-align:center;padding:32px 0 16px;margin-top:40px;border-top:1px solid rgba(148,163,184,0.06)}
.footer-logo{font-size:13px;font-weight:800;letter-spacing:1px;text-transform:uppercase;background:linear-gradient(135deg,#38bdf8,#818cf8);-webkit-background-clip:text;-webkit-text-fill-color:transparent;background-clip:text;margin-bottom:8px}
.report-footer p{color:#475569;font-size:11px;margin:4px 0}
.footer-session{font-family:monospace;font-size:10px!important;color:#334155!important;letter-spacing:0.5px}
/* v4.4.0 — auto detection / dual-view / conclusions */
.detection-banner{max-width:960px;margin:0 auto 16px;padding:10px 16px;background:linear-gradient(135deg,rgba(30,41,59,0.6),rgba(15,23,42,0.6));border:1px solid rgba(148,163,184,0.1);border-radius:10px;display:flex;align-items:center;gap:12px;flex-wrap:wrap;font-size:13px}
.detection-banner-icon{font-size:14px}
.detection-banner-label{flex:1;color:#cbd5e1;font-weight:600}
.detection-banner-count{color:#64748b;font-size:12px;font-weight:600}
.detection-warnings{flex-basis:100%;margin-top:6px;color:#94a3b8;font-size:12px}
.detection-warnings summary{cursor:pointer;color:#f59e0b;font-weight:600}
.detection-warnings ul{margin:6px 0 2px 20px;color:#94a3b8;line-height:1.6}
.callout{max-width:960px;margin:0 auto 20px;padding:14px 18px;border-radius:12px;font-size:13px;line-height:1.6}
.callout-warning{background:rgba(245,158,11,0.08);border:1px solid rgba(245,158,11,0.25);color:#fcd34d}
.callout-warning strong{color:#fbbf24}
.fpower-card{border-left:4px solid #475569}
.fpower-card.fpower-green{border-left-color:#22c55e}
.fpower-card.fpower-amber{border-left-color:#f59e0b}
.fpower-card.fpower-red{border-left-color:#ef4444}
.fpower-card.fpower-unavailable{border-left-color:#6b7280;opacity:0.85}
.stat-pill-value.fpower-green{color:#22c55e}
.stat-pill-value.fpower-amber{color:#f59e0b}
.stat-pill-value.fpower-red{color:#ef4444}
.fpower-diag-banner{margin-top:12px}
.fpower-diag-paths-label{color:#cbd5e1;font-size:12px;margin-top:8px;margin-bottom:4px;font-weight:600}
.fpower-diag-paths{margin:6px 0 2px 20px;color:#94a3b8;line-height:1.6;font-size:12px}
.fpower-diag-paths code{font-size:0.9em;opacity:0.85}
.metric-raw{color:#64748b;font-size:0.85em;margin-top:4px;opacity:0.75;font-weight:500}
.conclusions-list{display:flex;flex-direction:column;gap:12px}
.conclusion-card{background:rgba(15,23,42,0.5);border:1px solid rgba(148,163,184,0.1);border-left:4px solid #64748b;border-radius:10px;padding:14px 16px}
.conclusion-critical{border-left-color:#ef4444;background:rgba(239,68,68,0.05)}
.conclusion-warning{border-left-color:#f59e0b;background:rgba(245,158,11,0.05)}
.conclusion-info{border-left-color:#38bdf8;background:rgba(56,189,248,0.05)}
.conclusion-header{display:flex;align-items:center;gap:10px;margin-bottom:8px;font-size:12px}
.conclusion-icon{font-size:14px}
.conclusion-severity{font-weight:800;letter-spacing:0.3px;text-transform:uppercase;font-size:11px}
.conclusion-critical .conclusion-severity{color:#ef4444}
.conclusion-warning .conclusion-severity{color:#f59e0b}
.conclusion-info .conclusion-severity{color:#38bdf8}
.conclusion-id{color:#475569;font-family:monospace;font-size:10px;margin-left:auto;letter-spacing:0.3px}
.conclusion-headline{color:#e2e8f0;font-size:13px;line-height:1.6;margin-bottom:6px;font-weight:600}
.conclusion-rec{color:#94a3b8;font-size:12px;line-height:1.6;margin:0}
.events-table .source-auto{color:#38bdf8;font-weight:700}
.events-table .source-manual{color:#f97316;font-weight:700}
@media(max-width:768px){.container{padding:0 12px 32px}.report-header{padding:32px 20px 28px}.header-title{font-size:1.6rem}.header-meta{flex-direction:column;gap:4px}.meta-sep{display:none}.summary-grid{grid-template-columns:1fr}.summary-grade{padding:24px;border-right:none;border-bottom:1px solid rgba(148,163,184,0.06)}.summary-stats{grid-template-columns:repeat(2,1fr)}.metrics-grid{grid-template-columns:repeat(2,1fr)}.hw-grid{grid-template-columns:1fr}.hw-item{border-radius:0!important}.hw-item:first-child{border-radius:10px 10px 0 0!important}.hw-item:last-child{border-radius:0 0 10px 10px!important}.stats-row{gap:6px}.stat-pill{padding:6px 10px;font-size:12px}.grade-ring{width:110px;height:110px}.grade-letter{font-size:2.8rem}.fab-group{top:auto;bottom:16px;right:16px}.detection-banner{margin:0 12px 16px;font-size:12px}.callout{margin:0 12px 16px}}
@media(max-width:480px){.metrics-grid{grid-template-columns:1fr}.summary-stats{grid-template-columns:1fr 1fr}.nav-link{font-size:11px;padding:5px 8px}}
@media print {
  @page { size: A4; margin: 10mm 10mm 12mm 10mm }
  /* PAGE BACKGROUND IS WHITE — every container needs a contrasting fill or border to be visible */
  html, body { background: #fff !important; color: #0f172a !important; font-size: 10.5px !important; -webkit-print-color-adjust: exact; print-color-adjust: exact }
  .container { max-width: 100%; padding: 0 }
  /* Hide interactive-only chrome */
  .fab-group, .topnav, .expand-btn { display: none !important }
  .expand-content { display: block !important }

  /* === HEADER === Solid mid-gray block, sits clearly above the white page */
  .report-header { border-radius: 0; padding: 20px 18px 18px; margin-bottom: 14px; border: 1px solid #64748b !important }
  .header-bg { background: linear-gradient(135deg, #cbd5e1, #94a3b8) !important }
  .header-bg::after { display: none }
  .header-title { -webkit-text-fill-color: #0f172a !important; background: none !important; font-size: 1.5rem !important; margin-bottom: 4px; font-weight: 800 !important }
  .header-badge { color: #1e3a8a !important; border: 1px solid #1e3a8a !important; background: #ffffff !important; font-size: 9px !important; padding: 4px 12px !important; margin-bottom: 8px !important; font-weight: 700 !important }
  .header-pkg { color: #1e293b !important; font-size: 12px !important; margin-bottom: 6px; font-weight: 600 !important }
  .header-meta, .header-session { color: #334155 !important; font-size: 10px !important }

  /* === CARDS LEVEL 1 === Light gray fill (#f1f5f9, delta ~30) with strong border (#94a3b8, delta ~80) */
  .card, .card-summary {
    background: #f1f5f9 !important;
    border: 1px solid #94a3b8 !important;
    box-shadow: none !important;
    padding: 16px !important;
    margin-bottom: 14px !important;
    page-break-inside: auto;
    border-radius: 8px !important
  }
  .card h2, .card-header h2, .section-title { color: #0f172a !important; font-size: 1rem !important; margin-bottom: 10px !important; font-weight: 700 !important }
  .card-desc, .hint, .verdict { color: #475569 !important; font-size: 10px !important }

  /* === METRIC CARDS (level 2) === White on top of the gray level-1 card to invert contrast */
  .metric-card {
    background: #ffffff !important;
    border: 1px solid #94a3b8 !important;
    padding: 12px !important;
    border-radius: 6px !important
  }
  .metric-card .metric-value { color: #0f172a !important; font-weight: 800 !important }
  .metrics-grid { gap: 10px !important }

  /* === SUMMARY CARD === Slightly darker (#e2e8f0) to stand out as the hero element */
  .summary-grade {
    background: #e2e8f0 !important;
    padding: 20px !important;
    border-right: 1px solid #94a3b8 !important
  }
  .grade-ring { background: #fff !important; border: 5px solid var(--grade-color); width: 116px !important; height: 116px !important }
  .grade-ring-inner { background: #fff !important }
  .grade-letter { font-size: 2.8rem !important; font-weight: 900 !important }
  .grade-score { color: #1e293b !important; font-weight: 600 !important }
  .device-grade-pill {
    background: #ffffff !important;
    border: 1px solid #64748b !important;
    margin-top: 14px !important;
    padding: 10px 16px !important
  }
  .device-grade-label { color: #475569 !important; font-weight: 600 !important }
  .device-grade-score { color: #475569 !important }
  .summary-info { padding: 20px !important; background: transparent !important }
  .summary-info h2 { color: #0f172a !important }

  /* === STAT PILLS === Solid contrasting fill (#e2e8f0, delta ~30) + visible border */
  .stat-pill {
    background: #e2e8f0 !important;
    border: 1px solid #94a3b8 !important;
    padding: 8px 12px !important;
    border-radius: 6px !important
  }
  .stat-pill-value, .summary-stat-value { color: #0f172a !important; font-size: 14px !important; font-weight: 800 !important }
  .stat-pill-label, .summary-stat-label { color: #475569 !important; font-size: 9px !important; font-weight: 700 !important }
  .stats-row { gap: 8px !important }

  /* === HARDWARE GRID === White cells inside gray level-1 card */
  .hw-item {
    background: #ffffff !important;
    border: 1px solid #94a3b8 !important;
    padding: 10px 12px !important
  }
  .hw-label { color: #475569 !important; font-size: 9px !important; font-weight: 700 !important }
  .hw-value { color: #0f172a !important; font-size: 11px !important; font-weight: 700 !important }

  /* === DATA TABLES === Dark header + alternating row stripes for real-table look */
  .data-table { font-size: 10px !important; border: 1px solid #94a3b8 !important; border-collapse: collapse !important }
  .data-table th {
    background: #475569 !important;
    color: #ffffff !important;
    font-size: 9px !important;
    padding: 8px 10px !important;
    font-weight: 700 !important;
    letter-spacing: 0.5px !important
  }
  .data-table td {
    color: #0f172a !important;
    border-bottom: 1px solid #cbd5e1 !important;
    padding: 7px 10px !important
  }
  .data-table tr:nth-child(even) td { background: #f1f5f9 !important }
  .data-table tr:nth-child(odd) td { background: #ffffff !important }
  .good { color: #15803d !important; font-weight: 700 }
  .warn { color: #b45309 !important; font-weight: 700 }
  .bad { color: #b91c1c !important; font-weight: 800 }

  /* === CHARTS === White canvas with strong border so the chart area is clearly delimited */
  .chart-container {
    background: #ffffff !important;
    border: 1px solid #94a3b8 !important;
    height: 220px !important;
    padding: 12px !important;
    margin-top: 10px !important;
    border-radius: 6px !important
  }

  /* === GRADE BAR (the F-D-C-B-A scale at the bottom) === */
  .grade-bar { background: #cbd5e1 !important; border: 1px solid #94a3b8 !important }
  .grade-fill { background: var(--grade-color) !important }

  /* === FINAL SCORE === Big, bold, centered */
  .final-score-num, .final-score-grade { color: #0f172a !important; font-weight: 900 !important }
  .final-score-sep { color: #64748b !important }

  /* === FOOTER === Visible separator above + readable session ID */
  .report-footer {
    border-top: 2px solid #94a3b8 !important;
    padding: 16px 0 !important;
    margin-top: 18px !important
  }
  .footer-logo {
    -webkit-text-fill-color: #1e293b !important;
    background: none !important;
    font-size: 12px !important;
    font-weight: 800 !important
  }
  .report-footer p { color: #475569 !important; font-size: 9px !important }
  .footer-session { color: #64748b !important; font-weight: 600 !important }

  /* === PROBLEM CARDS === Colored fills already had OK contrast, just stronger borders */
  .problem-critical { background: #fef2f2 !important; border: 2px solid #dc2626 !important; color: #7f1d1d !important; font-weight: 600 !important }
  .problem-warning { background: #fffbeb !important; border: 2px solid #d97706 !important; color: #78350f !important; font-weight: 600 !important }
  .problem-info { background: #eff6ff !important; border: 2px solid #2563eb !important; color: #1e3a8a !important; font-weight: 600 !important }
  .status-ok { background: #f0fdf4 !important; border: 2px solid #16a34a !important; color: #14532d !important; font-weight: 600 !important }

  /* === METHOD ITEMS === White cells inside gray card */
  .method-item {
    background: #ffffff !important;
    border: 1px solid #94a3b8 !important
  }
  .method-label { color: #475569 !important; font-weight: 700 !important }
  .method-value { color: #0f172a !important }

  /* === MISC === */
  .summary-stat { background: transparent !important }
}
""".trimIndent()
}
