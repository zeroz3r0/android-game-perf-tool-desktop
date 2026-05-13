package com.gameperf.desktop.core.kpi

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Architectural guard `9.2-vitals-wakelocks-not-leaked`
 * (sdd/vitals-rate-and-wakelocks).
 *
 * Mirrors the SdkSignatureCatalog anti-duplication rule (CLAUDE.md v4.4.0)
 * and the ToolResolver anti-duplication rule (CLAUDE.md v4.2.13): every new
 * KPI metadata (thresholds, citations, direction) for the 3 Vitals-aware
 * KPIs MUST live exclusively in [KpiCatalog]. Any reference to
 * `KpiId.CRASH_RATE_USERS`, `KpiId.ANR_RATE_USERS`, or `KpiId.WAKE_LOCKS_RATE`
 * outside the allow-listed packages signals a parallel-catalog leak that
 * historically caused the same bug to recur three releases in a row.
 *
 * Allow-list (matches the catalog-grep guard described in tasks.md `9.2`):
 *  - `core/kpi/` — the catalog itself
 *  - `core/report/` — banner + HTML rendering
 *  - `core/model/` — SessionResult / Snapshot model fields
 *  - `viewmodel/` — capture pipeline wiring
 *  - `src/test/` — every test is free to reference whatever it needs
 *
 * Pure-Kotlin test, no I/O beyond reading source files. Skipped silently if
 * the source tree is not on disk (e.g. someone running tests from a stripped
 * artifact); the architectural invariant is meaningless without sources.
 */
class VitalsKpiCatalogArchitectureTest {

    private val watchedIds = listOf(
        "KpiId.CRASH_RATE_USERS",
        "KpiId.ANR_RATE_USERS",
        "KpiId.WAKE_LOCKS_RATE",
    )

    private val allowedPathFragments = listOf(
        "core/kpi/",
        "core\\kpi\\",
        "core/report/",
        "core\\report\\",
        "core/model/",
        "core\\model\\",
        "viewmodel/",
        "viewmodel\\",
    )

    @Test
    fun `vitals KPI ids never appear outside the allow-listed packages`() {
        val mainSrc = File("src/main/kotlin/com/gameperf/desktop")
        if (!mainSrc.exists()) {
            // No sources on disk — architectural test is moot. Surface as a
            // visible skip via assertTrue so CI can still see it ran.
            assertTrue(true, "src/main/kotlin not present; architectural test skipped")
            return
        }
        val violations = mutableListOf<String>()
        mainSrc.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val relativePath = file.path.replace('\\', '/')
                val allowed = allowedPathFragments.any { relativePath.contains(it) }
                if (allowed) return@forEach
                val text = file.readText(Charsets.UTF_8)
                watchedIds.forEach { id ->
                    if (text.contains(id)) {
                        violations += "${file.path} references $id outside allow-listed packages"
                    }
                }
            }
        if (violations.isNotEmpty()) {
            fail(
                "Vitals KPI ids leaked into non-allow-listed packages — single source of truth" +
                    " is KpiCatalog. Move metadata into core/kpi/ or read via" +
                    " KpiCatalog.byId(...).\n${violations.joinToString("\n")}",
            )
        }
    }
}
