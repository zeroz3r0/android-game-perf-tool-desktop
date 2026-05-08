package com.gameperf.desktop.core.metrics

import kotlinx.serialization.Serializable

/**
 * A closed time interval `[startMs, endMs]` representing a period to exclude
 * from metrics aggregation.
 *
 * Used by [FilteredMetricsCalculator] to filter out samples that fall within
 * detected event windows (ads, IAPs, loading screens).
 *
 * @property startMs Start of the interval in milliseconds (inclusive).
 * @property endMs End of the interval in milliseconds (inclusive).
 *
 * @since v4.4.0
 */
@Serializable
data class TimeRange(
    val startMs: Long,
    val endMs: Long,
) {
    init {
        require(startMs <= endMs) { "startMs ($startMs) must be <= endMs ($endMs)" }
    }

    /** Duration of this range in milliseconds. */
    val durationMs: Long get() = endMs - startMs

    /**
     * Checks if [timestampMs] falls within this range (inclusive bounds).
     */
    fun contains(timestampMs: Long): Boolean = timestampMs in startMs..endMs

    /**
     * Checks if this range overlaps with [other].
     * Two ranges overlap if their intersection is non-empty.
     */
    fun overlaps(other: TimeRange): Boolean =
        startMs <= other.endMs && endMs >= other.startMs

    /**
     * Returns the union of this range with [other].
     * Assumes the ranges overlap or are adjacent; if they don't overlap,
     * the result spans the gap between them.
     */
    fun union(other: TimeRange): TimeRange =
        TimeRange(
            startMs = minOf(startMs, other.startMs),
            endMs = maxOf(endMs, other.endMs),
        )

    /**
     * Returns a new range expanded by [paddingMs] on both sides.
     * The result is `[startMs - paddingMs, endMs + paddingMs]`.
     *
     * @param paddingMs Symmetric padding in milliseconds (must be >= 0).
     */
    fun withPadding(paddingMs: Long): TimeRange {
        require(paddingMs >= 0) { "paddingMs must be non-negative" }
        return TimeRange(
            startMs = startMs - paddingMs,
            endMs = endMs + paddingMs,
        )
    }
}
