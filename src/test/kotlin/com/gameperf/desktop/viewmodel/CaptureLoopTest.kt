package com.gameperf.desktop.viewmodel

import com.gameperf.desktop.core.model.Device
import com.gameperf.desktop.core.model.DevicePlatform
import com.gameperf.desktop.core.model.FrameSnapshot
import com.gameperf.desktop.core.model.MemSnapshot
import com.gameperf.desktop.core.model.ThermalSnapshot
import com.gameperf.desktop.testing.FakeAdbBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the metrics capture loop inside [AppViewModel.startCapture].
 *
 * These tests drive the loop through [FakeAdbBridge] configured to return
 * scripted metrics. Recording is suppressed via two [FakeAdbBridge.queueNull]
 * calls (COMPACT profile + STANDARD retry) so the loop runs without real ADB
 * processes. The metrics polling, LiveMetrics updates, and state transitions
 * still execute fully.
 *
 * Timing contract:
 *  - selectDevice's coroutine completes in < 50ms with fake bridge → 300ms wait is safe.
 *  - Each metrics iteration takes delay(500) + ~5ms work → 700ms covers 1 iteration.
 *  - stopCapture() sets shouldStop=true; loop exits after current delay(500) then the
 *    stop path runs delay(3000) for moov-atom flushing → we wait 5s after stop.
 *  - durationSeconds=1 auto-stop: 1s loop + 3s stop delay + buffer → 6s wait.
 *
 * These use real delays on Dispatchers.Default (the VM's scope). runTest with
 * virtual time cannot advance the VM's internal coroutines, so runBlocking is used.
 */
class CaptureLoopTest {

    /** FakeAdbBridge with scripted metrics and game package detection. */
    private open class MetricsFake(
        val gamePkg: String = "com.test.game",
        private val frameValue: FrameSnapshot =
            FrameSnapshot(fps = 45, avgFrameTime = 22.2, jankCount = 2, stutterCount = 0),
        private val cpuValue: Int = 55,
        private val memValue: MemSnapshot = MemSnapshot(totalMb = 512L, nativeMb = 256L, javaMb = 128L),
        private val thermalValue: ThermalSnapshot =
            ThermalSnapshot(cpu = 38.0, gpu = 35.0, battery = 31.0, skin = Double.NaN),
    ) : FakeAdbBridge() {
        override fun detectGame(deviceId: String): String = gamePkg
        override fun captureFrames(deviceId: String, pkg: String): FrameSnapshot = frameValue
        override fun captureCpuPercent(deviceId: String): Int = cpuValue
        override fun captureMemory(deviceId: String, pkg: String): MemSnapshot = memValue
        override fun captureTemperature(deviceId: String): ThermalSnapshot = thermalValue
    }

    /**
     * Queue 2 nulls to suppress recording without errors.
     * startSegmentWithRetry calls startScreenRecord TWICE:
     * once with COMPACT profile and once with STANDARD as retry.
     */
    private fun MetricsFake.suppressRecording(): MetricsFake = apply { queueNull(); queueNull() }

    private val testDevice = Device(id = "test-serial", model = "TestDevice", platform = DevicePlatform.ANDROID)

    /** Select device and wait for the internal coroutine to populate gamePackage. */
    private suspend fun AppViewModel.initForCapture(device: Device = testDevice) {
        selectDevice(device)
        delay(300) // selectDevice's scope.launch completes in < 50ms with fake; 300ms is safe
    }

    // ===== 1. State machine transitions =====

    @Test
    fun `startCapture sets isCapturing to true`() {
        runBlocking {
            val vm = AppViewModel(adb = MetricsFake().suppressRecording())
            try {
                vm.initForCapture()
                assertNotNull(vm.gamePackage.value, "game package must be detected before capture")

                vm.startCapture(durationSeconds = 0)
                delay(300)
                assertTrue(vm.isCapturing.value, "isCapturing should be true immediately after startCapture")

                vm.stopCapture()
                delay(5000) // loop exits (≤500ms) + stop delay(3000) + buffer
            } finally {
                vm.cleanup()
            }
        }
    }

    @Test
    fun `stopCapture clears isCapturing after session saves`() {
        runBlocking {
            val vm = AppViewModel(adb = MetricsFake().suppressRecording())
            try {
                vm.initForCapture()

                vm.startCapture(durationSeconds = 0)
                delay(300) // let it start

                vm.stopCapture()
                // After stopCapture: loop exits (≤500ms) + adb.stopScreenRecord(null) (no-op)
                // + delay(3000) moov-atom wait + pullRecordings/concat/report (all instant in fake)
                delay(5000)

                assertFalse(vm.isCapturing.value, "isCapturing should be false after full session cleanup")
            } finally {
                vm.cleanup()
            }
        }
    }

    // ===== 2. Metrics propagation to liveMetrics =====

    @Test
    fun `capture loop updates liveMetrics fps from bridge`() {
        runBlocking {
            val fake = MetricsFake(
                frameValue = FrameSnapshot(fps = 60, avgFrameTime = 16.6, jankCount = 0, stutterCount = 0),
            ).suppressRecording()
            val vm = AppViewModel(adb = fake)
            try {
                vm.initForCapture()

                vm.startCapture(durationSeconds = 0)
                delay(900) // one full iteration: delay(500) + ~50ms work + setup margin

                assertEquals(60, vm.liveMetrics.value.fps, "fps must reflect FakeAdbBridge.captureFrames result")

                vm.stopCapture()
                delay(4500)
            } finally {
                vm.cleanup()
            }
        }
    }

    @Test
    fun `capture loop updates liveMetrics cpu from bridge`() {
        runBlocking {
            val vm = AppViewModel(adb = MetricsFake(cpuValue = 72).suppressRecording())
            try {
                vm.initForCapture()

                vm.startCapture(durationSeconds = 0)
                delay(900)

                assertEquals(72, vm.liveMetrics.value.cpu, "cpu must reflect FakeAdbBridge.captureCpuPercent result")

                vm.stopCapture()
                delay(4500)
            } finally {
                vm.cleanup()
            }
        }
    }

    @Test
    fun `capture loop accumulates fpsHistory over multiple iterations`() {
        runBlocking {
            val vm = AppViewModel(
                adb = MetricsFake(
                    frameValue = FrameSnapshot(fps = 30, avgFrameTime = 33.3, jankCount = 1, stutterCount = 0),
                ).suppressRecording(),
            )
            try {
                vm.initForCapture()

                vm.startCapture(durationSeconds = 0)
                // fpsHistory snapshots every 4 iterations (iterCount % 4 == 0) = 4 × 500ms = 2s.
                // Allow 3s total for setup + 4 iterations + buffer.
                delay(3000)

                val history = vm.liveMetrics.value.fpsHistory
                assertTrue(history.isNotEmpty(), "fpsHistory must be populated after 4 iterations (2s); got size=${history.size}")
                assertTrue(history.all { it == 30 }, "all history entries should be 30 fps, got $history")

                vm.stopCapture()
                delay(4500)
            } finally {
                vm.cleanup()
            }
        }
    }

    // ===== 3. Duration-limited auto-stop =====

    @Test
    fun `capture self-terminates when durationSeconds elapses`() {
        runBlocking {
            val vm = AppViewModel(adb = MetricsFake().suppressRecording())
            try {
                vm.initForCapture()

                vm.startCapture(durationSeconds = 1) // 1-second session
                // 1s loop + delay(3000) stop path + report/history (instant) + buffer
                delay(6000)

                assertFalse(vm.isCapturing.value, "capture should have self-terminated after durationSeconds=1")
            } finally {
                vm.cleanup()
            }
        }
    }

    // ===== v4.3.5 — FPS-resume-after-ad: forced layer-cache invalidation =====

    /**
     * Fake that returns null FrameSnapshots until [nullFramesBeforeRecovery] calls
     * to captureFrames have happened, then starts returning real frames again.
     * Mirrors the exact ad-close scenario: the live cache locks onto a zombie
     * layer, captureFrames returns null repeatedly, the loop must force a cache
     * invalidation, then the next poll finds the fresh layer and recovers.
     */
    private class RecoveringFake(
        val nullFramesBeforeRecovery: Int,
        val recoveryFps: Int = 30,
    ) : FakeAdbBridge() {
        @Volatile var captureFramesCalls: Int = 0
        override fun detectGame(deviceId: String): String = "com.test.game"
        override fun captureFrames(deviceId: String, pkg: String): FrameSnapshot? {
            val n = ++captureFramesCalls
            return if (n <= nullFramesBeforeRecovery) {
                null
            } else {
                FrameSnapshot(fps = recoveryFps, avgFrameTime = 33.3, jankCount = 0, stutterCount = 0)
            }
        }
        override fun captureCpuPercent(deviceId: String): Int = 50
        override fun captureCpuPercent(deviceId: String, pkg: String): Int = 50
        override fun getBatteryLevel(deviceId: String): Int = 80
    }

    @Test
    fun `capture loop invalidates layer cache after 3 consecutive null frames`() {
        runBlocking {
            val fake = RecoveringFake(nullFramesBeforeRecovery = 3)
            fake.queueNull(); fake.queueNull() // suppress recording

            val vm = AppViewModel(adb = fake)
            try {
                vm.initForCapture()

                vm.startCapture(durationSeconds = 0)
                // Need at least 4 captureFrames calls (3 nulls + 1 recovery).
                // Each iteration takes delay(500) + ~50ms work, so 4 iterations = ~2.2s.
                // Give 3500ms to be safe under CI jitter.
                delay(3500)

                assertTrue(
                    fake.invalidateLayerCacheCalls.isNotEmpty(),
                    "expected at least one invalidateLayerCache call after 3 null frames; " +
                        "captureFramesCalls=${fake.captureFramesCalls}, " +
                        "invalidateLayerCacheCalls=${fake.invalidateLayerCacheCalls.size}",
                )
                // Should not invalidate every cycle — only after 3 consecutive nulls.
                // With 4-5 captureFrames calls in the window, expect 1 invalidation.
                assertTrue(
                    fake.invalidateLayerCacheCalls.size <= 2,
                    "must NOT invalidate on every cycle — got " +
                        "${fake.invalidateLayerCacheCalls.size} calls in " +
                        "${fake.captureFramesCalls} iterations",
                )

                vm.stopCapture()
                delay(4500)
            } finally {
                vm.cleanup()
            }
        }
    }

    @Test
    fun `capture loop does not invalidate when frames are non-null`() {
        runBlocking {
            // Healthy path: every captureFrames returns a valid FrameSnapshot.
            // The forced-invalidation counter must reset on every success and
            // never trigger.
            val fake = RecoveringFake(nullFramesBeforeRecovery = 0)
            fake.queueNull(); fake.queueNull()
            val vm = AppViewModel(adb = fake)
            try {
                vm.initForCapture()
                vm.startCapture(durationSeconds = 0)
                delay(3500)
                assertTrue(
                    fake.invalidateLayerCacheCalls.isEmpty(),
                    "must not invalidate while frames are flowing; got " +
                        "${fake.invalidateLayerCacheCalls.size} unwanted calls",
                )
                vm.stopCapture()
                delay(4500)
            } finally {
                vm.cleanup()
            }
        }
    }

    // ===== 4. Guard: no game package =====

    @Test
    fun `startCapture is no-op when no game package detected`() {
        runBlocking {
            // Plain FakeAdbBridge.detectGame returns null → gamePackage stays null → early return
            val vm = AppViewModel(adb = FakeAdbBridge())
            try {
                vm.initForCapture() // gamePackage will be null
                vm.startCapture(durationSeconds = 0)
                delay(200)
                assertFalse(vm.isCapturing.value, "startCapture must be no-op when gamePackage is null")
            } finally {
                vm.cleanup()
            }
        }
    }
}
