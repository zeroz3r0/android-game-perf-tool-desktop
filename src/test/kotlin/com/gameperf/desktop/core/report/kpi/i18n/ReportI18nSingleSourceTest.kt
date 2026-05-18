package com.gameperf.desktop.core.report.kpi.i18n

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Phase 1.5 — Architectural guard for inline castellano labels.
 *
 * Walks every `.kt` file under `src/main/kotlin/com/gameperf/desktop/core/report/`
 * and asserts the quoted-string literals belonging to `ReportStrings` do NOT
 * appear outside the canonical `core/report/kpi/i18n/` package.
 *
 * Mirrors `KpiBandColorsSingleSourceTest` discipline (CLAUDE.md anti-duplication).
 *
 * @since v4.7 (html-report-rag-bands — RAG-006)
 */
class ReportI18nSingleSourceTest {

    // Quoted-string matches for the canonical castellano labels — includes
    // historical accented variants as a safety net for paste-from-elsewhere.
    private val bannedLabels = Regex(
        """"(Bien|Atencion|Atenci\u00f3n|Mal|Presupuesto|Distribucion|Distribuci\u00f3n)""",
    )
    private val reportDir = File("src/main/kotlin/com/gameperf/desktop/core/report")

    @Test
    fun `no castellano label literal appears outside ReportStrings`() {
        assertTrue(reportDir.isDirectory, "expected report dir at ${reportDir.absolutePath}")
        val offenders = mutableListOf<String>()
        reportDir.walkTopDown()
            .filter {
                // Skip files inside the `i18n/` directory.
                it.isFile && it.extension == "kt" && !it.path.replace('\\', '/').contains("/i18n/")
            }
            .forEach { file ->
                val text = file.readText(Charsets.UTF_8)
                bannedLabels.findAll(text).forEach { match ->
                    offenders += "${file.name}: ${match.value}"
                }
            }
        assertTrue(
            offenders.isEmpty(),
            "Inline castellano labels found outside ReportStrings — reference " +
                "`ReportStrings.BAND_GREEN / BUDGET_60FPS / PHASE_DIST_TITLE / …` instead:\n  " +
                offenders.joinToString("\n  "),
        )
    }
}
