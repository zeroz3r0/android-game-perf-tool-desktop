package com.gameperf.desktop.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for [ThermalZoneClassifier]. Pure object — no mocks, no I/O.
 *
 * Lists are derived from PUBLIC AOSP / Snapdragon / Samsung kernel source patterns.
 * See exploration `sdd/grading-thermal-realism/explore` for the reasoning behind
 * each entry. The defensive bias is: when a zone name is ambiguous, we prefer to
 * classify it as `null` (ignored) rather than mis-bucketing it as CPU die / skin
 * and lying to the user about the temperature.
 */
class ThermalZoneClassifierTest {

    // ── Skin / case zones ────────────────────────────────────────────────

    @Test
    fun `classifies common Snapdragon skin zones as Skin`() {
        assertEquals(ThermalCategory.Skin, ThermalZoneClassifier.classify("skin-therm"))
        assertEquals(ThermalCategory.Skin, ThermalZoneClassifier.classify("skin-therm-usr"))
        assertEquals(ThermalCategory.Skin, ThermalZoneClassifier.classify("xo-therm"))
        assertEquals(ThermalCategory.Skin, ThermalZoneClassifier.classify("xo-therm-usr"))
        assertEquals(ThermalCategory.Skin, ThermalZoneClassifier.classify("quiet-therm"))
        assertEquals(ThermalCategory.Skin, ThermalZoneClassifier.classify("quiet-therm-monitor"))
        assertEquals(ThermalCategory.Skin, ThermalZoneClassifier.classify("virtual-skin"))
        assertEquals(ThermalCategory.Skin, ThermalZoneClassifier.classify("virtual-skin-therm"))
        assertEquals(ThermalCategory.Skin, ThermalZoneClassifier.classify("sm-skin-therm"))
    }

    @Test
    fun `classifies Pixel skin proxy zones as Skin`() {
        assertEquals(ThermalCategory.Skin, ThermalZoneClassifier.classify("back-therm"))
        assertEquals(ThermalCategory.Skin, ThermalZoneClassifier.classify("back-therm-usr"))
        // pa-therm is the deliberate Pixel-style trade-off documented in apply spec.
        assertEquals(ThermalCategory.Skin, ThermalZoneClassifier.classify("pa-therm"))
        assertEquals(ThermalCategory.Skin, ThermalZoneClassifier.classify("pa-therm0"))
        assertEquals(ThermalCategory.Skin, ThermalZoneClassifier.classify("pa-therm1"))
        assertEquals(ThermalCategory.Skin, ThermalZoneClassifier.classify("disp-therm"))
        assertEquals(ThermalCategory.Skin, ThermalZoneClassifier.classify("case-therm"))
    }

    @Test
    fun `classifies legacy thermalservice virtual-skin name as Skin`() {
        // dumpsys thermalservice fallback uses the n=="virtual-skin" exact match.
        assertEquals(ThermalCategory.Skin, ThermalZoneClassifier.classify("virtual-skin"))
    }

    // ── CPU die zones ────────────────────────────────────────────────────

    @Test
    fun `classifies Qualcomm CPU subsystem zones as DieCpu`() {
        assertEquals(ThermalCategory.DieCpu, ThermalZoneClassifier.classify("cpuss-0"))
        assertEquals(ThermalCategory.DieCpu, ThermalZoneClassifier.classify("cpuss-1-usr"))
        assertEquals(ThermalCategory.DieCpu, ThermalZoneClassifier.classify("cpuss-3-usr"))
    }

    @Test
    fun `classifies per-core cpuN-thermal zones as DieCpu`() {
        assertEquals(ThermalCategory.DieCpu, ThermalZoneClassifier.classify("cpu0-thermal"))
        assertEquals(ThermalCategory.DieCpu, ThermalZoneClassifier.classify("cpu7-thermal"))
    }

    @Test
    fun `classifies Tensor cpu step zones as DieCpu`() {
        assertEquals(ThermalCategory.DieCpu, ThermalZoneClassifier.classify("cpu-1-step"))
        assertEquals(ThermalCategory.DieCpu, ThermalZoneClassifier.classify("cpu-1-fast"))
    }

    @Test
    fun `classifies cluster zones as DieCpu`() {
        assertEquals(ThermalCategory.DieCpu, ThermalZoneClassifier.classify("gold_cluster_thermal"))
        assertEquals(ThermalCategory.DieCpu, ThermalZoneClassifier.classify("silver_cluster_thermal"))
        assertEquals(ThermalCategory.DieCpu, ThermalZoneClassifier.classify("prime_cluster_thermal"))
        assertEquals(ThermalCategory.DieCpu, ThermalZoneClassifier.classify("gold_thermal"))
        assertEquals(ThermalCategory.DieCpu, ThermalZoneClassifier.classify("silver_thermal"))
        assertEquals(ThermalCategory.DieCpu, ThermalZoneClassifier.classify("prime_thermal"))
    }

    @Test
    fun `classifies legacy big_little_mid names as DieCpu`() {
        // dumpsys thermalservice fallback emits these exact names on some Qualcomm.
        assertEquals(ThermalCategory.DieCpu, ThermalZoneClassifier.classify("big"))
        assertEquals(ThermalCategory.DieCpu, ThermalZoneClassifier.classify("little"))
        assertEquals(ThermalCategory.DieCpu, ThermalZoneClassifier.classify("mid"))
    }

    @Test
    fun `classifies aoss subsystem zones as DieCpu`() {
        assertEquals(ThermalCategory.DieCpu, ThermalZoneClassifier.classify("aoss0-usr"))
        assertEquals(ThermalCategory.DieCpu, ThermalZoneClassifier.classify("aoss1-usr"))
    }

    // ── GPU die zones ────────────────────────────────────────────────────

    @Test
    fun `classifies Qualcomm GPU subsystem zones as DieGpu`() {
        assertEquals(ThermalCategory.DieGpu, ThermalZoneClassifier.classify("gpuss-0-usr"))
        assertEquals(ThermalCategory.DieGpu, ThermalZoneClassifier.classify("gpuss-1-usr"))
    }

    @Test
    fun `classifies per-GPU thermal zones as DieGpu`() {
        assertEquals(ThermalCategory.DieGpu, ThermalZoneClassifier.classify("gpu0-thermal"))
        assertEquals(ThermalCategory.DieGpu, ThermalZoneClassifier.classify("gpu1-thermal"))
        assertEquals(ThermalCategory.DieGpu, ThermalZoneClassifier.classify("kgsl_3d0_thermal"))
        assertEquals(ThermalCategory.DieGpu, ThermalZoneClassifier.classify("kgsl_3d_thermal"))
    }

    @Test
    fun `classifies Mali GPU zones as DieGpu`() {
        assertEquals(ThermalCategory.DieGpu, ThermalZoneClassifier.classify("mali"))
        assertEquals(ThermalCategory.DieGpu, ThermalZoneClassifier.classify("mali-thermal"))
    }

    @Test
    fun `classifies legacy g3d as DieGpu`() {
        // dumpsys thermalservice exact-match used in AdbBridge fallback.
        assertEquals(ThermalCategory.DieGpu, ThermalZoneClassifier.classify("g3d"))
    }

    // ── Battery zones ────────────────────────────────────────────────────

    @Test
    fun `classifies battery zones as Battery`() {
        assertEquals(ThermalCategory.Battery, ThermalZoneClassifier.classify("battery"))
        assertEquals(ThermalCategory.Battery, ThermalZoneClassifier.classify("battery0"))
        assertEquals(ThermalCategory.Battery, ThermalZoneClassifier.classify("battery1"))
    }

    // ── Ignored zones ────────────────────────────────────────────────────

    @Test
    fun `ignores modem and wifi zones`() {
        assertNull(ThermalZoneClassifier.classify("modem"))
        assertNull(ThermalZoneClassifier.classify("modem0"))
        assertNull(ThermalZoneClassifier.classify("mdm-core"))
        assertNull(ThermalZoneClassifier.classify("wlan"))
        assertNull(ThermalZoneClassifier.classify("wlan_pa1"))
    }

    @Test
    fun `ignores charger and PMIC zones`() {
        assertNull(ThermalZoneClassifier.classify("chg-skin-therm"))
        assertNull(ThermalZoneClassifier.classify("chg-therm"))
        assertNull(ThermalZoneClassifier.classify("chg-bat-therm"))
        assertNull(ThermalZoneClassifier.classify("pm8350b_tz"))
        assertNull(ThermalZoneClassifier.classify("pm8350a_tz"))
        assertNull(ThermalZoneClassifier.classify("pm8550_tz"))
        assertNull(ThermalZoneClassifier.classify("wp_therm"))
        assertNull(ThermalZoneClassifier.classify("wp-therm"))
        assertNull(ThermalZoneClassifier.classify("usbc-therm"))
        assertNull(ThermalZoneClassifier.classify("usb-therm"))
    }

    @Test
    fun `ignores fully unknown zone names`() {
        assertNull(ThermalZoneClassifier.classify("foo"))
        assertNull(ThermalZoneClassifier.classify("zzz_unknown"))
        assertNull(ThermalZoneClassifier.classify(""))
    }

    @Test
    fun `classification is case insensitive`() {
        assertEquals(ThermalCategory.Skin, ThermalZoneClassifier.classify("SKIN-THERM"))
        assertEquals(ThermalCategory.DieCpu, ThermalZoneClassifier.classify("CPUSS-0-USR"))
        assertEquals(ThermalCategory.DieGpu, ThermalZoneClassifier.classify("Mali"))
    }

    @Test
    fun `chg-skin-therm is NOT classified as Skin (charger IC takes precedence)`() {
        // Critical guard: substring `skin` would match if we used naive matching.
        // The ignore list MUST win — chg-skin-therm is the charger IC, not the case.
        assertNull(ThermalZoneClassifier.classify("chg-skin-therm"))
    }
}
