package com.gameperf.desktop.core.sharing

import java.awt.Desktop
import java.awt.HeadlessException
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

/**
 * Local-share helper for the "Compartir reporte" feature.
 *
 * Opens the OS file explorer at the folder containing the HTML report and
 * places a Spanish-tuteo description block on the system clipboard so the
 * user can paste it into Slack / Drive / Teams / email along with the file.
 *
 * The block intentionally includes:
 *  - The session name + package + device (so the recipient knows what they
 *    are opening before clicking).
 *  - The report file name (so the file is identifiable in a long list of
 *    uploads on the recipient side).
 *  - A reminder that the HTML is **autocontenido** — the recipient does NOT
 *    need anything else: no video, no JS bundle, no internet, just the .html.
 *
 * This is the **default share path**. It is offline, persistent, costs
 * nothing and never expires. The temp-link upload ([TempShUploader]) is the
 * convenience secondary path for "paste-as-link-in-chat" workflows.
 *
 * ## Pure-ish design (per CLAUDE.md "tests puros sin mocks")
 *
 * The build-the-clipboard-text logic lives in [buildClipboardText] as a
 * pure function — testable without AWT. The AWT side effects are isolated
 * in [shareLocally] behind try/catch so a headless JVM (CI, locked desktop)
 * surfaces a clean [ReportShareResult.Failure] instead of crashing the
 * caller.
 *
 * @since v4.7.1
 */
internal object ReportSharer {

    /**
     * Local-share the [reportFile]. Opens its parent folder + copies the
     * description block to the clipboard.
     *
     * @param reportFile Absolute path to the HTML report.
     * @param sessionName Human name of the session (rename-able, may be empty).
     * @param packageName Android package of the game under test (e.g.
     *   `com.vivastudios.tower.battle`).
     * @param deviceName Marketing name of the device, e.g. "Google Pixel 7a".
     * @return [ReportShareResult.LocalShareResult] on success or a
     *   [ReportShareResult.Failure] with a Spanish-tuteo message.
     */
    fun shareLocally(
        reportFile: File,
        sessionName: String,
        packageName: String,
        deviceName: String,
    ): ReportShareResult {
        if (!reportFile.exists()) {
            return ReportShareResult.Failure(
                ReportShareResult.FailureReason.REPORT_NOT_FOUND,
                "No se encontró el informe HTML. Probablemente fue movido o eliminado.",
            )
        }

        val text = buildClipboardText(
            reportFile = reportFile,
            sessionName = sessionName,
            packageName = packageName,
            deviceName = deviceName,
        )

        val clipboardOk = runCatching {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                ?: error("systemClipboard returned null")
            clipboard.setContents(StringSelection(text), null)
        }.isSuccess

        if (!clipboardOk) {
            return ReportShareResult.Failure(
                ReportShareResult.FailureReason.CLIPBOARD_UNAVAILABLE,
                "No se pudo escribir en el portapapeles. Abre la carpeta a mano " +
                    "para encontrar el informe.",
            )
        }

        // Try to also OPEN the parent folder so the user can drag-and-drop the
        // file straight into Slack/Drive. Failure here is non-fatal — the user
        // still got the clipboard text with the path. We only fail hard if BOTH
        // operations fail (above already gated on clipboard).
        val parent = reportFile.parentFile ?: reportFile.absoluteFile.parentFile
        runCatching {
            if (Desktop.isDesktopSupported()) {
                val desktop = Desktop.getDesktop()
                if (desktop.isSupported(Desktop.Action.OPEN)) {
                    desktop.open(parent)
                }
            }
        }

        return ReportShareResult.LocalShareResult(
            folderPath = parent.absolutePath,
            clipboardText = text,
        )
    }

    /**
     * Build the Spanish-tuteo clipboard description block. Pure function —
     * no AWT, no I/O, no environment lookups, fully testable.
     *
     * Format (intentionally simple — pastes legibly into Slack / Discord /
     * email without rendering surprises across clients):
     *
     * ```
     * Reporte de rendimiento — {sessionName}
     * Paquete: {packageName}
     * Dispositivo: {deviceName}
     *
     * Archivo: {fileName}
     * Carpeta: {folderPath}
     *
     * El informe es un HTML autocontenido (~0,5 MB). Ábrelo en cualquier
     * navegador — no necesita conexión ni archivos extra.
     * ```
     *
     * Edge cases:
     *  - When [sessionName] is blank, the title line falls back to the
     *    package name so the recipient still gets context.
     *  - Empty package / device strings are rendered as "(desconocido)"
     *    to avoid stray dashes / leading colons that look broken.
     */
    fun buildClipboardText(
        reportFile: File,
        sessionName: String,
        packageName: String,
        deviceName: String,
    ): String {
        val title = sessionName.takeIf { it.isNotBlank() }
            ?: packageName.takeIf { it.isNotBlank() }
            ?: "(sesión sin nombre)"
        val pkg = packageName.takeIf { it.isNotBlank() } ?: "(desconocido)"
        val device = deviceName.takeIf { it.isNotBlank() } ?: "(desconocido)"
        val folder = (reportFile.parentFile ?: reportFile.absoluteFile.parentFile).absolutePath

        return buildString {
            append("Reporte de rendimiento — ").append(title).append('\n')
            append("Paquete: ").append(pkg).append('\n')
            append("Dispositivo: ").append(device).append("\n\n")
            append("Archivo: ").append(reportFile.name).append('\n')
            append("Carpeta: ").append(folder).append("\n\n")
            append("El informe es un HTML autocontenido (~0,5 MB). Ábrelo en cualquier ")
            append("navegador — no necesita conexión ni archivos extra.")
        }
    }
}
