package com.gameperf.desktop.core.events

import com.gameperf.desktop.core.AdbBridgeApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Default [EventDetector] implementation. Composes [LogcatCapture] +
 * [DumpsysPoller].
 *
 * State machine (per SDK signature):
 *  - WAITING: no open event for this signature key.
 *  - OPEN: open match received, awaiting close. Emits initial [DetectedEvent]
 *    with `endMs=null`.
 *  - CLOSED: close match received OR activity left top of stack OR session
 *    ended via [stop]. Updates the event with `endMs` and re-emits the list.
 *
 * Foreground proximity guard (EVT-008):
 *  - An open match is only accepted if the game's package was on top of the
 *    activity stack within the last [FOREGROUND_GUARD_MS] (default 2000 ms).
 *    This prevents false positives from background ads, system overlays,
 *    push-notification-driven SDK chatter, etc.
 *  - Special case: when the detector starts, the game is presumed to be on
 *    top (the user just launched a capture). We seed [lastGameForegroundMs]
 *    with the start time so early opens are not all rejected.
 *
 * Event cap (EVT-009):
 *  - After [MAX_EVENTS] (500) detected events, further opens are dropped and
 *    a "histogram-fallback" warning is added. The report renders a histogram
 *    aggregation instead of a per-event listing.
 *
 * Logcat gap (EVT-007):
 *  - When [LogcatCapture] reports a gap, all currently-open events are
 *    downgraded to [Confidence.LOW] because an SDK close signal could have
 *    fired during the silent window without us seeing it.
 *
 * Thread-safety:
 *  - [LogcatCapture] callbacks fire on `Dispatchers.IO`.
 *  - [DumpsysPoller] callbacks fire on `Dispatchers.IO`.
 *  - The state-machine handlers ([handleLogLine], [handleActivityStack],
 *    [handleGap]) are NOT internally synchronised. The current design
 *    accepts that two callbacks could interleave; the worst case is a
 *    duplicate event or a missed close, both of which the report tolerates.
 *    A heavier mutex would only matter for stress-test workloads.
 *
 * @since v4.4.0
 */
internal class EventDetectorImpl(
    private val bridge: AdbBridgeApi,
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
) : EventDetector {

    companion object {
        /** Window inside which an SDK open signal is accepted as
         *  attributable to the game in foreground. See EVT-008. */
        const val FOREGROUND_GUARD_MS = 2_000L

        /** Hard cap on detected events per session. See EVT-009. */
        const val MAX_EVENTS = 500

        /** Sprint 1 — minimum elapsed time between two APP_STARTUP
         *  emissions before a PID restart is allowed to fire a second
         *  startup event. Below this window the change is treated as
         *  PID flicker (spec ESC-START-003 scenario 2). */
        const val APP_STARTUP_DEBOUNCE_MS = 10_000L

        /** Sprint 2a — per-session cap on SCREEN_TRANSITION events. In
         *  addition to the global EVT-009 [MAX_EVENTS] ceiling, screen
         *  transitions are sub-capped because chatty multi-Activity games
         *  can otherwise saturate the report with navigation noise. Spec
         *  ESC-SCRN-003. */
        const val MAX_SCREEN_TRANSITIONS = 100

        /** Logcat atom emitted by Android's `ActivityManager` when a new
         *  process is started. Pattern is intentionally narrow so foreign
         *  components mentioning "Start proc" elsewhere are rejected by
         *  the tag-allowlist check in [handleLogLine]. */
        private val AM_PROC_START_RE: Regex = Regex("""\bStart proc\b.*?:(\S+?)/""")
    }

    private val _events = MutableStateFlow<List<DetectedEvent>>(emptyList())
    override val events: StateFlow<List<DetectedEvent>> = _events

    private val _warnings = MutableStateFlow<List<String>>(emptyList())
    override val warnings: StateFlow<List<String>> = _warnings

    private var logcatCapture: LogcatCapture? = null
    private var dumpsysPoller: DumpsysPoller? = null
    private var gamePackage: String = ""
    private var lastGameForegroundMs: Long = -1

    // ───────────────────────── Sprint 1 state ─────────────────────────
    //
    // APP_STARTUP tracking:
    //  - `lastAppStartupMs` records the wall-clock at which the most recent
    //    APP_STARTUP event was emitted (cold start OR PID restart). Used
    //    by `checkPidRestart` to debounce rapid PID flicker.
    //  - `lastGamePid` is the most recent PID observed by the per-tick
    //    `checkPidRestart` API. Starts unset (`null`); the first call
    //    establishes the baseline without emitting.
    private var lastAppStartupMs: Long = -1L
    private var lastGamePid: Int? = null

    // ───────────────────────── Sprint 2a state ────────────────────────
    //
    // SCREEN_TRANSITION tracking:
    //  - `lastTopCmp` is the most recent dumpsys top-component observed
    //    inside the game package. `null` until the first in-package frame
    //    fires the cold-start sensor; from then on every cmp change
    //    against this value emits a SCREEN_TRANSITION until the per-type
    //    cap is hit.
    //  - `openScreenTransitionId` carries the `id` of the currently-open
    //    SCREEN_TRANSITION (if any). Each new transition closes the
    //    previous one's `endMs` at the moment the new one opens, so the
    //    report timeline shows back-to-back screens without holes.
    private var lastTopCmp: String? = null
    private var openScreenTransitionId: String? = null

    /**
     * Open events keyed by a stable per-source signature key:
     *  - logcat opens use `"<sdk>:<tag>:<openPattern>"`
     *  - dumpsys opens use `"<sdk>:activity:<cmp>"`
     * Keeping these distinct lets a logcat-detected ad and an
     * activity-detected reopen of the same SDK be tracked independently.
     */
    private val openEvents = mutableMapOf<String, DetectedEvent>()

    override fun start(deviceId: String, gamePackage: String, scope: CoroutineScope) {
        this.gamePackage = gamePackage
        _events.value = emptyList()
        _warnings.value = emptyList()
        openEvents.clear()
        lastAppStartupMs = -1L
        lastGamePid = null
        lastTopCmp = null
        openScreenTransitionId = null
        // Game is presumed on top at capture start; seed the guard so early
        // opens within the first 2s are not all rejected as "background".
        lastGameForegroundMs = timeProvider()

        val capture = LogcatCapture(
            bridge = bridge,
            onLine = ::handleLogLine,
            onGap = ::handleGap,
        )
        val poller = DumpsysPoller(
            bridge = bridge,
            onActivityStack = ::handleActivityStack,
        )

        val tagArgs = SdkSignatureCatalog.logcatTagArgs()
        val started = capture.start(deviceId, tagArgs, scope)
        if (!started) {
            addWarning(
                "No se pudo iniciar la captura de logcat; la detección automática " +
                    "operará solo con dumpsys (cobertura reducida)."
            )
        }
        poller.start(deviceId, scope)

        logcatCapture = capture
        dumpsysPoller = poller
    }

    override fun stop() {
        logcatCapture?.stop()
        dumpsysPoller?.stop()
        // Force-close any still-open events with endInferred=true so the
        // report can disclose that the boundary was synthesized.
        if (openEvents.isNotEmpty()) {
            val now = timeProvider()
            val closed = openEvents.values.map { ev ->
                ev.copy(endMs = now, endInferred = true)
            }
            replaceEvents(closed)
            openEvents.clear()
        }
        logcatCapture = null
        dumpsysPoller = null
    }

    // ───────────────────────── State machine handlers ─────────────────────────
    //
    // These are `internal` so unit tests can drive the state machine directly
    // with synthetic [LogLine] / [ActivityFrame] sequences without spawning a
    // real [LogcatCapture] or [DumpsysPoller].

    /** Process a single parsed logcat line. */
    internal fun handleLogLine(line: LogLine) {
        // Sprint 1 — APP_STARTUP via `am_proc_start` atom (logcat fast path).
        //
        // ActivityManager polls the activity stack at 1 Hz which can miss
        // a launch that happens inside the first second of capture; the
        // `am_proc_start` line is broadcast immediately by AMS when the
        // game process is forked, so we treat it as the earliest available
        // cold-start signal and emit APP_STARTUP synchronously.
        //
        // The pattern is tag-locked to `ActivityManager` to avoid foreign
        // components stamping `"Start proc"` as part of unrelated logging.
        if (line.tag.equals("ActivityManager", ignoreCase = true) &&
            lastAppStartupMs < 0 &&
            gamePackage.isNotEmpty()
        ) {
            val m = AM_PROC_START_RE.find(line.msg)
            if (m != null && m.groupValues[1] == gamePackage) {
                emitAppStartup(line.tsMs, restart = false, source = "logcat")
                // Do NOT return — the same line could in theory also match
                // an `am_anr` open pattern (it cannot in practice, but
                // staying defensive keeps the state machine composable).
            }
        }

        // Try OPEN first.
        val openMatch = SdkSignatureCatalog.matchOpen(line)
        if (openMatch != null) {
            // Sprint 2b — INTERSTITIAL → REWARDED_VIDEO upgrade-before-open
            // (spec ESC-REW-002). If the new match resolves to REWARDED and
            // an open event of the SAME SDK is currently INTERSTITIAL, we
            // upgrade it in-place instead of opening a parallel rewarded
            // event. Without this short-circuit the activity-class path
            // (which opens AdMob/IS/AppLovin/Meta as INTERSTITIAL via
            // `defaultType`) would coexist with a duplicate logcat-keyed
            // REWARDED event, polluting the report.
            if (openMatch.resolvedType == EventType.REWARDED_VIDEO) {
                val openInterstitial = openEvents.values.firstOrNull {
                    it.sdkSource == openMatch.sig.sdk && it.type == EventType.INTERSTITIAL
                }
                if (openInterstitial != null) {
                    upgradeEventType(openInterstitial, EventType.REWARDED_VIDEO, line.tsMs)
                    return
                }
            }
            tryOpen(
                sig = openMatch.sig,
                resolvedType = openMatch.resolvedType,
                signatureMatched = openMatch.pattern.pattern,
                startMs = line.tsMs,
                tag = line.tag,
                source = "logcat",
            )
            return
        }
        // Otherwise check if this line CLOSES any currently-open event,
        // or — Sprint 2b — UPGRADES an INTERSTITIAL to REWARDED_VIDEO.
        for (entry in openEvents.values.toList()) {
            val sig = SdkSignatureCatalog.ALL.firstOrNull { it.sdk == entry.sdkSource } ?: continue
            val closePattern = SdkSignatureCatalog.matchClose(line, sig)
            if (closePattern != null) {
                tryClose(entry, line.tsMs, closePattern.pattern)
                continue
            }
            // Sprint 2b — INTERSTITIAL → REWARDED upgrade (spec ESC-REW-002).
            //
            // Activity-class path opens as `sig.defaultType` (typically
            // INTERSTITIAL for AdMob/IS/AppLovin/Meta). If a rewarded
            // callback for the SAME SDK fires while the event is still
            // open, the event is upgraded in-place — same id, same key,
            // only `type` and metadata change. The downgrade direction
            // (REWARDED → INTERSTITIAL) is deliberately NOT supported
            // because once a rewarded callback has fired the event is
            // definitively a rewarded ad, even if generic interstitial
            // callbacks fire afterwards on the same tag.
            if (entry.type == EventType.INTERSTITIAL &&
                sig.logcatTags.any { it.equals(line.tag, ignoreCase = true) }
            ) {
                val rewardedMatch = sig.openPatterns.firstOrNull { (pattern, type) ->
                    type == EventType.REWARDED_VIDEO && pattern.containsMatchIn(line.msg)
                }
                if (rewardedMatch != null) {
                    upgradeEventType(entry, EventType.REWARDED_VIDEO, line.tsMs)
                }
            }
        }
    }

    /**
     * Sprint 2b — upgrade an open event's [DetectedEvent.type] in-place,
     * preserving the same id (so consumers tracking by id see a mutation,
     * not a delete+insert) and the same key in [openEvents]. Adds
     * `upgradedFrom`/`upgradedAtMs` to metadata so the report can disclose
     * the reclassification.
     *
     * Spec ESC-REW-002 — directional: only used for INTERSTITIAL →
     * REWARDED_VIDEO. Downgrade is not supported.
     */
    private fun upgradeEventType(open: DetectedEvent, newType: EventType, atMs: Long) {
        val updated = open.copy(
            type = newType,
            metadata = open.metadata + mapOf(
                "upgradedFrom" to open.type.name,
                "upgradedAtMs" to atMs.toString(),
            ),
        )
        // Replace in the published events list by id.
        val current = _events.value.toMutableList()
        val idx = current.indexOfFirst { it.id == open.id }
        if (idx >= 0) {
            current[idx] = updated
            _events.value = current
        }
        // Update the openEvents map entry that points at this id (key stays
        // the same — it's not type-derived).
        val keyToUpdate = openEvents.entries.firstOrNull { it.value.id == open.id }?.key
        if (keyToUpdate != null) {
            openEvents[keyToUpdate] = updated
        }
    }

    /** Process a fresh activity-stack snapshot from dumpsys. */
    internal fun handleActivityStack(frames: List<ActivityFrame>) {
        if (frames.isEmpty()) return
        val top = frames.first()
        val now = timeProvider()

        // Sprint 1 — cold-start sensor (spec ESC-START-001).
        //
        // Precondition `lastGameForegroundMs == -1L` is the explicit spec
        // contract: the sentinel value is only present BEFORE any frame
        // has been observed (production seeds it from `start()`; tests
        // intentionally skip the seed via `newColdDetectorAtTime`). Once
        // a frame fires the sensor we also stamp `lastAppStartupMs` so the
        // logcat fast path skips a duplicate emission.
        //
        // The check runs BEFORE the SDK-activity match so the cold-start
        // event lands first in chronological order — important for the
        // report timeline ordering.
        if (isColdStartCandidate(top.cmp)) {
            emitAppStartup(now, restart = false, source = "dumpsys-firstforeground")
        }

        // Step 1 — try activity-level SDK detection FIRST. Many ad SDKs
        // host their activity inside the game's own process (cmp prefix is
        // the game package), so we cannot use "cmp starts with gamePackage"
        // as a shortcut to "game is on top in the gameplay sense".
        // ProGuard-stripped builds rely on this path because logcat opens
        // get optimised out.
        val sig = SdkSignatureCatalog.matchActivity(top.cmp)
        if (sig != null) {
            val key = "${sig.sdk}:activity:${top.cmp}"
            if (!openEvents.containsKey(key)) {
                tryOpenActivity(sig, top.cmp, now, key)
            }
        } else if (
            top.cmp.startsWith("$gamePackage/") || top.cmp.contains("/$gamePackage")
        ) {
            // Step 2 — no SDK activity matched and the game's package owns
            // the top frame, so the user is in normal gameplay. Refresh the
            // foreground timestamp so logcat-driven opens (which fire from
            // INSIDE the game process, e.g. AdMob preload) pass the guard.
            lastGameForegroundMs = now

            // Sprint 2a — SCREEN_TRANSITION (spec ESC-SCRN-001).
            //
            // The branch precondition guarantees the new top component
            // belongs to the game AND is NOT an SDK-owned activity (the
            // earlier `matchActivity != null` arm short-circuits those).
            // A change against `lastTopCmp` therefore represents in-game
            // navigation. The previous SCREEN_TRANSITION (if any) is
            // closed at `now` so the report renders contiguous bands; the
            // new one then opens with `endMs=null`.
            //
            // ESC-SCRN-002 (single-activity Unity games) is handled by
            // the `lastTopCmp != top.cmp` guard: if the cmp never changes
            // we never enter this block.
            //
            // ESC-SCRN-003 (per-type cap): once
            // [MAX_SCREEN_TRANSITIONS] transitions have been emitted, we
            // close the in-flight one but skip emitting a new one and
            // surface a Spanish warning. `lastTopCmp` still updates so
            // we never re-emit the same transition over and over.
            val prev = lastTopCmp
            if (prev != null && prev != top.cmp) {
                if (screenTransitionCount() >= MAX_SCREEN_TRANSITIONS) {
                    ensureWarning(
                        "Se alcanzó el tope de $MAX_SCREEN_TRANSITIONS cambios de " +
                            "pantalla — los siguientes se omiten para no inundar el reporte."
                    )
                    closeOpenScreenTransition(now)
                } else {
                    closeOpenScreenTransition(now)
                    emitScreenTransition(prev, top.cmp, now)
                }
            }
            lastTopCmp = top.cmp
        }

        // Step 3 — close any activity-keyed open event whose tracked
        // component is no longer anywhere in the visible stack.
        for ((key, ev) in openEvents.toMap()) {
            val activityPrefix = "${ev.sdkSource}:activity:"
            if (key.startsWith(activityPrefix)) {
                val trackedCmp = key.removePrefix(activityPrefix)
                val stillVisible = frames.any { it.cmp == trackedCmp }
                if (!stillVisible) {
                    tryClose(ev, timeProvider(), "activity-left-stack")
                }
            }
        }
    }

    /** Logcat reported a silent period exceeding the gap threshold. */
    internal fun handleGap(gapMs: Long) {
        addWarning(
            "Brecha de logcat de ${gapMs / 1000}s detectada — los eventos cercanos " +
                "se marcan con confianza baja."
        )
        if (openEvents.isEmpty()) return
        // Downgrade currently-open events to LOW confidence.
        val downgraded = openEvents.mapValues { (_, ev) ->
            if (ev.confidence == Confidence.LOW) ev else ev.copy(confidence = Confidence.LOW)
        }
        openEvents.clear()
        openEvents.putAll(downgraded)
        replaceEvents(downgraded.values.toList())
    }

    // ───────────────────────── Internal helpers ─────────────────────────

    private fun tryOpen(
        sig: SdkSignature,
        resolvedType: EventType,
        signatureMatched: String,
        startMs: Long,
        tag: String,
        source: String,
    ) {
        // Foreground guard — reject opens that look like background SDK noise.
        //
        // Sprint 1 — ANR bypass (spec ESC-ANR-001): ANR events are emitted
        // even when the game looks backgrounded because an ANR can fire
        // while the process is unresponsive yet still owns the top of the
        // activity stack — the foreground timestamp may simply not have
        // refreshed because the polling thread is also frozen.
        val now = timeProvider()
        val sinceForeground = now - lastGameForegroundMs
        if (resolvedType != EventType.ANR &&
            lastGameForegroundMs > 0 &&
            sinceForeground > FOREGROUND_GUARD_MS
        ) {
            return
        }
        if (totalEventCount() >= MAX_EVENTS) {
            ensureWarning(
                "Se alcanzó el tope de $MAX_EVENTS eventos detectados; el reporte " +
                    "usará un histograma agregado."
            )
            return
        }
        val key = "${sig.sdk}:$tag:$signatureMatched"
        if (openEvents.containsKey(key)) return  // already tracking same SDK+pattern

        val event = DetectedEvent(
            type = resolvedType,
            sdkSource = sig.sdk,
            startMs = startMs,
            endMs = null,
            confidence = Confidence.HIGH,
            signatureMatched = signatureMatched,
            metadata = mapOf("source" to source, "tag" to tag),
        )
        openEvents[key] = event
        appendEvent(event)
    }

    private fun tryOpenActivity(sig: SdkSignature, cmp: String, nowMs: Long, key: String) {
        if (totalEventCount() >= MAX_EVENTS) {
            ensureWarning(
                "Se alcanzó el tope de $MAX_EVENTS eventos detectados; el reporte " +
                    "usará un histograma agregado."
            )
            return
        }
        val event = DetectedEvent(
            // Activity-level matches carry no per-pattern discriminator, so the
            // signature's defaultType is the correct fallback (Sprint 0 shape).
            type = sig.defaultType,
            sdkSource = sig.sdk,
            startMs = nowMs,
            endMs = null,
            confidence = Confidence.MEDIUM,
            signatureMatched = "activity:$cmp",
            metadata = mapOf("source" to "dumpsys", "cmp" to cmp),
        )
        openEvents[key] = event
        appendEvent(event)
    }

    /**
     * Sprint 1 — true when the dumpsys top-component is a credible
     * cold-start signal: no foreground frame has been observed yet
     * ([lastGameForegroundMs] is still the sentinel), no APP_STARTUP has
     * already been emitted via the logcat fast path, the game package is
     * known, and the top component belongs to that package.
     */
    private fun isColdStartCandidate(topCmp: String): Boolean {
        if (lastGameForegroundMs != -1L) return false
        if (lastAppStartupMs >= 0) return false
        if (gamePackage.isEmpty()) return false
        return topCmp.startsWith("$gamePackage/") || topCmp.contains("/$gamePackage")
    }

    /**
     * Sprint 1 — emit a synthetic APP_STARTUP event from the cold-start
     * sensor or the PID-restart watcher.
     *
     * Records the wall-clock in [lastAppStartupMs] so the dumpsys path
     * does not re-emit, and so the PID watcher can debounce subsequent
     * restarts within [APP_STARTUP_DEBOUNCE_MS].
     *
     * @param now wall-clock timestamp to use as `startMs` of the event.
     * @param restart `true` when the emission is triggered by
     *   [checkPidRestart] (process re-launched mid-session); the event's
     *   metadata gains `restart=true` so the report can disclose the
     *   discontinuity.
     * @param source value for `metadata["source"]` —
     *   `"dumpsys-firstforeground"` for the cold-start sensor,
     *   `"logcat"` for the `am_proc_start` path, `"pid-restart"` for the
     *   PID watcher.
     */
    private fun emitAppStartup(now: Long, restart: Boolean, source: String) {
        if (totalEventCount() >= MAX_EVENTS) {
            ensureWarning(
                "Se alcanzó el tope de $MAX_EVENTS eventos detectados; el reporte " +
                    "usará un histograma agregado."
            )
            return
        }
        val metadata = mutableMapOf("source" to source)
        if (restart) metadata["restart"] = "true"
        val event = DetectedEvent(
            type = EventType.APP_STARTUP,
            sdkSource = "System Startup",
            startMs = now,
            endMs = null,
            confidence = Confidence.MEDIUM,
            signatureMatched = if (restart) "pid-restart" else "first-foreground",
            metadata = metadata,
        )
        // APP_STARTUP is emitted as a "point-in-time" event for now (no
        // close pattern). Future Sprint 1.1.5+ work will add the 10s/30s
        // auto-close + SDK_INIT-driven close. For the current sprint we
        // simply publish it and leave endMs=null until session stop, where
        // EVT-006 will synthesize endMs+endInferred=true. The event is NOT
        // tracked in openEvents because there is no close pattern to fire.
        appendEvent(event)
        lastAppStartupMs = now
    }

    // ───────────────────────── Sprint 2a helpers ─────────────────────────

    /**
     * Sprint 2a — count of SCREEN_TRANSITION events currently published.
     * Used by [handleActivityStack] to enforce [MAX_SCREEN_TRANSITIONS]
     * (spec ESC-SCRN-003).
     */
    private fun screenTransitionCount(): Int =
        _events.value.count { it.type == EventType.SCREEN_TRANSITION }

    /**
     * Sprint 2a — close the in-flight SCREEN_TRANSITION (if any) by
     * stamping `endMs=now`. The published events list is mutated in
     * place via the replace-by-id pattern used elsewhere in this file.
     * Idempotent: a no-op when no transition is open.
     */
    private fun closeOpenScreenTransition(now: Long) {
        val id = openScreenTransitionId ?: return
        val current = _events.value.toMutableList()
        val idx = current.indexOfFirst { it.id == id }
        if (idx >= 0) {
            current[idx] = current[idx].copy(endMs = now)
            _events.value = current
        }
        openScreenTransitionId = null
    }

    /**
     * Sprint 2a — emit a new SCREEN_TRANSITION event. The transition is
     * left open (`endMs=null`) until the NEXT cmp change closes it via
     * [closeOpenScreenTransition], or until `stop()` synthesises a close
     * with `endInferred=true`.
     *
     * @param fromCmp the previous top component (preserved verbatim in
     *   metadata `from` — including package prefix, useful for debugging).
     * @param toCmp   the new top component (metadata `to`).
     * @param now     wall-clock timestamp used as `startMs`.
     */
    private fun emitScreenTransition(fromCmp: String, toCmp: String, now: Long) {
        if (totalEventCount() >= MAX_EVENTS) {
            ensureWarning(
                "Se alcanzó el tope de $MAX_EVENTS eventos detectados; el reporte " +
                    "usará un histograma agregado."
            )
            return
        }
        val event = DetectedEvent(
            type = EventType.SCREEN_TRANSITION,
            sdkSource = "Game Navigation",
            startMs = now,
            endMs = null,
            confidence = Confidence.MEDIUM,
            signatureMatched = "dumpsys-cmp-change",
            metadata = mapOf(
                "source" to "dumpsys-cmp-change",
                "from" to fromCmp,
                "to" to toCmp,
            ),
        )
        openScreenTransitionId = event.id
        appendEvent(event)
    }

    /**
     * Sprint 1 — PID-restart watcher (spec ESC-START-003).
     *
     * Called by the viewmodel layer once per capture tick with the current
     * game process PID (or `null` if the process is not running). The first
     * call establishes the baseline silently. A subsequent call with a
     * different non-null PID emits a new APP_STARTUP event with
     * `metadata["restart"]="true"` PROVIDED the previous APP_STARTUP fired
     * more than [APP_STARTUP_DEBOUNCE_MS] ago — rapid flicker is suppressed
     * to avoid storm-emission when ADB briefly loses sight of the process.
     *
     * @param currentPid the game process PID this tick. `null` is treated
     *   as "process gone but coming back" and does NOT update the baseline,
     *   so the next non-null reading can still trigger a restart event.
     */
    fun checkPidRestart(currentPid: Int?) {
        if (currentPid == null) return
        val previous = lastGamePid
        if (previous == null) {
            // First observation — establish baseline silently.
            lastGamePid = currentPid
            return
        }
        if (previous == currentPid) return  // unchanged, no signal

        val now = timeProvider()
        if (lastAppStartupMs >= 0 && (now - lastAppStartupMs) <= APP_STARTUP_DEBOUNCE_MS) {
            // Flicker debounce — record a warning but do not duplicate
            // APP_STARTUP. The baseline still updates so a subsequent
            // stable change CAN fire.
            ensureWarning(
                "Reinicio rápido del proceso del juego detectado en t=${now}ms " +
                    "(menos de ${APP_STARTUP_DEBOUNCE_MS / 1000}s desde el último inicio) — " +
                    "se omite la emisión duplicada."
            )
            lastGamePid = currentPid
            return
        }
        emitAppStartup(now, restart = true, source = "pid-restart")
        lastGamePid = currentPid
    }

    private fun tryClose(open: DetectedEvent, endMs: Long, signatureMatched: String) {
        val updated = open.copy(
            endMs = endMs,
            signatureMatched = "${open.signatureMatched}|close:$signatureMatched",
        )
        // Replace in the published events list by id.
        val current = _events.value.toMutableList()
        val idx = current.indexOfFirst { it.id == open.id }
        if (idx >= 0) {
            current[idx] = updated
            _events.value = current
        }
        // Drop from openEvents (any key whose value carries this id).
        val keyToRemove = openEvents.entries.firstOrNull { it.value.id == open.id }?.key
        if (keyToRemove != null) openEvents.remove(keyToRemove)
    }

    private fun appendEvent(event: DetectedEvent) {
        _events.value = _events.value + event
    }

    private fun replaceEvents(updated: List<DetectedEvent>) {
        val current = _events.value.toMutableList()
        for (u in updated) {
            val idx = current.indexOfFirst { it.id == u.id }
            if (idx >= 0) current[idx] = u else current.add(u)
        }
        _events.value = current
    }

    private fun totalEventCount(): Int = _events.value.size

    private fun addWarning(msg: String) {
        _warnings.value = _warnings.value + msg
    }

    private fun ensureWarning(msg: String) {
        if (_warnings.value.contains(msg)) return
        _warnings.value = _warnings.value + msg
    }

    // ───────────────────────── Test hooks ─────────────────────────

    /**
     * Test-only: seed the foreground-guard timestamp directly. Production
     * code never calls this — [start] and [handleActivityStack] manage it.
     */
    internal fun setLastGameForegroundForTest(ms: Long) {
        lastGameForegroundMs = ms
    }

    /**
     * Test-only: seed the game package without spinning up the real
     * [LogcatCapture] / [DumpsysPoller]. Mirrors what [start] would set.
     */
    internal fun setGamePackageForTest(pkg: String) {
        this.gamePackage = pkg
    }

    /**
     * Test-only: read the count of currently-open events without exposing
     * the internal map.
     */
    internal fun openEventCountForTest(): Int = openEvents.size

    /**
     * Test-only: force a synthetic OPEN with a unique key so cap-related
     * tests can drive the state machine past [MAX_EVENTS] without depending
     * on the catalog's pattern variety.
     */
    internal fun forceOpenForTest(uniqueKey: String) {
        if (totalEventCount() >= MAX_EVENTS) {
            ensureWarning(
                "Se alcanzó el tope de $MAX_EVENTS eventos detectados; el reporte " +
                    "usará un histograma agregado."
            )
            return
        }
        val event = DetectedEvent(
            type = EventType.INTERSTITIAL,
            sdkSource = "TestSdk",
            startMs = timeProvider(),
            endMs = null,
            confidence = Confidence.HIGH,
            signatureMatched = uniqueKey,
            metadata = mapOf("source" to "test"),
        )
        openEvents[uniqueKey] = event
        appendEvent(event)
    }
}
