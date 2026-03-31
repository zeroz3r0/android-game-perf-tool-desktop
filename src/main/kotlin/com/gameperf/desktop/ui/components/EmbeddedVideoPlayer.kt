package com.gameperf.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gameperf.desktop.ui.theme.*
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.scene.media.MediaView
import javafx.util.Duration
import java.io.File
import javax.swing.JPanel
import java.awt.BorderLayout

/**
 * Embedded video player that renders a JavaFX MediaView inside Compose Desktop
 * via the SwingPanel → JFXPanel bridge.
 *
 * @param videoPath absolute path to the video file
 * @param currentTimeMs controlled externally by the timeline — when changed, video seeks
 * @param isPlaying whether the video should be playing
 * @param playbackSpeed playback rate (0.5, 1.0, 1.5, 2.0)
 * @param onTimeUpdate callback when video reports its current time during playback
 * @param onDurationReady callback when video duration is known
 * @param modifier Compose modifier
 */
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

    if (!fileExists) {
        // Placeholder when no video file
        Box(
            modifier = modifier
                .background(Color(0xFF0D1117), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.ErrorOutline, null, tint = TextDim, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(8.dp))
                Text("Video no disponible", color = TextDim, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text(videoPath.ifEmpty { "Sin archivo de video" }, color = TextDim, fontSize = 10.sp)
            }
        }
        return
    }

    // We track the MediaPlayer reference to control it from Compose state changes
    var mediaPlayerRef by remember { mutableStateOf<MediaPlayer?>(null) }
    var jfxInitialized by remember { mutableStateOf(false) }
    // Track if we are seeking from outside (timeline) to avoid feedback loops
    var seekingFromTimeline by remember { mutableStateOf(false) }

    // React to isPlaying changes
    LaunchedEffect(isPlaying) {
        val player = mediaPlayerRef ?: return@LaunchedEffect
        Platform.runLater {
            if (isPlaying) player.play() else player.pause()
        }
    }

    // React to playback speed changes
    LaunchedEffect(playbackSpeed) {
        val player = mediaPlayerRef ?: return@LaunchedEffect
        Platform.runLater {
            player.rate = playbackSpeed
        }
    }

    // React to external seek (timeline scrubbing)
    LaunchedEffect(currentTimeMs) {
        val player = mediaPlayerRef ?: return@LaunchedEffect
        if (!seekingFromTimeline) {
            seekingFromTimeline = true
            Platform.runLater {
                player.seek(Duration.millis(currentTimeMs.toDouble()))
                seekingFromTimeline = false
            }
        }
    }

    // Cleanup
    DisposableEffect(videoPath) {
        onDispose {
            mediaPlayerRef?.let { player ->
                Platform.runLater {
                    player.stop()
                    player.dispose()
                }
            }
            mediaPlayerRef = null
            jfxInitialized = false
        }
    }

    SwingPanel(
        modifier = modifier,
        factory = {
            val wrapper = JPanel(BorderLayout())
            wrapper.background = java.awt.Color(0x0D, 0x11, 0x17)

            val jfxPanel = JFXPanel()
            wrapper.add(jfxPanel, BorderLayout.CENTER)

            Platform.runLater {
                try {
                    val mediaUri = file.toURI().toString()
                    val media = Media(mediaUri)
                    val player = MediaPlayer(media)
                    val view = MediaView(player)

                    // Fit video to panel
                    view.isPreserveRatio = true
                    view.fitWidthProperty().bind(jfxPanel.scene?.widthProperty() ?: javafx.beans.binding.Bindings.createDoubleBinding({ 800.0 }))
                    view.fitHeightProperty().bind(jfxPanel.scene?.heightProperty() ?: javafx.beans.binding.Bindings.createDoubleBinding({ 450.0 }))

                    val scene = Scene(Group(view), java.awt.Color(0x0D, 0x11, 0x17).let {
                        javafx.scene.paint.Color.rgb(it.red, it.green, it.blue)
                    })

                    // Bind size properly after scene is set
                    view.fitWidthProperty().bind(scene.widthProperty())
                    view.fitHeightProperty().bind(scene.heightProperty())

                    jfxPanel.scene = scene

                    // Report duration when ready
                    player.setOnReady {
                        val durationMs = player.media.duration.toMillis().toLong()
                        onDurationReady(durationMs)
                    }

                    // Report time updates during playback
                    player.currentTimeProperty().addListener { _, _, newTime ->
                        if (!seekingFromTimeline && newTime != null) {
                            onTimeUpdate(newTime.toMillis().toLong())
                        }
                    }

                    player.rate = playbackSpeed

                    mediaPlayerRef = player
                    jfxInitialized = true
                } catch (e: Exception) {
                    // If JavaFX Media init fails, the panel stays black
                    System.err.println("EmbeddedVideoPlayer: failed to init JavaFX Media — ${e.message}")
                }
            }

            wrapper
        },
        update = { /* Recomposition updates are handled by LaunchedEffects above */ }
    )
}
