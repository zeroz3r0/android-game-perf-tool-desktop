package com.gameperf.desktop.viewmodel

import com.gameperf.desktop.core.AdbBridge
import com.gameperf.desktop.testing.FakeAdbBridge
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/**
 * Unit tests for [AppViewModel] internal helpers introduced in v3.1.13 and
 * extended in v3.1.14.
 *
 * v3.1.13 — validateScreenRecordProcess (pure detection logic, real spawned Process):
 *   - Fast-fail process → classified as DeadDuringWarmup with captured stderr + exit code.
 *   - Long-running process → classified as Alive with same Process reference.
 *   - Null input → classified as NullProcess.
 *
 * v3.1.14 — startSegmentWithRetry (end-to-end using [FakeAdbBridge]):
 *   Before v3.1.14 these couldn't be tested because `startSegmentWithRetry`
 *   called `AdbBridge.startScreenRecord` on the singleton object. v3.1.14
 *   refactors `AdbBridge` into an interface (`AdbBridgeApi`) and plumbs it
 *   through `AppViewModel`'s constructor, so a `FakeAdbBridge` can script
 *   the exact process-lifecycle scenarios the retry logic has to recover
 *   from. Scenarios covered:
 *     - Happy path: first attempt alive → returns that Process, single call.
 *     - Retry path: first attempt dies (encoder rejected), STANDARD retry
 *       alive → returns the retry Process, exactly two calls with the
 *       expected profile progression.
 *     - Double failure: first attempt dies AND STANDARD retry also dies →
 *       returns null; caller is responsible for surfacing a warning.
 *
 * The shorter warm-up (200ms instead of the production 1500ms) keeps the test
 * fast without changing the semantics of the assertion.
 */
class AppViewModelTest {

    // NOTE on the test method bodies below:
    // JUnit 4 requires test methods to have a JVM `void` return type. Using
    // `fun foo() = runBlocking { ... }` infers the return type from the block,
    // which can leave the method signature as `Object` (kotlin.Unit) and trip
    // the runner with `InvalidTestClassError: should be void`. Wrapping the
    // runBlocking in a block body with explicit Unit return keeps the JVM
    // signature as `void`.

    @Test
    fun `validateScreenRecordProcess returns DeadDuringWarmup with stderr when process dies fast`() {
        runBlocking {
            val vm = AppViewModel()
            try {
                // Simulate the "encoder rejected" failure mode: a process that exits
                // immediately with non-zero status and writes a diagnostic line.
                // Because AdbBridge.startScreenRecord uses redirectErrorStream(true),
                // we mirror that here so the validator reads from the inputStream.
                val proc = ProcessBuilder("sh", "-c", "echo 'encoder rejected' >&2; exit 1")
                    .redirectErrorStream(true)
                    .start()

                val result = vm.validateScreenRecordProcess(proc, warmupMs = 200)

                assertTrue(
                    result is AppViewModel.ScreenRecordValidation.DeadDuringWarmup,
                    "expected DeadDuringWarmup but got ${result::class.simpleName}"
                )
                // Smart cast works here because of the assertTrue above.
                assertEquals(1, result.exitCode, "exit code should be 1")
                assertTrue(
                    result.stderr.contains("encoder rejected"),
                    "stderr should contain the 'encoder rejected' line, got: '${result.stderr}'"
                )
            } finally {
                vm.cleanup()
            }
        }
    }

    @Test
    fun `validateScreenRecordProcess returns Alive when process stays running`() {
        runBlocking {
            val vm = AppViewModel()
            try {
                // A process that sleeps longer than the warm-up window — survives the check.
                val proc = ProcessBuilder("sh", "-c", "sleep 2")
                    .redirectErrorStream(true)
                    .start()

                val result = vm.validateScreenRecordProcess(proc, warmupMs = 200)

                assertTrue(
                    result is AppViewModel.ScreenRecordValidation.Alive,
                    "expected Alive but got ${result::class.simpleName}"
                )
                // Smart cast works because of the assertTrue contract above.
                assertNotNull(result.process)
                assertTrue(result.process.isAlive, "process should still be alive")

                // Cleanup the spawned sleep so it doesn't linger past the test.
                proc.destroyForcibly()
            } finally {
                vm.cleanup()
            }
        }
    }

    @Test
    fun `validateScreenRecordProcess returns NullProcess for null input`() {
        runBlocking {
            val vm = AppViewModel()
            try {
                val result = vm.validateScreenRecordProcess(null, warmupMs = 50)
                assertTrue(
                    result is AppViewModel.ScreenRecordValidation.NullProcess,
                    "expected NullProcess but got ${result::class.simpleName}"
                )
            } finally {
                vm.cleanup()
            }
        }
    }

    @Test
    fun `recordChainFailures starts at zero on a fresh AppViewModel`() {
        val vm = AppViewModel()
        try {
            // Just a sanity check on the diagnostic counter introduced in v3.1.13.
            // It's not exposed via a StateFlow because it's a debugging aid, not UI state.
            assertEquals(0, vm.recordChainFailures)
        } finally {
            vm.cleanup()
        }
    }

    // ===== v3.1.14 — startSegmentWithRetry end-to-end via FakeAdbBridge =====

    /**
     * Happy path: first scripted process stays alive past the warm-up → the
     * helper returns that exact Process without retrying. Verifies that when
     * everything works we don't waste a second attempt.
     */
    @Test
    fun `startSegmentWithRetry returns process when first attempt succeeds`() {
        runBlocking {
            val fake = FakeAdbBridge().queueAlive(seconds = 2)
            val vm = AppViewModel(adb = fake)
            try {
                val result = vm.startSegmentWithRetry(
                    deviceId = "fake-device",
                    sessionId = "session-happy",
                    segment = 0,
                    profile = AdbBridge.ScreenRecordProfile.COMPACT,
                )
                assertNotNull(result, "expected a live Process but got null")
                assertTrue(result.isAlive, "returned process should still be alive")

                // Exactly one startScreenRecord call — no retry on success.
                assertEquals(1, fake.startCalls.size, "should not retry when first attempt succeeds")
                assertEquals(
                    AdbBridge.ScreenRecordProfile.COMPACT,
                    fake.startCalls[0].profile,
                    "first call should use the requested profile"
                )
                // Clean up the spawned sleep so it doesn't linger past the test.
                result.destroyForcibly()
            } finally {
                vm.cleanup()
            }
        }
    }

    /**
     * Retry path: first scripted process dies fast (encoder rejected), second
     * scripted process stays alive → helper returns the second Process. Verifies
     * that the profile progression is COMPACT → STANDARD and that both calls
     * actually went through.
     */
    @Test
    fun `startSegmentWithRetry retries with STANDARD when first profile fails with encoder rejected`() {
        runBlocking {
            val fake = FakeAdbBridge()
                .queueFastFail("encoder rejected")  // first attempt: COMPACT, dies
                .queueAlive(seconds = 2)            // retry: STANDARD, alive
            val vm = AppViewModel(adb = fake)
            try {
                val result = vm.startSegmentWithRetry(
                    deviceId = "fake-device",
                    sessionId = "session-retry",
                    segment = 0,
                    profile = AdbBridge.ScreenRecordProfile.COMPACT,
                )
                assertNotNull(result, "expected retry to return a live Process")
                assertTrue(result.isAlive, "retry process should still be alive")

                // Two calls: COMPACT first, then STANDARD retry.
                assertEquals(2, fake.startCalls.size, "should retry exactly once after first fails")
                assertEquals(
                    AdbBridge.ScreenRecordProfile.COMPACT,
                    fake.startCalls[0].profile,
                    "first call should be COMPACT (the requested profile)"
                )
                assertEquals(
                    AdbBridge.ScreenRecordProfile.STANDARD,
                    fake.startCalls[1].profile,
                    "retry should escalate to STANDARD profile"
                )
                result.destroyForcibly()
            } finally {
                vm.cleanup()
            }
        }
    }

    /**
     * Double-failure path: first attempt dies AND the STANDARD retry also dies.
     * This was the test that the v3.1.13 implementation report acknowledged as
     * uncoverable without the `AdbBridgeApi` refactor. Now it IS coverable.
     * Verifies that the helper returns null, that both calls were made with
     * the expected profile progression, and that `recordChainFailures` is NOT
     * incremented by this helper (that's the caller's responsibility inside
     * the chain loop).
     */
    @Test
    fun `startSegmentWithRetry returns null when first attempt fails and STANDARD retry also fails`() {
        runBlocking {
            val fake = FakeAdbBridge()
                .queueFastFail("encoder rejected")             // first attempt: COMPACT dies
                .queueFastFail("STANDARD also unsupported")    // retry: STANDARD also dies
            val vm = AppViewModel(adb = fake)
            try {
                val result = vm.startSegmentWithRetry(
                    deviceId = "fake-device",
                    sessionId = "session-double-fail",
                    segment = 0,
                    profile = AdbBridge.ScreenRecordProfile.COMPACT,
                )
                assertNull(result, "expected null after both attempts failed, got $result")

                // Exactly two calls: the initial COMPACT and the STANDARD retry.
                assertEquals(2, fake.startCalls.size, "both attempts should have been made")
                assertEquals(AdbBridge.ScreenRecordProfile.COMPACT, fake.startCalls[0].profile)
                assertEquals(AdbBridge.ScreenRecordProfile.STANDARD, fake.startCalls[1].profile)

                // recordChainFailures is incremented by the chain loop (inside recordJob),
                // NOT by startSegmentWithRetry itself. So the counter is still 0 here.
                // The sanity-check test `recordChainFailures starts at zero` already
                // guards the initial value; this assertion documents the contract.
                assertEquals(
                    0,
                    vm.recordChainFailures,
                    "startSegmentWithRetry itself must not touch the chain-failure counter"
                )
            } finally {
                vm.cleanup()
            }
        }
    }

    // ===== Coverage gap DELIBERATELY not closed in v3.1.14 =====
    //
    // "chain loop stops and sets captureWarning when a mid-chain segment dies
    //  after retry" — this would require driving the entire `recordJob` loop
    // inside `startCapture`, which is tangled with `pullRecordings`,
    // `concatSegments`, `ReportGenerator.generate`, `SessionHistory.addEntry`,
    // and the 175_000ms `delay` that gates each chain iteration. Covering it
    // end-to-end means either:
    //   (a) refactoring `recordJob` into a standalone testable helper that
    //       takes a time provider + step count — non-trivial scope, affects
    //       the production happy path,
    //   (b) injecting a clock/scheduler into the coroutine — big public API
    //       change,
    // Both fall outside the v3.1.14 scope ("make startSegmentWithRetry
    // testable + highlight time button"). Left as a TODO for a later change
    // if we ever hit a regression in the chain-loop plumbing specifically.
    //
    // The three new tests above cover the CORE logic (the retry decision tree)
    // so a regression in the retry math would be caught even without driving
    // the full recordJob.
}
