package com.gameperf.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gameperf.desktop.ui.components.MetricCard
import com.gameperf.desktop.ui.components.MiniGraph
import com.gameperf.desktop.ui.components.MiniGraphWithMarkers
import com.gameperf.desktop.ui.components.StatRow
import com.gameperf.desktop.ui.components.VideoPlayer
import com.gameperf.desktop.ui.theme.*
import com.gameperf.desktop.viewmodel.AppViewModel
import com.gameperf.desktop.viewmodel.MarkerType
import com.gameperf.desktop.viewmodel.SessionMarker

@Composable
fun ResultsScreen(vm: AppViewModel) {
    val result by vm.result.collectAsState()
    val metrics by vm.liveMetrics.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Dual grade display
        Text("Resultado", color = TextSecondary, fontSize = 14.sp)
        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // General grade
            Card(
                modifier = Modifier.width(200.dp),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    Modifier.padding(20.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("General", color = TextSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${result.grade}",
                        color = gradeColor(result.grade),
                        fontSize = 72.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (result.deviceGrade != ' ') {
                Spacer(Modifier.width(24.dp))
                Card(
                    modifier = Modifier.width(200.dp),
                    colors = CardDefaults.cardColors(containerColor = Purple.copy(alpha = 0.1f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        Modifier.padding(20.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Dispositivo", color = TextSecondary, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${result.deviceGrade}",
                            color = gradeColor(result.deviceGrade),
                            fontSize = 72.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(result.deviceTier, color = TextDim, fontSize = 10.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "${result.gamePackage}  |  ${result.deviceModel}  |  ${formatDuration(result.duration)}",
            color = TextDim, fontSize = 12.sp
        )
        Spacer(Modifier.height(24.dp))

        // Key metrics - row 1
        val fpsColor = when {
            result.avgFps >= 55 -> Green
            result.avgFps >= 30 -> Yellow
            else -> Red
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricCard("FPS Promedio", "${result.avgFps}", fpsColor,
                subtitle = "Min: ${result.minFps} / Max: ${result.maxFps}", modifier = Modifier.weight(1f))
            MetricCard("P1 FPS", "${result.p1Fps}",
                if (result.p1Fps < 20) Red else if (result.p1Fps < 30) Yellow else Green,
                subtitle = "Peor 1% de frames", modifier = Modifier.weight(1f))
            MetricCard("Frame Time", "${"%.1f".format(result.avgFrameTime)}ms", Cyan,
                modifier = Modifier.weight(1f))
        }

        Spacer(Modifier.height(8.dp))

        // Key metrics - row 2
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricCard("CPU", "${result.avgCpu}%",
                if (result.avgCpu > 85) Red else if (result.avgCpu > 70) Yellow else Green,
                modifier = Modifier.weight(1f))
            MetricCard("Memoria Pico", "${result.peakMemMb}MB",
                if (result.peakMemMb > 2000) Red else Cyan,
                modifier = Modifier.weight(1f))
            MetricCard("Temp Max", "${result.maxTempCpu.toInt()}C",
                if (result.maxTempCpu > 45) Red else if (result.maxTempCpu > 40) Yellow else Green,
                modifier = Modifier.weight(1f))
        }

        Spacer(Modifier.height(8.dp))

        // Key metrics - row 3
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricCard("Bateria", "${result.batteryDrain}%",
                if (result.batteryDrain > 10) Red else Green,
                subtitle = "consumo", modifier = Modifier.weight(1f))
            MetricCard("Frame Drops", "${result.frameDrops}",
                if (result.frameDrops > 30) Red else if (result.frameDrops > 10) Yellow else Green,
                modifier = Modifier.weight(1f))
            // Spacer card for alignment
            Spacer(Modifier.weight(1f))
        }

        Spacer(Modifier.height(16.dp))

        // Graphs — FPS graph with marker overlays
        if (result.markers.isNotEmpty()) {
            MiniGraphWithMarkers(
                label = "FPS con marcadores",
                values = metrics.fpsHistory,
                color = Cyan,
                maxValue = 65f,
                markers = result.markers,
                totalSeconds = result.duration,
                modifier = Modifier.fillMaxWidth().height(180.dp)
            )
            Spacer(Modifier.height(8.dp))
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().height(140.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MiniGraph("FPS", metrics.fpsHistory, Cyan, maxValue = 65f, modifier = Modifier.weight(1f).fillMaxHeight())
                MiniGraph("Memoria (MB)", metrics.memHistory, Purple, modifier = Modifier.weight(1f).fillMaxHeight())
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(140.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (result.markers.isNotEmpty()) {
                MiniGraph("Memoria (MB)", metrics.memHistory, Purple, modifier = Modifier.weight(1f).fillMaxHeight())
            }
            MiniGraph("CPU %", metrics.cpuHistory, Yellow, maxValue = 100f, modifier = Modifier.weight(1f).fillMaxHeight())
            MiniGraph("Temperatura (C)", metrics.tempCpuHistory, Red, modifier = Modifier.weight(1f).fillMaxHeight())
        }

        // Session markers list
        if (result.markers.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.BookmarkAdded, null, tint = Cyan, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Marcadores de sesion", color = Cyan, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Text("${result.markers.size} marcadores", color = TextDim, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(12.dp))

                    result.markers.sortedBy { it.timestampSeconds }.forEach { marker ->
                        val markerColor = markerColor(marker.type)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .background(markerColor.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Timestamp
                            Text(
                                "${marker.timestampSeconds}s",
                                color = markerColor,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(48.dp)
                            )
                            // Type badge
                            Text(
                                marker.type.label,
                                color = markerColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .background(markerColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                            // Note
                            if (marker.note.isNotEmpty()) {
                                Spacer(Modifier.width(12.dp))
                                Text(marker.note, color = TextSecondary, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // Problems
        if (result.problems.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text("Problemas detectados", color = Red, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            result.problems.forEach { problem ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = Red.copy(alpha = 0.1f)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = Red, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(problem, color = androidx.compose.ui.graphics.Color.White, fontSize = 13.sp)
                    }
                }
            }
        }

        // Extra stats
        Spacer(Modifier.height(16.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkCard),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Detalles", color = Cyan, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                StatRow("Jank frames (>16ms)", "${result.totalJank}", Yellow)
                StatRow("Stutter frames (>100ms)", "${result.totalStutter}", Red)
                StatRow("Frame drops totales", "${result.frameDrops}", Orange)
            }
        }

        Spacer(Modifier.height(24.dp))

        // Report path
        if (result.reportPath.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Green.copy(alpha = 0.1f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Description, null, tint = Green, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Informe HTML generado", color = Green, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(result.reportPath, color = TextDim, fontSize = 10.sp)
                    }
                    Button(
                        onClick = { vm.openReport() },
                        colors = ButtonDefaults.buttonColors(containerColor = Green),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Abrir informe", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // Video recording
        if (result.videoPath.isNotEmpty()) {
            var showVideo by remember { mutableStateOf(false) }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Purple.copy(alpha = 0.1f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Videocam, null, tint = Purple, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Video de la sesion", color = Purple, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(result.videoPath, color = TextDim, fontSize = 10.sp)
                        }
                        OutlinedButton(
                            onClick = { vm.openVideo() },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                        ) {
                            Text("Externo", fontSize = 11.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { showVideo = !showVideo },
                            colors = ButtonDefaults.buttonColors(containerColor = Purple),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                if (showVideo) Icons.Default.ExpandLess else Icons.Default.PlayArrow,
                                null, modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(if (showVideo) "Ocultar" else "Reproducir", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }

                    if (showVideo) {
                        Spacer(Modifier.height(12.dp))
                        VideoPlayer(
                            videoPath = result.videoPath,
                            modifier = Modifier.fillMaxWidth().height(400.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

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
                Icon(Icons.Default.ArrowBack, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Nueva prueba")
            }
            Button(
                onClick = { vm.startCapture(0) },
                modifier = Modifier.weight(1f).height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Cyan),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Replay, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Repetir prueba", fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "${m}m ${s}s"
}

private fun markerColor(type: MarkerType): androidx.compose.ui.graphics.Color = when (type) {
    MarkerType.INTERSTITIAL -> Orange
    MarkerType.VIDEO_REWARD -> Purple
    MarkerType.LOADING -> Yellow
    MarkerType.SCENE_CHANGE -> Cyan
    MarkerType.CUSTOM -> Green
}
