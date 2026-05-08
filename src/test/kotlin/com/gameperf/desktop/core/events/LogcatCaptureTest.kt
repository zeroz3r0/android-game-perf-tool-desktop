package com.gameperf.desktop.core.events

import com.gameperf.desktop.testing.FakeAdbBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests for [LogcatCapture] using [FakeAdbBridge] + classpath
 * fixture files. Pure (no real adb processes spawned).
 *
 * Covers EVT-001 (lifecycle), EVT-006 (graceful close), EVT-007 (gap
 * detection feeds confidence downgrades).
 */
class LogcatCaptureTest {

    private val captureScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * No-op callbacks for tests that don't care about the line / gap stream.
     * Defined as [Unit]-returning expressions (rather than `{}`) so detekt's
     * EmptyFunctionBlock rule doesn't flag them — semantically identical.
     */
    private val ignoreLine: (LogLine) -> Unit = { _ -> Unit }
    private val ignoreGap: (Long) -> Unit = { _ -> Unit }

    @AfterTest
    fun teardown() {
        captureScope.cancel()
    }

    @Test
    fun `parses every well-formed line from the admob fixture`() = runBlocking {
        val bridge = FakeAdbBridge().setLogcatFixture("logcat-fixtures/admob-interstitial.log")
        val collected = CopyOnWriteArrayList<LogLine>()
        val capture = LogcatCapture(
            bridge = bridge,
            onLine = { collected += it },
            onGap = ignoreGap,
        )

        val started = capture.start("emu-5554", listOf("Ads:D", "*:S"), captureScope)
        assertTrue(started, "start must succeed when fixture is configured")

        // Wait for the reader to drain the in-memory stream. The fixture is
        // ~30 lines — well under any reasonable timeout.
        waitUntil(timeoutMs = 2_000L) { !capture.running.value }

        capture.stop()
        assertTrue(collected.isNotEmpty(), "expected at least one parsed line")
        // The fixture has Ads / AdActivity / MobileAds / MyGame / Unity tags.
        // We assert the SDK tags are present (proves the parser ran on real bytes).
        val tags = collected.map { it.tag }.toSet()
        assertTrue("AdActivity" in tags, "expected AdActivity tag in parsed output, got $tags")
        assertTrue("MobileAds" in tags, "expected MobileAds tag in parsed output, got $tags")
    }

    @Test
    fun `start returns false when bridge has no fixture configured`() = runBlocking {
        val bridge = FakeAdbBridge() // no fixture set
        val capture = LogcatCapture(bridge, onLine = ignoreLine, onGap = ignoreGap)

        val started = capture.start("emu-5554", listOf("*:S"), captureScope)

        assertFalse(started, "start must fail when fixture is null")
        assertFalse(capture.running.value, "running must remain false")
    }

    @Test
    fun `start is idempotent when already running`() = runBlocking {
        val bridge = FakeAdbBridge().setLogcatFixture("logcat-fixtures/admob-interstitial.log")
        val capture = LogcatCapture(bridge, onLine = ignoreLine, onGap = ignoreGap)

        val first = capture.start("emu-5554", listOf("*:S"), captureScope)
        val second = capture.start("emu-5554", listOf("*:S"), captureScope)

        assertTrue(first, "first start must succeed")
        assertFalse(second, "second start while running must return false")

        capture.stop()
    }

    @Test
    fun `stop is idempotent and safe before start`() = runBlocking {
        val bridge = FakeAdbBridge().setLogcatFixture("logcat-fixtures/admob-interstitial.log")
        val capture = LogcatCapture(bridge, onLine = ignoreLine, onGap = ignoreGap)

        // Before any start
        capture.stop()
        assertFalse(capture.running.value)

        capture.start("emu-5554", listOf("*:S"), captureScope)
        capture.stop()
        capture.stop() // second stop must not throw
        assertFalse(capture.running.value)
    }

    @Test
    fun `running flow transitions false to true to false`() = runBlocking {
        val bridge = FakeAdbBridge().setLogcatFixture("logcat-fixtures/admob-interstitial.log")
        val capture = LogcatCapture(bridge, onLine = ignoreLine, onGap = ignoreGap)

        assertFalse(capture.running.value, "initial state must be false")

        capture.start("emu-5554", listOf("*:S"), captureScope)
        assertTrue(capture.running.value, "running must be true immediately after start")

        // Wait for the in-memory fixture to drain naturally.
        waitUntil(timeoutMs = 2_000L) { !capture.running.value }
        assertFalse(capture.running.value, "running must transition back to false after EOF")
    }

    @Test
    fun `detects gap when stream pauses longer than threshold`() = runBlocking {
        // Build a piped process that emits one line, sleeps > GAP_THRESHOLD_MS,
        // then emits another line. The reader must observe the gap when the
        // second line finally arrives. We use a much smaller threshold-equivalent
        // by exploiting GAP_THRESHOLD_MS directly — sleep just over it.
        val pipeIn = PipedInputStream(8 * 1024)
        val pipeOut = PipedOutputStream(pipeIn)
        val proc = PipedFakeProcess(pipeIn)
        val bridge = ProcessProvidingFakeBridge(proc)

        val gaps = CopyOnWriteArrayList<Long>()
        val lines = CopyOnWriteArrayList<LogLine>()
        val capture = LogcatCapture(
            bridge = bridge,
            onLine = { lines += it },
            onGap = { gaps += it },
        )
        capture.start("emu-5554", listOf("*:S"), captureScope)

        // Emit one line, then sleep > GAP_THRESHOLD_MS, then emit another.
        pipeOut.write("01-15 14:32:18.456  1234  5678 I AdActivity: Showing ad\n".toByteArray())
        pipeOut.flush()
        // Wait for the reader to consume the first line so its lastReceiveMs is set.
        waitUntil(timeoutMs = 1_000L) { lines.isNotEmpty() }
        delay(LogcatCapture.GAP_THRESHOLD_MS + 250L)
        pipeOut.write("01-15 14:32:24.000  1234  5678 I AdActivity: Ad dismissed\n".toByteArray())
        pipeOut.flush()
        pipeOut.close()
        proc.markExitWhenDrained()

        waitUntil(timeoutMs = 8_000L) { !capture.running.value }
        capture.stop()

        assertEquals(2, lines.size, "expected both lines to be parsed")
        assertTrue(
            gaps.any { it > LogcatCapture.GAP_THRESHOLD_MS },
            "expected at least one gap > threshold, got $gaps",
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // Test helpers
    // ─────────────────────────────────────────────────────────────────────

    /** Polling-based wait without coroutine virtual time. */
    private suspend fun waitUntil(timeoutMs: Long, predicate: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!predicate()) delay(10L)
        }
    }
}

/**
 * A [Process] backed by an externally-fed [PipedInputStream]. The test
 * pushes bytes into the pipe and the reader on the other side sees them
 * exactly as if they came from a long-lived adb logcat process.
 */
private class PipedFakeProcess(input: PipedInputStream) : Process() {
    private val inputStream = input
    private val errorStream = ByteArrayInputStream(ByteArray(0))
    private val outputStream = ByteArrayOutputStream()
    @Volatile private var alive = true
    override fun getOutputStream() = outputStream
    override fun getInputStream() = inputStream
    override fun getErrorStream() = errorStream
    override fun waitFor(): Int { alive = false; return 0 }
    override fun exitValue(): Int =
        if (alive) throw IllegalThreadStateException() else 0
    override fun destroy() { alive = false }
    override fun isAlive(): Boolean = alive
    /** Test convenience: flip alive flag once the input pipe is drained. */
    fun markExitWhenDrained() { alive = false }
}

/**
 * [FakeAdbBridge] subclass that hands a pre-built [Process] to the next
 * [startLogcat] caller. Avoids reimplementing all of [com.gameperf.desktop.core.AdbBridgeApi]
 * just to provide a custom logcat process.
 */
private class ProcessProvidingFakeBridge(private val proc: Process) : FakeAdbBridge() {
    override fun startLogcat(deviceId: String, tagArgs: List<String>): Process? = proc
}
