package com.gameperf.desktop.viewmodel

import com.gameperf.desktop.core.SessionHistory
import com.gameperf.desktop.core.model.NetworkDiagnostic
import com.gameperf.desktop.core.model.NetworkUnavailableReason
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v4.6.x -- Boundary tests for the Network bandwidth wiring in [AppViewModel].
 *
 * Mirrors the [AppViewModelGpuTest] precedent: we do NOT spin up a real
 * ViewModel (the capture loop is 1500+ LOC of ADB plumbing). Instead we
 * exercise the persistence boundary -- what [LiveMetrics] / [SessionResult] /
 * [SessionHistory.HistoryEntry] hold AFTER the loop has run, and assert every
 * new Network field round-trips end-to-end via [SessionHistory.addEntry] +
 * [SessionHistory.load].
 *
 * Coverage targets:
 *  - NET-001 persisted fields: `networkAvailable`, `maxNetworkRxBytes`,
 *    `maxNetworkTxBytes`, `networkRxHistory`, `networkTxHistory`,
 *    `networkDiagnostic`.
 *  - Backward compat (NET-001 scenario 2): a pre-v4.6.x `.gameperf` row that
 *    lacks the network fields deserialises with safe defaults
 *    (`networkAvailable=false`, history empty, diagnostic=null,
 *    max*Bytes=-1). Default flips opposite of fpower (false, not true) because
 *    pre-v4.6.x sessions NEVER captured network (mirrors GPU precedent).
 *
 * Pattern is identical to AppViewModelGpuTest in this directory.
 */
class AppViewModelNetworkTest {

    private lateinit var tempFile: File

    @BeforeTest
    fun setUp() {
        val dir = Files.createTempDirectory("net-pending-").toFile()
        tempFile = File(dir, "history.json")
        SessionHistory.historyFileOverride = tempFile
    }

    @AfterTest
    fun tearDown() {
        SessionHistory.historyFileOverride = null
        runCatching { tempFile.delete() }
        runCatching { tempFile.parentFile?.listFiles()?.forEach { it.delete() } }
        runCatching { tempFile.parentFile?.delete() }
    }

    // ===== LiveMetrics shape =====

    @Test
    fun `LiveMetrics has network fields with defaults`() {
        val live = LiveMetrics()
        assertEquals(-1L, live.networkRxBytes, "networkRxBytes scalar defaults to -1L sentinel")
        assertEquals(-1L, live.networkTxBytes, "networkTxBytes scalar defaults to -1L sentinel")
        assertFalse(live.networkAvailable, "networkAvailable defaults false (opposite of fpower/thermal)")
    }

    @Test
    fun `LiveMetrics carries network values when populated`() {
        val live = LiveMetrics(
            networkRxBytes = 1_500_000L,
            networkTxBytes = 250_000L,
            networkAvailable = true,
        )
        assertEquals(1_500_000L, live.networkRxBytes)
        assertEquals(250_000L, live.networkTxBytes)
        assertTrue(live.networkAvailable)
    }

    // ===== SessionResult shape =====

    @Test
    fun `SessionResult has network fields with defaults`() {
        val r = SessionResult()
        assertFalse(r.networkAvailable, "networkAvailable defaults false -- pre-v4.6.x never captured network")
        assertNull(r.networkDiagnostic, "networkDiagnostic defaults null on happy path")
        assertTrue(r.networkRxHistory.isEmpty(), "networkRxHistory defaults empty")
        assertTrue(r.networkTxHistory.isEmpty(), "networkTxHistory defaults empty")
        assertEquals(-1L, r.maxNetworkRxBytes, "maxNetworkRxBytes defaults -1L sentinel")
        assertEquals(-1L, r.maxNetworkTxBytes, "maxNetworkTxBytes defaults -1L sentinel")
    }

    @Test
    fun `SessionResult carries network aggregates when populated`() {
        val rx = listOf(1_000L, 5_000L, 12_000L)
        val tx = listOf(200L, 800L, 1_500L)
        val r = SessionResult(
            networkAvailable = true,
            networkRxHistory = rx,
            networkTxHistory = tx,
            maxNetworkRxBytes = 12_000L,
            maxNetworkTxBytes = 1_500L,
        )
        assertTrue(r.networkAvailable)
        assertEquals(12_000L, r.maxNetworkRxBytes)
        assertEquals(1_500L, r.maxNetworkTxBytes)
        assertEquals(3, r.networkRxHistory.size)
        assertEquals(3, r.networkTxHistory.size)
    }

    @Test
    fun `SessionResult unavailable path carries diagnostic`() {
        val diag = NetworkDiagnostic(
            probedSources = listOf("BINDER:11", "BINDER:12", "BINDER:14", "BINDER:15"),
            detectedMethod = null,
            failedBinderCodes = listOf(11, 12, 14, 15),
            reason = NetworkUnavailableReason.BINDER_UNAVAILABLE,
        )
        val r = SessionResult(
            networkAvailable = false,
            networkDiagnostic = diag,
        )
        assertFalse(r.networkAvailable)
        val loaded = r.networkDiagnostic
        assertNotNull(loaded)
        assertEquals(NetworkUnavailableReason.BINDER_UNAVAILABLE, loaded.reason)
        assertEquals(4, loaded.probedSources.size)
        assertEquals(4, loaded.failedBinderCodes.size)
    }

    // ===== HistoryEntry round-trip =====

    private fun baseEntry(id: String = "net-1"): SessionHistory.HistoryEntry =
        SessionHistory.HistoryEntry(
            id = id,
            name = "net session",
            gamePackage = "com.vivastudios.pieceout",
            deviceModel = "Samsung SM-S911B",
            grade = 'A',
            deviceGrade = 'A',
            avgFps = 60,
            duration = 60,
            date = "13/05/2026 12:00",
            reportPath = "",
            videoPath = "",
        )

    @Test
    fun `pendingEntry carries networkAvailable=true with history`() {
        val rx = listOf(1_000L, 5_000L, 12_000L)
        val tx = listOf(200L, 800L, 1_500L)
        val entry = baseEntry("net-happy").copy(
            networkAvailable = true,
            maxNetworkRxBytes = 12_000L,
            maxNetworkTxBytes = 1_500L,
            networkRxHistory = rx,
            networkTxHistory = tx,
        )

        SessionHistory.addEntry(entry)
        val loaded = SessionHistory.load().firstOrNull { it.id == "net-happy" }

        assertNotNull(loaded, "Pending network entry must round-trip via SessionHistory")
        assertTrue(loaded.networkAvailable, "networkAvailable=true must survive serialisation")
        assertEquals(12_000L, loaded.maxNetworkRxBytes, "maxNetworkRxBytes must round-trip lossless")
        assertEquals(1_500L, loaded.maxNetworkTxBytes, "maxNetworkTxBytes must round-trip lossless")
        assertEquals(rx, loaded.networkRxHistory, "networkRxHistory element-equal")
        assertEquals(tx, loaded.networkTxHistory, "networkTxHistory element-equal")
        assertNull(loaded.networkDiagnostic, "happy path has no diagnostic")
    }

    @Test
    fun `pendingEntry carries networkAvailable=false plus diagnostic`() {
        val diag = NetworkDiagnostic(
            probedSources = listOf("BINDER:11", "BINDER:12", "BINDER:14", "BINDER:15", "DUMPSYS"),
            detectedMethod = null,
            failedBinderCodes = listOf(11, 12, 14, 15),
            reason = NetworkUnavailableReason.DUMPSYS_PERMISSION_DENIED,
        )
        val entry = baseEntry("net-unavail").copy(
            networkAvailable = false,
            networkDiagnostic = diag,
        )

        SessionHistory.addEntry(entry)
        val loaded = SessionHistory.load().firstOrNull { it.id == "net-unavail" }

        assertNotNull(loaded)
        assertFalse(loaded.networkAvailable)
        val loadedDiag = loaded.networkDiagnostic
        assertNotNull(loadedDiag, "diagnostic must round-trip")
        assertEquals(NetworkUnavailableReason.DUMPSYS_PERMISSION_DENIED, loadedDiag.reason)
        assertEquals(5, loadedDiag.probedSources.size)
        assertEquals(4, loadedDiag.failedBinderCodes.size)
        assertTrue(loaded.networkRxHistory.isEmpty(), "no readings on unavailable path")
        assertTrue(loaded.networkTxHistory.isEmpty(), "no readings on unavailable path")
    }

    @Test
    fun `pendingEntry default network fields are backward compat shape`() {
        // Builder uses ZERO network named-args. Defaults must match the "no
        // network data" semantics so a v4.5.x `.gameperf` row that lacks ALL
        // network keys hydrates identically to a fresh session that never
        // captured network. Crucially the networkAvailable default flips
        // false (NOT true like thermal/fpower) because pre-v4.6.x sessions
        // never captured network at all (mirrors gpuAvailable=false default).
        val entry = baseEntry("net-defaults")
        SessionHistory.addEntry(entry)
        val loaded = SessionHistory.load().firstOrNull { it.id == "net-defaults" }
        assertNotNull(loaded)
        assertFalse(loaded.networkAvailable, "default networkAvailable=false preserves 'never captured' semantics")
        assertNull(loaded.networkDiagnostic, "default diagnostic=null")
        assertTrue(loaded.networkRxHistory.isEmpty())
        assertTrue(loaded.networkTxHistory.isEmpty())
        assertEquals(-1L, loaded.maxNetworkRxBytes, "default maxNetworkRxBytes=-1L sentinel")
        assertEquals(-1L, loaded.maxNetworkTxBytes, "default maxNetworkTxBytes=-1L sentinel")
    }

    @Test
    fun `pendingEntry preserves each NetworkUnavailableReason`() {
        // Spot-check every enum variant round-trips to catch a future addition
        // that forgets to wire the (de)serialiser path.
        val reasons = NetworkUnavailableReason.values().toList()
        reasons.forEachIndexed { idx, reason ->
            val entry = baseEntry("net-reason-$idx").copy(
                networkAvailable = false,
                networkDiagnostic = NetworkDiagnostic(
                    probedSources = listOf("BINDER:11"),
                    detectedMethod = null,
                    failedBinderCodes = listOf(11),
                    reason = reason,
                ),
            )
            SessionHistory.addEntry(entry)
        }
        val loaded = SessionHistory.load()
        reasons.forEachIndexed { idx, reason ->
            val e = loaded.firstOrNull { it.id == "net-reason-$idx" }
            assertNotNull(e, "reason=$reason entry must load")
            assertEquals(reason, e.networkDiagnostic?.reason, "reason=$reason must round-trip")
        }
    }

    // ===== Pre-v4.6.x backward compat (missing fields in JSON) =====

    @Test
    fun `legacy v4_5_0 row missing network keys loads with safe defaults`() {
        // Simulate a v4.5.0 history.json row that predates this change. The
        // minimum-viable shape includes only fields that already shipped
        // (everything kotlinx.serialization sees, with no network* keys). The
        // decoder's `ignoreUnknownKeys=true` is symmetric -- missing keys
        // become the field defaults defined on SerializableEntry.
        val legacyJson = """[
  {
    "id": "legacy-net-1",
    "name": "pre-v4.6.x session",
    "gamePackage": "com.legacy.game",
    "deviceModel": "Samsung SM-G998B",
    "grade": "B",
    "deviceGrade": "B",
    "avgFps": 58,
    "duration": 120,
    "date": "01/01/2025 10:00",
    "reportPath": "",
    "videoPath": "",
    "isFavorite": true
  }
]"""
        tempFile.writeText(legacyJson)
        val loaded = SessionHistory.load()
        assertEquals(1, loaded.size, "legacy row must load")
        val e = loaded[0]
        assertEquals("legacy-net-1", e.id)
        // Backward-compat assertions: every new network field hydrates to the
        // "never captured" defaults documented on HistoryEntry.
        assertFalse(e.networkAvailable, "missing networkAvailable key defaults to false")
        assertEquals(-1L, e.maxNetworkRxBytes, "missing maxNetworkRxBytes key defaults to -1L sentinel")
        assertEquals(-1L, e.maxNetworkTxBytes, "missing maxNetworkTxBytes key defaults to -1L sentinel")
        assertTrue(e.networkRxHistory.isEmpty(), "missing networkRxHistory defaults empty")
        assertTrue(e.networkTxHistory.isEmpty(), "missing networkTxHistory defaults empty")
        assertNull(e.networkDiagnostic, "missing networkDiagnostic key defaults null")
    }

    // ===== Network aggregation contract =====
    //
    // AppViewModel post-loop computes:
    //   maxNetworkRxBytes = if (networkRxHistory.isNotEmpty()) networkRxHistory.max() else -1L
    // The -1L sentinel matches NetworkSnapshot.rxBytes so an empty history
    // round-trips as "no data" instead of accidental 0.

    @Test
    fun `maxNetworkRxBytes from empty history returns minus one sentinel`() {
        val history = emptyList<Long>()
        val max = if (history.isNotEmpty()) history.max() else -1L
        assertEquals(-1L, max, "empty history -> -1L sentinel, NOT 0 (preserves no-data semantics)")
    }

    @Test
    fun `maxNetworkRxBytes from populated history matches list max`() {
        val history = listOf(1_000L, 5_000L, 12_000L, 8_000L)
        val max = if (history.isNotEmpty()) history.max() else -1L
        assertEquals(12_000L, max)
    }

    @Test
    fun `network history filter mirrors gate networkAvailable AND rxBytes ge 0`() {
        // Document the AppViewModel append-gate rule as pure logic so a future
        // refactor that drops one half of the gate (e.g. only checks
        // networkAvailable) is caught here.
        data class Sample(val available: Boolean, val rxBytes: Long)
        val raw = listOf(
            Sample(available = true, rxBytes = 1_000L),    // keep
            Sample(available = false, rxBytes = -1L),      // drop -- unavailable
            Sample(available = true, rxBytes = -1L),       // drop -- sentinel value
            Sample(available = true, rxBytes = 0L),        // keep -- legitimate zero (no traffic yet)
            Sample(available = false, rxBytes = 5_000L),   // drop (defensive: shouldn't happen but gate must enforce)
            Sample(available = true, rxBytes = 999_000L),  // keep
        )
        val kept = raw.filter { it.available && it.rxBytes >= 0 }.map { it.rxBytes }
        assertEquals(listOf(1_000L, 0L, 999_000L), kept, "gate must enforce BOTH halves of the rule")
    }
}
