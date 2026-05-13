package com.gameperf.desktop.core.report.kpi

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * T6.2 — Anti-duplication grep guard for KPI band colors.
 *
 * Walks every `.kt` file under `src/main/kotlin/com/gameperf/desktop/core/report/kpi/`
 * and asserts that NO band-hex literal appears outside [KpiBandColors].
 *
 * The banned hex values are the canonical band foreground colors plus their
 * historical alternates:
 *  - `#10b981` / `#22c55e` (green family)
 *  - `#f59e0b` / `#d97706` (amber family)
 *  - `#ef4444` / `#dc2626` (red family)
 *  - `#f97316` (orange — appears in other parts of the report; banned here
 *    to avoid accidental cross-package drift)
 *
 * If this test fails, refactor the offending file to call
 * `KpiBandColors.forBand(Band.X)` or `KpiBandColors.cssClassFor(Band.X)`
 * rather than hardcoding the hex value.
 *
 * Mirrors the anti-duplication discipline from
 *  - v4.2.13 (`ToolResolver` consolidation across `AdbBridge` + `IosBridge`)
 *  - v4.4.0 (`SdkSignatureCatalog.ALL` as single source for SDK signatures)
 * as documented in `CLAUDE.md`.
 *
 * @since v4.6 (shareable-html-report Block F)
 */
class KpiBandColorsSingleSourceTest {

    private val bannedHex = Regex("#(10b981|f59e0b|ef4444|22c55e|d97706|dc2626|f97316)", RegexOption.IGNORE_CASE)
    private val kpiSourceDir = File("src/main/kotlin/com/gameperf/desktop/core/report/kpi")
    private val allowedFile = "KpiBandColors.kt"

    @Test
    fun `no banned band hex appears outside KpiBandColors`() {
        assertTrue(kpiSourceDir.isDirectory, "expected kpi source dir at ${kpiSourceDir.absolutePath}")
        val offenders = mutableListOf<String>()
        kpiSourceDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != allowedFile }
            .forEach { file ->
                // Read with explicit UTF-8 — CLAUDE.md v4.2.4 mojibake lesson.
                val text = file.readText(Charsets.UTF_8)
                bannedHex.findAll(text).forEach { match ->
                    offenders += "${file.name}: ${match.value}"
                }
            }
        assertTrue(
            offenders.isEmpty(),
            "Banned band hex literals found outside KpiBandColors.kt — refactor via " +
                "KpiBandColors.forBand(Band.X) / cssClassFor(Band.X):\n  " +
                offenders.joinToString("\n  "),
        )
    }
}
