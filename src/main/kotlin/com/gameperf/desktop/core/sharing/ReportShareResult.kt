package com.gameperf.desktop.core.sharing

/**
 * Result of a "Compartir reporte" action.
 *
 * Two flavors:
 *  - [LocalShareResult] — opened the reports folder + put share-ready text on
 *    the clipboard for the user to paste into Slack/Drive/Teams/whatever.
 *  - [TempLinkShareResult] — uploaded the HTML to a public ephemeral host
 *    (temp.sh, ~3 day retention) and returned a URL the user can paste as
 *    a link in a chat.
 *
 * @since v4.7.1
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
     * Temp-link share — the HTML was uploaded to a public host and the URL
     * was placed on the clipboard.
     *
     * @property url The public URL the recipient opens in a browser to view
     *   the rendered HTML report. Will look like
     *   `https://temp.sh/XXXXX/informe_...html`.
     * @property retentionDescription Human-readable retention note for the
     *   snackbar (e.g. "Válido ~3 días"). Sourced from the uploader so a
     *   future backend swap can override this without touching call sites.
     */
    data class TempLinkShareResult(
        val url: String,
        val retentionDescription: String,
    ) : ReportShareResult()

    /**
     * Failure mode common to both shares. Always carries a Spanish-tuteo
     * user-facing message ready to drop into a snackbar; never expose raw
     * exception strings to the user.
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

        /** Network error contacting the temp host (timeout, DNS, TLS). */
        UPLOAD_NETWORK_ERROR,

        /** Temp host returned a non-2xx HTTP status. */
        UPLOAD_HTTP_ERROR,

        /** Temp host responded 2xx but the body did not contain a parseable URL. */
        UPLOAD_BAD_RESPONSE,

        /** File too big for the configured upload limit. */
        UPLOAD_FILE_TOO_LARGE,
    }
}
