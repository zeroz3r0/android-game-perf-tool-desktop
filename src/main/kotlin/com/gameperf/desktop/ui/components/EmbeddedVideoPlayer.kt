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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * Embedded video player using ffmpeg batch frame extraction at native FPS.
 *
 * On first load:
 * 1. Probes the video FPS with ffprobe (e.g., 30fps for Android screenrecord)
 * 2. Batch extracts ALL native frames into a temp directory (~1600 frames in ~5s for a 39s video)
 * 3. Pre-loads ALL frames into memory as ImageBitmap (~112MB for 1600 frames — acceptable)
 *
 * Playback uses frame INDEX, not milliseconds:
 * - Frame interval = 1000ms / videoFps (e.g., 33ms for 30fps)
 * - Scrubbing converts time → frame index for instant display from memory
 *
 * Key design: scrubbing while NOT playing shows the frame immediately.
 * Playback loop uses a local frame counter to avoid stale compose state capture.
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
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var videoDurationMs by remember { mutableStateOf(0L) }
    var videoFps by remember { mutableStateOf(30.0) }
    var totalFrames by remember { mutableStateOf(0) }
    var extractionProgress by remember { mutableStateOf(-1f) } // -1 = not started, 0-1 = extracting, 2 = loading frames
    var loadingProgress by remember { mutableStateOf(0f) }

    // All frames pre-loaded in memory
    var allFrames by remember { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    var displayedFrameIndex by remember { mutableStateOf(-1) }
    var framesDir by remember { mutableStateOf<File?>(null) }

    // ---- Error UI ----
    if (errorMessage != null) {
        Box(modifier.background(Color(0xFF0D1117), RoundedCornerShape(12.dp)), Alignment.Center) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.ErrorOutline, null, tint = Color(0xFFEF4444), modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(8.dp))
                Text(errorMessage!!, color = Color(0xFFEF4444), fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
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
            }
        }
        return
    }

    // ---- Init: probe FPS, get duration, batch extract ALL native frames, pre-load into memory ----
    LaunchedEffect(videoPath) {
        errorMessage = null
        currentFrame = null
        allFrames = emptyList()
        displayedFrameIndex = -1
        extractionProgress = 0f
        loadingProgress = 0f

        withContext(Dispatchers.IO) {
            if (!isFfmpegAvailable()) {
                errorMessage = "ffmpeg no encontrado. Instalar con: brew install ffmpeg"
                return@withContext
            }

            // 1. Probe video FPS
            val detectedFps = getVideoFps(videoPath)
            videoFps = detectedFps

            // 2. Get duration
            val duration = getVideoDuration(videoPath)
            if (duration <= 0) {
                errorMessage = "No se pudo leer la duración del video"
                return@withContext
            }
            videoDurationMs = duration
            onDurationReady(duration)

            // 3. Create temp directory for frames
            val tmpDir = File(System.getProperty("java.io.tmpdir"), "gameperf_frames_${System.currentTimeMillis()}")
            tmpDir.mkdirs()

            // 4. Batch extract ALL native frames (no fps filter — extract every frame)
            val process = ProcessBuilder(
                findFfmpeg(),
                "-i", videoPath,
                "-q:v", "5",
                "${tmpDir.absolutePath}/frame_%05d.jpg"
            ).redirectErrorStream(true).start()

            // Monitor extraction progress
            val expectedFrames = (duration / 1000.0 * detectedFps).toInt()
            var monitorRunning = true
            Thread {
                while (monitorRunning) {
                    Thread.sleep(300)
                    val count = tmpDir.listFiles()?.size ?: 0
                    extractionProgress = if (expectedFrames > 0) (count.toFloat() / expectedFrames).coerceIn(0f, 0.99f) else 0f
                }
            }.apply { isDaemon = true; start() }

            process.waitFor(120, TimeUnit.SECONDS)
            monitorRunning = false

            val frameFiles = tmpDir.listFiles()?.filter { it.extension == "jpg" }?.sortedBy { it.name } ?: emptyList()
            if (frameFiles.isEmpty()) {
                errorMessage = "No se pudieron extraer frames del video"
                tmpDir.deleteRecursively()
                return@withContext
            }

            totalFrames = frameFiles.size
            framesDir = tmpDir
            extractionProgress = 2f // extraction done, now loading

            // 5. Pre-load ALL frames into memory
            val bitmaps = ArrayList<ImageBitmap>(frameFiles.size)
            frameFiles.forEachIndexed { i, frameFile ->
                val img = ImageIO.read(frameFile)
                if (img != null) {
                    val baos = ByteArrayOutputStream()
                    ImageIO.write(img, "png", baos)
                    val bitmap = org.jetbrains.skia.Image.makeFromEncoded(baos.toByteArray()).toComposeImageBitmap()
                    bitmaps.add(bitmap)
                } else {
                    // Skip unreadable frame but keep index alignment — use previous frame or skip
                    if (bitmaps.isNotEmpty()) {
                        bitmaps.add(bitmaps.last())
                    }
                }
                loadingProgress = (i + 1).toFloat() / frameFiles.size

                // Show first frame as soon as it's loaded
                if (i == 0 && bitmaps.isNotEmpty()) {
                    currentFrame = bitmaps[0]
                    displayedFrameIndex = 0
                }
            }

            allFrames = bitmaps
            totalFrames = bitmaps.size
        }
    }

    // Cleanup temp dir when composable leaves
    DisposableEffect(videoPath) {
        onDispose {
            framesDir?.deleteRecursively()
        }
    }

    // ---- Scrub from timeline: show frame immediately when NOT playing ----
    LaunchedEffect(currentTimeMs) {
        if (isPlaying) return@LaunchedEffect // playback controls the frame, not external time
        if (allFrames.isEmpty()) return@LaunchedEffect
        val idx = timeToFrame(currentTimeMs, videoFps, totalFrames)
        if (idx != displayedFrameIndex && idx in allFrames.indices) {
            currentFrame = allFrames[idx]
            displayedFrameIndex = idx
        }
    }

    // ---- Playback loop ----
    LaunchedEffect(isPlaying, playbackSpeed) {
        if (!isPlaying) return@LaunchedEffect
        if (allFrames.isEmpty()) return@LaunchedEffect

        var frameIdx = timeToFrame(currentTimeMs, videoFps, totalFrames)
        val delayMs = (1000.0 / videoFps / playbackSpeed).toLong().coerceAtLeast(8L)

        while (isActive && isPlaying) {
            delay(delayMs)
            if (!isPlaying) return@LaunchedEffect // responsive pause check
            frameIdx++
            if (frameIdx >= totalFrames) {
                frameIdx = 0
            }
            if (frameIdx in allFrames.indices) {
                currentFrame = allFrames[frameIdx]
                displayedFrameIndex = frameIdx
            }
            onTimeUpdate(frameToTime(frameIdx, videoFps))
        }
    }

    // ---- UI ----
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
            extractionProgress == 2f -> {
                // Extraction done, loading frames into memory
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Cyan, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Cargando frames en memoria...", color = TextDim, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { loadingProgress },
                        color = Green,
                        trackColor = DarkCard,
                        modifier = Modifier.width(200.dp).height(4.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        String.format(Locale.US, "%d%%", (loadingProgress * 100).toInt()),
                        color = TextDim, fontSize = 11.sp
                    )
                }
            }
            extractionProgress in 0f..1f -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Cyan, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Extrayendo frames...", color = TextDim, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { extractionProgress },
                        color = Cyan,
                        trackColor = DarkCard,
                        modifier = Modifier.width(200.dp).height(4.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        String.format(Locale.US, "%d%%", (extractionProgress * 100).toInt()),
                        color = TextDim, fontSize = 11.sp
                    )
                }
            }
            else -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Cyan, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Preparando video...", color = TextDim, fontSize = 13.sp)
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Frame <-> Time conversion
// ═══════════════════════════════════════════════════════════════

private fun timeToFrame(timeMs: Long, fps: Double, total: Int): Int =
    (timeMs * fps / 1000.0).toInt().coerceIn(0, (total - 1).coerceAtLeast(0))

private fun frameToTime(frameIndex: Int, fps: Double): Long =
    (frameIndex * 1000.0 / fps).toLong()

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

/** Load a JPEG frame file from disk into an ImageBitmap. */
private fun loadFrame(file: File): ImageBitmap? {
    if (!file.exists()) return null
    return try {
        val img = ImageIO.read(file)
        img?.let {
            val baos = ByteArrayOutputStream()
            ImageIO.write(it, "png", baos)
            org.jetbrains.skia.Image.makeFromEncoded(baos.toByteArray()).toComposeImageBitmap()
        }
    } catch (_: Exception) { null }
}

/** Get video FPS via ffprobe. Returns fps as Double (e.g. 30.0, 29.97). Defaults to 30.0. */
private fun getVideoFps(videoPath: String): Double {
    return try {
        // ffprobe returns something like "30/1" or "30000/1001"
        val p = ProcessBuilder(
            findFfprobe(), "-v", "error", "-select_streams", "v",
            "-show_entries", "stream=r_frame_rate",
            "-of", "default=noprint_wrappers=1:nokey=1", videoPath
        ).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor(5, TimeUnit.SECONDS)
        // Parse "30/1" format
        val parts = out.split("/")
        if (parts.size == 2) {
            val num = parts[0].toDoubleOrNull() ?: 30.0
            val den = parts[1].toDoubleOrNull() ?: 1.0
            if (den > 0) num / den else 30.0
        } else {
            out.toDoubleOrNull() ?: 30.0
        }
    } catch (_: Exception) { 30.0 }
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
        (out.toDoubleOrNull()?.times(1000))?.toLong() ?: 0L
    } catch (_: Exception) { 0L }
}
