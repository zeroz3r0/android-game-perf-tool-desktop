package com.gameperf.desktop.report

import com.gameperf.desktop.core.AdbBridge
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

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
        deviceGrade: Char = ' ', deviceScore: Int = 0, deviceTier: String = ""
    ): String {
        val dir = File(System.getProperty("user.home"), "GamePerf Reports")
        dir.mkdirs()
        val date = SimpleDateFormat("yyyy-MM-dd_HHmm").format(Date())
        val safePkg = pkg.replace(".", "_").takeLast(30)
        val deviceName = (info?.model ?: "Unknown").replace(" ", "_")
        val file = File(dir, "informe_${safePkg}_${deviceName}_$date.html")

        val gc = gradeColor(grade)
        val dateDisplay = SimpleDateFormat("dd/MM/yyyy HH:mm").format(Date())
        val durStr = "${duration/60}m ${duration%60}s"
        val batteryDrain = batteryStart - batteryEnd
        val stability = if (avgFps > 0 && fpsHistory.size > 1) {
            val range = (maxFps - minFps).toDouble()
            ((1 - range / avgFps / 2).coerceIn(0.0, 1.0) * 100).toInt()
        } else 100

        // Chart data
        val fpsD = fpsHistory.joinToString(",")
        val fpsL = fpsHistory.indices.joinToString(",") { "\"${it+1}s\"" }
        val memD = memHistory.joinToString(",")
        val natD = nativeHistory.joinToString(",")
        val javD = javaHistory.joinToString(",")
        val memL = memHistory.indices.joinToString(",") { "\"${it+1}s\"" }
        val cpuD = cpuHistory.joinToString(",")
        val tcD = tempCpuHistory.joinToString(",") { "%.0f".format(it) }
        val tgD = tempGpuHistory.joinToString(",") { "%.0f".format(it) }
        val tsD = tempSkinHistory.joinToString(",") { "%.0f".format(it) }
        val tL = (1..maxOf(cpuHistory.size, tempCpuHistory.size, 1)).joinToString(",") { "\"${it}s\"" }

        // Frame time histogram
        val ftBuckets = listOf(
            allFrameTimes.count { it < 8.0 },
            allFrameTimes.count { it in 8.0..16.66 },
            allFrameTimes.count { it in 16.67..33.32 },
            allFrameTimes.count { it in 33.33..49.99 },
            allFrameTimes.count { it in 50.0..99.99 },
            allFrameTimes.count { it >= 100.0 }
        ).joinToString(",")

        val problemsHtml = if (problems.isEmpty()) {
            """<div class="ok-box"><span class="ok-icon">&#10003;</span> Sin problemas criticos detectados. Rendimiento optimo.</div>"""
        } else {
            problems.joinToString("") { p -> """<div class="problem-box"><span class="problem-icon">&#9888;</span> $p</div>""" }
        }

        val html = """<!DOCTYPE html>
<html lang="es">
<head>
<meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1.0">
<title>Informe de Rendimiento - ${esc(pkg)}</title>
<script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
<script src="https://cdn.jsdelivr.net/npm/chartjs-plugin-annotation"></script>
<style>
${CSS}
</style>
</head>
<body>
<div class="container">

<!-- PDF Button -->
<button class="pdf-btn" onclick="window.print()">&#128196; Descargar PDF</button>

<!-- Header -->
<div class="header">
    <h1>Informe de Rendimiento</h1>
    <p class="sub">${esc(pkg)}</p>
    <p class="sub">${dateDisplay} | ${durStr} | ${if(isWifi) "WiFi (bateria real)" else "USB"}</p>
    <div class="grade-big" style="color:$gc;text-shadow:0 0 60px $gc">${grade}</div>
    <p class="grade-label">Puntuacion: $score / 100</p>
    ${if(deviceGrade != ' ') """
    <div style="margin-top:16px;padding:16px;background:rgba(123,44,191,0.1);border-radius:12px;border:1px solid rgba(123,44,191,0.3);display:inline-block">
        <p style="color:#888;font-size:12px;margin-bottom:4px">Nota ajustada al dispositivo ($deviceTier)</p>
        <span style="font-size:4rem;font-weight:900;color:${gradeColor(deviceGrade)}">$deviceGrade</span>
        <p style="color:#888;font-size:12px;margin-top:4px">Puntuacion: $deviceScore / 100</p>
    </div>""" else ""}
</div>

<!-- Device -->
<div class="card">
    <h2>&#128241; Dispositivo</h2>
    <div class="grid-2">
        <div class="stat"><span class="sl">Modelo</span><span class="sv">${esc(info?.model ?: "?")}</span></div>
        <div class="stat"><span class="sl">Fabricante</span><span class="sv">${esc(info?.manufacturer ?: "?")}</span></div>
        <div class="stat"><span class="sl">CPU</span><span class="sv">${esc(info?.cpu ?: "?")}</span></div>
        <div class="stat"><span class="sl">GPU</span><span class="sv">${esc((info?.gpu ?: "?").take(50))}</span></div>
        <div class="stat"><span class="sl">RAM</span><span class="sv">${info?.ram ?: "?"}</span></div>
        <div class="stat"><span class="sl">Cores</span><span class="sv">${info?.cores ?: "?"}</span></div>
        <div class="stat"><span class="sl">SDK</span><span class="sv">${info?.sdk ?: "?"}</span></div>
        <div class="stat"><span class="sl">Resolucion</span><span class="sv">${esc(info?.resolution ?: "?")}</span></div>
    </div>
</div>

<!-- Session -->
<div class="card">
    <h2>&#128337; Sesion</h2>
    <div class="grid-2">
        <div class="stat"><span class="sl">Duracion</span><span class="sv">$durStr</span></div>
        <div class="stat"><span class="sl">Muestras FPS</span><span class="sv">${fpsHistory.size}</span></div>
        <div class="stat"><span class="sl">Conexion</span><span class="sv">${if(isWifi) "WiFi (bateria real)" else "USB"}</span></div>
        <div class="stat"><span class="sl">Frame drops</span><span class="sv ${cls(frameDrops,30,10)}">$frameDrops</span></div>
    </div>
</div>

<!-- FPS -->
<div class="card">
    <h2>&#127918; FPS (Frames por segundo)</h2>
    <p class="card-desc">Medido desde SurfaceFlinger con ventana temporal de 1 segundo. Objetivo: 60 FPS estable.</p>
    <table class="ptable">
        <tr><th>P1</th><th>P5</th><th>P50</th><th>P90</th><th>P99</th><th>Min</th><th>Max</th><th>Avg</th><th>Estabilidad</th></tr>
        <tr>
            <td class="${cls(p1,20,30,"r")}">${p1}</td>
            <td class="${cls(p5,25,35,"r")}">${p5}</td>
            <td>${p50}</td><td>${p90}</td><td>${p99}</td>
            <td class="bad">${minFps}</td><td class="good">${maxFps}</td>
            <td class="avg-cell" style="color:$gc">${avgFps}</td>
            <td class="${if(stability<70)"warn" else "good"}">${stability}%</td>
        </tr>
    </table>
    <p class="hint">P1 = el peor 1% de lecturas. Si P1 es bajo, hay tirones puntuales aunque el promedio sea bueno.</p>
    <div class="chart-box"><canvas id="fpsChart"></canvas></div>
</div>

<!-- Frame Times -->
<div class="card">
    <h2>&#9201; Frame Times</h2>
    <p class="card-desc">Tiempo que tarda cada frame en renderizarse. Menos = mejor. >16.67ms = debajo de 60fps.</p>
    <div class="grid-2">
        <div class="stat"><span class="sl">Promedio</span><span class="sv">${"%.1f".format(avgFrameTime)}ms</span></div>
        <div class="stat"><span class="sl">P99 (peor caso)</span><span class="sv ${cls(p99FrameTime.toInt(),50,17,"r")}">${"%.1f".format(p99FrameTime)}ms</span></div>
        <div class="stat"><span class="sl">Jank (>16ms)</span><span class="sv warn">${jank}</span></div>
        <div class="stat"><span class="sl">Stutter (>100ms)</span><span class="sv bad">${stutter}</span></div>
    </div>
    <div class="chart-box"><canvas id="ftChart"></canvas></div>
</div>

<!-- Memory -->
<div class="card">
    <h2>&#128190; Memoria</h2>
    <p class="card-desc">Total PSS: memoria real usada por el juego. Native Heap: texturas, meshes (C++). Java Heap: logica del juego.</p>
    <div class="grid-2">
        <div class="stat"><span class="sl">Inicio</span><span class="sv">${memHistory.firstOrNull() ?: "?"}MB</span></div>
        <div class="stat"><span class="sl">Final</span><span class="sv">${memHistory.lastOrNull() ?: "?"}MB</span></div>
        <div class="stat"><span class="sl">Pico</span><span class="sv ${cls(peakMem.toInt(),2000,1500)}">${peakMem}MB</span></div>
        <div class="stat"><span class="sl">Crecimiento</span><span class="sv">${if(memHistory.size>=2) "${memHistory.last()-memHistory.first()}MB" else "?"}MB</span></div>
    </div>
    <div class="chart-box"><canvas id="memChart"></canvas></div>
</div>

<!-- CPU -->
<div class="card">
    <h2>&#9881; CPU y GPU</h2>
    <div class="grid-2">
        <div class="stat"><span class="sl">CPU promedio</span><span class="sv ${cls(avgCpu,85,70)}">${avgCpu}%</span></div>
        <div class="stat"><span class="sl">CPU maximo</span><span class="sv">${maxCpu}%</span></div>
    </div>
    <div class="chart-box"><canvas id="cpuChart"></canvas></div>
</div>

<!-- Temperature -->
<div class="card">
    <h2>&#127777; Temperatura</h2>
    <p class="card-desc">Por encima de ~42C se activa el thermal throttling que reduce CPU/GPU automaticamente.</p>
    <div class="grid-2">
        <div class="stat"><span class="sl">CPU max</span><span class="sv ${cls(maxTempCpu.toInt(),45,40)}">${if(maxTempCpu>0) "${maxTempCpu.toInt()}C" else "N/A"}</span></div>
        <div class="stat"><span class="sl">GPU max</span><span class="sv">${if(maxTempGpu>0) "${maxTempGpu.toInt()}C" else "N/A"}</span></div>
    </div>
    <div class="chart-box"><canvas id="tempChart"></canvas></div>
</div>

<!-- Battery -->
<div class="card">
    <h2>&#128267; Bateria</h2>
    <div class="grid-2">
        <div class="stat"><span class="sl">Inicio</span><span class="sv">${batteryStart}%</span></div>
        <div class="stat"><span class="sl">Final</span><span class="sv">${batteryEnd}%</span></div>
        <div class="stat"><span class="sl">Consumo</span><span class="sv ${cls(batteryDrain,10,5)}">${batteryDrain}%</span></div>
        <div class="stat"><span class="sl">Consumo/min</span><span class="sv">${if(duration>0) "%.1f".format(batteryDrain.toDouble()/(duration/60.0)) else "0"}%</span></div>
    </div>
    ${if(!isWifi) "<p class='hint'>Nota: medido con USB conectado. Para consumo real de bateria, usa modo WiFi.</p>" else "<p class='hint good'>Medido via WiFi - consumo real de bateria sin carga USB.</p>"}
</div>

<!-- Problems -->
<div class="card ${if(problems.isNotEmpty()) "card-red" else ""}">
    <h2>${if(problems.isEmpty()) "&#9989;" else "&#9888;"} Problemas detectados</h2>
    $problemsHtml
</div>

<!-- Grade Breakdown -->
<div class="card">
    <h2>&#127942; Desglose de la nota</h2>
    <p class="card-desc">Puntuacion base: 100 puntos. Se restan penalizaciones por problemas de rendimiento.</p>
    <div class="grade-bar">
        <div class="grade-fill" style="width:${score.coerceIn(0,100)}%;background:$gc"></div>
    </div>
    <div class="grade-labels"><span>0</span><span>F</span><span>D</span><span>C</span><span>B</span><span>A</span><span>100</span></div>
    <div class="stat"><span class="sl">Puntuacion final</span><span class="sv" style="color:$gc;font-size:1.4em">${score} / 100 = ${grade}</span></div>
</div>

<!-- Methodology -->
<div class="card card-dim">
    <h2>&#128218; Metodologia</h2>
    <p class="card-desc">Como se recopilan y calculan las metricas de este informe.</p>
    <div class="stat"><span class="sl">FPS</span><span class="sv small">SurfaceFlinger --latency, ventana 1s, filtro IQR</span></div>
    <div class="stat"><span class="sl">Frame Times</span><span class="sv small">Delta entre timestamps de presentacion</span></div>
    <div class="stat"><span class="sl">Memoria</span><span class="sv small">dumpsys meminfo (PSS + Native + Java)</span></div>
    <div class="stat"><span class="sl">CPU</span><span class="sv small">/proc/stat delta entre muestras</span></div>
    <div class="stat"><span class="sl">Temperatura</span><span class="sv small">dumpsys thermalservice + thermal zones</span></div>
    <div class="stat"><span class="sl">Bateria</span><span class="sv small">dumpsys battery${if(isWifi) " (WiFi, sin carga)" else " (USB charging disabled)"}</span></div>
    <div class="stat"><span class="sl">Nota</span><span class="sv small">Base 100 - penalizaciones (FPS/P1/problemas)</span></div>
</div>

<footer>
    <p>Game Performance Tool v1.0.0</p>
    <p>Informe generado: ${SimpleDateFormat("dd/MM/yyyy HH:mm:ss").format(Date())}</p>
</footer>

</div>

<script>
const D={responsive:true,maintainAspectRatio:false,plugins:{legend:{labels:{color:'#999',font:{size:11}}},tooltip:{mode:'index',intersect:false}},scales:{x:{ticks:{color:'#666',maxTicksLimit:15,font:{size:10}},grid:{color:'rgba(255,255,255,0.04)'}},y:{ticks:{color:'#888',font:{size:11}},grid:{color:'rgba(255,255,255,0.06)'}}}};
${if(fpsD.isNotEmpty()) """
new Chart(document.getElementById('fpsChart'),{type:'line',data:{labels:[$fpsL],datasets:[{label:'FPS',data:[$fpsD],borderColor:'#00d4ff',backgroundColor:'rgba(0,212,255,0.08)',fill:true,tension:0.3,pointRadius:1,borderWidth:2}]},options:{...D,plugins:{...D.plugins,annotation:{annotations:{l30:{type:'line',yMin:30,yMax:30,borderColor:'rgba(255,0,68,0.5)',borderWidth:1,borderDash:[5,5],label:{content:'30 FPS',display:true,color:'#ff0044',font:{size:9}}},l60:{type:'line',yMin:60,yMax:60,borderColor:'rgba(0,255,136,0.4)',borderWidth:1,borderDash:[5,5],label:{content:'60 FPS',display:true,color:'#00ff88',font:{size:9}}}}}}}});""" else ""}
${if(ftBuckets.isNotEmpty()) """
new Chart(document.getElementById('ftChart'),{type:'bar',data:{labels:['<8ms\\n(>120fps)','8-16ms\\n(60-120fps)','16-33ms\\n(30-60fps)','33-50ms\\n(20-30fps)','50-100ms\\n(<20fps)','>100ms\\n(stutter)'],datasets:[{label:'Frames',data:[$ftBuckets],backgroundColor:['#00ff88','#88ff00','#ffaa00','#ff6600','#ff0044','#cc0033'],borderRadius:4}]},options:{...D,plugins:{...D.plugins,legend:{display:false}}}});""" else ""}
${if(memD.isNotEmpty()) """
new Chart(document.getElementById('memChart'),{type:'line',data:{labels:[$memL],datasets:[{label:'Total PSS (MB)',data:[$memD],borderColor:'#00d4ff',tension:0.3,pointRadius:1,borderWidth:2},{label:'Native Heap (MB)',data:[$natD],borderColor:'#ff6600',tension:0.3,pointRadius:1,borderWidth:1.5},{label:'Java Heap (MB)',data:[$javD],borderColor:'#00ff88',tension:0.3,pointRadius:1,borderWidth:1.5}]},options:D});""" else ""}
${if(cpuD.isNotEmpty()) """
new Chart(document.getElementById('cpuChart'),{type:'line',data:{labels:[$tL],datasets:[{label:'CPU %',data:[$cpuD],borderColor:'#00d4ff',backgroundColor:'rgba(0,212,255,0.05)',fill:true,tension:0.3,pointRadius:1,borderWidth:2}]},options:{...D,scales:{...D.scales,y:{...D.scales.y,min:0,max:100}}}});""" else ""}
${if(tcD.isNotEmpty()) """
new Chart(document.getElementById('tempChart'),{type:'line',data:{labels:[$tL],datasets:[{label:'CPU',data:[$tcD],borderColor:'#ff0044',tension:0.3,pointRadius:1,borderWidth:2},{label:'GPU',data:[$tgD],borderColor:'#ff6600',tension:0.3,pointRadius:1,borderWidth:1.5},{label:'Skin',data:[$tsD],borderColor:'#ffaa00',tension:0.3,pointRadius:1,borderWidth:1.5}]},options:D});""" else ""}
</script>
</body></html>"""

        file.writeText(html)
        return file.absolutePath
    }

    private fun gradeColor(g: Char) = when(g) { 'A'->"#00ff88"; 'B'->"#88ff00"; 'C'->"#ffaa00"; 'D'->"#ff6600"; else->"#ff0044" }
    private fun cls(v: Int, bad: Int, warn: Int, dir: String = "n") =
        if (dir=="r") { if(v>bad) "bad" else if(v>warn) "warn" else "good" }
        else { if(v>bad) "bad" else if(v>warn) "warn" else "good" }
    private fun esc(s: String) = s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")

    private val CSS = """
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:linear-gradient(135deg,#0f0f1a 0%,#1a1a2e 50%,#16213e 100%);min-height:100vh;color:#fff;padding:24px;line-height:1.6;font-size:14px}
.container{max-width:900px;margin:0 auto}

.pdf-btn{position:fixed;top:16px;right:16px;background:#00d4ff;color:#000;border:none;padding:10px 20px;border-radius:10px;font-weight:700;font-size:14px;cursor:pointer;z-index:100;box-shadow:0 4px 15px rgba(0,212,255,0.3);transition:all 0.2s}
.pdf-btn:hover{transform:scale(1.05);box-shadow:0 4px 20px rgba(0,212,255,0.5)}

.header{text-align:center;margin-bottom:36px}
h1{background:linear-gradient(90deg,#00d4ff,#7b2cbf);-webkit-background-clip:text;-webkit-text-fill-color:transparent;font-size:2rem;margin-bottom:8px;letter-spacing:-0.5px}
.sub{color:#888;font-size:13px;margin:6px 0}
.grade-big{font-size:8rem;font-weight:900;margin:16px 0;line-height:1}
.grade-label{color:#888;font-size:14px}

.card{background:rgba(255,255,255,0.04);border-radius:16px;padding:24px;margin-bottom:20px;border:1px solid rgba(255,255,255,0.08)}
.card h2{color:#00d4ff;margin-bottom:16px;font-size:1.15rem;letter-spacing:-0.3px}
.card-desc{color:#777;font-size:0.82em;margin-bottom:16px;line-height:1.6}
.card-red{border-left:4px solid #ff0044}
.card-dim{opacity:0.7}
.hint{color:#666;font-size:0.75em;margin-top:10px;font-style:italic;line-height:1.5}

.grid-2{display:grid;grid-template-columns:1fr 1fr;gap:0 24px}
.stat{display:flex;justify-content:space-between;align-items:center;padding:10px 0;border-bottom:1px solid rgba(255,255,255,0.06);gap:12px}
.stat:last-child{border-bottom:none}
.sl{color:#888;font-size:13px;letter-spacing:0.2px}
.sv{font-weight:700;font-size:14px}
.sv.small{font-size:11px;color:#999;font-weight:400}

.good{color:#00ff88}.warn{color:#ffaa00}.bad{color:#ff0044}

.ptable{width:100%;border-collapse:collapse;margin:14px 0}
.ptable td,.ptable th{padding:10px 14px;text-align:center;border:1px solid rgba(255,255,255,0.08)}
.ptable th{color:#00d4ff;font-size:0.8em;font-weight:600;letter-spacing:0.5px;text-transform:uppercase}
.ptable td{font-weight:700;font-size:1.05em}
.avg-cell{font-size:1.4em}

.chart-box{height:260px;background:rgba(0,0,0,0.25);border-radius:12px;padding:12px;margin-top:14px}

.problem-box{background:rgba(255,0,68,0.06);border-left:3px solid #ff0044;padding:16px 18px;margin:10px 0;border-radius:10px;color:#ff8888;font-size:14px;line-height:1.6}
.problem-icon{margin-right:10px}
.ok-box{background:rgba(0,255,136,0.06);border-left:3px solid #00ff88;padding:16px 18px;border-radius:10px;color:#00ff88;font-size:14px;line-height:1.6}
.ok-icon{margin-right:10px}

.grade-bar{height:12px;background:rgba(255,255,255,0.1);border-radius:6px;margin:14px 0;overflow:hidden}
.grade-fill{height:100%;border-radius:6px;transition:width 0.5s}
.grade-labels{display:flex;justify-content:space-between;color:#666;font-size:10px;margin-bottom:14px}

footer{text-align:center;color:#444;font-size:0.72em;padding:32px 0 12px;border-top:1px solid rgba(255,255,255,0.04);margin-top:36px}
footer p{margin:4px 0}

@media print{
  body{background:#fff!important;color:#000!important;padding:10px!important;font-size:12px!important}
  .container{max-width:100%}
  .pdf-btn{display:none!important}
  .card{border:1px solid #ddd!important;background:#fafafa!important;page-break-inside:avoid}
  .grade-big{text-shadow:none!important}
  h1{background:none!important;-webkit-text-fill-color:#333!important}
  .sub,.sl,.card-desc,.hint{color:#666!important}
  .sv{color:#000!important}
  .card h2{color:#0066cc!important}
  .good{color:#008800!important}.warn{color:#cc6600!important}.bad{color:#cc0000!important}
  .chart-box{background:#f5f5f5!important}
  footer{color:#999!important}
  .grade-bar{background:#eee!important}
}
""".trimIndent()
}
