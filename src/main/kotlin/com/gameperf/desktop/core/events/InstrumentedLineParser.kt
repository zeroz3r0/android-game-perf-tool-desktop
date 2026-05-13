package com.gameperf.desktop.core.events

/**
 * Pure parser for instrumented opt-in logcat lines emitted under the
 * single tag `GamePerf` (level `I`).
 *
 * Protocol: the game emits `Log.i("GamePerf", "<TAG>.Start")` to open a
 * phase and `Log.i("GamePerf", "<TAG>.Stop")` to close it. Only the four
 * tags in [ALLOWED_TAGS] are recognised; any other tag value, lowercase
 * variant, or trailing content is silently rejected by returning `null`.
 *
 * Matching rules (spec IEM-002, IEM-003):
 *  - The two regexes use `matchEntire`, so trailing characters reject.
 *  - The character class `[A-Z_]+` rejects any lowercase or punctuation.
 *  - An allowlist filter rejects unknown UPPER_SNAKE tags (e.g. `MENU`).
 *
 * Regexes are compiled as top-level `private val` per CLAUDE.md hot-path
 * convention (the detector calls `parse` once per logcat line).
 *
 * This file is the single source of truth for the allowlist and the
 * regex literals — the [SdkSignatureCatalog] entry exists only to keep
 * the tag flowing through `adb logcat` filters (per spec IEM-007) and to
 * satisfy the catalog's pattern-presence invariants. Actual classification
 * and per-tag routing happens in [EventDetectorImpl]'s instrumented branch.
 *
 * @since instrumented-event-mode change
 */
internal object InstrumentedLineParser {

    /**
     * Fixed allowlist of recognised phase tags. Hard-coded (not user
     * configurable) so the grading rubric in the report stays deterministic
     * across captures. See design table — alternatives considered were
     * user config / free-text; both were rejected.
     */
    val ALLOWED_TAGS: Set<String> = setOf(
        "CINEMATIC",
        "TUTORIAL",
        "GAMEPLAY_DENSE",
        "SPECIAL_EVENT",
    )

    private val OPEN_RE: Regex = Regex("""^([A-Z_]+)\.Start$""")
    private val CLOSE_RE: Regex = Regex("""^([A-Z_]+)\.Stop$""")

    /**
     * Parse a single logcat message body (the part after `<tag>:` in
     * threadtime format).
     *
     * @return An [InstrumentedHit] when [msg] matches one of the four
     *   `{Tag}.Start` / `{Tag}.Stop` literals with an allowlisted tag, or
     *   `null` otherwise. The detector treats `null` as silent rejection —
     *   no warning is surfaced (spec IEM-002, IEM-005).
     */
    fun parse(msg: String): InstrumentedHit? {
        OPEN_RE.matchEntire(msg)?.let { m ->
            val tag = m.groupValues[1]
            return if (tag in ALLOWED_TAGS) InstrumentedHit(tag, true) else null
        }
        CLOSE_RE.matchEntire(msg)?.let { m ->
            val tag = m.groupValues[1]
            return if (tag in ALLOWED_TAGS) InstrumentedHit(tag, false) else null
        }
        return null
    }
}
