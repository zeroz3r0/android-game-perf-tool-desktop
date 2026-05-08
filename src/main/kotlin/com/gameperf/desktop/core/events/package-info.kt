/**
 * Auto event detection for ad/IAP/loading windows.
 *
 * This package provides automatic detection of SDK-triggered events (interstitials,
 * rewarded videos, IAPs, loading screens) via logcat streaming and dumpsys polling.
 * Detection results are exposed as [DetectedEvent] instances through the [EventDetector]
 * interface.
 *
 * Architecture:
 *  - [SdkSignatureCatalog] is the SINGLE source of truth for SDK activity classes,
 *    logcat tags, and open/close regex patterns. Extending detection = adding entries
 *    to its `ALL` list. No parallel definitions elsewhere.
 *  - [LogcatCapture] owns the long-lived `adb logcat` child process.
 *  - [DumpsysPoller] polls `dumpsys activity activities` at 1 Hz.
 *  - [EventDetectorImpl] orchestrates both sources and implements the LOAD→SHOW→CLOSE
 *    state machine for each detected event.
 *
 * Usage:
 *  - The feature is gated by [com.gameperf.desktop.core.Settings.autoEventDetectionEnabled]
 *    (default true). The flag is loaded once via [com.gameperf.desktop.core.Settings.Companion.load]
 *    at capture start; mid-session toggling has no effect on the in-flight session.
 *  - [AppViewModel] instantiates [EventDetectorImpl] at capture start and bridges
 *    `events: StateFlow<List<DetectedEvent>>` to the UI and report generator.
 *
 * @since v4.4.0
 * @see com.gameperf.desktop.core.metrics.FilteredMetricsCalculator for filtered aggregation
 * @see com.gameperf.desktop.core.conclusions.ConclusionEngine for heuristic analysis
 */
package com.gameperf.desktop.core.events
