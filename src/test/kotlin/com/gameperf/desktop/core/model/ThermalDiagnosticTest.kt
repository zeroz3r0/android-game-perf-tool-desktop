package com.gameperf.desktop.core.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the v4.4.1 thermal diagnostic data shapes:
 *  - [ThermalDiagnostic] data class
 *  - [ThermalUnavailableReason] enum
 *  - [ThermalSnapshot] additive widening with `thermalAvailable` and `diagnostic`
 *
 * Pure tests — no I/O, no mocks. Verifies field shape, default values,
 * serialization round-trip, and back-compat for v4.3.x callers.
 *
 * See `sdd/temperature-not-shown/design` ADR-4 (default-true field on
 * ThermalSnapshot) and ADR-5 (separate optional ThermalDiagnostic class).
 */
class ThermalDiagnosticTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ── ThermalDiagnostic shape ──────────────────────────────────────────

    @Test
    fun `ThermalDiagnostic exposes raw zone names, classification counts, and reason`() {
        val diag = ThermalDiagnostic(
            rawZoneNames = listOf("vendor_zone_a", "vendor_zone_b"),
            classificationCounts = mapOf("DieCpu" to 0, "Skin" to 0, "null" to 2),
            reason = ThermalUnavailableReason.ALL_ZONES_UNCLASSIFIED,
        )
        assertEquals(listOf("vendor_zone_a", "vendor_zone_b"), diag.rawZoneNames)
        assertEquals(2, diag.classificationCounts["null"])
        assertEquals(ThermalUnavailableReason.ALL_ZONES_UNCLASSIFIED, diag.reason)
    }

    // ── ThermalUnavailableReason enum ────────────────────────────────────

    @Test
    fun `ThermalUnavailableReason exposes all v4_4_1 reasons`() {
        // Compile-time presence check — every reason from spec must exist.
        val all = setOf(
            ThermalUnavailableReason.NO_ZONES_DETECTED,
            ThermalUnavailableReason.ALL_ZONES_UNCLASSIFIED,
            ThermalUnavailableReason.ALL_TEMPS_INVALID,
            ThermalUnavailableReason.PERMISSION_DENIED,
            ThermalUnavailableReason.UNKNOWN,
        )
        assertEquals(5, all.size, "all 5 reasons must be distinct")
        // values() reachable for iteration
        assertTrue(ThermalUnavailableReason.values().size == 5)
    }

    // ── ThermalDiagnostic serialization round-trip ───────────────────────

    @Test
    fun `ThermalDiagnostic round-trips through kotlinx Json`() {
        val diag = ThermalDiagnostic(
            rawZoneNames = listOf("z0", "z1", "z2"),
            classificationCounts = mapOf("DieCpu" to 1, "Skin" to 0, "null" to 2),
            reason = ThermalUnavailableReason.ALL_TEMPS_INVALID,
        )
        val text = json.encodeToString(ThermalDiagnostic.serializer(), diag)
        val decoded = json.decodeFromString(ThermalDiagnostic.serializer(), text)
        assertEquals(diag, decoded)
    }

    // ── ThermalSnapshot widened with thermalAvailable + diagnostic ───────

    @Test
    fun `ThermalSnapshot defaults preserve v4_3_x semantics (thermalAvailable=true, diagnostic=null)`() {
        // Existing v4.3.x call sites using the 4-arg constructor MUST still compile
        // and produce thermalAvailable=true, diagnostic=null (back-compat ADR-4).
        val snap = ThermalSnapshot(cpu = 45.0, gpu = 50.0, battery = 35.0, skin = 40.0)
        assertEquals(true, snap.thermalAvailable, "default must be true for v4.3.x callers")
        assertNull(snap.diagnostic)
    }

    @Test
    fun `ThermalSnapshot accepts thermalAvailable=false with populated diagnostic`() {
        val diag = ThermalDiagnostic(
            rawZoneNames = listOf("vendor_secret_zone0"),
            classificationCounts = mapOf("null" to 1),
            reason = ThermalUnavailableReason.ALL_ZONES_UNCLASSIFIED,
        )
        val snap = ThermalSnapshot(
            cpu = -1.0, gpu = -1.0, battery = -1.0, skin = -1.0,
            dieCpu = -1.0,
            thermalAvailable = false,
            diagnostic = diag,
        )
        assertEquals(false, snap.thermalAvailable)
        assertNotNull(snap.diagnostic)
        assertEquals(ThermalUnavailableReason.ALL_ZONES_UNCLASSIFIED, snap.diagnostic?.reason)
    }

    @Test
    fun `ThermalSnapshot round-trips through kotlinx Json with diagnostic`() {
        val diag = ThermalDiagnostic(
            rawZoneNames = listOf("z0", "z1"),
            classificationCounts = mapOf("DieCpu" to 0, "null" to 2),
            reason = ThermalUnavailableReason.PERMISSION_DENIED,
        )
        val snap = ThermalSnapshot(
            cpu = -1.0, gpu = -1.0, battery = -1.0, skin = -1.0,
            dieCpu = -1.0,
            thermalAvailable = false,
            diagnostic = diag,
        )
        val text = json.encodeToString(ThermalSnapshot.serializer(), snap)
        val decoded = json.decodeFromString(ThermalSnapshot.serializer(), text)
        assertEquals(snap, decoded)
        assertEquals(false, decoded.thermalAvailable)
        assertEquals(ThermalUnavailableReason.PERMISSION_DENIED, decoded.diagnostic?.reason)
    }

    @Test
    fun `Pre-v4_4_1 JSON loads with thermalAvailable defaulted to true`() {
        // Simulate a v4.3.x .gameperf export that lacks thermalAvailable / diagnostic.
        val legacyJson = """{"cpu":42.0,"gpu":50.0,"battery":35.0,"skin":40.0,"dieCpu":85.0}"""
        val decoded = json.decodeFromString(ThermalSnapshot.serializer(), legacyJson)
        assertEquals(true, decoded.thermalAvailable, "missing field must default to true")
        assertNull(decoded.diagnostic)
        assertEquals(85.0, decoded.dieCpu)
    }
}
