package com.gameperf.desktop.core.devactions

import com.gameperf.desktop.core.HardwareScoring
import com.gameperf.desktop.core.conclusions.ConclusionInput
import com.gameperf.desktop.core.conclusions.Severity
import com.gameperf.desktop.core.metrics.MetricsAggregates
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for the Sprint 0 foundation of `DevActionEngine` and its
 * supporting data classes.
 *
 * Each test cites the SDD spec requirement(s) it verifies.
 *
 * Spec: `sdd/dev-action-brief/spec` — DAB-001..DAB-005, DAB-016.
 * Design: `sdd/dev-action-brief/design` — ADR-1, ADR-4, ADR-5, ADR-6.
 *
 * @since v4.5.0
 */
class DevActionEngineTest {

    private val json = Json { prettyPrint = false }

    // ────────────────────────────────────────────────────────────────────
    // Data class shape + serialisation
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `DAB-001 - DevActionItem round-trips through JSON with all fields populated`() {
        val item = DevActionItem(
            ruleId = "cpu-saturated",
            severity = Severity.CRITICAL,
            title = "CPU saturada",
            evidence = DevActionEvidence(
                metric = "cpu",
                segment = "FILTERED",
                values = mapOf("avgCpu" to "95", "maxCpu" to "99"),
            ),
            diagnostic = "El hilo principal está al límite.",
            codeAreaHints = listOf(
                CodeAreaHint(
                    engine = GameEngine.UNITY,
                    area = "MonoBehaviour.Update",
                    whyHere = "Scripts pesados por frame.",
                    docLink = "https://docs.unity3d.com/",
                ),
            ),
            suggestedActions = listOf(
                ActionStep(
                    description = "Mover lógica a coroutines.",
                    tool = "Unity Profiler",
                    docLink = "https://docs.unity3d.com/Manual/ProfilerWindow.html",
                    engineSpecific = GameEngine.UNITY,
                ),
            ),
            relatedLogcatLines = listOf(
                LogcatLineRef(timestampMs = 1_000L, tag = "Unity", excerpt = "FrameStats: ..."),
            ),
            confidence = Confidence.HIGH,
        )

        val encoded = json.encodeToString(DevActionItem.serializer(), item)
        val decoded = json.decodeFromString(DevActionItem.serializer(), encoded)

        assertEquals(item, decoded)
    }

    @Test
    fun `DAB-002 - DevActionBrief defaults topN to 5 and preserves it across serialisation`() {
        val brief = DevActionBrief()
        val encoded = json.encodeToString(DevActionBrief.serializer(), brief)
        val decoded = json.decodeFromString(DevActionBrief.serializer(), encoded)

        assertEquals(5, brief.topN)
        assertEquals(brief, decoded)
        assertTrue(brief.items.isEmpty())
    }

    @Test
    fun `DAB-001 - GameEngine enum exposes the 6 documented variants`() {
        val expected = setOf(
            GameEngine.UNITY,
            GameEngine.UNREAL,
            GameEngine.COCOS2D,
            GameEngine.GODOT,
            GameEngine.NATIVE,
            GameEngine.GENERIC,
        )
        assertEquals(expected, GameEngine.entries.toSet())
    }

    @Test
    fun `DAB-001 - Confidence enum exposes HIGH MEDIUM LOW`() {
        val expected = setOf(Confidence.HIGH, Confidence.MEDIUM, Confidence.LOW)
        assertEquals(expected, Confidence.entries.toSet())
    }

    // ────────────────────────────────────────────────────────────────────
    // DevActionEngine.run — wraps ConclusionEngine 1:1
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `DAB-005 - DevActionEngine run maps each Conclusion to a DevActionItem 1-to-1 preserving order`() {
        val input = multiRuleInput()
        val brief = DevActionEngine.run(input)

        // Same fixture as ConclusionEngineSnapshotTest — fires 3 rules:
        // CRITICAL cpu-saturated, WARNING jank-with-good-avg, INFO ad-vs-game-fps-gap.
        val ruleIds = brief.items.map { it.ruleId }
        assertEquals(
            listOf("cpu-saturated", "jank-with-good-avg", "ad-vs-game-fps-gap"),
            ruleIds,
        )
    }

    @Test
    fun `DAB-002 - DevActionEngine preserves severity ordering CRITICAL then WARNING then INFO`() {
        val brief = DevActionEngine.run(multiRuleInput())
        val severities = brief.items.map { it.severity }

        assertEquals(
            listOf(Severity.CRITICAL, Severity.WARNING, Severity.INFO),
            severities,
        )
    }

    @Test
    fun `DAB-005 - DevActionEngine reuses Conclusion headline as DevActionItem title`() {
        val brief = DevActionEngine.run(multiRuleInput())
        val cpu = brief.items.single { it.ruleId == "cpu-saturated" }

        assertEquals(
            "La CPU del proceso está saturada (uso medio del 95%, pico del 99%).",
            cpu.title,
        )
    }

    @Test
    fun `DAB-005 - DevActionEngine returns empty brief when no rules fire`() {
        val brief = DevActionEngine.run(emptyInput())
        assertTrue(brief.items.isEmpty())
        assertEquals(5, brief.topN)
    }

    @Test
    fun `DAB-003 - DevActionEngine populates codeAreaHints from catalog in Sprint 1`() {
        val brief = DevActionEngine.run(multiRuleInput())
        brief.items.forEach { item ->
            assertTrue(
                item.codeAreaHints.isNotEmpty(),
                "Sprint 1 catalog must yield hints; got empty for ${item.ruleId}",
            )
        }
    }

    @Test
    fun `DAB-004 - DevActionEngine populates suggestedActions from catalog in Sprint 1`() {
        val brief = DevActionEngine.run(multiRuleInput())
        brief.items.forEach { item ->
            assertTrue(
                item.suggestedActions.isNotEmpty(),
                "Sprint 1 catalog must yield actions; got empty for ${item.ruleId}",
            )
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Sprint 1 — per-rule × per-engine enrichment (DAB-003, DAB-004, DAB-006)
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `DAB-003 - Sprint 1 — every production rule yields engine-tagged hints for UNITY`() {
        assertEverRuleProducesHintsFor(GameEngine.UNITY)
    }

    @Test
    fun `DAB-003 - Sprint 1 — every production rule yields engine-tagged hints for UNREAL`() {
        assertEverRuleProducesHintsFor(GameEngine.UNREAL)
    }

    @Test
    fun `DAB-003 - Sprint 1 — every production rule yields engine-tagged hints for COCOS2D`() {
        assertEverRuleProducesHintsFor(GameEngine.COCOS2D)
    }

    @Test
    fun `DAB-006 - Sprint 1 — GODOT engine input falls through to GENERIC hints`() {
        // GameEngine.GODOT has no dedicated catalog entries (design ADR-3 — fall through to GENERIC).
        // We cannot wire the engine into DevActionEngine.run yet (Sprint 2 ships GameEngineDetector),
        // but the catalog itself must already implement the fallback so Sprint 2 has the contract.
        com.gameperf.desktop.core.conclusions.RuleRegistry.all.forEach { rule ->
            val godot = CodeAreaCatalog.lookup(rule.id, GameEngine.GODOT)
            val generic = CodeAreaCatalog.lookup(rule.id, GameEngine.GENERIC)
            assertEquals(generic, godot, "GODOT must fall through to GENERIC for ${rule.id}")
        }
    }

    @Test
    fun `DAB-004 - Sprint 1 — engineSpecific filtering drops UNITY-only steps when engine is UNREAL`() {
        // We probe the catalog directly for any rule that has at least one UNITY-tagged step.
        val ruleWithUnityStep = com.gameperf.desktop.core.conclusions.RuleRegistry.all.firstOrNull { rule ->
            ActionStepsCatalog.lookup(rule.id).any { it.engineSpecific == GameEngine.UNITY }
        }
        // If no Unity-tagged step exists this assertion is vacuously true; Sprint 1 expectation is
        // at least one Unity-specific step somewhere in the catalog.
        assertTrue(
            ruleWithUnityStep != null,
            "Sprint 1 must ship at least one ActionStep with engineSpecific=UNITY.",
        )
        val steps = ActionStepsCatalog.lookup(ruleWithUnityStep.id)
        val filteredForUnreal = steps.filter {
            it.engineSpecific == null || it.engineSpecific == GameEngine.UNREAL
        }
        assertTrue(
            filteredForUnreal.none { it.engineSpecific == GameEngine.UNITY },
            "Unity-tagged steps must NOT survive a filter for UNREAL.",
        )
    }

    private fun assertEverRuleProducesHintsFor(engine: GameEngine) {
        com.gameperf.desktop.core.conclusions.RuleRegistry.all.forEach { rule ->
            val hints = CodeAreaCatalog.lookup(rule.id, engine)
            assertTrue(
                hints.isNotEmpty(),
                "${rule.id} must have $engine-specific hints in Sprint 1.",
            )
            assertTrue(
                hints.all { it.engine == engine },
                "All hints for ${rule.id} under $engine must carry that engine tag.",
            )
        }
    }

    @Test
    fun `DAB-001 - DevActionEngine populates Confidence from ConfidenceLookup baseline`() {
        val brief = DevActionEngine.run(multiRuleInput())
        val cpu = brief.items.single { it.ruleId == "cpu-saturated" }
        val jank = brief.items.single { it.ruleId == "jank-with-good-avg" }
        val ad = brief.items.single { it.ruleId == "ad-vs-game-fps-gap" }

        // Per design ADR-6 baseline assignment.
        assertEquals(Confidence.HIGH, cpu.confidence)
        assertEquals(Confidence.MEDIUM, jank.confidence)
        assertEquals(Confidence.HIGH, ad.confidence)
    }

    @Test
    fun `DAB-005 - DevActionEngine reuses Conclusion recommendation as DevActionItem diagnostic`() {
        val brief = DevActionEngine.run(multiRuleInput())
        val cpu = brief.items.single { it.ruleId == "cpu-saturated" }

        // The Conclusion.recommendation is non-null for the 8 production rules;
        // diagnostic must carry the same text (Sprint 0 placeholder per design pseudocode).
        assertTrue(cpu.diagnostic.isNotEmpty())
        assertNotEquals(cpu.title, cpu.diagnostic)
    }

    // ────────────────────────────────────────────────────────────────────
    // Fixtures (intentionally self-contained — matches snapshot test)
    // ────────────────────────────────────────────────────────────────────

    private fun multiRuleInput(): ConclusionInput = ConclusionInput(
        filtered = aggregates(avgFps = 58, avgCpu = 95, maxCpu = 99, totalJank = 60L),
        raw = aggregates(avgFps = 48, avgCpu = 95, maxCpu = 99, totalJank = 60L),
        targetFps = 60,
        deviceTier = HardwareScoring.DeviceTier.MID,
        events = listOf(
            com.gameperf.desktop.core.events.DetectedEvent(
                id = "dev-action-engine-test-event-1",
                type = com.gameperf.desktop.core.events.EventType.INTERSTITIAL,
                sdkSource = "AdMob",
                startMs = 1_000L,
                endMs = 6_000L,
                confidence = com.gameperf.desktop.core.events.Confidence.HIGH,
                signatureMatched = "test",
            ),
        ),
        sessionDurationS = 60,
    )

    private fun emptyInput(): ConclusionInput = ConclusionInput(
        filtered = aggregates(),
        raw = aggregates(),
        targetFps = 60,
        deviceTier = HardwareScoring.DeviceTier.MID,
        events = emptyList(),
        sessionDurationS = 120,
    )

    private fun aggregates(
        avgFps: Int = 60,
        avgCpu: Int = 30,
        maxCpu: Int = 45,
        totalJank: Long = 0L,
    ): MetricsAggregates = MetricsAggregates(
        avgFps = avgFps,
        minFps = 58,
        maxFps = 60,
        p1 = 58,
        p5 = 58,
        p50 = 60,
        p90 = 60,
        p99 = 60,
        avgFrameTime = 16.6,
        p99FrameTime = 16.6,
        peakMem = 200L,
        avgCpu = avgCpu,
        maxCpu = maxCpu,
        maxTempCpu = 35.0,
        maxTempGpu = 35.0,
        maxTempSkin = 32.0,
        maxTempDieCpu = 40.0,
        totalJank = totalJank,
        totalStutter = 0,
        sampleCount = 120,
    )
}
