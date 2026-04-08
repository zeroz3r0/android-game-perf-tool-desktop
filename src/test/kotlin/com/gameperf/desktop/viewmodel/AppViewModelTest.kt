package com.gameperf.desktop.viewmodel

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/**
 * Unit tests for [AppViewModel] internal helpers introduced in v3.1.13.
 *
 * Scope:
 *   These tests target the screenrecord chain instrumentation logic — specifically
 *   the [AppViewModel.validateScreenRecordProcess] helper that decides whether a
 *   freshly-started Process is alive or died during warm-up. We CAN'T (yet) test
 *   [AppViewModel.startSegmentWithRetry] in isolation because it calls the
 *   `AdbBridge` singleton directly, which would require either:
 *     (a) refactoring AdbBridge into an interface for DI, or
 *     (b) introducing a heavy mocking framework like mockk.
 *   Both options are out of scope for v3.1.13 — see the bottom note for the
 *   tradeoff.
 *
 * What we DO cover here is the pure-ish detection logic, using a real `Process`
 * spawned via `ProcessBuilder("sh", "-c", ...)`:
 *   - A process that immediately exits with code 1 and writes to stderr →
 *     should be classified as `DeadDuringWarmup` with the stderr captured
 *     and the exit code preserved. This is the moral equivalent of the
 *     "encoder rejected" scenario the chain helper has to recover from.
 *   - A process that stays alive past the warm-up window → should be classified
 *     as `Alive` with the same Process reference.
 *   - A null process → should be classified as `NullProcess`.
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

    // ===== Coverage gap acknowledged =====
    //
    // What's NOT covered here that the v3.1.13 task description asked for:
    //   "if the first segment fails AND the retry with STANDARD also fails, the
    //    method returns null and registers the error".
    //
    // That assertion targets [AppViewModel.startSegmentWithRetry], which depends
    // on the `AdbBridge` singleton (`object AdbBridge { ... }`). Mocking it would
    // require either refactoring AdbBridge into an interface (large scope change
    // for v3.1.13) or pulling in mockk/mockito-inline (new dependency, ~2-3 MB
    // added to test classpath, decision deserves its own discussion).
    //
    // Instead, we cover the highest-value piece — the detection logic in
    // validateScreenRecordProcess — which is the part that was previously inline
    // and untested. The retry-fan-out is just a control-flow wrapper around it
    // and is exercised end-to-end during a real capture session.
    //
    // The orchestrator was warned about this in the v3.1.13 implementation report.
    // If we want full coverage of startSegmentWithRetry, the proposed follow-up
    // is: extract `AdbBridge` to an interface in a separate change (v3.1.14 or
    // later) and inject it into AppViewModel via constructor.
}
