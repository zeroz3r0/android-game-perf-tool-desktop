/**
 * Pure metrics aggregation with dual-view support (filtered + raw).
 *
 * This package provides the [FilteredMetricsCalculator] which computes session
 * aggregates (avg/min/max/percentiles) over timestamped metric histories while
 * excluding samples that fall within detected event ranges (ads, IAPs, loading).
 *
 * The dual-view approach ensures:
 *  - **Filtered** aggregates reflect the player's actual gameplay experience
 *    (excluding SDK surface render times).
 *  - **Raw** aggregates provide an audit trail of the complete session for
 *    transparency and debugging.
 *
 * Key types:
 *  - [TimeRange] — closed interval `[startMs, endMs]` representing an excluded period.
 *  - [MetricsAggregates] — computed stats (avgFps, percentiles, temps, etc.).
 *  - [FilterInput] — all timestamped metric histories needed for aggregation.
 *
 * All functions in this package are pure, side-effect-free, and trivially testable.
 *
 * @since v4.4.0
 * @see com.gameperf.desktop.core.events.DetectedEvent for event detection
 * @see com.gameperf.desktop.core.grading.FinalScoreCalculator for final scoring
 */
package com.gameperf.desktop.core.metrics
