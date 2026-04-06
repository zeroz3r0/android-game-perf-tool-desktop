package com.gameperf.desktop.ui.util

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.SwingUtilities
import kotlinx.coroutines.CompletableDeferred

/**
 * Coroutine-friendly wrappers around AWT's native [FileDialog].
 *
 * Why `SwingUtilities.invokeLater` + [CompletableDeferred] instead of
 * `withContext(Dispatchers.Main)`: Compose Desktop does not always bind a reliable Main
 * dispatcher to the AWT EDT when called from a `viewModelScope` coroutine. The pattern
 * here is portable across macOS / Linux / Windows and never blocks the calling thread.
 */
object PickerUtils {

    /**
     * Opens a native "Save As" dialog on the AWT EDT and suspends until the user either
     * confirms a destination or cancels. Returns `null` on cancel.
     *
     * @param title dialog title, e.g. "Guardar informe PDF".
     * @param defaultName suggested filename, e.g. "informe_com_example_game_20260406.pdf".
     * @param extension extension without the leading dot (default "pdf"). Appended to the
     *                  returned file if the user typed a name without it.
     */
    suspend fun pickSaveFile(
        title: String,
        defaultName: String,
        extension: String = "pdf"
    ): File? {
        val deferred = CompletableDeferred<File?>()
        SwingUtilities.invokeLater {
            try {
                val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE)
                dialog.file = defaultName
                dialog.setFilenameFilter { _, name -> name.endsWith(".$extension", ignoreCase = true) }
                dialog.isVisible = true
                val dir = dialog.directory
                val file = dialog.file
                if (dir != null && file != null) {
                    val resolved = if (file.endsWith(".$extension", ignoreCase = true)) file else "$file.$extension"
                    deferred.complete(File(dir, resolved))
                } else {
                    deferred.complete(null)
                }
            } catch (t: Throwable) {
                deferred.completeExceptionally(t)
            }
        }
        return deferred.await()
    }
}
