package com.gameperf.desktop.core

/**
 * v4.6.x â€” Confidence rating for a NETWORK probe candidate.
 *
 * Distinct enum (not [com.gameperf.desktop.core.Confidence] used by GpuVendorCatalog)
 * because each metric domain has its own promotion gate. GPU uses HIGH/MEDIUM/LOW;
 * network adds HINT as the pre-lab-verification rung. Keeping enums separate
 * avoids accidental cross-catalog coupling.
 *
 * See `sdd/network-bandwidth-total-app/design` D3.
 */
enum class NetworkConfidence {
    HINT,
    MEDIUM,
    HIGH,
}

/**
 * v4.6.x â€” One probe candidate the bridge tries when it asks the device for
 * per-UID network bandwidth. A candidate is either a binder transaction code
 * (preferred, fast) OR a `dumpsys` command (slow fallback). At least one of
 * the two MUST be non-null â€” see [NetworkVendorCatalogTest].
 *
 * - [method] â€” short label surfaced in [NetworkDiagnostic.detectedMethod] /
 *   `probedSources`. Stable format: `"BINDER:<code>"` for binder candidates,
 *   `"DUMPSYS"` for the dumpsys fallback (currently lives as a sibling
 *   const, see [NetworkVendorCatalog.DUMPSYS_NETSTATS_COMMAND]).
 * - [binderCode] â€” transaction code for `service call netstats`. v1 walks
 *   `[11, 12, 14, 15]` because AOSP renumbered the call between Android 11
 *   and 14 and some vendors (Samsung/MIUI) shifted again.
 * - [dumpsysCommand] â€” full shell command (e.g. `"dumpsys netstats detail --uid"`).
 *   v1 keeps this as a sibling const, NOT inside [NetworkVendorCatalog.PROBE_CANDIDATES],
 *   so the catalog is binder-only. The field is present here so a future
 *   dumpsys-as-candidate variant can drop straight in without API churn.
 * - [confidence] â€” promotion gate. All v1 entries are [NetworkConfidence.HINT].
 *
 * See `sdd/network-bandwidth-total-app/spec` NET-003 + NET-004.
 */
data class NetworkProbeCandidate(
    val method: String,
    val binderCode: Int? = null,
    val dumpsysCommand: String? = null,
    val confidence: NetworkConfidence,
)

/**
 * v4.6.x â€” Single source of truth for the network bandwidth probe pipeline.
 *
 * CLAUDE.md v4.2.13 lesson â€” anti-duplication rule reapplied: any binder
 * transaction code or dumpsys command lives HERE, NOT scattered across
 * `AdbBridge`, `FakeAdbBridge`, parser tests, or report copy. The price of
 * adding a binder code: append to [PROBE_CANDIDATES] + update the size lock in
 * `NetworkVendorCatalogTest`. No other catalog may grow elsewhere.
 *
 * Mirrors:
 *  - [GpuVendorCatalog] â€” same probe-once-then-cache pattern
 *  - `FPowerVendorCatalog` â€” same vendor-ordering invariant
 *  - `ThermalZoneClassifier` â€” same KDoc-anti-duplication warning shape
 *
 * Ordering invariant (NET-003): binder candidates appear BEFORE any dumpsys
 * candidate. v1 keeps dumpsys as a sibling const ([DUMPSYS_NETSTATS_COMMAND])
 * so the catalog is uniformly binder-only.
 *
 * Confidence policy (design D3): every v1 entry MUST be [NetworkConfidence.HINT] until
 * a real-device lab capture confirms each binder code on each Android major.
 * Banner copy reads "estimado" â€” the user knows the value is provisional.
 *
 * See `sdd/network-bandwidth-total-app/spec` NET-003 + NET-004.
 */
object NetworkVendorCatalog {

    /**
     * Catalog of binder transaction codes to walk during the cold probe.
     * Ordered most-likely-on-modern-AOSP first. Locked at size 4 by
     * `NetworkVendorCatalogTest` â€” to grow, update both list and test.
     */
    val PROBE_CANDIDATES: List<NetworkProbeCandidate> = listOf(
        NetworkProbeCandidate(
            method = "BINDER:11",
            binderCode = 11,
            confidence = NetworkConfidence.HINT,
        ),
        NetworkProbeCandidate(
            method = "BINDER:12",
            binderCode = 12,
            confidence = NetworkConfidence.HINT,
        ),
        NetworkProbeCandidate(
            method = "BINDER:14",
            binderCode = 14,
            confidence = NetworkConfidence.HINT,
        ),
        NetworkProbeCandidate(
            method = "BINDER:15",
            binderCode = 15,
            confidence = NetworkConfidence.HINT,
        ),
    )

    /**
     * Dumpsys fallback command (Task 2.2). Kept as a sibling const, NOT
     * inside [PROBE_CANDIDATES], because:
     *  - It is the LAST-RESORT path (slow, can take 1-2s on devices with
     *    long netstats history) â€” never used in steady-state.
     *  - Anti-duplication: this string MUST NOT appear inside any candidate's
     *    `dumpsysCommand`; the asserter `DUMPSYS_NETSTATS_COMMAND is NOT also
     *    listed inside PROBE_CANDIDATES` enforces this.
     *
     * Consumer: `AdbBridge.captureNetworkBandwidth` builds the full call as
     * `"$DUMPSYS_NETSTATS_COMMAND $uid"` at runtime.
     */
    const val DUMPSYS_NETSTATS_COMMAND: String = "dumpsys netstats detail --uid"
}

