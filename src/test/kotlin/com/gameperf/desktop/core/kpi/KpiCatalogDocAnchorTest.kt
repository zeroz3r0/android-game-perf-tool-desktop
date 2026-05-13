package com.gameperf.desktop.core.kpi

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 8 — Anti-drift anchor between [KpiCatalog] and
 * `docs/competitive-analysis-and-kpis.md`.
 *
 * The docs file is the design source of truth for KPI thresholds; the
 * catalog is the runtime source of truth for scoring. When one changes
 * without the other, scoring drifts silently from the documented contract.
 *
 * This test:
 *  1. Reads the docs file (explicit `Charsets.UTF_8` per CLAUDE.md mojibake
 *     lesson — `BufferedReader(InputStreamReader(...))` Java-style is banned
 *     because it falls back to platform charset).
 *  2. Asserts each anchor citation phrase is still present in the docs.
 *  3. Asserts the [KpiCatalog] threshold matching that anchor is unchanged.
 *
 * Anchors picked from doc §3.1 (Android Vitals) and §3.6 (PerfDog FPower)
 * because those numbers are externally cited (Google + Tencent) and are
 * unlikely to change without a docs revision that would be reviewed.
 *
 * @since v4.5 (kpi-scoring internal v1)
 */
class KpiCatalogDocAnchorTest {

    private val docContent: String by lazy {
        val file = File("docs/competitive-analysis-and-kpis.md")
        assertTrue(file.exists(), "docs/competitive-analysis-and-kpis.md must exist for anchor test")
        // CLAUDE.md mojibake lesson — pass UTF-8 explicitly. `File.readText()`
        // already defaults to UTF-8 in Kotlin stdlib but we pin it to be
        // immune to future stdlib defaulting changes.
        file.readText(Charsets.UTF_8)
    }

    // ─────────────────── Anchor 1: cold start ≥ 5s ───────────────────

    @Test
    fun `anchor — cold start slow threshold ≥5s matches catalog floor across all tiers`() {
        assertTrue(
            docContent.contains("≥ 5 seconds"),
            "doc anchor 'Cold start ≥ 5 seconds' (§3.1) must remain present — catalog COLD_START_MS floor is locked to this number",
        )
        val cold = KpiCatalog.byId(KpiId.COLD_START_MS)
        DeviceTier.values().forEach { tier ->
            assertEquals(
                5000.0,
                cold.thresholds[tier]!!.floor,
                "COLD_START_MS floor for $tier must match docs §3.1 Vitals ≥5s slow threshold",
            )
        }
    }

    // ─────────────────── Anchor 2: warm start ≥ 2s ───────────────────

    @Test
    fun `anchor — warm start slow threshold ≥2s matches catalog floor across all tiers`() {
        assertTrue(
            docContent.contains("≥ 2 seconds"),
            "doc anchor 'Warm start ≥ 2 seconds' (§3.1) must remain present",
        )
        val warm = KpiCatalog.byId(KpiId.WARM_START_MS)
        DeviceTier.values().forEach { tier ->
            assertEquals(
                2000.0,
                warm.thresholds[tier]!!.floor,
                "WARM_START_MS floor for $tier must match docs §3.1 Vitals ≥2s slow threshold",
            )
        }
    }

    // ─────────────────── Anchor 3: hot start ≥ 1s ───────────────────

    @Test
    fun `anchor — hot start slow threshold ≥1s matches catalog floor across all tiers`() {
        assertTrue(
            docContent.contains("≥ 1 second"),
            "doc anchor 'Hot start ≥ 1 second' (§3.1) must remain present",
        )
        val hot = KpiCatalog.byId(KpiId.HOT_START_MS)
        DeviceTier.values().forEach { tier ->
            assertEquals(
                1000.0,
                hot.thresholds[tier]!!.floor,
                "HOT_START_MS floor for $tier must match docs §3.1 Vitals ≥1s slow threshold",
            )
        }
    }

    // ─────────────────── Anchor 4: slow frames > 50% ───────────────────

    @Test
    fun `anchor — excessive slow frames 50pct bad threshold matches catalog floor`() {
        assertTrue(
            docContent.contains(">50% of frames had render time >16 ms"),
            "doc anchor 'Excessive slow frames >50%' (§3.1) must remain present",
        )
        val slow = KpiCatalog.byId(KpiId.SLOW_FRAMES)
        DeviceTier.values().forEach { tier ->
            assertEquals(
                50.0,
                slow.thresholds[tier]!!.floor,
                "SLOW_FRAMES floor for $tier must match docs §3.1 Vitals >50% bad threshold",
            )
        }
    }

    // ─────────────────── Anchor 5: FPower 50 / 65 mW/frame ───────────────────

    @Test
    fun `anchor — FPower target 50 floor 65 mW per frame matches PerfDog case studies`() {
        assertTrue(
            docContent.contains("< 50 mW/frame"),
            "doc anchor 'FPower < 50 mW/frame excellent' (§3.6) must remain present",
        )
        assertTrue(
            docContent.contains("> 65 mW/frame"),
            "doc anchor 'FPower > 65 mW/frame investigate' (§3.6) must remain present",
        )
        val fpower = KpiCatalog.byId(KpiId.FPOWER)
        DeviceTier.values().forEach { tier ->
            assertEquals(
                50.0,
                fpower.thresholds[tier]!!.target,
                "FPOWER target for $tier must match PerfDog §3.6 anchor 50 mW/frame (excellent ceiling)",
            )
            assertEquals(
                65.0,
                fpower.thresholds[tier]!!.floor,
                "FPOWER floor for $tier must match PerfDog §3.6 anchor 65 mW/frame (investigate threshold)",
            )
        }
    }
}
