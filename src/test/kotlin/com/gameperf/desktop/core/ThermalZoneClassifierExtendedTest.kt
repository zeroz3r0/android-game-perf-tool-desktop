package com.gameperf.desktop.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * v4.4.1 -- Extended classifier tests for the "temperature-not-shown" change.
 *
 * Adds vendor patterns missing from v4.3.6 (Pixel XL pre-Treble Qualcomm
 * `tsens_tz_sensor*`, Samsung Galaxy Tab A8 Unisoc T618 `cluster*-thermal` /
 * `ump_thermal`) to the strict allow-list, and exercises the new stage-2
 * keyword catch-all (`classifyHeuristic`) with `chg-`/`pmic-` prefix guards.
 *
 * Pure tests, no I/O, no mocks. The existing v4.3.6 fixtures
 * ([ThermalZoneClassifierTest]) MUST also continue to pass unchanged.
 *
 * See `sdd/temperature-not-shown/spec` MODIFIED requirements for the
 * scenarios encoded here, and `sdd/temperature-not-shown/design` ADR-1, 2.
 */
class ThermalZoneClassifierExtendedTest {

    // ---- Stage 1: Pixel XL pre-Treble Qualcomm `tsens_tz_sensor*` ------

    @Test
    fun `Pixel XL tsens_tz_sensor zones classify as DieCpu`() {
        assertEquals(ThermalCategory.DieCpu, ThermalZoneClassifier.classify("tsens_tz_sensor0"))
        assertEquals(ThermalCategory.DieCpu, ThermalZoneClassifier.classify("tsens_tz_sensor1"))
        assertEquals(ThermalCategory.DieCpu, ThermalZoneClassifier.classify("tsens_tz_sensor7"))
    }

    @Test
    fun `tsens_tz_sensor accepts multi-digit indices`() {
        assertEquals(ThermalCategory.DieCpu, ThermalZoneClassifier.classify("tsens_tz_sensor10"))
        assertEquals(ThermalCategory.DieCpu, ThermalZoneClassifier.classify("tsens_tz_sensor12"))
        assertEquals(ThermalCategory.DieCpu, ThermalZoneClassifier.classify("tsens_tz_sensor99"))
    }

    // ---- Stage 1: Samsung Galaxy Tab A8 Unisoc T618 ---------------------

    @Test
    fun `Tab A8 cluster-thermal zones classify as DieCpu (hyphen variant)`() {
        assertEquals(ThermalCategory.DieCpu, ThermalZoneClassifier.classify("cluster0-thermal"))
        assertEquals(ThermalCategory.DieCpu, ThermalZoneClassifier.classify("cluster1-thermal"))
    }

    @Test
    fun `Tab A8 cluster_thermal zones classify as DieCpu (underscore variant)`() {
        // Per spec: both `-` and `_` separators accepted for cluster zones.
        assertEquals(ThermalCategory.DieCpu, ThermalZoneClassifier.classify("cluster0_thermal"))
        assertEquals(ThermalCategory.DieCpu, ThermalZoneClassifier.classify("cluster1_thermal"))
    }

    @Test
    fun `Tab A8 ump_thermal classifies as DieGpu (Unisoc Mali alias)`() {
        assertEquals(ThermalCategory.DieGpu, ThermalZoneClassifier.classify("ump_thermal"))
    }

    @Test
    fun `cluster zones with multi-digit index classify as DieCpu`() {
        assertEquals(ThermalCategory.DieCpu, ThermalZoneClassifier.classify("cluster10-thermal"))
        assertEquals(ThermalCategory.DieCpu, ThermalZoneClassifier.classify("cluster12_thermal"))
    }

    // ---- Stage 2 (catch-all heuristic) ----------------------------------

    @Test
    fun `classifyHeuristic returns DieCpu for cpu and core keyword zones`() {
        assertEquals(ThermalCategory.DieCpu, ThermalZoneClassifier.classifyHeuristic("vendor-cpu-zone"))
        assertEquals(ThermalCategory.DieCpu, ThermalZoneClassifier.classifyHeuristic("core_temp"))
        assertEquals(ThermalCategory.DieCpu, ThermalZoneClassifier.classifyHeuristic("CPU_THERMAL"))
        assertEquals(ThermalCategory.DieCpu, ThermalZoneClassifier.classifyHeuristic("vendor_core_temp_x"))
    }

    @Test
    fun `classifyHeuristic returns DieGpu for gpu mali kgsl keyword zones`() {
        assertEquals(ThermalCategory.DieGpu, ThermalZoneClassifier.classifyHeuristic("vendor_gpu_zone"))
        assertEquals(ThermalCategory.DieGpu, ThermalZoneClassifier.classifyHeuristic("mali-thermal-x"))
        assertEquals(ThermalCategory.DieGpu, ThermalZoneClassifier.classifyHeuristic("kgsl_x_thermal"))
    }

    @Test
    fun `classifyHeuristic returns Skin for skin and case and back keyword zones`() {
        assertEquals(ThermalCategory.Skin, ThermalZoneClassifier.classifyHeuristic("battery_skin_thermal"))
        assertEquals(ThermalCategory.Skin, ThermalZoneClassifier.classifyHeuristic("vendor_case_x"))
        assertEquals(ThermalCategory.Skin, ThermalZoneClassifier.classifyHeuristic("vendor_back_temp"))
    }

    @Test
    fun `classifyHeuristic returns null for fully unknown vendor zones`() {
        // No keyword match.
        assertNull(ThermalZoneClassifier.classifyHeuristic("vendor_secret_zone0"))
        assertNull(ThermalZoneClassifier.classifyHeuristic("mystery_temp_x"))
        assertNull(ThermalZoneClassifier.classifyHeuristic(""))
    }

    @Test
    fun `classifyHeuristic rejects chg- prefix even when name contains cpu keyword`() {
        // Critical: charger IC zones MUST NOT pollute DieCpu.
        assertNull(ThermalZoneClassifier.classifyHeuristic("chg-controller-cpu-tsens"))
        assertNull(ThermalZoneClassifier.classifyHeuristic("chg-skin-therm"))
        assertNull(ThermalZoneClassifier.classifyHeuristic("chg-cpu-zone"))
    }

    @Test
    fun `classifyHeuristic rejects pmic- prefix`() {
        assertNull(ThermalZoneClassifier.classifyHeuristic("pmic-tz"))
        assertNull(ThermalZoneClassifier.classifyHeuristic("pmic-thermal-cpu"))
        assertNull(ThermalZoneClassifier.classifyHeuristic("pmic-skin"))
    }

    // ---- Stage 1 / Stage 2 boundary -------------------------------------

    @Test
    fun `stage 1 strict allow-list still wins for v4_3_6 fixtures`() {
        // The Moto g(30) regression — existing patterns MUST still classify.
        assertEquals(ThermalCategory.DieCpu, ThermalZoneClassifier.classify("cpu0-thermal"))
        assertEquals(ThermalCategory.DieCpu, ThermalZoneClassifier.classify("cpu7-thermal"))
        // S23 patterns
        assertEquals(ThermalCategory.DieCpu, ThermalZoneClassifier.classify("cpuss-0-usr"))
        assertEquals(ThermalCategory.Skin, ThermalZoneClassifier.classify("skin-therm"))
    }

    @Test
    fun `classify trims whitespace and is case insensitive for new patterns`() {
        // Existing classifier already does .lowercase().trim() in classify().
        assertEquals(ThermalCategory.DieCpu, ThermalZoneClassifier.classify("  TSENS_TZ_SENSOR0  "))
        assertEquals(ThermalCategory.DieCpu, ThermalZoneClassifier.classify("CLUSTER0-THERMAL"))
        assertEquals(ThermalCategory.DieGpu, ThermalZoneClassifier.classify("UMP_THERMAL"))
    }
}
