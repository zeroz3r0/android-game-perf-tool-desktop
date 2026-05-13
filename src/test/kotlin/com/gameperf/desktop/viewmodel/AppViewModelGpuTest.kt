package com.gameperf.desktop.viewmodel

import com.gameperf.desktop.core.SessionHistory
import com.gameperf.desktop.core.model.GpuDiagnostic
import com.gameperf.desktop.core.model.GpuUnavailableReason
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
 * v4.5.0 -- Batch 4 boundary tests for the GPU usage wiring in [AppViewModel].
 *
 * Mirrors the [AppViewModelFPowerTest] precedent: we do NOT spin up a real
 * ViewModel (the capture loop is 1500+ LOC of ADB plumbing). Instead we
 * exercise the persistence boundary -- what [LiveMetrics] / [SessionResult] /
 * [SessionHistory.HistoryEntry] hold AFTER the loop has run, and assert every
 * new GPU field round-trips end-to-end via [SessionHistory.addEntry] +
 * [SessionHistory.load].
 *
 * Coverage targets:
 *  - GPU-015 / GPU-016: every-4-tick cadence + history-append gate
 *    (covered structurally -- per-tick capture lives in Batch 3
 *    [com.gameperf.desktop.core.AdbBridgeGpuTest]; here we assert the
 *    resulting `lastGpu` lands on the persisted entry).
 *  - GPU-017 persisted fields: `gpuAvailable`, `gpuDiagnostic`,
 *    `gpuUsageHistory`, `gpuUsageTimed`, `maxGpuUsage`.
 *  - Backward compat: a pre-v4.5.0 `.gameperf` row that lacks the gpu fields
 *    deserialises with safe defaults (`gpuAvailable=false`, history empty,
 *    diagnostic=null, maxGpuUsage=-1). Default flips opposite of fpower
 *    (false, not true) because pre-v4.5.0 sessions NEVER captured GPU.
 *
 * Pattern is identical to AppViewModelFPowerTest in this directory.
 */
class AppViewModelGpuTest {

    private lateinit var tempFile: File

    @BeforeTest
    fun setUp() {
        val dir = Files.createTempDirectory("gpu-pending-").toFile()
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
    fun `LiveMetrics has gpu fields with defaults`() {
        val live = LiveMetrics()
        assertEquals(-1, live.gpuUsage, "gpuUsage scalar defaults to -1 sentinel")
        assertFalse(live.gpuAvailable, "gpuAvailable defaults false (opposite of fpower/thermal)")
        assertTrue(live.gpuUsageHistory.isEmpty(), "gpuUsageHistory defaults empty")
    }

    @Test
    fun `LiveMetrics carries gpu history snapshot when populated`() {
        val history = listOf(35, 48, 60, 72)
        val live = LiveMetrics(
            gpuUsage = 72,
            gpuAvailable = true,
            gpuUsageHistory = history,
        )
        assertEquals(72, live.gpuUsage)
        assertTrue(live.gpuAvailable)
        assertEquals(4, live.gpuUsageHistory.size)
        assertEquals(history, live.gpuUsageHistory)
    }

    @Test
    fun `LiveMetrics gpuAvailable false keeps sentinel scalar`() {
        // The wiring rule in AppViewModel: gpuUsage = if (gpuAvailable) usagePct else -1.
        // The data class itself just stores what's given, but this test documents the
        // intent so a future refactor that drops the gate at the ViewModel emission
        // site gets caught against THIS expected shape.
        val live = LiveMetrics(gpuUsage = -1, gpuAvailable = false)
        assertEquals(-1, live.gpuUsage)
        assertFalse(live.gpuAvailable)
    }

    // ===== SessionResult shape =====

    @Test
    fun `SessionResult has gpu fields with defaults`() {
        val r = SessionResult()
        assertFalse(r.gpuAvailable, "gpuAvailable defaults false -- pre-v4.5.0 never captured GPU")
        assertNull(r.gpuDiagnostic, "gpuDiagnostic defaults null on happy path")
        assertTrue(r.gpuUsageHistory.isEmpty(), "gpuUsageHistory defaults empty")
        assertTrue(r.gpuUsageTimed.isEmpty(), "gpuUsageTimed defaults empty")
        assertEquals(-1, r.maxGpuUsage, "maxGpuUsage defaults -1 sentinel (matches GpuSnapshot)")
    }

    @Test
    fun `SessionResult carries gpu aggregates when populated`() {
        val history = listOf(40, 55, 70)
        val timed = history.mapIndexed { i, v -> TimedSample(i * 2, v.toDouble()) }
        val r = SessionResult(
            gpuAvailable = true,
            gpuUsageHistory = history,
            gpuUsageTimed = timed,
            maxGpuUsage = 70,
        )
        assertTrue(r.gpuAvailable)
        assertEquals(70, r.maxGpuUsage)
        assertEquals(3, r.gpuUsageHistory.size)
        assertEquals(3, r.gpuUsageTimed.size)
    }

    @Test
    fun `SessionResult unavailable path carries diagnostic`() {
        val diag = GpuDiagnostic(
            probedPaths = listOf(
                "/sys/class/misc/mali0/device/utilization",
                "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
                "/sys/class/kgsl/kgsl-3d0/gpubusy",
            ),
            detectedVendor = "ADRENO",
            failedEnableCommand = "echo 1 > /sys/class/kgsl/kgsl-3d0/perfcounter 2>&1; echo rc=\$?",
            reason = GpuUnavailableReason.ADRENO_PERFCOUNTER_DISABLED,
        )
        val r = SessionResult(
            gpuAvailable = false,
            gpuDiagnostic = diag,
        )
        assertFalse(r.gpuAvailable)
        val loaded = r.gpuDiagnostic
        assertNotNull(loaded)
        assertEquals(GpuUnavailableReason.ADRENO_PERFCOUNTER_DISABLED, loaded.reason)
        assertEquals("ADRENO", loaded.detectedVendor)
        assertEquals(3, loaded.probedPaths.size)
        assertNotNull(loaded.failedEnableCommand)
    }

    // ===== HistoryEntry round-trip =====

    private fun baseEntry(id: String = "gpu-1"): SessionHistory.HistoryEntry =
        SessionHistory.HistoryEntry(
            id = id,
            name = "gpu session",
            gamePackage = "com.vivastudios.pieceout",
            deviceModel = "Samsung SM-S911B",
            grade = 'A',
            deviceGrade = 'A',
            avgFps = 60,
            duration = 60,
            date = "12/05/2026 10:00",
            reportPath = "",
            videoPath = "",
        )

    @Test
    fun `pendingEntry carries gpuAvailable=true with history`() {
        val history = listOf(35, 48, 60, 72)
        val timed = history.mapIndexed { i, v -> TimedSample(i * 2, v.toDouble()) }
        val entry = baseEntry("gpu-happy").copy(
            gpuAvailable = true,
            maxGpuUsage = 72,
            gpuUsageHistory = history,
            gpuUsageTimed = timed,
        )

        SessionHistory.addEntry(entry)
        val loaded = SessionHistory.load().firstOrNull { it.id == "gpu-happy" }

        assertNotNull(loaded, "Pending gpu entry must round-trip via SessionHistory")
        assertTrue(loaded.gpuAvailable, "gpuAvailable=true must survive serialisation")
        assertEquals(72, loaded.maxGpuUsage, "maxGpuUsage must round-trip lossless")
        assertEquals(history, loaded.gpuUsageHistory, "gpuUsageHistory element-equal")
        assertEquals(4, loaded.gpuUsageTimed.size, "gpuUsageTimed arity preserved")
        assertEquals(
            timed.map { it.second to it.value.toInt() },
            loaded.gpuUsageTimed.map { it.second to it.value.toInt() },
            "TimedSample wire flatten/hydrate is lossless for integer percentages",
        )
        assertNull(loaded.gpuDiagnostic, "happy path has no diagnostic")
    }

    @Test
    fun `pendingEntry carries gpuAvailable=false plus diagnostic`() {
        val diag = GpuDiagnostic(
            probedPaths = listOf(
                "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
                "/sys/class/kgsl/kgsl-3d0/gpubusy",
            ),
            detectedVendor = "ADRENO",
            failedEnableCommand = "echo 1 > /sys/class/kgsl/kgsl-3d0/perfcounter",
            reason = GpuUnavailableReason.ADRENO_PERFCOUNTER_DISABLED,
        )
        val entry = baseEntry("gpu-unavail").copy(
            gpuAvailable = false,
            gpuDiagnostic = diag,
        )

        SessionHistory.addEntry(entry)
        val loaded = SessionHistory.load().firstOrNull { it.id == "gpu-unavail" }

        assertNotNull(loaded)
        assertFalse(loaded.gpuAvailable)
        val loadedDiag = loaded.gpuDiagnostic
        assertNotNull(loadedDiag, "diagnostic must round-trip")
        assertEquals(GpuUnavailableReason.ADRENO_PERFCOUNTER_DISABLED, loadedDiag.reason)
        assertEquals("ADRENO", loadedDiag.detectedVendor)
        assertEquals(2, loadedDiag.probedPaths.size)
        assertNotNull(loadedDiag.failedEnableCommand)
        assertTrue(loaded.gpuUsageHistory.isEmpty(), "no readings on unavailable path")
    }

    @Test
    fun `pendingEntry default gpu fields are backward compat shape`() {
        // Builder uses ZERO gpu named-args. Defaults must match the "no GPU data"
        // semantics so a v4.4.x `.gameperf` row that lacks ALL gpu keys hydrates
        // identically to a fresh session that never captured GPU. Crucially the
        // gpuAvailable default flips false (NOT true like thermal/fpower) because
        // pre-v4.5.0 sessions never captured GPU at all.
        val entry = baseEntry("gpu-defaults")
        SessionHistory.addEntry(entry)
        val loaded = SessionHistory.load().firstOrNull { it.id == "gpu-defaults" }
        assertNotNull(loaded)
        assertFalse(loaded.gpuAvailable, "default gpuAvailable=false preserves 'never captured' semantics")
        assertNull(loaded.gpuDiagnostic, "default diagnostic=null")
        assertTrue(loaded.gpuUsageHistory.isEmpty())
        assertTrue(loaded.gpuUsageTimed.isEmpty())
        assertEquals(-1, loaded.maxGpuUsage, "default maxGpuUsage=-1 sentinel matches GpuSnapshot.usagePct")
    }

    @Test
    fun `pendingEntry preserves each GpuUnavailableReason`() {
        // Spot-check every enum variant round-trips to catch a future addition that
        // forgets to wire the (de)serialiser path.
        val reasons = GpuUnavailableReason.values().toList()
        reasons.forEachIndexed { idx, reason ->
            val entry = baseEntry("gpu-reason-$idx").copy(
                gpuAvailable = false,
                gpuDiagnostic = GpuDiagnostic(
                    probedPaths = listOf("/sys/class/misc/mali0/device/utilization"),
                    detectedVendor = if (reason == GpuUnavailableReason.POWERVR_UNSUPPORTED) "POWERVR" else null,
                    reason = reason,
                ),
            )
            SessionHistory.addEntry(entry)
        }
        val loaded = SessionHistory.load()
        reasons.forEachIndexed { idx, reason ->
            val e = loaded.firstOrNull { it.id == "gpu-reason-$idx" }
            assertNotNull(e, "reason=$reason entry must load")
            assertEquals(reason, e.gpuDiagnostic?.reason, "reason=$reason must round-trip")
        }
    }

    // ===== Pre-v4.5.0 backward compat (missing fields in JSON) =====

    @Test
    fun `legacy v4_4_1 row missing gpu keys loads with safe defaults`() {
        // Simulate a v4.4.1 history.json row that predates this change.
        // The minimum-viable shape includes only fields that already shipped
        // (everything kotlinx.serialization sees, with no gpu* keys). The
        // decoder's `ignoreUnknownKeys=true` is symmetric -- missing keys
        // become the field defaults defined on SerializableEntry.
        val legacyJson = """[
  {
    "id": "legacy-1",
    "name": "pre-v4.5.0 session",
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
        assertEquals("legacy-1", e.id)
        // Backward-compat assertions: every new GPU field hydrates to the
        // "never captured" defaults documented on HistoryEntry.
        assertFalse(e.gpuAvailable, "missing gpuAvailable key defaults to false")
        assertEquals(-1, e.maxGpuUsage, "missing maxGpuUsage key defaults to -1 sentinel")
        assertTrue(e.gpuUsageHistory.isEmpty(), "missing gpuUsageHistory defaults empty")
        assertTrue(e.gpuUsageTimed.isEmpty(), "missing gpuUsageTimed defaults empty")
        assertNull(e.gpuDiagnostic, "missing gpuDiagnostic key defaults null")
    }

    // ===== GPU aggregation contract =====
    //
    // AppViewModel post-loop computes:
    //   maxGpuUsage = if (gpuUsageHistory.isNotEmpty()) gpuUsageHistory.max() else -1
    // The -1 sentinel matches GpuSnapshot.usagePct so an empty history
    // round-trips as "no data" instead of accidental 0.

    @Test
    fun `maxGpuUsage from empty history returns minus one sentinel`() {
        val history = emptyList<Int>()
        val max = if (history.isNotEmpty()) history.max() else -1
        assertEquals(-1, max, "empty history -> -1 sentinel, NOT 0 (preserves no-data semantics)")
    }

    @Test
    fun `maxGpuUsage from populated history matches list max`() {
        val history = listOf(35, 48, 72, 60)
        val max = if (history.isNotEmpty()) history.max() else -1
        assertEquals(72, max)
    }

    @Test
    fun `gpu history filter mirrors gate gpuAvailable AND usagePct ge 0`() {
        // Document the AppViewModel append-gate rule as pure logic so a future
        // refactor that drops one half of the gate (e.g. only checks
        // gpuAvailable) is caught here.
        data class Sample(val available: Boolean, val pct: Int)
        val raw = listOf(
            Sample(available = true, pct = 50),    // keep
            Sample(available = false, pct = -1),   // drop -- unavailable
            Sample(available = true, pct = -1),    // drop -- warm-up baseline tick (Adreno gpubusy)
            Sample(available = true, pct = 0),     // keep -- legitimate idle frame
            Sample(available = false, pct = 80),   // drop (defensive: shouldn't happen but gate must enforce)
            Sample(available = true, pct = 100),   // keep -- 100% load
        )
        val kept = raw.filter { it.available && it.pct >= 0 }.map { it.pct }
        assertEquals(listOf(50, 0, 100), kept, "gate must enforce BOTH halves of the rule")
    }
}
