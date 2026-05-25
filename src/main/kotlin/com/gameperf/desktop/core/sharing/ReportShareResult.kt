package com.gameperf.desktop.core.sharing

/**
 * Result of a "Compartir reporte" action.
 *
 * v5.0.0 — the temp.sh upload path was retired entirely (engram #512). The
 * only sharing flavor left is [LocalShareResult]: open the reports folder
 * + put share-ready text on the clipboard for the user to paste into
 * Slack/Drive/Teams/whatever. For a faster "paste a link" flow the user
 * has the new "Copiar HTML como data URL" button which lives outside this
 * sealed hierarchy ([DataUrlBuilder] + ViewModel-level error handling).
 *
 * @since v4.7.1 (sealed hierarchy)
 * @since v5.0.0 (`TempLinkShareResult` + `UPLOAD_*` reasons removed)
 */
internal sealed class ReportShareResult {

    /**
     * Local share — the reports folder was opened in the OS file explorer and
     * a description block was placed on the clipboard.
     *
     * @property folderPath Absolute path of the folder that was opened (kept
     *   for the snackbar so the user knows where to look).
     * @property clipboardText The text that was placed on the clipboard. Kept
     *   here for tests and for a future "fallback" path that surfaces the
     *   text inline when AWT clipboard is unavailable (headless CI, locked
     *   workstation, etc.).
     */
    data class LocalShareResult(
        val folderPath: String,
        val clipboardText: String,
    ) : ReportShareResult()

    /**
     * Failure mode for the share. Always carries a Spanish-tuteo user-facing
     * message ready to drop into a snackbar; never expose raw exception
     * strings to the user.
     *
     * @property reason Categorical failure reason (for logging + tests).
     * @property userMessage Spanish-tuteo message ready for the snackbar.
     */
    data class Failure(
        val reason: FailureReason,
        val userMessage: String,
    ) : ReportShareResult()

    enum class FailureReason {
        /** [java.io.File] for the report does not exist. */
        REPORT_NOT_FOUND,

        /** The clipboard could not be accessed (headless JVM, locked desktop). */
        CLIPBOARD_UNAVAILABLE,

        /** The file explorer could not be launched (no Desktop support, denied). */
        DESKTOP_BROWSE_UNAVAILABLE,
    }
}
