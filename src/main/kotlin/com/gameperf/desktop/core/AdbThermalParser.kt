package com.gameperf.desktop.core

import com.gameperf.desktop.core.model.ThermalSnapshot

/**
 * Pure thermal-output parser, extracted from [AdbBridge] in v4.3.6 so the
 * classifier-based parsing logic can be tested in isolation without spawning
 * any `adb` processes.
 *
 * See `sdd/grading-thermal-realism/explore` for the full context: pre-v4.3.6
 * the parser used substring matching on zone names (`type.contains("cpu")`)
 * which over-bucketed every CPU die zone on a Snapdragon 8 Gen 2 and produced
 * the 93°C "skin" temperature shown to the user. The classifier path here uses
 * exact-match sets + word-boundary regex (see [ThermalZoneClassifier]) and
 * separates die-CPU from skin so the UI can label each correctly.
 *
 * Plausibility windows (tightened from v4.2.5):
 *  - Skin / Battery: 0..60°C — case/cell never legitimately above 60°C.
 *  - DieCpu / DieGpu: 0..120°C — silicon can run hot but >120°C is a parse error.
 */
internal object AdbThermalParser {

    /**
     * Parse the multi-line `for z in /sys/class/thermal/thermal_zone*; do echo
     * "$(cat $z/type):$(cat $z/temp)"; done` output. Each non-empty
     * `type:temp_milli_c` pair is classified and the MAX value per category
     * is returned. Lines that fail to parse, fall outside the plausibility
     * window, or classify to `null` are silently skipped.
     */
    fun parseThermalZonesOutput(zones: String): ThermalSnapshot {
        var dieCpu = -1.0
        var gpu = -1.0
        var battery = -1.0
        var skin = -1.0
        for (line in zones.lines()) {
            val sep = line.indexOf(':')
            if (sep < 0) continue
            val type = line.substring(0, sep).trim()
            val raw = line.substring(sep + 1).trim().toLongOrNull() ?: continue
            val temp = if (raw > 1000) raw / 1000.0 else raw.toDouble()
            val category = ThermalZoneClassifier.classify(type) ?: continue
            if (!withinPlausibilityWindow(category, temp)) continue
            when (category) {
                ThermalCategory.Skin -> if (temp > skin) skin = temp
                ThermalCategory.DieCpu -> if (temp > dieCpu) dieCpu = temp
                ThermalCategory.DieGpu -> if (temp > gpu) gpu = temp
                ThermalCategory.Battery -> if (temp > battery) battery = temp
            }
        }
        return buildSnapshot(dieCpu, gpu, battery, skin)
    }

    /**
     * Pure merge of `dumpsys thermalservice` output into an existing snapshot.
     * Used as fallback when sysfs returned incomplete data (Android 10+ may
     * hide raw zones from non-root shells).
     */
    fun mergeThermalServiceFallback(
        existing: ThermalSnapshot,
        dump: String,
        thermalRegex: Regex,
    ): ThermalSnapshot {
        var dieCpu = existing.dieCpu
        var gpu = existing.gpu
        var battery = existing.battery
        var skin = existing.skin
        for (m in thermalRegex.findAll(dump)) {
            val v = m.groupValues[1].toDoubleOrNull() ?: continue
            // The dumpsys path uses the v4.2.5 wide envelope to stay
            // permissive — that fallback is already a degraded code path.
            if (v !in -40.0..150.0) continue
            val n = m.groupValues[2].trim()
            when (ThermalZoneClassifier.classify(n)) {
                ThermalCategory.Skin -> if (v > skin) skin = v
                ThermalCategory.DieCpu -> if (v > dieCpu) dieCpu = v
                ThermalCategory.DieGpu -> if (v > gpu) gpu = v
                ThermalCategory.Battery -> if (v > battery) battery = v
                null -> Unit
            }
        }
        return buildSnapshot(dieCpu, gpu, battery, skin)
    }

    private fun withinPlausibilityWindow(category: ThermalCategory, temp: Double): Boolean = when (category) {
        ThermalCategory.Skin, ThermalCategory.Battery -> temp in 0.0..60.0
        ThermalCategory.DieCpu, ThermalCategory.DieGpu -> temp in 0.0..120.0
    }

    /** Build a [ThermalSnapshot] with the legacy `cpu` field populated from skin/die. */
    private fun buildSnapshot(dieCpu: Double, gpu: Double, battery: Double, skin: Double): ThermalSnapshot {
        // Legacy `cpu` semantics — see ThermalSnapshot KDoc. Skin wins when
        // available so existing reports/exports show the user-facing temp.
        val legacyCpu = if (skin > 0) skin else dieCpu
        return ThermalSnapshot(cpu = legacyCpu, gpu = gpu, battery = battery, skin = skin, dieCpu = dieCpu)
    }
}
