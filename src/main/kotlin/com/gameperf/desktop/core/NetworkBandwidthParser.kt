package com.gameperf.desktop.core

// Top-level regex (private val) — compiled once per JVM, never inline in hot path.
// Per CLAUDE.md v4.2.5 lesson (regex in hot paths must be top-level private val).

/**
 * Matches a single bucket line in `dumpsys netstats detail --uid <uid>` output.
 *
 * Format (Android 10+):
 * `  0x10000000 IFACE STATE ROAMING RX_BYTES RX_PKTS TX_BYTES TX_PKTS`
 *
 * Captures: group(1) = rxBytes, group(2) = txBytes. Robust to variable
 * leading whitespace + extra trailing fields some vendors add.
 */
private val DUMPSYS_BUCKET_LINE: Regex = Regex(
    """^\s*0x[0-9a-fA-F]+\s+\S+\s+\S+\s+\S+\s+(\d+)\s+\d+\s+(\d+)\s+\d+""",
)

/**
 * Matches the `Result: Parcel(....)` line from `service call netstats <code> i32 <uid>`.
 *
 * The parcel payload contains 4×int64 (big-endian hex words, space-separated):
 *  [0..1]  rxBytes (high 32 bits, low 32 bits)
 *  [2..3]  txBytes (high 32 bits, low 32 bits)
 *
 * Captures the parenthesised content for downstream tokenisation.
 */
private val PARCEL_PAYLOAD: Regex = Regex(
    """Parcel\(\s*(.+?)\)""",
    RegexOption.DOT_MATCHES_ALL,
)

/**
 * Plausibility window for v1 (NET-010): bytes must be in `[0, 100 GB]`.
 *
 * The upper bound guards against binder code collisions producing
 * terabyte-scale garbage (a 100 GB session is implausible for a mobile
 * gameplay capture). Tune if real-device captures show otherwise.
 */
private const val MAX_PLAUSIBLE_BYTES: Long = 100L * 1024L * 1024L * 1024L

/**
 * v4.6.x — Pure parser for `dumpsys netstats` and `service call netstats`
 * Android shell outputs. No I/O, no time-dependent state. Top-level regex
 * compiled once (CLAUDE.md v4.2.5 hot-path lesson). Mirrors
 * [GpuUsageParser] and `FPowerParser` precedent.
 *
 * Two entry points:
 *  - [parseDumpsysNetstats] — slow fallback path
 *  - [parseServiceCallResponse] — fast binder path
 *
 * Each returns `Pair(rxBytes, txBytes)` on success, `null` on missing UID,
 * malformed input, or unparseable payload.
 *
 * See `sdd/network-bandwidth-total-app/spec` NET-005, NET-006, NET-010.
 */
internal object NetworkBandwidthParser {

    /**
     * Parse `dumpsys netstats detail --uid <uid>` output and return the
     * summed `(rxBytes, txBytes)` for the requested UID, or `null` if the
     * output has no buckets for that UID or every line is malformed.
     *
     * Multi-bucket UID lines are summed (NET-005). The dumpsys output
     * separates buckets per (uid, interface, state, roaming) tuple.
     *
     * @param raw multi-line shell output
     * @param uid Android UID of the game process (e.g. `10234`)
     * @return summed `Pair(rxBytes, txBytes)` across all matching buckets,
     *   or `null` if no bucket parsed cleanly for this UID.
     */
    fun parseDumpsysNetstats(raw: String, uid: Int): Pair<Long, Long>? {
        if (raw.isBlank()) return null
        // dumpsys groups bucket lines under `uid=<uid>` blocks. We do a simple
        // 2-pass: locate `uid=<uid>` then sum DUMPSYS_BUCKET_LINE matches in
        // the trailing block. For v1 we accept ANY bucket line in the output
        // because some dumpsys variants flatten the per-uid block — the
        // caller has already filtered by `--uid <uid>` so every bucket in
        // the output already belongs to the target UID.
        val needle = "uid=$uid"
        val rawIfFiltered = if (needle in raw) raw.substringAfter(needle) else raw
        var rxSum = 0L
        var txSum = 0L
        var matched = false
        rawIfFiltered.lineSequence().forEach { line ->
            val m = DUMPSYS_BUCKET_LINE.find(line) ?: return@forEach
            val rx = m.groupValues[1].toLongOrNull() ?: return@forEach
            val tx = m.groupValues[2].toLongOrNull() ?: return@forEach
            if (rx < 0L || tx < 0L) return@forEach
            rxSum += rx
            txSum += tx
            matched = true
        }
        return if (matched) rxSum to txSum else null
    }

    /**
     * Parse the `Result: Parcel(...)` line produced by
     * `service call netstats <code> i32 <uid>` and return `(rxBytes, txBytes)`.
     *
     * Binder parcel format: 4 big-endian int64s laid out as two pairs of
     * 32-bit hex words. v1 collapses each pair into a [Long] via
     * `(highWord shl 32) or lowWord`.
     *
     * Returns `null` for:
     *  - Missing `Parcel(...)` payload
     *  - Fewer than 4 hex words inside the parcel
     *  - Any token failing hex parse
     *  - Negative result (NET-010 lower-bound guard)
     */
    fun parseServiceCallResponse(raw: String): Pair<Long, Long>? {
        if (raw.isBlank()) return null
        val payloadMatch = PARCEL_PAYLOAD.find(raw) ?: return null
        // Extract only 8-char hex words (the parcel int32 layout). This skips
        // ASCII filler like `'....d....'` that some service-call dumps append.
        val hexWordPattern = Regex("""\b[0-9a-fA-F]{8}\b""")
        val words = hexWordPattern.findAll(payloadMatch.groupValues[1])
            .map { it.value.toLongOrNull(16) }
            .toList()
        if (words.size < 4 || words.any { it == null }) return null
        @Suppress("UNCHECKED_CAST")
        val w = words as List<Long>
        val rxBytes = (w[0] shl 32) or (w[1] and 0xFFFFFFFFL)
        val txBytes = (w[2] shl 32) or (w[3] and 0xFFFFFFFFL)
        if (rxBytes < 0L || txBytes < 0L) return null
        return rxBytes to txBytes
    }

    /**
     * NET-010 plausibility window: `[0, 100 GB]`. Used by the bridge to
     * reject binder-collision garbage values before persisting them.
     */
    fun isPlausibleBytes(bytes: Long): Boolean =
        bytes in 0L..MAX_PLAUSIBLE_BYTES
}
