package com.gameperf.desktop.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persistent user settings for the application.
 *
 * Settings are stored in `~/GamePerf Reports/settings.json` and loaded at startup.
 * Unknown fields are ignored for forward compatibility.
 *
 * @since v4.4.0
 */
@Serializable
data class Settings(
    // ═══ Capture Settings ═══

    /**
     * Enable automatic event detection (ads, IAPs, loading screens).
     *
     * When true (default), [EventDetector] is instantiated at capture start to
     * detect SDK events via logcat and dumpsys. Detected events are used to:
     *  - Filter metrics aggregates (exclude ad render times from game FPS).
     *  - Generate heuristic conclusions about performance issues.
     *  - Render shaded bands on the FPS chart in the report.
     *
     * When false, the capture behaves like pre-v4.4.0: manual markers only,
     * no `#sec-conclusions` section, no dual-view metric cards.
     *
     * Note: toggling this setting mid-session is NOT supported — the value is
     * read once at `startCapture` time.
     */
    val autoEventDetectionEnabled: Boolean = true,

    // ═══ KPI Scoring (internal v1) ═══

    /**
     * Enable the internal KPI scoring framework (Issue #2 Block E).
     *
     * Default OFF (design D5). When false, `KpiScoringFacade.compute` returns
     * `null` so no KPI score is computed and the existing
     * `FinalScoreCalculator` A-F grade is unaffected. When true (or when the
     * JVM system property `gameperf.kpi.internal=true` is set),
     * `KpiScoringFacade.compute` runs the full pipeline and returns a
     * [com.gameperf.desktop.core.kpi.KpiScoreReport].
     *
     * Either signal flipping the flag ON is sufficient — the sysprop is a
     * per-process override for ad-hoc inspection without touching the
     * persisted JSON.
     *
     * @since v4.5 (kpi-scoring internal v1)
     */
    val kpiScoringInternalEnabled: Boolean = false,

    // ═══ Sharing (v4.7.1) ═══

    /**
     * If true, the user already accepted the temp-link share disclaimer and
     * does not want to see it again. When false (default for fresh installs),
     * the disclaimer dialog is shown the first time the user clicks
     * "Enlace temporal (3 días)" so they understand the file will land on a
     * public, anonymous host.
     *
     * Default OFF — the disclaimer MUST surface on first use because the
     * privacy implication (public file hoster, no auth) is non-obvious.
     *
     * @since v4.7.1
     */
    val tempLinkShareDisclaimerAccepted: Boolean = false,
) {
    companion object {
        private val settingsFile: File
            get() = File(System.getProperty("user.home"), "GamePerf Reports/settings.json")

        /** Lenient JSON config for forward compatibility. */
        private val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
            isLenient = true
        }

        /**
         * Load settings from disk. Returns defaults if the file doesn't exist
         * or is malformed.
         */
        fun load(): Settings {
            return try {
                if (!settingsFile.exists()) return Settings()
                val text = settingsFile.readText()
                json.decodeFromString<Settings>(text)
            } catch (e: Exception) {
                System.err.println("[GamePerf] Failed to load settings: ${e.message}")
                Settings()
            }
        }

        /**
         * Save settings to disk. Creates the parent directory if needed.
         */
        fun save(settings: Settings) {
            try {
                settingsFile.parentFile?.mkdirs()
                val text = json.encodeToString(Settings.serializer(), settings)
                settingsFile.writeText(text)
            } catch (e: Exception) {
                System.err.println("[GamePerf] Failed to save settings: ${e.message}")
            }
        }
    }
}
