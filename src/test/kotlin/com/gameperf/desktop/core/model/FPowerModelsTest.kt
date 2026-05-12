package com.gameperf.desktop.core.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the v4.5.0 FPower data shapes:
 *  - [FPowerSnapshot] data class (added to Metrics.kt alongside ThermalSnapshot)
 *  - [FPowerDiagnostic] data class
 *  - [FPowerUnavailableReason] enum
 *
 * Pure tests — no I/O, no mocks. Verifies field shape, default values,
 * serialization round-trip, and backward-compat for pre-v4.5.0 `.gameperf`
 * exports (spec FPW-004, FPW-005, FPW-012).
 *
 * Mirrors `ThermalDiagnosticTest` exactly per design ADR-1 (mirror the thermal
 * v4.4.1 architecture).
 */
class FPowerModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ── FPowerSnapshot defaults ──────────────────────────────────────────

    @Test
    fun `FPowerSnapshot zero-arg defaults render unavailable sentinel`() {
        val snap = FPowerSnapshot()
        assertEquals(-1.0, snap.fpowerMwPerFrame)
        assertEquals(-1.0, snap.powerW)
        assertEquals(-1.0, snap.currentMicroA)
        assertEquals(-1.0, snap.voltageMicroV)
        assertEquals(
            true,
            snap.fpowerAvailable,
            "default mirrors ThermalSnapshot.thermalAvailable=true for v4.4.x-compat (design §3)",
        )
        assertNull(snap.diagnostic)
    }

    @Test
    fun `FPowerSnapshot accepts unavailable state with populated diagnostic`() {
        val diag = FPowerDiagnostic(
            rawPathsTried = listOf("/sys/class/power_supply/battery/current_now"),
            lastReadout = mapOf("/sys/class/power_supply/battery/current_now" to ""),
            reason = FPowerUnavailableReason.BATTERY_PATH_MISSING,
        )
        val snap = FPowerSnapshot(
            fpowerMwPerFrame = -1.0,
            powerW = -1.0,
            currentMicroA = -1.0,
            voltageMicroV = -1.0,
            fpowerAvailable = false,
            diagnostic = diag,
        )
        assertEquals(false, snap.fpowerAvailable)
        assertNotNull(snap.diagnostic)
        assertEquals(FPowerUnavailableReason.BATTERY_PATH_MISSING, snap.diagnostic?.reason)
    }

    // ── FPowerDiagnostic shape ───────────────────────────────────────────

    @Test
    fun `FPowerDiagnostic exposes raw paths tried, last readout, and reason`() {
        val diag = FPowerDiagnostic(
            rawPathsTried = listOf(
                "/sys/class/power_supply/battery/current_now",
                "/sys/class/power_supply/Battery/current_now",
            ),
            lastReadout = mapOf(
                "/sys/class/power_supply/battery/current_now" to "",
                "/sys/class/power_supply/Battery/current_now" to "",
            ),
            reason = FPowerUnavailableReason.BATTERY_PATH_MISSING,
        )
        assertEquals(2, diag.rawPathsTried.size)
        assertEquals(2, diag.lastReadout.size)
        assertEquals(FPowerUnavailableReason.BATTERY_PATH_MISSING, diag.reason)
    }

    // ── FPowerUnavailableReason enum ─────────────────────────────────────

    @Test
    fun `FPowerUnavailableReason exposes all six reasons per spec FPW-005`() {
        // Compile-time presence check — every reason from spec must exist.
        val all = setOf(
            FPowerUnavailableReason.BATTERY_PATH_MISSING,
            FPowerUnavailableReason.FPS_ZERO,
            FPowerUnavailableReason.IMPLAUSIBLE_VALUE,
            FPowerUnavailableReason.OEM_LOCKED,
            FPowerUnavailableReason.PERMISSION_DENIED,
            FPowerUnavailableReason.UNKNOWN,
        )
        assertEquals(6, all.size, "all 6 reasons must be distinct per spec FPW-005")
        assertTrue(FPowerUnavailableReason.values().size == 6)
    }

    // ── Round-trip serialization (FPW-004) ───────────────────────────────

    @Test
    fun `FPowerDiagnostic round-trips through kotlinx Json`() {
        val diag = FPowerDiagnostic(
            rawPathsTried = listOf("/sys/class/power_supply/battery/current_now"),
            lastReadout = mapOf("/sys/class/power_supply/battery/current_now" to "-350000"),
            reason = FPowerUnavailableReason.IMPLAUSIBLE_VALUE,
        )
        val text = json.encodeToString(FPowerDiagnostic.serializer(), diag)
        val decoded = json.decodeFromString(FPowerDiagnostic.serializer(), text)
        assertEquals(diag, decoded)
    }

    @Test
    fun `FPowerSnapshot round-trips through kotlinx Json with diagnostic`() {
        val diag = FPowerDiagnostic(
            rawPathsTried = listOf("/sys/class/power_supply/battery/current_now"),
            lastReadout = mapOf("/sys/class/power_supply/battery/current_now" to ""),
            reason = FPowerUnavailableReason.PERMISSION_DENIED,
        )
        val snap = FPowerSnapshot(
            fpowerMwPerFrame = -1.0,
            powerW = -1.0,
            currentMicroA = -1.0,
            voltageMicroV = -1.0,
            fpowerAvailable = false,
            diagnostic = diag,
        )
        val text = json.encodeToString(FPowerSnapshot.serializer(), snap)
        val decoded = json.decodeFromString(FPowerSnapshot.serializer(), text)
        assertEquals(snap, decoded)
        assertEquals(false, decoded.fpowerAvailable)
        assertEquals(FPowerUnavailableReason.PERMISSION_DENIED, decoded.diagnostic?.reason)
    }

    // ── Backward-compat with pre-v4.5.0 JSON (FPW-012) ───────────────────

    @Test
    fun `Pre-v4_5_0 JSON loads with FPowerSnapshot defaults`() {
        // Simulate a pre-v4.5.0 `.gameperf` export that has no FPower section.
        // The defaulted constructor must round-trip an empty object cleanly.
        val legacyJson = """{}"""
        val decoded = json.decodeFromString(FPowerSnapshot.serializer(), legacyJson)
        assertEquals(-1.0, decoded.fpowerMwPerFrame)
        assertEquals(true, decoded.fpowerAvailable, "missing field must default to true")
        assertNull(decoded.diagnostic)
    }
}
