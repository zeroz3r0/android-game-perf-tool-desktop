package com.gameperf.desktop.core.devactions

import com.gameperf.desktop.core.conclusions.RuleRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Sprint 1 tests for [ActionStepsCatalog].
 *
 * Spec: `sdd/dev-action-brief/spec` — DAB-004 (action steps),
 * DAB-009 (Spanish tuteo-formal copy).
 * Design: `sdd/dev-action-brief/design` — ADR-2 (static catalog),
 * ADR-5 (doc-link pattern), ADR-4 (engineSpecific filtering).
 *
 * @since v4.5.0
 */
class ActionStepsCatalogTest {

    private val ruleIds: List<String> = RuleRegistry.all.map { it.id }

    // ────────────────────────────────────────────────────────────────────
    // Completeness — DAB-004
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `DAB-004 - every production rule has 1 to 5 action steps`() {
        ruleIds.forEach { ruleId ->
            val steps = ActionStepsCatalog.lookup(ruleId)
            assertTrue(
                steps.size in 1..5,
                "Expected 1..5 steps for ruleId=$ruleId; got ${steps.size}.",
            )
            steps.forEach { step ->
                assertTrue(
                    step.description.isNotBlank(),
                    "Step description must not be blank for ruleId=$ruleId.",
                )
            }
        }
    }

    @Test
    fun `unknown ruleId returns empty list`() {
        assertEquals(emptyList(), ActionStepsCatalog.lookup("not-a-real-rule"))
    }

    // ────────────────────────────────────────────────────────────────────
    // Engine-specific coverage — every rule has at least one step per core engine
    // (either via engineSpecific tag or a null engineSpecific step that applies to all)
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `every rule has at least one Unity-applicable step`() {
        assertAtLeastOneStepFor(GameEngine.UNITY)
    }

    @Test
    fun `every rule has at least one Unreal-applicable step`() {
        assertAtLeastOneStepFor(GameEngine.UNREAL)
    }

    @Test
    fun `every rule has at least one Cocos2d-applicable step`() {
        assertAtLeastOneStepFor(GameEngine.COCOS2D)
    }

    @Test
    fun `every rule has at least one generic-applicable step`() {
        ruleIds.forEach { ruleId ->
            val steps = ActionStepsCatalog.lookup(ruleId)
            val applicable = steps.filter { it.engineSpecific == null }
            assertTrue(
                applicable.isNotEmpty(),
                "Rule $ruleId must have at least one engine-agnostic step (engineSpecific=null).",
            )
        }
    }

    private fun assertAtLeastOneStepFor(engine: GameEngine) {
        ruleIds.forEach { ruleId ->
            val steps = ActionStepsCatalog.lookup(ruleId)
            val applicable = steps.filter { it.engineSpecific == null || it.engineSpecific == engine }
            assertTrue(
                applicable.isNotEmpty(),
                "Rule $ruleId has no step applicable to $engine.",
            )
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Spanish tuteo-formal linter — DAB-009
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `DAB-009 - no usted or vosotros in step descriptions`() {
        val forbidden = Regex("""\b(usted|vosotros|vuestro|vuestra)\b""", RegexOption.IGNORE_CASE)
        ruleIds.forEach { ruleId ->
            ActionStepsCatalog.lookup(ruleId).forEach { step ->
                assertFalse(
                    forbidden.containsMatchIn(step.description),
                    "Forbidden Spanish form in $ruleId step: ${step.description}",
                )
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Doc-link validation — well-formed https first-party URLs
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `every non-null docLink is a well-formed https URL`() {
        val urlPattern = Regex("""^https://[a-z0-9.\-]+\.[a-z]{2,}(/.*)?$""", RegexOption.IGNORE_CASE)
        ruleIds.forEach { ruleId ->
            ActionStepsCatalog.lookup(ruleId).forEach steps@{ step ->
                val link = step.docLink ?: return@steps
                assertTrue(
                    urlPattern.matches(link),
                    "Malformed docLink for $ruleId: $link",
                )
            }
        }
    }
}
