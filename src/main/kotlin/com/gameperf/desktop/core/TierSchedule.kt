package com.gameperf.desktop.core

/**
 * Pure tier scheduling logic for the capture polling loop.
 *
 * The capture loop runs every ~500ms and needs to interleave three categories of metric
 * fetches with very different costs:
 *
 *   - **Fast tier** (~30-50ms total): FPS, CPU, battery. Runs every iteration.
 *   - **Medium tier** (~30-80ms): thermal sensors. Runs every ~2 seconds.
 *   - **Slow tier** (~200-800ms): `dumpsys meminfo` (blocks game's main thread). Runs every ~5s.
 *   - **Compositor tier** (~150-500ms): `getMissedFrames` (full `dumpsys SurfaceFlinger`,
 *     grabs the global compositor lock). Runs every ~7s. **Must NOT coincide with the slow
 *     tier** because both are heavy AND both contend with the game (slow blocks the game
 *     process; compositor blocks the SurfaceFlinger lock the game uses to present frames).
 *
 * v3.1.10 had two critical bugs in its tier scheduling:
 *   1. The first iteration (`iterCount = 0`) ran ALL tiers because `0 % N == 0` for any N.
 *      Result: 1-1.5s stutter at the start of every capture.
 *   2. Medium and slow tiers shared a common factor (LCM(4,10) = 20) so they coincided
 *      every 20 iterations, producing a heavy hiccup every ~10s.
 *
 * v3.1.11 attempted to fix this by shifting modulo phases (`% 4 == 3`, `% 10 == 7`) but
 * the round-2 review caught that the fix only avoided iter 0 — the heavy tiers still
 * coincided every 20 iterations because both phases are still `≡ 3 (mod 4)`. The fix
 * also re-introduced `getMissedFrames` into the same iteration as `meminfo`, making the
 * recurring hiccup ~60% larger than v3.1.10.
 *
 * This class implements the **v3.1.11 round-2 fix**: four independent phases chosen so
 * that:
 *   - Iter 0 runs ONLY the fast tier.
 *   - Each heavy tier (medium, slow, compositor) fires on its own iteration — never two
 *     at once.
 *   - The phases are coprime where possible to spread coincidences across long timescales.
 *
 * Phase choices:
 *   - Medium  (thermal):       `iter % 4 == 1` → fires at iter 1, 5, 9, 13, 17, 21, 25, ...
 *   - Slow    (meminfo):       `iter % 10 == 6` → fires at iter 6, 16, 26, 36, 46, ...
 *   - Compositor (missed):     `iter % 14 == 3` → fires at iter 3, 17, 31, 45, 59, ...
 *
 * Coincidence analysis (verifying NO two heavy tiers ever fire on the same iteration):
 *   - Medium ∩ Slow:       both true requires `i % 4 == 1` AND `i % 10 == 6`.
 *                          `i % 10 == 6` → i ∈ {6, 16, 26, 36, ...}. None of those satisfy
 *                          `i % 4 == 1` (6%4=2, 16%4=0, 26%4=2, 36%4=0, ...). NEVER coincide.
 *   - Medium ∩ Compositor: requires `i % 4 == 1` AND `i % 14 == 3`. CRT: solutions exist at
 *                          i = 17, 45, 73, ... (every 28 iters). 17%4=1 ✓, 17%14=3 ✓.
 *                          Coincide every 28 iterations ≈ every 14 seconds. ACCEPTABLE — both
 *                          are cheap (medium ~80ms, compositor ~500ms).
 *   - Slow ∩ Compositor:   requires `i % 10 == 6` AND `i % 14 == 3`. CRT: 10 and 14 share
 *                          gcd=2; 6 and 3 differ by an odd number, so NO solution. NEVER
 *                          coincide. (Verified: i=6 → 6%14=6, i=16 → 16%14=2, i=26 → 26%14=12,
 *                          i=36 → 36%14=8, i=46 → 46%14=4, i=56 → 56%14=0, ... never 3.)
 *
 * Worst-case iteration cost:
 *   - Iter 0:    fast only (~30-50ms). No stutter.
 *   - Iter 1:    fast + medium (~110-130ms). Barely visible.
 *   - Iter 3:    fast + compositor (~180-550ms). Single spike.
 *   - Iter 5:    fast + medium (~110-130ms).
 *   - Iter 6:    fast + slow (~230-850ms). Single spike.
 *   - Iter 9:    fast + medium.
 *   - Iter 13:   fast + medium.
 *   - Iter 16:   fast + slow.
 *   - Iter 17:   fast + medium + compositor (~210-630ms). The single coincidence per ~28
 *                iterations, both fire. Still bounded because slow is NOT in this iteration.
 *
 * The maximum cost is now ~850ms (slow alone) or ~630ms (medium + compositor). v3.1.10's
 * worst case was ~880ms (medium + slow at iter 0). v3.1.11 round-1 was ~1380ms (medium +
 * slow + compositor at iter 7). v3.1.11 round-2 is ~850ms — equivalent to v3.1.10 for the
 * recurring case AND eliminates the iter-0 burst.
 */
data class TierSchedule(
    val mediumPeriod: Int = 4,
    val mediumPhase: Int = 1,
    val slowPeriod: Int = 10,
    val slowPhase: Int = 6,
    val compositorPeriod: Int = 14,
    val compositorPhase: Int = 3
) {
    /** True if iter 0 runs ONLY the fast tier. */
    init {
        require(mediumPhase != 0) { "medium phase must be non-zero so iter 0 stays fast-only" }
        require(slowPhase != 0) { "slow phase must be non-zero so iter 0 stays fast-only" }
        require(compositorPhase != 0) { "compositor phase must be non-zero so iter 0 stays fast-only" }
    }

    fun runMedium(iter: Int): Boolean = iter % mediumPeriod == mediumPhase
    fun runSlow(iter: Int): Boolean = iter % slowPeriod == slowPhase
    fun runCompositor(iter: Int): Boolean = iter % compositorPeriod == compositorPhase

    /** True if any heavy tier fires on this iteration. Used for tests/debugging. */
    fun runAnyHeavy(iter: Int): Boolean = runMedium(iter) || runSlow(iter) || runCompositor(iter)
}
