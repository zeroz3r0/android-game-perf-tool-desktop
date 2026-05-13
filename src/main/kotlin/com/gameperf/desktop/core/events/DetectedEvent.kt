package com.gameperf.desktop.core.events

import kotlinx.serialization.Serializable

/**
 * Type of detected event. Maps to the SDK behavior observed during capture.
 *
 * @property INTERSTITIAL Full-screen ad (AdMob, Unity Ads, IronSource, AppLovin, Meta AN).
 * @property REWARDED_VIDEO Rewarded video ad (typically longer duration).
 * @property IAP In-app purchase flow (Google Play Billing, StoreKit).
 * @property LOADING Loading screen or scene transition detected via heuristics.
 * @property FOREGROUND_LOSS Game lost foreground (iOS fallback for ad detection).
 * @property APP_STARTUP Application launch / cold start window (Sprint 1).
 * @property SDK_INIT Third-party SDK initialization phase (Sprint 1).
 * @property ANR Application Not Responding incident (Sprint 1).
 * @property SCREEN_TRANSITION In-game navigation between screens (Sprint 2a).
 * @property INSTRUMENTED Event injected via the in-game instrumentation hook (Sprint 3).
 * @property VR_SESSION VR headset session window (Sprint 4a).
 * @property VR_RETURN_TRANSITION Return-to-2D transition after VR session (Sprint 4a).
 * @property RATE_US Rate-us / review prompt presentation (Sprint 5).
 * @property CUTSCENE Auto-detected cinematic / cutscene phase from engine scene name (auto-phase-detection).
 * @property MENU_NAV Auto-detected menu / lobby navigation phase from engine scene name (auto-phase-detection).
 * @property COMBAT_PHASE Auto-detected combat / boss / wave phase from engine scene name (auto-phase-detection).
 * @property TUTORIAL_PHASE Auto-detected tutorial / onboarding phase from engine scene name (auto-phase-detection).
 * @property UNKNOWN Unclassified event (SDK not in catalog).
 */
@Serializable
enum class EventType {
    INTERSTITIAL,
    REWARDED_VIDEO,
    IAP,
    LOADING,
    FOREGROUND_LOSS,
    APP_STARTUP,
    SDK_INIT,
    ANR,
    SCREEN_TRANSITION,
    INSTRUMENTED,
    VR_SESSION,
    VR_RETURN_TRANSITION,
    RATE_US,
    // auto-phase-detection-from-engine-logs (additive, AUTO-001)
    CUTSCENE,
    MENU_NAV,
    COMBAT_PHASE,
    TUTORIAL_PHASE,
    UNKNOWN,
}

/**
 * Confidence level for a detected event.
 *
 * @property HIGH Both logcat and dumpsys corroborated the detection.
 * @property MEDIUM Logcat-only detection (dumpsys did not confirm).
 * @property LOW Detection during a logcat gap, or iOS fallback heuristic.
 */
@Serializable
enum class Confidence {
    HIGH,
    MEDIUM,
    LOW,
}

/**
 * A detected event (ad, IAP, loading) within a capture session.
 *
 * Events are detected by [EventDetector] and exposed via `StateFlow<List<DetectedEvent>>`.
 * They are used by [FilteredMetricsCalculator] to exclude the event time window from
 * aggregated metrics, and by [ReportGenerator] to render shaded bands on the FPS chart.
 *
 * @property id Unique identifier (UUID) for this event instance.
 * @property type Classification of the event (interstitial, IAP, etc.).
 * @property sdkSource Friendly name of the SDK (e.g., "AdMob", "Unity Ads", "PlayBilling").
 * @property startMs Wall-clock start time relative to `captureStartTime` (milliseconds).
 * @property endMs Wall-clock end time relative to `captureStartTime` (milliseconds).
 *   Null while the event is still open (in SHOW state).
 * @property confidence Detection confidence level.
 * @property signatureMatched The regex pattern or activity class that triggered detection.
 *   Useful for debugging false positives.
 * @property metadata Additional key-value pairs (e.g., "adUnitId", "productId").
 * @property endInferred True if [endMs] was inferred at session end rather than via an
 *   explicit close signal. The report can disclose this inference.
 *
 * @since v4.4.0
 */
@Serializable
data class DetectedEvent(
    val id: String = java.util.UUID.randomUUID().toString(),
    val type: EventType,
    val sdkSource: String,
    val startMs: Long,
    val endMs: Long? = null,
    val confidence: Confidence,
    val signatureMatched: String,
    val metadata: Map<String, String> = emptyMap(),
    val endInferred: Boolean = false,
)
