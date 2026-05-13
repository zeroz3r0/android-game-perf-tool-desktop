package com.gameperf.desktop.core.kpi

/**
 * Phase 3 — pure linear scoring (KPI-002, Model A from docs §6.2).
 *
 * Maps a raw KPI value to a 0..100 integer score by linear interpolation
 * between [floor] (→ 0) and [target] (→ 100). Out-of-range values are
 * clamped. Non-finite values (NaN, ±∞) are mapped per direction with no
 * exceptions — aggregators rely on this never throwing.
 *
 * Direction semantics:
 *   - [Direction.HIGHER_IS_BETTER]: `value ≥ target → 100`, `value ≤ floor → 0`.
 *     Example: FPS, where target > floor numerically.
 *   - [Direction.LOWER_IS_BETTER]:  `value ≤ target → 100`, `value ≥ floor → 0`.
 *     Example: cold-start time, where target < floor numerically.
 *
 * Pure: no I/O, no time, deterministic. Mirror of `LinearScoring` in design.
 *
 * @since v4.5 (kpi-scoring internal v1)
 */

/**
 * Scores a single KPI [value] against its per-tier [Threshold] using the
 * linear (Model A) formula.
 *
 * @return integer in `[0, 100]`. Returns `0` for `NaN`.
 */
fun scoreLinear(
    value: Double,
    target: Double,
    floor: Double,
    direction: Direction,
): Int {
    if (value.isNaN()) return 0

    val raw: Double = when (direction) {
        Direction.HIGHER_IS_BETTER -> {
            // target ≥ value ≥ floor expected; linearly map [floor, target] → [0, 100].
            // If target == floor we degenerate — treat any value ≥ target as 100,
            // otherwise as 0 (avoids divide-by-zero).
            val span = target - floor
            if (span == 0.0) {
                if (value >= target) 100.0 else 0.0
            } else {
                (value - floor) / span * 100.0
            }
        }
        Direction.LOWER_IS_BETTER -> {
            // floor ≥ value ≥ target expected; linearly map [target, floor] → [100, 0].
            val span = floor - target
            if (span == 0.0) {
                if (value <= target) 100.0 else 0.0
            } else {
                (floor - value) / span * 100.0
            }
        }
    }

    // Clamp to [0, 100] — covers Infinity and out-of-range raw values.
    val clamped = when {
        raw.isNaN() -> 0.0
        raw < 0.0 -> 0.0
        raw > 100.0 -> 100.0
        else -> raw
    }
    return clamped.toInt()
}
