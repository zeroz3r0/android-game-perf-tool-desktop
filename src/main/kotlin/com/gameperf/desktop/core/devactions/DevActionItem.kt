package com.gameperf.desktop.core.devactions

import com.gameperf.desktop.core.conclusions.Severity
import kotlinx.serialization.Serializable

/**
 * Detected primary game engine (or runtime) of the session under analysis.
 *
 * Used to index per-engine entries of [CodeAreaCatalog] and to filter
 * [ActionStep.engineSpecific] suggestions. Engine is derived from already-
 * captured `DetectedEvent.sdkSource` values by `GameEngineDetector`
 * (introduced in Sprint 2 — Sprint 0 placeholder is always [GENERIC]).
 *
 * Design: `sdd/dev-action-brief/design` ADR-3.
 *
 * @property UNITY Unity Engine.
 * @property UNREAL Unreal Engine.
 * @property COCOS2D Cocos2d / Cocos2d-x.
 * @property GODOT Godot.
 * @property NATIVE Native Android / NDK (no high-level game engine).
 * @property GENERIC Unknown or undetected — fallback bucket.
 */
@Serializable
enum class GameEngine {
    UNITY,
    UNREAL,
    COCOS2D,
    GODOT,
    NATIVE,
    GENERIC,
}

/**
 * Heuristic confidence in a [DevActionItem]'s actionability.
 *
 * Hand-set per-rule in [ConfidenceLookup]. See design ADR-6 for the
 * per-rule baseline rationale.
 *
 * @property HIGH Almost certainly the bottleneck (e.g. cpu-saturated at 95% avg).
 * @property MEDIUM Likely but not exclusive (e.g. memory-leak-suspect on short session).
 * @property LOW Plausible alternative explanation exists (e.g. fps-cap-suspect at p99≈30).
 */
@Serializable
enum class Confidence {
    HIGH,
    MEDIUM,
    LOW,
}

/**
 * Structured evidence backing a [DevActionItem].
 *
 * Renderer projects this into a `<dl>` element with metric label / value
 * pairs plus a segment chip (RAW / FILTERED / EVENT_WINDOW).
 *
 * @property metric Top-level metric domain: `fps`, `cpu`, `memory`, `thermal`, `events`.
 * @property segment Aggregation segment: `RAW`, `FILTERED`, or `EVENT_WINDOW`.
 * @property values Free-form key→value map projected into the renderer's `<dl>`.
 *           Keys are stable (`p50`, `avgCpu`, `maxTempCpu`, …) — the renderer
 *           applies localised labels.
 */
@Serializable
data class DevActionEvidence(
    val metric: String,
    val segment: String,
    val values: Map<String, String> = emptyMap(),
)

/**
 * Per-engine code-area hint — "look here in the codebase".
 *
 * Populated from [CodeAreaCatalog] in Sprint 1; Sprint 0 always empty.
 *
 * @property engine Engine this hint applies to. The renderer shows the matching
 *           engine's hint set first, with [GameEngine.GENERIC] as fallback.
 * @property area Symbolic location (e.g. "MonoBehaviour.Update / LateUpdate del hilo principal").
 * @property whyHere One-sentence rationale linking the rule to the area (Spanish tuteo-formal).
 * @property docLink Optional first-party documentation URL.
 */
@Serializable
data class CodeAreaHint(
    val engine: GameEngine,
    val area: String,
    val whyHere: String,
    val docLink: String? = null,
)

/**
 * Concrete actionable step the developer can take.
 *
 * Populated from [ActionStepsCatalog] in Sprint 1; Sprint 0 always empty.
 *
 * @property description Action description (Spanish tuteo-formal).
 * @property tool Optional named tool (e.g. "Unity Memory Profiler").
 * @property docLink Optional first-party documentation URL.
 * @property engineSpecific If non-null, the step only applies to the named engine.
 *           `null` means "applies to all engines".
 */
@Serializable
data class ActionStep(
    val description: String,
    val tool: String? = null,
    val docLink: String? = null,
    val engineSpecific: GameEngine? = null,
)

/**
 * Reference to a logcat line considered relevant evidence.
 *
 * Reserved for the future `logcat-event-stream M.x` change (spec DAB-014).
 * Always empty in v4.5.x — the field exists so renderer + persistence are
 * already DAB-014-ready when the upstream pipeline ships.
 *
 * @property timestampMs Wall-clock timestamp relative to capture start (ms).
 * @property tag Logcat tag (e.g. "Unity", "Choreographer").
 * @property excerpt The line content (HTML-escaped at render time).
 */
@Serializable
data class LogcatLineRef(
    val timestampMs: Long,
    val tag: String,
    val excerpt: String,
)

/**
 * Developer-actionable enrichment of a single [com.gameperf.desktop.core.conclusions.Conclusion].
 *
 * One-to-one with the underlying Conclusion ([ruleId] is the join key). The
 * 1:1 mapping is locked by `DevActionEngineTest` and verified by every
 * snapshot run — see spec DAB-005.
 *
 * @property ruleId Stable rule identifier — equal to the underlying `Conclusion.ruleId`.
 * @property severity Reuses the existing 3-tier `Severity` enum (design ADR-5).
 * @property title One-line headline (mirrors `Conclusion.headline` — design pseudocode).
 * @property evidence Structured evidence backing the diagnostic.
 * @property diagnostic Root-cause hypothesis (Sprint 0: reuses `Conclusion.recommendation`).
 * @property codeAreaHints Per-engine code-area hints (Sprint 1 fills; empty in Sprint 0).
 * @property suggestedActions Concrete actionable steps (Sprint 1 fills; empty in Sprint 0).
 * @property relatedLogcatLines Reserved for DAB-014 — empty in v4.5.x.
 * @property confidence Per-rule baseline confidence (design ADR-6).
 */
@Serializable
data class DevActionItem(
    val ruleId: String,
    val severity: Severity,
    val title: String,
    val evidence: DevActionEvidence,
    val diagnostic: String,
    val codeAreaHints: List<CodeAreaHint>,
    val suggestedActions: List<ActionStep>,
    val relatedLogcatLines: List<LogcatLineRef> = emptyList(),
    val confidence: Confidence,
)

/**
 * The dev-action-brief artefact persisted on `SessionResult` and rendered
 * at the top of the report.
 *
 * @property items Ordered list of [DevActionItem] — same order as
 *           `ConclusionEngine.run` output (severity DESC then ruleId ASC).
 * @property topN Number of items visible by default — the renderer hides
 *           anything beyond this behind a "Mostrar todo" toggle.
 *           Default 5 (design ADR-4).
 */
@Serializable
data class DevActionBrief(
    val items: List<DevActionItem> = emptyList(),
    val topN: Int = DEFAULT_TOP_N,
) {
    companion object {
        /** Default number of items visible before the JS toggle hides the rest (design ADR-4). */
        const val DEFAULT_TOP_N: Int = 5
    }
}
