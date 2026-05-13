package com.gameperf.desktop.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the v4.6.x network bandwidth model shapes:
 *  - [NetworkUnavailableReason] enum (exhaustive — exactly 5 reasons)
 *  - [NetworkDiagnostic] data class with `probedSources` cap at 10 in factory
 *  - [NetworkSnapshot] sentinel defaults (`-1L / -1L / false / null`)
 *
 * Pure tests — no I/O, no mocks. Verifies field shape, default values,
 * serialization round-trip, and the factory-level probedSources cap that
 * prevents unbounded growth of failed-session export size.
 *
 * Mirrors the v4.4.1 ThermalDiagnostic and v4.5.0 GpuDiagnostic patterns
 * (see `sdd/network-bandwidth-total-app/spec` NET-001 + NET-002).
 */
class NetworkModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ── NetworkUnavailableReason enum ────────────────────────────────────

    @Test
    fun `NetworkUnavailableReason exposes exactly the 5 spec reasons`() {
        val all = setOf(
            NetworkUnavailableReason.ALL_PROBES_FAILED,
            NetworkUnavailableReason.DUMPSYS_PERMISSION_DENIED,
            NetworkUnavailableReason.BINDER_UNAVAILABLE,
            NetworkUnavailableReason.IMPLAUSIBLE_VALUE,
            NetworkUnavailableReason.CAPTURE_THREW,
        )
        // Compile-time presence + enum cardinality check — spec NET-002 fixes the closed set at 5.
        assertEquals(5, NetworkUnavailableReason.entries.size, "spec NET-002 fixes the closed set at 5 reasons")
        assertEquals(all, NetworkUnavailableReason.entries.toSet())
    }

    @Test
    fun `NetworkUnavailableReason round-trips through JSON inside NetworkDiagnostic`() {
        for (reason in NetworkUnavailableReason.entries) {
            val diag = NetworkDiagnostic(
                probedSources = listOf("BINDER:11"),
                reason = reason,
            )
            val encoded = json.encodeToString(diag)
            val decoded = json.decodeFromString<NetworkDiagnostic>(encoded)
            assertEquals(reason, decoded.reason, "reason $reason must round-trip exactly")
        }
    }

    // ── NetworkDiagnostic shape ──────────────────────────────────────────

    @Test
    fun `NetworkDiagnostic exposes probedSources, detectedMethod, failedBinderCodes and reason`() {
        val diag = NetworkDiagnostic(
            probedSources = listOf("BINDER:11", "BINDER:12"),
            detectedMethod = "BINDER:11",
            failedBinderCodes = listOf(12, 14),
            reason = NetworkUnavailableReason.BINDER_UNAVAILABLE,
        )
        assertEquals(listOf("BINDER:11", "BINDER:12"), diag.probedSources)
        assertEquals("BINDER:11", diag.detectedMethod)
        assertEquals(listOf(12, 14), diag.failedBinderCodes)
        assertEquals(NetworkUnavailableReason.BINDER_UNAVAILABLE, diag.reason)
    }

    @Test
    fun `NetworkDiagnostic defaults detectedMethod=null and failedBinderCodes=empty`() {
        val diag = NetworkDiagnostic(
            probedSources = listOf("DUMPSYS"),
            reason = NetworkUnavailableReason.ALL_PROBES_FAILED,
        )
        assertNull(diag.detectedMethod)
        assertEquals(emptyList(), diag.failedBinderCodes)
    }

    @Test
    fun `NetworkDiagnostic create factory caps probedSources to 10`() {
        // Spec design §2.4 (capped diagnostic) — failed-session export size must stay predictable.
        val flood = (1..50).map { "BINDER:$it" }
        val capped = NetworkDiagnostic.create(
            probedSources = flood,
            reason = NetworkUnavailableReason.ALL_PROBES_FAILED,
        )
        assertTrue(capped.probedSources.size <= 10,
            "factory MUST cap probedSources to at most 10 entries (got ${capped.probedSources.size})")
        // First 10 must be preserved in order — the cap is a head-take.
        assertEquals(flood.take(10), capped.probedSources)
    }

    @Test
    fun `NetworkDiagnostic create factory leaves small lists untouched`() {
        val small = listOf("BINDER:11", "DUMPSYS")
        val diag = NetworkDiagnostic.create(
            probedSources = small,
            reason = NetworkUnavailableReason.IMPLAUSIBLE_VALUE,
        )
        assertEquals(small, diag.probedSources, "lists already <= 10 must be passed through unchanged")
    }

    // ── NetworkSnapshot defaults ─────────────────────────────────────────

    @Test
    fun `default-constructed NetworkSnapshot has sentinel values`() {
        // Spec NET-001: -1L / -1L / false / null mirrors FPower + GPU precedent.
        val snap = NetworkSnapshot()
        assertEquals(-1L, snap.rxBytes)
        assertEquals(-1L, snap.txBytes)
        assertEquals(false, snap.networkAvailable)
        assertNull(snap.diagnostic)
    }

    @Test
    fun `NetworkSnapshot round-trips through JSON when populated`() {
        val snap = NetworkSnapshot(
            rxBytes = 12345L,
            txBytes = 678L,
            networkAvailable = true,
            diagnostic = null,
        )
        val encoded = json.encodeToString(snap)
        val decoded = json.decodeFromString<NetworkSnapshot>(encoded)
        assertEquals(12345L, decoded.rxBytes)
        assertEquals(678L, decoded.txBytes)
        assertEquals(true, decoded.networkAvailable)
        assertNull(decoded.diagnostic)
    }

    @Test
    fun `NetworkSnapshot round-trips with embedded diagnostic`() {
        val snap = NetworkSnapshot(
            rxBytes = -1L,
            txBytes = -1L,
            networkAvailable = false,
            diagnostic = NetworkDiagnostic(
                probedSources = listOf("BINDER:11", "BINDER:12", "DUMPSYS"),
                detectedMethod = null,
                failedBinderCodes = listOf(11, 12),
                reason = NetworkUnavailableReason.ALL_PROBES_FAILED,
            ),
        )
        val encoded = json.encodeToString(snap)
        val decoded = json.decodeFromString<NetworkSnapshot>(encoded)
        assertNotNull(decoded.diagnostic)
        assertEquals(NetworkUnavailableReason.ALL_PROBES_FAILED, decoded.diagnostic!!.reason)
        assertEquals(listOf(11, 12), decoded.diagnostic!!.failedBinderCodes)
    }

    @Test
    fun `older JSON without network fields decodes to defaults via ignoreUnknownKeys`() {
        // Forward-compat — NET-001 scenario 2: older clients must read newer payloads
        // gracefully (Json { ignoreUnknownKeys = true }) and brand-new NetworkSnapshot()
        // defaults must absorb missing-field payloads on the newer side.
        val payloadWithExtraField = """{"rxBytes":42,"txBytes":7,"networkAvailable":true,"future":"ignored"}"""
        val decoded = json.decodeFromString<NetworkSnapshot>(payloadWithExtraField)
        assertEquals(42L, decoded.rxBytes)
        assertEquals(7L, decoded.txBytes)
        assertTrue(decoded.networkAvailable)
        assertNull(decoded.diagnostic)
    }
}
