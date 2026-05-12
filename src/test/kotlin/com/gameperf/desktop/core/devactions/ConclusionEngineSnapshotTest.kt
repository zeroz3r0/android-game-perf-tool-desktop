package com.gameperf.desktop.core.devactions

import com.gameperf.desktop.core.HardwareScoring
import com.gameperf.desktop.core.conclusions.Conclusion
import com.gameperf.desktop.core.conclusions.ConclusionEngine
import com.gameperf.desktop.core.conclusions.ConclusionInput
import com.gameperf.desktop.core.conclusions.Severity
import com.gameperf.desktop.core.metrics.MetricsAggregates
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * DAB-016 invariant — locks the byte-equivalent output of
 * [ConclusionEngine.run] for a representative `ConclusionInput` fixture.
 *
 * The whole `dev-action-brief` change is gated on this snapshot
 * remaining byte-identical from Sprint 0 entry through Sprint 3 exit
 * (and forever after). Any diff here is a regression in `ConclusionEngine`
 * itself, NOT a `DevActionEngine` issue.
 *
 * Fixture is hand-crafted (no fixtures-module reuse) so the test owns its
 * input verbatim — same bytes in, same bytes out, across runs.
 *
 * See `sdd/dev-action-brief/spec` DAB-016 + design ADR-1.
 *
 * @since v4.5.0
 */
class ConclusionEngineSnapshotTest {

    @Test
    fun `DAB-016 - ConclusionEngine output is byte-identical for a multi-rule fixture`() {
        val input = multiRuleSnapshotInput()
        val actual = ConclusionEngine.run(input)

        // Expected snapshot — hand-derived from the rule predicates over
        // the fixture below. CRITICAL: cpu-saturated. WARNING: jank-with-good-avg.
        // INFO: ad-vs-game-fps-gap. Sort = (severity ordinal asc, ruleId asc).
        val expected = listOf(
            Conclusion(
                ruleId = "cpu-saturated",
                severity = Severity.CRITICAL,
                headline = "La CPU del proceso está saturada (uso medio del 95%, pico del 99%).",
                recommendation = "El juego está pidiendo más CPU de la que el dispositivo puede dar. " +
                    "Esto causa caídas de FPS impredecibles y reduce la batería rápido. Revisa con el " +
                    "desarrollador la lógica del hilo principal: scripts pesados por frame, físicas " +
                    "complejas, o cálculos que deberían moverse a hilos secundarios o coroutines.",
            ),
            Conclusion(
                ruleId = "jank-with-good-avg",
                severity = Severity.WARNING,
                headline = "Aunque la media de FPS es buena (58), el juego presenta tirones frecuentes " +
                    "(60 eventos de jank por minuto).",
                recommendation = "La media oculta picos de mal rendimiento. Estos tirones se notan más " +
                    "que un FPS bajo estable. Suelen deberse a carga de assets en runtime, GC pauses, " +
                    "o frames pesados puntuales (efectos, físicas masivas). Revisa con el desarrollador " +
                    "el frame time histogram y los hot frames.",
            ),
            Conclusion(
                ruleId = "ad-vs-game-fps-gap",
                severity = Severity.INFO,
                headline = "Los 1 eventos detectados (anuncios, IAP, cargas) sesgan la media " +
                    "de FPS: bruta 48, filtrada 58 (21%).",
                recommendation = "La métrica filtrada refleja el rendimiento real del juego, sin la " +
                    "contribución de las pantallas de anuncios o IAP. Úsala como referencia principal " +
                    "y consulta la métrica bruta solo para auditar.",
            ),
        )

        assertEquals(expected, actual)
    }

    @Test
    fun `DAB-016 - empty session produces empty list (deterministic invariant)`() {
        val input = emptySnapshotInput()
        val actual = ConclusionEngine.run(input)
        assertEquals(emptyList(), actual)
    }

    // ────────────────────────────────────────────────────────────────────
    // Fixtures (owned by this test — no dependency on ConclusionTestFixtures
    // so the snapshot is fully self-contained and reproducible).
    // ────────────────────────────────────────────────────────────────────

    private fun multiRuleSnapshotInput(): ConclusionInput {
        // Build aggregates that trigger 3 rules simultaneously:
        //  - CpuSaturationRule (CRITICAL): avgCpu = 95
        //  - JankWithGoodAvgRule (WARNING): avgFps >= 50 + jank/min >= 30
        //  - AdVsGameFpsGapRule (INFO): events present + filtered/raw delta >= 15%
        val filtered = aggregates(
            avgFps = 58,
            avgCpu = 95,
            maxCpu = 99,
            totalJank = 60L,
        )
        val raw = aggregates(
            avgFps = 48,
            avgCpu = 95,
            maxCpu = 99,
            totalJank = 60L,
        )
        return ConclusionInput(
            filtered = filtered,
            raw = raw,
            targetFps = 60,
            deviceTier = HardwareScoring.DeviceTier.MID,
            events = listOf(snapshotEvent()),
            sessionDurationS = 60,
        )
    }

    private fun emptySnapshotInput(): ConclusionInput = ConclusionInput(
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

    private fun snapshotEvent(): com.gameperf.desktop.core.events.DetectedEvent =
        com.gameperf.desktop.core.events.DetectedEvent(
            id = "snapshot-event-001",
            type = com.gameperf.desktop.core.events.EventType.INTERSTITIAL,
            sdkSource = "AdMob",
            startMs = 1_000L,
            endMs = 6_000L,
            confidence = com.gameperf.desktop.core.events.Confidence.HIGH,
            signatureMatched = "snapshot",
        )
}
