package com.gameperf.desktop.core

/**
 * v4.5.0 -- Pure parser for the Android sysfs GPU probe output. No I/O, no
 * time-dependent state, no mutable global. Every function is deterministic
 * `(input: String) -> Result?` mirroring the v4.4.1 `AdbThermalParser` split.
 *
 * Entry points:
 *  - [parseProbeOutput] : multi-line `<path>:<value>` body emitted by the
 *    one-shot probe `for p in <paths>; do echo "${p}:$(cat $p 2>/dev/null)";
 *    done`. Returns the FIRST non-empty hit in [GpuVendorCatalog.PROBE_CANDIDATES]
 *    order (catalog-order wins).
 *  - [parseMali] : single-int Mali utilization percent.
 *  - [parseAdrenoGpuBusyPercentage] : single-int Adreno percent with optional
 *    `%` suffix.
 *  - [parseAdrenoGpuBusy] : two cumulative `Long` counters `(busy, total)`.
 *  - [computeAdrenoDelta] : delta math for [parseAdrenoGpuBusy] across two
 *    consecutive ticks.
 *
 * All numeric outputs are gated to `[0, 100]` (plausibility window per spec
 * GPU-021).
 */
internal object GpuUsageParser {

    private const val MIN_PCT = 0
    private const val MAX_PCT = 100

    /**
     * Parse the multi-line `<path>:<value>` probe output. Returns the FIRST
     * non-empty hit matched against [GpuVendorCatalog.PROBE_CANDIDATES] in
     * declaration order. `null` when every candidate is absent or empty.
     *
     * Matching is `line.startsWith("$path:")` to ensure exact prefix
     * boundaries (substring uniqueness asserted in [GpuVendorCatalogTest]
     * guarantees no two catalog entries can collide).
     */
    fun parseProbeOutput(rawOutput: String): GpuProbeResult? {
        val lines = rawOutput.lines()
        // Catalog-order wins: outer loop over candidates so first-match-in-catalog short-circuits.
        for (candidate in GpuVendorCatalog.PROBE_CANDIDATES) {
            val prefix = "${candidate.path}:"
            val match = lines.firstOrNull { it.startsWith(prefix) } ?: continue
            val payload = match.substring(prefix.length).trim()
            if (payload.isEmpty()) continue
            return GpuProbeResult(
                vendor = candidate.vendor,
                winningPath = candidate.path,
                format = candidate.format,
                rawPayload = payload,
            )
        }
        return null
    }

    /** Mali single-int utilization. Returns null on parse error or out-of-range. */
    fun parseMali(raw: String): Int? {
        val n = raw.trim().toIntOrNull() ?: return null
        return if (n in MIN_PCT..MAX_PCT) n else null
    }

    /** Adreno gpu_busy_percentage. Strips optional trailing '%'. Gated 0..100. */
    fun parseAdrenoGpuBusyPercentage(raw: String): Int? {
        val cleaned = raw.trim().removeSuffix("%").trim()
        val n = cleaned.toIntOrNull() ?: return null
        return if (n in MIN_PCT..MAX_PCT) n else null
    }

    /**
     * Adreno raw gpubusy: two whitespace-separated cumulative `Long` counters
     * `(busy, total)`. Returns null on malformed input, missing token, or
     * negative numbers. Whitespace separator includes newlines so two-line
     * sysfs dumps are accepted.
     */
    fun parseAdrenoGpuBusy(raw: String): Pair<Long, Long>? {
        val tokens = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.size != 2) return null
        val busy = tokens[0].toLongOrNull() ?: return null
        val total = tokens[1].toLongOrNull() ?: return null
        if (busy < 0L || total < 0L) return null
        return busy to total
    }

    /**
     * Pure delta math for Adreno gpubusy across two consecutive ticks.
     *
     * Returns null on:
     *  - deltaTotal ≤ 0 (counter wraparound | post-boot idle | clock anomaly)
     *  - deltaBusy  < 0 (defensive — `u64` in kernel, `Long` in JVM)
     *  - deltaBusy > deltaTotal (implausible — busy time cannot exceed wall clock)
     *
     * Otherwise returns `((deltaBusy * 100) / deltaTotal)` coerced to `[0,100]`.
     */
    fun computeAdrenoDelta(prev: Pair<Long, Long>, curr: Pair<Long, Long>): Int? {
        val deltaBusy = curr.first - prev.first
        val deltaTotal = curr.second - prev.second
        if (deltaTotal <= 0L) return null
        if (deltaBusy < 0L) return null
        if (deltaBusy > deltaTotal) return null
        val pct = (deltaBusy * 100L) / deltaTotal
        return pct.toInt().coerceIn(MIN_PCT, MAX_PCT)
    }
}

/**
 * Probe result returned by [GpuUsageParser.parseProbeOutput] — first
 * non-empty catalog hit. Used by the bridge to cache vendor + winning path
 * for steady-state ticks.
 */
internal data class GpuProbeResult(
    val vendor: GpuVendor,
    val winningPath: String,
    val format: ProbeFormat,
    val rawPayload: String,
)
