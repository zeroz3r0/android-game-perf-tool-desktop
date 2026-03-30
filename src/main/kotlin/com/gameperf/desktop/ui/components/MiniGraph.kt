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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gameperf.desktop.ui.theme.*

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
