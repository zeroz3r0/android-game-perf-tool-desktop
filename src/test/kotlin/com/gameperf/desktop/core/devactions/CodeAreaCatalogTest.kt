package com.gameperf.desktop.core.devactions

import com.gameperf.desktop.core.conclusions.RuleRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Sprint 1 tests for [CodeAreaCatalog].
 *
 * Spec: `sdd/dev-action-brief/spec` — DAB-003 (catalog completeness),
 * DAB-006 (engine fallback), DAB-009 (Spanish tuteo-formal).
 * Design: `sdd/dev-action-brief/design` — ADR-2 (static catalog),
 * ADR-3 (GODOT/NATIVE fall through to GENERIC).
 *
 * @since v4.5.0
 */
class CodeAreaCatalogTest {

    private val coreEngines = listOf(
        GameEngine.UNITY,
        GameEngine.UNREAL,
        GameEngine.COCOS2D,
        GameEngine.GENERIC,
    )

    private val ruleIds: List<String> = RuleRegistry.all.map { it.id }

    // ────────────────────────────────────────────────────────────────────
    // Completeness — DAB-003
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `DAB-003 - every production rule has non-empty hints under UNITY`() {
        assertCompleteness(GameEngine.UNITY)
    }

    @Test
    fun `DAB-003 - every production rule has non-empty hints under UNREAL`() {
        assertCompleteness(GameEngine.UNREAL)
    }

    @Test
    fun `DAB-003 - every production rule has non-empty hints under COCOS2D`() {
        assertCompleteness(GameEngine.COCOS2D)
    }

    @Test
    fun `DAB-003 - every production rule has non-empty hints under GENERIC`() {
        assertCompleteness(GameEngine.GENERIC)
    }

    private fun assertCompleteness(engine: GameEngine) {
        ruleIds.forEach { ruleId ->
            val hints = CodeAreaCatalog.lookup(ruleId, engine)
            assertTrue(
                hints.isNotEmpty(),
                "CodeAreaCatalog.lookup(\"$ruleId\", $engine) returned empty — Sprint 1 must fill it.",
            )
            assertTrue(
                hints.size in 1..3,
                "Expected 1..3 hints for ($ruleId, $engine); got ${hints.size}.",
            )
            hints.forEach { hint ->
                assertTrue(
                    hint.area.isNotBlank(),
                    "Hint area must not be blank for ($ruleId, $engine).",
                )
                assertTrue(
                    hint.whyHere.isNotBlank(),
                    "Hint whyHere must not be blank for ($ruleId, $engine).",
                )
                assertEquals(
                    engine,
                    hint.engine,
                    "Hint engine must equal the lookup key for ($ruleId, $engine).",
                )
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // GODOT / NATIVE fallback to GENERIC — DAB-006, design ADR-3
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `DAB-006 - GODOT engine falls back to GENERIC hints`() {
        ruleIds.forEach { ruleId ->
            val godot = CodeAreaCatalog.lookup(ruleId, GameEngine.GODOT)
            val generic = CodeAreaCatalog.lookup(ruleId, GameEngine.GENERIC)
            assertEquals(
                generic,
                godot,
                "GODOT lookup must mirror GENERIC for ruleId=$ruleId.",
            )
        }
    }

    @Test
    fun `DAB-006 - NATIVE engine falls back to GENERIC hints`() {
        ruleIds.forEach { ruleId ->
            val native = CodeAreaCatalog.lookup(ruleId, GameEngine.NATIVE)
            val generic = CodeAreaCatalog.lookup(ruleId, GameEngine.GENERIC)
            assertEquals(
                generic,
                native,
                "NATIVE lookup must mirror GENERIC for ruleId=$ruleId.",
            )
        }
    }

    @Test
    fun `unknown ruleId returns empty list`() {
        assertEquals(
            emptyList(),
            CodeAreaCatalog.lookup("not-a-real-rule", GameEngine.UNITY),
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // Spanish tuteo-formal linter — DAB-009
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `DAB-009 - no usted or vosotros in hint whyHere copy`() {
        val forbidden = Regex("""\b(usted|vosotros|vuestro|vuestra)\b""", RegexOption.IGNORE_CASE)
        coreEngines.forEach { engine ->
            ruleIds.forEach { ruleId ->
                CodeAreaCatalog.lookup(ruleId, engine).forEach { hint ->
                    assertFalse(
                        forbidden.containsMatchIn(hint.whyHere),
                        "Forbidden Spanish form in ($ruleId, $engine) whyHere: ${hint.whyHere}",
                    )
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Doc-link validation — DAB-004 (well-formed first-party https URLs)
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `every non-null docLink is a well-formed https URL`() {
        val urlPattern = Regex("""^https://[a-z0-9.\-]+\.[a-z]{2,}(/.*)?$""", RegexOption.IGNORE_CASE)
        coreEngines.forEach { engine ->
            ruleIds.forEach { ruleId ->
                CodeAreaCatalog.lookup(ruleId, engine).forEach hints@{ hint ->
                    val link = hint.docLink ?: return@hints
                    assertTrue(
                        urlPattern.matches(link),
                        "Malformed docLink in ($ruleId, $engine): $link",
                    )
                }
            }
        }
    }
}
