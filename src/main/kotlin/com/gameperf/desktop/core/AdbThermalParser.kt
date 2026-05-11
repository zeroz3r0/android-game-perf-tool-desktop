package com.gameperf.desktop.core

import com.gameperf.desktop.core.model.ThermalDiagnostic
import com.gameperf.desktop.core.model.ThermalSnapshot
import com.gameperf.desktop.core.model.ThermalUnavailableReason

/**
 * Pure thermal-output parser, extracted from [AdbBridge] in v4.3.6 so the
 * classifier-based parsing logic can be tested in isolation without spawning
 * any `adb` processes.
 *
 * See `sdd/grading-thermal-realism/explore` for the full context: pre-v4.3.6
 * the parser used substring matching on zone names (`type.contains("cpu")`)
 * which over-bucketed every CPU die zone on a Snapdragon 8 Gen 2 and produced
 * the 93 C "skin" temperature shown to the user. The classifier path here uses
 * exact-match sets + word-boundary regex (see [ThermalZoneClassifier]) and
 * separates die-CPU from skin so the UI can label each correctly.
 *
 * Plausibility windows (tightened from v4.2.5):
 *  - Skin / Battery: 0..60 C -- case/cell never legitimately above 60 C.
 *  - DieCpu / DieGpu: 0..120 C -- silicon can run hot but >120 C is a parse error.
 *
 * v4.4.1 (sdd/temperature-not-shown) -- the parser now derives
 * [com.gameperf.desktop.core.model.ThermalSnapshot.thermalAvailable] from the
 * "at least one CPU/SKIN zone yielded a valid temperature in the plausibility
 * window" rule, calls [ThermalZoneClassifier.classifyHeuristic] (stage 2)
 * when stage 1 returns null, and populates a [ThermalDiagnostic] with raw
 * zone names + bucket counts + reason when nothing useful classifies.
 */
internal object AdbThermalParser {

    /** Cap on raw zone names surfaced via diagnostic to keep export size bounded. */
    private const val DIAGNOSTIC_RAW_NAMES_LIMIT: Int = 10

    /**
     * Parse the multi-line `for z in /sys/class/thermal/thermal_zoneN; do echo
     * "$(cat $z/type):$(cat $z/temp)"; done` output. Each non-empty
     * `type:temp_milli_c` pair is classified and the MAX value per category
     * is returned. Lines that fail to parse, fall outside the plausibility
     * window, or classify to `null` are silently skipped (but counted in the
     * v4.4.1 diagnostic when the snapshot ends up unavailable).
     */
    fun parseThermalZonesOutput(zones: String): ThermalSnapshot {
        var dieCpu = -1.0
        var gpu = -1.0
        var battery = -1.0
        var skin = -1.0

        // v4.4.1 -- accumulators for diagnostic build at end of loop.
        val rawZoneNames = mutableListOf<String>()
        var nullCount = 0
        var dieCpuCount = 0
        var dieGpuCount = 0
        var skinCount = 0
        var batteryCount = 0
        var anyZoneSeen = false
        var anyZoneOutOfRange = false

        for (line in zones.lines()) {
            val sep = line.indexOf(':')
            if (sep < 0) continue
            val type = line.substring(0, sep).trim()
            val raw = line.substring(sep + 1).trim().toLongOrNull() ?: continue
            val temp = if (raw > 1000) raw / 1000.0 else raw.toDouble()

            anyZoneSeen = true
            rawZoneNames += type

            // v4.4.1 -- two-stage classification: strict allow-list first,
            // then keyword catch-all heuristic so unknown vendor zones still
            // get a chance instead of being silently dropped.
            val category = ThermalZoneClassifier.classify(type)
                ?: ThermalZoneClassifier.classifyHeuristic(type)

            if (category == null) {
                nullCount++
                continue
            }

            if (!withinPlausibilityWindow(category, temp)) {
                anyZoneOutOfRange = true
                // OOR samples are NOT contributing temps but DO count toward
                // their bucket so the diagnostic can distinguish "naming
                // failed" from "values are corrupt".
                when (category) {
                    ThermalCategory.Skin -> skinCount++
                    ThermalCategory.DieCpu -> dieCpuCount++
                    ThermalCategory.DieGpu -> dieGpuCount++
                    ThermalCategory.Battery -> batteryCount++
                }
                continue
            }

            when (category) {
                ThermalCategory.Skin -> {
                    if (temp > skin) skin = temp
                    skinCount++
                }
                ThermalCategory.DieCpu -> {
                    if (temp > dieCpu) dieCpu = temp
                    dieCpuCount++
                }
                ThermalCategory.DieGpu -> {
                    if (temp > gpu) gpu = temp
                    dieGpuCount++
                }
                ThermalCategory.Battery -> {
                    if (temp > battery) battery = temp
                    batteryCount++
                }
            }
        }

        // v4.4.1 -- thermalAvailable rule: at least one CPU OR SKIN zone
        // yielded a temp inside the plausibility window. GPU/Battery alone
        // do NOT qualify because the user-facing report headline is "CPU /
        // skin temperature" -- if neither is available we should render N/D.
        val thermalAvailable = (skin > 0.0) || (dieCpu > 0.0)

        val diagnostic = if (thermalAvailable) {
            null
        } else {
            buildDiagnostic(
                rawZoneNames = rawZoneNames,
                dieCpuCount = dieCpuCount,
                dieGpuCount = dieGpuCount,
                skinCount = skinCount,
                batteryCount = batteryCount,
                nullCount = nullCount,
                anyZoneSeen = anyZoneSeen,
                anyZoneOutOfRange = anyZoneOutOfRange,
            )
        }

        return buildSnapshot(
            dieCpu = dieCpu,
            gpu = gpu,
            battery = battery,
            skin = skin,
            thermalAvailable = thermalAvailable,
            diagnostic = diagnostic,
        )
    }

    /**
     * v4.4.1 -- assemble the [ThermalDiagnostic] for the caller. Truncates
     * rawZoneNames to [DIAGNOSTIC_RAW_NAMES_LIMIT] so the failed-session
     * `.gameperf` export and HTML report banner stay bounded in size.
     */
    private fun buildDiagnostic(
        rawZoneNames: List<String>,
        dieCpuCount: Int,
        dieGpuCount: Int,
        skinCount: Int,
        batteryCount: Int,
        nullCount: Int,
        anyZoneSeen: Boolean,
        anyZoneOutOfRange: Boolean,
    ): ThermalDiagnostic {
        val reason = when {
            !anyZoneSeen -> ThermalUnavailableReason.NO_ZONES_DETECTED
            nullCount == 0 && anyZoneOutOfRange -> ThermalUnavailableReason.ALL_TEMPS_INVALID
            else -> ThermalUnavailableReason.ALL_ZONES_UNCLASSIFIED
        }
        return ThermalDiagnostic(
            rawZoneNames = rawZoneNames.take(DIAGNOSTIC_RAW_NAMES_LIMIT),
            classificationCounts = mapOf(
                "DieCpu" to dieCpuCount,
                "DieGpu" to dieGpuCount,
                "Skin" to skinCount,
                "Battery" to batteryCount,
                "null" to nullCount,
            ),
            reason = reason,
        )
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

    /**
     * Build a [ThermalSnapshot] with the legacy `cpu` field populated from
     * skin/die. v4.4.1 -- accepts thermalAvailable + diagnostic so
     * [parseThermalZonesOutput] can surface unsupported-vendor diagnostics
     * to the report HTML (see sdd/temperature-not-shown).
     */
    private fun buildSnapshot(
        dieCpu: Double,
        gpu: Double,
        battery: Double,
        skin: Double,
        thermalAvailable: Boolean = true,
        diagnostic: ThermalDiagnostic? = null,
    ): ThermalSnapshot {
        // Legacy `cpu` semantics -- see ThermalSnapshot KDoc. Skin wins when
        // available so existing reports/exports show the user-facing temp.
        val legacyCpu = if (skin > 0) skin else dieCpu
        return ThermalSnapshot(
            cpu = legacyCpu,
            gpu = gpu,
            battery = battery,
            skin = skin,
            dieCpu = dieCpu,
            thermalAvailable = thermalAvailable,
            diagnostic = diagnostic,
        )
    }
}
