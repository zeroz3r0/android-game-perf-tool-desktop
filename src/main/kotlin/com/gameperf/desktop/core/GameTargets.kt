package com.gameperf.desktop.core

import com.gameperf.desktop.core.kpi.FrameBudgets
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Per-game performance targets stored in `~/GamePerf Reports/game-targets.json`.
 * User-editable; loaded at report-generation time to render the
 * "Objetivos del juego" section comparing measured KPIs against the
 * per-game expectations.
 *
 * All fields are nullable so a partial catalog entry only renders the
 * KPI cards the user actually cares about — null targets are silently
 * skipped by [com.gameperf.desktop.report.ReportGenerator].
 *
 * @since v5.1.0
 */
@Serializable
data class GameTargets(
    /** Human-readable game name shown in the section title. */
    val displayName: String? = null,

    /** Minimum acceptable average FPS (higher is better). */
    val targetAvgFps: Int? = null,

    /** Minimum acceptable P1 (worst-1%) FPS (higher is better). */
    val targetP1Fps: Int? = null,

    /** Maximum acceptable average frame time in ms (lower is better). */
    val maxAvgFrameTimeMs: Double? = null,

    /** Maximum acceptable skin (case) temperature in °C (lower is better). */
    val maxTempSkinC: Double? = null,

    /** Maximum acceptable die-CPU temperature in °C (lower is better). */
    val maxTempCpuC: Double? = null,

    /** Maximum acceptable peak RAM in MB (lower is better). */
    val maxPeakRamMb: Long? = null,

    /** Maximum acceptable average CPU usage % (lower is better). */
    val maxAvgCpuPct: Int? = null,

    /** Maximum acceptable average FPower in mW/frame (lower is better). */
    val maxFPowerMwFrame: Double? = null,

    /** Maximum acceptable battery drain % across the session (lower is better). */
    val maxBatteryDrainPct: Int? = null,

    /** Free-form notes shown alongside the KPI cards. */
    val notes: String? = null,
)

/**
 * Catalog of per-package [GameTargets] entries.
 *
 * `version` is reserved for future schema migrations; v5.1.0 ships v1.
 * Unknown JSON fields are ignored at parse time (see [GameTargetsCatalogIO]).
 *
 * @since v5.1.0
 */
@Serializable
data class GameTargetsCatalog(
    val version: Int = 1,
    val targets: Map<String, GameTargets> = emptyMap(),
) {
    /** Returns the targets entry for [pkg] or `null` when absent. */
    fun getTargetsFor(pkg: String): GameTargets? = targets[pkg]
}

/**
 * IO + bootstrap for the user-editable game targets catalog.
 *
 * Mirrors the [Settings] pattern (single object, lenient JSON, defensive
 * try/catch in every code path). [targetsFile] is `internal var` so tests
 * can redirect IO to a temp directory — production code never mutates it.
 *
 * Failure-mode contract:
 * - [load] NEVER throws; returns an empty catalog on any IO/parse error.
 * - [save] NEVER throws; logs a warning to stderr on failure.
 * - [ensureBootstrapped] NEVER throws; logs a warning when the parent
 *   directory cannot be created.
 *
 * @since v5.1.0
 */
object GameTargetsCatalogIO {

    /**
     * Default target path: `~/GamePerf Reports/game-targets.json`.
     * `var` (not `val`) so tests can swap it to a temp directory — production
     * code MUST NOT mutate this in normal operation.
     */
    internal var targetsFile: File =
        File(System.getProperty("user.home"), "GamePerf Reports/game-targets.json")

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
        isLenient = true
    }

    /** Casual mobile baseline shipped on first launch for `com.vivastudios.pieceout`. */
    private val PIECE_OUT_DEFAULTS = GameTargets(
        displayName = "Piece Out",
        targetAvgFps = 30,
        targetP1Fps = 25,
        // Reference FrameBudgets.FPS_30_MS (= 1000 / 30 = 33.3) instead of the bare
        // literal so `FrameBudgetsSingleSourceTest` stays green (v4.7 RAG-005 guard).
        maxAvgFrameTimeMs = FrameBudgets.FPS_30_MS,
        maxTempSkinC = 42.0,
        maxTempCpuC = 95.0,
        maxPeakRamMb = 1500L,
        maxAvgCpuPct = 60,
        maxFPowerMwFrame = 65.0,
        maxBatteryDrainPct = 15,
        notes = "Baseline casual 2D — ajusta estos valores según el dispositivo de referencia.",
    )

    /**
     * Load the catalog from disk. Returns an empty catalog when the file
     * does not exist or cannot be parsed (logged warning, never throws).
     */
    fun load(): GameTargetsCatalog = try {
        if (!targetsFile.exists()) {
            GameTargetsCatalog()
        } else {
            val text = targetsFile.readText(Charsets.UTF_8)
            json.decodeFromString(GameTargetsCatalog.serializer(), text)
        }
    } catch (e: Exception) {
        System.err.println("[GamePerf] Failed to load game-targets.json: ${e.message}")
        GameTargetsCatalog()
    }

    /**
     * Persist the catalog to disk. Creates the parent directory if needed.
     * Failures are logged but never thrown (catalog is non-critical).
     */
    fun save(catalog: GameTargetsCatalog) {
        try {
            targetsFile.parentFile?.mkdirs()
            val text = json.encodeToString(GameTargetsCatalog.serializer(), catalog)
            targetsFile.writeText(text, Charsets.UTF_8)
        } catch (e: Exception) {
            System.err.println("[GamePerf] Failed to save game-targets.json: ${e.message}")
        }
    }

    /**
     * Bootstrap the catalog file with a `com.vivastudios.pieceout` template
     * entry on first launch. Idempotent — never overwrites an existing file.
     * Silent on parent-directory failure (logged warning only).
     */
    fun ensureBootstrapped() {
        try {
            if (targetsFile.exists()) return
            val template = GameTargetsCatalog(
                targets = mapOf("com.vivastudios.pieceout" to PIECE_OUT_DEFAULTS),
            )
            save(template)
        } catch (e: Exception) {
            System.err.println("[GamePerf] Failed to bootstrap game-targets.json: ${e.message}")
        }
    }
}
