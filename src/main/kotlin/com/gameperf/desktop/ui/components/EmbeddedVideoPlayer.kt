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
import javax.imageio.ImageIO

/**
 * Embedded video player using ffmpeg batch frame extraction.
 *
 * On first load, extracts ALL frames at 10fps into a temp directory (~7ms/frame).
 * Frames are loaded from disk into memory on demand and cached.
 * Playback advances 1 frame every 100ms (10fps) for smooth video.
 *
 * Key design: scrubbing PAUSES playback automatically to prevent fighting.
 */
private const val EXTRACT_FPS = 10
private const val FRAME_INTERVAL_MS = 1000L / EXTRACT_FPS // 100ms

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
    var totalFrames by remember { mutableStateOf(0) }
    var extractionProgress by remember { mutableStateOf(-1f) } // -1 = not started, 0-1 = progress, 2 = done
    var framesDir by remember { mutableStateOf<File?>(null) }

    // In-memory cache: frame index -> ImageBitmap. Keep last 60 loaded.
    val frameCache = remember { mutableMapOf<Int, ImageBitmap>() }
    var displayedFrameIndex by remember { mutableStateOf(-1) }

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

    // ---- Init: get duration + batch extract ALL frames ----
    LaunchedEffect(videoPath) {
        errorMessage = null
        currentFrame = null
        frameCache.clear()
        displayedFrameIndex = -1
        extractionProgress = 0f

        withContext(Dispatchers.IO) {
            if (!isFfmpegAvailable()) {
                errorMessage = "ffmpeg no encontrado. Instalar con: brew install ffmpeg"
                return@withContext
            }

            val duration = getVideoDuration(videoPath)
            if (duration <= 0) {
                errorMessage = "No se pudo leer la duración del video"
                return@withContext
            }
            videoDurationMs = duration
            onDurationReady(duration)

            // Create temp directory for frames
            val tmpDir = File(System.getProperty("java.io.tmpdir"), "gameperf_frames_${System.currentTimeMillis()}")
            tmpDir.mkdirs()

            // Batch extract at EXTRACT_FPS using ffmpeg -vf fps=N
            val ts = String.format(Locale.US, "%d", EXTRACT_FPS)
            val process = ProcessBuilder(
                findFfmpeg(),
                "-i", videoPath,
                "-vf", "fps=$ts",
                "-q:v", "5",
                "${tmpDir.absolutePath}/frame_%05d.jpg"
            ).redirectErrorStream(true).start()

            // Monitor progress by checking file count in a background thread
            val expectedFrames = (duration / 1000.0 * EXTRACT_FPS).toInt()
            var monitorRunning = true
            val monitorThread = Thread {
                while (monitorRunning) {
                    Thread.sleep(300)
                    val count = tmpDir.listFiles()?.size ?: 0
                    extractionProgress = if (expectedFrames > 0) (count.toFloat() / expectedFrames).coerceIn(0f, 0.99f) else 0f
                }
            }.apply { isDaemon = true; start() }

            process.waitFor(120, TimeUnit.SECONDS)
            monitorRunning = false

            val frames = tmpDir.listFiles()?.filter { it.extension == "jpg" }?.sortedBy { it.name } ?: emptyList()
            if (frames.isEmpty()) {
                errorMessage = "No se pudieron extraer frames del video"
                tmpDir.deleteRecursively()
                return@withContext
            }

            totalFrames = frames.size
            framesDir = tmpDir
            extractionProgress = 2f // done

            // Load first frame
            val firstFrame = loadFrame(frames[0])
            if (firstFrame != null) {
                frameCache[0] = firstFrame
                currentFrame = firstFrame
                displayedFrameIndex = 0
            }
        }
    }

    // Cleanup temp dir when composable leaves
    DisposableEffect(videoPath) {
        onDispose {
            framesDir?.deleteRecursively()
        }
    }

    // ---- Show frame for current time position ----
    val targetFrameIndex = (currentTimeMs.toDouble() / FRAME_INTERVAL_MS).toInt().coerceIn(0, (totalFrames - 1).coerceAtLeast(0))

    LaunchedEffect(targetFrameIndex, framesDir) {
        val dir = framesDir ?: return@LaunchedEffect
        if (targetFrameIndex == displayedFrameIndex) return@LaunchedEffect

        val cached = frameCache[targetFrameIndex]
        if (cached != null) {
            currentFrame = cached
            displayedFrameIndex = targetFrameIndex
        } else {
            withContext(Dispatchers.IO) {
                val frameFile = File(dir, "frame_%05d.jpg".format(targetFrameIndex + 1)) // ffmpeg starts at 1
                val bitmap = loadFrame(frameFile)
                if (bitmap != null) {
                    frameCache[targetFrameIndex] = bitmap
                    currentFrame = bitmap
                    displayedFrameIndex = targetFrameIndex

                    // Evict old cache entries
                    if (frameCache.size > 60) {
                        val toRemove = frameCache.keys.sorted().take(frameCache.size - 60)
                        toRemove.forEach { frameCache.remove(it) }
                    }
                }
            }
        }
    }

    // ---- Pre-load nearby frames ----
    LaunchedEffect(targetFrameIndex, framesDir) {
        val dir = framesDir ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            // Pre-load next 20 frames (2 seconds at 10fps)
            for (i in (targetFrameIndex + 1)..(targetFrameIndex + 20)) {
                if (i >= totalFrames) break
                if (frameCache.containsKey(i)) continue
                val frameFile = File(dir, "frame_%05d.jpg".format(i + 1))
                val bitmap = loadFrame(frameFile)
                if (bitmap != null) frameCache[i] = bitmap
            }
        }
    }

    // ---- Playback ----
    LaunchedEffect(isPlaying, playbackSpeed) {
        if (!isPlaying) return@LaunchedEffect
        // Use a local accumulator that starts from the current external position
        var playPos = currentTimeMs
        val intervalMs = (FRAME_INTERVAL_MS / playbackSpeed).toLong().coerceAtLeast(30L)
        while (isActive) {
            delay(intervalMs)
            if (!isPlaying) return@LaunchedEffect
            playPos += FRAME_INTERVAL_MS
            if (playPos >= videoDurationMs) {
                onTimeUpdate(0L)
                return@LaunchedEffect
            }
            onTimeUpdate(playPos)
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
                    Text("${(extractionProgress * 100).toInt()}%", color = TextDim, fontSize = 11.sp)
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

/** Load a JPEG frame file from disk into an ImageBitmap. Fast: ~1-2ms per frame. */
private fun loadFrame(file: File): ImageBitmap? {
    if (!file.exists()) return null
    return try {
        val img = ImageIO.read(file)
        img?.let {
            val baos = java.io.ByteArrayOutputStream()
            ImageIO.write(it, "png", baos)
            org.jetbrains.skia.Image.makeFromEncoded(baos.toByteArray()).toComposeImageBitmap()
        }
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
        (out.toDoubleOrNull()?.times(1000))?.toLong() ?: 0L
    } catch (_: Exception) { 0L }
}
