package com.gameperf.desktop.viewmodel

import com.gameperf.desktop.report.PdfExporter
import com.gameperf.desktop.ui.util.PickerUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v4.1.0 — Manages PDF export lifecycle.
 *
 * Extracted from AppViewModel. Owns ExportStatus flow and the
 * shared runExportPipeline logic.
 */
class ExportDelegate(
    private val scope: CoroutineScope,
) {

    /**
     * Lifecycle of a single PDF export attempt. Drives the ExportBanner composable.
     */
    sealed class ExportStatus {
        object Idle : ExportStatus()
        object InProgress : ExportStatus()
        data class Success(val path: String) : ExportStatus()
        data class Error(
            val message: String,
            val actionUrl: String? = null,
            val actionLabel: String? = null,
        ) : ExportStatus()
    }

    private val _exportStatus = MutableStateFlow<ExportStatus>(ExportStatus.Idle)
    val exportStatus: StateFlow<ExportStatus> = _exportStatus.asStateFlow()

    fun resetExportStatus() {
        _exportStatus.value = ExportStatus.Idle
    }

    fun exportToPdf(htmlPath: String, defaultFileName: String) {
        if (htmlPath.isEmpty()) {
            _exportStatus.value = ExportStatus.Error("No hay informe HTML para exportar.")
            return
        }
        scope.launch {
            _exportStatus.value = ExportStatus.InProgress
            val target: File? = try {
                PickerUtils.pickSaveFile(
                    title = "Guardar informe PDF",
                    defaultName = defaultFileName,
                    extension = "pdf"
                )
            } catch (t: Throwable) {
                _exportStatus.value = ExportStatus.Error("No se pudo abrir el selector: ${t.message}")
                return@launch
            }
            if (target == null) {
                _exportStatus.value = ExportStatus.Idle
                return@launch
            }
            try {
                withContext(Dispatchers.IO) {
                    PdfExporter.exportHtmlToPdf(htmlPath, target)
                }
                _exportStatus.value = ExportStatus.Success(target.absolutePath)
            } catch (e: PdfExporter.PdfExportException) {
                val msg = e.message ?: "Error desconocido al exportar PDF"
                val isNoBrowser = msg.startsWith("No se encontró")
                _exportStatus.value = ExportStatus.Error(
                    message = msg,
                    actionUrl = if (isNoBrowser) "https://www.google.com/chrome/" else null,
                    actionLabel = if (isNoBrowser) "Descargar Chrome" else null,
                )
            } catch (e: Throwable) {
                _exportStatus.value = ExportStatus.Error("Error inesperado: ${e.message}")
            }
        }
    }
}
