package com.gameperf.desktop.core

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

object ToolInstaller {
    const val MIN_FREE_SPACE_MB = 100L

    enum class Stage { DOWNLOADING, EXTRACTING, VERIFYING }

    data class Progress(val stage: Stage, val percent: Float = 0f)

    suspend fun download(url: String, targetDir: File, sha256: String?): Result<File> {
        if (!hasEnoughSpace(MIN_FREE_SPACE_MB, targetDir)) {
            return Result.failure(Exception("Espacio insuficiente."))
        }
        targetDir.mkdirs()

        val downloadResult = Downloader.download(url) { }
        if (downloadResult.isFailure) {
            return Result.failure(downloadResult.exceptionOrNull() ?: Exception("Download failed"))
        }

        val tempFile = downloadResult.getOrThrow()
        return try {
            extractZip(tempFile, targetDir)

            if (sha256 != null) {
                val extractedFiles = targetDir.listFiles()?.flatMap { f ->
                    if (f.isDirectory) f.listFiles()?.toList() ?: emptyList() else listOf(f)
                } ?: emptyList()

                val binary = extractedFiles.firstOrNull { f ->
                    f.extension in listOf("exe", "") || f.nameWithoutExtension in listOf("ffmpeg", "ffprobe", "adb")
                }

                if (binary != null) {
                    val computedHash = computeSha256(binary)
                    if (computedHash != null && !computedHash.equals(sha256, ignoreCase = true)) {
                        targetDir.deleteRecursively()
                        return Result.failure(Exception("SHA256 verification failed."))
                    }
                }
            }

            setExecutable(targetDir)
            Result.success(targetDir)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            tempFile.delete()
        }
    }

    fun hasEnoughSpace(requiredMb: Long, targetDir: File): Boolean {
        return try {
            val freeSpace = targetDir.freeSpace / (1024 * 1024)
            freeSpace >= requiredMb
        } catch (_: Exception) {
            true
        }
    }

    internal fun extractZip(zipFile: File, targetDir: File) {
        // Use ZipInputStream over FileSystems.newFileSystem to avoid the
        // ProviderMismatchException that arises when relativizing a zip-fs
        // path against a default-fs target path. ZipInputStream returns plain
        // String entry names that we can compose with the target File directly.
        val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        java.util.zip.ZipInputStream(java.io.BufferedInputStream(java.io.FileInputStream(zipFile))).use { zis ->
            var entry: java.util.zip.ZipEntry? = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                // Defensive: reject zip-slip entries that try to escape the target dir.
                if (name.contains("..")) {
                    entry = zis.nextEntry
                    continue
                }
                // Normalize separators: zip uses '/' on every platform.
                val normalized = if (isWindows) name.replace('/', java.io.File.separatorChar) else name
                val destFile = java.io.File(targetDir, normalized)
                if (entry.isDirectory) {
                    destFile.mkdirs()
                } else {
                    destFile.parentFile?.mkdirs()
                    java.io.FileOutputStream(destFile).use { fos ->
                        zis.copyTo(fos)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    internal fun computeSha256(file: File): String? {
        if (!file.exists()) return null

        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }

    internal fun setExecutable(dir: File) {
        val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        if (isWindows) return

        dir.listFiles()?.forEach { file ->
            if (file.isFile) {
                val name = file.nameWithoutExtension
                if (name in listOf("ffmpeg", "ffprobe", "adb")) {
                    file.setExecutable(true)
                }
            }
            if (file.isDirectory) {
                setExecutable(file)
            }
        }
    }
}
