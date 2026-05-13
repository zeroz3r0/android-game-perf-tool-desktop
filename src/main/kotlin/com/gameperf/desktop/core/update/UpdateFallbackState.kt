package com.gameperf.desktop.core.update

import com.gameperf.desktop.core.GITHUB_OWNER
import com.gameperf.desktop.core.GITHUB_REPO
import kotlinx.serialization.Serializable

/**
 * State driving the in-app update fallback panel. Non-null value means the
 * panel is visible; `null` means hidden.
 *
 * Pure @Serializable data class — zero I/O, zero coroutines, zero Compose.
 * The owning [com.gameperf.desktop.core.update.UpdateOutcome] is mapped to a
 * UI-friendly [UpdateFallbackReason] via [from] per design §6.
 *
 * @property reason            UI-facing failure category.
 * @property attemptedVersion  Target release the user tried to install (e.g. "4.4.1").
 * @property downloadUrl       GitHub release page for the manual download button.
 * @property installGuideUrl   Documentation link for the installation guide button.
 * @property diagnosticTail    Trailing lines of `last-update.log` for the "Detalles técnicos" expander.
 */
@Serializable
data class UpdateFallbackState(
    val reason: UpdateFallbackReason,
    val attemptedVersion: String,
    val downloadUrl: String,
    val installGuideUrl: String,
    val diagnosticTail: String?,
) {
    companion object {
        /** Wiki / README anchor with manual install instructions. */
        const val INSTALL_GUIDE_URL: String =
            "https://github.com/$GITHUB_OWNER/$GITHUB_REPO#instalación-manual"

        /**
         * Builds the GitHub release page URL for [version], used by the
         * "Descargar manualmente vX.Y.Z" button.
         */
        fun downloadUrlFor(version: String): String =
            "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/tag/v$version"

        /**
         * Maps a terminal failure [outcome] to a fallback panel state.
         *
         * Mapping per design §6:
         *   - [UpdateOutcome.FailedUacDenied]       -> [UpdateFallbackReason.USER_CANCELLED_UAC]
         *   - [UpdateOutcome.FailedWatchdogTimeout] -> [UpdateFallbackReason.USER_CANCELLED_UAC]
         *     (dominant cause when outer PS exit was clean)
         *   - [UpdateOutcome.FailedDownload]        -> [UpdateFallbackReason.DOWNLOAD_FAILED]
         *   - [UpdateOutcome.FailedHelperCrash]     -> [UpdateFallbackReason.HELPER_CRASHED]
         *   - [UpdateOutcome.FailedUnknown]         -> [UpdateFallbackReason.UNKNOWN]
         *
         * @throws IllegalArgumentException if [outcome] is [UpdateOutcome.Success] —
         *   success outcomes never produce a fallback panel and calling this with
         *   `Success` is a programmer error.
         */
        fun from(
            outcome: UpdateOutcome,
            attemptedVersion: String,
            helperLogTail: String?,
        ): UpdateFallbackState {
            val reason = when (outcome) {
                is UpdateOutcome.Success ->
                    throw IllegalArgumentException("UpdateOutcome.Success has no fallback state")
                is UpdateOutcome.FailedUacDenied -> UpdateFallbackReason.USER_CANCELLED_UAC
                is UpdateOutcome.FailedWatchdogTimeout -> UpdateFallbackReason.USER_CANCELLED_UAC
                is UpdateOutcome.FailedDownload -> UpdateFallbackReason.DOWNLOAD_FAILED
                is UpdateOutcome.FailedHelperCrash -> UpdateFallbackReason.HELPER_CRASHED
                is UpdateOutcome.FailedUnknown -> UpdateFallbackReason.UNKNOWN
            }
            return UpdateFallbackState(
                reason = reason,
                attemptedVersion = attemptedVersion,
                downloadUrl = downloadUrlFor(attemptedVersion),
                installGuideUrl = INSTALL_GUIDE_URL,
                diagnosticTail = helperLogTail,
            )
        }
    }
}
