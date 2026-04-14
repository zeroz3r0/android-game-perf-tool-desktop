package com.gameperf.desktop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gameperf.desktop.ui.theme.*
import com.gameperf.desktop.ui.util.formatTimeMs
import com.gameperf.desktop.viewmodel.SessionMarker
import java.util.Locale

/**
 * Interactive timeline with FPS data overlay, color-coded markers, and a draggable playhead.
 *
 * Layout (top to bottom):
 * 1. Y-axis FPS labels (0, 15, 30, 45, 60) on the left
 * 2. FPS overlay graph with colored performance zones and filled area under the line
 * 3. Marker lane — colored vertical bars with flag labels
 * 4. Scrub bar with draggable playhead and vertical line through chart
 * 5. X-axis time tick marks (interval auto-adjusted to current zoom level)
 *
 * Interactions:
 * - Drag (including drag start = tap-to-seek): seeks video, pauses playback via onScrubStart
 * - Long press: adds a marker at that timestamp
 * - **v3.1.11**: Ctrl + mouse scroll: zoom in/out around the cursor position
 * - **v3.1.11**: Middle-click drag (or Shift + drag): pan the zoomed viewport horizontally
 * - **v3.1.11**: Double-click on the zoom-reset hint: reset viewport to full duration
 *
 * The viewport is local component state — zoom level does not affect the underlying data,
 * the playback, or any other consumer of the timeline. Resetting the viewport (or scrolling
 * out fully) restores the original full-timeline view.
 */
@Composable
fun InteractiveTimeline(
    durationMs: Long,
    currentTimeMs: Long,
    fpsData: List<Pair<Int, Int>>,  // (second, fps) data points
    markers: List<SessionMarker>,
    onSeek: (Long) -> Unit,
    onScrubStart: () -> Unit,           // pause playback when user starts scrubbing
    onScrubEnd: () -> Unit,             // optionally resume after scrub
    onRequestAddMarker: (Long) -> Unit, // long-press to add marker
    modifier: Modifier = Modifier
) {
    if (durationMs <= 0) return

    // Left margin for Y-axis labels
    val yAxisWidth = 32.dp

    // ── v3.1.11: Viewport state for zoom + pan ──
    var viewStartMs by remember(durationMs) { mutableStateOf(0L) }
    var viewEndMs by remember(durationMs) { mutableStateOf(durationMs) }
    val viewDurationMs = (viewEndMs - viewStartMs).coerceAtLeast(1L)
    val isZoomed = viewStartMs > 0L || viewEndMs < durationMs

    // ── Hover tooltip + pan state ──
    // hoverX: canvas-X of the mouse cursor (-1 = not hovering)
    // isPanMode: true when Shift is held → drag pans the viewport instead of scrubbing
    var hoverX by remember { mutableStateOf(-1f) }
    var isPanMode by remember { mutableStateOf(false) }

    // Helper: convert a session-time-ms to canvas-X-pixels (clamped 0..w).
    fun timeMsToX(timeMs: Long, w: Float): Float =
        ((timeMs - viewStartMs).toFloat() / viewDurationMs.toFloat() * w).coerceIn(0f, w)

    // Helper: convert a canvas-X-pixel to session-time-ms (clamped to viewport).
    fun xToTimeMs(x: Float, w: Float): Long =
        (viewStartMs + (x / w * viewDurationMs).toLong()).coerceIn(viewStartMs, viewEndMs)

    Column(modifier = modifier) {
        // FPS chart + markers + scrub bar all in a single Canvas for precision
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .background(Color(0xFF0D1117), RoundedCornerShape(8.dp))
        ) {
            Row(Modifier.fillMaxSize()) {
                // Y-axis labels
                Column(
                    modifier = Modifier
                        .width(yAxisWidth)
                        .fillMaxHeight()
                        .padding(top = 4.dp, bottom = 30.dp), // align with chart area
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    listOf(60, 45, 30, 15, 0).forEach { fps ->
                        Text(
                            "$fps",
                            color = when {
                                fps >= 55 -> Green.copy(alpha = 0.7f)
                                fps >= 30 -> Yellow.copy(alpha = 0.7f)
                                fps > 0 -> Red.copy(alpha = 0.7f)
                                else -> TextDim
                            },
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Main chart area
                Canvas(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(end = 8.dp, top = 4.dp, bottom = 4.dp)
                        .pointerInput(durationMs) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    if (isPanMode) return@detectDragGestures // pan: no scrub
                                    onScrubStart()
                                    val seekMs = (viewStartMs + (offset.x / size.width * viewDurationMs).toLong())
                                        .coerceIn(viewStartMs, viewEndMs)
                                    onSeek(seekMs)
                                },
                                onDrag = { change, delta ->
                                    change.consume()
                                    if (isPanMode && isZoomed) {
                                        // Pan: translate the viewport by the drag delta
                                        val w = size.width.toFloat()
                                        if (w > 0f) {
                                            val deltaMs = -(delta.x / w * viewDurationMs).toLong()
                                            val newStart = (viewStartMs + deltaMs)
                                                .coerceIn(0L, durationMs - viewDurationMs)
                                            viewStartMs = newStart
                                            viewEndMs = newStart + viewDurationMs
                                        }
                                    } else {
                                        val seekMs = (viewStartMs + (change.position.x / size.width * viewDurationMs).toLong())
                                            .coerceIn(viewStartMs, viewEndMs)
                                        onSeek(seekMs)
                                    }
                                },
                                onDragEnd = {
                                    if (!isPanMode) onScrubEnd()
                                },
                                onDragCancel = {
                                    if (!isPanMode) onScrubEnd()
                                }
                            )
                        }
                        .pointerInput(durationMs) {
                            detectTapGestures(
                                onLongPress = { offset ->
                                    val seekMs = (viewStartMs + (offset.x / size.width * viewDurationMs).toLong())
                                        .coerceIn(viewStartMs, viewEndMs)
                                    onRequestAddMarker(seekMs)
                                },
                                // v3.1.11: double tap resets the viewport to full duration.
                                onDoubleTap = {
                                    viewStartMs = 0L
                                    viewEndMs = durationMs
                                }
                            )
                        }
                        // Ctrl+scroll zoom, Shift state tracking, and hover position.
                        .pointerInput(durationMs) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: continue
                                    val w = size.width.toFloat()

                                    when (event.type) {
                                        // ── Hover tracking ──
                                        PointerEventType.Move -> {
                                            hoverX = change.position.x.coerceIn(0f, w)
                                            isPanMode = event.keyboardModifiers.isShiftPressed && isZoomed
                                        }
                                        PointerEventType.Enter -> {
                                            hoverX = change.position.x.coerceIn(0f, w)
                                        }
                                        PointerEventType.Exit -> {
                                            hoverX = -1f
                                            isPanMode = false
                                        }
                                        // ── Ctrl + scroll zoom (unchanged) ──
                                        PointerEventType.Scroll -> {
                                            if (!event.keyboardModifiers.isCtrlPressed) continue
                                            val scrollY = change.scrollDelta.y
                                            if (scrollY == 0f || w <= 0f) continue

                                            val cursorX = change.position.x.coerceIn(0f, w)
                                            val pivotMs = (viewStartMs + (cursorX / w * viewDurationMs).toLong())
                                                .coerceIn(0L, durationMs)

                                            val zoomFactor = if (scrollY < 0) 0.85f else 1.18f
                                            val newDuration = (viewDurationMs * zoomFactor).toLong()
                                                .coerceIn(1000L, durationMs)

                                            val pivotFraction = cursorX / w
                                            val newStart = (pivotMs - (pivotFraction * newDuration).toLong())
                                                .coerceIn(0L, durationMs - newDuration)

                                            viewStartMs = newStart
                                            viewEndMs = newStart + newDuration
                                            change.consume()
                                        }
                                        else -> {}
                                    }
                                }
                            }
                        }
                ) {
                    val w = size.width
                    val h = size.height
                    val fpsAreaHeight = h * 0.60f
                    val markerLaneY = fpsAreaHeight
                    val markerLaneHeight = h * 0.08f
                    val scrubY = markerLaneY + markerLaneHeight
                    val scrubHeight = h * 0.08f
                    val timeAxisY = scrubY + scrubHeight + 4f
                    // Remaining is for spacing / time ticks

                    // ── 1. FPS Zone Background ──
                    drawFpsZones(w, fpsAreaHeight)

                    // ── 2. FPS Line Chart with filled area ──
                    // v3.1.11: pass viewport so the line is rendered for the visible
                    // window only. Outside-viewport points are clipped.
                    if (fpsData.isNotEmpty()) {
                        drawFpsLineWithFill(fpsData, viewStartMs, viewEndMs, w, fpsAreaHeight)
                    }

                    // ── 3. Marker Vertical Bars ──
                    // v3.1.11: only draw markers whose timestamp is in the viewport.
                    markers.forEach { marker ->
                        if (marker.timestampMs < viewStartMs || marker.timestampMs > viewEndMs) return@forEach
                        val markerX = ((marker.timestampMs - viewStartMs).toFloat() / viewDurationMs * w)
                        if (markerX in 0f..w) {
                            val mColor = parseColorHex(marker.colorHex)

                            // Subtle background tint behind marker
                            drawRect(
                                color = mColor.copy(alpha = 0.06f),
                                topLeft = Offset(markerX - 4f, 0f),
                                size = Size(8f, fpsAreaHeight)
                            )

                            // Dashed vertical line spanning FPS area
                            drawLine(
                                color = mColor.copy(alpha = 0.8f),
                                start = Offset(markerX, 0f),
                                end = Offset(markerX, fpsAreaHeight),
                                strokeWidth = 2f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
                            )

                            // Larger diamond at top of marker
                            drawCircle(
                                color = mColor,
                                radius = 8f,
                                center = Offset(markerX, 10f)
                            )

                            // Marker title text next to diamond
                            val title = marker.title.ifEmpty { marker.type.label }
                            drawContext.canvas.nativeCanvas.apply {
                                val paint = org.jetbrains.skia.Paint().apply {
                                    color = org.jetbrains.skia.Color.makeARGB(
                                        (mColor.alpha * 255).toInt(),
                                        (mColor.red * 255).toInt(),
                                        (mColor.green * 255).toInt(),
                                        (mColor.blue * 255).toInt()
                                    )
                                }
                                val font = org.jetbrains.skia.Font(null, 10f)
                                drawString(title, markerX + 12f, 14f, font, paint)
                            }

                            // Wider colored bar in marker lane
                            drawRect(
                                color = mColor.copy(alpha = 0.6f),
                                topLeft = Offset(markerX - 4f, markerLaneY),
                                size = Size(8f, markerLaneHeight)
                            )
                        }
                    }

                    // ── 4. Scrub Bar ──
                    val scrubTrackY = scrubY + scrubHeight / 2 - 2f
                    // Track background
                    drawRect(
                        color = Color.White.copy(alpha = 0.08f),
                        topLeft = Offset(0f, scrubTrackY),
                        size = Size(w, 4f)
                    )
                    // Elapsed portion
                    // v3.1.11: respect viewport. The playhead may be OUTSIDE the visible
                    // viewport (when zoomed in to a section that doesn't include current
                    // playback position) — in that case we render at -1f / w+1f to keep
                    // it off-screen, and the elapsed-bar fills proportionally.
                    val playheadInView = currentTimeMs in viewStartMs..viewEndMs
                    val playheadX = if (playheadInView) {
                        ((currentTimeMs - viewStartMs).toFloat() / viewDurationMs * w).coerceIn(0f, w)
                    } else if (currentTimeMs < viewStartMs) {
                        -20f  // off-screen left
                    } else {
                        w + 20f  // off-screen right
                    }
                    // Elapsed bar fills the visible portion of the viewport.
                    val elapsedFillWidth = if (currentTimeMs >= viewEndMs) w
                                            else if (currentTimeMs <= viewStartMs) 0f
                                            else playheadX
                    drawRect(
                        color = Cyan.copy(alpha = 0.5f),
                        topLeft = Offset(0f, scrubTrackY),
                        size = Size(elapsedFillWidth, 4f)
                    )

                    // Playhead vertical line through the entire chart
                    drawLine(
                        color = Cyan.copy(alpha = 0.4f),
                        start = Offset(playheadX, 0f),
                        end = Offset(playheadX, fpsAreaHeight),
                        strokeWidth = 1.5f
                    )

                    // Bigger playhead circle
                    drawCircle(
                        color = Cyan,
                        radius = 10f,
                        center = Offset(playheadX, scrubTrackY + 2f)
                    )
                    // Playhead glow
                    drawCircle(
                        color = Cyan.copy(alpha = 0.25f),
                        radius = 16f,
                        center = Offset(playheadX, scrubTrackY + 2f)
                    )

                    // ── 5. Current FPS tooltip near playhead ──
                    if (fpsData.isNotEmpty()) {
                        val currentSec = (currentTimeMs / 1000.0).toFloat()
                        // Find closest FPS data point
                        val closest = fpsData.minByOrNull { kotlin.math.abs(it.first - currentSec) }
                        if (closest != null) {
                            val fpsVal = closest.second
                            val maxFps = 65f
                            val fpsY = (fpsAreaHeight - (fpsVal.coerceIn(0, 65).toFloat() / maxFps * fpsAreaHeight)).coerceIn(0f, fpsAreaHeight)

                            // Small dot at intersection
                            drawCircle(
                                color = Color.White,
                                radius = 4f,
                                center = Offset(playheadX, fpsY)
                            )

                            // FPS value label
                            drawContext.canvas.nativeCanvas.apply {
                                val paint = org.jetbrains.skia.Paint().apply {
                                    color = org.jetbrains.skia.Color.makeARGB(255, 255, 255, 255)
                                }
                                val font = org.jetbrains.skia.Font(null, 11f)
                                val label = String.format(Locale.US, "%d fps", fpsVal)
                                // Position above or below the dot depending on space
                                val labelY = if (fpsY > 20f) fpsY - 8f else fpsY + 16f
                                val labelX = if (playheadX > w - 50f) playheadX - 45f else playheadX + 8f
                                drawString(label, labelX, labelY, font, paint)
                            }
                        }
                    }

                    // ── 5b. Hover tooltip — FPS at cursor position ──
                    // Shows the exact FPS value at the hovered time, independent of playhead.
                    if (hoverX >= 0f && fpsData.isNotEmpty()) {
                        val hoverMs = (viewStartMs + (hoverX / w * viewDurationMs).toLong())
                            .coerceIn(viewStartMs, viewEndMs)
                        val hoverSec = (hoverMs / 1000.0).toFloat()
                        val hoverClosest = fpsData.minByOrNull { kotlin.math.abs(it.first - hoverSec) }
                        if (hoverClosest != null) {
                            val hovFps = hoverClosest.second
                            val maxFps = 65f
                            val hovY = (fpsAreaHeight - (hovFps.coerceIn(0, 65).toFloat() / maxFps * fpsAreaHeight))
                                .coerceIn(0f, fpsAreaHeight)

                            // Vertical cursor line (dim, so it doesn't compete with playhead)
                            drawLine(
                                color = Color.White.copy(alpha = 0.20f),
                                start = Offset(hoverX, 0f),
                                end = Offset(hoverX, fpsAreaHeight),
                                strokeWidth = 1f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f))
                            )

                            // Dot on FPS line at hover position
                            drawCircle(
                                color = Color.White.copy(alpha = 0.75f),
                                radius = 3.5f,
                                center = Offset(hoverX, hovY)
                            )

                            // FPS + time label box
                            val fpsColor = when {
                                hovFps >= 55 -> Color(0xFF00FF88)
                                hovFps >= 30 -> Color(0xFFFFAA00)
                                else -> Color(0xFFFF4466)
                            }
                            drawContext.canvas.nativeCanvas.apply {
                                val paint = org.jetbrains.skia.Paint().apply {
                                    color = org.jetbrains.skia.Color.makeARGB(
                                        (fpsColor.alpha * 255).toInt(),
                                        (fpsColor.red * 255).toInt(),
                                        (fpsColor.green * 255).toInt(),
                                        (fpsColor.blue * 255).toInt()
                                    )
                                }
                                val font = org.jetbrains.skia.Font(null, 10.5f)
                                val label = String.format(Locale.US, "%d fps", hovFps)
                                // Flip label side to avoid overflow at right edge
                                val labelX = if (hoverX > w - 55f) hoverX - 50f else hoverX + 6f
                                val labelY = if (hovY > 18f) hovY - 6f else hovY + 14f
                                drawString(label, labelX, labelY, font, paint)
                            }
                        }
                    }

                    // ── 6. FPS reference lines ──
                    val maxFps = 65f
                    // 30 FPS line
                    val y30 = fpsAreaHeight - (30f / maxFps * fpsAreaHeight)
                    drawLine(
                        color = Yellow.copy(alpha = 0.3f),
                        start = Offset(0f, y30),
                        end = Offset(w, y30),
                        strokeWidth = 1f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
                    )
                    // 60 FPS line
                    val y60 = fpsAreaHeight - (60f / maxFps * fpsAreaHeight)
                    drawLine(
                        color = Green.copy(alpha = 0.3f),
                        start = Offset(0f, y60),
                        end = Offset(w, y60),
                        strokeWidth = 1f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
                    )

                    // ── 7. Time tick marks (interval auto-adapts to zoom level) ──
                    // v3.1.11: when zoomed in, ticks are denser. We aim for ~6-12 ticks
                    // visible across the viewport regardless of zoom level.
                    val viewDurationSec = viewDurationMs / 1000f
                    val tickInterval = chooseTickInterval(viewDurationSec)
                    val viewStartSec = (viewStartMs / 1000f).toInt()
                    val viewEndSec = (viewEndMs / 1000f).toInt()
                    // Round up to the next tick boundary so ticks are at "nice" multiples.
                    var tickSec = ((viewStartSec / tickInterval) + 1) * tickInterval
                    while (tickSec < viewEndSec) {
                        val tickX = ((tickSec - viewStartSec).toFloat() / viewDurationSec * w)
                        if (tickX in 0f..w) {
                            // Small vertical tick mark
                            drawLine(
                                color = TextDim,
                                start = Offset(tickX, timeAxisY),
                                end = Offset(tickX, timeAxisY + 6f),
                                strokeWidth = 1f
                            )
                            // Time label
                            drawContext.canvas.nativeCanvas.apply {
                                val paint = org.jetbrains.skia.Paint().apply {
                                    color = org.jetbrains.skia.Color.makeARGB(
                                        (TextDim.alpha * 255).toInt(),
                                        (TextDim.red * 255).toInt(),
                                        (TextDim.green * 255).toInt(),
                                        (TextDim.blue * 255).toInt()
                                    )
                                }
                                val font = org.jetbrains.skia.Font(null, 9f)
                                val minutes = tickSec / 60
                                val seconds = tickSec % 60
                                val label = String.format(Locale.US, "%d:%02d", minutes, seconds)
                                drawString(label, tickX - 10f, timeAxisY + 16f, font, paint)
                            }
                        }
                        tickSec += tickInterval
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // ── Time Labels ──
        // v3.1.11: when zoomed, the labels show the viewport range, not the full session.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(formatTimeMs(viewStartMs), color = TextDim, fontSize = 10.sp)
            Text(
                formatTimeMs(currentTimeMs),
                color = Cyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Text(formatTimeMs(viewEndMs), color = TextDim, fontSize = 10.sp)
        }

        // ── Hint ──
        Spacer(Modifier.height(2.dp))
        Text(
            text = if (isZoomed) {
                "Zoom: ${formatTimeMs(viewDurationMs)} visible • Shift+arrastrar para mover vista • Doble clic para resetear • Ctrl+rueda para zoom"
            } else {
                "Ctrl+rueda para zoom • Clic para posicionar • Arrastrar para scrub • Mantener pulsado para marcador"
            },
            color = if (isZoomed) Cyan.copy(alpha = 0.7f) else TextDim,
            fontSize = 9.sp,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}

/**
 * Pick a sensible tick interval (in seconds) for the visible viewport so the timeline
 * shows ~6-12 ticks across regardless of zoom level. Hand-picked breakpoints rather than
 * `viewDurationSec / 10` because round numbers (1, 5, 10, 30, 60, 120) read better than
 * arbitrary intervals like 7s or 13s.
 */
private fun chooseTickInterval(viewDurationSec: Float): Int = when {
    viewDurationSec <= 10 -> 1        // 1s ticks for very tight zoom
    viewDurationSec <= 30 -> 2        // 2s ticks
    viewDurationSec <= 60 -> 5        // 5s ticks
    viewDurationSec <= 120 -> 10      // 10s ticks
    viewDurationSec <= 300 -> 20      // 20s ticks (5 min view)
    viewDurationSec <= 600 -> 30      // 30s ticks (10 min view)
    viewDurationSec <= 1800 -> 60     // 1 min ticks (30 min view)
    else -> 120                       // 2 min ticks for very long sessions
}

/** Draw background colored zones: red (<20), yellow (20-30), green (>30). */
private fun DrawScope.drawFpsZones(w: Float, h: Float) {
    val maxFps = 65f
    // Red zone: 0-20
    val y20 = h - (20f / maxFps * h)
    drawRect(
        color = Color(0xFFFF0044).copy(alpha = 0.04f),
        topLeft = Offset(0f, y20),
        size = Size(w, h - y20)
    )
    // Yellow zone: 20-30
    val y30 = h - (30f / maxFps * h)
    drawRect(
        color = Color(0xFFFFAA00).copy(alpha = 0.03f),
        topLeft = Offset(0f, y30),
        size = Size(w, y20 - y30)
    )
}

/**
 * Draw the FPS line chart with a thicker line and filled gradient area underneath.
 *
 * v3.1.11: respects a viewport `[viewStartMs, viewEndMs]` so when the user zooms in,
 * only the visible portion of the FPS line is rendered. The fpsData is in
 * `(second, fps)` pairs (one second resolution from the polling loop).
 *
 * The viewport-aware coordinate transformation maps `sec * 1000` to canvas X via
 * `((sec*1000 - viewStartMs) / (viewEndMs - viewStartMs)) * w`. Points outside the
 * viewport are still iterated (so the line stays connected at the edges) but their
 * X is clamped to 0..w which would distort the line if we drew them naively. Instead
 * we filter to only points whose timestamp is in [viewStart - oneSec, viewEnd + oneSec]
 * (one second of slack on each side so the visible line connects to neighbors).
 */
private fun DrawScope.drawFpsLineWithFill(
    fpsData: List<Pair<Int, Int>>,
    viewStartMs: Long,
    viewEndMs: Long,
    w: Float,
    h: Float
) {
    if (fpsData.size < 2) return
    val maxFps = 65f
    val viewDurationMs = (viewEndMs - viewStartMs).coerceAtLeast(1L).toFloat()
    val viewStartSec = viewStartMs / 1000f
    val viewEndSec = viewEndMs / 1000f

    // Filter to visible points + one second of slack on each side so the line connects
    // to off-screen neighbors at the viewport edges.
    val visiblePoints = fpsData.filter { (sec, _) ->
        sec >= (viewStartSec - 1f).toInt() && sec <= (viewEndSec + 1f).toInt()
    }
    if (visiblePoints.size < 2) return

    fun secToX(sec: Int): Float {
        val timeMs = sec * 1000f
        return ((timeMs - viewStartMs) / viewDurationMs * w)
    }

    // Build line path
    val linePath = Path()
    val fillPath = Path()
    visiblePoints.forEachIndexed { i, (sec, fps) ->
        val x = secToX(sec)
        val y = (h - (fps.coerceIn(0, 65).toFloat() / maxFps * h)).coerceIn(0f, h)
        if (i == 0) {
            linePath.moveTo(x, y)
            fillPath.moveTo(x, h) // start fill from bottom
            fillPath.lineTo(x, y)
        } else {
            linePath.lineTo(x, y)
            fillPath.lineTo(x, y)
        }
    }

    // Close fill path at the bottom
    val lastX = secToX(visiblePoints.last().first)
    fillPath.lineTo(lastX, h)
    fillPath.close()

    // Filled area with gradient alpha
    drawPath(
        fillPath,
        brush = Brush.verticalGradient(
            colors = listOf(
                Cyan.copy(alpha = 0.15f),
                Cyan.copy(alpha = 0.02f)
            ),
            startY = 0f,
            endY = h
        )
    )

    // Thicker main line
    drawPath(linePath, Cyan, style = Stroke(width = 3f))

    // Draw colored segments for FPS < 30 and < 20
    for (i in 1 until visiblePoints.size) {
        val (sec0, fps0) = visiblePoints[i - 1]
        val (sec1, fps1) = visiblePoints[i]
        val avgFps = (fps0 + fps1) / 2
        val segColor = when {
            avgFps < 20 -> Color(0xFFFF0044)
            avgFps < 30 -> Color(0xFFFFAA00)
            else -> continue
        }
        val x0 = secToX(sec0)
        val x1 = secToX(sec1)
        val y0 = (h - (fps0.coerceIn(0, 65).toFloat() / maxFps * h)).coerceIn(0f, h)
        val y1 = (h - (fps1.coerceIn(0, 65).toFloat() / maxFps * h)).coerceIn(0f, h)
        drawLine(segColor, Offset(x0, y0), Offset(x1, y1), strokeWidth = 3.5f)
    }
}

/** Parse a hex color string like "#FF6600" to a Compose Color. */
internal fun parseColorHex(hex: String): Color {
    return try {
        val clean = hex.removePrefix("#")
        val argb = when (clean.length) {
            6 -> (0xFF000000 or clean.toLong(16)).toULong()
            8 -> clean.toLong(16).toULong()
            else -> 0xFFFF0000u
        }
        Color(argb.toLong())
    } catch (_: Exception) {
        Color(0xFFFF0000)
    }
}

// formatTimeMs moved to com.gameperf.desktop.ui.util.Formatting
