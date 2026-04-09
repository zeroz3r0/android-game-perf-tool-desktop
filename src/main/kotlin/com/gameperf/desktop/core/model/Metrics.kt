package com.gameperf.desktop.core.model

/**
 * Platform-agnostic frame timing snapshot.
 * Replaces `AdbBridge.FrameSnapshot`.
 *
 * Sentinel: all fields -1 / -1.0 when capture fails or metric unavailable.
 */
data class FrameSnapshot(
    val fps: Int,
    val avgFrameTime: Double,
    val jankCount: Int,
    val stutterCount: Int,
)

/**
 * Platform-agnostic memory snapshot.
 * Replaces `AdbBridge.MemSnapshot`.
 *
 * For iOS, [nativeMb] and [javaMb] are always 0 (physFootprint only gives total).
 */
data class MemSnapshot(
    val totalMb: Long,
    val nativeMb: Long,
    val javaMb: Long,
)

/**
 * Platform-agnostic thermal snapshot.
 * Replaces `AdbBridge.ThermalSnapshot`.
 *
 * Sentinel: -1.0 means the metric is unavailable on this platform.
 * For iOS: [skin] is always -1.0 (never exposed by iOS).
 * For iOS: [gpu] is -1.0 when unavailable.
 */
data class ThermalSnapshot(
    val cpu: Double,
    val gpu: Double,
    val battery: Double,
    val skin: Double,
)
