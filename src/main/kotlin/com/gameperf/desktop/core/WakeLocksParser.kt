package com.gameperf.desktop.core

import com.gameperf.desktop.core.model.WakeLocksDiagnostic
import com.gameperf.desktop.core.model.WakeLocksSnapshot
import com.gameperf.desktop.core.model.WakeLocksUnavailableReason

/**
 * v4.6.0 — Pure parser for `adb shell dumpsys batterystats --charged <pkg>`
 * output. Produces a [WakeLocksSnapshot] with the total accumulated partial
 * wake-lock time attributable to the requested package.
 *
 * Pure: no I/O, no time, no mutable state, no logging. All regexes compiled
 * once at object load. Mirrors `core/AdbThermalParser`, `core/FPowerParser`,
 * `core/events/SdkSignatureCatalog` precedent — keeps the hot path testable
 * in microseconds and detekt-CCN safe.
 *
 * **v1 measurement model**: every partial wake lock attributed to the
 * package is counted as screen-off accumulation. Partial wake locks are by
 * definition the type of wake lock Vitals tracks under "excessive partial
 * wake locks (>2h in 24h screen-off)" — see engram #424. v2 may split into
 * screen-off vs screen-on once we wire per-event-timeline analysis.
 *
 * Plausibility window: each per-entry duration must be `0 <= ms <=` exactly
 * 24h. Entries outside the window are dropped; the snapshot carries an
 * [WakeLocksUnavailableReason.OUT_OF_RANGE_VALUE] diagnostic so the report
 * surfaces "data partially dropped" instead of silently corrupting the
 * total.
 *
 * Design source: `sdd/vitals-rate-and-wakelocks/design` §4.
 */
internal object WakeLocksParser {

    /**
     * Marker for the section that contains every partial wake lock entry in
     * the dumpsys output. Detected via `Regex.find` so we don't depend on
     * line-anchored matching (Samsung One UI sometimes indents the section
     * header by one space). Compiled once, top-level (CLAUDE.md regex rule).
     */
    private val SECTION_HEADER = Regex("""All partial wake locks:""")

    /**
     * Whole-line shape:
     *
     *     [whitespace]Wake lock <UID-or-other-token> <TAG>: <DURATION> partial realtime ...
     *
     * Captures:
     *  - Group 1 = TAG (anything up to the first colon followed by space-or-EOL,
     *    minus the duration). We need the TAG to substring-match the package.
     *  - Group 2 = DURATION (e.g. "1h 22m 17s", "30m 0s", "8s", "25h 0m 0s").
     *
     * The duration is captured as a flexible run of "Nh Nm Ns" tokens with
     * optional spaces — [parseDurationToMs] handles the unit math.
     *
     * We DO NOT require `partial` after the duration (Samsung sometimes omits
     * the literal in less-detailed batterystats variants). Instead, we anchor
     * to the leading `Wake lock` token + `:` separator.
     */
    private val WAKE_LOCK_LINE = Regex(
        """Wake lock\s+\S+\s+(\S+):\s+((?:\d+h\s+)?(?:\d+m\s+)?\d+s)\b"""
    )

    /**
     * Per-unit decomposition. Captures one of `<N>h`, `<N>m`, `<N>s` so we
     * can multiply by the appropriate factor. Top-level compile.
     */
    private val DURATION_UNIT = Regex("""(\d+)\s*([hms])""")

    /** Permission denial markers (different Android versions phrase them differently). */
    private val PERMISSION_DENIAL = Regex("""Permission Denial|requires android\.permission\.DUMP""")

    /** Plausibility window upper bound — 24 hours expressed in ms. */
    private const val MAX_PLAUSIBLE_MS: Long = 24L * 3600L * 1000L

    /**
     * Parse [raw] dumpsys output, attributing partial wake locks whose tag
     * contains the package [pkg] as a token-bounded substring.
     *
     * @return a [WakeLocksSnapshot] — `wakeLocksAvailable=true` plus totals
     *   when at least one valid entry was found, otherwise an unavailable
     *   snapshot with a [WakeLocksDiagnostic] describing the proximate cause.
     */
    fun parse(raw: String, pkg: String): WakeLocksSnapshot {
        if (raw.isBlank()) {
            return unavailable(WakeLocksUnavailableReason.PARSE_FAILED, pkg)
        }
        if (PERMISSION_DENIAL.containsMatchIn(raw)) {
            return unavailable(WakeLocksUnavailableReason.PARSE_FAILED, pkg)
        }
        val headerMatch = SECTION_HEADER.find(raw)
            ?: return unavailable(WakeLocksUnavailableReason.PARSE_FAILED, pkg)

        // Walk the section body — every line after the header until the next
        // blank line or another section title. We use the substring after the
        // header for the line scan; matches with non-wake-lock content are
        // simply not captured by WAKE_LOCK_LINE.
        val sectionBody = raw.substring(headerMatch.range.last + 1)
        val sectionEndIdx = findSectionEnd(sectionBody)
        val scanText = sectionBody.substring(0, sectionEndIdx)

        var totalMs = 0L
        var matched = 0
        var droppedOutOfRange = 0

        WAKE_LOCK_LINE.findAll(scanText).forEach { m ->
            val tag = m.groupValues[1]
            if (!tagMatchesPackage(tag, pkg)) return@forEach
            val durationText = m.groupValues[2]
            val ms = parseDurationToMs(durationText) ?: return@forEach
            if (ms !in 0L..MAX_PLAUSIBLE_MS) {
                droppedOutOfRange++
                return@forEach
            }
            totalMs += ms
            matched++
        }

        return when {
            matched > 0 -> {
                val diag = if (droppedOutOfRange > 0) {
                    WakeLocksDiagnostic(
                        probedCommand = "dumpsys batterystats --charged $pkg",
                        reason = WakeLocksUnavailableReason.OUT_OF_RANGE_VALUE,
                    )
                } else {
                    null
                }
                WakeLocksSnapshot(
                    totalScreenOffMs = totalMs,
                    totalScreenOnMs = 0L,
                    partialLockCount = matched,
                    wakeLocksAvailable = true,
                    diagnostic = diag,
                )
            }
            droppedOutOfRange > 0 -> unavailable(WakeLocksUnavailableReason.OUT_OF_RANGE_VALUE, pkg)
            else -> unavailable(WakeLocksUnavailableReason.PKG_NOT_FOUND, pkg)
        }
    }

    /**
     * Locate the first index AFTER which the wake-locks section ends. We
     * detect end-of-section by a blank line followed by a non-`Wake lock`
     * heading line. Falls back to the whole remaining text if no terminator
     * is found (e.g. the wake-locks section is the last section dumped).
     */
    private fun findSectionEnd(body: String): Int {
        val lines = body.lines()
        var charsConsumed = 0
        var inSection = true
        for (line in lines) {
            val trimmed = line.trim()
            // Blank line — section may end here unless the next line is still
            // a wake-lock entry. We use a soft heuristic: any non-blank line
            // that isn't "Wake lock ..." starting after the section header
            // signals end-of-section.
            if (inSection && trimmed.isNotEmpty() &&
                !trimmed.startsWith("Wake lock") &&
                !trimmed.startsWith("All partial wake locks")
            ) {
                // First non-wake-lock heading line — section ends here.
                return charsConsumed
            }
            // +1 for the line terminator stripped by `.lines()`.
            charsConsumed += line.length + 1
        }
        return body.length
    }

    /**
     * Token-bounded substring check — the package must appear in the tag
     * NOT followed or preceded by another identifier character. Prevents
     * `com.example.game` from matching `com.example.gameworld`.
     */
    private fun tagMatchesPackage(tag: String, pkg: String): Boolean {
        var searchFrom = 0
        while (true) {
            val idx = tag.indexOf(pkg, startIndex = searchFrom)
            if (idx < 0) return false
            val before = if (idx == 0) null else tag[idx - 1]
            val afterIdx = idx + pkg.length
            val after = if (afterIdx >= tag.length) null else tag[afterIdx]
            val leftOk = before == null || !before.isJavaIdentifierPart()
            val rightOk = after == null || !after.isJavaIdentifierPart()
            if (leftOk && rightOk) return true
            searchFrom = idx + 1
        }
    }

    /**
     * Convert a `Nh Nm Ns` style duration string into milliseconds. Returns
     * `null` when the string doesn't decode (no h/m/s tokens at all).
     */
    private fun parseDurationToMs(text: String): Long? {
        var totalSeconds = 0L
        var matched = false
        DURATION_UNIT.findAll(text).forEach { m ->
            matched = true
            val n = m.groupValues[1].toLongOrNull() ?: return null
            totalSeconds += when (m.groupValues[2]) {
                "h" -> n * 3600L
                "m" -> n * 60L
                "s" -> n
                else -> 0L
            }
        }
        if (!matched) return null
        return totalSeconds * 1000L
    }

    private fun unavailable(
        reason: WakeLocksUnavailableReason,
        pkg: String,
    ): WakeLocksSnapshot = WakeLocksSnapshot(
        totalScreenOffMs = -1L,
        totalScreenOnMs = -1L,
        partialLockCount = 0,
        wakeLocksAvailable = false,
        diagnostic = WakeLocksDiagnostic(
            probedCommand = "dumpsys batterystats --charged $pkg",
            reason = reason,
        ),
    )
}
