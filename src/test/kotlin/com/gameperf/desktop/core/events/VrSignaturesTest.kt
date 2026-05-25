package com.gameperf.desktop.core.events

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the VR_SESSION signature added in Sprint 4
 * (vr-event-detection, Issue #2 / D.6).
 *
 * Per CLAUDE.md "tests puros sin mocks" — no mocks. Inputs are hand-built
 * [LogLine] instances. Per the same SOP, the single source of truth for
 * the patterns is [SdkSignatureCatalog.ALL] — this test does NOT
 * re-declare any regex literal; it only feeds canonical log lines through
 * `matchOpen` / `matchClose` and asserts on the resolved match.
 *
 * Coverage:
 *  - Spec VR-002 (Oculus VrApi + OVRPlugin open + close patterns).
 *  - Spec VR-003 (OpenXR `xrBeginSession` / `XR_SESSION_STATE_*` patterns).
 *  - Spec VR-006 (tag specificity — bare `XR` tag and out-of-allowlist
 *    tags like `Unity` MUST NOT match VR_SESSION).
 *  - Spec VR-007 (KDoc HINT confidence assertion via source-file read).
 *
 * Phase 3 (dedup VR-004 / synthesis VR-005) and Phase 4 (fixture replay
 * VR-008) live elsewhere — Phases 1+2 are scoped to per-pattern
 * positive+negative.
 */
class VrSignaturesTest {

    // ═══════ 2.1 — VrApi `vrapi_EnterVrMode` ═══════

    @Test
    fun `vrapi_EnterVrMode on VrApi tag opens VR_SESSION at startMs`() {
        val line = lineFor(tag = "VrApi", msg = "vrapi_EnterVrMode()", tsMs = 1_000L)
        val matched = SdkSignatureCatalog.matchOpen(line)
        assertNotNull(matched, "vrapi_EnterVrMode must open a VR_SESSION")
        assertEquals("VRRuntime", matched.sig.sdk)
        assertEquals(EventType.VR_SESSION, matched.resolvedType)
    }

    @Test
    fun `VrApi unrelated surface line does NOT open VR_SESSION`() {
        // Tag is in the allowlist but the message has no canonical VR token
        // → must NOT match. Guards against tag-only matching.
        val line = lineFor(tag = "VrApi", msg = "surface created width=2880 height=1600")
        assertNull(SdkSignatureCatalog.matchOpen(line))
    }

    // ═══════ 2.2 — OVRPlugin HMDMounted / Entered VR Mode (case-insensitive) ═══════

    @Test
    fun `HMDMounted on OVRPlugin tag opens VR_SESSION`() {
        val line = lineFor(tag = "OVRPlugin", msg = "HMDMounted event received")
        val matched = SdkSignatureCatalog.matchOpen(line)
        assertNotNull(matched, "HMDMounted must open VR_SESSION")
        assertEquals("VRRuntime", matched.sig.sdk)
        assertEquals(EventType.VR_SESSION, matched.resolvedType)
    }

    @Test
    fun `Entered VR Mode on VrApi tag opens VR_SESSION (case-insensitive)`() {
        // Patterns are case-insensitive for the prose-style "Entered VR Mode"
        // / "Left VR Mode" log lines — Oculus drivers historically log this
        // both as "Entered VR Mode" and "entered vr mode" across SDK versions.
        val line = lineFor(tag = "VrApi", msg = "entered vr mode for stereoscopic render")
        val matched = SdkSignatureCatalog.matchOpen(line)
        assertNotNull(matched, "case-insensitive 'entered vr mode' must open VR_SESSION")
        assertEquals("VRRuntime", matched.sig.sdk)
    }

    @Test
    fun `OVRPlugin noise line does NOT open VR_SESSION`() {
        val line = lineFor(tag = "OVRPlugin", msg = "log noise from background thread")
        assertNull(SdkSignatureCatalog.matchOpen(line))
    }

    @Test
    fun `vrapi_LeaveVrMode closes a VR_SESSION on the VRRuntime signature`() {
        val sig = SdkSignatureCatalog.ALL.first { it.sdk == "VRRuntime" }
        val line = lineFor(tag = "VrApi", msg = "vrapi_LeaveVrMode() reason=user")
        assertNotNull(
            SdkSignatureCatalog.matchClose(line, sig),
            "vrapi_LeaveVrMode must match a VRRuntime close pattern",
        )
    }

    @Test
    fun `HMDUnmounted closes a VR_SESSION on the VRRuntime signature`() {
        val sig = SdkSignatureCatalog.ALL.first { it.sdk == "VRRuntime" }
        val line = lineFor(tag = "OVRPlugin", msg = "HMDUnmounted event received")
        assertNotNull(
            SdkSignatureCatalog.matchClose(line, sig),
            "HMDUnmounted must match a VRRuntime close pattern",
        )
    }

    // ═══════ 2.3 — OpenXR xrBeginSession / xrEndSession ═══════

    @Test
    fun `xrBeginSession on OpenXR tag opens VR_SESSION at startMs`() {
        val line = lineFor(tag = "OpenXR", msg = "xrBeginSession succeeded handle=0xdeadbeef", tsMs = 2_000L)
        val matched = SdkSignatureCatalog.matchOpen(line)
        assertNotNull(matched, "xrBeginSession must open VR_SESSION")
        assertEquals("VRRuntime", matched.sig.sdk)
        assertEquals(EventType.VR_SESSION, matched.resolvedType)
    }

    @Test
    fun `OpenXR instance-created noise does NOT open VR_SESSION`() {
        // The tag is in the allowlist but the message has no canonical
        // session-begin token — must NOT open. Mirrors the spec VR-003
        // negative: arbitrary OpenXR chatter is ignored.
        val line = lineFor(tag = "OpenXR", msg = "instance created — extension list loaded")
        assertNull(SdkSignatureCatalog.matchOpen(line))
    }

    @Test
    fun `xrEndSession closes a VR_SESSION on the VRRuntime signature`() {
        val sig = SdkSignatureCatalog.ALL.first { it.sdk == "VRRuntime" }
        val line = lineFor(tag = "OpenXR", msg = "xrEndSession returned XR_SUCCESS")
        assertNotNull(
            SdkSignatureCatalog.matchClose(line, sig),
            "xrEndSession must match a VRRuntime close pattern",
        )
    }

    @Test
    fun `XR_SESSION_STATE_STOPPING closes a VR_SESSION`() {
        val sig = SdkSignatureCatalog.ALL.first { it.sdk == "VRRuntime" }
        val line = lineFor(tag = "xrInstance", msg = "session transitioned to XR_SESSION_STATE_STOPPING")
        assertNotNull(
            SdkSignatureCatalog.matchClose(line, sig),
            "XR_SESSION_STATE_STOPPING must match a VRRuntime close pattern",
        )
    }

    // ═══════ 2.4 — xrInstance tag + state machine open ═══════

    @Test
    fun `XR_SESSION_STATE_READY on xrInstance tag opens VR_SESSION`() {
        val line = lineFor(tag = "xrInstance", msg = "session state -> XR_SESSION_STATE_READY")
        val matched = SdkSignatureCatalog.matchOpen(line)
        assertNotNull(matched, "XR_SESSION_STATE_READY must open VR_SESSION")
        assertEquals("VRRuntime", matched.sig.sdk)
        assertEquals(EventType.VR_SESSION, matched.resolvedType)
    }

    @Test
    fun `OpenXR tag with arbitrary log noise does NOT open VR_SESSION`() {
        // Reinforce the spec VR-003 negative: the tag alone is not enough.
        val line = lineFor(tag = "OpenXR", msg = "XR: log noise from custom subsystem")
        assertNull(SdkSignatureCatalog.matchOpen(line))
    }

    // ═══════ 2.5 — tag-specificity (spec VR-006) ═══════

    @Test
    fun `bare XR tag (not in allowlist) does NOT open VR_SESSION even with VR-like message`() {
        // The short `XR` tag is deliberately EXCLUDED from the VRRuntime
        // logcatTags allowlist (only `VrApi`, `OVRPlugin`, `OpenXR`,
        // `xrInstance`). A custom app tagging unrelated messages on `XR`
        // (exchange-rate updates, custom XR-prefixed app tags, etc.)
        // must NOT trigger a VR open.
        val line = lineFor(tag = "XR", msg = "exchange rate update USD/EUR = 0.92")
        val matched = SdkSignatureCatalog.matchOpen(line)
        // Either no match, or matches some other SDK — never VRRuntime.
        assertTrue(
            matched == null || matched.sig.sdk != "VRRuntime",
            "bare XR tag must NOT resolve to VRRuntime (allowlist guard)",
        )
    }

    @Test
    fun `Unity tag with xrBeginSession-like prose does NOT open VR_SESSION`() {
        // Unity tag IS in another signature's allowlist (Unity Engine,
        // Unity Ads, Unity Ads Init) but is NOT in the VRRuntime allowlist.
        // Even if a Unity log line happens to mention `xrBeginSession`,
        // it must NOT trigger a VRRuntime open. This guards against
        // double-classification when Unity-driven games log VR-ish prose
        // on the Unity tag instead of OpenXR.
        val line = lineFor(tag = "Unity", msg = "App logged xrBeginSession-like prose for diagnostic")
        val matched = SdkSignatureCatalog.matchOpen(line)
        assertTrue(
            matched == null || matched.sig.sdk != "VRRuntime",
            "Unity tag must NOT resolve to VRRuntime (allowlist guard)",
        )
    }

    // ═══════ Spec VR-007 — confidence: HINT in KDoc ═══════

    @Test
    fun `VRRuntime catalog entry KDoc declares HINT confidence and cites public-doc sources`() {
        // Spec VR-007 — until real-device lab verification exists at
        // `src/test/resources/logcat-fixtures/vr-real-device-*.log`, the
        // VR patterns are NOT promoted to HIGH confidence. The disclosure
        // lives in KDoc adjacent to the catalog entry. This test reads
        // the source file and asserts the disclosure string is present,
        // so a future refactor can't silently strip the disclaimer.
        val catalogFile = java.io.File(
            "src/main/kotlin/com/gameperf/desktop/core/events/SdkSignatureCatalog.kt",
        )
        assertTrue(catalogFile.isFile, "catalog source file not at expected path")
        val text = catalogFile.readText(java.nio.charset.StandardCharsets.UTF_8)
        assertTrue(
            text.contains("confidence: HINT"),
            "VRRuntime KDoc MUST contain literal 'confidence: HINT' disclosure (spec VR-007)",
        )
        assertTrue(
            text.contains("lab-verified", ignoreCase = true) ||
                text.contains("lab verification", ignoreCase = true),
            "VRRuntime KDoc MUST reference lab verification status (spec VR-007)",
        )
        assertTrue(
            text.contains("OpenXR", ignoreCase = true) || text.contains("Khronos", ignoreCase = true),
            "VRRuntime KDoc MUST cite Khronos OpenXR spec / Meta public sample sources (spec VR-007)",
        )
    }

    // ═══════ Phase 3 — Dedup (VR-004) ═══════

    @Test
    fun `VrApi and OpenXR opens within 5s window produce exactly ONE VR_SESSION`() {
        // Spec VR-004 — Meta's runtime layers OpenXR on top of VrApi, so on
        // a real Quest both will fire opens during the same headset session.
        // The detector MUST collapse them via `dedupWindowMs=5_000L` and
        // emit a single VR_SESSION with startMs from the FIRST open.
        val det = newVrDetectorAtTime(1_000L)
        det.handleLogLine(lineFor(tag = "VrApi", msg = "vrapi_EnterVrMode()", tsMs = 1_000L))
        det.handleLogLine(lineFor(tag = "OpenXR", msg = "xrBeginSession succeeded", tsMs = 3_000L))

        val events = det.events.value
        assertEquals(
            1, events.size,
            "VrApi + OpenXR within 5s must collapse into ONE VR_SESSION (got $events)",
        )
        assertEquals(EventType.VR_SESSION, events[0].type)
        assertEquals(1_000L, events[0].startMs, "first open's startMs wins after dedup")
        assertEquals("VRRuntime", events[0].sdkSource)
    }

    @Test
    fun `VrApi and OpenXR opens outside 5s window produce TWO VR_SESSION events`() {
        // Boundary: same two patterns 10 seconds apart fall OUTSIDE the 5s
        // dedup window → two distinct sessions, as the spec demands.
        // v4.9.0 — dedup window is computed in reception-time; advance the
        // controlled clock between the two opens AND refresh the foreground
        // timestamp so the EVT-008 guard does not reject the second open
        // (the guard is `now - lastGameForegroundMs > 2_000 ms`).
        val (det, clock) = newVrDetectorWithControlledClock(1_000L)
        det.handleLogLine(lineFor(tag = "VrApi", msg = "vrapi_EnterVrMode()", tsMs = 99_999L))
        clock[0] = 11_000L
        det.setLastGameForegroundForTest(11_000L) // simulate game still on top
        det.handleLogLine(lineFor(tag = "OpenXR", msg = "xrBeginSession succeeded", tsMs = 99_999L))

        val events = det.events.value
        assertEquals(
            2, events.size,
            "opens 10s apart must NOT dedup (outside 5s window) — got $events",
        )
    }

    // ═══════ Phase 3 — VR_RETURN_TRANSITION synthesis (VR-005) ═══════

    @Test
    fun `close pattern on VR_SESSION emits synthetic VR_RETURN_TRANSITION at endMs`() {
        // Spec VR-005 — when a VR_SESSION closes via a close pattern
        // (`vrapi_LeaveVrMode`), the detector synthesises a follow-up
        // VR_RETURN_TRANSITION with startMs=closed.endMs, endMs+2000,
        // LOW confidence, endInferred=true, signatureMatched containing
        // the synthesised marker.
        // v4.9.0 — endMs is reception-time; advance the controlled clock
        // between open and close. Close is not foreground-guarded so we
        // don't need to refresh lastGameForegroundMs (the guard is open-only).
        val (det, clock) = newVrDetectorWithControlledClock(1_000L)
        det.handleLogLine(lineFor(tag = "VrApi", msg = "vrapi_EnterVrMode()", tsMs = 99_999L))
        clock[0] = 10_000L
        det.handleLogLine(lineFor(tag = "VrApi", msg = "vrapi_LeaveVrMode()", tsMs = 99_999L))

        val events = det.events.value
        assertEquals(2, events.size, "close must produce VR_SESSION + synthesised transition")
        val session = events.first { it.type == EventType.VR_SESSION }
        val transition = events.first { it.type == EventType.VR_RETURN_TRANSITION }

        assertEquals(10_000L, session.endMs)
        assertEquals(10_000L, transition.startMs, "transition startMs == session endMs")
        assertEquals(12_000L, transition.endMs, "transition endMs == startMs + 2000")
        assertEquals(Confidence.LOW, transition.confidence)
        assertEquals("VRRuntime", transition.sdkSource)
        assertTrue(
            transition.signatureMatched.contains("synthesized:vr-return-transition"),
            "synthesised marker must appear in signatureMatched (got '${transition.signatureMatched}')",
        )
        assertTrue(transition.endInferred, "synthesised transition must have endInferred=true")
    }

    @Test
    fun `detector stop on open VR_SESSION synthesises VR_RETURN_TRANSITION with endInferred`() {
        // Spec VR-005 scenario 2 — VR_SESSION never closed via pattern;
        // calling stop() must (a) force-close the VR_SESSION with
        // endInferred=true (existing EVT-006 behaviour) AND (b) synthesise
        // a follow-up VR_RETURN_TRANSITION using the inferred end.
        var clock = 1_000L
        val det = EventDetectorImpl(
            bridge = com.gameperf.desktop.testing.FakeAdbBridge(),
            timeProvider = { clock },
        )
        det.setLastGameForegroundForTest(clock)
        det.handleLogLine(lineFor(tag = "VrApi", msg = "vrapi_EnterVrMode()", tsMs = 1_000L))

        // Advance the clock so stop() stamps endMs=10_000.
        clock = 10_000L
        det.stop()

        val events = det.events.value
        assertEquals(2, events.size, "stop() must produce VR_SESSION + synthesised transition")
        val session = events.first { it.type == EventType.VR_SESSION }
        val transition = events.first { it.type == EventType.VR_RETURN_TRANSITION }

        assertEquals(10_000L, session.endMs)
        assertTrue(session.endInferred, "force-closed VR_SESSION must have endInferred=true")
        assertEquals(10_000L, transition.startMs)
        assertEquals(12_000L, transition.endMs)
        assertEquals(Confidence.LOW, transition.confidence)
        assertTrue(transition.endInferred)
    }

    // ═══════ Phase 4 — Fixture-driven smoke (VR-008) ═══════

    @Test
    fun `vr-oculus-session fixture produces exactly 1 VR_SESSION and 1 VR_RETURN_TRANSITION`() {
        val det = newVrDetectorAtTime(Long.MAX_VALUE)
        val lines = readFixtureLines("logcat-fixtures/vr-oculus-session.log")
        assertTrue(lines.size in 40..70, "fixture has expected length (got ${lines.size})")
        for (raw in lines) {
            val parsed = LogcatLineParser.parse(raw) ?: continue
            det.handleLogLine(parsed)
        }
        det.stop()
        val events = det.events.value
        val sessions = events.count { it.type == EventType.VR_SESSION }
        val transitions = events.count { it.type == EventType.VR_RETURN_TRANSITION }
        assertEquals(1, sessions, "oculus fixture must yield exactly 1 VR_SESSION (events=$events)")
        assertEquals(1, transitions, "oculus fixture must yield exactly 1 VR_RETURN_TRANSITION")
    }

    @Test
    fun `vr-openxr-session fixture produces exactly 1 VR_SESSION and 1 VR_RETURN_TRANSITION`() {
        val det = newVrDetectorAtTime(Long.MAX_VALUE)
        val lines = readFixtureLines("logcat-fixtures/vr-openxr-session.log")
        assertTrue(lines.size in 40..70, "fixture has expected length (got ${lines.size})")
        for (raw in lines) {
            val parsed = LogcatLineParser.parse(raw) ?: continue
            det.handleLogLine(parsed)
        }
        det.stop()
        val events = det.events.value
        val sessions = events.count { it.type == EventType.VR_SESSION }
        val transitions = events.count { it.type == EventType.VR_RETURN_TRANSITION }
        assertEquals(1, sessions, "openxr fixture must yield exactly 1 VR_SESSION (events=$events)")
        assertEquals(1, transitions, "openxr fixture must yield exactly 1 VR_RETURN_TRANSITION")
    }

    // ═══════ helpers ═══════

    private fun lineFor(tag: String, msg: String, tsMs: Long = 0L): LogLine =
        LogLine(tsMs = tsMs, pid = 1234, tid = 5678, level = 'I', tag = tag, msg = msg)

    /**
     * Build an [EventDetectorImpl] with the foreground guard primed at a
     * far-future timestamp so opens at any tsMs pass the EVT-008 check.
     * The clock is fixed at [nowMs] for deterministic close-time assertions
     * on synthesised transitions.
     */
    private fun newVrDetectorAtTime(nowMs: Long): EventDetectorImpl {
        val det = EventDetectorImpl(
            bridge = com.gameperf.desktop.testing.FakeAdbBridge(),
            timeProvider = { nowMs },
        )
        det.setLastGameForegroundForTest(nowMs)
        return det
    }

    /**
     * v4.9.0 — controlled-clock variant for tests pinning open/close
     * reception-times. See engram #503 for the dual-clock fix that made
     * `newVrDetectorAtTime` (constant clock) insufficient for these cases.
     */
    private fun newVrDetectorWithControlledClock(initialMs: Long): Pair<EventDetectorImpl, LongArray> {
        val clock = longArrayOf(initialMs)
        val det = EventDetectorImpl(
            bridge = com.gameperf.desktop.testing.FakeAdbBridge(),
            timeProvider = { clock[0] },
        )
        det.setLastGameForegroundForTest(initialMs)
        return det to clock
    }

    private fun readFixtureLines(resourcePath: String): List<String> {
        val stream = javaClass.classLoader.getResourceAsStream(resourcePath)
            ?: error("missing fixture: $resourcePath")
        return java.io.BufferedReader(
            java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8),
        ).useLines { it.toList() }
    }
}
