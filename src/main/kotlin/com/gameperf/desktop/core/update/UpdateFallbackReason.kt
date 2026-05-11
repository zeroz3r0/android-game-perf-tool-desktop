package com.gameperf.desktop.core.update

import kotlinx.serialization.Serializable

/**
 * UI-facing reason for the update fallback panel. Mapped from [UpdateOutcome]
 * via [UpdateFallbackState.from] per design §6 mapping table.
 *
 * Distinct from [UpdateOutcome] because the UI groups failures by user-actionable
 * cause: e.g. both `FailedUacDenied` and `FailedWatchdogTimeout` resolve to
 * [USER_CANCELLED_UAC] because the dominant cause of the watchdog timing out
 * after a clean outer-PowerShell exit is the user dismissing the UAC dialog.
 */
@Serializable
enum class UpdateFallbackReason {
    /** User dismissed the UAC prompt (or watchdog timed out — see design §6). */
    USER_CANCELLED_UAC,

    /** Helper process took too long to signal canary (no clean cause inferred). */
    HELPER_TIMEOUT,

    /** HTTP download or asset extraction failed. */
    DOWNLOAD_FAILED,

    /** Helper exited non-zero or threw mid-execution. */
    HELPER_CRASHED,

    /** Catch-all — unclassified terminal failure. */
    UNKNOWN,
}
