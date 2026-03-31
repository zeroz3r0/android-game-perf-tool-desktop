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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * Embedded video player using ffmpeg for frame extraction.
 *
 * Extracts one frame per second and caches them. Playback advances
 * the position by 1 second at a time (matching capture sample rate).
 * Scrubbing shows the cached frame for that second instantly.
 *
 * ROOT CAUSE of previous issues:
 * - Green/broken: String.format locale produced commas instead of dots for ffmpeg -ss
 * - Jerky playback: tried to advance at 30fps but ffmpeg extraction takes 50ms+
 * - Pause lag: coroutine wasn't checking isPlaying frequently enough
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
    val file = remember(videoPath) { File(videoPath) }
    val fileExists = remember(videoPath) { file.exists() }

    var currentFrame by remember { mutableStateOf<ImageBitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var videoDurationMs by remember { mutableStateOf(0L) }
    // Track which second is currently displayed to avoid redundant extractions
    var displayedSecond by remember { mutableStateOf(-1) }

    // Frame cache: second -> ImageBitmap. Max 120 entries.
    val frameCache = remember { mutableMapOf<Int, ImageBitmap>() }

    // ---- Error UI ----
    if (errorMessage != null) {
        Box(modifier.background(Color(0xFF0D1117), RoundedCornerShape(12.dp)), Alignment.Center) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.ErrorOutline, null, tint = Color(0xFFEF4444), modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(8.dp))
                Text(errorMessage!!, color = Color(0xFFEF4444), fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Text("Ruta: $videoPath", color = TextDim, fontSize = 10.sp, textAlign = TextAlign.Center)
            }
        }
        return
    }

    // ---- File not found UI ----
    if (!fileExists) {
        Box(modifier.background(Color(0xFF0D1117), RoundedCornerShape(12.dp)), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.ErrorOutline, null, tint = TextDim, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(8.dp))
                Text("Video no disponible", color = TextDim, fontSize = 14.sp)
                Text(videoPath.ifEmpty { "Sin archivo" }, color = TextDim, fontSize = 10.sp)
            }
        }
        return
    }

    // ---- Init: get duration + extract first frame ----
    LaunchedEffect(videoPath) {
        isLoading = true
        errorMessage = null
        currentFrame = null
        frameCache.clear()
        displayedSecond = -1

        withContext(Dispatchers.IO) {
            if (!isFfmpegAvailable()) {
                errorMessage = "ffmpeg no encontrado. Instalar con: brew install ffmpeg"
                isLoading = false
                return@withContext
            }

            val duration = getVideoDuration(videoPath)
            if (duration <= 0) {
                errorMessage = "No se pudo leer la duración del video"
                isLoading = false
                return@withContext
            }
            videoDurationMs = duration
            onDurationReady(duration)

            val frame = extractFrame(videoPath, 0.0)
            if (frame != null) {
                currentFrame = frame
                frameCache[0] = frame
                displayedSecond = 0
                isLoading = false
            } else {
                errorMessage = "No se pudo extraer el primer frame del video"
                isLoading = false
            }
        }
    }

    // ---- Seek: when currentTimeMs changes, show the frame for that second ----
    val targetSecond = (currentTimeMs / 1000).toInt()
    LaunchedEffect(targetSecond) {
        if (targetSecond == displayedSecond) return@LaunchedEffect
        // Check cache first (instant)
        val cached = frameCache[targetSecond]
        if (cached != null) {
            currentFrame = cached
            displayedSecond = targetSecond
        } else {
            // Extract in background
            withContext(Dispatchers.IO) {
                val frame = extractFrame(videoPath, targetSecond.toDouble())
                if (frame != null) {
                    frameCache[targetSecond] = frame
                    currentFrame = frame
                    displayedSecond = targetSecond
                    // Evict old entries
                    if (frameCache.size > 120) {
                        val toRemove = frameCache.keys.sorted().take(frameCache.size - 120)
                        toRemove.forEach { frameCache.remove(it) }
                    }
                }
            }
        }
    }

    // ---- Pre-fetch: extract next few seconds in advance ----
    LaunchedEffect(targetSecond) {
        withContext(Dispatchers.IO) {
            // Pre-fetch next 5 seconds
            for (s in (targetSecond + 1)..(targetSecond + 5)) {
                if (s * 1000L > videoDurationMs) break
                if (frameCache.containsKey(s)) continue
                val frame = extractFrame(videoPath, s.toDouble())
                if (frame != null) frameCache[s] = frame
            }
        }
    }

    // ---- Playback: advance 1 second at a time ----
    // Use a ref to track playback position independently of recomposition.
    // This avoids the stale closure bug where currentTimeMs is captured once.
    val playbackPositionRef = remember { mutableStateOf(0L) }
    // Sync ref with external position when not playing (e.g., user scrubs)
    LaunchedEffect(currentTimeMs, isPlaying) {
        if (!isPlaying) playbackPositionRef.value = currentTimeMs
    }

    LaunchedEffect(isPlaying, playbackSpeed) {
        if (!isPlaying) return@LaunchedEffect
        // Start from wherever the current position is
        playbackPositionRef.value = currentTimeMs
        // Interval = 1 second / speed. At 2x speed = 500ms, at 0.5x = 2000ms
        val intervalMs = (1000.0 / playbackSpeed).toLong().coerceAtLeast(100L)
        while (isActive) {
            // Check isPlaying every 50ms for responsive pause
            val steps = (intervalMs / 50).toInt().coerceAtLeast(1)
            for (i in 0 until steps) {
                if (!isActive) return@LaunchedEffect
                delay(50)
                if (!isPlaying) return@LaunchedEffect
            }
            val newTime = playbackPositionRef.value + 1000L
            if (newTime >= videoDurationMs) {
                onTimeUpdate(0L)
                return@LaunchedEffect
            }
            playbackPositionRef.value = newTime
            onTimeUpdate(newTime)
        }
    }

    // ---- Main UI ----
    Box(modifier.background(Color(0xFF0D1117)), Alignment.Center) {
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
                    CircularProgressIndicator(color = Cyan, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Cargando video...", color = TextDim, fontSize = 13.sp)
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// ffmpeg utilities
// ═══════════════════════════════════════════════════════════════

private fun findFfmpeg(): String {
    val localBin = File("/usr/local/bin/ffmpeg")
    return if (localBin.exists()) localBin.absolutePath else "ffmpeg"
}

private fun findFfprobe(): String {
    val localBin = File("/usr/local/bin/ffprobe")
    return if (localBin.exists()) localBin.absolutePath else "ffprobe"
}

private fun isFfmpegAvailable(): Boolean {
    return try {
        val p = ProcessBuilder(findFfmpeg(), "-version").redirectErrorStream(true).start()
        p.inputStream.readBytes()
        p.waitFor(5, TimeUnit.SECONDS)
        p.exitValue() == 0
    } catch (_: Exception) { false }
}

/**
 * Extract a single frame at the given second using ffmpeg.
 * CRITICAL: Uses Locale.US to ensure decimal POINT (not comma) in -ss argument.
 */
private fun extractFrame(videoPath: String, timestampSeconds: Double): ImageBitmap? {
    return try {
        // MUST use Locale.US — Spanish locale produces "5,000" which breaks ffmpeg -ss
        val ts = String.format(Locale.US, "%.3f", timestampSeconds)
        val process = ProcessBuilder(
            findFfmpeg(),
            "-ss", ts,
            "-i", videoPath,
            "-vframes", "1",
            "-f", "image2pipe",
            "-vcodec", "mjpeg",
            "-q:v", "3",
            "-"
        ).redirectErrorStream(false).start()

        // Read stdout and stderr concurrently to prevent deadlock
        val stderrThread = Thread { process.errorStream.readBytes() }
        stderrThread.start()
        val bytes = process.inputStream.readBytes()
        stderrThread.join(3000)
        process.waitFor(5, TimeUnit.SECONDS)

        if (bytes.size > 100) { // Valid JPEG is at least a few hundred bytes
            val img = ImageIO.read(ByteArrayInputStream(bytes))
            img?.toComposeImageBitmap()
        } else null
    } catch (_: Exception) { null }
}

/** Get video duration in ms via ffprobe. */
private fun getVideoDuration(videoPath: String): Long {
    return try {
        val p = ProcessBuilder(
            findFfprobe(), "-v", "error",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1",
            videoPath
        ).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor(5, TimeUnit.SECONDS)
        // ffprobe output uses dot regardless of locale
        (out.toDoubleOrNull()?.times(1000))?.toLong() ?: 0L
    } catch (_: Exception) { 0L }
}

/** Convert AWT BufferedImage to Compose ImageBitmap via Skia. */
private fun java.awt.image.BufferedImage.toComposeImageBitmap(): ImageBitmap {
    val baos = ByteArrayOutputStream()
    ImageIO.write(this, "png", baos)
    return org.jetbrains.skia.Image.makeFromEncoded(baos.toByteArray()).toComposeImageBitmap()
}
