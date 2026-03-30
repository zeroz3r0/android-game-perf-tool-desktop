package com.gameperf.desktop.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gameperf.desktop.ui.components.MetricCard
import com.gameperf.desktop.ui.components.MiniGraph
import com.gameperf.desktop.ui.theme.*
import com.gameperf.desktop.viewmodel.AppViewModel

@Composable
fun CaptureScreen(vm: AppViewModel) {
    val metrics by vm.liveMetrics.collectAsState()
    val gamePackage by vm.gamePackage.collectAsState()
    val deviceInfo by vm.deviceInfo.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp)
    ) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Pulsing dot
            Box(
                modifier = Modifier.size(12.dp).clip(CircleShape).background(Red)
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
                onClick = { vm.stopCapture() },
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
                subtitle = "avg: ${"%.0f".format(metrics.avgFps)}", modifier = Modifier.weight(1f))
            MetricCard("Frame Time", if (metrics.frameTime > 0) "${"%.1f".format(metrics.frameTime)}ms" else "--", Cyan,
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

        Spacer(Modifier.height(16.dp))

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
}

@Composable
private fun MiniStat(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = TextDim, fontSize = 10.sp)
        Text(value, color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

private fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}
