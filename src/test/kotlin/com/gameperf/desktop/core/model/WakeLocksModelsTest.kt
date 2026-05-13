package com.gameperf.desktop.core.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Invariants for the wake-locks single-session model
 * (sdd/vitals-rate-and-wakelocks, design §3).
 *
 * Pure-Kotlin tests — no I/O, no mocks. Verifies:
 *  - Default constructor produces a "no data" snapshot (mirrors GpuSnapshot /
 *    NetworkSnapshot v4.5/v4.6 precedent: `available=false` + `-1L` sentinels +
 *    `diagnostic=null`).
 *  - `kotlinx.serialization` round-trip works for both the snapshot and its
 *    diagnostic payload (required so `.gameperf` history exports round-trip).
 *  - Every [WakeLocksUnavailableReason] enum entry is serialisable as its name.
 */
class WakeLocksModelsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
        isLenient = true
    }

    @Test
    fun `default snapshot encodes the no-data sentinel state`() {
        val snap = WakeLocksSnapshot()
        assertEquals(-1L, snap.totalScreenOffMs, "default totalScreenOffMs must be -1L sentinel")
        assertEquals(-1L, snap.totalScreenOnMs, "default totalScreenOnMs must be -1L sentinel")
        assertEquals(0, snap.partialLockCount, "default partialLockCount must be 0")
        assertFalse(snap.wakeLocksAvailable, "default wakeLocksAvailable must be false (no data)")
        assertNull(snap.diagnostic, "default diagnostic must be null on the happy path")
    }

    @Test
    fun `snapshot round-trips through kotlinx serialization preserving all fields`() {
        val original = WakeLocksSnapshot(
            totalScreenOffMs = 7_500_000L,
            totalScreenOnMs = 250_000L,
            partialLockCount = 4,
            wakeLocksAvailable = true,
            diagnostic = null,
        )
        val text = json.encodeToString(WakeLocksSnapshot.serializer(), original)
        val decoded = json.decodeFromString(WakeLocksSnapshot.serializer(), text)
        assertEquals(original, decoded, "round-trip must preserve every field byte-for-byte")
    }

    @Test
    fun `diagnostic round-trips with reason and probed command`() {
        val diag = WakeLocksDiagnostic(
            probedCommand = "dumpsys batterystats --charged com.example.game",
            reason = WakeLocksUnavailableReason.PKG_NOT_FOUND,
        )
        val text = json.encodeToString(WakeLocksDiagnostic.serializer(), diag)
        val decoded = json.decodeFromString(WakeLocksDiagnostic.serializer(), text)
        assertEquals(diag, decoded)
        assertEquals(WakeLocksUnavailableReason.PKG_NOT_FOUND, decoded.reason)
        assertTrue(decoded.probedCommand.startsWith("dumpsys"))
    }

    @Test
    fun `snapshot with diagnostic round-trips`() {
        val original = WakeLocksSnapshot(
            wakeLocksAvailable = false,
            diagnostic = WakeLocksDiagnostic(
                probedCommand = "dumpsys batterystats --charged com.foo",
                reason = WakeLocksUnavailableReason.PARSE_FAILED,
            ),
        )
        val text = json.encodeToString(WakeLocksSnapshot.serializer(), original)
        val decoded = json.decodeFromString(WakeLocksSnapshot.serializer(), text)
        assertNotNull(decoded.diagnostic)
        assertEquals(WakeLocksUnavailableReason.PARSE_FAILED, decoded.diagnostic!!.reason)
        assertFalse(decoded.wakeLocksAvailable)
    }

    @Test
    fun `every unavailable reason has a stable name for wire encoding`() {
        // Spec design §3 closes the set at 4 reasons. Locking the names guards
        // against accidental renames that would break .gameperf round-trips.
        val expected = setOf(
            "PKG_NOT_FOUND",
            "PARSE_FAILED",
            "OUT_OF_RANGE_VALUE",
            "CAPTURE_THREW",
        )
        val actual = WakeLocksUnavailableReason.values().map { it.name }.toSet()
        assertEquals(expected, actual, "WakeLocksUnavailableReason names are wire-stable")
    }
}
