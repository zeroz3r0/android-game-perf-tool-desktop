package com.gameperf.desktop.core.kpi

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Phase 1.4 — Architectural guard for frame-time budget literals.
 *
 * Walks every `.kt` file under `src/main/kotlin/com/gameperf/desktop/` and
 * asserts that the literals `16.6`, `33.3`, `8.3` do NOT appear outside the
 * single source of truth (`FrameBudgets.kt`).
 *
 * Allow-list:
 *   - `FrameBudgets.kt` (canonical definition)
 *   - `KpiCatalog.kt` (`FRAME_TIME_P99` Vitals threshold rows legitimately
 *     reuse `16.6` and `33.3` as per-tier `target` / `floor` — these are
 *     scoring anchors, conceptually distinct from the frame-time budget
 *     overlay lines this test protects.)
 *
 * Mirrors `KpiBandColorsSingleSourceTest` discipline (CLAUDE.md v4.2.13
 * + v4.4.0 anti-duplication lesson).
 *
 * @since v4.7 (html-report-rag-bands — RAG-005)
 */
class FrameBudgetsSingleSourceTest {

    // Word-boundary on both sides so `16.66` and `133.3` are NOT matched.
    private val bannedBudget = Regex("""\b(16\.6|33\.3|8\.3)\b""")
    private val srcDir = File("src/main/kotlin/com/gameperf/desktop")

    // Allow-list:
    //   - FrameBudgets.kt: canonical definition.
    //   - KpiCatalog.kt: `FRAME_TIME_P99` Vitals thresholds legitimately reuse
    //     16.6 / 33.3 as scoring anchors — conceptually distinct from the
    //     frame-budget overlay lines this guard protects.
    private val allowedFiles = setOf("FrameBudgets.kt", "KpiCatalog.kt")

    @Test
    fun `no frame budget literal appears outside FrameBudgets`() {
        assertTrue(srcDir.isDirectory, "expected src dir at ${srcDir.absolutePath}")
        val offenders = mutableListOf<String>()
        srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name !in allowedFiles }
            .forEach { file ->
                // Read with explicit UTF-8 — CLAUDE.md v4.2.4 mojibake lesson.
                val lines = file.readText(Charsets.UTF_8).lineSequence()
                lines.forEachIndexed { idx, raw ->
                    // Skip pure KDoc / comment lines — they document the value, not use it.
                    val trimmed = raw.trimStart()
                    if (trimmed.startsWith("*") || trimmed.startsWith("//")) return@forEachIndexed
                    bannedBudget.findAll(raw).forEach { match ->
                        offenders += "${file.name}:${idx + 1}: ${match.value}"
                    }
                }
            }
        assertTrue(
            offenders.isEmpty(),
            "Banned frame-budget literals found outside FrameBudgets.kt — " +
                "reference `FrameBudgets.FPS_60_MS / FPS_30_MS / FPS_120_MS` instead:\n  " +
                offenders.joinToString("\n  "),
        )
    }
}
