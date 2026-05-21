package com.gameperf.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gameperf.desktop.core.SessionHistory
import com.gameperf.desktop.ui.components.ExportBanner
import com.gameperf.desktop.ui.theme.*
import com.gameperf.desktop.ui.util.fmtUS
import com.gameperf.desktop.viewmodel.AppViewModel

/**
 * Comparison screen: side-by-side metric comparison between our game and competitor sessions.
 * Shows a detailed table with color-coded cells + a visual radar/bar chart section.
 */
@Composable
fun ComparisonScreen(vm: AppViewModel) {
    val entries = remember { vm.getSelectedEntries() }
    val oursEntries = entries.filter { it.tag == SessionHistory.SessionTag.OUR_GAME }
    val compEntries = entries.filter { it.tag == SessionHistory.SessionTag.COMPETITION }

    val exportStatus by vm.exportStatus.collectAsState()
    // The comparison HTML path is generated lazily on demand. We remember it so the
    // "Exportar comparativa a PDF" button can hand it to the ViewModel, and so that
    // multiple PDF exports of the same comparison reuse the same source.
    var lastComparisonPath by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp)
    ) {
        // ═══════ PDF EXPORT BANNER ═══════
        ExportBanner(
            status = exportStatus,
            onDismiss = { vm.resetExportStatus() },
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.goHome() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver", tint = Cyan)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "Comparativa de rendimiento",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Cyan
            )
        }
        Text(
            "Nuestro juego vs Competencia",
            color = TextSecondary, fontSize = 13.sp,
            modifier = Modifier.padding(start = 48.dp)
        )
        Spacer(Modifier.height(24.dp))

        // Device match warning
        val allDevices = entries.map { it.deviceModel }.distinct()
        if (allDevices.size > 1) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Yellow.copy(alpha = 0.1f)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, null, tint = Yellow, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Las sesiones usan dispositivos diferentes (${allDevices.joinToString(", ")}). Para una comparación justa, usa el mismo dispositivo.",
                        color = Yellow, fontSize = 12.sp
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // === Comparison Table ===
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkCard),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("Tabla comparativa", color = Cyan, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    // Metric labels column
                    Column {
                        HeaderCell("Metrica", Modifier.width(160.dp))
                        MetricLabelCell("Juego")
                        MetricLabelCell("Dispositivo")
                        MetricLabelCell("FPS Promedio")
                        MetricLabelCell("P1 FPS")
                        MetricLabelCell("P5 FPS")
                        MetricLabelCell("Frame Time Avg")
                        MetricLabelCell("P95 Frame Time")
                        MetricLabelCell("P99 Frame Time")
                        MetricLabelCell("Memoria Pico")
                        MetricLabelCell("CPU Promedio")
                        MetricLabelCell("Temp Max")
                        MetricLabelCell("Nota (A-F)")
                        MetricLabelCell("Puntuacion")
                        MetricLabelCell("Duracion")
                    }

                    // Data columns for each session
                    entries.forEach { entry ->
                        val isOurs = entry.tag == SessionHistory.SessionTag.OUR_GAME
                        val borderColor = if (isOurs) Cyan else Orange

                        Column(
                            modifier = Modifier
                                .width(150.dp)
                                .border(1.dp, borderColor.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                        ) {
                            // Header: session type
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(borderColor.copy(alpha = 0.15f))
                                    .padding(8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    if (isOurs) "NUESTRO" else entry.competitorName.ifEmpty { "COMP" },
                                    color = borderColor, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                                    textAlign = TextAlign.Center
                                )
                            }

                            // Data cells
                            DataCell(entry.gamePackage.substringAfterLast('.'))
                            DataCell(entry.deviceModel)

                            // FPS - higher is better
                            ComparisonCell(entry.avgFps.toDouble(), entries, { it.avgFps.toDouble() }, higher = true, entry, "${entry.avgFps}")
                            ComparisonCell(entry.p1Fps.toDouble(), entries, { it.p1Fps.toDouble() }, higher = true, entry, "${entry.p1Fps}")
                            ComparisonCell(entry.p5Fps.toDouble(), entries, { it.p5Fps.toDouble() }, higher = true, entry, "${entry.p5Fps}")

                            // Frame time - lower is better
                            ComparisonCell(entry.avgFrameTime, entries, { it.avgFrameTime }, higher = false, entry, "${fmtUS("%.1f", entry.avgFrameTime)}ms")
                            ComparisonCell(entry.p95FrameTime, entries, { it.p95FrameTime }, higher = false, entry, "${fmtUS("%.1f", entry.p95FrameTime)}ms")
                            ComparisonCell(entry.p99FrameTime, entries, { it.p99FrameTime }, higher = false, entry, "${fmtUS("%.1f", entry.p99FrameTime)}ms")

                            // Memory - lower is better
                            ComparisonCell(entry.peakMemMb.toDouble(), entries, { it.peakMemMb.toDouble() }, higher = false, entry, "${entry.peakMemMb}MB")

                            // CPU - lower is better
                            ComparisonCell(entry.avgCpu.toDouble(), entries, { it.avgCpu.toDouble() }, higher = false, entry, "${entry.avgCpu}%")

                            // Temp - lower is better
                            ComparisonCell(entry.maxTemp, entries, { it.maxTemp }, higher = false, entry, "${entry.maxTemp.toInt()}C")

                            // Grade - higher letter is better
                            val gradeVal = gradeToNumeric(entry.grade)
                            ComparisonCell(gradeVal, entries, { gradeToNumeric(it.grade) }, higher = true, entry, "${entry.grade}")

                            // Score - higher is better
                            ComparisonCell(entry.score.toDouble(), entries, { it.score.toDouble() }, higher = true, entry, "${entry.score}/100")

                            // Duration - neutral
                            DataCell("${entry.duration / 60}m ${entry.duration % 60}s")
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // === Summary ===
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkCard),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("Resumen", color = Cyan, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(12.dp))

                if (oursEntries.isNotEmpty() && compEntries.isNotEmpty()) {
                    val ours = oursEntries.first()
                    val comp = compEntries.first()

                    data class MetricComparison(val name: String, val oursVal: Double, val compVal: Double, val higherBetter: Boolean)

                    val metrics = listOf(
                        MetricComparison("FPS Promedio", ours.avgFps.toDouble(), comp.avgFps.toDouble(), true),
                        MetricComparison("P1 FPS", ours.p1Fps.toDouble(), comp.p1Fps.toDouble(), true),
                        MetricComparison("Frame Time", ours.avgFrameTime, comp.avgFrameTime, false),
                        MetricComparison("Memoria Pico", ours.peakMemMb.toDouble(), comp.peakMemMb.toDouble(), false),
                        MetricComparison("CPU", ours.avgCpu.toDouble(), comp.avgCpu.toDouble(), false),
                        MetricComparison("Temperatura", ours.maxTemp, comp.maxTemp, false),
                        MetricComparison("Nota", gradeToNumeric(ours.grade), gradeToNumeric(comp.grade), true)
                    )

                    val weWin = metrics.count { m ->
                        if (m.higherBetter) m.oursVal > m.compVal else m.oursVal < m.compVal
                    }
                    val theyWin = metrics.count { m ->
                        if (m.higherBetter) m.compVal > m.oursVal else m.compVal < m.oursVal
                    }
                    val competitorLabel = comp.competitorName.ifEmpty { comp.gamePackage.substringAfterLast('.') }

                    // Scoreboard
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Nuestro juego", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("$weWin", color = if (weWin > theyWin) Green else if (weWin < theyWin) Red else Yellow,
                                fontSize = 48.sp, fontWeight = FontWeight.Bold)
                            Text("métricas ganadas", color = TextDim, fontSize = 10.sp)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(competitorLabel, color = Orange, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "$theyWin",
                                color = if (theyWin > weWin) Green else if (theyWin < weWin) Red else Yellow,
                                fontSize = 48.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text("métricas ganadas", color = TextDim, fontSize = 10.sp)
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Per-metric breakdown
                    metrics.forEach { m ->
                        val weAreBetter = if (m.higherBetter) m.oursVal > m.compVal else m.oursVal < m.compVal
                        val areTied = m.oursVal == m.compVal
                        val icon = when {
                            areTied -> Icons.Default.DragHandle
                            weAreBetter -> Icons.Default.ThumbUp
                            else -> Icons.Default.ThumbDown
                        }
                        val color = when {
                            areTied -> Yellow
                            weAreBetter -> Green
                            else -> Red
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .background(color.copy(alpha = 0.05f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(m.name, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text(
                                when {
                                    areTied -> "Empate"
                                    weAreBetter -> "Ganamos"
                                    else -> "Competencia gana"
                                },
                                color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // === Visual Comparison (Bar chart style in Compose) ===
        if (oursEntries.isNotEmpty() && compEntries.isNotEmpty()) {
            val ours = oursEntries.first()
            val comp = compEntries.first()

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("Comparativa visual", color = Cyan, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("Barras normalizadas al mejor valor de cada métrica", color = TextDim, fontSize = 11.sp)
                    Spacer(Modifier.height(16.dp))

                    // Legend
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).clip(RoundedCornerShape(2.dp)).background(Cyan))
                        Spacer(Modifier.width(6.dp))
                        Text("Nuestro juego", color = TextSecondary, fontSize = 11.sp)
                        Spacer(Modifier.width(16.dp))
                        Box(Modifier.size(12.dp).clip(RoundedCornerShape(2.dp)).background(Orange))
                        Spacer(Modifier.width(6.dp))
                        Text(comp.competitorName.ifEmpty { "Competencia" }, color = TextSecondary, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(16.dp))

                    // Normalized bars for key metrics
                    data class BarData(val label: String, val oursVal: Double, val compVal: Double, val higherBetter: Boolean, val unit: String)

                    val bars = listOf(
                        BarData("FPS Avg", ours.avgFps.toDouble(), comp.avgFps.toDouble(), true, ""),
                        BarData("P1 FPS", ours.p1Fps.toDouble(), comp.p1Fps.toDouble(), true, ""),
                        BarData("Estabilidad", 100.0 - ours.avgFrameTime.coerceIn(0.0, 100.0), 100.0 - comp.avgFrameTime.coerceIn(0.0, 100.0), true, ""),
                        BarData("Memoria", ours.peakMemMb.toDouble(), comp.peakMemMb.toDouble(), false, "MB"),
                        BarData("CPU", ours.avgCpu.toDouble(), comp.avgCpu.toDouble(), false, "%"),
                        BarData("Termica", ours.maxTemp, comp.maxTemp, false, "C")
                    )

                    bars.forEach { bar ->
                        val maxVal = maxOf(bar.oursVal, bar.compVal).coerceAtLeast(1.0)
                        // For "lower is better" metrics, invert the bar so better = longer
                        val oursPct = if (bar.higherBetter) bar.oursVal / maxVal else (maxVal - bar.oursVal + 1) / maxVal
                        val compPct = if (bar.higherBetter) bar.compVal / maxVal else (maxVal - bar.compVal + 1) / maxVal

                        Column(Modifier.padding(vertical = 6.dp)) {
                            Text(bar.label, color = TextSecondary, fontSize = 11.sp)
                            Spacer(Modifier.height(4.dp))

                            // Our bar
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(16.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xFF0D1117))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .fillMaxWidth(oursPct.toFloat().coerceIn(0.02f, 1f))
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Cyan)
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "${if (bar.higherBetter) bar.oursVal.toInt() else bar.oursVal.toInt()}${bar.unit}",
                                    color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(60.dp)
                                )
                            }
                            Spacer(Modifier.height(2.dp))

                            // Competitor bar
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(16.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xFF0D1117))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .fillMaxWidth(compPct.toFloat().coerceIn(0.02f, 1f))
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Orange)
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "${if (bar.higherBetter) bar.compVal.toInt() else bar.compVal.toInt()}${bar.unit}",
                                    color = Orange, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(60.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = { vm.goHome() },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Volver")
            }
            Button(
                onClick = {
                    val path = vm.generateComparisonReport(entries)
                    if (path.isNotEmpty()) {
                        lastComparisonPath = path
                        try {
                            if (java.awt.Desktop.isDesktopSupported()) {
                                java.awt.Desktop.getDesktop().open(java.io.File(path))
                            }
                        } catch (_: Exception) {}
                    }
                },
                modifier = Modifier.weight(1f).height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Purple),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Description, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Generar informe comparativo", fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(12.dp))

        // PDF export action — separate row so it does not visually compete with
        // the primary "generate" button. Generates the HTML on demand if the user
        // hasn't done so yet, then forwards the path to the ViewModel.
        val exportingNow = exportStatus is com.gameperf.desktop.viewmodel.ExportDelegate.ExportStatus.InProgress
        Button(
            onClick = {
                // If the user clicks PDF without having generated the HTML first,
                // generate it now so the export has a source. The path is also
                // tracked in the ViewModel's _tempComparisons for cleanup on close.
                val path = if (lastComparisonPath.isNotEmpty() && java.io.File(lastComparisonPath).exists()) {
                    lastComparisonPath
                } else {
                    val fresh = vm.generateComparisonReport(entries)
                    if (fresh.isNotEmpty()) lastComparisonPath = fresh
                    fresh
                }
                if (path.isNotEmpty()) {
                    vm.exportComparisonToPdf(path)
                }
            },
            enabled = !exportingNow && entries.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Cyan,
                disabledContainerColor = TextDim.copy(alpha = 0.3f)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.PictureAsPdf, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Exportar comparativa a PDF", fontWeight = FontWeight.Bold)
        }
    }
}

// ===== Helper Composables =====

@Composable
private fun HeaderCell(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(DarkSurface)
            .padding(8.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(text, color = Cyan, fontWeight = FontWeight.Bold, fontSize = 11.sp)
    }
}

@Composable
private fun MetricLabelCell(text: String) {
    Box(
        modifier = Modifier
            .width(160.dp)
            .height(36.dp)
            .background(DarkSurface.copy(alpha = 0.5f))
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(text, color = TextSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun DataCell(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

/**
 * A data cell that is color-coded based on how this entry's value compares to others.
 * Green = we're the best, Red = we're the worst, white = neutral/tied.
 */
@Composable
private fun ComparisonCell(
    value: Double,
    allEntries: List<SessionHistory.HistoryEntry>,
    extractor: (SessionHistory.HistoryEntry) -> Double,
    higher: Boolean,
    currentEntry: SessionHistory.HistoryEntry,
    displayText: String
) {
    val allValues = allEntries.map { extractor(it) }
    val best = if (higher) allValues.maxOrNull() else allValues.minOrNull()
    val worst = if (higher) allValues.minOrNull() else allValues.maxOrNull()

    val isOurs = currentEntry.tag == SessionHistory.SessionTag.OUR_GAME
    val isBest = value == best && allValues.count { it == best } < allValues.size // not all equal
    val isWorst = value == worst && allValues.count { it == worst } < allValues.size

    val bgColor = when {
        isBest && isOurs -> Green.copy(alpha = 0.12f)
        isWorst && isOurs -> Red.copy(alpha = 0.12f)
        isBest && !isOurs -> Red.copy(alpha = 0.08f) // competitor beats us — red bg for them too as warning
        isWorst && !isOurs -> Green.copy(alpha = 0.08f)
        else -> Color.Transparent
    }
    val textColor = when {
        isBest -> Green
        isWorst -> Red
        else -> Color.White
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(bgColor)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(displayText, color = textColor, fontSize = 12.sp, fontWeight = if (isBest) FontWeight.Bold else FontWeight.Normal)
            if (isBest) {
                Spacer(Modifier.width(4.dp))
                Text("*", color = Green, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun gradeToNumeric(grade: Char): Double = when (grade) {
    'A' -> 5.0; 'B' -> 4.0; 'C' -> 3.0; 'D' -> 2.0; else -> 1.0
}
