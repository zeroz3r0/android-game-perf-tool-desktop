package com.gameperf.desktop.core

/**
 * Orchestrates tool checks and bootstrap for dependencies (adb, ffmpeg).
 */
object DependencyBootstrap {

    /** Tool name and reason it wasn't found. */
    data class MissingTool(
        val toolName: String,
        val reason: MissingReason
    )

    enum class MissingReason {
        /** Tool is not installed anywhere. */
        NOT_FOUND,
        /** Bundled tool available in app resources. */
        BUNDLED_AVAILABLE,
        /** Tool found in UserToolsDir. */
        USER_DIR_AVAILABLE
    }

    /** Progress stages for bootstrap. */
    sealed class BootstrapProgress {
        abstract val stage: BootstrapStage

        data class Downloading(val percent: Float) : BootstrapProgress() {
            override val stage: BootstrapStage = BootstrapStage.DOWNLOADING
        }

        data object Extracting : BootstrapProgress() {
            override val stage: BootstrapStage = BootstrapStage.EXTRACTING
        }

        data object Verifying : BootstrapProgress() {
            override val stage: BootstrapStage = BootstrapStage.VERIFYING
        }

        data object Completed : BootstrapProgress() {
            override val stage: BootstrapStage = BootstrapStage.COMPLETED
        }

        data class Failed(val errorMessage: String) : BootstrapProgress() {
            override val stage: BootstrapStage = BootstrapStage.FAILED
        }
    }

    enum class BootstrapStage {
        DOWNLOADING,
        EXTRACTING,
        VERIFYING,
        COMPLETED,
        FAILED
    }

    /** Official download URLs for tools. */
    val TOOL_URLS = mapOf(
        "adb" to "https://dl.google.com/android/repository/platform-tools-latest.zip",
        "ffmpeg" to "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip"
    )

    /** Known SHA256 hashes (best-effort - may be null for some tools). */
    val TOOL_SHA256 = mapOf(
        "adb" to null, // Google doesn't publish hash
        // ffmpeg hash from gyan.dev - update with actual hash when known
        "ffmpeg" to null
    )

    /**
     * Check for missing dependencies by querying ToolResolver.
     *
     * @return List of missing tools with reasons.
     */
    fun check(): List<MissingTool> {
        val missing = mutableListOf<MissingTool>()

        // Check adb
        if (ToolResolver.find("adb") == null) {
            // Check if bundled in app resources
            val bundledPath = bundledAdbPath()
            if (bundledPath != null) {
                missing.add(MissingTool("adb", MissingReason.BUNDLED_AVAILABLE))
            } else {
                missing.add(MissingTool("adb", MissingReason.NOT_FOUND))
            }
        }

        // Note: ffmpeg is checked on-demand when recording is attempted,
        // not at startup (per the spec).

        return missing
    }

    /**
     * Check for bundled adb in app resources.
     *
     * @return Path to bundled adb, or null if not available.
     */
    private fun bundledAdbPath(): String? {
        return try {
            // Check resources next to the JAR
            val classPath = System.getProperty("java.class.path", "")
            val jarDir = classPath.split(java.io.File.pathSeparator)
                .firstOrNull { it.endsWith(".jar") }
                ?.let { java.io.File(it).parentFile }

            if (jarDir != null) {
                val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
                val adbName = if (isWindows) "adb.exe" else "adb"
                val bundled = java.io.File(jarDir, "tools/$adbName")
                if (bundled.exists()) bundled.absolutePath else null
            } else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Get download URL for a tool.
     *
     * @param toolName Tool name (adb, ffmpeg).
     * @return Download URL, or null if not known.
     */
    fun downloadUrl(toolName: String): String? = TOOL_URLS[toolName]

    /**
     * Get SHA256 hash for a tool.
     *
     * @param toolName Tool name.
     * @return SHA256 hash, or null if not available.
     */
    fun sha256(toolName: String): String? = TOOL_SHA256[toolName]
}
