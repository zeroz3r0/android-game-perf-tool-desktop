package com.gameperf.desktop.core.conclusions.rules

import com.gameperf.desktop.core.HardwareScoring
import com.gameperf.desktop.core.conclusions.Conclusion
import com.gameperf.desktop.core.conclusions.ConclusionInput
import com.gameperf.desktop.core.conclusions.Rule
import com.gameperf.desktop.core.conclusions.Severity

/**
 * Flags games whose `p99` FPS sits very close to 30 on devices that should be
 * comfortably running at 60 — i.e., a developer-side FPS cap that prevents the
 * device from delivering its potential.
 *
 * Predicate:
 *  - Device tier is HIGH or ULTRA_HIGH (lower tiers can't reliably do 60+
 *    anyway, so a 30-fps cap there is normal — CON-003 explicitly excludes
 *    them).
 *  - `filtered.p99` falls within ±5% of 30 fps. We use p99 (not max) because
 *    a single transient spike to 32 is not a "cap" — we want the upper plateau.
 *
 * Output is INFO, not WARNING — the cap may be intentional (battery, design).
 * The recommendation is informational, not a defect call.
 *
 * @since v4.4.0
 */
object Capped30FpsRule : Rule {
    override val id: String = "fps-cap-suspect"
    override val severity: Severity = Severity.INFO

    private const val CAP_TARGET_FPS = 30.0
    private const val CAP_DETECTION_RATIO = 0.95

    override fun matches(input: ConclusionInput): Boolean {
        val isHighTier = input.deviceTier == HardwareScoring.DeviceTier.HIGH ||
            input.deviceTier == HardwareScoring.DeviceTier.ULTRA_HIGH
        if (!isHighTier) return false
        val p99 = input.filtered.p99.toDouble()
        // p99 within ±5% of 30 fps is the cap signature.
        return p99 in (CAP_TARGET_FPS * CAP_DETECTION_RATIO)..(CAP_TARGET_FPS / CAP_DETECTION_RATIO)
    }

    override fun render(input: ConclusionInput): Conclusion {
        val p99 = input.filtered.p99
        return Conclusion(
            ruleId = id,
            severity = severity,
            headline = "El juego parece tener un cap de FPS a 30 (p99 = $p99) en un dispositivo " +
                "de gama alta capaz de más.",
            recommendation = "Si es intencional (decisión de diseño o ahorro de batería), está bien. " +
                "Si no, puede ser un FPS cap heredado de configuraciones por defecto, vsync forzado " +
                "a 30, o targets de Unity/Unreal. El usuario percibe un juego menos fluido del que " +
                "su teléfono podría ofrecer.",
        )
    }
}
