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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gameperf.desktop.ui.theme.*
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
 * 5. X-axis time tick marks every 10 seconds
 *
 * Interactions:
 * - Drag (including drag start = tap-to-seek): seeks video, pauses playback via onScrubStart
 * - Long press: adds a marker at that timestamp
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
                                    onScrubStart()
                                    val seekMs = (offset.x / size.width * durationMs).toLong()
                                        .coerceIn(0, durationMs)
                                    onSeek(seekMs)
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val seekMs = (change.position.x / size.width * durationMs).toLong()
                                        .coerceIn(0, durationMs)
                                    onSeek(seekMs)
                                },
                                onDragEnd = {
                                    onScrubEnd()
                                },
                                onDragCancel = {
                                    onScrubEnd()
                                }
                            )
                        }
                        .pointerInput(durationMs) {
                            detectTapGestures(
                                onLongPress = { offset ->
                                    val seekMs = (offset.x / size.width * durationMs).toLong()
                                        .coerceIn(0, durationMs)
                                    onRequestAddMarker(seekMs)
                                }
                            )
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
                    if (fpsData.isNotEmpty()) {
                        drawFpsLineWithFill(fpsData, durationMs, w, fpsAreaHeight)
                    }

                    // ── 3. Marker Vertical Bars ──
                    markers.forEach { marker ->
                        val markerX = (marker.timestampMs.toFloat() / durationMs * w)
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
                    val playheadX = (currentTimeMs.toFloat() / durationMs * w).coerceIn(0f, w)
                    drawRect(
                        color = Cyan.copy(alpha = 0.5f),
                        topLeft = Offset(0f, scrubTrackY),
                        size = Size(playheadX, 4f)
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

                    // ── 7. Time tick marks every 10 seconds ──
                    val durationSec = durationMs / 1000f
                    val tickInterval = 10 // seconds
                    var tickSec = tickInterval
                    while (tickSec < durationSec) {
                        val tickX = (tickSec / durationSec * w)
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
                        tickSec += tickInterval
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // ── Time Labels ──
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(formatTimeMs(0), color = TextDim, fontSize = 10.sp)
            Text(
                formatTimeMs(currentTimeMs),
                color = Cyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Text(formatTimeMs(durationMs), color = TextDim, fontSize = 10.sp)
        }

        // ── Hint ──
        Spacer(Modifier.height(2.dp))
        Text(
            "Clic para posicionar • Arrastrar para scrub • Mantener pulsado para agregar marcador",
            color = TextDim,
            fontSize = 9.sp,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
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

/** Draw the FPS line chart with a thicker line and filled gradient area underneath. */
private fun DrawScope.drawFpsLineWithFill(
    fpsData: List<Pair<Int, Int>>,
    durationMs: Long,
    w: Float,
    h: Float
) {
    if (fpsData.size < 2) return
    val maxFps = 65f
    val durationSec = durationMs / 1000f

    // Build line path
    val linePath = Path()
    val fillPath = Path()
    fpsData.forEachIndexed { i, (sec, fps) ->
        val x = (sec / durationSec * w).coerceIn(0f, w)
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
    val lastSec = fpsData.last().first
    val lastX = (lastSec / durationSec * w).coerceIn(0f, w)
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
    for (i in 1 until fpsData.size) {
        val (sec0, fps0) = fpsData[i - 1]
        val (sec1, fps1) = fpsData[i]
        val avgFps = (fps0 + fps1) / 2
        val segColor = when {
            avgFps < 20 -> Color(0xFFFF0044)
            avgFps < 30 -> Color(0xFFFFAA00)
            else -> continue
        }
        val x0 = (sec0 / durationSec * w).coerceIn(0f, w)
        val x1 = (sec1 / durationSec * w).coerceIn(0f, w)
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

/** Format milliseconds to MM:SS. */
internal fun formatTimeMs(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}
