package com.gameperf.desktop.core.conclusions

import com.gameperf.desktop.core.HardwareScoring
import com.gameperf.desktop.core.events.DetectedEvent
import com.gameperf.desktop.core.metrics.MetricsAggregates
import com.gameperf.desktop.viewmodel.TimedSample
import kotlinx.serialization.Serializable

/**
 * Severity levels for heuristic conclusions.
 *
 * Used for sorting: CRITICAL > WARNING > INFO.
 *
 * @property CRITICAL Severe issue that significantly impacts the player experience.
 * @property WARNING Moderate issue that should be investigated.
 * @property INFO Informational observation, not necessarily a problem.
 */
@Serializable
enum class Severity {
    CRITICAL,
    WARNING,
    INFO,
}

/**
 * A heuristic conclusion produced by a [Rule].
 *
 * Rendered in the report's `#sec-conclusions` section with a severity icon,
 * headline, and optional actionable recommendation.
 *
 * @property ruleId Stable identifier for the rule (e.g., "stable-low-fps-low-cpu").
 * @property severity Severity level for ordering.
 * @property headline One-sentence description in Castilian Spanish (formal tuteo).
 * @property recommendation Optional actionable advice (also Castilian Spanish, tuteo).
 *
 * @since v4.4.0
 */
@Serializable
data class Conclusion(
    val ruleId: String,
    val severity: Severity,
    val headline: String,
    val recommendation: String? = null,
)

/**
 * Input data for [Rule.matches] and [Rule.render].
 *
 * Aggregates all the context needed for heuristic evaluation: filtered and raw
 * metrics, device tier, detected events, session metadata, and optional timed
 * series for trend analysis (memory growth, thermal correlation).
 *
 * @property filtered Metrics aggregates computed EXCLUDING event windows.
 * @property raw Metrics aggregates computed over the FULL session.
 * @property targetFps Inferred game target FPS (see `inferGameTargetFps`).
 * @property deviceTier Device hardware tier (see [HardwareScoring.detectTier]).
 * @property events List of detected events during the session.
 * @property sessionDurationS Total session duration in seconds.
 * @property memTimedFiltered Memory samples (MB) over time, with event windows excluded.
 *           Used by trend rules (e.g., MemoryGrowthRule for linear regression slope).
 *           Default empty for callers that don't need trend analysis.
 * @property tempCpuTimedFiltered CPU temperature samples over time, event-filtered.
 *           Used by ThermalThrottlingRule to correlate FPS drops with thermal events.
 * @property fpsTimedFiltered FPS samples over time, event-filtered.
 *           Used by trend rules to detect sustained dips vs transient spikes.
 *
 * @since v4.4.0
 */
data class ConclusionInput(
    val filtered: MetricsAggregates,
    val raw: MetricsAggregates,
    val targetFps: Int,
    val deviceTier: HardwareScoring.DeviceTier,
    val events: List<DetectedEvent>,
    val sessionDurationS: Int,
    val memTimedFiltered: List<TimedSample> = emptyList(),
    val tempCpuTimedFiltered: List<TimedSample> = emptyList(),
    val fpsTimedFiltered: List<TimedSample> = emptyList(),
)

/**
 * Interface for individual heuristic rules.
 *
 * Each rule is a pure function that:
 *  1. Checks if its predicate [matches] the input.
 *  2. If so, [render]s a [Conclusion] with interpolated values.
 *
 * Rules are registered in [RuleRegistry.all] and executed by [ConclusionEngine.run].
 *
 * Implementation contract:
 *  - `id` must be a stable, unique kebab-case string (used for sorting ties).
 *  - `severity` determines output ordering.
 *  - `matches` must be pure (no side effects, no I/O).
 *  - `render` must produce Castilian Spanish (formal tuteo) text.
 *
 * @since v4.4.0
 */
interface Rule {
    /** Stable unique identifier (kebab-case, e.g., "stable-low-fps-low-cpu"). */
    val id: String

    /** Severity level for ordering. */
    val severity: Severity

    /**
     * Evaluates whether this rule's predicate matches the given [input].
     * @return true if the rule should fire; false otherwise.
     */
    fun matches(input: ConclusionInput): Boolean

    /**
     * Renders a [Conclusion] for the given [input].
     * Called only when [matches] returns true.
     * @return A conclusion with interpolated values from the input.
     */
    fun render(input: ConclusionInput): Conclusion
}
