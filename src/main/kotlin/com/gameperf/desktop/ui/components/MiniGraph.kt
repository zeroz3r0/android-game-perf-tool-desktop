package com.gameperf.desktop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gameperf.desktop.ui.theme.*
import com.gameperf.desktop.viewmodel.MarkerType
import com.gameperf.desktop.viewmodel.SessionMarker

@Composable
fun MiniGraph(
    label: String,
    values: List<Number>,
    color: Color = Cyan,
    maxValue: Float? = null,
    modifier: Modifier = Modifier.fillMaxWidth().height(100.dp)
) {
    Column(modifier = modifier) {
        Text(label, color = TextSecondary, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF0D1117), RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            if (values.size < 2) return@Canvas
            val floats = values.map { it.toFloat() }
            val max = maxValue ?: (floats.maxOrNull() ?: 1f) * 1.1f
            val min = 0f
            val range = if (max - min > 0) max - min else 1f
            val w = size.width
            val h = size.height
            val step = w / (floats.size - 1).coerceAtLeast(1)

            val path = Path()
            floats.forEachIndexed { i, v ->
                val x = i * step
                val y = h - ((v - min) / range * h)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(width = 2f))

            // Last value dot
            val lastX = (floats.size - 1) * step
            val lastY = h - ((floats.last() - min) / range * h)
            drawCircle(color, radius = 4f, center = Offset(lastX, lastY))
        }
    }
}

/**
 * FPS graph with vertical marker lines overlaid.
 * Each marker is drawn as a dashed vertical line at the corresponding second.
 */
@Composable
fun MiniGraphWithMarkers(
    label: String,
    values: List<Number>,
    color: Color = Cyan,
    maxValue: Float? = null,
    markers: List<SessionMarker>,
    totalSeconds: Int,
    modifier: Modifier = Modifier.fillMaxWidth().height(160.dp)
) {
    Column(modifier = modifier) {
        // Label row with marker legend
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = TextSecondary, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                markers.map { it.type }.distinct().forEach { type ->
                    Row {
                        Canvas(Modifier.size(8.dp).padding(top = 4.dp)) {
                            drawCircle(markerTypeColor(type), radius = size.minDimension / 2)
                        }
                        Spacer(Modifier.width(3.dp))
                        Text(type.label, color = TextDim, fontSize = 9.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF0D1117), RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            if (values.size < 2) return@Canvas
            val floats = values.map { it.toFloat() }
            val max = maxValue ?: (floats.maxOrNull() ?: 1f) * 1.1f
            val min = 0f
            val range = if (max - min > 0) max - min else 1f
            val w = size.width
            val h = size.height
            val step = w / (floats.size - 1).coerceAtLeast(1)

            // Draw marker lines FIRST (behind the graph)
            val maxSec = if (totalSeconds > 0) totalSeconds else floats.size
            markers.forEach { marker ->
                val markerX = if (maxSec > 0) (marker.timestampSeconds.toFloat() / maxSec * w) else 0f
                if (markerX in 0f..w) {
                    val mColor = markerTypeColor(marker.type)
                    // Dashed vertical line
                    drawLine(
                        color = mColor.copy(alpha = 0.7f),
                        start = Offset(markerX, 0f),
                        end = Offset(markerX, h),
                        strokeWidth = 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
                    )
                    // Small diamond at top
                    drawCircle(mColor, radius = 4f, center = Offset(markerX, 6f))
                }
            }

            // Draw the FPS line
            val path = Path()
            floats.forEachIndexed { i, v ->
                val x = i * step
                val y = h - ((v - min) / range * h)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(width = 2f))

            // Last value dot
            val lastX = (floats.size - 1) * step
            val lastY = h - ((floats.last() - min) / range * h)
            drawCircle(color, radius = 4f, center = Offset(lastX, lastY))
        }
    }
}

/** Map marker type to Compose Color for graph overlays. */
private fun markerTypeColor(type: MarkerType): Color = when (type) {
    MarkerType.INTERSTITIAL -> Color(0xFFFF6600)
    MarkerType.VIDEO_REWARD -> Color(0xFF7B2CBF)
    MarkerType.LOADING -> Color(0xFFFFAA00)
    MarkerType.SCENE_CHANGE -> Color(0xFF00D4FF)
    MarkerType.CUSTOM -> Color(0xFF00FF88)
}
