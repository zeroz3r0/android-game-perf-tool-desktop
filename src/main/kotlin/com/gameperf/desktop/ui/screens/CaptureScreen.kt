package com.gameperf.desktop.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gameperf.desktop.ui.components.MetricCard
import com.gameperf.desktop.ui.components.MiniGraph
import com.gameperf.desktop.ui.theme.*
import com.gameperf.desktop.ui.util.fmtUS
import com.gameperf.desktop.ui.util.formatDuration
import com.gameperf.desktop.viewmodel.AppViewModel
import com.gameperf.desktop.viewmodel.MarkerType

@Composable
fun CaptureScreen(vm: AppViewModel) {
    val metrics by vm.liveMetrics.collectAsState()
    val gamePackage by vm.gamePackage.collectAsState()
    val deviceInfo by vm.deviceInfo.collectAsState()
    val markers by vm.markers.collectAsState()
    val captureError by vm.captureError.collectAsState()

    var showNoteField by remember { mutableStateOf(false) }
    var noteText by remember { mutableStateOf("") }
    var showStopConfirmation by remember { mutableStateOf(false) }

    // Pulsing animation for recording indicator
    val infiniteTransition = rememberInfiniteTransition()
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        )
    )

    // Focus requester for keyboard shortcuts
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    showStopConfirmation = true
                    true
                } else false
            }
    ) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Pulsing dot
            Box(
                modifier = Modifier.size(12.dp).clip(CircleShape).background(Red.copy(alpha = pulseAlpha))
            )
            Spacer(Modifier.width(8.dp))
            Text("CAPTURANDO", color = Red, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.width(16.dp))
            Text(gamePackage ?: "", color = TextSecondary, fontSize = 12.sp)
            Spacer(Modifier.width(8.dp))
            Text(deviceInfo?.model ?: "", color = TextDim, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(formatDuration(metrics.elapsed), color = Cyan, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(16.dp))
            Button(
                onClick = { showStopConfirmation = true },
                colors = ButtonDefaults.buttonColors(containerColor = Red),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Stop, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Detener")
            }
        }

        Spacer(Modifier.height(20.dp))

        // Metric cards - 2 rows of 3
        val fpsColor = when {
            metrics.fps >= 55 -> Green
            metrics.fps >= 30 -> Yellow
            metrics.fps > 0 -> Red
            else -> TextDim
        }
        val cpuColor = when {
            metrics.cpu > 85 -> Red
            metrics.cpu > 70 -> Yellow
            else -> Green
        }
        val tempColor = when {
            metrics.tempCpu > 45 -> Red
            metrics.tempCpu > 40 -> Yellow
            metrics.tempCpu > 0 -> Green
            else -> TextDim
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricCard("FPS", if (metrics.fps > 0) "${metrics.fps}" else "--", fpsColor,
                subtitle = "avg: ${fmtUS("%.0f", metrics.avgFps)}", modifier = Modifier.weight(1f))
            MetricCard("Frame Time", if (metrics.frameTime > 0) "${fmtUS("%.1f", metrics.frameTime)}ms" else "--", Cyan,
                modifier = Modifier.weight(1f))
            MetricCard("CPU", if (metrics.cpu > 0) "${metrics.cpu}%" else "--", cpuColor,
                modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricCard("Memoria", if (metrics.memMb > 0) "${metrics.memMb}MB" else "--", Cyan,
                modifier = Modifier.weight(1f))
            MetricCard("Temp", if (metrics.tempCpu > 0) "${metrics.tempCpu.toInt()}C" else "--", tempColor,
                modifier = Modifier.weight(1f))
            MetricCard("Bateria", if (metrics.battery > 0) "${metrics.battery}%" else "--",
                if (metrics.battery < 20) Red else Green, modifier = Modifier.weight(1f))
        }

        Spacer(Modifier.height(12.dp))

        // === Marker buttons ===
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(DarkCard, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.BookmarkAdd, null, tint = Cyan, modifier = Modifier.size(18.dp))
            Text("Marcadores", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)

            // Quick-add buttons for each marker type (except CUSTOM which has its own flow)
            MarkerButton("Intersticial", Color(0xFFFF6600)) { vm.addMarker(MarkerType.INTERSTITIAL) }
            MarkerButton("Video Reward", Color(0xFF7B2CBF)) { vm.addMarker(MarkerType.VIDEO_REWARD) }
            MarkerButton("Carga", Color(0xFFFFAA00)) { vm.addMarker(MarkerType.LOADING) }
            MarkerButton("Cambio escena", Color(0xFF00D4FF)) { vm.addMarker(MarkerType.SCENE_CHANGE) }

            // Custom note button
            OutlinedButton(
                onClick = { showNoteField = !showNoteField },
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Green),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(32.dp)
            ) {
                @Suppress("DEPRECATION")
                Icon(Icons.Default.NoteAdd, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Nota +", fontSize = 11.sp)
            }

            Spacer(Modifier.weight(1f))

            // Marker count badge
            if (markers.isNotEmpty()) {
                Badge(containerColor = Cyan, contentColor = Color.Black) {
                    Text("${markers.size}", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Custom note text field (shown when "Nota +" is clicked)
        if (showNoteField) {
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth()
                    .background(DarkCard.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    modifier = Modifier.weight(1f).height(40.dp),
                    placeholder = { Text("Ej: FPS drop al cargar nivel 3...", color = TextDim, fontSize = 12.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp),
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Green,
                        cursorColor = Green
                    )
                )
                Button(
                    onClick = {
                        if (noteText.isNotBlank()) {
                            vm.addMarker(MarkerType.CUSTOM, noteText.trim())
                            noteText = ""
                            showNoteField = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Green),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("Agregar", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                }
                IconButton(
                    onClick = { showNoteField = false; noteText = "" },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Default.Close, null, tint = TextDim, modifier = Modifier.size(16.dp))
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Capture error banner (device disconnect, etc.)
        if (captureError != null) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .background(Red.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Default.ErrorOutline, null, tint = Red, modifier = Modifier.size(24.dp))
                Text(captureError!!, color = Red, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.clearCaptureError() }) {
                    Text("Cerrar", color = Red, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // Graphs
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.weight(1f)) {
                MiniGraph("FPS", metrics.fpsHistory, Cyan, maxValue = 65f,
                    modifier = Modifier.fillMaxWidth().weight(1f))
                Spacer(Modifier.height(8.dp))
                MiniGraph("CPU %", metrics.cpuHistory, Yellow, maxValue = 100f,
                    modifier = Modifier.fillMaxWidth().weight(1f))
            }
            Column(Modifier.weight(1f)) {
                MiniGraph("Memoria (MB)", metrics.memHistory, Purple,
                    modifier = Modifier.fillMaxWidth().weight(1f))
                Spacer(Modifier.height(8.dp))
                MiniGraph("Temperatura (C)", metrics.tempCpuHistory, Red,
                    modifier = Modifier.fillMaxWidth().weight(1f))
            }
        }

        Spacer(Modifier.height(12.dp))

        // Bottom stats
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(DarkCard, RoundedCornerShape(12.dp))
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            MiniStat("Jank", "${metrics.jankCount}", Yellow)
            MiniStat("Stutter", "${metrics.stutterCount}", Red)
            MiniStat("Frame Drops", "${metrics.frameDrops}", Orange)
            MiniStat("GPU Temp", if (metrics.tempGpu > 0) "${metrics.tempGpu.toInt()}C" else "--", Orange)
        }
    }

    // ═══════ STOP CONFIRMATION DIALOG ═══════
    if (showStopConfirmation) {
        AlertDialog(
            onDismissRequest = { showStopConfirmation = false },
            title = { Text("Detener captura", fontWeight = FontWeight.Bold) },
            text = { Text("¿Detener la captura? Se guardarán los datos recopilados hasta ahora.") },
            confirmButton = {
                Button(
                    onClick = {
                        showStopConfirmation = false
                        vm.stopCapture()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Red)
                ) {
                    Text("Detener", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirmation = false }) {
                    Text("Cancelar")
                }
            },
            containerColor = DarkCard,
            titleContentColor = Color.White,
            textContentColor = TextSecondary
        )
    }
}

@Composable
private fun MarkerButton(label: String, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = color.copy(alpha = 0.2f), contentColor = color),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        modifier = Modifier.height(32.dp)
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MiniStat(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = TextDim, fontSize = 10.sp)
        Text(value, color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

// formatDuration moved to com.gameperf.desktop.ui.util.Formatting
