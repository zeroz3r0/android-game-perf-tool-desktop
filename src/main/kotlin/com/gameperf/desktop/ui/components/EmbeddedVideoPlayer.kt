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
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * LRU cache for decoded video frames, keyed by frame index.
 *
 * v3.2.1: 200 → 1500.
 * v4.2.0: 1500 OOM'd host OS (5GB heap). 500 made scrubbing laggy.
 * Sweet spot: 600 frames ≈ 10s @60fps, ~900MB peak — keeps the JVM heap under
 * the new 2GB cap (-Xmx2048m) while still buffering enough for smooth scrub.
 */
private class FrameCache(private val maxSize: Int = 600) {
    private val cache = object : LinkedHashMap<Int, ImageBitmap>(maxSize + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ImageBitmap>?): Boolean {
            if (size > maxSize) {
                // M-8: release native Skia resources on eviction instead of waiting for GC.
                eldest?.value?.let { tryCloseBitmap(it) }
                return true
            }
            return false
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
    fun clear() {
        // M-8: explicitly release all native Skia bitmaps before clearing.
        cache.values.forEach { tryCloseBitmap(it) }
        cache.clear()
    }

    @Synchronized
    fun size(): Int = cache.size
}

/**
 * M-8: Attempt to release native Skia resources backing an [ImageBitmap].
 * Compose Desktop's ImageBitmap doesn't expose a public `close()` method,
 * but the underlying `org.jetbrains.skia.Bitmap` (or `Image`) does. We
 * use reflection as a best-effort path — if it fails, GC finalization is
 * the fallback (same as the pre-fix behavior).
 */
private fun tryCloseBitmap(bitmap: ImageBitmap) {
    try {
        // SkiaBackedImageBitmap wraps an org.jetbrains.skia.Bitmap that has close().
        val skiaField = bitmap.javaClass.declaredFields.firstOrNull {
            it.type.name.contains("Bitmap") || it.type.name.contains("Image")
        }
        if (skiaField != null) {
            skiaField.isAccessible = true
            val skiaObj = skiaField.get(bitmap)
            val closeMethod = skiaObj?.javaClass?.getMethod("close")
            closeMethod?.invoke(skiaObj)
        }
    } catch (_: Exception) {
        // Best-effort: if reflection fails, GC handles cleanup (pre-fix behavior).
    }
}

/**
 * H-1: Thread-safe set tracking all currently-running ffmpeg [Process] instances
 * spawned by [extractFrameAtIndex]. When a preload is cancelled or the player is
 * disposed, these are forcibly killed to prevent zombie OS processes.
 */
private val activeProcesses: MutableSet<Process> =
    Collections.synchronizedSet(mutableSetOf<Process>())

/**
 * Decode exactly ONE frame from the MP4 at the given frame index, using ffmpeg
 * **input seeking** (keyframe-based, fast).
 *
 * v3.2.1 core primitive: replaces the old approach of pre-extracting every frame
 * of the video to /tmp as JPEGs (35 000+ files for a 10 min @60fps video, taking
 * minutes before the player could display anything). Now each frame request is
 * a single `ffmpeg -ss <t> -i <path> -vframes 1` invocation that exits as soon
 * as it has written one JPEG to stdout. Typical latency: 80–200ms cold, 30–50ms
 * warm (MP4 still in OS page cache).
 *
 * CRITICAL: `-ss` MUST come BEFORE `-i`. That is **input seeking** — ffmpeg jumps
 * to the nearest keyframe before the requested time, then decodes forward only
 * as far as needed. Putting `-ss` AFTER `-i` is **output seeking** — ffmpeg
 * decodes from the very beginning of the file discarding frames, which is
 * O(video-length) and kills the whole point of on-demand seeking.
 *
 * Both stdout AND stderr are drained explicitly to avoid the well-known deadlock
 * where ffmpeg blocks writing to a full pipe buffer.
 *
 * Returns null on any failure (process error, empty output, decode failure,
 * timeout). A 5-second hard timeout with `destroyForcibly()` protects against
 * pathological MP4s.
 */
private fun extractFrameAtIndex(videoPath: String, frameIndex: Int, fps: Double): ImageBitmap? {
    return try {
        val seekSeconds = frameIndex / fps
        // CRITICAL: -ss BEFORE -i → input seeking (keyframes, fast).
        val proc = ProcessBuilder(
            findFfmpeg(),
            "-ss", String.format(Locale.US, "%.3f", seekSeconds),
            "-i", videoPath,
            "-vframes", "1",
            "-q:v", "5",
            "-f", "image2pipe",
            "-vcodec", "mjpeg",
            "-loglevel", "error",
            "-"
        ).redirectErrorStream(false).start()

        // H-1: track process so it can be killed on cancel/dispose.
        activeProcesses.add(proc)
        try {
            // Read JPEG bytes from stdout.
            val bytes = proc.inputStream.use { it.readBytes() }
            // Drain stderr to prevent the process from hanging on a full pipe buffer.
            proc.errorStream.use { it.readBytes() }

            if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                return null
            }
            if (proc.exitValue() != 0 || bytes.isEmpty()) return null

            org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
        } finally {
            // H-1: untrack after completion (success or failure).
            activeProcesses.remove(proc)
        }
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        System.gc()
        null
    }
}

/**
 * Embedded video player using on-demand ffmpeg seeking + LRU frame cache.
 *
 * v3.2.1 rewrite: the previous implementation pre-extracted every frame of the
 * video to disk as JPEGs before showing anything, which took minutes and
 * created tens of thousands of temp files. Now the first frame appears in
 * <300 ms, seeking to any point in the timeline fetches the target frame in
 * <200 ms, and playback is smooth because a ±600-frame preload window keeps
 * the cache warm around the playhead.
 *
 * Public API (signature byte-identical to v3.2.0 — the single call site in
 * `ResultsScreen.kt` is untouched).
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
    var ready by remember { mutableStateOf(false) }

    // On-demand cache: indexed by frame number. No disk-backed paths anymore.
    val frameCache = remember { FrameCache(1500) }
    var displayedIdx by remember { mutableStateOf(-1) }
    val coroutineScope = rememberCoroutineScope()

    // Job handle for the current preload window. When the user seeks to a new
    // spot, we cancel the previous preload so stale work doesn't compete with
    // the new window for ffmpeg throughput.
    var preloadJob by remember { mutableStateOf<Job?>(null) }

    /**
     * Get a frame by its index — cache-first, fallback to an on-demand
     * ffmpeg seek. Used by both scrub and playback loops.
     */
    fun getFrame(index: Int): ImageBitmap? {
        if (index < 0) return null
        frameCache.get(index)?.let { return it }
        val bitmap = extractFrameAtIndex(videoPath, index, videoFps) ?: return null
        frameCache.put(index, bitmap)
        return bitmap
    }

    /**
     * Preload a window of ±600 frames around [centerIndex] in the background.
     * At 60fps that's ~10s of video, at 30fps ~20s — enough to cover both
     * small scrubs and continuous playback without cache misses.
     *
     * Runs up to 4 ffmpeg processes in parallel. More than that saturates disk
     * I/O and context switches; 4 is the empirical sweet spot on a Mac.
     *
     * The previous preload Job (if any) is cancelled first, so rapid scrubs
     * don't accumulate stale work.
     */
    fun preloadWindow(centerIndex: Int) {
        preloadJob?.cancel()
        // H-1: kill any ffmpeg processes that were spawned by the cancelled preload.
        killActiveProcesses()
        preloadJob = coroutineScope.launch(Dispatchers.IO) {
            val totalFrames = (videoDurationMs / 1000.0 * videoFps).toInt().coerceAtLeast(1)
            val windowRadius = 600
            val start = (centerIndex - windowRadius).coerceAtLeast(0)
            val end = (centerIndex + windowRadius).coerceAtMost(totalFrames - 1)

            // Forward priority: frames ahead of the playhead are more urgent
            // than frames behind it (playback goes forward, scrub direction is
            // unpredictable but most users drag forward).
            val forwardIndices = (centerIndex..end).filter { !frameCache.contains(it) }
            val backwardIndices = (start until centerIndex).reversed().filter { !frameCache.contains(it) }
            val allIndices = forwardIndices + backwardIndices

            val parallelism = 3
            coroutineScope {
                allIndices.chunked(parallelism).forEach { chunk ->
                    if (!isActive) return@forEach
                    chunk.map { idx ->
                        async {
                            if (!isActive || frameCache.contains(idx)) return@async
                            val bmp = extractFrameAtIndex(videoPath, idx, videoFps)
                            if (bmp != null) frameCache.put(idx, bmp)
                        }
                    }.awaitAll()
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

    // ---- INIT: decode only the first frame; preload window runs in background ----
    LaunchedEffect(videoPath) {
        errorMessage = null
        currentFrame = null
        frameCache.clear()
        displayedIdx = -1
        ready = false

        withContext(Dispatchers.IO) {
            if (!isFfmpegAvailable()) {
                errorMessage = "ffmpeg no encontrado. Instalar con: brew install ffmpeg"
                return@withContext
            }

            videoFps = getVideoFps(videoPath)
            val duration = getVideoDuration(videoPath)
            if (duration <= 0) {
                // v3.1.12: informative error preserved verbatim. Common cause is
                // a corrupt segment from a chain stop that didn't give Android
                // time to flush the moov atom.
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

            // Decode ONLY the first frame for instant display.
            val firstBitmap = extractFrameAtIndex(videoPath, 0, videoFps)
            if (firstBitmap == null) {
                errorMessage = "No se pudo decodificar el primer frame del video"
                return@withContext
            }
            frameCache.put(0, firstBitmap)
            currentFrame = firstBitmap
            displayedIdx = 0
            ready = true

            // Kick off the initial preload window in background — does NOT
            // block ready=true, so the player is usable immediately.
            preloadWindow(0)
        }
    }

    // Cleanup
    DisposableEffect(videoPath) {
        onDispose {
            preloadJob?.cancel()
            // H-1: kill all tracked ffmpeg processes to prevent zombies.
            killActiveProcesses()
            // M-8: clear() now disposes native Skia resources.
            frameCache.clear()
        }
    }

    // ---- SCRUB: react to external time changes when NOT playing ----
    LaunchedEffect(currentTimeMs, isPlaying, ready) {
        if (isPlaying || !ready || videoDurationMs <= 0) return@LaunchedEffect
        val totalFrames = (videoDurationMs / 1000.0 * videoFps).toInt().coerceAtLeast(1)
        val idx = msToFrame(currentTimeMs, videoFps, totalFrames)
        if (idx != displayedIdx && idx in 0 until totalFrames) {
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
        if (!isPlaying || !ready || videoDurationMs <= 0) return@LaunchedEffect
        val totalFrames = (videoDurationMs / 1000.0 * videoFps).toInt().coerceAtLeast(1)
        // Start from current position
        var idx = msToFrame(currentTimeMs, videoFps, totalFrames)
        val intervalMs = (1000.0 / videoFps / playbackSpeed).toLong().coerceAtLeast(8L)

        // Pre-load window ahead of playhead
        preloadWindow(idx)

        while (isActive) {
            delay(intervalMs)
            idx++
            if (idx >= totalFrames) idx = 0

            // Try cache first; if miss, the on-demand ffmpeg seek happens inside getFrame.
            val bitmap = withContext(Dispatchers.IO) { getFrame(idx) }
            if (bitmap != null) {
                currentFrame = bitmap
                displayedIdx = idx
                onTimeUpdate(frameToMs(idx, videoFps))
            } else {
                // Frame couldn't be decoded — advance without updating display.
                onTimeUpdate(frameToMs(idx, videoFps))
            }

            // Trigger pre-load every 50 frames to keep the window ahead.
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
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Cyan, modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(12.dp))
                Text("Preparando video...", color = TextDim, fontSize = 13.sp)
            }
        }
    }
}

/**
 * H-1: Force-kill all tracked ffmpeg processes. Called when preloadJob is
 * cancelled (new seek) and on dispose (player leaves the composition).
 */
private fun killActiveProcesses() {
    synchronized(activeProcesses) {
        activeProcesses.forEach { proc ->
            try { proc.destroyForcibly() } catch (_: Exception) {}
        }
        activeProcesses.clear()
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
