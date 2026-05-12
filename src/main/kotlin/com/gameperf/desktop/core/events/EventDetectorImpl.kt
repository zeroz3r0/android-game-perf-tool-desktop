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
    }

    private val _events = MutableStateFlow<List<DetectedEvent>>(emptyList())
    override val events: StateFlow<List<DetectedEvent>> = _events

    private val _warnings = MutableStateFlow<List<String>>(emptyList())
    override val warnings: StateFlow<List<String>> = _warnings

    private var logcatCapture: LogcatCapture? = null
    private var dumpsysPoller: DumpsysPoller? = null
    private var gamePackage: String = ""
    private var lastGameForegroundMs: Long = -1

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
        // Try OPEN first.
        val openMatch = SdkSignatureCatalog.matchOpen(line)
        if (openMatch != null) {
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
        // Otherwise check if this line CLOSES any currently-open event.
        for (entry in openEvents.values.toList()) {
            val sig = SdkSignatureCatalog.ALL.firstOrNull { it.sdk == entry.sdkSource } ?: continue
            val closePattern = SdkSignatureCatalog.matchClose(line, sig)
            if (closePattern != null) {
                tryClose(entry, line.tsMs, closePattern.pattern)
            }
        }
    }

    /** Process a fresh activity-stack snapshot from dumpsys. */
    internal fun handleActivityStack(frames: List<ActivityFrame>) {
        if (frames.isEmpty()) return
        val top = frames.first()
        val now = timeProvider()

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
        val now = timeProvider()
        val sinceForeground = now - lastGameForegroundMs
        if (lastGameForegroundMs > 0 && sinceForeground > FOREGROUND_GUARD_MS) {
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
