package com.gameperf.desktop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.dp
import com.gameperf.desktop.core.events.DetectedEvent

/** Cyan tint distinct from manual marker palette (orange / red / yellow / cyan dot). */
private val AutoEventCyan = Color(0xFF22D3EE)

/** Dash pattern for the auto-event vertical line. Slightly tighter than manual markers (6f, 4f). */
private val AutoEventDash = floatArrayOf(4f, 4f)

private const val AutoEventStrokeWidth = 2f

/**
 * v4.4.1 — wraps the existing [MiniGraph] FPS plot and overlays a vertical dashed cyan line
 * for each [DetectedEvent.startMs]. Mirrors the visual language of [MiniGraphWithMarkers] (the
 * manual-marker variant) but isolates the new behavior in a separate composable so the other
 * three live charts (CPU / Memoria / Temperatura) keep using the plain [MiniGraph] and stay
 * untouched.
 *
 * Bug 2 (auto-event-detection-not-marking) fix: prior to v4.4.1 the live `MiniGraph` for FPS
 * received no events list; only the supplementary "Auto: N eventos" pill was visible. This
 * wrapper restores parity with the manual marker dashed-line treatment so the user sees the
 * detector firing during capture.
 *
 * Events whose [DetectedEvent.startMs] falls outside `[captureStartMs, captureNowMs]` are
 * skipped via the `xFrac in 0f..1f` guard. This covers two spec scenarios from
 * `capture-live-feedback`:
 *  - "Late event after capture stop" — `startMs > captureNowMs` → `xFrac > 1f` → skipped, no crash.
 *  - "Event at capture-start edge" — `startMs == captureStartMs` → `xFrac == 0f` → drawn at left edge.
 *
 * If [captureStartMs] is `0L` (no active capture) or `totalMs <= 0L` (clock not advanced yet),
 * the overlay short-circuits without drawing anything.
 *
 * @param label Chart label rendered by the inner [MiniGraph].
 * @param values Numeric series plotted by the inner [MiniGraph].
 * @param captureStartMs Wall-clock ms when capture began. `0L` disables the overlay.
 * @param captureNowMs Wall-clock ms representing "right now" — typically `System.currentTimeMillis()`
 *   driven by Compose recomposition tick.
 * @param events Detected events to render as vertical lines.
 * @param color Color of the inner [MiniGraph] line (NOT the auto-event overlay color).
 * @param maxValue Optional fixed Y-axis max for the inner [MiniGraph].
 * @param modifier Outer modifier; defaults to [Modifier.fillMaxWidth].
 *
 * @since v4.4.1
 */
@Composable
fun MiniGraphWithEvents(
    label: String,
    values: List<Number>,
    captureStartMs: Long,
    captureNowMs: Long,
    events: List<DetectedEvent>,
    color: Color,
    maxValue: Float? = null,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    Box(modifier = modifier) {
        MiniGraph(
            label = label,
            values = values,
            color = color,
            maxValue = maxValue,
            modifier = Modifier.fillMaxSize(),
        )
        // Overlay aligns with the inner Canvas of MiniGraph, which sits below the 11sp label
        // (~16dp) and inside an 8dp content padding. Tunable if visual drift is observed.
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 16.dp, bottom = 8.dp),
        ) {
            val totalMs = captureNowMs - captureStartMs
            if (captureStartMs <= 0L || totalMs <= 0L) return@Canvas
            events.forEach { event ->
                val xFrac = (event.startMs - captureStartMs).toFloat() / totalMs.toFloat()
                if (xFrac in 0f..1f) {
                    val x = xFrac * size.width
                    drawLine(
                        color = AutoEventCyan,
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = AutoEventStrokeWidth,
                        pathEffect = PathEffect.dashPathEffect(AutoEventDash),
                    )
                }
            }
        }
    }
}
