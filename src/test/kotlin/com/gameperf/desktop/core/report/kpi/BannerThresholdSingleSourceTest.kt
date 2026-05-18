package com.gameperf.desktop.core.report.kpi

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Architectural guard for `sdd/html-report-rag-bands` Phase 5 (closes
 * engram followup #460): `AndroidVitalsBanners.kt` MUST NOT contain
 * hardcoded numeric Vitals gates — every threshold MUST be read from
 * `KpiCatalog.byId(...).thresholds[MID].floor`.
 *
 * Greps the file-scoped source text for the three banned literals:
 *  - `1.09`  (crash rate users floor)
 *  - `0.47`  (ANR rate users floor)
 *  - `7_200_000` or `7200000` (wake locks ms gate)
 *
 * Allowed: matches inside KDoc comments (lines starting with `*` or `//`).
 * The guard ONLY fails when a literal appears inside production code paths,
 * matching the discipline the project established for
 * `FrameBudgetsSingleSourceTest` and `KpiBandColorsSingleSourceTest`.
 *
 * @since v4.7 (html-report-rag-bands — RAG-007, closes #460)
 */
class BannerThresholdSingleSourceTest {

    private val targetFile: File = File(
        "src/main/kotlin/com/gameperf/desktop/core/report/kpi/AndroidVitalsBanners.kt"
    )

    private val bannedRegex = Regex("""\b(1\.09|0\.47|7_?200_?000)\b""")

    @Test
    fun `AndroidVitalsBanners does not hardcode wake locks ms gate or rate floors`() {
        assertTrue(targetFile.exists(), "expected ${targetFile.path} to exist")
        val lines = targetFile.readLines(Charsets.UTF_8)
        val offenders = mutableListOf<String>()
        for ((idx, rawLine) in lines.withIndex()) {
            val trimmed = rawLine.trim()
            // Skip KDoc / single-line comment lines — they may legitimately cite
            // the values to document the source of truth.
            if (trimmed.startsWith("*") || trimmed.startsWith("//")) continue
            // Skip the package / import block — nothing to grep.
            if (trimmed.startsWith("package ") || trimmed.startsWith("import ")) continue
            val match = bannedRegex.find(rawLine) ?: continue
            offenders += "${idx + 1}: '${match.value}' in `${rawLine.trim()}`"
        }
        assertEquals(
            expected = emptyList<String>(),
            actual = offenders,
            message = "AndroidVitalsBanners.kt must consume `KpiCatalog.byId(...).thresholds[MID].floor`" +
                " for wake-locks / crash / ANR — no hardcoded literals.\nOffenders:\n" +
                offenders.joinToString("\n"),
        )
    }
}
