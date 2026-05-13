package com.gameperf.desktop.core

import com.gameperf.desktop.core.model.ThermalUnavailableReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v4.4.1 -- Extended parser tests for the "temperature-not-shown" change.
 *
 * Verifies [AdbThermalParser.parseThermalZonesOutput] correctly:
 *  - derives [com.gameperf.desktop.core.model.ThermalSnapshot.thermalAvailable]
 *    from "at least one CPU/SKIN zone yielded a valid temperature in the
 *    plausibility window" rule;
 *  - populates [com.gameperf.desktop.core.model.ThermalSnapshot.diagnostic]
 *    with raw zone names + bucket counts + reason when thermalAvailable=false;
 *  - calls [ThermalZoneClassifier.classifyHeuristic] (stage 2) when stage 1
 *    returns null, surfaces unsupported-vendor zones via the catch-all OR
 *    keeps `thermalAvailable=false` when nothing matches at all.
 *
 * Pure tests, no mocks. Existing v4.3.6 fixtures ([AdbBridgeThermalTest])
 * MUST continue to pass unchanged (back-compat default thermalAvailable=true
 * for any populated snapshot).
 *
 * See `sdd/temperature-not-shown/spec` AdbThermalParser requirement +
 * scenarios, and design ADR-3, ADR-4, ADR-5.
 */
class AdbThermalParserExtendedTest {

    // ---- Happy path: at least one CPU zone valid -> thermalAvailable=true

    @Test
    fun `mixed valid plus unknown zones - thermalAvailable=true`() {
        val fixture = """
            cpu0-thermal:45000
            unknown_vendor_zzz:50000
            another_mystery:60000
        """.trimIndent()
        val s = AdbThermalParser.parseThermalZonesOutput(fixture)
        assertEquals(true, s.thermalAvailable)
        assertNull(s.diagnostic)
        assertEquals(45.0, s.dieCpu)
    }

    @Test
    fun `S23-style fixture preserves v4_3_6 behavior - thermalAvailable=true`() {
        // Existing v4.3.6 fixture (AdbBridgeThermalTest s23Fixture, abridged) -
        // we re-assert the new flag stays true and diagnostic stays null.
        val fixture = """
            skin-therm:42000
            cpuss-0-usr:93000
            gpuss-0-usr:78000
            battery:39000
        """.trimIndent()
        val s = AdbThermalParser.parseThermalZonesOutput(fixture)
        assertEquals(true, s.thermalAvailable)
        assertNull(s.diagnostic)
        assertEquals(42.0, s.skin)
        assertEquals(93.0, s.dieCpu)
    }

    // ---- All-null classification -> thermalAvailable=false + diagnostic

    @Test
    fun `all zones unclassified - thermalAvailable=false with ALL_ZONES_UNCLASSIFIED reason`() {
        val fixture = """
            vendor_secret_zone0:42000
            mystery_temp_x:39000
            another_unknown_zzz:35000
        """.trimIndent()
        val s = AdbThermalParser.parseThermalZonesOutput(fixture)
        assertEquals(false, s.thermalAvailable)
        assertNotNull(s.diagnostic)
        assertEquals(ThermalUnavailableReason.ALL_ZONES_UNCLASSIFIED, s.diagnostic?.reason)
        // Raw zone names sample MUST surface the original vendor strings.
        assertTrue(
            s.diagnostic!!.rawZoneNames.contains("vendor_secret_zone0"),
            "raw zone names must include the unrecognized vendor zone, got ${s.diagnostic!!.rawZoneNames}"
        )
        assertTrue(s.diagnostic!!.rawZoneNames.contains("mystery_temp_x"))
        assertEquals(3, s.diagnostic!!.classificationCounts["null"])
    }

    @Test
    fun `permission-denied empty body - thermalAvailable=false with NO_ZONES_DETECTED`() {
        val fixture = ""
        val s = AdbThermalParser.parseThermalZonesOutput(fixture)
        assertEquals(false, s.thermalAvailable)
        assertNotNull(s.diagnostic)
        assertEquals(ThermalUnavailableReason.NO_ZONES_DETECTED, s.diagnostic?.reason)
        assertEquals(emptyList<String>(), s.diagnostic?.rawZoneNames)
    }

    @Test
    fun `all temps invalid out of plausibility window - thermalAvailable=false with ALL_TEMPS_INVALID`() {
        // All zones classify but every temperature is out of range -> reason
        // distinguishes "naming failed" (ALL_ZONES_UNCLASSIFIED) from
        // "values are corrupt" (ALL_TEMPS_INVALID).
        val fixture = """
            cpu0-thermal:850000
            cpuss-0-usr:200000
            skin-therm:95000
        """.trimIndent()
        val s = AdbThermalParser.parseThermalZonesOutput(fixture)
        assertEquals(false, s.thermalAvailable)
        assertNotNull(s.diagnostic)
        assertEquals(ThermalUnavailableReason.ALL_TEMPS_INVALID, s.diagnostic?.reason)
        assertTrue(s.diagnostic!!.rawZoneNames.contains("cpu0-thermal"))
    }

    // ---- Stage-2 catch-all rescues unknown-vendor zones -----------------

    @Test
    fun `stage-2 keyword catch-all rescues unknown-vendor cpu zone`() {
        // The strict allow-list does NOT match `vendor_special_cpu_die`,
        // but the stage-2 heuristic recognizes the `cpu` keyword and
        // classifies it as DieCpu -> thermalAvailable=true.
        val fixture = """
            vendor_special_cpu_die:50000
        """.trimIndent()
        val s = AdbThermalParser.parseThermalZonesOutput(fixture)
        assertEquals(true, s.thermalAvailable)
        assertNull(s.diagnostic)
        assertEquals(50.0, s.dieCpu)
    }

    @Test
    fun `stage-2 catch-all does NOT rescue chg- prefix zones (prefix guard wins)`() {
        // The catch-all sees `cpu` keyword in `chg-controller-cpu-tsens` but
        // the prefix guard rejects FIRST. Result: nothing classified -> false.
        val fixture = """
            chg-controller-cpu-tsens:35000
            chg-skin-therm:33000
        """.trimIndent()
        val s = AdbThermalParser.parseThermalZonesOutput(fixture)
        assertEquals(false, s.thermalAvailable)
        assertNotNull(s.diagnostic)
        // Reason: stage 1 ignored chg-skin-therm (IGNORE_LITERAL); stage 2
        // rejected the other via prefix guard. Both end as null, so we
        // surface ALL_ZONES_UNCLASSIFIED so the user knows we saw zones
        // but couldn't bucket any.
        assertEquals(ThermalUnavailableReason.ALL_ZONES_UNCLASSIFIED, s.diagnostic?.reason)
    }

    // ---- Diagnostic raw-name truncation ---------------------------------

    @Test
    fun `diagnostic rawZoneNames truncates to ten entries`() {
        // Build a fixture with 15 unknown zones; only the first 10 should
        // surface in rawZoneNames so the report HTML stays bounded.
        val fixture = (0 until 15).joinToString("\n") { "vendor_unknown_zone_$it:40000" }
        val s = AdbThermalParser.parseThermalZonesOutput(fixture)
        assertEquals(false, s.thermalAvailable)
        assertNotNull(s.diagnostic)
        assertEquals(10, s.diagnostic!!.rawZoneNames.size, "rawZoneNames must truncate to 10")
    }

    // ---- Classification counts populated --------------------------------

    @Test
    fun `diagnostic classificationCounts surfaces null-classification count`() {
        val fixture = """
            vendor_a_zzz:30000
            vendor_b_zzz:35000
        """.trimIndent()
        val s = AdbThermalParser.parseThermalZonesOutput(fixture)
        assertNotNull(s.diagnostic)
        assertEquals(2, s.diagnostic!!.classificationCounts["null"])
    }
}
