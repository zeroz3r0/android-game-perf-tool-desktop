package com.gameperf.desktop.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gameperf.desktop.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * Embedded video player that uses ffmpeg to extract individual frames
 * and renders them as Compose ImageBitmap objects.
 *
 * Approach:
 * - For scrubbing/seeking: extracts single frames on-demand via ffmpeg subprocess
 * - For playback: coroutine timer advances the frame every ~33ms (30fps)
 * - LRU cache of ~60 frames keyed by second to avoid re-extraction
 *
 * Requires ffmpeg and ffprobe to be installed and available on PATH
 * (or at /usr/local/bin/).
 *
 * @param videoPath absolute path to the video file
 * @param currentTimeMs controlled externally by the timeline -- when changed, extracts frame at that position
 * @param isPlaying whether the video should be playing (auto-advance frames)
 * @param playbackSpeed playback rate (0.5, 1.0, 1.5, 2.0)
 * @param onTimeUpdate callback when playback advances the current time
 * @param onDurationReady callback when video duration is known (from ffprobe)
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
    var currentFrame by remember { mutableStateOf<ImageBitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var videoDurationMs by remember { mutableStateOf(0L) }

    // Frame cache: key = second (int), value = extracted ImageBitmap
    // Max 60 entries — evicts oldest when full
    val frameCache = remember { mutableMapOf<Int, ImageBitmap>() }

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

    // ---- Get video duration on first load and extract first frame ----
    LaunchedEffect(videoPath) {
        isLoading = true
        errorMessage = null
        currentFrame = null
        frameCache.clear()

        withContext(Dispatchers.IO) {
            // Check ffmpeg availability
            if (!isFfmpegAvailable()) {
                errorMessage = "ffmpeg no encontrado. Instalar con: brew install ffmpeg"
                isLoading = false
                return@withContext
            }

            // Get duration via ffprobe
            val duration = getVideoDuration(videoPath)
            if (duration > 0) {
                videoDurationMs = duration
                onDurationReady(duration)
            } else {
                errorMessage = "No se pudo leer la duracion del video"
                isLoading = false
                return@withContext
            }

            // Extract first frame
            val frame = extractFrame(videoPath, 0.0)
            if (frame != null) {
                currentFrame = frame
                frameCache[0] = frame
                isLoading = false
            } else {
                errorMessage = "No se pudo extraer el primer frame del video"
                isLoading = false
            }
        }
    }

    // ---- Debounced seek: extract frame at current time ----
    LaunchedEffect(Unit) {
        snapshotFlow { currentTimeMs }
            .distinctUntilChanged()
            .debounce(50L) // 50ms debounce for smooth scrubbing
            .collect { timeMs ->
                withContext(Dispatchers.IO) {
                    val second = (timeMs / 1000).toInt()
                    val cached = frameCache[second]
                    if (cached != null) {
                        currentFrame = cached
                    } else {
                        val frame = extractFrame(videoPath, timeMs / 1000.0)
                        if (frame != null) {
                            currentFrame = frame
                            frameCache[second] = frame
                            // Evict oldest entries if cache exceeds 60
                            if (frameCache.size > 60) {
                                val keysToRemove = frameCache.keys.sorted()
                                    .take(frameCache.size - 60)
                                keysToRemove.forEach { frameCache.remove(it) }
                            }
                        }
                    }
                }
            }
    }

    // ---- Playback: advance time based on timer ----
    LaunchedEffect(isPlaying, playbackSpeed) {
        if (!isPlaying) return@LaunchedEffect
        val frameIntervalMs = (1000.0 / 30.0 / playbackSpeed).toLong()
            .coerceAtLeast(16L) // minimum 16ms (~60fps cap)
        while (isActive) {
            delay(frameIntervalMs)
            val newTime = currentTimeMs + frameIntervalMs
            if (newTime >= videoDurationMs) {
                onTimeUpdate(0L) // Loop to start
                break
            }
            onTimeUpdate(newTime)
        }
    }

    // ---- UI: Main display ----
    Box(
        modifier = modifier.background(Color(0xFF0D1117)),
        contentAlignment = Alignment.Center
    ) {
        when {
            currentFrame != null -> {
                Image(
                    bitmap = currentFrame!!,
                    contentDescription = "Video frame",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
            isLoading -> {
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
}

// ═══════════════════════════════════════════════════════════════
// ffmpeg utilities
// ═══════════════════════════════════════════════════════════════

/** Resolve ffmpeg binary: prefer /usr/local/bin, fallback to PATH */
private fun findFfmpeg(): String {
    val localBin = File("/usr/local/bin/ffmpeg")
    return if (localBin.exists()) localBin.absolutePath else "ffmpeg"
}

/** Resolve ffprobe binary: prefer /usr/local/bin, fallback to PATH */
private fun findFfprobe(): String {
    val localBin = File("/usr/local/bin/ffprobe")
    return if (localBin.exists()) localBin.absolutePath else "ffprobe"
}

/** Check that ffmpeg is available */
private fun isFfmpegAvailable(): Boolean {
    return try {
        val process = ProcessBuilder(findFfmpeg(), "-version")
            .redirectErrorStream(true)
            .start()
        process.inputStream.readBytes() // consume output
        process.waitFor(5, TimeUnit.SECONDS)
        process.exitValue() == 0
    } catch (_: Exception) {
        false
    }
}

/**
 * Extract a single video frame at the given timestamp using ffmpeg.
 * Outputs MJPEG to stdout via image2pipe, reads into an ImageBitmap.
 *
 * @param videoPath path to the video file
 * @param timestampSeconds position in seconds (e.g., 2.5 for 2500ms)
 * @return ImageBitmap of the frame, or null on failure
 */
private fun extractFrame(videoPath: String, timestampSeconds: Double): ImageBitmap? {
    return try {
        val process = ProcessBuilder(
            findFfmpeg(),
            "-ss", String.format("%.3f", timestampSeconds),
            "-i", videoPath,
            "-vframes", "1",
            "-f", "image2pipe",
            "-vcodec", "mjpeg",
            "-q:v", "5", // quality: 2=best, 31=worst, 5=good balance
            "-"
        ).redirectErrorStream(false).start()

        val bytes = process.inputStream.readBytes()
        // Consume stderr to prevent blocking on buffer fill
        process.errorStream.readBytes()
        process.waitFor(5, TimeUnit.SECONDS)

        if (bytes.isNotEmpty()) {
            val bufferedImage = ImageIO.read(ByteArrayInputStream(bytes))
            bufferedImage?.toComposeImageBitmap()
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * Get video duration in milliseconds using ffprobe.
 *
 * @param videoPath path to the video file
 * @return duration in milliseconds, or 0 on failure
 */
private fun getVideoDuration(videoPath: String): Long {
    return try {
        val process = ProcessBuilder(
            findFfprobe(),
            "-v", "error",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1",
            videoPath
        ).redirectErrorStream(true).start()

        val output = process.inputStream.bufferedReader().readText().trim()
        process.waitFor(5, TimeUnit.SECONDS)

        (output.toDoubleOrNull()?.times(1000))?.toLong() ?: 0L
    } catch (_: Exception) {
        0L
    }
}

/**
 * Convert a java.awt.image.BufferedImage to a Compose ImageBitmap.
 * Uses Skia's image decoding via PNG intermediary.
 */
private fun java.awt.image.BufferedImage.toComposeImageBitmap(): ImageBitmap {
    val baos = ByteArrayOutputStream()
    ImageIO.write(this, "png", baos)
    return org.jetbrains.skia.Image.makeFromEncoded(baos.toByteArray())
        .toComposeImageBitmap()
}
