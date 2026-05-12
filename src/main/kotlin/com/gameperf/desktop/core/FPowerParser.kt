package com.gameperf.desktop.core

import com.gameperf.desktop.core.model.FPowerDiagnostic
import com.gameperf.desktop.core.model.FPowerSnapshot
import com.gameperf.desktop.core.model.FPowerUnavailableReason
import kotlin.math.abs

/**
 * v4.5.0 -- Pure parser for the FPower (mW per frame) metric, extracted out
 * of [AdbBridge] so the math + sign-convention + plausibility logic can be
 * unit-tested in isolation without spawning any `adb` processes.
 *
 * No I/O. No shell calls. No suspending functions. Stateless. Takes
 * already-fetched raw sysfs payloads + the current FPS reading, returns an
 * [FPowerSnapshot].
 *
 * The bridge in Batch 3 will compose these primitives:
 *  1. `cat current_now` -> [parseMicroAmpere]
 *  2. `cat voltage_now` -> [parseMicroVolt]
 *  3. [computePowerW] / [computeFPowerMwPerFrame] -> sanity-bounded values
 *  4. [buildSnapshot] -> final [FPowerSnapshot] with diagnostic on failure
 *
 * Mirrors [AdbThermalParser] (`core/AdbThermalParser.kt`) per design ADR-1.
 *
 * **Plausibility window** (FPW-011, design ADR-4):
 *  - `0 W < powerW < 30 W` -- devices don't pull more than 30 W sustained.
 *  - `0 mW/frame < fpowerMwPerFrame < 500 mW/frame` -- PerfDog cases stay
 *    well below 100 in field data; 500 is a generous safety cap.
 *  Strict-less-than on both bounds; the boundary itself is rejected.
 *
 * **Sign convention** (FPW-002, design ADR-2): some kernels report
 * `current_now` positive-on-charge, others positive-on-discharge.
 * [computePowerW] applies `abs()` so both conventions yield the same power.
 *
 * See `sdd/fpower-metric/design` §1 + spec FPW-002, FPW-003, FPW-011.
 */
internal object FPowerParser {

    /** `microA * microV / 1e12 -> W`. Top-level so it's compiled once. */
    private const val POWER_DIVISOR: Double = 1e12

    /** Watts -> milliwatts conversion. */
    private const val W_TO_MW: Double = 1000.0

    /** Upper bound for plausible instantaneous battery power (strict). */
    private const val POWER_W_MAX: Double = 30.0

    /** Upper bound for plausible FPower (strict). */
    private const val FPOWER_MW_PER_FRAME_MAX: Double = 500.0

    /**
     * Parse a raw `voltage_now` sysfs payload into microvolts. Trims
     * whitespace; returns `null` for empty / malformed / non-numeric input.
     *
     * Negative values are accepted at parse time — sign convention drift is
     * handled in [computePowerW] via `abs()` per design ADR-2.
     */
    fun parseMicroVolt(raw: String): Long? = raw.trim().toLongOrNull()

    /**
     * Parse a raw `current_now` sysfs payload into microamperes. Same shape
     * as [parseMicroVolt] — kept as a separate function for call-site clarity
     * and so the bridge wiring in Batch 3 reads top-down.
     */
    fun parseMicroAmpere(raw: String): Long? = raw.trim().toLongOrNull()

    /**
     * Compute instantaneous battery power in watts from the parsed microA +
     * microV readings.
     *
     * Formula: `|currentMicroA| * voltageMicroV / 1e12`.
     *
     * Returns `null` when:
     *  - either input is null (parse failed upstream), OR
     *  - the result falls outside the strict-open `(0, POWER_W_MAX)` window
     *    -- caller treats this as `IMPLAUSIBLE_VALUE` per FPW-011.
     */
    fun computePowerW(currentMicroA: Long?, voltageMicroV: Long?): Double? {
        if (currentMicroA == null || voltageMicroV == null) return null
        val powerW = abs(currentMicroA).toDouble() * voltageMicroV.toDouble() / POWER_DIVISOR
        // Strict-open lower bound rejects 0 (zero current OR zero voltage).
        if (powerW <= 0.0 || powerW >= POWER_W_MAX) return null
        return powerW
    }

    /**
     * Compute FPower in mW per frame from instantaneous power + FPS.
     *
     * Formula: `powerW * 1000 / fps`.
     *
     * Returns `null` when:
     *  - [powerW] is null (computation failed upstream), OR
     *  - `fps <= 0` -- caller treats this as `FPS_ZERO` per FPW-003, OR
     *  - the result falls outside the strict-open `(0, FPOWER_MW_PER_FRAME_MAX)`
     *    window -- caller treats this as `IMPLAUSIBLE_VALUE` per FPW-011.
     */
    fun computeFPowerMwPerFrame(powerW: Double?, fps: Double): Double? {
        if (powerW == null) return null
        if (fps <= 0.0) return null
        val mwPerFrame = powerW * W_TO_MW / fps
        if (mwPerFrame <= 0.0 || mwPerFrame >= FPOWER_MW_PER_FRAME_MAX) return null
        return mwPerFrame
    }

    /**
     * Assemble an [FPowerSnapshot] from parsed inputs + FPS + diagnostic
     * context. Encodes the per-reason precedence:
     *
     *  1. Either parser returned null -> `BATTERY_PATH_MISSING`.
     *  2. Power outside plausibility window -> `IMPLAUSIBLE_VALUE`.
     *  3. `fps <= 0` -> `FPS_ZERO` (intermediates `powerW / currentMicroA /
     *     voltageMicroV` still populated -- the read worked, just no frames).
     *  4. FPower outside plausibility window -> `IMPLAUSIBLE_VALUE`.
     *  5. Otherwise -> happy path, `fpowerAvailable=true`, no diagnostic.
     *
     * Intermediate fields on the snapshot follow the rule "expose what we
     * managed to compute". On `BATTERY_PATH_MISSING` everything is `-1.0`;
     * on `FPS_ZERO` the raw readings + power are populated; on
     * `IMPLAUSIBLE_VALUE` intermediates are populated but `fpowerMwPerFrame
     * = -1.0`.
     */
    @Suppress("ReturnCount") // Per-reason early returns keep the decision tree readable.
    fun buildSnapshot(
        currentMicroA: Long?,
        voltageMicroV: Long?,
        fps: Double,
        rawPathsTried: List<String>,
        lastReadout: Map<String, String>,
    ): FPowerSnapshot {
        // Step 1 -- either parser failed -> BATTERY_PATH_MISSING. Intermediates -1.0.
        if (currentMicroA == null || voltageMicroV == null) {
            return unavailable(
                reason = FPowerUnavailableReason.BATTERY_PATH_MISSING,
                rawPathsTried = rawPathsTried,
                lastReadout = lastReadout,
            )
        }

        // Step 2 -- power computed, but maybe out-of-window.
        val powerW = computePowerW(currentMicroA, voltageMicroV)
        if (powerW == null) {
            return FPowerSnapshot(
                fpowerMwPerFrame = -1.0,
                powerW = -1.0,
                currentMicroA = currentMicroA.toDouble(),
                voltageMicroV = voltageMicroV.toDouble(),
                fpowerAvailable = false,
                diagnostic = FPowerDiagnostic(
                    rawPathsTried = rawPathsTried,
                    lastReadout = lastReadout,
                    reason = FPowerUnavailableReason.IMPLAUSIBLE_VALUE,
                ),
            )
        }

        // Step 3 -- fps <= 0 -> FPS_ZERO. Intermediates (raw + power) populated.
        if (fps <= 0.0) {
            return FPowerSnapshot(
                fpowerMwPerFrame = -1.0,
                powerW = powerW,
                currentMicroA = currentMicroA.toDouble(),
                voltageMicroV = voltageMicroV.toDouble(),
                fpowerAvailable = false,
                diagnostic = FPowerDiagnostic(
                    rawPathsTried = rawPathsTried,
                    lastReadout = lastReadout,
                    reason = FPowerUnavailableReason.FPS_ZERO,
                ),
            )
        }

        // Step 4 -- fpower computed, but maybe out-of-window.
        val fpowerMw = computeFPowerMwPerFrame(powerW, fps)
        if (fpowerMw == null) {
            return FPowerSnapshot(
                fpowerMwPerFrame = -1.0,
                powerW = powerW,
                currentMicroA = currentMicroA.toDouble(),
                voltageMicroV = voltageMicroV.toDouble(),
                fpowerAvailable = false,
                diagnostic = FPowerDiagnostic(
                    rawPathsTried = rawPathsTried,
                    lastReadout = lastReadout,
                    reason = FPowerUnavailableReason.IMPLAUSIBLE_VALUE,
                ),
            )
        }

        // Step 5 -- happy path.
        return FPowerSnapshot(
            fpowerMwPerFrame = fpowerMw,
            powerW = powerW,
            currentMicroA = currentMicroA.toDouble(),
            voltageMicroV = voltageMicroV.toDouble(),
            fpowerAvailable = true,
            diagnostic = null,
        )
    }

    /**
     * Build an unavailable snapshot with all intermediate fields at the
     * `-1.0` sentinel and a populated [FPowerDiagnostic]. Used when the
     * parse step itself fails (no useful intermediates to surface).
     */
    private fun unavailable(
        reason: FPowerUnavailableReason,
        rawPathsTried: List<String>,
        lastReadout: Map<String, String>,
    ): FPowerSnapshot = FPowerSnapshot(
        fpowerMwPerFrame = -1.0,
        powerW = -1.0,
        currentMicroA = -1.0,
        voltageMicroV = -1.0,
        fpowerAvailable = false,
        diagnostic = FPowerDiagnostic(
            rawPathsTried = rawPathsTried,
            lastReadout = lastReadout,
            reason = reason,
        ),
    )
}
