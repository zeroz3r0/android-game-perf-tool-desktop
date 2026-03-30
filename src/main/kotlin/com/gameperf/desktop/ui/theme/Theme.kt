package com.gameperf.desktop.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

val Cyan = Color(0xFF00D4FF)
val CyanDark = Color(0xFF0099BB)
val Purple = Color(0xFF7B2CBF)
val Green = Color(0xFF00FF88)
val Yellow = Color(0xFFFFAA00)
val Orange = Color(0xFFFF6600)
val Red = Color(0xFFFF0044)
val DarkBg = Color(0xFF1A1A2E)
val DarkSurface = Color(0xFF16213E)
val DarkCard = Color(0xFF1E2A45)
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFF888888)
val TextDim = Color(0xFF555555)

val AppColors = darkColorScheme(
    primary = Cyan,
    onPrimary = Color.Black,
    secondary = Purple,
    onSecondary = Color.White,
    background = DarkBg,
    surface = DarkSurface,
    surfaceVariant = DarkCard,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    error = Red,
    onError = Color.White
)

fun gradeColor(grade: Char): Color = when (grade) {
    'A' -> Green
    'B' -> Color(0xFF88FF00)
    'C' -> Yellow
    'D' -> Orange
    else -> Red
}

fun severityColor(severity: String): Color = when (severity.lowercase()) {
    "alto" -> Red
    "medio" -> Yellow
    else -> Cyan
}
