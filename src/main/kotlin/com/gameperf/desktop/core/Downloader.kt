package com.gameperf.desktop.core

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reusable HTTP download utility extracted from [AutoUpdater.downloadUpdate].
 *
 * Provides:
 * - Buffer size 8192 bytes for efficient streaming
 * - Progress callback with values from 0.0 to 1.0
 * - Temp file handling with automatic cleanup
 * - Error handling with descriptive messages
 *
 * This pattern is battle-tested in AutoUpdater for app updates and is now
 * reused for in-app dependency downloads (adb, ffmpeg).
 */
object Downloader {

    /** Buffer size for streaming downloads — 8KB provides good throughput */
    const val DOWNLOAD_BUFFER_SIZE = 8192

    /**
     * Last error encountered by [download], for debugging.
     */
    @Volatile
    var lastDownloadError: String? = null
        private set

    /**
     * Download a file from [url] to a temporary file.
     *
     * @param url The URL to download from.
     * @param onProgress Callback invoked with progress from 0.0 to 1.0.
     *                   Called once at start (0.0) and once at completion (1.0).
     * @return [Result] containing the downloaded [File], or failure with error message.
     */
    fun download(url: String, onProgress: (Float) -> Unit): Result<File> {
        lastDownloadError = null
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "GamePerfDesktop/${AppVersion.NAME}")
            conn.setRequestProperty("Accept", "application/octet-stream")
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                lastDownloadError = "HTTP $responseCode"
                conn.disconnect()
                return Result.failure(Exception("HTTP $responseCode"))
            }

            val totalSize = conn.contentLengthLong
            val tempFile = File.createTempFile("gameperf-download-", ".tmp")
            tempFile.deleteOnExit()

            conn.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    var downloaded = 0L
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        if (totalSize > 0) {
                            val progress = (downloaded.toFloat() / totalSize).coerceIn(0f, 1f)
                            onProgress(progress)
                        }
                    }
                }
            }
            conn.disconnect()
            onProgress(1f)

            // Sanity check: if the file is suspiciously small, treat as failed download.
            if (tempFile.length() < 1024) {
                lastDownloadError = "Descarga truncada (${tempFile.length()} bytes)"
                tempFile.delete()
                return Result.failure(Exception("Download truncated: ${tempFile.length()} bytes"))
            }

            Result.success(tempFile)
        } catch (e: Exception) {
            lastDownloadError = "${e.javaClass.simpleName}: ${e.message ?: "sin detalle"}"
            Result.failure(e)
        }
    }
}
