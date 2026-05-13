# Design: event-segmentation-coverage

> NOTE: Orchestrator CWD = `firebase-remote-config-sync` (CWD basename bug). Artifact belongs to project `android-game-perf-tool-desktop`. Engram topic_key `sdd/event-segmentation-coverage/design`.

Aligns with proposal + spec ESC-001..ESC-CATALOG-002. Mirrors the `gpu-usage-percent` design pattern: pure data + pure parsing + thin bridge wiring + report rendering, all with strict TDD red→green.

---

## 1. Architecture overview

```
                         ┌─────────────────────────────────────────────┐
                         │ SdkSignatureCatalog (object) — SoT          │
                         │ ALL: List<SdkSignature>                     │
                         │ - 9 existing (migrated to openPatterns)     │
                         │ - 5 new (Firebase, AppMeasurement, ANR,     │
                         │   GamePerf, Quest VR, In-App Review)        │
                         └────────────────┬────────────────────────────┘
                                          │
                                          │ Sprint 0 refactor
                                          ▼
   SdkSignature (data class)
     sdk: String
     defaultType: EventType                     ◄── NEW (replaces type)
     activityClasses: List<String>
     logcatTags: List<String>
     openPatterns: List<Pair<Regex, EventType>> ◄── NEW shape
     closePatterns: List<Regex>

   matchOpen(line) → MatchResult?
     for sig in ALL where line.tag in sig.logcatTags:
       for (pattern, type) in sig.openPatterns:
         if pattern.containsMatchIn(line.msg):
           return MatchResult(sig, pattern, resolvedType=type)
     return null

   matchActivity(cmp) → SdkSignature?
     same as today; resolved type at call site = sig.defaultType

                                          │
                                          ▼
   EventDetectorImpl
     handleLogLine(line):
       MatchResult? open = catalog.matchOpen(line)
       if open != null:
         resolvedType = applyPostStartupGate(open.resolvedType, open.sig, now)  ◄── NEW Sprint 1 gate
         tryOpen(open.sig, open.pattern.pattern, line.tsMs, line.tag, resolvedType, source="logcat")
       else:
         for entry in openEvents:
           sig = ALL.firstOrNull { it.sdk == entry.sdkSource } ?: continue
           closePattern = catalog.matchClose(line, sig)
           if closePattern != null:
             tryClose(entry, line.tsMs, closePattern.pattern)
           // Sprint 2b reclassification:
           rewardedMatch = sig.openPatterns.find { it.first.containsMatchIn(line.msg) && it.second == REWARDED_VIDEO }
           if rewardedMatch != null AND entry.type == INTERSTITIAL:
             upgradeEventType(entry, REWARDED_VIDEO, "upgradedAtMs=${line.tsMs}")

     handleActivityStack(frames):
       top = frames.first()
       now = timeProvider()

       // Sprint 1 — cold-start sensor
       if lastGameForegroundMs == -1L AND top.cmp.startsWith("$gamePackage/"):
         emitAppStartup(now)
         lastGameForegroundMs = now

       sig = catalog.matchActivity(top.cmp)
       if sig != null:
         tryOpenActivity(sig, top.cmp, now, key, resolvedType=sig.defaultType)
       elif top.cmp.startsWith("$gamePackage/"):
         // Sprint 2a — SCREEN_TRANSITION emission
         if lastTopCmp != null AND lastTopCmp != top.cmp:
           closeOpenScreenTransition(now)
           emitScreenTransition(lastTopCmp, top.cmp, now)
         lastTopCmp = top.cmp
         lastGameForegroundMs = now

       // Sprint 4a — VR session silent-gap check
       checkVrSilentGap(now)

       // close activity-keyed open events whose tracked cmp left the stack
       // (unchanged)

     // Sprint 1 — PID-restart watcher (called periodically by viewmodel)
     checkPidRestart(currentGamePid):
       if lastGamePid != currentGamePid AND now - lastAppStartupMs > 10000:
         emitAppStartup(now, restart=true)
         lastGamePid = currentGamePid

     // Sprint 4a — VR_RETURN_TRANSITION delayed emission
     closeVrSession(closeMs):
       super.close(vrSession)
       emitDetectedEvent(VR_RETURN_TRANSITION, startMs=closeMs, endMs=closeMs+VR_RETURN_TRANSITION_WINDOW_MS)

                                          │
                                          ▼
                                  StateFlow<List<DetectedEvent>>
                                  StateFlow<List<String>>  // warnings
                                          │
                       ┌──────────────────┴──────────────────┐
                       ▼                                     ▼
              AppViewModel                          SessionHistory payload
              (unchanged — consumes opaque events)  (EventType.* deserialize cleanly)
                       │                                     │
                       └──────────────────┬──────────────────┘
                                          ▼
                                  ReportGenerator
                                  when(event.type) {  // L1225 label, L1233 color
                                    + 7 new branches
                                  }
                                  + ESC-CONCL-001 PostVrRecoveryRule
                                  + ESC-CONCL-002 AnrSeverityRule
                                  + ESC-REPORT-002 VR caveat footnote
```

---

## 2. Component contracts

### 2.1 `core/events/DetectedEvent.kt` (modified)

```kotlin
@Serializable
enum class EventType {
    INTERSTITIAL,
    REWARDED_VIDEO,
    IAP,
    LOADING,
    FOREGROUND_LOSS,
    APP_STARTUP,            // Sprint 1
    SDK_INIT,               // Sprint 1
    ANR,                    // Sprint 1
    SCREEN_TRANSITION,      // Sprint 2a
    INSTRUMENTED,           // Sprint 3
    VR_SESSION,             // Sprint 4a
    VR_RETURN_TRANSITION,   // Sprint 4a
    RATE_US,                // Sprint 5
    UNKNOWN,
}
```

Ordinals 0..4 preserved for backwards-compat with existing `.gameperf` files. UNKNOWN moves to ordinal 13.

`DetectedEvent` data class unchanged — only the enum expands.

### 2.2 `core/events/SdkSignature.kt` (Sprint 0 BREAKING refactor)

```kotlin
internal data class SdkSignature(
    val sdk: String,
    val defaultType: EventType,                       // ◄── replaces `type`
    val activityClasses: List<String>,
    val logcatTags: List<String>,
    val openPatterns: List<Pair<Regex, EventType>>,   // ◄── new shape
    val closePatterns: List<Regex>,
)
```

`defaultType` is the EventType emitted by the activity-class path. For SDKs where activity class is unambiguous (e.g. AdMob `AdActivity` doesn't tell us interstitial vs rewarded), `defaultType=INTERSTITIAL` is the safe fallback; Sprint 2b adds runtime upgrade-to-REWARDED logic when the matching rewarded pattern fires within the open event's lifetime.

### 2.3 `core/events/MatchResult.kt` (NEW)

```kotlin
internal data class MatchResult(
    val sig: SdkSignature,
    val pattern: Regex,
    val resolvedType: EventType,
)
```

Return value of `SdkSignatureCatalog.matchOpen()`. Replaces the current `Pair<SdkSignature, Regex>`.

### 2.4 `core/events/SdkSignatureCatalog.kt` (modified)

`ALL` size grows from 9 to 14. Five new entries: Firebase, Google Analytics for Firebase (AppMeasurement), System ANR, GamePerf, Meta Quest VR, Google Play In-App Review.

The 6 existing ad/billing entries gain SDK_INIT patterns inside their `openPatterns` lists (no new entries — the patterns extend the existing entries because they share the same `logcatTags` allowlist).

```kotlin
val ALL: List<SdkSignature> = listOf(
    // ── AdMob ── extended with rewarded + init patterns (Sprint 1, 2b)
    SdkSignature(
        sdk = "AdMob",
        defaultType = EventType.INTERSTITIAL,
        activityClasses = listOf(
            "com.google.android.gms.ads.AdActivity",
            "com.google.android.gms.ads.OutOfContextTestingActivity",
        ),
        logcatTags = listOf("Ads", "AdActivity", "MobileAds"),
        openPatterns = listOf(
            // Sprint 1 — INIT
            Regex("""(?i)\bInitializing AdMob SDK\b""") to EventType.SDK_INIT,
            Regex("""(?i)\bMobileAds:.*\binitialize\b""") to EventType.SDK_INIT,
            // Sprint 2b — REWARDED (specific)
            Regex("""(?i)\bonUserEarnedReward\b""") to EventType.REWARDED_VIDEO,
            Regex("""(?i)\bonRewardedAdLoaded\b""") to EventType.REWARDED_VIDEO,
            // Existing INTERSTITIAL
            Regex("""(?i)\bShowing ad\b""") to EventType.INTERSTITIAL,
            Regex("""(?i)\bonAdShown\b""") to EventType.INTERSTITIAL,
            Regex("""(?i)\bad opened\b""") to EventType.INTERSTITIAL,
            Regex("""(?i)\bLoaded ad\b""") to EventType.INTERSTITIAL,
        ),
        closePatterns = listOf(
            Regex("""(?i)\bAd dismissed\b"""),
            Regex("""(?i)\bonAdClosed\b"""),
            Regex("""(?i)\bonAdDismissed\b"""),
        ),
    ),
    // … similar extension for Unity Ads, IronSource, AppLovin, Meta Audience, Google Play Billing
    //   (Unity Ads / Play Billing get only INIT additions, not REWARDED — Unity Ads is already
    //    REWARDED_VIDEO by default; Play Billing is IAP)

    // ── Unity Engine, Unreal Engine, Cocos2d ── unchanged (Sprint 0 only migrates shape)
    // Their openPatterns become `listOf(regex to LOADING, ...)` — single type per pattern.

    // ── Firebase (NEW Sprint 1) ──
    SdkSignature(
        sdk = "Firebase",
        defaultType = EventType.SDK_INIT,
        activityClasses = emptyList(),
        logcatTags = listOf("Firebase", "FirebaseApp"),
        openPatterns = listOf(
            Regex("""(?i)\bFirebaseApp initialization successful\b""") to EventType.SDK_INIT,
            Regex("""(?i)\bFirebase:.*\binitialized\b""") to EventType.SDK_INIT,
        ),
        closePatterns = listOf(
            Regex("""(?i)\binitialization complete\b"""),
        ),
    ),

    // ── Google Analytics for Firebase / AppMeasurement (NEW Sprint 1) ──
    SdkSignature(
        sdk = "Google Analytics for Firebase",
        defaultType = EventType.SDK_INIT,
        activityClasses = emptyList(),
        logcatTags = listOf("FA", "FirebaseAnalytics"),
        openPatterns = listOf(
            Regex("""(?i)\bApp measurement init\b""") to EventType.SDK_INIT,
            Regex("""(?i)\bFA:.*\binitialized\b""") to EventType.SDK_INIT,
        ),
        closePatterns = listOf(
            Regex("""(?i)\bmeasurement.*\bready\b"""),
        ),
    ),

    // ── System ANR (NEW Sprint 1) ──
    SdkSignature(
        sdk = "System ANR",
        defaultType = EventType.ANR,
        activityClasses = emptyList(),
        logcatTags = listOf("ActivityManager"),
        openPatterns = listOf(
            Regex("""am_anr""") to EventType.ANR,
        ),
        closePatterns = listOf(
            Regex("""am_proc_died"""),
        ),
    ),

    // ── GamePerf instrumented protocol (NEW Sprint 3) ──
    SdkSignature(
        sdk = "GamePerf",
        defaultType = EventType.INSTRUMENTED,
        activityClasses = emptyList(),
        logcatTags = listOf("GamePerf"),
        openPatterns = listOf(
            Regex("""(?i)^\s*([A-Z_]+)\.Start(?:\s+name="([^"]+)")?(?:\s+group="([^"]+)")?""") to EventType.INSTRUMENTED,
        ),
        closePatterns = listOf(
            Regex("""(?i)^\s*([A-Z_]+)\.Stop(?:\s+name="([^"]+)")?"""),
        ),
    ),

    // ── Meta Quest VR (NEW Sprint 4a) ──
    SdkSignature(
        sdk = "Meta Quest VR",
        defaultType = EventType.VR_SESSION,
        activityClasses = emptyList(),
        logcatTags = listOf("VrApi", "XrPerformanceManager"),
        openPatterns = listOf(
            // Any non-empty VrApi-tagged line is evidence the VR session is active.
            Regex(""".+""") to EventType.VR_SESSION,
        ),
        closePatterns = emptyList(), // close via silent-gap heuristic, not by close pattern
    ),

    // ── Google Play In-App Review (NEW Sprint 5) ──
    SdkSignature(
        sdk = "Google Play In-App Review",
        defaultType = EventType.RATE_US,
        activityClasses = listOf(
            "com.google.android.play.core.review.ReviewActivity",
        ),
        logcatTags = listOf("ReviewManager", "PlayCore"),
        openPatterns = listOf(
            Regex("""(?i)\blaunchReviewFlow\b""") to EventType.RATE_US,
            Regex("""(?i)\bReviewManager:.*\binvoked\b""") to EventType.RATE_US,
        ),
        closePatterns = listOf(
            Regex("""(?i)\bonComplete\b"""),
            Regex("""(?i)\bReviewActivity\s+destroyed\b"""),
        ),
    ),
)
```

### 2.5 `core/events/EventDetectorImpl.kt` (modified)

New fields:

```kotlin
private var lastAppStartupMs: Long = -1L
private var lastTopCmp: String? = null
private var lastVrApiLineMs: Long = -1L
private var lastGamePid: Int? = null

companion object {
    // Sprint 1
    const val SDK_INIT_WINDOW_MS = 10_000L
    const val SDK_INIT_AUTOCLOSE_MS = 5_000L
    const val APP_STARTUP_CAP_MS = 30_000L

    // Sprint 2a
    const val MAX_SCREEN_TRANSITIONS = 100

    // Sprint 4a
    const val VR_SESSION_SILENT_GAP_MS = 5_000L
    const val VR_RETURN_TRANSITION_WINDOW_MS = 5_000L
}
```

Modified flow points:

- `handleLogLine`: now resolves type via `MatchResult`; gates SDK_INIT against `lastAppStartupMs`; reclassifies INTERSTITIAL → REWARDED when rewarded pattern fires within open event lifetime.
- `handleActivityStack`: emits APP_STARTUP on first foreground; emits SCREEN_TRANSITION on cmp change inside game package; tracks `lastTopCmp`.
- New `handleVrApiLine(line: LogLine)`: extracted helper called from `handleLogLine` when `line.tag in ["VrApi", "XrPerformanceManager"]`. Opens VR_SESSION if none; updates `lastVrApiLineMs`.
- New `checkVrSilentGap(now: Long)`: called once per dumpsys tick. If VR_SESSION open AND `now - lastVrApiLineMs >= VR_SESSION_SILENT_GAP_MS`, close VR_SESSION AND emit VR_RETURN_TRANSITION.
- New `checkPidRestart(currentPid: Int?)`: optional API called by `AppViewModel` per-tick. Compares to `lastGamePid`. NOT critical-path; can be deferred to Sprint 1 batch 3.

### 2.6 `core/conclusions/rules/PostVrRecoveryRule.kt` (NEW Sprint 4a)

```kotlin
internal class PostVrRecoveryRule(
    private val tempRiseThresholdC: Double = 2.0,
) : ConclusionRule {

    override fun evaluate(input: ConclusionInput): List<Conclusion> {
        val transitions = input.events.filter { it.type == EventType.VR_RETURN_TRANSITION && it.endMs != null }
        if (transitions.isEmpty()) return emptyList()

        return transitions.mapNotNull { ev ->
            val startTemp = input.thermalSamplesByMs[ev.startMs] ?: return@mapNotNull null
            val endTemp = input.thermalSamplesByMs[ev.endMs!!] ?: return@mapNotNull null
            val rise = endTemp - startTemp
            if (rise < tempRiseThresholdC) return@mapNotNull null

            Conclusion(
                severity = ConclusionSeverity.WARNING,
                text = "Tras cerrar la sesión VR la temperatura sube ${"%.1f".format(rise)}°C en los siguientes " +
                       "${(ev.endMs - ev.startMs) / 1000} segundos — revisa el manejo de la transición de salida de VR.",
            )
        }
    }
}
```

Pattern mirrors `LoadingThermalRecoveryRule.kt`.

### 2.7 `core/conclusions/rules/AnrSeverityRule.kt` (NEW Sprint 1)

```kotlin
internal class AnrSeverityRule : ConclusionRule {
    override fun evaluate(input: ConclusionInput): List<Conclusion> {
        val anrs = input.events.filter { it.type == EventType.ANR }
        if (anrs.isEmpty()) return emptyList()

        val timestamps = anrs.joinToString(", ") { "t=${it.startMs}ms" }
        return listOf(
            Conclusion(
                severity = ConclusionSeverity.HIGH,
                text = "Se detectó al menos una ANR (App No Responde) durante la sesión. " +
                       "Revisa los logs del proceso afectado en torno a $timestamps — " +
                       "Vitals penaliza apps con tasa de ANR ≥0.47% de DAU.",
            )
        )
    }
}
```

### 2.8 `report/ReportGenerator.kt` (modified)

Extend label/color `when` branches. Add VR caveat footnote. Add INSTRUMENTED row metadata rendering.

```kotlin
// Label mapping (L1225)
val label = when (event.type) {
    EventType.INTERSTITIAL -> "Intersticial"
    EventType.REWARDED_VIDEO -> "Vídeo recompensado"
    EventType.IAP -> "Compra (IAP)"
    EventType.LOADING -> "Carga"
    EventType.FOREGROUND_LOSS -> "Pérdida de foreground"
    EventType.APP_STARTUP -> "Inicio"
    EventType.SDK_INIT -> "Inicialización SDK"
    EventType.ANR -> "App no responde (ANR)"
    EventType.SCREEN_TRANSITION -> "Cambio de pantalla"
    EventType.INSTRUMENTED -> {
        val phase = event.metadata["phase"] ?: "Marcador instrumentado"
        val name = event.metadata["name"]
        if (name.isNullOrBlank()) "Marcador instrumentado — $phase"
        else "Marcador instrumentado — $phase ($name)"
    }
    EventType.VR_SESSION -> "Sesión VR"
    EventType.VR_RETURN_TRANSITION -> "Recuperación post-VR"
    EventType.RATE_US -> "Solicitud de valoración"
    EventType.UNKNOWN -> "Desconocido"
}

// Color mapping (L1233)
val color = when (event.type) {
    EventType.INTERSTITIAL, EventType.REWARDED_VIDEO -> "#f97316"
    EventType.IAP -> "#38bdf8"
    EventType.LOADING -> "#f59e0b"
    EventType.FOREGROUND_LOSS -> "#a855f7"
    EventType.APP_STARTUP -> "#10b981"
    EventType.SDK_INIT -> "#22d3ee"
    EventType.ANR -> "#dc2626"
    EventType.SCREEN_TRANSITION -> "#0891b2"
    EventType.INSTRUMENTED -> "#a855f7"
    EventType.VR_SESSION -> "#7c3aed"
    EventType.VR_RETURN_TRANSITION -> "#c4b5fd"
    EventType.RATE_US -> "#f59e0b"
    EventType.UNKNOWN -> "#94a3b8"
}

// VR caveat footnote (post-Sprint 4a)
if (events.any { it.type == EventType.VR_SESSION }) {
    html.append("""
        <p class="hint">
          Detección VR limitada a dispositivos Meta Quest (Horizon OS) en esta versión.
          Otros runtimes XR no se detectan automáticamente.
        </p>
    """.trimIndent())
}
```

---

## 3. Algorithm pseudocode

### 3.1 Sprint 1 — SDK_INIT post-startup gate

```
handleLogLine(line):
  matchResult = catalog.matchOpen(line)
  if matchResult == null:
    ... (close pattern matching, unchanged)
    return

  resolvedType = matchResult.resolvedType

  // Gate: SDK_INIT only fires within startup window
  if resolvedType == SDK_INIT:
    if lastAppStartupMs < 0 OR (line.tsMs - lastAppStartupMs) > SDK_INIT_WINDOW_MS:
      // Fall through to next pattern in this sig
      fallbackResolvedType = matchResult.sig.openPatterns
        .firstOrNull { (p, t) -> t != SDK_INIT && p.containsMatchIn(line.msg) }
        ?.second
      if fallbackResolvedType == null:
        return  // gate rejected, no fallback
      resolvedType = fallbackResolvedType

  tryOpen(matchResult.sig, matchResult.pattern.pattern, line.tsMs, line.tag, resolvedType, "logcat")
```

### 3.2 Sprint 2a — SCREEN_TRANSITION emission

```
handleActivityStack(frames):
  if frames.isEmpty(): return
  top = frames.first()
  now = timeProvider()

  // Cold-start check (Sprint 1)
  if lastGameForegroundMs == -1L AND top.cmp.startsWith("$gamePackage/"):
    emitAppStartup(now)
    lastGameForegroundMs = now

  sig = catalog.matchActivity(top.cmp)
  if sig != null:
    ... (tryOpenActivity with sig.defaultType)
  elif top.cmp.startsWith("$gamePackage/"):
    if lastTopCmp != null AND lastTopCmp != top.cmp:
      if screenTransitionCount() >= MAX_SCREEN_TRANSITIONS:
        ensureWarning("Se alcanzó el tope de 100 cambios de pantalla — los siguientes se omiten para no inundar el reporte.")
      else:
        closeOpenScreenTransition(now)
        emitScreenTransition(lastTopCmp!!, top.cmp, now)
    lastTopCmp = top.cmp
    lastGameForegroundMs = now

  // Sprint 4a — check VR silent gap once per tick
  checkVrSilentGap(now)

  // Close activity-keyed open events whose cmp left the stack (unchanged from today)
  ...
```

### 3.3 Sprint 2b — INTERSTITIAL → REWARDED upgrade

```
handleLogLine(line):
  matchResult = catalog.matchOpen(line)
  if matchResult != null:
    tryOpen(...)  // as above
    return

  // No new open. Check close patterns AND reclassification patterns.
  for entry in openEvents.values.toList():
    sig = catalog.ALL.firstOrNull { it.sdk == entry.sdkSource } ?: continue

    closePattern = catalog.matchClose(line, sig)
    if closePattern != null:
      tryClose(entry, line.tsMs, closePattern.pattern)
      continue

    // Reclassification: open entry is INTERSTITIAL, a REWARDED pattern fires → upgrade
    if entry.type == EventType.INTERSTITIAL:
      rewardedMatch = sig.openPatterns.firstOrNull { (p, t) ->
        t == EventType.REWARDED_VIDEO AND p.containsMatchIn(line.msg) AND
        line.tag in sig.logcatTags
      }
      if rewardedMatch != null:
        upgradeEventType(entry, EventType.REWARDED_VIDEO, line.tsMs)
```

`upgradeEventType()`:
```
upgradeEventType(entry, newType, atMs):
  updated = entry.copy(
    type = newType,
    metadata = entry.metadata + mapOf("upgradedFrom" to entry.type.name, "upgradedAtMs" to atMs.toString())
  )
  replaceInPublishedList(entry.id, updated)
  reassignOpenKey(entry.id, updated)
```

### 3.4 Sprint 4a — VR session silent-gap close

```
handleLogLine(line):
  if line.tag in ["VrApi", "XrPerformanceManager"]:
    handleVrApiLine(line)
    return
  ... (rest of handleLogLine)

handleVrApiLine(line):
  lastVrApiLineMs = line.tsMs
  if openVrSession == null AND foregroundGuardPasses(line.tsMs):
    sig = catalog.ALL.first { it.sdk == "Meta Quest VR" }
    event = DetectedEvent(
      type = VR_SESSION, sdkSource = sig.sdk, startMs = line.tsMs,
      confidence = HIGH, signatureMatched = "vrapi-tag-present",
      metadata = mapOf("source" to "logcat-tag", "tag" to line.tag)
    )
    openVrSession = event
    appendEvent(event)

checkVrSilentGap(now):
  if openVrSession == null: return
  if now - lastVrApiLineMs >= VR_SESSION_SILENT_GAP_MS:
    closeVrSession(now)

closeVrSession(closeMs):
  // close the open VR_SESSION
  updated = openVrSession!!.copy(endMs = closeMs)
  replaceInPublishedList(updated.id, updated)
  openVrSession = null

  // emit VR_RETURN_TRANSITION
  transition = DetectedEvent(
    type = VR_RETURN_TRANSITION, sdkSource = "Meta Quest VR",
    startMs = closeMs, endMs = closeMs + VR_RETURN_TRANSITION_WINDOW_MS,
    confidence = MEDIUM, signatureMatched = "vr-recovery-window",
    metadata = mapOf("source" to "vr-recovery-window")
  )
  appendEvent(transition)
```

---

## 4. Test file ownership

| Test file | Owner sprint | Tests | Notes |
|-----------|--------------|-------|-------|
| `SdkSignaturePatternsTest.kt` | Sprint 0 | ~5 | Refactor invariants: `MatchResult` returned, `defaultType` distinct from per-pattern types. |
| `SdkSignatureCatalogTest.kt` | Sprint 0+1+2b+3+4a+5 | +18 | Migration of `sig.type` reads to helpers; new SDK positive/negative tests. |
| `EventDetectorImplTest.kt` | Sprint 0 (no changes) + 1 (+5 for cold-start) | +5 | Sprint 1 adds APP_STARTUP cold-start, PID restart, SDK_INIT gate, ANR confidence-stable-under-gap. |
| `LoadingSignaturesTest.kt` | Sprint 0 (migration only) | unchanged count | Sprint 0: `matched.first.type` reads update to `matched.resolvedType`. |
| `AppStartupDetectorTest.kt` (NEW) | Sprint 1 | ~6 | Cold-start, PID restart, suppression, endMs synthesis. |
| `SdkInitGateTest.kt` (NEW) | Sprint 1 | ~5 | 10s window discriminator; outside-window fallback. |
| `AnrDetectorTest.kt` (NEW) | Sprint 1 | ~4 | am_anr emit, foreground guard relaxed, gap doesn't downgrade, am_proc_died closes. |
| `ScreenTransitionTest.kt` (NEW) | Sprint 2a | ~5 | Cmp change emits, single-activity no-emit, cap warning, sequential close. |
| `RewardedSignaturesTest.kt` (NEW) | Sprint 2b | ~12 | 4 SDKs × (positive open, negative noise, upgrade flow). |
| `InstrumentedProtocolTest.kt` (NEW) | Sprint 3 | ~8 | Start/Stop parsing, phase/name/group metadata, lone-Stop tolerance. |
| `QuestVrSessionTest.kt` (NEW) | Sprint 4a | ~6 | Open on first VrApi, silent-gap close, VR_RETURN_TRANSITION delayed emit, boundary 5000ms. |
| `RateUsSignaturesTest.kt` (NEW) | Sprint 5 | ~4 | launchReviewFlow open, dumpsys ReviewActivity open, onComplete close. |
| `PostVrRecoveryRuleTest.kt` (NEW) | Sprint 4a | ~3 | Temp rise emits conclusion, no rise no conclusion, Spanish copy assertion. |
| `AnrSeverityRuleTest.kt` (NEW) | Sprint 1 | ~3 | Single ANR conclusion, multiple ANR aggregation, no-ANR no-conclusion. |
| `ReportGeneratorEventsTest.kt` (extend or new) | Sprint 4a + general | ~4 | VR caveat footnote present, INSTRUMENTED row renders phase + name, color/label coverage. |

Total new tests: ~70-80 (estimate ~59 in proposal; design refined to 70-80 with more granular coverage). Final delta vs 837 baseline: ~900 final count (+8%).

---

## 5. Algorithms — edge cases

### 5.1 SDK_INIT cross-tag collisions

Firebase and Google Analytics (FA) share no tags with ad SDKs. AdMob `Ads`/`MobileAds` tags do collide with INTERSTITIAL show patterns — Sprint 0 `openPatterns` order matters: SDK_INIT patterns listed BEFORE the show patterns so they're matched first, then the 10s gate applies.

### 5.2 SCREEN_TRANSITION vs ad activity collision

When `top.cmp` matches an SDK activity class (`matchActivity != null`), the SDK path takes precedence; no SCREEN_TRANSITION is emitted. Documented in spec ESC-SCRN-001 ("AND no SDK signature matches the activity").

### 5.3 Rewarded upgrade re-entry

Once an event is upgraded INTERSTITIAL → REWARDED, the open key stays the same; only the `type` field and metadata change. Subsequent rewarded patterns fire NO further upgrade. The downgrade path (REWARDED → INTERSTITIAL) is NOT supported — a once-classified rewarded event stays rewarded.

### 5.4 VR session pre-startup

If a `VrApi` line arrives BEFORE the cold-start sensor has fired (rare; the user started capture in the middle of an already-running VR app), the foreground guard rejects it. Documented in ESC-VR-001 ("foreground guard primed"). The detector waits for the next dumpsys tick that puts the game in foreground; subsequent VrApi lines then open the session.

### 5.5 Multiple VR sessions in one capture

Possible (rare): user enters and exits VR multiple times. The detector closes the old VR_SESSION on silent gap, emits VR_RETURN_TRANSITION, then accepts a NEW VR_SESSION on the next VrApi line.

### 5.6 INSTRUMENTED Start/Stop mismatch

Spec ESC-INSTR-002: lone Stops are silently ignored. Lone Starts naturally close via session-end `endInferred=true`. Mismatched name strings (Start name="A", Stop name="B") close the most recently opened INSTRUMENTED event whose phase matches the Stop's phase, OR if no phase match, close the most recently opened INSTRUMENTED event regardless of name.

### 5.7 RATE_US dumpsys + logcat overlap

If both paths fire (logcat `launchReviewFlow` followed by `ReviewActivity` on dumpsys top), the second OPEN is rejected by EVT-005's existing key-uniqueness guard. Documented.

---

## 6. Test fixtures

### 6.1 New fixtures under `src/test/resources/logcat-fixtures/`

| Fixture | Sprint | Lines (target) | Content |
|---------|--------|----------------|---------|
| `app-startup-cold.log` | 1 | 40-60 | Cold-start sequence: `am_proc_start`, game foreground appearance, Firebase init, AdMob init, gameplay begins. |
| `sdk-init-firebase.log` | 1 | 30 | `FirebaseApp initialization successful`, `App measurement init`, `FA: initialized`. |
| `anr-game.log` | 1 | 20 | `am_anr ... Process com.example.game`, followed by `am_proc_died`. |
| `admob-rewarded.log` | 2b | 50-80 | `Loaded ad` (rewarded), `onRewardedAdLoaded`, `onUserEarnedReward type=coins amount=10`, `Ad dismissed`. |
| `ironsource-rewarded.log` | 2b | 50-80 | `rewardedVideoDidOpen instanceId=42`, `onRewardedVideoAdShowSucceeded`, `interstitialDidClose`. |
| `applovin-rewarded.log` | 2b | 50-80 | `onRewardedVideoStarted`, `onRewardedAdReceivedReward type=coins amount=20`, `onAdHidden`. |
| `meta-rewarded.log` | 2b | 50-80 | `onRewardedAdLoaded`, `onRewardedVideoCompleted`, `onInterstitialDismissed`. |
| `instrumented-protocol.log` | 3 | 40-60 | `CINEMATIC.Start name="intro"`, `CINEMATIC.Stop name="intro"`, `TUTORIAL.Start name="first_battle" group="onboarding"`, `TUTORIAL.Stop name="first_battle"`, `GAMEPLAY_DENSE.Start`, etc. |
| `quest-vrapi-session.log` | 4a | 60-80 | 30+ `VrApi: FPS=72 GPU%=45 ...` lines at synthetic 100ms intervals, then 6s silent gap, then more VrApi lines (= two distinct VR sessions). |
| `rate-us-play-core.log` | 5 | 30 | `ReviewManager: launchReviewFlow invoked`, `PlayCore: onComplete result=SUCCESS`. |

Each fixture mirrors the format of existing fixtures (line layout consistent with `LogcatLineParser.THREADTIME_REGEX`).

### 6.2 Fixture line format reminder

```
MM-DD HH:MM:SS.mmm  PID  TID L TAG: MSG
```

Example for Quest VR fixture:
```
05-12 14:50:00.100  1234  5678 I VrApi: FPS=72 Prd=33ms Tear=0 Early=0 Stale=0 GPU%=45 CPU%=22
05-12 14:50:00.200  1234  5678 I VrApi: FPS=72 Prd=33ms Tear=0 Early=0 Stale=0 GPU%=46 CPU%=23
05-12 14:50:00.300  1234  5678 I VrApi: FPS=72 Prd=33ms Tear=0 Early=0 Stale=0 GPU%=44 CPU%=22
...
```

---

## 7. Backwards compatibility

### 7.1 Serialization

`EventType` adds 7 new ordinal-tail values + reorders UNKNOWN to ordinal 13. kotlinx.serialization by default serializes enums BY NAME (not ordinal) — name-based load is safe across versions.

Older binaries reading new `.gameperf` files with new EventType values will throw `SerializationException` unless we configure `coerceInputValues = true` on the Json instance OR add a `@Serializer` for forward-compat. **Design decision**: ship Sprint 0 with `coerceInputValues = true` (single Json config tweak in `SessionHistory`) so old binaries reading new files degrade unknown enums to `UNKNOWN`. Tested in `SessionHistoryRoundTripTest`.

### 7.2 Production code

Only `EventDetectorImpl.tryOpen` and `tryOpenActivity` change in Sprint 0 — both already exist as private helpers, adapted to take `resolvedType` parameter. No external API change.

### 7.3 Report HTML

The new `EventType` branches are additive. Older sessions (without the new types) render unchanged. NO breakage for legacy reports.

---

## 8. Effort + risk consolidation

| Sprint | Effort | Atomic? | Rollback |
|--------|--------|---------|----------|
| 0 | 1.0d | YES (single commit) | Revert the commit; tests stay green on pre-refactor branch. |
| 1 | 2.0d | NO (3 sub-features) | Per-sub-feature rollback supported via batch commits. |
| 2a | 0.5d | YES | Revert commit. |
| 2b | 1.0d | NO (4 SDKs separately) | Per-SDK rollback supported via batch commits. |
| 3 | 1.0d | YES | Revert commit. |
| 4a | 1.0d | NO (Quest detect + recovery rule) | Per-feature rollback supported. |
| 5 | 0.5d | YES | Revert commit. |
| 6 | 0d | DONE | N/A. |
| **Total** | **7.0d** | | |

---

## 9. Open design questions (deferred to Sprint kickoff if blocking)

1. **Sprint 1 — AppMeasurement entry name**: "Google Analytics for Firebase" vs "AppMeasurement" vs "FA". Decision: use "Google Analytics for Firebase" (matches Firebase's own marketing). Document in CHANGELOG.
2. **Sprint 4a — VR_RETURN_TRANSITION close window**: 5000ms. May tune post-release. Spec ESC-VR-003 defines constant.
3. **Sprint 2b — REWARDED→INTERSTITIAL downgrade**: NOT supported. Once upgraded, stays upgraded.
4. **Sprint 3 — INSTRUMENTED metadata size**: spec doesn't cap metadata map size. If a game emits 1000s of phases per session, EVT-009 global cap (500 events) protects us. No additional cap needed.

---

## 10. CHANGELOG entry template

```
v4.5.0 — 2026-MM-DD

## Que hay de nuevo
- Detección automática de inicio de aplicación (APP_STARTUP) y de inicialización de SDKs (SDK_INIT) — 6 SDKs catalogados.
- Detección pasiva de ANR (App No Responde) vía logcat.
- Detección de cambios de pantalla (SCREEN_TRANSITION) en juegos multi-Activity.
- Clasificación correcta de vídeos recompensados en AdMob / IronSource / AppLovin / Meta Audience (antes se reportaban como intersticiales).
- Protocolo opt-in "GamePerf:I" para marcar fases del juego (cinemáticas, tutoriales, gameplay denso) — instrucciones en README.
- Detección de sesiones VR en dispositivos Meta Quest (Horizon OS) — incluye ventana de recuperación post-VR para análisis térmico.
- Detección de la solicitud de valoración de Google Play (RATE_US).

## Arreglos
- Refactor interno de `SdkSignature` para soportar múltiples tipos de evento por SDK (no afecta a sesiones guardadas previamente).

## Detalles técnicos
- 7 nuevos valores en `EventType`. Compatibilidad hacia atrás de archivos `.gameperf` garantizada (coerceInputValues=true).
- ~60-80 nuevos tests; total ~900 tests, CI <5 min.
- 10 nuevos fixtures de logcat reales.
- 2 nuevas reglas de conclusión: `AnrSeverityRule` + `PostVrRecoveryRule`.
- Tag allowlist de logcat se amplía a: `Firebase`, `FA`, `ActivityManager`, `GamePerf`, `VrApi`, `XrPerformanceManager`, `ReviewManager`, `PlayCore`.
```
