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
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
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
 * H-1: Thread-safe set tracking ffmpeg [Process] instances spawned by
 * [extractFrameAtIndex] (single-frame on-demand seeks + preload window).
 * Killed when a preload is cancelled (user scrubs to a new position) or when
 * the player leaves composition.
 *
 * v4.3.2: this set is **separate** from [activeThumbnailProcesses]. Before
 * v4.3.2 there was a single `activeProcesses` set shared by both subsystems;
 * every scrub called `preloadWindow(idx)` → `killActiveProcesses()` which
 * also killed the long-running ffmpeg spawned by [generateThumbnailTrack].
 * Result: on any video ≥10s the user could never get the thumbnail track to
 * finish if they touched the timeline during its 15-60s generation — the
 * spinner would stall forever and every subsequent scrub fell through to
 * the 10-20x slower on-demand single-frame seek. That amplified bug #1
 * (the oversized frame cache causing GC stalls) into the "de repente va
 * lentísimo" report that persisted across multiple fix attempts.
 */
private val activeFrameProcesses: MutableSet<Process> =
    Collections.synchronizedSet(mutableSetOf<Process>())

/**
 * v4.3.2: Thread-safe set tracking the long-running ffmpeg process spawned
 * by [generateThumbnailTrack]. Separate from [activeFrameProcesses] so that
 * scrubbing doesn't kill the thumbnail generator mid-flight. Killed only on
 * player dispose.
 */
private val activeThumbnailProcesses: MutableSet<Process> =
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
        activeFrameProcesses.add(proc)
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
            activeFrameProcesses.remove(proc)
        }
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        // Let JVM handle OOM — explicit gc() is ineffective and a code smell
        null
    }
}

/**
 * v4.2.3: Thumbnail track for smooth scrubbing on long videos.
 *
 * The LRU [FrameCache] only covers ±10s around the playhead. On a 2-hour
 * session the user was seeing the video stall for ~200ms every time they
 * dragged the timeline cursor beyond the cached window — ffmpeg had to cold-
 * start for each frame request and the disk I/O added latency.
 *
 * Solution: generate a low-resolution thumbnail track in background (one
 * invocation of ffmpeg with `fps=1/N,scale=240:-1` → ~500 thumbnails
 * covering the whole video, ~14MB total in RAM, takes 15-30s for a 2h video).
 * During scrub we show the closest thumbnail instantly; once the user stops
 * scrubbing, a debounce timer fetches the full-res frame at the exact
 * timestamp via the original on-demand ffmpeg seek.
 *
 * Contract: `thumbs` is non-empty, and `intervalMs` is `(durationMs / thumbs.size)`.
 * To look up the thumbnail for a given playhead time use
 * `thumbs[(timeMs / intervalMs).toInt().coerceIn(0, thumbs.lastIndex)]`.
 */
private data class ThumbnailTrack(
    val thumbs: List<ImageBitmap>,
    val intervalMs: Long,
)

/** Target number of thumbnails in the track. 500 thumbs × ~14KB JPEG ≈ 7MB, fits
 *  comfortably in RAM and gives ~7s resolution on a 1-hour video, ~14s on 2h. */
private const val THUMBNAIL_TARGET_COUNT = 500

/** Width of each thumbnail in pixels. 240px is enough to identify a scene
 *  while being tiny enough to decode in a few ms. Height preserves aspect ratio. */
private const val THUMBNAIL_WIDTH = 240

/** Video duration threshold under which we skip thumbnail generation — for
 *  short clips the ±600-frame window already covers the entire video. */
private const val THUMBNAIL_MIN_DURATION_MS = 10_000L

/**
 * Generate a thumbnail track for the video by invoking ffmpeg once with a
 * downsampling filter. Writes JPEGs to a temp directory, reads them in order,
 * decodes to [ImageBitmap], then deletes the temp files.
 *
 * Reports progress through [onProgress] (0.0 .. 1.0). The callback is invoked
 * both while ffmpeg is running (based on how many files appeared on disk) and
 * once per decoded bitmap during the read phase — the numbers don't line up
 * perfectly but the progress bar moves steadily.
 *
 * Returns null if:
 *   - The video is shorter than [THUMBNAIL_MIN_DURATION_MS] (not worth the overhead).
 *   - ffmpeg fails or times out (5 min hard timeout for 2h video at worst case).
 *   - No thumbnails were produced.
 *   - Caller coroutine was cancelled.
 *
 * Best-effort: the caller should tolerate null and fall back to the per-frame
 * on-demand seeking (original v3.2.1 behavior).
 */
private suspend fun generateThumbnailTrack(
    videoPath: String,
    durationMs: Long,
    onProgress: suspend (Float) -> Unit,
): ThumbnailTrack? = withContext(Dispatchers.IO) {
    if (durationMs < THUMBNAIL_MIN_DURATION_MS) return@withContext null

    val tmpDir = try {
        Files.createTempDirectory("gp_thumbs_").toFile()
    } catch (_: Exception) {
        return@withContext null
    }

    try {
        // Interval per thumb in seconds — e.g. 2h video / 500 thumbs = 14.4s per thumb.
        val durationSec = durationMs / 1000.0
        val intervalSec = (durationSec / THUMBNAIL_TARGET_COUNT).coerceAtLeast(0.1)

        // Build ffmpeg command. `fps=1/N` means "output 1 frame every N input seconds"
        // so ffmpeg skips most of the decode work. `scale=W:-1` keeps aspect ratio.
        val filter = "fps=1/${"%.6f".format(Locale.US, intervalSec)},scale=${THUMBNAIL_WIDTH}:-1"
        val outputPattern = File(tmpDir, "thumb_%05d.jpg").absolutePath
        val cmd = listOf(
            findFfmpeg(),
            "-v", "error",
            "-y",
            "-i", videoPath,
            "-vf", filter,
            "-q:v", "4",
            "-fps_mode", "vfr",
            outputPattern,
        )

        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        // v4.3.2: tracked in the thumbnail-specific set so scrub's
        // killActiveFrameProcesses() no longer kills this long-running process.
        activeThumbnailProcesses.add(proc)

        // Progress monitor: while ffmpeg runs, poll the temp dir and report
        // fraction of target count produced so far. Cancelled cooperatively.
        val progressJob = launch {
            while (isActive && proc.isAlive) {
                delay(500)
                val produced = tmpDir.list()?.size ?: 0
                onProgress((produced.toFloat() / THUMBNAIL_TARGET_COUNT).coerceAtMost(0.9f))
            }
        }

        // Drain stdout/stderr combined (we redirected to stdout) to prevent pipe-full deadlock.
        val drainJob = launch(Dispatchers.IO) {
            runCatching { proc.inputStream.readBytes() }
        }

        // 5 min hard timeout. For a 2-hour video with 500 thumbs at 240p, real
        // elapsed time is typically 20-60s depending on codec and disk speed.
        val finished = try {
            proc.waitFor(5, TimeUnit.MINUTES)
        } catch (_: InterruptedException) {
            false
        } finally {
            progressJob.cancel()
            drainJob.cancel()
            activeThumbnailProcesses.remove(proc)
        }

        if (!finished) {
            proc.destroyForcibly()
            return@withContext null
        }
        if (proc.exitValue() != 0) return@withContext null

        // Read generated JPEGs in order. Numbering starts at 1 (ffmpeg convention).
        val thumbFiles = tmpDir.listFiles { f -> f.extension.equals("jpg", true) }
            ?.sortedBy { it.name }
            ?: return@withContext null
        if (thumbFiles.isEmpty()) return@withContext null

        val thumbs = mutableListOf<ImageBitmap>()
        thumbFiles.forEachIndexed { idx, file ->
            if (!isActive) return@withContext null
            try {
                val bytes = file.readBytes()
                val bitmap = org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
                thumbs.add(bitmap)
            } catch (_: Exception) {
                // Skip corrupt thumbs — the track is a best-effort preview.
            }
            // Progress: 0.9 → 1.0 during the decode phase.
            val progress = 0.9f + 0.1f * (idx + 1).toFloat() / thumbFiles.size
            onProgress(progress.coerceAtMost(1.0f))
        }

        if (thumbs.isEmpty()) return@withContext null

        val actualIntervalMs = durationMs / thumbs.size
        ThumbnailTrack(thumbs = thumbs, intervalMs = actualIntervalMs.coerceAtLeast(1L))
    } finally {
        // Cleanup temp files. tmpDir is under system temp so if deletion fails
        // the OS will reap it eventually, not our problem.
        runCatching { tmpDir.walkBottomUp().forEach { it.delete() } }
    }
}

/**
 * Embedded video player using on-demand ffmpeg seeking + LRU frame cache +
 * a pre-generated low-resolution thumbnail track for smooth scrubbing.
 *
 * v3.2.1 rewrite: the previous implementation pre-extracted every frame of the
 * video to disk as JPEGs before showing anything, which took minutes and
 * created tens of thousands of temp files. Now the first frame appears in
 * <300 ms, seeking to any point in the timeline fetches the target frame in
 * <200 ms, and playback is smooth because a ±600-frame preload window keeps
 * the cache warm around the playhead.
 *
 * v4.2.3: added a low-resolution thumbnail track generated in background on
 * init. During scrub the user sees the closest thumbnail instantly (no
 * ffmpeg round-trip); when scrubbing stops, a 250ms debounce triggers the
 * full-res frame decode at the exact timestamp. Makes a 2-hour session
 * scrubbable end-to-end without lag.
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

    // v4.2.3: thumbnail track for smooth scrubbing on long videos.
    // While null, the player still works (falls back to on-demand seek) — this
    // is a progressive enhancement, not a hard dependency.
    var thumbnailTrack by remember { mutableStateOf<ThumbnailTrack?>(null) }
    var thumbnailProgress by remember { mutableStateOf(0f) }
    var thumbnailGenerating by remember { mutableStateOf(false) }

    // On-demand cache: indexed by frame number. No disk-backed paths anymore.
    //
    // v4.3.2: reverted to 600 (the documented "sweet spot" in FrameCache's KDoc).
    // The call site here was pinning 1500 for historical reasons — at ~1.5MB per
    // decoded frame that's ~2.25GB, above the -Xmx2048m heap cap, so the JVM
    // GC thrashed once the user scrubbed past a few hundred uncached frames.
    // Symptom from the user: "al arrastrar el timeline hacia la derecha el video
    // de repente empieza a ir lentísimo" — that was the GC stop-the-world pauses
    // when the cache filled and old frames had to be evicted while new decodes
    // were landing. 600 frames ≈ 10s @60fps, ~900MB peak, well under the cap.
    val frameCache = remember { FrameCache() }
    var displayedIdx by remember { mutableStateOf(-1) }
    val coroutineScope = rememberCoroutineScope()

    // Job handle for the current preload window. When the user seeks to a new
    // spot, we cancel the previous preload so stale work doesn't compete with
    // the new window for ffmpeg throughput.
    var preloadJob by remember { mutableStateOf<Job?>(null) }
    // v4.3.4: last center index we preloaded around. Used by [PreloadStrategy]
    // to decide whether a new preload trigger is "steady playback" (extend the
    // existing window — do NOT kill in-flight ffmpegs) or "scrub/seek" (reset
    // the window). Without this, the playback loop's `preloadWindow` call
    // every 50 frames killed the very ffmpegs the previous call had spawned,
    // dropping effective playback to ~25% speed.
    var lastPreloadCenter by remember { mutableStateOf<Int?>(null) }
    // v4.2.3: separate job for thumbnail track generation — runs once per videoPath,
    // cancelled on dispose. Kept independent from preloadJob so scrubbing doesn't
    // cancel the thumbnail pre-gen.
    var thumbnailJob by remember { mutableStateOf<Job?>(null) }
    // v4.2.3: debounce job for "settled-on-a-frame" full-res decode. Fires 250ms
    // after the last scrub update; if the user scrubs again within 250ms we cancel
    // and restart, so we don't waste ffmpeg cycles on frames the user scrolled past.
    var fullResDebounceJob by remember { mutableStateOf<Job?>(null) }

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
     * Preload a window of frames around [centerIndex] in the background.
     *
     * Two modes, decided by [PreloadStrategy.shouldReset]:
     *
     *   - **Reset** (scrub / seek / first call): cancel the in-flight preload
     *     job, kill its ffmpeg processes, and start a fresh symmetric window
     *     ([PreloadStrategy.SCRUB_WINDOW]). This is the original v3.2.1
     *     behavior, correct when the playhead jumps.
     *
     *   - **Extend** (steady playback, small forward delta): if the previous
     *     preload job is still running, no-op — let it finish; killing it
     *     and respawning the same ffmpegs is the v4.3.x bug. If it has
     *     completed, kick off a new asymmetric window
     *     ([PreloadStrategy.PLAYBACK_WINDOW], heavy forward bias) without
     *     killing anything, so the cache stays warm ahead of the playhead.
     *
     * Runs up to 3 ffmpeg processes in parallel. More than that saturates
     * disk I/O and context switches; 3 is the empirical sweet spot.
     *
     * Total window size stays under [FrameCache] capacity (600) so that
     * no in-window frame is evicted by another in-window frame — same
     * invariant that the v4.3.2 fix locked in.
     */
    fun preloadWindow(centerIndex: Int) {
        val reset = PreloadStrategy.shouldReset(centerIndex, lastPreloadCenter)
        val window = if (reset) PreloadStrategy.SCRUB_WINDOW else PreloadStrategy.PLAYBACK_WINDOW

        if (reset) {
            preloadJob?.cancel()
            // H-1: kill any ffmpeg processes spawned by the cancelled preload.
            // v4.3.2: ONLY frame-extractor processes — the thumbnail-track ffmpeg
            // lives in activeThumbnailProcesses and is immune to scrub cancellation.
            killActiveFrameProcesses()
        } else {
            // v4.3.4: steady-playback path. If the previous preload is still
            // running, do NOT touch it — the playback loop fires every 50
            // frames, and killing+respawning every 50 frames is exactly the
            // bug. Let in-flight ffmpegs finish; they're warming the cache
            // for the very frames the playhead is about to consume.
            val active = preloadJob
            if (active != null && active.isActive) {
                lastPreloadCenter = centerIndex
                return
            }
        }

        preloadJob = coroutineScope.launch(Dispatchers.IO) {
            val totalFrames = (videoDurationMs / 1000.0 * videoFps).toInt().coerceAtLeast(1)
            val start = (centerIndex - window.backward).coerceAtLeast(0)
            val end = (centerIndex + window.forward).coerceAtMost(totalFrames - 1)

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
        lastPreloadCenter = centerIndex
    }

    /**
     * v4.4.1: Force a fresh PLAYBACK_WINDOW preload, ignoring the
     * shouldReset heuristic.
     *
     * Used ONLY by the playback `LaunchedEffect` at play-start. Unlike
     * [preloadWindow], which decides reset-vs-extend via
     * `PreloadStrategy.shouldReset(idx, lastCenter)`, this helper
     * UNCONDITIONALLY resets to [PreloadStrategy.PLAYBACK_WINDOW]. This
     * is correct for play-after-scrub: the in-flight job (if any) is a
     * SCRUB_WINDOW (300/300 symmetric) which wastes half its budget on
     * backward frames the playhead is moving away from. Without this
     * helper, `shouldReset(idx, idx)` returned `false` → `preloadWindow`
     * no-op'd → playback ran at ~5-7fps until the cache cap exhausted.
     * See explore #260, ADR 1.
     *
     * Body delegates to the pure top-level [forcePlaybackPreloadCore] so
     * the integration test can drive the same logic without a Compose
     * runtime. Cancellation order is critical: caller MUST cancel
     * `fullResDebounceJob` BEFORE invoking this helper (ADR 2) so a
     * stale debounce cannot fire post-spawn and `killActiveFrameProcesses`
     * the freshly-spawned playback preload.
     */
    fun forcePlaybackPreload(centerIndex: Int) {
        preloadJob?.cancel()
        // H-1: kill any ffmpeg processes spawned by the cancelled preload.
        // Frame-extractor only — thumbnail track is in a separate set.
        killActiveFrameProcesses()
        lastPreloadCenter = centerIndex
        val totalFrames = (videoDurationMs / 1000.0 * videoFps).toInt().coerceAtLeast(1)
        preloadJob = coroutineScope.launch(Dispatchers.IO) {
            forcePlaybackPreloadCore(
                centerIndex = centerIndex,
                totalFrames = totalFrames,
                window = PreloadStrategy.PLAYBACK_WINDOW,
                isCached = { idx -> frameCache.contains(idx) },
                extractFrame = { idx -> extractFrameAtIndex(videoPath, idx, videoFps) },
                putFrame = { idx, bmp -> frameCache.put(idx, bmp) },
                parallelism = 3,
            )
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
        thumbnailTrack = null
        thumbnailProgress = 0f
        thumbnailGenerating = false
        // v4.3.4: reset preload tracker so first preload of the new video
        // is correctly classified as "first call" → reset (seed cache).
        lastPreloadCenter = null

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

        // v4.2.3: kick off thumbnail-track generation in background AFTER the
        // first frame is visible. The spinner overlay is what the user asked
        // for ("ruedecita como diciendo cargando y renderizando video") so
        // there's clear feedback during the 15-60s it takes on a long video.
        if (ready && videoDurationMs >= THUMBNAIL_MIN_DURATION_MS && thumbnailTrack == null) {
            thumbnailGenerating = true
            thumbnailJob = coroutineScope.launch(Dispatchers.IO) {
                val track = generateThumbnailTrack(
                    videoPath = videoPath,
                    durationMs = videoDurationMs,
                    onProgress = { p -> thumbnailProgress = p },
                )
                thumbnailTrack = track
                thumbnailGenerating = false
            }
        }
    }

    // Cleanup
    DisposableEffect(videoPath) {
        onDispose {
            preloadJob?.cancel()
            thumbnailJob?.cancel()
            fullResDebounceJob?.cancel()
            // H-1: kill BOTH ffmpeg process sets on dispose — thumbnail generator
            // included, since the player is leaving composition.
            killActiveFrameProcesses()
            killActiveThumbnailProcesses()
            // M-8: clear() now disposes native Skia resources.
            frameCache.clear()
            // Release thumbnail track bitmaps too — same native memory concern.
            thumbnailTrack?.thumbs?.forEach { tryCloseBitmap(it) }
        }
    }

    // ---- SCRUB: react to external time changes when NOT playing ----
    //
    // v4.2.3 two-phase scrub:
    //   Phase 1 — instant thumbnail (if track is ready): show the closest
    //             pre-decoded low-res thumbnail. Zero ffmpeg work, <1ms.
    //   Phase 2 — debounced full-res: 250ms after the user stops scrubbing,
    //             fetch the exact-timestamp full-res frame from the LRU cache
    //             or via on-demand ffmpeg. The user ends up on a crisp frame.
    //
    // The debounce means continuous scrubbing doesn't spawn an ffmpeg process
    // per pixel — we only fetch the full frame once the cursor settles. Makes
    // dragging across a 2-hour timeline buttery-smooth.
    LaunchedEffect(currentTimeMs, isPlaying, ready, thumbnailTrack) {
        if (isPlaying || !ready || videoDurationMs <= 0) return@LaunchedEffect
        val totalFrames = (videoDurationMs / 1000.0 * videoFps).toInt().coerceAtLeast(1)
        val idx = msToFrame(currentTimeMs, videoFps, totalFrames)
        if (idx == displayedIdx || idx !in 0 until totalFrames) return@LaunchedEffect

        // Phase 1: thumbnail track hit — instant display.
        val track = thumbnailTrack
        val cachedFull = frameCache.get(idx)
        if (cachedFull != null) {
            // Already cached full-res: show it directly, skip thumbnail flash.
            currentFrame = cachedFull
            displayedIdx = idx
            preloadWindow(idx)
            return@LaunchedEffect
        }
        if (track != null) {
            val thumbIdx = (currentTimeMs / track.intervalMs)
                .toInt()
                .coerceIn(0, track.thumbs.lastIndex)
            currentFrame = track.thumbs[thumbIdx]
            // Note: displayedIdx intentionally NOT updated here — it's the
            // full-res frame index, which we're still about to fetch.
        }

        // Phase 2: debounce full-res decode. Cancel any pending debounce first
        // so rapid scrubs collapse into a single tail-decode.
        fullResDebounceJob?.cancel()
        fullResDebounceJob = coroutineScope.launch(Dispatchers.IO) {
            delay(250)
            if (!isActive) return@launch
            val bitmap = getFrame(idx)
            if (bitmap != null && isActive) {
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

        // v4.4.1: kill any pending scrub debounce BEFORE spawning the play
        // preload. Order is critical (ADR 2) — if we spawned first, the
        // debounce (still 0-250ms from firing) could call `preloadWindow` →
        // `killActiveFrameProcesses` and nuke the play preload's ffmpegs.
        // Null-safe: cancel on a completed/null Job is a no-op.
        fullResDebounceJob?.cancel()
        fullResDebounceJob = null

        // v4.4.1: force PLAYBACK_WINDOW regardless of prior preload center.
        // The previous `preloadWindow(idx)` here was classified as "extend"
        // after a scrub-then-play sequence (delta=0 from scrub seed) → no-op
        // → cache only had 300 forward frames. See explore #260, ADR 1.
        forcePlaybackPreload(idx)

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
            // v4.2.3: non-blocking spinner overlay while thumbnail track is
            // being generated in background. The video is already playable
            // (cached ±10s window + on-demand seek) — the overlay just tells
            // the user "scrubbing the whole timeline will be smooth soon".
            if (thumbnailGenerating && thumbnailTrack == null) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .background(Color(0xCC0D1117), RoundedCornerShape(8.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            color = Cyan,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                "Preparando vista previa del video...",
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { thumbnailProgress },
                                color = Cyan,
                                trackColor = Color(0x33FFFFFF),
                                modifier = Modifier
                                    .width(220.dp)
                                    .height(3.dp)
                            )
                        }
                    }
                }
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

/**
 * H-1: Force-kill all tracked **frame-extractor** ffmpeg processes.
 * Called when preloadJob is cancelled (new seek) and on dispose.
 *
 * v4.3.2: does NOT touch the thumbnail-track ffmpeg — that lives in
 * [activeThumbnailProcesses] and must survive scrub-triggered cancellations.
 */
private fun killActiveFrameProcesses() {
    synchronized(activeFrameProcesses) {
        activeFrameProcesses.forEach { proc ->
            try { proc.destroyForcibly() } catch (_: Exception) {}
        }
        activeFrameProcesses.clear()
    }
}

/**
 * v4.3.2: Force-kill the thumbnail-track ffmpeg process. Called ONLY on
 * player dispose — not on scrub, not on preload cancel.
 */
private fun killActiveThumbnailProcesses() {
    synchronized(activeThumbnailProcesses) {
        activeThumbnailProcesses.forEach { proc ->
            try { proc.destroyForcibly() } catch (_: Exception) {}
        }
        activeThumbnailProcesses.clear()
    }
}

// ═══════════════════════════════════════════════════════════════

/**
 * v4.4.1: Pure, testable core of the play-start preload sequence.
 *
 * Seeds the cache with frames in `[centerIndex - window.backward .. centerIndex + window.forward]`,
 * forward-biased (the playhead is moving forward — backfill behind it only
 * after the forward budget is satisfied). Skips frames already in the cache.
 * Runs up to [parallelism] extractions concurrently — same chunked policy
 * the in-Composable [EmbeddedVideoPlayer.preloadWindow] uses.
 *
 * Intentionally split out of the Composable so the integration test can drive
 * it without `rememberCoroutineScope` or a Compose runtime: the production
 * wrapper closes over `frameCache`, `videoFps`, `videoDurationMs` and the
 * real `extractFrameAtIndex`; the test wraps a [FakeFfmpeg] + an in-memory
 * `MutableMap`. Behavior is identical because the wrapper is a pure pass-
 * through.
 *
 * Bug context (see explore #260, ADR 1): the original [preloadWindow] routed
 * play-start through `PreloadStrategy.shouldReset(idx, idx)` which returned
 * `false` and the extend branch found an active scrub-window job and no-op'd.
 * The forward buffer therefore stayed at `SCRUB_WINDOW.forward = 300` (with
 * half wasted backward) instead of `PLAYBACK_WINDOW.forward = 500`. This
 * helper UNCONDITIONALLY uses the window passed in — the caller (always the
 * play-start `LaunchedEffect`) hardcodes `PLAYBACK_WINDOW`, never asks the
 * heuristic.
 *
 * `coroutineScope { ... }` is used to await all in-flight extractions before
 * returning, so cancellation propagates correctly: cancelling the parent job
 * (e.g. user pauses, then plays again) cancels every chunk's `async` and
 * lets [FakeFfmpeg.cancelledFrames] observe it.
 */
internal suspend fun forcePlaybackPreloadCore(
    centerIndex: Int,
    totalFrames: Int,
    window: PreloadStrategy.Window,
    isCached: (Int) -> Boolean,
    extractFrame: suspend (Int) -> ImageBitmap?,
    putFrame: (Int, ImageBitmap) -> Unit,
    parallelism: Int = 3,
) {
    if (totalFrames <= 0) return
    val start = (centerIndex - window.backward).coerceAtLeast(0)
    val end = (centerIndex + window.forward).coerceAtMost(totalFrames - 1)
    val forwardIndices = (centerIndex..end).filter { !isCached(it) }
    val backwardIndices = (start until centerIndex).reversed().filter { !isCached(it) }
    val allIndices = forwardIndices + backwardIndices

    coroutineScope {
        allIndices.chunked(parallelism).forEach { chunk ->
            if (!isActive) return@forEach
            chunk.map { idx ->
                async {
                    if (!isActive || isCached(idx)) return@async
                    val bmp = extractFrame(idx)
                    if (bmp != null) putFrame(idx, bmp)
                }
            }.awaitAll()
        }
    }
}

private fun msToFrame(ms: Long, fps: Double, total: Int): Int =
    (ms * fps / 1000.0).toInt().coerceIn(0, (total - 1).coerceAtLeast(0))

private fun frameToMs(idx: Int, fps: Double): Long =
    (idx * 1000.0 / fps).toLong()

// v4.2.3: delegated to core.ToolResolver so Windows users with ffmpeg installed
// via WinGet / Scoop / Chocolatey get the correct path. Fallback to bare "ffmpeg"
// / "ffprobe" string keeps the old behavior where OS PATH resolution might still
// work even if our explicit locations don't match.
private fun findFfmpeg(): String = com.gameperf.desktop.core.ToolResolver.find("ffmpeg") ?: "ffmpeg"

private fun findFfprobe(): String = com.gameperf.desktop.core.ToolResolver.find("ffprobe") ?: "ffprobe"

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
