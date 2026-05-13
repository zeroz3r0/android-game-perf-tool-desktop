package com.gameperf.desktop.core.model

import kotlinx.serialization.Serializable

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
 *
 * v4.3.6 — semantics of [cpu] vs [dieCpu]:
 *  - [dieCpu] is the silicon (junction) temperature of the CPU. Routinely
 *    80-95°C under sustained load and NOT a problem unless > 95°C. Captured
 *    via the [com.gameperf.desktop.core.ThermalZoneClassifier] DieCpu bucket.
 *  - [cpu] is the LEGACY user-facing CPU temp field, kept for serialization
 *    compat with `.gameperf` exports written before v4.3.6. Its semantics are
 *    "the temperature the user sees as 'CPU temp' in the HUD/report" — equal
 *    to [skin] when skin is available, else equal to [dieCpu]. New callers
 *    SHOULD prefer [skin] / [dieCpu] explicitly.
 *  - [skin] is the case/skin estimator. Throttle threshold ~42°C.
 *
 *  v4.3.6 default for [dieCpu] is -1.0 to keep the data class compatible
 *  with kotlinx.serialization decoders for old exports that lack the field.
 *
 *  v4.4.1 -- additive widening for the "temperature-not-shown" change:
 *  - [thermalAvailable] defaults to `true` so all v4.3.x callers keep the
 *    same behavior. Set to `false` by [com.gameperf.desktop.core.AdbThermalParser]
 *    when no CPU/SKIN zone yields a valid temperature within the plausibility
 *    window (e.g. unsupported vendor, permission denied).
 *  - [diagnostic] is `null` on the happy path. When [thermalAvailable] is
 *    `false`, the parser populates a [ThermalDiagnostic] listing the raw
 *    vendor zone names + bucket counts + reason so the report HTML can
 *    surface a banner instead of rendering a misleading "0°C".
 *  - `@Serializable` is added so [ThermalDiagnostic] survives `.gameperf`
 *    export round-trip; pre-v4.4.1 JSON loads with the defaulted fields.
 */
@Serializable
data class ThermalSnapshot(
    val cpu: Double,
    val gpu: Double,
    val battery: Double,
    val skin: Double,
    val dieCpu: Double = -1.0,
    val thermalAvailable: Boolean = true,
    val diagnostic: ThermalDiagnostic? = null,
)

/**
 * v4.5.0 -- Platform-agnostic FPower snapshot (battery power normalised by FPS).
 *
 * `fpowerMwPerFrame = abs(currentMicroA) * voltageMicroV / 1e12 * 1000 / fps`
 *
 * PerfDog-style color bands quoted in the report HTML: green < 50,
 * amber 50-65, red > 65 mW/frame.
 *
 * Sentinel: all numeric fields -1.0 when the metric is unavailable.
 * [fpowerAvailable] defaults to `true` so pre-v4.5.0 `.gameperf` JSON loads
 * unchanged (mirrors ThermalSnapshot.thermalAvailable widening at line 71).
 * [diagnostic] is `null` on the happy path; populated by
 * [com.gameperf.desktop.core.FPowerParser] when the sysfs probe fails, so
 * the report HTML can surface a banner instead of a misleading "0 mW".
 *
 * See `sdd/fpower-metric/design` §3 + spec FPW-004.
 */
@Serializable
data class FPowerSnapshot(
    val fpowerMwPerFrame: Double = -1.0,
    val powerW: Double = -1.0,
    val currentMicroA: Double = -1.0,
    val voltageMicroV: Double = -1.0,
    val fpowerAvailable: Boolean = true,
    val diagnostic: FPowerDiagnostic? = null,
)

/**
 * v4.5.0 -- Platform-agnostic GPU usage snapshot (Android sysfs probe).
 *
 * Sentinel semantics:
 *  - [usagePct] is `[0, 100]` when [gpuAvailable] is `true`; `-1` otherwise.
 *  - [gpuAvailable] defaults to `false` (NOT `true` like [ThermalSnapshot]).
 *    Pre-v4.5.0 `.gameperf` exports never captured GPU, so deserialising one
 *    of those files into this class must report "no data" — opposite default
 *    from thermal (which DID exist pre-v4.4.1).
 *  - [diagnostic] is `null` on the happy path; populated by
 *    [com.gameperf.desktop.core.AdbBridge] when the probe pipeline fails, so
 *    the report HTML can surface a Spanish tuteo-formal banner instead of
 *    rendering a misleading "0%".
 *
 * iOS sessions always persist [gpuAvailable] = `false` (out of Sprint 1 scope).
 *
 * See `sdd/gpu-usage-percent/design` §2.3 + spec GPU-010.
 */
@Serializable
data class GpuSnapshot(
    val usagePct: Int = -1,
    val gpuAvailable: Boolean = false,
    val diagnostic: GpuDiagnostic? = null,
)

/**
 * v4.6.x -- Platform-agnostic network bandwidth snapshot (Android binder /
 * dumpsys probe).
 *
 * Sentinel semantics (mirrors GpuSnapshot v4.5.0 precedent):
 *  - [rxBytes] / [txBytes] are non-negative cumulative byte counters for the
 *    game UID when [networkAvailable] is `true`; `-1L` otherwise.
 *  - [networkAvailable] defaults to `false` -- pre-v4.6.x `.gameperf` exports
 *    never captured network, so deserialising one of those files must report
 *    "no data" (design D6 — same shape as `gpuAvailable=false` default).
 *  - [diagnostic] is `null` on the happy path; populated by
 *    [com.gameperf.desktop.core.AdbBridge] when the probe pipeline fails, so
 *    the report HTML can surface a Spanish tuteo-formal banner instead of
 *    rendering a misleading "0 KB/s".
 *
 * iOS sessions always persist [networkAvailable] = `false` (out of scope v1).
 *
 * See `sdd/network-bandwidth-total-app/spec` NET-001 and design §3.
 */
@Serializable
data class NetworkSnapshot(
    val rxBytes: Long = -1L,
    val txBytes: Long = -1L,
    val networkAvailable: Boolean = false,
    val diagnostic: NetworkDiagnostic? = null,
)
