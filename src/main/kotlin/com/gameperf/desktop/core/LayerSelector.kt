package com.gameperf.desktop.core

import com.gameperf.desktop.core.model.FrameSnapshot

/**
 * Pure ranking and parsing logic for SurfaceFlinger layer candidates plus the
 * captureFrames candidate-iteration helper. Extracted as an `object` so unit
 * tests can exercise everything in isolation (no adb mocking).
 *
 * The FPS-resume-after-ad bug (v4.3.5) traced back to two amplifying weaknesses
 * in the previous selector:
 *
 *  1. It used `firstOrNull()` over a `List<String>` whose order was determined
 *     by `dumpsys SurfaceFlinger --list`, which is NOT documented to be stable.
 *     After an ad SDK destroys and recreates the host SurfaceView, BOTH the old
 *     (zombie) and new layer transiently appear in the list. The old selector
 *     would deterministically re-elect the same dead layer every poll → the
 *     `--latency` query for it returns 1 line → HUD stuck on `--`.
 *
 *  2. It treated every line containing the package name as a real candidate.
 *     Animation-leashes, Dim layers, BackdropBlur, and Splash surfaces all
 *     contain the package name as a substring but never deliver real frames.
 *     Selecting one of those returned 1 line forever.
 *
 * This selector fixes both:
 *
 *  - **Recency ranking**: every SurfaceView layer carries a trailing `#N` and/or
 *    `@N` integer suffix that increments each time the layer is recreated. The
 *    fresh layer always has a higher suffix than the zombie. The selector picks
 *    the highest combined `(#N, @N)` pair so the new layer wins.
 *
 *  - **Noise filtering**: Background, Dim, BackdropBlur, animation-leash, and
 *    `Surface(name=Splash …)` lines are dropped before ranking. A line that
 *    matches ANY noise pattern is rejected; the selector only ranks the
 *    surviving candidates. If filtering empties the list, the selector returns
 *    null — the caller treats that as "no resolvable layer this poll" and
 *    retries via the captureFrames re-discovery path.
 *
 *  - **Tie-breakers within the same recency tier**: BLAST > non-BLAST (the
 *    BLAST queue is the active rendering pipeline on Android 12+); SurfaceView
 *    (excluding Background) > anything else.
 *
 *  - **Pre-Android-12 fallback**: layers without parseable suffixes still get
 *    the noise filter, then the first SurfaceView candidate wins.
 *
 * v4.3.5: Extracted from `AdbBridge.parseSurfaceFlingerListOutput` so the
 * detekt complexity score on AdbBridge doesn't grow. The parser still owns
 * the line splitting + Android-12-modern-format `RequestedLayerState{...}`
 * unwrapping; this object owns the pure ranking after extraction.
 */
internal object LayerSelector {

    // Substrings that indicate a layer is NOT a real frame-producing surface.
    // Matching is case-sensitive — these are exact tokens SurfaceFlinger emits.
    private val NOISE_TOKENS = listOf(
        "Background",
        "Dim",
        "BackdropBlur",
        "animation-leash",
        "Surface(name=Splash",
    )

    // Captures any trailing `#N` or `@N` integer in the layer string. `#N` is
    // the visible-state counter (Android 10+); `@N` is the layer-state counter
    // used by some Android <12 layouts. Both are monotonically increasing.
    private val RE_HASH_SUFFIX = Regex("#(\\d+)")
    private val RE_AT_SUFFIX = Regex("@(\\d+)")

    // v4.3.5: regex previously hosted on AdbBridge. Lives here now so the pure
    // parsing helpers can stay in this object instead of inflating AdbBridge's
    // function count past the detekt threshold.
    private val RE_SF_MODERN = Regex("RequestedLayerState\\{(.+?)\\s+parentId=")
    private val RE_WHITESPACE = "\\s+".toRegex()

    /**
     * Result of [captureFramesFromCandidates] — the chosen layer and its
     * parsed [FrameSnapshot]. Exposed so [AdbBridge.captureFrames] can promote
     * the winning candidate to the front of its cache.
     */
    data class CandidateCaptureResult(
        val snapshot: FrameSnapshot,
        val winningLayer: String,
    )

    /**
     * Rank the candidates and return the best one, or null if no candidate
     * survives the noise filter.
     *
     * @param candidates Raw layer-name strings already filtered to those that
     *   reference the target package by [AdbBridge.parseSurfaceFlingerListOutput].
     */
    fun selectBestLayer(candidates: List<String>): String? {
        if (candidates.isEmpty()) return null

        val clean = candidates.filterNot { line -> NOISE_TOKENS.any { line.contains(it) } }
        if (clean.isEmpty()) return null

        // Score each candidate. Higher score wins. The composite key is sorted
        // lexicographically across (recency, isBlast, isSurfaceView):
        //   1. Highest combined suffix (#N + @N) — recency wins outright.
        //   2. BLAST tag in SurfaceView (active rendering pipeline).
        //   3. SurfaceView at all (vs. plain `pkg/Activity` lines).
        return clean.maxWithOrNull(
            compareBy({ recencyOf(it) }, { blastBitOf(it) }, { surfaceViewBitOf(it) }),
        )
    }

    private fun recencyOf(line: String): Int {
        val hash = RE_HASH_SUFFIX.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val at = RE_AT_SUFFIX.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        // Both #N and @N are monotonically increasing for the same logical
        // layer, so summing avoids ties when one suffix is missing while still
        // ordering correctly when both are present.
        return hash + at
    }

    private fun blastBitOf(line: String): Int = if (line.contains("BLAST")) 1 else 0

    private fun surfaceViewBitOf(line: String): Int = if (line.contains("SurfaceView")) 1 else 0

    // ===== v4.3.5: pure parsing/iteration helpers (moved from AdbBridge) =====

    /**
     * Pure parser for `dumpsys SurfaceFlinger --list` output. Returns the
     * single best layer for [pkg] or null if no candidate matches.
     *
     * Handles both Android 12+ `RequestedLayerState{<name> parentId=<n>}` and
     * the pre-12 plain-line format. Ranking delegates to [selectBestLayer].
     */
    fun parseSurfaceFlingerListOutput(output: String, pkg: String): String? =
        selectBestLayer(extractLayerLines(output, pkg))

    /**
     * Pure parser variant returning ALL ranked candidates for [pkg]. The
     * captureFrames iteration helper walks this list in order until a layer
     * delivers ≥3 lines from `--latency`.
     */
    fun parseSurfaceFlingerListAllCandidates(output: String, pkg: String): List<String> {
        val lines = extractLayerLines(output, pkg)
        if (lines.isEmpty()) return emptyList()
        val remaining = lines.toMutableList()
        val ranked = mutableListOf<String>()
        while (remaining.isNotEmpty()) {
            val best = selectBestLayer(remaining) ?: break
            ranked += best
            remaining.remove(best)
        }
        return ranked
    }

    private fun extractLayerLines(output: String, pkg: String): List<String> {
        val layers = output.lines().filter { it.contains(pkg) }
        return layers.mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            val modern = RE_SF_MODERN.find(trimmed)
            if (modern != null) {
                modern.groupValues[1].trim().takeIf { it.isNotBlank() }
            } else {
                // Pre-Android-12 format: the line IS the layer name. Don't
                // strip anything — SurfaceFlinger needs the exact string
                // including any `#N` or `@N` suffix.
                trimmed
            }
        }
    }

    /**
     * Pure iteration helper: walk [candidates] in order, run [latencyFor] on
     * each, and return the first one whose output has ≥3 lines AND parses to
     * a non-null [FrameSnapshot] via [computeSnapshot]. Returns null if every
     * candidate fails.
     *
     * v4.3.5: extracted from `AdbBridge.captureFrames` for unit testability —
     * pre-fix the retry logic was inline and only exercisable through real
     * adb shell calls. Now the full multi-candidate fallback is testable with
     * a function lambda that simulates `--latency` output.
     *
     * @param computeSnapshot Function converting parsed timestamps into a
     *   [FrameSnapshot]. Defaults to [AdbBridge.computeFrameSnapshot].
     */
    fun captureFramesFromCandidates(
        candidates: List<String>,
        computeSnapshot: (List<Long>) -> FrameSnapshot? = AdbBridge::computeFrameSnapshot,
        latencyFor: (String) -> String,
    ): CandidateCaptureResult? {
        for (candidate in candidates) {
            val output = latencyFor(candidate)
            val lines = output.lines()
            if (lines.size < 3) continue
            val times = parseLatencyTimestamps(lines)
            val snap = computeSnapshot(times) ?: continue
            return CandidateCaptureResult(snap, candidate)
        }
        return null
    }

    private fun parseLatencyTimestamps(lines: List<String>): List<Long> {
        val times = mutableListOf<Long>()
        for (i in 1 until lines.size) {
            val parts = lines[i].trim().split(RE_WHITESPACE)
            if (parts.size >= 2) {
                val ts = parts[1].toLongOrNull() ?: continue
                if (ts > 0 && ts < Long.MAX_VALUE / 2 && (times.isEmpty() || ts >= times.last())) {
                    times.add(ts)
                }
            }
        }
        return times
    }
}
