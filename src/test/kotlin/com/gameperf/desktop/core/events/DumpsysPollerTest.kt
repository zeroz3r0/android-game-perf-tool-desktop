package com.gameperf.desktop.core.events

import com.gameperf.desktop.testing.FakeAdbBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit + smoke tests for [DumpsysPoller].
 *
 * The parser is exercised directly via [DumpsysPoller.parseFrames] (most
 * of the surface). One small integration test using [FakeAdbBridge]
 * verifies the poll loop actually fires.
 *
 * Covers EVT-004 scenarios.
 */
class DumpsysPollerTest {

    private val pollScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** No-op `onActivityStack` for parser-only tests. Defined as an explicit
     *  Unit-returning expression to keep detekt's EmptyFunctionBlock happy. */
    private val ignoreFrames: (List<ActivityFrame>) -> Unit = { _ -> Unit }

    @AfterTest
    fun teardown() {
        pollScope.cancel()
    }

    // ─────────────────────────────────────────────────────────────────────
    // parseFrames — pure / direct
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `parses a single cmp from minimal dumpsys output`() {
        val poller = DumpsysPoller(FakeAdbBridge(), onActivityStack = ignoreFrames)
        val output = "  Hist  #0: ActivityRecord{abc u0 com.example.app/.MainActivity t1234} cmp=com.example.app/.MainActivity"

        val frames = poller.parseFrames(output)

        assertEquals(1, frames.size)
        assertEquals("com.example.app/.MainActivity", frames[0].cmp)
    }

    @Test
    fun `parses AdMob AdActivity cmp from real-shape dumpsys output`() {
        val poller = DumpsysPoller(FakeAdbBridge(), onActivityStack = ignoreFrames)
        // Real-shape sample — taken from the `dumpsys activity activities`
        // output structure on a device showing an interstitial ad.
        val output = """
              Stack #1:
                Task id #1234
                  * TaskRecord{abc #1234 A=com.example I=com.example/.MainActivity}
                    Hist  #0: ActivityRecord{def u0 com.example/com.google.android.gms.ads.AdActivity t1234}
                      packageName=com.example processName=com.example
                      cmp=com.example/com.google.android.gms.ads.AdActivity
                    Hist  #1: ActivityRecord{ghi u0 com.example/.MainActivity t1234}
                      cmp=com.example/.MainActivity
        """.trimIndent()

        val frames = poller.parseFrames(output)

        assertEquals(2, frames.size)
        assertEquals("com.example/com.google.android.gms.ads.AdActivity", frames[0].cmp)
        assertEquals("com.example/.MainActivity", frames[1].cmp)
    }

    @Test
    fun `caps at top-of-stack limit when many cmp matches present`() {
        val poller = DumpsysPoller(FakeAdbBridge(), onActivityStack = ignoreFrames)
        val output = (1..10).joinToString("\n") { "    cmp=com.app$it/.Activity" }

        val frames = poller.parseFrames(output)

        assertEquals(DumpsysPoller.TOP_OF_STACK_LIMIT, frames.size)
        // The first TOP_OF_STACK_LIMIT entries (in document order) must win.
        assertEquals("com.app1/.Activity", frames[0].cmp)
        assertEquals("com.app${DumpsysPoller.TOP_OF_STACK_LIMIT}/.Activity", frames.last().cmp)
    }

    @Test
    fun `returns empty list when output has no cmp tokens`() {
        val poller = DumpsysPoller(FakeAdbBridge(), onActivityStack = ignoreFrames)

        assertTrue(poller.parseFrames("nothing useful here").isEmpty())
        assertTrue(poller.parseFrames("packageName=com.example something else").isEmpty())
    }

    @Test
    fun `returns empty list on empty input`() {
        val poller = DumpsysPoller(FakeAdbBridge(), onActivityStack = ignoreFrames)

        assertTrue(poller.parseFrames("").isEmpty())
        assertTrue(poller.parseFrames("    \n  \n").isEmpty())
    }

    @Test
    fun `does not crash on garbage binary input`() {
        val poller = DumpsysPoller(FakeAdbBridge(), onActivityStack = ignoreFrames)
        val garbage = String(ByteArray(256) { it.toByte() })

        // Must not throw — null/empty result both acceptable.
        val frames = poller.parseFrames(garbage)
        assertTrue(frames.size <= DumpsysPoller.TOP_OF_STACK_LIMIT)
    }

    @Test
    fun `does not match a cmp token without slash`() {
        val poller = DumpsysPoller(FakeAdbBridge(), onActivityStack = ignoreFrames)
        // The pattern requires a `/` between package and activity.
        // `cmp=NoSlashHere` should not produce an ActivityFrame.
        val output = "cmp=NoSlashHere cmp=com.real/.Act"

        val frames = poller.parseFrames(output)

        assertEquals(1, frames.size)
        assertEquals("com.real/.Act", frames[0].cmp)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Smoke test — actual poll loop
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `polls and emits frames when shell returns canned dumpsys output`() = runBlocking {
        val canned = "Hist #0: cmp=com.example/com.google.android.gms.ads.AdActivity"
        val bridge = FakeAdbBridge()
        bridge.shellResponses["dumpsys activity"] = canned

        val emissions = CopyOnWriteArrayList<List<ActivityFrame>>()
        val poller = DumpsysPoller(bridge, onActivityStack = { emissions += it })

        poller.start("emu-5554", pollScope)

        // Wait for at least one emission. Real wall-clock — the loop runs
        // at 1 Hz so this typically completes in ~10 ms (first iteration
        // fires before the first delay).
        withTimeout(2_000L) {
            while (emissions.isEmpty()) delay(10L)
        }

        poller.stop()

        assertTrue(emissions.isNotEmpty(), "poller should have emitted at least once")
        assertEquals(1, emissions.first().size)
        assertEquals(
            "com.example/com.google.android.gms.ads.AdActivity",
            emissions.first()[0].cmp,
        )
    }

    @Test
    fun `stop halts further emissions`() = runBlocking {
        val bridge = FakeAdbBridge()
        bridge.shellResponses["dumpsys"] = "cmp=com.example/.MainActivity"
        val emissions = CopyOnWriteArrayList<List<ActivityFrame>>()
        val poller = DumpsysPoller(bridge, onActivityStack = { emissions += it })

        poller.start("emu-5554", pollScope)
        withTimeout(2_000L) {
            while (emissions.isEmpty()) delay(10L)
        }
        val countAtStop = emissions.size
        poller.stop()

        // After stop, give the loop time to (not) tick again.
        delay(DumpsysPoller.POLL_INTERVAL_MS + 200L)

        // Allow at most 1 extra emission for an in-flight iteration that
        // already passed the isActive check before stop arrived.
        assertTrue(
            emissions.size - countAtStop <= 1,
            "expected no further emissions after stop; got ${emissions.size - countAtStop}",
        )
    }

    @Test
    fun `start is idempotent while already running`() = runBlocking {
        val bridge = FakeAdbBridge()
        bridge.shellResponses["dumpsys"] = "cmp=com.example/.A"
        val poller = DumpsysPoller(bridge, onActivityStack = ignoreFrames)

        poller.start("emu-5554", pollScope)
        poller.start("emu-5554", pollScope) // must not throw / double-spawn

        // Let one tick happen then stop.
        delay(50L)
        poller.stop()

        // The bridge recorded shell() calls — over a 50 ms window with a
        // 1 Hz poller we expect at most 1 (first iteration). If the second
        // start spawned a parallel job we'd see > 1 within the window.
        assertTrue(
            bridge.shellCalls.size <= 1,
            "second start spawned a duplicate poll loop (${bridge.shellCalls.size} calls)",
        )
    }

    @Test
    fun `stop is safe before start`() {
        val poller = DumpsysPoller(FakeAdbBridge(), onActivityStack = ignoreFrames)
        // Should not throw.
        poller.stop()
        assertFalse(false) // anchor
    }
}
