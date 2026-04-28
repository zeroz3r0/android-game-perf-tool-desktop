package com.gameperf.desktop.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the v4.3.6 thermal extraction pipeline. Three real-device fixtures
 * verify the [AdbThermalParser.parseThermalZonesOutput] classifier + plausibility
 * windows against the patterns the v4.2.5 substring-match approach was
 * mis-handling.
 *
 * Fixtures are based on PUBLIC AOSP / vendor kernel source patterns — see
 * `sdd/grading-thermal-realism/explore` for the reasoning behind each entry.
 *
 * Pure tests, no mocks, no I/O — the parser takes a raw multi-line string.
 */
class AdbBridgeThermalTest {

    // ── Fixture A: Snapdragon 8 Gen 2 / Galaxy S23 ───────────────────────

    private val s23Fixture = """
        skin-therm:42000
        xo-therm:38000
        cpuss-0-usr:93000
        cpuss-1-usr:91000
        cpu0-thermal:88000
        gold_cluster_thermal:92000
        quiet-therm:43000
        pm8550_tz:48000
        chg-skin-therm:45000
        usbc-therm:50000
        gpuss-0-usr:78000
        battery:39000
    """.trimIndent()

    @Test
    fun `S23 fixture - skin uses MAX of skin allow-list zones`() {
        val s = AdbThermalParser.parseThermalZonesOutput(s23Fixture)
        // skin-therm=42, xo-therm=38, quiet-therm=43 → MAX=43
        assertEquals(43.0, s.skin)
    }

    @Test
    fun `S23 fixture - dieCpu uses MAX of cpu die zones (93C is the right answer)`() {
        val s = AdbThermalParser.parseThermalZonesOutput(s23Fixture)
        // cpuss-0-usr=93, cpuss-1-usr=91, cpu0-thermal=88, gold_cluster_thermal=92 → MAX=93
        assertEquals(93.0, s.dieCpu)
    }

    @Test
    fun `S23 fixture - dieGpu picks gpuss zone`() {
        val s = AdbThermalParser.parseThermalZonesOutput(s23Fixture)
        assertEquals(78.0, s.gpu)
    }

    @Test
    fun `S23 fixture - battery picks the battery zone`() {
        val s = AdbThermalParser.parseThermalZonesOutput(s23Fixture)
        assertEquals(39.0, s.battery)
    }

    @Test
    fun `S23 fixture - PMIC and charger and USB-C zones are IGNORED`() {
        val s = AdbThermalParser.parseThermalZonesOutput(s23Fixture)
        // pm8550_tz=48, chg-skin-therm=45, usbc-therm=50 — none of these should
        // contaminate skin or dieCpu. If they had, skin would be 50 or dieCpu
        // would include 48. The asserted MAX values above already prove this,
        // but we make the intent explicit here.
        assertTrue(s.skin < 50.0, "skin must NOT include usbc-therm 50C, got ${s.skin}")
        assertTrue(s.dieCpu < 95.0, "dieCpu must NOT include PMIC 48C contamination, got ${s.dieCpu}")
    }

    @Test
    fun `S23 fixture - legacy cpu field equals skin (skin available)`() {
        val s = AdbThermalParser.parseThermalZonesOutput(s23Fixture)
        // Legacy semantics: ThermalSnapshot.cpu = skin when skin > 0.
        assertEquals(s.skin, s.cpu)
    }

    // ── Fixture B: Pixel 8 Pro / Tensor G3 ───────────────────────────────

    private val pixel8Fixture = """
        quiet-therm:38000
        back-therm:36000
        pa-therm:39000
        disp-therm:35000
        cpu-1-step:75000
        cpu-1-fast:72000
        mali:65000
        battery:35000
    """.trimIndent()

    @Test
    fun `Pixel 8 fixture - skin picks pa-therm as max`() {
        val s = AdbThermalParser.parseThermalZonesOutput(pixel8Fixture)
        // quiet-therm=38, back-therm=36, pa-therm=39, disp-therm=35 → MAX=39
        assertEquals(39.0, s.skin)
    }

    @Test
    fun `Pixel 8 fixture - dieCpu picks Tensor cpu-N-step`() {
        val s = AdbThermalParser.parseThermalZonesOutput(pixel8Fixture)
        // cpu-1-step=75, cpu-1-fast=72 → MAX=75
        assertEquals(75.0, s.dieCpu)
    }

    @Test
    fun `Pixel 8 fixture - dieGpu picks Mali zone`() {
        val s = AdbThermalParser.parseThermalZonesOutput(pixel8Fixture)
        assertEquals(65.0, s.gpu)
    }

    @Test
    fun `Pixel 8 fixture - battery is correctly picked`() {
        val s = AdbThermalParser.parseThermalZonesOutput(pixel8Fixture)
        assertEquals(35.0, s.battery)
    }

    // ── Fixture C: Generic Snapdragon 7 series with unknown zones ────────

    private val sd7Fixture = """
        skin-therm:40000
        cpuss-0-usr:70000
        weird_unknown_zone:99999
        another_zzz:50000
        battery:34000
    """.trimIndent()

    @Test
    fun `SD7 fixture - unknown zones are silently ignored`() {
        val s = AdbThermalParser.parseThermalZonesOutput(sd7Fixture)
        assertEquals(40.0, s.skin)
        assertEquals(70.0, s.dieCpu)
        assertEquals(34.0, s.battery)
        // Unknown zones ignored — they don't appear in any bucket.
    }

    // ── Plausibility windows ─────────────────────────────────────────────

    @Test
    fun `skin temperature above 60C is rejected as sensor error`() {
        // Cellphone case temperatures top out around 50°C even on a thermal
        // worst case. 95°C from a "skin" zone is a corrupt sensor read.
        val fixture = "skin-therm:95000"
        val s = AdbThermalParser.parseThermalZonesOutput(fixture)
        assertEquals(-1.0, s.skin)
    }

    @Test
    fun `die temperature above 120C is rejected as sensor error`() {
        val fixture = "cpuss-0-usr:850000"  // the historical 850°C corrupt-zone case
        val s = AdbThermalParser.parseThermalZonesOutput(fixture)
        assertEquals(-1.0, s.dieCpu)
    }

    @Test
    fun `negative temperature is rejected`() {
        val fixture = """
            skin-therm:-5000
            cpuss-0-usr:75000
        """.trimIndent()
        val s = AdbThermalParser.parseThermalZonesOutput(fixture)
        assertEquals(-1.0, s.skin)
        assertEquals(75.0, s.dieCpu)
    }

    @Test
    fun `legacy cpu field falls back to dieCpu when no skin sensor`() {
        val fixture = """
            cpuss-0-usr:75000
            mali:60000
        """.trimIndent()
        val s = AdbThermalParser.parseThermalZonesOutput(fixture)
        assertEquals(-1.0, s.skin, "no skin sensor in this fixture")
        assertEquals(75.0, s.dieCpu)
        // Legacy cpu = dieCpu when skin unavailable.
        assertEquals(75.0, s.cpu)
    }

    // ── thermalservice fallback merge ────────────────────────────────────

    @Test
    fun `mergeThermalServiceFallback fills missing fields from dumpsys`() {
        val existing = AdbThermalParser.parseThermalZonesOutput("")  // all -1.0
        val dump = """
            Temperature{mValue=72.5, mType=1, mName=cpu0,
            Temperature{mValue=68.0, mType=2, mName=g3d,
            Temperature{mValue=35.0, mType=3, mName=battery,
            Temperature{mValue=41.0, mType=4, mName=skin,
        """.trimIndent()
        val thermalRegex = Regex("Temperature\\{mValue=([\\d.]+),\\s*mType=\\d+,\\s*mName=([^,]+),")
        val merged = AdbThermalParser.mergeThermalServiceFallback(existing, dump, thermalRegex)
        assertEquals(72.5, merged.dieCpu)
        assertEquals(68.0, merged.gpu)
        assertEquals(35.0, merged.battery)
        assertEquals(41.0, merged.skin)
    }
}
