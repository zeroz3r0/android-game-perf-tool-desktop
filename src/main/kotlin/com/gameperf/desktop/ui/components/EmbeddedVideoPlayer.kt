package com.gameperf.desktop.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Embedded video player: batch-extracts ALL native frames, loads raw JPEG bytes
 * into Skia bitmaps (0.1ms/frame), plays back at source FPS from memory.
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
    val fileExists = remember(videoPath) { File(videoPath).exists() }

    // State
    var currentFrame by remember { mutableStateOf<ImageBitmap?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var videoDurationMs by remember { mutableStateOf(0L) }
    var videoFps by remember { mutableStateOf(30.0) }
    var extractionProgress by remember { mutableStateOf(-1f) }
    var loadingProgress by remember { mutableStateOf(0f) }
    var ready by remember { mutableStateOf(false) }

    // All frames in memory. Index 0 = first frame.
    val allFrames = remember { mutableStateListOf<ImageBitmap>() }
    var displayedIdx by remember { mutableStateOf(-1) }
    var framesDir by remember { mutableStateOf<File?>(null) }

    // ---- Error / not found UI ----
    if (errorMessage != null) {
        Box(modifier.background(Color(0xFF0D1117), RoundedCornerShape(12.dp)), Alignment.Center) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.ErrorOutline, null, tint = Color(0xFFEF4444), modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(8.dp))
                Text(errorMessage!!, color = Color(0xFFEF4444), fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            }
        }
        return
    }
    if (!fileExists) {
        Box(modifier.background(Color(0xFF0D1117), RoundedCornerShape(12.dp)), Alignment.Center) {
            Text("Video no disponible", color = TextDim, fontSize = 14.sp)
        }
        return
    }

    // ---- INIT: extract + load ----
    LaunchedEffect(videoPath) {
        errorMessage = null
        currentFrame = null
        allFrames.clear()
        displayedIdx = -1
        ready = false
        extractionProgress = 0f
        loadingProgress = 0f

        withContext(Dispatchers.IO) {
            if (!isFfmpegAvailable()) {
                errorMessage = "ffmpeg no encontrado. Instalar con: brew install ffmpeg"
                return@withContext
            }

            videoFps = getVideoFps(videoPath)
            val duration = getVideoDuration(videoPath)
            if (duration <= 0) { errorMessage = "No se pudo leer la duración del video"; return@withContext }
            videoDurationMs = duration
            onDurationReady(duration)

            // Extract all native frames
            val tmpDir = File(System.getProperty("java.io.tmpdir"), "gp_${System.currentTimeMillis()}")
            tmpDir.mkdirs()
            framesDir = tmpDir

            val proc = ProcessBuilder(
                findFfmpeg(), "-i", videoPath, "-q:v", "5",
                "${tmpDir.absolutePath}/f_%06d.jpg"
            ).redirectErrorStream(true).start()

            val expected = (duration / 1000.0 * videoFps).toInt().coerceAtLeast(1)
            var monitoring = true
            Thread {
                while (monitoring) {
                    Thread.sleep(250)
                    val n = tmpDir.listFiles()?.size ?: 0
                    extractionProgress = (n.toFloat() / expected).coerceIn(0f, 0.99f)
                }
            }.apply { isDaemon = true; start() }

            proc.waitFor(180, TimeUnit.SECONDS)
            monitoring = false
            extractionProgress = 1f

            val files = tmpDir.listFiles()?.filter { it.extension == "jpg" }?.sortedBy { it.name } ?: emptyList()
            if (files.isEmpty()) { errorMessage = "No se pudieron extraer frames"; tmpDir.deleteRecursively(); return@withContext }

            // Load ALL frames: read raw JPEG bytes → Skia decode (fast, ~0.1ms/frame read + ~2ms decode)
            files.forEachIndexed { i, f ->
                try {
                    val bytes = f.readBytes()
                    val bitmap = org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
                    allFrames.add(bitmap)
                } catch (_: Exception) {
                    // Skip broken frame
                    if (allFrames.isNotEmpty()) allFrames.add(allFrames.last())
                }
                loadingProgress = (i + 1).toFloat() / files.size
                // Show first frame immediately
                if (i == 0 && allFrames.isNotEmpty()) {
                    currentFrame = allFrames[0]
                    displayedIdx = 0
                }
            }
            ready = true
        }
    }

    // Cleanup
    DisposableEffect(videoPath) { onDispose { framesDir?.deleteRecursively() } }

    // ---- SCRUB: react to external time changes when NOT playing ----
    LaunchedEffect(currentTimeMs, isPlaying, ready) {
        if (isPlaying || !ready || allFrames.isEmpty()) return@LaunchedEffect
        val idx = msToFrame(currentTimeMs, videoFps, allFrames.size)
        if (idx != displayedIdx && idx in allFrames.indices) {
            currentFrame = allFrames[idx]
            displayedIdx = idx
        }
    }

    // ---- PLAYBACK ----
    LaunchedEffect(isPlaying, playbackSpeed, ready) {
        if (!isPlaying || !ready || allFrames.isEmpty()) return@LaunchedEffect
        // Start from current position
        var idx = msToFrame(currentTimeMs, videoFps, allFrames.size)
        val intervalMs = (1000.0 / videoFps / playbackSpeed).toLong().coerceAtLeast(8L)

        while (isActive) {
            delay(intervalMs)
            // Check pause AFTER delay — isPlaying is a snapshot but LaunchedEffect
            // will be CANCELLED and relaunched when isPlaying changes (it's a key),
            // so this loop will be interrupted by cancellation.
            idx++
            if (idx >= allFrames.size) idx = 0
            currentFrame = allFrames[idx]
            displayedIdx = idx
            onTimeUpdate(frameToMs(idx, videoFps))
        }
    }

    // ---- UI ----
    Box(modifier.background(Color(0xFF0D1117)), Alignment.Center) {
        if (currentFrame != null) {
            Image(
                bitmap = currentFrame!!,
                contentDescription = "Video",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else if (extractionProgress in 0f..1f) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Cyan, modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(12.dp))
                Text("Extrayendo frames...", color = TextDim, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { extractionProgress }, color = Cyan,
                    trackColor = DarkCard, modifier = Modifier.width(200.dp).height(4.dp))
                Text(String.format(Locale.US, "%d%%", (extractionProgress * 100).toInt()),
                    color = TextDim, fontSize = 11.sp)
            }
        } else if (!ready && loadingProgress > 0f) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Green, modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(12.dp))
                Text("Cargando frames...", color = TextDim, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { loadingProgress }, color = Green,
                    trackColor = DarkCard, modifier = Modifier.width(200.dp).height(4.dp))
                Text(String.format(Locale.US, "%d%%", (loadingProgress * 100).toInt()),
                    color = TextDim, fontSize = 11.sp)
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Cyan, modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(12.dp))
                Text("Preparando video...", color = TextDim, fontSize = 13.sp)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
private fun msToFrame(ms: Long, fps: Double, total: Int): Int =
    (ms * fps / 1000.0).toInt().coerceIn(0, (total - 1).coerceAtLeast(0))

private fun frameToMs(idx: Int, fps: Double): Long =
    (idx * 1000.0 / fps).toLong()

private fun findFfmpeg(): String =
    if (File("/usr/local/bin/ffmpeg").exists()) "/usr/local/bin/ffmpeg" else "ffmpeg"

private fun findFfprobe(): String =
    if (File("/usr/local/bin/ffprobe").exists()) "/usr/local/bin/ffprobe" else "ffprobe"

private fun isFfmpegAvailable(): Boolean = try {
    val p = ProcessBuilder(findFfmpeg(), "-version").redirectErrorStream(true).start()
    p.inputStream.readBytes(); p.waitFor(5, TimeUnit.SECONDS); p.exitValue() == 0
} catch (_: Exception) { false }

private fun getVideoFps(path: String): Double = try {
    val p = ProcessBuilder(findFfprobe(), "-v", "error", "-select_streams", "v",
        "-show_entries", "stream=r_frame_rate", "-of", "default=noprint_wrappers=1:nokey=1", path
    ).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText().trim()
    p.waitFor(5, TimeUnit.SECONDS)
    val parts = out.split("/")
    if (parts.size == 2) (parts[0].toDouble() / parts[1].toDouble()) else (out.toDoubleOrNull() ?: 30.0)
} catch (_: Exception) { 30.0 }

private fun getVideoDuration(path: String): Long = try {
    val p = ProcessBuilder(findFfprobe(), "-v", "error", "-show_entries", "format=duration",
        "-of", "default=noprint_wrappers=1:nokey=1", path
    ).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText().trim()
    p.waitFor(5, TimeUnit.SECONDS)
    (out.toDoubleOrNull()?.times(1000))?.toLong() ?: 0L
} catch (_: Exception) { 0L }
