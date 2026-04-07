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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * LRU cache for decoded video frames.
 * Keeps at most [maxSize] frames in memory (~200 = ~20MB for 720p JPEG).
 * Frames are loaded from disk on demand (~2ms per frame via Skia).
 */
private class FrameCache(private val maxSize: Int = 200) {
    private val cache = object : LinkedHashMap<Int, ImageBitmap>(maxSize + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ImageBitmap>?): Boolean {
            return size > maxSize
        }
    }

    @Synchronized
    fun get(index: Int): ImageBitmap? = cache[index]

    @Synchronized
    fun put(index: Int, bitmap: ImageBitmap) {
        cache[index] = bitmap
    }

    @Synchronized
    fun contains(index: Int): Boolean = cache.containsKey(index)

    @Synchronized
    fun clear() = cache.clear()
}

/**
 * Load a single frame from disk by its file path.
 * Returns null if the file doesn't exist or can't be decoded.
 * Typically takes ~2ms (file read + Skia decode).
 */
private fun loadFrameFromDisk(path: String): ImageBitmap? {
    return try {
        val bytes = File(path).readBytes()
        org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
    } catch (_: Exception) {
        null
    }
}

/**
 * Embedded video player using a sliding-window frame cache.
 * Extracts all frames to disk as JPEG, then loads only a window of ~200 frames
 * around the current position into memory. This keeps memory usage under ~20MB
 * regardless of video length (vs. 270MB+ for 3-min video when loading all frames).
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
    var ready by remember { mutableStateOf(false) }

    // Sliding window: store file paths on disk, cache decoded bitmaps in LRU
    val framePaths = remember { mutableStateListOf<String>() }
    val frameCache = remember { FrameCache(200) }
    var displayedIdx by remember { mutableStateOf(-1) }
    var framesDir by remember { mutableStateOf<File?>(null) }
    val coroutineScope = rememberCoroutineScope()

    /**
     * Get a frame from cache, or load it from disk synchronously.
     * For scrub this adds ~2ms latency which is imperceptible.
     */
    fun getFrame(index: Int): ImageBitmap? {
        if (index !in framePaths.indices) return null
        frameCache.get(index)?.let { return it }
        // Cache miss — load from disk (~2ms)
        val bitmap = loadFrameFromDisk(framePaths[index]) ?: return null
        frameCache.put(index, bitmap)
        return bitmap
    }

    /**
     * Pre-load frames around [centerIndex] into the cache in background.
     * Loads ±100 frames (window of ~200) = ~3.3 seconds at 30fps.
     */
    fun preloadWindow(centerIndex: Int) {
        coroutineScope.launch(Dispatchers.IO) {
            val totalFrames = framePaths.size
            val start = (centerIndex - 100).coerceAtLeast(0)
            val end = (centerIndex + 100).coerceAtMost(totalFrames - 1)
            // Prioritize forward frames (ahead of playhead)
            for (i in centerIndex..end) {
                if (!frameCache.contains(i)) {
                    val bmp = loadFrameFromDisk(framePaths[i])
                    if (bmp != null) frameCache.put(i, bmp)
                }
            }
            for (i in (start until centerIndex).reversed()) {
                if (!frameCache.contains(i)) {
                    val bmp = loadFrameFromDisk(framePaths[i])
                    if (bmp != null) frameCache.put(i, bmp)
                }
            }
        }
    }

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

    // ---- INIT: extract frames to disk (DON'T load all into memory) ----
    LaunchedEffect(videoPath) {
        errorMessage = null
        currentFrame = null
        framePaths.clear()
        frameCache.clear()
        displayedIdx = -1
        ready = false
        extractionProgress = 0f

        withContext(Dispatchers.IO) {
            if (!isFfmpegAvailable()) {
                errorMessage = "ffmpeg no encontrado. Instalar con: brew install ffmpeg"
                return@withContext
            }

            videoFps = getVideoFps(videoPath)
            val duration = getVideoDuration(videoPath)
            if (duration <= 0) {
                // v3.1.12: more informative error. The most common cause is a corrupt
                // segment from a chain stop that didn't give Android time to flush the
                // moov atom. The user can't fix this themselves but at least they know
                // it's a recording-side issue, not a player issue.
                val file = File(videoPath)
                errorMessage = if (!file.exists()) {
                    "El archivo de video no existe en el disco. Es probable que se haya borrado o movido."
                } else if (file.length() == 0L) {
                    "El archivo de video esta vacio. La grabacion fallo durante la captura."
                } else {
                    "El video esta dañado y no se puede leer (falta el moov atom MP4). Esto pasa cuando la grabacion se interrumpe abruptamente. Las metricas del reporte siguen siendo validas."
                }
                return@withContext
            }
            videoDurationMs = duration
            onDurationReady(duration)

            // Extract all native frames to disk
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

            // Store only file paths — NOT decoded bitmaps
            framePaths.addAll(files.map { it.absolutePath })

            // Load first frame immediately for display
            val firstBitmap = loadFrameFromDisk(framePaths[0])
            if (firstBitmap != null) {
                frameCache.put(0, firstBitmap)
                currentFrame = firstBitmap
                displayedIdx = 0
            }

            // Pre-load initial window (first ~100 frames) in background
            preloadWindow(0)

            ready = true
        }
    }

    // Cleanup
    DisposableEffect(videoPath) { onDispose { frameCache.clear(); framesDir?.deleteRecursively() } }

    // ---- SCRUB: react to external time changes when NOT playing ----
    LaunchedEffect(currentTimeMs, isPlaying, ready) {
        if (isPlaying || !ready || framePaths.isEmpty()) return@LaunchedEffect
        val idx = msToFrame(currentTimeMs, videoFps, framePaths.size)
        if (idx != displayedIdx && idx in framePaths.indices) {
            val bitmap = withContext(Dispatchers.IO) { getFrame(idx) }
            if (bitmap != null) {
                currentFrame = bitmap
                displayedIdx = idx
                preloadWindow(idx)
            }
        }
    }

    // ---- PLAYBACK ----
    LaunchedEffect(isPlaying, playbackSpeed, ready) {
        if (!isPlaying || !ready || framePaths.isEmpty()) return@LaunchedEffect
        // Start from current position
        var idx = msToFrame(currentTimeMs, videoFps, framePaths.size)
        val intervalMs = (1000.0 / videoFps / playbackSpeed).toLong().coerceAtLeast(8L)

        // Pre-load window ahead of playhead
        preloadWindow(idx)

        while (isActive) {
            delay(intervalMs)
            idx++
            if (idx >= framePaths.size) idx = 0

            // Try cache first; if miss, load from disk (with small timeout)
            val bitmap = withContext(Dispatchers.IO) { getFrame(idx) }
            if (bitmap != null) {
                currentFrame = bitmap
                displayedIdx = idx
                onTimeUpdate(frameToMs(idx, videoFps))
            } else {
                // Frame couldn't be decoded — advance without updating display
                onTimeUpdate(frameToMs(idx, videoFps))
            }

            // Trigger pre-load every 50 frames to keep window ahead
            if (idx % 50 == 0) {
                preloadWindow(idx)
            }
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

private fun findFfmpeg(): String {
    val candidates = listOf(
        "/usr/local/bin/ffmpeg",       // Homebrew Intel Mac
        "/opt/homebrew/bin/ffmpeg",     // Homebrew ARM Mac
        "/usr/bin/ffmpeg",             // System Linux
        "C:\\ffmpeg\\bin\\ffmpeg.exe",  // Common Windows
    )
    return candidates.firstOrNull { File(it).exists() } ?: "ffmpeg" // fallback to PATH
}

private fun findFfprobe(): String {
    val candidates = listOf(
        "/usr/local/bin/ffprobe",       // Homebrew Intel Mac
        "/opt/homebrew/bin/ffprobe",     // Homebrew ARM Mac
        "/usr/bin/ffprobe",             // System Linux
        "C:\\ffmpeg\\bin\\ffprobe.exe",  // Common Windows
    )
    return candidates.firstOrNull { File(it).exists() } ?: "ffprobe" // fallback to PATH
}

private fun isFfmpegAvailable(): Boolean = try {
    val p = ProcessBuilder(findFfmpeg(), "-version").redirectErrorStream(true).start()
    p.inputStream.readBytes(); p.waitFor(5, TimeUnit.SECONDS); p.exitValue() == 0
} catch (_: Exception) { false }

private fun getVideoFps(path: String): Double = try {
    // Use avg_frame_rate, NOT r_frame_rate — r_frame_rate returns 90000/1 for Android screenrecord
    val p = ProcessBuilder(findFfprobe(), "-v", "error", "-select_streams", "v",
        "-show_entries", "stream=avg_frame_rate", "-of", "default=noprint_wrappers=1:nokey=1", path
    ).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText().trim()
    p.waitFor(5, TimeUnit.SECONDS)
    val parts = out.split("/")
    val fps = if (parts.size == 2) (parts[0].toDouble() / parts[1].toDouble()) else (out.toDoubleOrNull() ?: 30.0)
    // Sanity check: if fps is absurd (>120 or <1), default to 30
    if (fps in 1.0..120.0) fps else 30.0
} catch (_: Exception) { 30.0 }

private fun getVideoDuration(path: String): Long = try {
    val p = ProcessBuilder(findFfprobe(), "-v", "error", "-show_entries", "format=duration",
        "-of", "default=noprint_wrappers=1:nokey=1", path
    ).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText().trim()
    p.waitFor(5, TimeUnit.SECONDS)
    (out.toDoubleOrNull()?.times(1000))?.toLong() ?: 0L
} catch (_: Exception) { 0L }
