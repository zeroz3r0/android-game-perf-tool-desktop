package com.gameperf.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gameperf.desktop.ui.components.*
import com.gameperf.desktop.ui.theme.*
import com.gameperf.desktop.viewmodel.AppViewModel
import com.gameperf.desktop.viewmodel.MarkerType
import com.gameperf.desktop.viewmodel.SessionMarker

@Composable
fun ResultsScreen(vm: AppViewModel) {
    val result by vm.result.collectAsState()
    val metrics by vm.liveMetrics.collectAsState()
    val videoPosition by vm.videoPosition.collectAsState()
    val isVideoPlaying by vm.isVideoPlaying.collectAsState()
    val videoDuration by vm.videoDuration.collectAsState()
    val playbackSpeed by vm.playbackSpeed.collectAsState()
    // Marker dialog state
    var showMarkerDialog by remember { mutableStateOf(false) }
    var markerDialogTimestamp by remember { mutableStateOf(0L) }
    var editingMarker by remember { mutableStateOf<SessionMarker?>(null) }

    // Effective duration: prefer video duration, fallback to session duration
    val effectiveDurationMs = if (videoDuration > 0) videoDuration else result.duration.toLong() * 1000

    // Build FPS data pairs for the timeline
    val fpsTimedData = remember(metrics.fpsTimed) {
        metrics.fpsTimed.map { it.second to it.value.toInt() }
    }

    // Speed options
    val speedOptions = listOf(0.5, 1.0, 1.5, 2.0)
    var speedMenuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ═══════ EMBEDDED VIDEO PLAYER ═══════
        if (result.videoPath.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column {
                    // Video area — portrait aspect ratio (9:16) with dark background
                    EmbeddedVideoPlayer(
                        videoPath = result.videoPath,
                        currentTimeMs = videoPosition,
                        isPlaying = isVideoPlaying,
                        playbackSpeed = playbackSpeed,
                        onTimeUpdate = { vm.setVideoPosition(it) },
                        onDurationReady = { vm.setVideoDuration(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .background(Color(0xFF0D1117), RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    )

                    // ═══════ PLAYBACK CONTROLS ═══════
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(DarkCard)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Skip back 5s
                        IconButton(
                            onClick = {
                                vm.setVideoPosition((videoPosition - 5000).coerceAtLeast(0))
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Replay5, "Retroceder 5s", tint = TextSecondary)
                        }

                        // Play / Pause
                        IconButton(
                            onClick = { vm.setVideoPlaying(!isVideoPlaying) },
                            modifier = Modifier
                                .size(44.dp)
                                .background(Cyan.copy(alpha = 0.1f), CircleShape)
                        ) {
                            Icon(
                                if (isVideoPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                if (isVideoPlaying) "Pausar" else "Reproducir",
                                tint = Cyan,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        // Skip forward 5s
                        IconButton(
                            onClick = {
                                vm.setVideoPosition(
                                    (videoPosition + 5000).coerceAtMost(effectiveDurationMs)
                                )
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Forward5, "Avanzar 5s", tint = TextSecondary)
                        }

                        Spacer(Modifier.width(12.dp))

                        // Current time / total time
                        Text(
                            "${formatTimeMs(videoPosition)} / ${formatTimeMs(effectiveDurationMs)}",
                            color = Cyan,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )

                        Spacer(Modifier.weight(1f))

                        // Playback speed selector
                        Box {
                            TextButton(
                                onClick = { speedMenuExpanded = true },
                                colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
                            ) {
                                Text(
                                    "${playbackSpeed}x",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            DropdownMenu(
                                expanded = speedMenuExpanded,
                                onDismissRequest = { speedMenuExpanded = false }
                            ) {
                                speedOptions.forEach { speed ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "${speed}x",
                                                fontWeight = if (speed == playbackSpeed) FontWeight.Bold else FontWeight.Normal
                                            )
                                        },
                                        onClick = {
                                            vm.setPlaybackSpeed(speed)
                                            speedMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        // Open in external player
                        IconButton(
                            onClick = { vm.openVideo() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, "Abrir externo", tint = TextDim)
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
        }

        // ═══════ INTERACTIVE TIMELINE ═══════
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkCard),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("FPS", color = Cyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    // Legend
                    Box(Modifier.size(8.dp).background(Green.copy(alpha = 0.4f), CircleShape))
                    Spacer(Modifier.width(3.dp))
                    Text(">30", color = TextDim, fontSize = 9.sp)
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(8.dp).background(Yellow.copy(alpha = 0.4f), CircleShape))
                    Spacer(Modifier.width(3.dp))
                    Text("20-30", color = TextDim, fontSize = 9.sp)
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(8.dp).background(Red.copy(alpha = 0.4f), CircleShape))
                    Spacer(Modifier.width(3.dp))
                    Text("<20", color = TextDim, fontSize = 9.sp)
                }
                Spacer(Modifier.height(4.dp))

                InteractiveTimeline(
                    durationMs = effectiveDurationMs,
                    currentTimeMs = videoPosition,
                    fpsData = fpsTimedData,
                    markers = result.markers,
                    onSeek = { vm.setVideoPosition(it) },
                    onRequestAddMarker = { ms ->
                        markerDialogTimestamp = ms
                        editingMarker = null
                        showMarkerDialog = true
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ═══════ MARKERS LIST ═══════
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkCard),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.BookmarkAdded, null, tint = Cyan, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Marcadores", color = Cyan, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text("${result.markers.size} marcadores", color = TextDim, fontSize = 11.sp)
                    Spacer(Modifier.width(8.dp))
                    // Add marker button
                    SmallFloatingActionButton(
                        onClick = {
                            markerDialogTimestamp = videoPosition
                            editingMarker = null
                            showMarkerDialog = true
                        },
                        containerColor = Cyan.copy(alpha = 0.15f),
                        contentColor = Cyan,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Add, "Añadir", modifier = Modifier.size(18.dp))
                    }
                }

                if (result.markers.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    result.markers.sortedBy { it.timestampMs }.forEach { marker ->
                        val mColor = parseColorHex(marker.colorHex)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .background(mColor.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                                .clickable {
                                    // Click marker → seek to that time
                                    vm.setVideoPosition(marker.timestampMs)
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Color dot
                            Box(
                                Modifier.size(10.dp)
                                    .background(mColor, CircleShape)
                            )
                            Spacer(Modifier.width(8.dp))
                            // Timestamp
                            Text(
                                formatTimeMs(marker.timestampMs),
                                color = mColor,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(52.dp)
                            )
                            // Title
                            Text(
                                marker.title.ifEmpty { marker.type.label },
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            // Note preview
                            if (marker.note.isNotEmpty()) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    marker.note,
                                    color = TextDim,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 150.dp)
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                            // Edit button
                            IconButton(
                                onClick = {
                                    editingMarker = marker
                                    markerDialogTimestamp = marker.timestampMs
                                    showMarkerDialog = true
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.Edit, "Editar", tint = TextDim, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Sin marcadores. Mantén pulsado en la línea de tiempo para añadir uno.",
                        color = TextDim,
                        fontSize = 12.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ═══════ COMPACT METRICS ═══════
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkCard),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Métricas", color = Cyan, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    // Grade badge
                    Box(
                        modifier = Modifier
                            .background(
                                gradeColor(result.grade).copy(alpha = 0.15f),
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "Nota: ${result.grade}",
                            color = gradeColor(result.grade),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))

                // Metrics row 1
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CompactMetric(
                        "FPS", "${result.avgFps}",
                        fpsColor(result.avgFps),
                        Modifier.weight(1f)
                    )
                    CompactMetric(
                        "Frame", "${"%.1f".format(result.avgFrameTime)}ms",
                        Cyan,
                        Modifier.weight(1f)
                    )
                    CompactMetric(
                        "Memoria", "${result.peakMemMb}MB",
                        if (result.peakMemMb > 2000) Red else Cyan,
                        Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(6.dp))
                // Metrics row 2
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CompactMetric(
                        "CPU", "${result.avgCpu}%",
                        if (result.avgCpu > 85) Red else if (result.avgCpu > 70) Yellow else Green,
                        Modifier.weight(1f)
                    )
                    CompactMetric(
                        "Temp", "${result.maxTempCpu.toInt()}°C",
                        if (result.maxTempCpu > 45) Red else if (result.maxTempCpu > 40) Yellow else Green,
                        Modifier.weight(1f)
                    )
                    CompactMetric(
                        "Batería", "${result.batteryDrain}%",
                        if (result.batteryDrain > 10) Red else Green,
                        Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ═══════ SESSION INFO ═══════
        Text(
            "${result.gamePackage}  ·  ${result.deviceModel}  ·  ${formatDuration(result.duration)}",
            color = TextDim, fontSize = 11.sp
        )

        // ═══════ PROBLEMS ═══════
        if (result.problems.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Red.copy(alpha = 0.08f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Problemas detectados (${result.problems.size})",
                        color = Red,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    result.problems.forEach { problem ->
                        Row(
                            Modifier.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Warning, null, tint = Red, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(problem, color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ═══════ ACTION BUTTONS ═══════
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (result.reportPath.isNotEmpty()) {
                Button(
                    onClick = { vm.openReport() },
                    colors = ButtonDefaults.buttonColors(containerColor = Green),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f).height(44.dp)
                ) {
                    Icon(Icons.Default.Description, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Informe HTML", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }

            OutlinedButton(
                onClick = { vm.goHome() },
                modifier = Modifier.weight(1f).height(44.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Nueva prueba", fontSize = 12.sp)
            }

            Button(
                onClick = { vm.startCapture(0) },
                modifier = Modifier.weight(1f).height(44.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Cyan),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Replay, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Repetir", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }

    // ═══════ MARKER DIALOG ═══════
    if (showMarkerDialog) {
        MarkerDialog(
            timestampMs = markerDialogTimestamp,
            existingMarker = editingMarker,
            onConfirm = { title, note, colorHex, type ->
                if (editingMarker != null) {
                    vm.editMarker(editingMarker!!.id, title, note, colorHex, type)
                } else {
                    vm.addTimelineMarker(markerDialogTimestamp, title, note, colorHex, type)
                }
                showMarkerDialog = false
                editingMarker = null
            },
            onDelete = if (editingMarker != null) {
                {
                    vm.deleteMarker(editingMarker!!.id)
                    showMarkerDialog = false
                    editingMarker = null
                }
            } else null,
            onDismiss = {
                showMarkerDialog = false
                editingMarker = null
            }
        )
    }
}

/** Compact metric display for the results grid. */
@Composable
private fun CompactMetric(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(color.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, color = TextDim, fontSize = 10.sp)
        Spacer(Modifier.height(2.dp))
        Text(value, color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

private fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "${m}m ${s}s"
}

private fun fpsColor(fps: Int): Color = when {
    fps >= 55 -> Green
    fps >= 30 -> Yellow
    else -> Red
}

private fun formatTimeMs(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
