package com.gameperf.desktop.viewmodel

import com.gameperf.desktop.core.AppVersion
import com.gameperf.desktop.core.AutoUpdater
import com.gameperf.desktop.core.CURRENT_VERSION
import com.gameperf.desktop.core.update.FileUpdateHistoryStore
import com.gameperf.desktop.core.update.UpdateAttempt
import com.gameperf.desktop.core.update.UpdateFallbackState
import com.gameperf.desktop.core.update.UpdateHistoryStore
import com.gameperf.desktop.core.update.UpdateOutcome
import java.io.File
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * v4.1.0 — Manages auto-update check, download, and apply.
 *
 * Extracted from AppViewModel. Owns the update-related StateFlows and
 * the coroutine logic for checking/downloading/applying updates.
 *
 * v4.4.1 additions:
 *   - [updateFallback] — non-null when an update attempt failed terminally
 *     and the fallback panel should render. Supersedes [updateError] for
 *     UAC / watchdog / helper failures (those used to be invisible).
 *   - [historyStore] — every terminal [AutoUpdater.UpdateResult] writes one
 *     [UpdateAttempt] line to `~/GamePerf Reports/updates/history.jsonl`.
 *   - [dismissFallback] — user-driven dismissal of the panel (history kept).
 */
class UpdateDelegate(
    private val scope: CoroutineScope,
    private val onStatusMessage: (String) -> Unit,
    private val historyStore: UpdateHistoryStore = defaultHistoryStore(),
) {

    private val _updateAvailable = MutableStateFlow<AutoUpdater.ReleaseInfo?>(null)
    val updateAvailable: StateFlow<AutoUpdater.ReleaseInfo?> = _updateAvailable

    private val _updateProgress = MutableStateFlow<Float?>(null)
    val updateProgress: StateFlow<Float?> = _updateProgress

    private val _updateError = MutableStateFlow<String?>(null)
    val updateError: StateFlow<String?> = _updateError

    /**
     * v4.4.1 — Non-null when the fallback panel should render. Set by
     * [applyOutcome] from any failed [AutoUpdater.UpdateResult]; reset to
     * null by [dismissFallback] or by a successful outcome.
     *
     * Note on layering vs [updateError]: the legacy [updateError] string
     * survives for the plain banner, but the fallback panel SHOULD be
     * preferred when both are set — the panel carries actionable buttons
     * (manual download + install guide) while [updateError] is a one-line
     * status. The HomeScreen wires this preference in v4.4.1 (B6).
     */
    private val _updateFallback = MutableStateFlow<UpdateFallbackState?>(null)
    val updateFallback: StateFlow<UpdateFallbackState?> = _updateFallback

    fun checkForUpdates() {
        scope.launch(Dispatchers.IO) {
            try {
                val release = AutoUpdater.checkForUpdate()
                if (release != null && AutoUpdater.isNewer(release.version, CURRENT_VERSION)) {
                    _updateAvailable.value = release
                }
            } catch (_: Exception) {
                // Silently ignore — update check is non-critical
            }
        }
    }

    fun downloadAndApplyUpdate() {
        val release = _updateAvailable.value ?: return
        val downloadUrl = release.jarUrl
        if (downloadUrl == null) {
            // No JAR asset available — synthesize a download failure outcome so the
            // fallback panel renders alongside the legacy banner string. Spec E1.
            val message = "No hay JAR disponible para tu plataforma. " +
                "Descarga manualmente desde: ${release.htmlUrl}"
            _updateError.value = message
            applyOutcome(
                result = AutoUpdater.UpdateResult(
                    success = false,
                    message = message,
                    outcome = UpdateOutcome.FailedDownload(httpStatus = null, message = message),
                ),
                attemptedVersion = release.version,
                durationMs = 0L,
            )
            return
        }
        scope.launch(Dispatchers.IO) {
            _updateProgress.value = 0f
            _updateError.value = null
            val startedAtMs = System.currentTimeMillis()
            try {
                val file = AutoUpdater.downloadUpdate(downloadUrl) { progress ->
                    _updateProgress.value = progress
                }
                if (file == null) {
                    val reason = AutoUpdater.lastDownloadError
                    val msg = if (reason.isNullOrBlank()) {
                        "Error al descargar la actualizacion."
                    } else {
                        "Error al descargar: $reason"
                    }
                    _updateError.value = msg
                    _updateProgress.value = null
                    // v4.4.1 (T6.3): production wiring of applyOutcome for the
                    // download failure path. Spec E1.
                    applyOutcome(
                        result = AutoUpdater.UpdateResult(
                            success = false,
                            message = msg,
                            outcome = UpdateOutcome.FailedDownload(httpStatus = null, message = msg),
                        ),
                        attemptedVersion = release.version,
                        durationMs = System.currentTimeMillis() - startedAtMs,
                    )
                    return@launch
                }
                _updateProgress.value = 1f
                delay(500)
                // v4.4.1 (T6.4): kick off a best-effort status ticker so the user
                // sees "Verificando actualización... (Ns / 8s)" while AutoUpdater's
                // 8s post-spawn watchdog polls the helper canary. The ticker is
                // cancelled the moment applyUpdate returns.
                val watchdogTicker: Job = launchWatchdogStatusTicker()
                val result = try {
                    // v4.4.1 (T6.3): thread release.version to applyUpdate so the
                    // staged JAR filename + downstream outcome surface the target
                    // version (spec N1/N2).
                    AutoUpdater.applyUpdate(file, release.version)
                } finally {
                    watchdogTicker.cancel()
                }
                when {
                    // v4.3.8: protected-path Windows install — UAC helper has been
                    // spawned. Show a status message, then exit so the helper can
                    // replace the JAR and relaunch the app under the new version.
                    result.success && result.pendingElevatedExit -> {
                        _updateError.value = null
                        _updateProgress.value = null
                        // v4.4.1 (T6.3): record the success outcome BEFORE exitProcess
                        // so the history.jsonl line is durable across the relaunch.
                        applyOutcome(
                            result = result,
                            attemptedVersion = release.version,
                            durationMs = System.currentTimeMillis() - startedAtMs,
                        )
                        onStatusMessage(
                            "Cerrando GamePerf para aplicar la actualización con permisos de administrador. " +
                                "Volverá a abrir automáticamente."
                        )
                        // Give Compose/Skiko time to release native GL contexts + AWT EDT
                        // BEFORE the JVM dies. v4.6.1: bumped 1500→3000 ms because the
                        // legacy grace combined with the broad helper-side process filter
                        // tripped the UAC helper's 30s abort (bug #474, repro 2026-05-18).
                        delay(GRACE_BEFORE_EXIT_MS)
                        exitProcess(0)
                    }
                    result.success && result.needsManualRestart -> {
                        _updateError.value = null
                        _updateProgress.value = null
                        applyOutcome(
                            result = result,
                            attemptedVersion = release.version,
                            durationMs = System.currentTimeMillis() - startedAtMs,
                        )
                        onStatusMessage(result.message)
                    }
                    result.success -> {
                        // Direct-write success path (FAT_JAR_STANDALONE etc. that
                        // System.exit'd already; this branch is largely defensive).
                        _updateError.value = null
                        _updateProgress.value = null
                        applyOutcome(
                            result = result,
                            attemptedVersion = release.version,
                            durationMs = System.currentTimeMillis() - startedAtMs,
                        )
                    }
                    else -> {
                        _updateError.value = result.message.ifEmpty { "Error al aplicar la actualización" }
                        _updateProgress.value = null
                        // v4.4.1 (T6.3): every failure path (watchdog timeout,
                        // helper crash, unknown) lands in applyOutcome — that's
                        // what mounts the fallback panel + writes history.
                        applyOutcome(
                            result = result,
                            attemptedVersion = release.version,
                            durationMs = System.currentTimeMillis() - startedAtMs,
                        )
                    }
                }
            } catch (e: Exception) {
                val msg = "Error: ${e.message}"
                _updateError.value = msg
                _updateProgress.value = null
                applyOutcome(
                    result = AutoUpdater.UpdateResult(
                        success = false,
                        message = msg,
                        outcome = UpdateOutcome.FailedUnknown(msg),
                    ),
                    attemptedVersion = release.version,
                    durationMs = System.currentTimeMillis() - startedAtMs,
                )
            }
        }
    }

    /**
     * v4.4.1 (T6.4) — Best-effort UI status ticker for the post-spawn watchdog.
     *
     * Emits "Verificando actualización... ({elapsed}s / 8s)" via [onStatusMessage]
     * roughly once per second while the 8s `HelperLogWatcher.awaitCanary` polls.
     * The ticker is cancelled in `finally` once `AutoUpdater.applyUpdate` returns,
     * so successful early canaries (typical \u003c2s) only flash one or two updates.
     *
     * Coupled to ADR-2's 8s default; if the watchdog timeout changes, bump
     * [WATCHDOG_STATUS_MAX_SECONDS] so the displayed denominator stays accurate.
     */
    private fun launchWatchdogStatusTicker(): Job = scope.launch(Dispatchers.IO) {
        for (elapsed in 1..WATCHDOG_STATUS_MAX_SECONDS) {
            delay(WATCHDOG_STATUS_TICK_MS)
            if (!isActive) return@launch
            onStatusMessage(
                "Verificando actualización... (${elapsed}s / ${WATCHDOG_STATUS_MAX_SECONDS}s)"
            )
        }
    }

    fun dismissUpdate() {
        _updateAvailable.value = null
        _updateError.value = null
        _updateProgress.value = null
    }

    /**
     * v4.4.1 — User-initiated dismissal of the fallback panel.
     *
     * Spec scenarios D1 / D2: clears [updateFallback] to null but does NOT
     * truncate or delete `history.jsonl` (the audit trail outlives the panel).
     */
    fun dismissFallback() {
        _updateFallback.value = null
    }

    /**
     * v4.4.1 — Apply a terminal [AutoUpdater.UpdateResult] to the StateFlows
     * + history store.
     *
     * Spec scenarios E1..E4 + D1:
     *   - failed outcome → set [updateFallback] via [UpdateFallbackState.from]
     *   - success outcome → reset [updateFallback] to null
     *   - every outcome → append one [UpdateAttempt] to [historyStore]
     *
     * Backward-compat: a `success=false` result with `outcome=null`
     * (a legacy AutoUpdater path that didn't get migrated) is materialized
     * as [UpdateOutcome.FailedUnknown] using [AutoUpdater.UpdateResult.message]
     * — better to surface the panel than swallow the failure silently.
     *
     * History append is called BEFORE any potential exitProcess so the
     * jsonl line is durable even when the watchdog success path triggers
     * the v4.3.8 1.5s-then-`exitProcess(0)` flow downstream.
     *
     * Public for v4.4.1 unit testability — not called from external code yet
     * (production wires it through [downloadAndApplyUpdate]'s success / failure
     * branches in B5.4).
     */
    fun applyOutcome(
        result: AutoUpdater.UpdateResult,
        attemptedVersion: String,
        durationMs: Long,
        helperLogTail: String? = null,
    ) {
        val effectiveOutcome: UpdateOutcome = result.outcome
            ?: if (result.success) UpdateOutcome.Success
            else UpdateOutcome.FailedUnknown(result.message.ifBlank { "Unknown update failure" })

        // Update the fallback StateFlow first so any observer sees the new state
        // before the history append's I/O completes.
        _updateFallback.value = if (effectiveOutcome is UpdateOutcome.Success) {
            null
        } else {
            UpdateFallbackState.from(
                outcome = effectiveOutcome,
                attemptedVersion = attemptedVersion,
                helperLogTail = helperLogTail,
            )
        }

        historyStore.append(
            UpdateAttempt(
                timestamp = System.currentTimeMillis(),
                fromVersion = AppVersion.NAME,
                toVersion = attemptedVersion,
                outcome = effectiveOutcome,
                durationMs = durationMs,
                errorMessage = if (effectiveOutcome is UpdateOutcome.Success) null
                    else result.message.ifBlank { null },
                helperLogTail = helperLogTail,
            )
        )
    }

    companion object {
        /**
         * Default production [UpdateHistoryStore] backed by
         * `~/GamePerf Reports/updates/history.jsonl`. Mirrors
         * [AutoUpdater.lastUpdateLogPath] sibling location (ADR-1).
         */
        internal fun defaultHistoryStore(): UpdateHistoryStore {
            val file = File(
                System.getProperty("user.home"),
                "GamePerf Reports/updates/history.jsonl",
            )
            return FileUpdateHistoryStore(file)
        }

        /** Watchdog status ticker tick (1s — matches ADR-2 8s budget). */
        private const val WATCHDOG_STATUS_TICK_MS: Long = 1_000L

        /** Maximum elapsed seconds shown in the watchdog status text. */
        private const val WATCHDOG_STATUS_MAX_SECONDS: Int = 8

        /**
         * v4.6.1 — Milliseconds the JVM lingers after surfacing the "Cerrando
         * GamePerf..." status message and BEFORE calling `exitProcess(0)` on
         * the elevated-exit branch.
         *
         * Bumped from 1500 → 3000 because Compose/Skiko cleanup on Windows
         * bundles (native GL contexts, AWT EDT teardown) can take 5-15 s and
         * the v4.6.0 1.5 s grace combined with the helper-side 30 s timeout +
         * broad process filter caused the UAC helper to abort with `exit 1`
         * even on successful UAC consent (bug #474, repro 2026-05-18).
         *
         * Pinned by [com.gameperf.desktop.viewmodel.UpdateDelegateGraceTest]
         * via reflection — do NOT lower below the legacy 1500 ms floor.
         */
        private const val GRACE_BEFORE_EXIT_MS: Long = 3_000L
    }
}
