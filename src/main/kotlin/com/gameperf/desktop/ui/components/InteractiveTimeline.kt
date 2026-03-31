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

/**
 * Interactive timeline with FPS data overlay, color-coded markers, and a draggable playhead.
 *
 * Layout (top to bottom):
 * 1. FPS overlay graph with colored performance zones
 * 2. Marker lane — colored vertical bars with flag labels
 * 3. Scrub bar with draggable playhead
 * 4. Time labels
 */
@Composable
fun InteractiveTimeline(
    durationMs: Long,
    currentTimeMs: Long,
    fpsData: List<Pair<Int, Int>>,  // (second, fps) data points
    markers: List<SessionMarker>,
    onSeek: (Long) -> Unit,
    onRequestAddMarker: (Long) -> Unit,  // right-click / secondary gesture to add marker
    modifier: Modifier = Modifier
) {
    if (durationMs <= 0) return

    Column(modifier = modifier) {
        // FPS chart + markers + scrub bar all in a single Canvas for precision
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .background(Color(0xFF0D1117), RoundedCornerShape(8.dp))
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .pointerInput(durationMs) {
                        detectTapGestures(
                            onTap = { offset ->
                                val seekMs = (offset.x / size.width * durationMs).toLong()
                                    .coerceIn(0, durationMs)
                                onSeek(seekMs)
                            },
                            onLongPress = { offset ->
                                val seekMs = (offset.x / size.width * durationMs).toLong()
                                    .coerceIn(0, durationMs)
                                onRequestAddMarker(seekMs)
                            }
                        )
                    }
                    .pointerInput(durationMs) {
                        detectDragGestures { change, _ ->
                            change.consume()
                            val seekMs = (change.position.x / size.width * durationMs).toLong()
                                .coerceIn(0, durationMs)
                            onSeek(seekMs)
                        }
                    }
            ) {
                val w = size.width
                val h = size.height
                val fpsAreaHeight = h * 0.65f
                val markerLaneY = fpsAreaHeight
                val markerLaneHeight = h * 0.10f
                val scrubY = markerLaneY + markerLaneHeight
                val scrubHeight = h * 0.10f
                // Remaining is for spacing

                // ── 1. FPS Zone Background ──
                drawFpsZones(w, fpsAreaHeight)

                // ── 2. FPS Line Chart ──
                if (fpsData.isNotEmpty()) {
                    drawFpsLine(fpsData, durationMs, w, fpsAreaHeight)
                }

                // ── 3. Marker Vertical Bars ──
                markers.forEach { marker ->
                    val markerX = (marker.timestampMs.toFloat() / durationMs * w)
                    if (markerX in 0f..w) {
                        val mColor = parseColorHex(marker.colorHex)

                        // Subtle background tint behind marker
                        drawRect(
                            color = mColor.copy(alpha = 0.06f),
                            topLeft = Offset(markerX - 2f, 0f),
                            size = Size(4f, fpsAreaHeight)
                        )

                        // Dashed vertical line spanning FPS area
                        drawLine(
                            color = mColor.copy(alpha = 0.8f),
                            start = Offset(markerX, 0f),
                            end = Offset(markerX, fpsAreaHeight),
                            strokeWidth = 2f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
                        )

                        // Diamond at top of marker
                        drawCircle(
                            color = mColor,
                            radius = 5f,
                            center = Offset(markerX, 8f)
                        )

                        // Small colored bar in marker lane
                        drawRect(
                            color = mColor.copy(alpha = 0.6f),
                            topLeft = Offset(markerX - 3f, markerLaneY),
                            size = Size(6f, markerLaneHeight)
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
                // Playhead circle
                drawCircle(
                    color = Cyan,
                    radius = 8f,
                    center = Offset(playheadX, scrubTrackY + 2f)
                )
                // Playhead glow
                drawCircle(
                    color = Cyan.copy(alpha = 0.25f),
                    radius = 14f,
                    center = Offset(playheadX, scrubTrackY + 2f)
                )

                // ── 5. FPS reference lines ──
                // 30 FPS line
                val y30 = fpsAreaHeight - (30f / 65f * fpsAreaHeight)
                drawLine(
                    color = Yellow.copy(alpha = 0.3f),
                    start = Offset(0f, y30),
                    end = Offset(w, y30),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
                )
                // 60 FPS line
                val y60 = fpsAreaHeight - (60f / 65f * fpsAreaHeight)
                drawLine(
                    color = Green.copy(alpha = 0.3f),
                    start = Offset(0f, y60),
                    end = Offset(w, y60),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
                )
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

/** Draw the FPS line chart over time. */
private fun DrawScope.drawFpsLine(
    fpsData: List<Pair<Int, Int>>,
    durationMs: Long,
    w: Float,
    h: Float
) {
    if (fpsData.size < 2) return
    val maxFps = 65f
    val durationSec = durationMs / 1000f

    val path = Path()
    fpsData.forEachIndexed { i, (sec, fps) ->
        val x = (sec / durationSec * w).coerceIn(0f, w)
        val y = (h - (fps.coerceIn(0, 65).toFloat() / maxFps * h)).coerceIn(0f, h)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, Cyan, style = Stroke(width = 2f))

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
        drawLine(segColor, Offset(x0, y0), Offset(x1, y1), strokeWidth = 2.5f)
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
    return "%02d:%02d".format(minutes, seconds)
}
