package com.gameperf.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gameperf.desktop.ui.theme.*
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.scene.media.MediaView
import javafx.util.Duration
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JPanel
import java.awt.BorderLayout

/**
 * Embedded video player that renders a JavaFX MediaView inside Compose Desktop
 * via the SwingPanel -> JFXPanel bridge.
 *
 * Fixes applied:
 * - StackPane for centering (replaces Group)
 * - AtomicBoolean for thread-safe seek flag
 * - Debounced seek from timeline via snapshotFlow
 * - Player ready/error states with UI feedback
 * - Proper JavaFX scene background color
 * - Time update debouncing to prevent feedback loops
 *
 * @param videoPath absolute path to the video file
 * @param currentTimeMs controlled externally by the timeline -- when changed, video seeks
 * @param isPlaying whether the video should be playing
 * @param playbackSpeed playback rate (0.5, 1.0, 1.5, 2.0)
 * @param onTimeUpdate callback when video reports its current time during playback
 * @param onDurationReady callback when video duration is known
 * @param modifier Compose modifier
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
fun EmbeddedVideoPlayer(
    videoPath: String,
    currentTimeMs: Long,
    isPlaying: Boolean,
    playbackSpeed: Double,
    onTimeUpdate: (Long) -> Unit,
    onDurationReady: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val file = File(videoPath)
    val fileExists = file.exists()

    // Player state
    var playerReady by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val mediaPlayerRef = remember { mutableStateOf<MediaPlayer?>(null) }
    val isSeeking = remember { AtomicBoolean(false) }
    val lastReportedTime = remember { AtomicLong(0L) }

    // Cleanup on videoPath change or disposal
    DisposableEffect(videoPath) {
        onDispose {
            mediaPlayerRef.value?.let { player ->
                Platform.runLater {
                    player.stop()
                    player.dispose()
                }
            }
            mediaPlayerRef.value = null
            playerReady = false
            errorMessage = null
            isSeeking.set(false)
            lastReportedTime.set(0L)
        }
    }

    // ---- UI: Error state ----
    if (errorMessage != null) {
        Box(
            modifier = modifier
                .background(Color(0xFF0D1117), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = Color(0xFFEF4444),
                    modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    errorMessage!!,
                    color = Color(0xFFEF4444),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Ruta: $videoPath",
                    color = TextDim,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
        return
    }

    // ---- UI: File not found ----
    if (!fileExists) {
        Box(
            modifier = modifier
                .background(Color(0xFF0D1117), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = TextDim,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text("Video no disponible", color = TextDim, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    videoPath.ifEmpty { "Sin archivo de video" },
                    color = TextDim,
                    fontSize = 10.sp
                )
            }
        }
        return
    }

    // ---- UI: Main player with loading overlay ----
    Box(modifier = modifier) {
        // SwingPanel with JFXPanel — always rendered so JavaFX can initialize
        SwingPanel(
            modifier = Modifier.fillMaxSize(),
            factory = {
                createJfxPanel(
                    videoPath = videoPath,
                    mediaPlayerRef = mediaPlayerRef,
                    isSeeking = isSeeking,
                    lastReportedTime = lastReportedTime,
                    playbackSpeed = playbackSpeed,
                    onPlayerReady = { playerReady = true },
                    onDurationReady = onDurationReady,
                    onTimeUpdate = onTimeUpdate,
                    onError = { msg -> errorMessage = msg }
                )
            },
            update = { /* State changes handled by LaunchedEffects below */ }
        )

        // Loading overlay — shown while player initializes
        if (!playerReady) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0D1117)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = Cyan,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Cargando video...",
                        color = TextDim,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }

    // ---- Debounced seek from timeline (snapshotFlow) ----
    LaunchedEffect(Unit) {
        snapshotFlow { currentTimeMs }
            .distinctUntilChanged()
            .debounce(150L)
            .collect { timeMs ->
                val player = mediaPlayerRef.value ?: return@collect
                if (!isSeeking.get()) {
                    isSeeking.set(true)
                    Platform.runLater {
                        try {
                            player.seek(Duration.millis(timeMs.toDouble()))
                        } finally {
                            isSeeking.set(false)
                        }
                    }
                }
            }
    }

    // ---- Play/Pause control ----
    LaunchedEffect(isPlaying, playerReady) {
        if (!playerReady) return@LaunchedEffect
        val player = mediaPlayerRef.value ?: return@LaunchedEffect
        Platform.runLater {
            if (isPlaying) player.play() else player.pause()
        }
    }

    // ---- Speed control ----
    LaunchedEffect(playbackSpeed, playerReady) {
        if (!playerReady) return@LaunchedEffect
        val player = mediaPlayerRef.value ?: return@LaunchedEffect
        Platform.runLater {
            player.rate = playbackSpeed
        }
    }
}

/**
 * Creates and configures the JFXPanel with a MediaPlayer inside a StackPane
 * for proper video centering and background color.
 */
private fun createJfxPanel(
    videoPath: String,
    mediaPlayerRef: MutableState<MediaPlayer?>,
    isSeeking: AtomicBoolean,
    lastReportedTime: AtomicLong,
    playbackSpeed: Double,
    onPlayerReady: () -> Unit,
    onDurationReady: (Long) -> Unit,
    onTimeUpdate: (Long) -> Unit,
    onError: (String) -> Unit
): JPanel {
    val wrapper = JPanel(BorderLayout())
    wrapper.background = java.awt.Color(0x0D, 0x11, 0x17)

    val jfxPanel = JFXPanel()
    wrapper.add(jfxPanel, BorderLayout.CENTER)

    Platform.runLater {
        try {
            val file = File(videoPath)
            val mediaUri = file.toURI().toString()
            val media = Media(mediaUri)

            // Media-level error handler
            media.setOnError {
                val msg = media.error?.message ?: "desconocido"
                onError("Error de medio: $msg")
            }

            val player = MediaPlayer(media)

            // Player error handler
            player.setOnError {
                val msg = player.error?.message ?: "desconocido"
                onError("Error de reproduccion: $msg")
            }

            // Player ready: report duration and signal readiness
            player.setOnReady {
                val durationMs = player.totalDuration.toMillis().toLong()
                onDurationReady(durationMs)
                onPlayerReady()
            }

            // End of media: stop and reset to beginning
            player.setOnEndOfMedia {
                Platform.runLater {
                    player.stop()
                    player.seek(Duration.ZERO)
                }
            }

            // Time updates with debounce: only report if delta > 100ms
            player.currentTimeProperty().addListener { _, _, newTime ->
                if (newTime != null && !isSeeking.get()) {
                    val ms = newTime.toMillis().toLong()
                    val last = lastReportedTime.get()
                    if (Math.abs(ms - last) > 100) {
                        lastReportedTime.set(ms)
                        onTimeUpdate(ms)
                    }
                }
            }

            // Set up MediaView inside StackPane for proper centering
            val view = MediaView(player)
            view.isPreserveRatio = true

            val root = StackPane(view)
            root.style = "-fx-background-color: #0D1117;"

            // Bind view size to StackPane size for responsive scaling
            view.fitWidthProperty().bind(root.widthProperty())
            view.fitHeightProperty().bind(root.heightProperty())

            val scene = Scene(root)
            scene.fill = javafx.scene.paint.Color.web("#0D1117")

            jfxPanel.scene = scene

            // Apply initial playback speed
            player.rate = playbackSpeed

            // Store reference for Compose-side control
            mediaPlayerRef.value = player
        } catch (e: Exception) {
            System.err.println("EmbeddedVideoPlayer: failed to init JavaFX Media — ${e.message}")
            onError("Error al inicializar el reproductor: ${e.message ?: "error desconocido"}")
        }
    }

    return wrapper
}
