package com.gameperf.desktop.report

import com.gameperf.desktop.core.AdbBridge
import com.gameperf.desktop.viewmodel.MarkerType
import com.gameperf.desktop.viewmodel.SessionMarker
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID

object ReportGenerator {

    fun generate(
        pkg: String, info: AdbBridge.DeviceInfo?, grade: Char, score: Int, duration: Int,
        fpsHistory: List<Int>, memHistory: List<Long>, nativeHistory: List<Long>, javaHistory: List<Long>,
        cpuHistory: List<Int>, tempCpuHistory: List<Double>, tempGpuHistory: List<Double>, tempSkinHistory: List<Double>,
        frameTimeHistory: List<Double>, allFrameTimes: List<Double>,
        avgFps: Int, minFps: Int, maxFps: Int, p1: Int, p5: Int, p50: Int, p90: Int, p99: Int,
        avgFrameTime: Double, p99FrameTime: Double,
        peakMem: Long, avgCpu: Int, maxCpu: Int, maxTempCpu: Double, maxTempGpu: Double,
        batteryStart: Int, batteryEnd: Int, frameDrops: Int, jank: Int, stutter: Int,
        problems: List<String>, isWifi: Boolean,
        deviceGrade: Char = ' ', deviceScore: Int = 0, deviceTier: String = "",
        fpsTimestamps: List<Pair<Int, Int>> = emptyList(),
        markers: List<SessionMarker> = emptyList()
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
        val fpsGrade = metricGrade(avgFps, 55, 45, 30, 20)
        val ftGrade = metricGrade(100 - avgFrameTime.toInt().coerceIn(0, 100), 84, 67, 50, 34)
        val memGrade = metricGrade(100 - (peakMem.toInt() / 30).coerceIn(0, 100), 80, 60, 40, 20)
        val cpuGrade = metricGrade(100 - avgCpu, 70, 55, 40, 20)
        val tempGrade = if (maxTempCpu <= 0) 'A' else metricGrade(
            100 - ((maxTempCpu - 30).toInt().coerceIn(0, 30) * 100 / 30), 80, 60, 40, 20
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
        val tcD = tempCpuHistory.joinToString(",") { "%.1f".format(it) }
        val tgD = tempGpuHistory.joinToString(",") { "%.1f".format(it) }
        val tsD = tempSkinHistory.joinToString(",") { "%.1f".format(it) }
        val tL = (1..maxOf(cpuHistory.size, tempCpuHistory.size, 1)).joinToString(",") { "\"${it}s\"" }

        val ftBuckets = listOf(
            allFrameTimes.count { it < 8.0 },
            allFrameTimes.count { it in 8.0..16.66 },
            allFrameTimes.count { it in 16.67..33.32 },
            allFrameTimes.count { it in 33.33..49.99 },
            allFrameTimes.count { it in 50.0..99.99 },
            allFrameTimes.count { it >= 100.0 }
        ).joinToString(",")

        // Marker annotations for FPS chart
        val markerAnnotationsJs = if (markers.isNotEmpty()) {
            markers.mapIndexed { i, m ->
                val labelText = m.note.ifEmpty { m.type.label }
                val color = markerColorHex(m.type)
                """m$i:{type:'line',xMin:'${m.timestampSeconds}s',xMax:'${m.timestampSeconds}s',borderColor:'$color',borderWidth:2,borderDash:[4,4],label:{content:'${escJs(labelText)}',display:true,position:'start',backgroundColor:'$color',color:'#fff',font:{size:10,weight:'bold'},padding:4,borderRadius:4}}"""
            }.joinToString(",")
        } else ""

        // Session markers section
        val markersHtml = if (markers.isNotEmpty()) {
            """
<section id="sec-markers" class="card">
    <div class="card-header"><h2>&#128205; Marcadores de Sesion</h2></div>
    <p class="card-desc">Eventos marcados manualmente durante la captura. Cada marcador se muestra como linea vertical en el grafico de FPS.</p>
    <div style="display:flex;gap:8px;flex-wrap:wrap;margin-bottom:16px">
        ${markers.map { it.type }.distinct().joinToString("") { type ->
                val color = markerColorHex(type)
                """<span class="marker-badge" style="background:${color}20;color:$color;border:1px solid ${color}40">${type.label} (${markers.count { it.type == type }})</span>"""
            }}
    </div>
    <div class="table-wrap">
    <table class="data-table">
        <thead><tr><th>Segundo</th><th>Tipo</th><th>Nota</th></tr></thead>
        <tbody>
        ${markers.sortedBy { it.timestampSeconds }.joinToString("\n        ") { m ->
                val color = markerColorHex(m.type)
                """<tr><td class="mono">${m.timestampSeconds}s</td><td><span class="marker-badge" style="background:${color}20;color:$color;border:1px solid ${color}40">${m.type.label}</span></td><td>${esc(m.note.ifEmpty { "\u2014" })}</td></tr>"""
            }}
        </tbody>
    </table>
    </div>
</section>"""
        } else ""

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
<script src="https://cdn.jsdelivr.net/npm/chart.js@4"></script>
<script src="https://cdn.jsdelivr.net/npm/chartjs-plugin-annotation@3"></script>
<script src="https://cdn.jsdelivr.net/npm/chartjs-plugin-zoom@2"></script>
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
        <a href="#sec-dashboard" class="nav-link">Metricas</a>
        <a href="#sec-fps" class="nav-link">FPS</a>
        <a href="#sec-frametime" class="nav-link">Frame Time</a>
        <a href="#sec-memory" class="nav-link">Memoria</a>
        <a href="#sec-cpu" class="nav-link">CPU</a>
        <a href="#sec-temp" class="nav-link">Temp</a>
        <a href="#sec-problems" class="nav-link">Problemas</a>
        ${if (markers.isNotEmpty()) """<a href="#sec-markers" class="nav-link">Marcadores</a>""" else ""}
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
                <div class="summary-stat"><span class="summary-stat-value">${"%.1f".format(avgFrameTime)}ms</span><span class="summary-stat-label">Frame Time</span></div>
                <div class="summary-stat"><span class="summary-stat-value">${peakMem}MB</span><span class="summary-stat-label">Mem. Pico</span></div>
                <div class="summary-stat"><span class="summary-stat-value ${cls(avgCpu, 85, 70)}">${avgCpu}%</span><span class="summary-stat-label">CPU Prom.</span></div>
                <div class="summary-stat"><span class="summary-stat-value">${stability}%</span><span class="summary-stat-label">Estabilidad</span></div>
            </div>
        </div>
    </div>
</section>

<section id="sec-dashboard" class="metrics-dashboard">
    <h2 class="section-title">&#128202; Panel de Metricas</h2>
    <div class="metrics-grid">
        ${metricCard("FPS", "$avgFps", "fps", fpsGrade, gradeColor(fpsGrade), "Min $minFps / Max $maxFps / P1 $p1")}
        ${metricCard("Frame Time", "${"%.1f".format(avgFrameTime)}ms", "frametime", ftGrade, gradeColor(ftGrade), "P99 ${"%.1f".format(p99FrameTime)}ms / Jank $jank")}
        ${metricCard("Memoria", "${peakMem}MB", "memory", memGrade, gradeColor(memGrade), "Inicio ${memHistory.firstOrNull() ?: "?"}MB / Final ${memHistory.lastOrNull() ?: "?"}MB")}
        ${metricCard("CPU", "${avgCpu}%", "cpu", cpuGrade, gradeColor(cpuGrade), "Max ${maxCpu}%")}
        ${metricCard("Temperatura", if (maxTempCpu > 0) "${maxTempCpu.toInt()}\u00B0C" else "N/A", "temp", tempGrade, gradeColor(tempGrade), if (maxTempGpu > 0) "GPU ${maxTempGpu.toInt()}\u00B0C" else "Solo CPU")}
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
        <div class="stat-pill"><span class="stat-pill-label">Promedio</span><span class="stat-pill-value">${"%.1f".format(avgFrameTime)}ms</span></div>
        <div class="stat-pill"><span class="stat-pill-label">P99</span><span class="stat-pill-value ${cls(p99FrameTime.toInt(), 50, 17, "r")}">${"%.1f".format(p99FrameTime)}ms</span></div>
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
    <p class="card-desc">Por encima de ~42°C se activa el thermal throttling que reduce CPU/GPU automaticamente.</p>
    <div class="stats-row">
        <div class="stat-pill"><span class="stat-pill-label">CPU Max</span><span class="stat-pill-value ${cls(maxTempCpu.toInt(), 45, 40)}">${if (maxTempCpu > 0) "${maxTempCpu.toInt()}\u00B0C" else "N/A"}</span></div>
        <div class="stat-pill"><span class="stat-pill-label">GPU Max</span><span class="stat-pill-value">${if (maxTempGpu > 0) "${maxTempGpu.toInt()}\u00B0C" else "N/A"}</span></div>
    </div>
    <div class="chart-container"><canvas id="tempChart"></canvas></div>
</section>

<section class="card">
    <div class="card-header"><h2>&#128267; Bateria</h2></div>
    <div class="stats-row">
        <div class="stat-pill"><span class="stat-pill-label">Inicio</span><span class="stat-pill-value">${batteryStart}%</span></div>
        <div class="stat-pill"><span class="stat-pill-label">Final</span><span class="stat-pill-value">${batteryEnd}%</span></div>
        <div class="stat-pill"><span class="stat-pill-label">Consumo</span><span class="stat-pill-value ${cls(batteryDrain, 10, 5)}">${batteryDrain}%</span></div>
        <div class="stat-pill"><span class="stat-pill-label">Consumo/min</span><span class="stat-pill-value">${if (duration > 0) "%.2f".format(batteryDrain.toDouble() / (duration / 60.0)) else "0"}%</span></div>
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

$markersHtml

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
        <div class="hw-item"><span class="hw-label">SDK</span><span class="hw-value">${info?.sdk ?: "?"}</span></div>
        <div class="hw-item"><span class="hw-label">Resolucion</span><span class="hw-value">${esc(info?.resolution ?: "?")}</span></div>
        ${if (deviceTier.isNotEmpty()) """<div class="hw-item"><span class="hw-label">GPU Tier</span><span class="hw-value hw-tier">${esc(deviceTier)}</span></div>""" else ""}
        ${if (deviceScore > 0) """<div class="hw-item"><span class="hw-label">Hardware Score</span><span class="hw-value">${deviceScore}/100</span></div>""" else ""}
    </div>
</section>

<footer class="report-footer">
    <div class="footer-logo">Game Performance Tool</div>
    <p>v1.0.0 — Informe generado el ${SimpleDateFormat("dd/MM/yyyy 'a las' HH:mm:ss").format(Date())}</p>
    <p class="footer-session">Session: $sessionId</p>
</footer>

</div>

<script id="sessionData" type="application/json">$jsonData</script>

<script>
Chart.defaults.font.family='-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Helvetica Neue",sans-serif';
Chart.defaults.color='#94a3b8';
var B={responsive:true,maintainAspectRatio:false,interaction:{mode:'index',intersect:false},plugins:{legend:{labels:{color:'#94a3b8',font:{size:11},usePointStyle:true,pointStyle:'circle',padding:16}},tooltip:{mode:'index',intersect:false,backgroundColor:'rgba(15,23,42,0.95)',titleColor:'#e2e8f0',bodyColor:'#cbd5e1',borderColor:'rgba(148,163,184,0.2)',borderWidth:1,padding:12,cornerRadius:8,titleFont:{weight:'600'}}},scales:{x:{ticks:{color:'#64748b',maxTicksLimit:20,font:{size:10}},grid:{color:'rgba(148,163,184,0.06)'}},y:{ticks:{color:'#94a3b8',font:{size:11}},grid:{color:'rgba(148,163,184,0.08)'}}}};
var ZP={zoom:{wheel:{enabled:true},pinch:{enabled:true},mode:'x'},pan:{enabled:true,mode:'x'}};
""")
            // FPS Chart
            if (fpsD.isNotEmpty()) append("""
(function(){var c=document.getElementById('fpsChart').getContext('2d');var g=c.createLinearGradient(0,0,0,300);g.addColorStop(0,'rgba(16,185,129,0.25)');g.addColorStop(0.5,'rgba(245,158,11,0.10)');g.addColorStop(1,'rgba(239,68,68,0.05)');new Chart(c,{type:'line',data:{labels:[$fpsL],datasets:[{label:'FPS',data:[$fpsD],borderColor:'#38bdf8',backgroundColor:g,fill:true,tension:0.3,pointRadius:0,pointHoverRadius:5,pointHoverBackgroundColor:'#38bdf8',borderWidth:2.5,segment:{borderColor:function(ctx){var v=ctx.p1.parsed.y;if(v<20)return'#ef4444';if(v<30)return'#f59e0b';return'#38bdf8'}}}]},options:{...B,scales:{...B.scales,y:{...B.scales.y,min:0,suggestedMax:65}},plugins:{...B.plugins,zoom:ZP,annotation:{annotations:{zr:{type:'box',yMin:0,yMax:20,backgroundColor:'rgba(239,68,68,0.04)',borderWidth:0},zy:{type:'box',yMin:20,yMax:30,backgroundColor:'rgba(245,158,11,0.03)',borderWidth:0},l30:{type:'line',yMin:30,yMax:30,borderColor:'rgba(245,158,11,0.4)',borderWidth:1,borderDash:[6,4],label:{content:'30 FPS',display:true,color:'#f59e0b',font:{size:10,weight:'600'},backgroundColor:'rgba(15,23,42,0.8)',padding:4}},l60:{type:'line',yMin:60,yMax:60,borderColor:'rgba(16,185,129,0.4)',borderWidth:1,borderDash:[6,4],label:{content:'60 FPS',display:true,color:'#10b981',font:{size:10,weight:'600'},backgroundColor:'rgba(15,23,42,0.8)',padding:4}}${if (markerAnnotationsJs.isNotEmpty()) ",$markerAnnotationsJs" else ""}}}}}})})();
""")
            // Frame Time Histogram
            if (ftBuckets.isNotEmpty()) append("""
(function(){new Chart(document.getElementById('ftChart').getContext('2d'),{type:'bar',data:{labels:['<8ms (>120fps)','8-16ms (60-120fps)','16-33ms (30-60fps)','33-50ms (20-30fps)','50-100ms (<20fps)','>100ms (stutter)'],datasets:[{label:'Frames',data:[$ftBuckets],backgroundColor:['#10b981','#84cc16','#f59e0b','#f97316','#ef4444','#dc2626'],borderRadius:6,borderSkipped:false,maxBarThickness:60}]},options:{indexAxis:'y',...B,plugins:{...B.plugins,legend:{display:false}},scales:{x:{...B.scales.x},y:{...B.scales.y,ticks:{...B.scales.y.ticks,font:{size:11}}}}}})})();
""")
            // Memory Chart
            if (memD.isNotEmpty()) append("""
(function(){new Chart(document.getElementById('memChart').getContext('2d'),{type:'line',data:{labels:[$memL],datasets:[{label:'Total PSS (MB)',data:[$memD],borderColor:'#38bdf8',backgroundColor:'rgba(56,189,248,0.08)',fill:true,tension:0.3,pointRadius:0,borderWidth:2.5},{label:'Native Heap (MB)',data:[$natD],borderColor:'#f97316',tension:0.3,pointRadius:0,borderWidth:1.5},{label:'Java Heap (MB)',data:[$javD],borderColor:'#10b981',tension:0.3,pointRadius:0,borderWidth:1.5}]},options:{...B,plugins:{...B.plugins,zoom:ZP}}})})();
""")
            // CPU Chart
            if (cpuD.isNotEmpty()) append("""
(function(){var c=document.getElementById('cpuChart').getContext('2d');var g=c.createLinearGradient(0,0,0,300);g.addColorStop(0,'rgba(56,189,248,0.2)');g.addColorStop(1,'rgba(56,189,248,0.01)');new Chart(c,{type:'line',data:{labels:[$tL],datasets:[{label:'CPU %',data:[$cpuD],borderColor:'#38bdf8',backgroundColor:g,fill:true,tension:0.3,pointRadius:0,borderWidth:2.5,segment:{borderColor:function(ctx){var v=ctx.p1.parsed.y;if(v>85)return'#ef4444';if(v>70)return'#f59e0b';return'#38bdf8'}}}]},options:{...B,scales:{...B.scales,y:{...B.scales.y,min:0,max:100}},plugins:{...B.plugins,annotation:{annotations:{w:{type:'line',yMin:85,yMax:85,borderColor:'rgba(239,68,68,0.3)',borderWidth:1,borderDash:[6,4],label:{content:'85% Saturacion',display:true,color:'#ef4444',font:{size:9},backgroundColor:'rgba(15,23,42,0.8)',padding:3}}}}}}})})();
""")
            // Temperature Chart
            if (tcD.isNotEmpty()) append("""
(function(){new Chart(document.getElementById('tempChart').getContext('2d'),{type:'line',data:{labels:[$tL],datasets:[{label:'CPU',data:[$tcD],borderColor:'#ef4444',tension:0.3,pointRadius:0,borderWidth:2.5},{label:'GPU',data:[$tgD],borderColor:'#f97316',tension:0.3,pointRadius:0,borderWidth:1.5},{label:'Skin',data:[$tsD],borderColor:'#f59e0b',tension:0.3,pointRadius:0,borderWidth:1.5}]},options:{...B,plugins:{...B.plugins,annotation:{annotations:{t:{type:'line',yMin:42,yMax:42,borderColor:'rgba(239,68,68,0.4)',borderWidth:1,borderDash:[6,4],label:{content:'Thermal Throttle (~42\u00B0C)',display:true,color:'#ef4444',font:{size:9},backgroundColor:'rgba(15,23,42,0.8)',padding:3}}}}}}})})();
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
    private fun escJs(s: String) = s.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"").replace("\n", " ")

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

    private fun buildJsonData(
        pkg: String, info: AdbBridge.DeviceInfo?, grade: Char, score: Int, duration: Int,
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
  "device": {"model":"${escJs(info?.model ?: "?")}","manufacturer":"${escJs(info?.manufacturer ?: "?")}","cpu":"${escJs(info?.cpu ?: "?")}","gpu":"${escJs((info?.gpu ?: "?").take(60))}","ram":"${escJs(info?.ram ?: "?")}","cores":${info?.cores ?: 0},"sdk":${info?.sdk ?: 0},"resolution":"${escJs(info?.resolution ?: "?")}","tier":"${escJs(deviceTier)}"},
  "grade": "$grade", "score": $score, "deviceGrade": "$deviceGrade", "deviceScore": $deviceScore, "duration": $duration,
  "fps": {"avg":$avgFps,"min":$minFps,"max":$maxFps,"p1":$p1,"p5":$p5,"p50":$p50,"p90":$p90,"p99":$p99,"stability":$stability},
  "frameTime": {"avg":${"%.2f".format(avgFrameTime)},"p99":${"%.2f".format(p99FrameTime)},"jank":$jank,"stutter":$stutter},
  "memory": {"peakMb":$peakMem}, "cpu": {"avg":$avgCpu,"max":$maxCpu},
  "temperature": {"cpuMax":${"%.1f".format(maxTempCpu)},"gpuMax":${"%.1f".format(maxTempGpu)}},
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
@media(max-width:768px){.container{padding:0 12px 32px}.report-header{padding:32px 20px 28px}.header-title{font-size:1.6rem}.header-meta{flex-direction:column;gap:4px}.meta-sep{display:none}.summary-grid{grid-template-columns:1fr}.summary-grade{padding:24px;border-right:none;border-bottom:1px solid rgba(148,163,184,0.06)}.summary-stats{grid-template-columns:repeat(2,1fr)}.metrics-grid{grid-template-columns:repeat(2,1fr)}.hw-grid{grid-template-columns:1fr}.hw-item{border-radius:0!important}.hw-item:first-child{border-radius:10px 10px 0 0!important}.hw-item:last-child{border-radius:0 0 10px 10px!important}.stats-row{gap:6px}.stat-pill{padding:6px 10px;font-size:12px}.grade-ring{width:110px;height:110px}.grade-letter{font-size:2.8rem}.fab-group{top:auto;bottom:16px;right:16px}}
@media(max-width:480px){.metrics-grid{grid-template-columns:1fr}.summary-stats{grid-template-columns:1fr 1fr}.nav-link{font-size:11px;padding:5px 8px}}
@media print{@page{size:A4;margin:12mm 10mm}body{background:#fff!important;color:#1e293b!important;font-size:11px!important;-webkit-print-color-adjust:exact;print-color-adjust:exact}.container{max-width:100%;padding:0}.fab-group,.topnav{display:none!important}.report-header{border-radius:0;padding:24px 16px 20px;page-break-after:avoid}.header-bg{background:linear-gradient(135deg,#f1f5f9,#e2e8f0)!important}.header-bg::after{display:none}.header-title{-webkit-text-fill-color:#1e293b!important;font-size:1.6rem}.header-badge{color:#475569;border-color:#cbd5e1;background:#f1f5f9!important}.header-pkg,.header-meta,.header-session{color:#64748b!important}.card,.card-summary{background:#fafafa!important;border:1px solid #e2e8f0!important;page-break-inside:avoid;box-shadow:none}.metric-card{background:#f8fafc!important;border:1px solid #e2e8f0!important}.card-header h2,.section-title{color:#1e293b!important}.card-desc,.hint{color:#64748b!important}.verdict{color:#475569!important}.summary-grade{background:rgba(0,0,0,0.02)!important}.grade-ring{background:none!important;border:4px solid var(--grade-color)}.grade-ring-inner{background:#fff!important}.stat-pill{background:#f1f5f9!important;border-color:#e2e8f0!important}.stat-pill-value,.summary-stat-value{color:#1e293b!important}.stat-pill-label,.summary-stat-label{color:#64748b!important}.hw-item{background:#f8fafc!important}.hw-label{color:#64748b!important}.hw-value{color:#1e293b!important}.data-table th{background:#f1f5f9!important;color:#475569!important}.data-table td{color:#1e293b!important;border-color:#e2e8f0!important}.good{color:#059669!important}.warn{color:#d97706!important}.bad{color:#dc2626!important}.chart-container{background:#f8fafc!important;border-color:#e2e8f0!important;height:220px}.grade-bar{background:#e2e8f0!important}.footer-logo{-webkit-text-fill-color:#475569!important}.report-footer{border-color:#e2e8f0!important}.report-footer p{color:#94a3b8!important}.expand-content{display:block!important}.expand-btn .expand-icon{transform:rotate(90deg)}.problem-critical{background:#fef2f2!important;border-color:#fecaca!important;color:#991b1b!important}.problem-warning{background:#fffbeb!important;border-color:#fde68a!important;color:#92400e!important}.problem-info{background:#eff6ff!important;border-color:#bfdbfe!important;color:#1e40af!important}.status-ok{background:#f0fdf4!important;border-color:#86efac!important;color:#166534!important}.method-item{background:#f8fafc!important}.method-label{color:#475569!important}.method-value{color:#64748b!important}.summary-info,.summary-stat{background:transparent!important}}
""".trimIndent()
}
