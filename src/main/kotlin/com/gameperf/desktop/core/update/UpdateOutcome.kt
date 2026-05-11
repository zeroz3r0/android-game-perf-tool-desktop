package com.gameperf.desktop.core.update

import kotlinx.serialization.Serializable

/**
 * Outcome of a single auto-update attempt.
 *
 * Sealed class with 6 variants per design §3 + error matrix §6:
 *   - [Success]                — update completed (or elevated path armed for relaunch)
 *   - [FailedUacDenied]        — outer PowerShell exited cleanly but helper never spawned
 *   - [FailedWatchdogTimeout]  — helper canary never observed within timeout
 *   - [FailedDownload]         — HTTP download or asset extraction failed
 *   - [FailedHelperCrash]      — helper process exited non-zero or otherwise crashed
 *   - [FailedUnknown]          — any other terminal failure (catch-all)
 *
 * Each variant is `@Serializable` so it can roundtrip inside [UpdateAttempt]
 * through the manual jsonl line writer used by `UpdateHistoryStore`.
 */
@Serializable
sealed class UpdateOutcome {

    /** Update succeeded (or elevated relaunch path was armed and JVM is exiting). */
    @Serializable
    data object Success : UpdateOutcome()

    /**
     * Outer PowerShell launched cleanly but the elevated helper never wrote its canary.
     * Dominant cause is the user dismissing the UAC prompt. See design §6.
     */
    @Serializable
    data object FailedUacDenied : UpdateOutcome()

    /**
     * `HelperLogWatcher.awaitCanary` returned `Timeout`. Forensic precision — the
     * UI may map this back to [FailedUacDenied] reason via `UpdateFallbackState.from`.
     */
    @Serializable
    data object FailedWatchdogTimeout : UpdateOutcome()

    /**
     * HTTP download, asset extraction, or hash verification failed.
     *
     * @property httpStatus HTTP status code if available; `null` for network-level failures.
     * @property message    Short human-readable reason (English — diagnostic file convention).
     */
    @Serializable
    data class FailedDownload(val httpStatus: Int? = null, val message: String) : UpdateOutcome()

    /**
     * Helper process exited non-zero or threw mid-execution.
     *
     * @property exitCode Helper exit code if observed; `null` if process state was indeterminate.
     */
    @Serializable
    data class FailedHelperCrash(val exitCode: Int? = null) : UpdateOutcome()

    /**
     * Catch-all terminal failure that does not match any other variant.
     *
     * @property message Short diagnostic message (English).
     */
    @Serializable
    data class FailedUnknown(val message: String) : UpdateOutcome()
}
