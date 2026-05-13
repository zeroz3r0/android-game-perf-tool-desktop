package com.gameperf.desktop.core.devactions

import com.gameperf.desktop.core.events.DetectedEvent

/**
 * v4.5.0 — Detects the primary [GameEngine] from observed [DetectedEvent]s.
 *
 * Strategy (per design ADR-3):
 * 1. Scan [DetectedEvent.sdkSource] for engine names ("Unity Engine",
 *    "Unreal Engine", "Cocos2d") populated by `SdkSignatureCatalog`
 *    LOADING signatures (commit 7116786). Sprint 2 adds ZERO new
 *    SDK signatures.
 * 2. Count occurrences per engine across the events list.
 * 3. The engine with the highest count wins.
 * 4. On equal counts, the engine whose LATEST event has the greatest
 *    `startMs` wins (most-recent tie-break — list order is NOT used).
 * 5. When no engine events are present, returns [GameEngine.GENERIC].
 *
 * Mirrors the catalog-based strategy of the thermal classifier — no
 * heuristics on metric signatures, no I/O, no APK manifest sniff.
 *
 * Inputs come from `ConclusionInput.events`. Outputs feed
 * `CodeAreaCatalog.lookup(ruleId, engine)` inside `DevActionEngine.run`.
 *
 * Spec: DAB-005, DAB-006.
 * Design: `sdd/dev-action-brief/design` ADR-3.
 *
 * @since v4.5.0
 */
object GameEngineDetector {

    /**
     * Maps the `DetectedEvent.sdkSource` strings produced by
     * `SdkSignatureCatalog` LOADING signatures onto [GameEngine] variants.
     *
     * Adding a new engine = adding a row here AND a `SdkSignature` in
     * `SdkSignatureCatalog`. Sprint 2 ships the existing three.
     */
    private val SDK_SOURCE_TO_ENGINE: Map<String, GameEngine> = mapOf(
        "Unity Engine" to GameEngine.UNITY,
        "Unreal Engine" to GameEngine.UNREAL,
        "Cocos2d" to GameEngine.COCOS2D,
    )

    /**
     * Returns the dominant [GameEngine] observed in [events], or
     * [GameEngine.GENERIC] when no engine-tagged event is present.
     */
    fun detect(events: List<DetectedEvent>): GameEngine {
        if (events.isEmpty()) return GameEngine.GENERIC

        val counts = mutableMapOf<GameEngine, Int>()
        val latestStartMs = mutableMapOf<GameEngine, Long>()

        for (event in events) {
            val engine = SDK_SOURCE_TO_ENGINE[event.sdkSource] ?: continue
            counts[engine] = (counts[engine] ?: 0) + 1
            val previous = latestStartMs[engine]
            if (previous == null || event.startMs > previous) {
                latestStartMs[engine] = event.startMs
            }
        }

        if (counts.isEmpty()) return GameEngine.GENERIC

        val maxCount = counts.values.max()
        val tied = counts.filterValues { it == maxCount }.keys

        return if (tied.size == 1) {
            tied.first()
        } else {
            // ADR-3 tie-break: greatest startMs across the tied engines wins.
            tied.maxBy { engine -> latestStartMs[engine] ?: Long.MIN_VALUE }
        }
    }
}
