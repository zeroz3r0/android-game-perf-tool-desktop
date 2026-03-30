package com.gameperf.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import com.gameperf.desktop.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Video player that launches ffplay/system player externally
 * and tracks playback time in the app for correlation with metrics.
 *
 * JavaFX MediaView doesn't work reliably in packaged macOS apps
 * due to missing native codec modules, so we use the system player.
 */
@Composable
fun VideoPlayer(
    videoPath: String,
    modifier: Modifier = Modifier
) {
    var isPlaying by remember { mutableStateOf(false) }
    var currentSecond by remember { mutableStateOf(0) }
    var playerProcess by remember { mutableStateOf<Process?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Cleanup on dispose
    DisposableEffect(videoPath) {
        onDispose {
            playerProcess?.destroyForcibly()
            playerProcess = null
        }
    }

    // Timer that tracks seconds while playing
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (isPlaying) {
                delay(1000)
                if (isPlaying) currentSecond++
            }
        }
    }

    val file = File(videoPath)
    val fileExists = file.exists()

    Column(modifier = modifier) {
        // Info card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkCard),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.padding(20.dp)) {
                if (!fileExists) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ErrorOutline, null, tint = Red, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Video no encontrado", color = Red, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(videoPath, color = TextDim, fontSize = 10.sp)
                } else if (errorMessage != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ErrorOutline, null, tint = Yellow, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(errorMessage!!, color = Yellow, fontSize = 13.sp)
                    }
                } else {
                    // Playback controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Big play/stop button
                        Button(
                            onClick = {
                                if (isPlaying) {
                                    // Stop
                                    playerProcess?.destroyForcibly()
                                    playerProcess = null
                                    isPlaying = false
                                } else {
                                    // Start ffplay or system player
                                    currentSecond = 0
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            // Try ffplay first (best for frame-accurate playback)
                                            val ffplay = listOf("/usr/local/bin/ffplay", "/opt/homebrew/bin/ffplay")
                                                .firstOrNull { File(it).exists() }

                                            val process = if (ffplay != null) {
                                                ProcessBuilder(
                                                    ffplay, "-autoexit", "-window_title", "GamePerf - Video de sesion",
                                                    "-x", "960", "-y", "540",
                                                    file.absolutePath
                                                ).start()
                                            } else {
                                                // Fallback: open with system player
                                                val os = System.getProperty("os.name").lowercase()
                                                when {
                                                    os.contains("mac") -> ProcessBuilder("open", file.absolutePath).start()
                                                    os.contains("win") -> ProcessBuilder("cmd", "/c", "start", "", file.absolutePath).start()
                                                    else -> ProcessBuilder("xdg-open", file.absolutePath).start()
                                                }
                                            }
                                            playerProcess = process
                                            isPlaying = true

                                            // Wait for process to finish
                                            process.waitFor()
                                            isPlaying = false
                                            playerProcess = null
                                        } catch (e: Exception) {
                                            errorMessage = "No se pudo abrir el video: ${e.message}"
                                            isPlaying = false
                                        }
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isPlaying) Red else Purple
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Icon(
                                if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                                null, modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (isPlaying) "Detener video" else "Reproducir video",
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(Modifier.width(24.dp))

                        if (isPlaying) {
                            // Live time indicator
                            Box(
                                modifier = Modifier.size(10.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(Red)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("EN REPRODUCCION", color = Red, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(16.dp))
                        }

                        // Second counter (always visible)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Segundo", color = TextDim, fontSize = 10.sp)
                            Text(
                                "${currentSecond}s",
                                color = Cyan, fontSize = 28.sp, fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(Modifier.weight(1f))

                        // File info
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Archivo", color = TextDim, fontSize = 10.sp)
                            Text(file.name, color = TextSecondary, fontSize = 11.sp)
                            Text(
                                "${"%.1f".format(file.length() / 1024.0 / 1024.0)} MB",
                                color = TextDim, fontSize = 10.sp
                            )
                        }
                    }

                    if (isPlaying) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "El video se reproduce en una ventana separada. " +
                            "Usa el contador de segundos para correlacionar con las metricas del informe.",
                            color = TextDim, fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}
