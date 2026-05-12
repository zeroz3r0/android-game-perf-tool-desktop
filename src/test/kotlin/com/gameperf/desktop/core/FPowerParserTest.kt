package com.gameperf.desktop.core

import com.gameperf.desktop.core.model.FPowerUnavailableReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v4.5.0 -- Pure unit tests for [FPowerParser].
 *
 * Covers spec requirements:
 *  - FPW-002 -- Power(W) = abs(current_microA) * voltage_microV / 1e12 (both sign
 *    conventions neutralised by `abs()` per design ADR-2).
 *  - FPW-003 -- FPower(mW/frame) = Power * 1000 / fps when fps>0; FPS_ZERO when
 *    fps<=0.
 *  - FPW-004 -- `FPowerSnapshot` built with intermediate fields populated.
 *  - FPW-005 -- diagnostic.reason matches the failure cause.
 *  - FPW-011 -- Plausibility window: 0<powerW<30, 0<fpowerMwPerFrame<500.
 *
 * Pure tests, no mocks. Mirrors [AdbThermalParserExtendedTest] style.
 *
 * See `sdd/fpower-metric/spec` + design §1.
 */
class FPowerParserTest {

    // ---- parseMicroVolt -----------------------------------------------------

    @Test
    fun `parseMicroVolt happy path returns Long`() {
        assertEquals(3_987_654L, FPowerParser.parseMicroVolt("3987654"))
    }

    @Test
    fun `parseMicroVolt strips trailing newline`() {
        assertEquals(3_987_654L, FPowerParser.parseMicroVolt("3987654\n"))
    }

    @Test
    fun `parseMicroVolt empty string returns null`() {
        assertNull(FPowerParser.parseMicroVolt(""))
    }

    @Test
    fun `parseMicroVolt whitespace only returns null`() {
        assertNull(FPowerParser.parseMicroVolt("   \t\n"))
    }

    @Test
    fun `parseMicroVolt non-numeric returns null`() {
        assertNull(FPowerParser.parseMicroVolt("not a number"))
    }

    @Test
    fun `parseMicroVolt negative value accepted by parser (caller filters)`() {
        // Defensive: parser accepts; caller applies abs() per ADR-2.
        assertEquals(-100L, FPowerParser.parseMicroVolt("-100"))
    }

    @Test
    fun `parseMicroVolt Long_MAX_VALUE round-trips`() {
        assertEquals(Long.MAX_VALUE, FPowerParser.parseMicroVolt("9223372036854775807"))
    }

    // ---- parseMicroAmpere ---------------------------------------------------

    @Test
    fun `parseMicroAmpere happy path returns Long`() {
        assertEquals(350_000L, FPowerParser.parseMicroAmpere("350000"))
    }

    @Test
    fun `parseMicroAmpere strips trailing newline`() {
        assertEquals(350_000L, FPowerParser.parseMicroAmpere("350000\n"))
    }

    @Test
    fun `parseMicroAmpere empty string returns null`() {
        assertNull(FPowerParser.parseMicroAmpere(""))
    }

    @Test
    fun `parseMicroAmpere non-numeric returns null`() {
        assertNull(FPowerParser.parseMicroAmpere("permission denied"))
    }

    @Test
    fun `parseMicroAmpere negative value accepted (sign convention drift per ADR-2)`() {
        // Some kernels report negative=discharging; abs() applied in computePowerW.
        assertEquals(-350_000L, FPowerParser.parseMicroAmpere("-350000"))
    }

    // ---- computePowerW (FPW-002) -------------------------------------------

    @Test
    fun `computePowerW happy path - positive current`() {
        // 350 mA at 3.99 V -> ~1.395 W
        val p = FPowerParser.computePowerW(350_000L, 3_987_654L)
        assertNotNull(p)
        assertEquals(1.3956789, p, 0.001)
    }

    @Test
    fun `computePowerW negative current discharging - abs neutralises per ADR-2`() {
        val p = FPowerParser.computePowerW(-350_000L, 3_987_654L)
        assertNotNull(p)
        assertEquals(1.3956789, p, 0.001)
    }

    @Test
    fun `computePowerW null current returns null`() {
        assertNull(FPowerParser.computePowerW(null, 3_987_654L))
    }

    @Test
    fun `computePowerW null voltage returns null`() {
        assertNull(FPowerParser.computePowerW(350_000L, null))
    }

    @Test
    fun `computePowerW implausible 100W returns null (FPW-011)`() {
        // 25 A at 4 V = 100 W -> out of POWER_W_MAX window.
        assertNull(FPowerParser.computePowerW(25_000_000L, 4_000_000L))
    }

    @Test
    fun `computePowerW zero current returns 0_0`() {
        // 0 W is outside (0, 30) strict-open window per FPW-011 -> null.
        assertNull(FPowerParser.computePowerW(0L, 3_987_654L))
    }

    @Test
    fun `computePowerW zero voltage returns null (outside window)`() {
        assertNull(FPowerParser.computePowerW(350_000L, 0L))
    }

    // ---- computeFPowerMwPerFrame (FPW-003) ---------------------------------

    @Test
    fun `computeFPowerMwPerFrame happy path at 60fps`() {
        // 1.395 W * 1000 / 60 = 23.25 mW/frame
        val f = FPowerParser.computeFPowerMwPerFrame(1.395, 60.0)
        assertNotNull(f)
        assertEquals(23.25, f, 0.01)
    }

    @Test
    fun `computeFPowerMwPerFrame fps zero returns null (FPW-003 FPS_ZERO)`() {
        assertNull(FPowerParser.computeFPowerMwPerFrame(1.395, 0.0))
    }

    @Test
    fun `computeFPowerMwPerFrame negative fps returns null (defensive)`() {
        assertNull(FPowerParser.computeFPowerMwPerFrame(1.395, -5.0))
    }

    @Test
    fun `computeFPowerMwPerFrame null power returns null`() {
        assertNull(FPowerParser.computeFPowerMwPerFrame(null, 60.0))
    }

    @Test
    fun `computeFPowerMwPerFrame implausible 1000mW per frame returns null (FPW-011)`() {
        // 10 W / 10 fps = 1000 mW/frame -> out of FPOWER_MW_PER_FRAME_MAX.
        assertNull(FPowerParser.computeFPowerMwPerFrame(10.0, 10.0))
    }

    @Test
    fun `computeFPowerMwPerFrame exactly at 500 boundary returns null (strict less-than)`() {
        // 500 mW/frame is rejected — strict less-than upper bound per FPW-011.
        assertNull(FPowerParser.computeFPowerMwPerFrame(30.0, 60.0).also {
            // sanity: 30 * 1000 / 60 = 500 exactly
        })
        // Direct boundary case — power chosen so result is exactly 500.
        val f = FPowerParser.computeFPowerMwPerFrame(5.0, 10.0)
        assertNull(f)  // 5 * 1000 / 10 = 500 -> rejected
    }

    @Test
    fun `computeFPowerMwPerFrame just under boundary returns value`() {
        val f = FPowerParser.computeFPowerMwPerFrame(4.999, 10.0)
        assertNotNull(f)
        assertEquals(499.9, f, 0.001)
    }

    // ---- buildSnapshot (FPW-004 + FPW-005) ---------------------------------

    @Test
    fun `buildSnapshot happy path - all fields populated, available=true`() {
        val s = FPowerParser.buildSnapshot(
            currentMicroA = 350_000L,
            voltageMicroV = 3_987_654L,
            fps = 60.0,
            rawPathsTried = listOf("/sys/class/power_supply/battery/current_now"),
            lastReadout = mapOf("/sys/class/power_supply/battery/current_now" to "350000"),
        )
        assertTrue(s.fpowerAvailable, "snapshot must be available on happy path")
        assertNull(s.diagnostic, "diagnostic must be null on happy path")
        assertEquals(350_000.0, s.currentMicroA)
        assertEquals(3_987_654.0, s.voltageMicroV)
        assertEquals(1.3956789, s.powerW, 0.001)
        assertEquals(23.26, s.fpowerMwPerFrame, 0.01)
    }

    @Test
    fun `buildSnapshot null current - reason BATTERY_PATH_MISSING (FPW-005)`() {
        val s = FPowerParser.buildSnapshot(
            currentMicroA = null,
            voltageMicroV = 3_987_654L,
            fps = 60.0,
            rawPathsTried = listOf("/sys/class/power_supply/battery/current_now"),
            lastReadout = mapOf("/sys/class/power_supply/battery/current_now" to ""),
        )
        assertEquals(false, s.fpowerAvailable)
        val d = s.diagnostic
        assertNotNull(d)
        assertEquals(FPowerUnavailableReason.BATTERY_PATH_MISSING, d.reason)
        // Intermediates sentinel-filled
        assertEquals(-1.0, s.currentMicroA)
        assertEquals(-1.0, s.voltageMicroV)
        assertEquals(-1.0, s.powerW)
        assertEquals(-1.0, s.fpowerMwPerFrame)
    }

    @Test
    fun `buildSnapshot null voltage - reason BATTERY_PATH_MISSING (FPW-005)`() {
        val s = FPowerParser.buildSnapshot(
            currentMicroA = 350_000L,
            voltageMicroV = null,
            fps = 60.0,
            rawPathsTried = listOf("/sys/class/power_supply/battery/voltage_now"),
            lastReadout = emptyMap(),
        )
        assertEquals(false, s.fpowerAvailable)
        assertEquals(FPowerUnavailableReason.BATTERY_PATH_MISSING, s.diagnostic?.reason)
    }

    @Test
    fun `buildSnapshot fps zero - reason FPS_ZERO, intermediates populated (FPW-003)`() {
        // The sysfs read worked, just no frames. powerW + currentMicroA + voltageMicroV
        // populated so the diagnostic carries useful triage info.
        val s = FPowerParser.buildSnapshot(
            currentMicroA = 350_000L,
            voltageMicroV = 3_987_654L,
            fps = 0.0,
            rawPathsTried = listOf("/sys/class/power_supply/battery/current_now"),
            lastReadout = emptyMap(),
        )
        assertEquals(false, s.fpowerAvailable)
        assertEquals(FPowerUnavailableReason.FPS_ZERO, s.diagnostic?.reason)
        assertEquals(350_000.0, s.currentMicroA)
        assertEquals(3_987_654.0, s.voltageMicroV)
        assertEquals(1.3956789, s.powerW, 0.001)
        // fpowerMwPerFrame is sentinel because the division failed.
        assertEquals(-1.0, s.fpowerMwPerFrame)
    }

    @Test
    fun `buildSnapshot implausible power - reason IMPLAUSIBLE_VALUE (FPW-011)`() {
        // 25 A * 4 V = 100 W, out of POWER_W_MAX=30 window.
        val s = FPowerParser.buildSnapshot(
            currentMicroA = 25_000_000L,
            voltageMicroV = 4_000_000L,
            fps = 60.0,
            rawPathsTried = listOf("/sys/class/power_supply/battery/current_now"),
            lastReadout = emptyMap(),
        )
        assertEquals(false, s.fpowerAvailable)
        assertEquals(FPowerUnavailableReason.IMPLAUSIBLE_VALUE, s.diagnostic?.reason)
    }

    @Test
    fun `buildSnapshot propagates rawPathsTried + lastReadout into diagnostic`() {
        val paths = listOf("/path/a", "/path/b")
        val readout = mapOf("/path/a" to "", "/path/b" to "garbage")
        val s = FPowerParser.buildSnapshot(
            currentMicroA = null,
            voltageMicroV = null,
            fps = 60.0,
            rawPathsTried = paths,
            lastReadout = readout,
        )
        val d = s.diagnostic
        assertNotNull(d)
        assertEquals(paths, d.rawPathsTried)
        assertEquals(readout, d.lastReadout)
    }
}
