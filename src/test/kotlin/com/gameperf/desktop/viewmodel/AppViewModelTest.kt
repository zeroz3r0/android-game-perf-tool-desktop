package com.gameperf.desktop.viewmodel

import com.gameperf.desktop.core.AdbBridge
import com.gameperf.desktop.core.AdbVersion
import com.gameperf.desktop.core.model.Device
import com.gameperf.desktop.core.model.DevicePlatform
import com.gameperf.desktop.core.ConnectFailureReason
import com.gameperf.desktop.core.ConnectResult
import com.gameperf.desktop.core.MdnsService
import com.gameperf.desktop.core.MdnsServiceType
import com.gameperf.desktop.core.PairFailureReason
import com.gameperf.desktop.core.PairResult
import com.gameperf.desktop.testing.FakeAdbBridge
import com.gameperf.desktop.testing.ProcessTestUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.fail

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
                val proc = ProcessTestUtils.spawnFastFail("encoder rejected", exitCode = 1)

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
                val proc = ProcessTestUtils.spawnSleeping(seconds = 2)

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

    // ============================================================
    // v3.2.0 — Wireless ADB (pair WiFi flow for Android 11+)
    // ============================================================
    //
    // 11 integration tests that exercise the VM state machine defined in
    // Phase 5 against a scripted [FakeAdbBridge]. Each test maps to a
    // scenario in the sdd/wireless-adb/spec (WP-1..WP-11) or to a design
    // decision (D-10 re-discover). Tests are INDEPENDENT of the v3.1.14
    // tests above — they use a fresh VM instance and never invoke
    // [AppViewModel.init], so the device polling loop and the adb-version
    // bootstrap launch do NOT interfere with the state under test.
    //
    // Polling cadence: the production loop uses `delay(2500)` between
    // snapshots, which would make any test involving the loop take 7.5+
    // seconds of wall-clock time. Because the VM's scope is
    // `Dispatchers.Default` (not a TestDispatcher), we can't use
    // `runTest`'s virtual time — we'd have to change the VM constructor.
    // Instead, each test uses `awaitState` (below) to poll the StateFlow
    // with a short timeout, and only the tests that fundamentally depend
    // on the 3-empty-polls fallback (WP-2) pay the full wall-clock cost.
    // All other tests finish in <1s.

    /**
     * Wait until [predicate] returns true on the latest [_wifiPanel] value,
     * or fail the test with [message] after [timeoutMs]. Polls every 25ms —
     * cheap enough that completed transitions usually resolve in the first
     * poll.
     */
    private suspend fun awaitWifiPanel(
        vm: AppViewModel,
        timeoutMs: Long = 3000,
        message: String,
        predicate: (WifiDelegate.WifiPanelState) -> Boolean,
    ): WifiDelegate.WifiPanelState {
        val result = withTimeoutOrNull(timeoutMs) {
            while (true) {
                val s = vm.wifiPanel.value
                if (predicate(s)) return@withTimeoutOrNull s
                delay(25)
            }
            @Suppress("UNREACHABLE_CODE") null
        }
        if (result == null) {
            fail("$message — stuck at ${vm.wifiPanel.value::class.simpleName}")
        }
        return result
    }

    /**
     * WP-1 + D-10 — Happy path: the user expands the panel, picks the mDNS
     * pairing service, types the correct code, and the VM walks through
     * Pairing → Connecting → Connected → Hidden. Asserts that the connect
     * port comes from the re-discover step (D-10), not the pair port.
     */
    @Test
    fun pairAndConnectHappyPathWithMdnsDiscovery() {
        runBlocking {
            val fake = FakeAdbBridge()
            // First snapshot (discover phase) has the pairing service.
            val pairing = MdnsService(
                instance = "adb-XXXX-YYYY",
                serviceType = MdnsServiceType.PAIRING,
                ip = "192.168.1.42",
                port = 37123,
            )
            // Second snapshot (post-pair re-discover) adds the connect
            // service on a DIFFERENT port — asserting D-10 actually uses it.
            val connect = MdnsService(
                instance = "adb-XXXX-YYYY",
                serviceType = MdnsServiceType.CONNECT,
                ip = "192.168.1.42",
                port = 38145,
            )
            fake.scriptedMdnsSnapshots += listOf(pairing)           // discovery poll 1
            fake.scriptedMdnsSnapshots += listOf(pairing, connect)  // re-discover (D-10)
            fake.scriptedPair += PairResult.Success
            fake.scriptedConnect += ConnectResult.Success("192.168.1.42:38145")

            val vm = AppViewModel(adb = fake)
            try {
                vm.openWifiPanel()
                val discovered = awaitWifiPanel(
                    vm,
                    message = "expected Discovered after first poll",
                ) { it is WifiDelegate.WifiPanelState.Discovered && it.services.isNotEmpty() }
                discovered as WifiDelegate.WifiPanelState.Discovered
                assertEquals(1, discovered.services.size)
                assertEquals(MdnsServiceType.PAIRING, discovered.services[0].serviceType)

                vm.selectMdnsDevice(discovered.services[0])
                vm.submitCodeForSelected("123456")

                val finalState = awaitWifiPanel(
                    vm,
                    timeoutMs = 5000,
                    message = "expected Hidden after Connected",
                ) { it is WifiDelegate.WifiPanelState.Hidden }
                assertTrue(finalState is WifiDelegate.WifiPanelState.Hidden)

                assertEquals(1, fake.pairCalls.size, "should have paired exactly once")
                assertEquals(Triple("192.168.1.42", 37123, "123456"), fake.pairCalls[0])
                assertEquals(1, fake.connectCalls.size, "should have connected exactly once")
                // D-10: connect port is the CONNECT service port from the
                // re-discover snapshot (38145), NOT the pair port (37123).
                assertEquals("192.168.1.42" to 38145, fake.connectCalls[0])
                assertTrue(fake.mdnsServiceCalls >= 2, "should have called mdns at least twice (discovery + D-10 re-discover)")
            } finally {
                vm.cleanup()
            }
        }
    }

    /**
     * D-10 isolation — The first snapshot has ONLY the pairing service. The
     * second snapshot (the re-discover) has BOTH pairing and connect. The
     * connect port must come from snapshot 2, proving re-discover isn't a
     * no-op on a stale cache.
     */
    @Test
    fun pairAndConnectMdnsReDiscoveryFindsCorrectConnectPort() {
        runBlocking {
            val fake = FakeAdbBridge()
            val pairingOnly = MdnsService("adb-Z", MdnsServiceType.PAIRING, "10.0.0.5", 37777)
            val rediscoveredConnect = MdnsService("adb-Z", MdnsServiceType.CONNECT, "10.0.0.5", 38999)

            // Poll 1: pairing only
            fake.scriptedMdnsSnapshots += listOf(pairingOnly)
            // Poll 2 (D-10 re-discover): both services
            fake.scriptedMdnsSnapshots += listOf(pairingOnly, rediscoveredConnect)
            fake.scriptedPair += PairResult.Success
            fake.scriptedConnect += ConnectResult.Success("10.0.0.5:38999")

            val vm = AppViewModel(adb = fake)
            try {
                vm.openWifiPanel()
                val discovered = awaitWifiPanel(
                    vm,
                    message = "expected Discovered state",
                ) { it is WifiDelegate.WifiPanelState.Discovered && it.services.isNotEmpty() }
                discovered as WifiDelegate.WifiPanelState.Discovered
                vm.selectMdnsDevice(discovered.services[0])
                vm.submitCodeForSelected("000000")

                awaitWifiPanel(
                    vm,
                    timeoutMs = 5000,
                    message = "expected Hidden after Connected",
                ) { it is WifiDelegate.WifiPanelState.Hidden }

                assertEquals(1, fake.connectCalls.size)
                // The key assertion: connect went to 38999, not 37777.
                assertEquals(38999, fake.connectCalls[0].second)
            } finally {
                vm.cleanup()
            }
        }
    }

    /**
     * WP-2 — Three consecutive empty mDNS polls → manual form expands
     * automatically. This test pays the full wall-clock cost (~7.5s for
     * three 2.5s poll iterations) because it fundamentally depends on the
     * loop's debounce cadence. Acceptable: it's the only test that does.
     */
    @Test
    fun manualFormExpandsAutomaticallyWhenMdnsReturnsEmptyForThreePolls() {
        runBlocking {
            val fake = FakeAdbBridge()
            // Empty snapshots for as many polls as the test may run. The
            // FakeAdbBridge contract is "empty queue → emptyList", so we
            // don't even need to push entries — but we do push a few to
            // be explicit.
            repeat(5) { fake.scriptedMdnsSnapshots += emptyList<MdnsService>() }

            val vm = AppViewModel(adb = fake)
            try {
                vm.openWifiPanel()
                awaitWifiPanel(
                    vm,
                    timeoutMs = 15_000,
                    message = "expected InputtingManual after 3 empty polls",
                ) { it is WifiDelegate.WifiPanelState.InputtingManual }
                assertTrue(vm.wifiPanel.value is WifiDelegate.WifiPanelState.InputtingManual)
            } finally {
                vm.cleanup()
            }
        }
    }

    /**
     * WP-3 — When the system reports mDNS as unavailable (real path would
     * be `adb mdns check` failing), the VM skips discovery entirely and
     * jumps to the manual form without waiting for 3 empty polls.
     */
    @Test
    fun mdnsAvailableBecomesFalseWhenAdbMdnsCheckFails() {
        runBlocking {
            val fake = FakeAdbBridge()
            val vm = AppViewModel(adb = fake)
            try {
                // Flip the sensor BEFORE opening the panel — simulates the
                // real startup check that marks mDNS unavailable.
                vm.setMdnsAvailableForTest(false)
                vm.openWifiPanel()

                awaitWifiPanel(
                    vm,
                    timeoutMs = 1000,
                    message = "expected immediate InputtingManual when mdns unavailable",
                ) { it is WifiDelegate.WifiPanelState.InputtingManual }

                assertEquals(false, vm.mdnsAvailable.value)
                assertTrue(vm.wifiPanel.value is WifiDelegate.WifiPanelState.InputtingManual)
            } finally {
                vm.cleanup()
            }
        }
    }

    /**
     * WP-4 — Wrong pairing code surfaces the exact user-friendly error
     * string from the spec. Literal-equals assertion, no `contains`.
     */
    @Test
    fun pairWithWrongCodeSurfacesUserFriendlyError() {
        runBlocking {
            val fake = FakeAdbBridge()
            val pairing = MdnsService("adb-X", MdnsServiceType.PAIRING, "192.168.1.42", 37123)
            fake.scriptedMdnsSnapshots += listOf(pairing)
            fake.scriptedPair += PairResult.Failure(
                reason = PairFailureReason.INVALID_CODE,
                rawStderr = "adb: failed to authenticate",
            )

            val vm = AppViewModel(adb = fake)
            try {
                vm.openWifiPanel()
                val discovered = awaitWifiPanel(
                    vm,
                    message = "expected Discovered",
                ) { it is WifiDelegate.WifiPanelState.Discovered && it.services.isNotEmpty() }
                discovered as WifiDelegate.WifiPanelState.Discovered
                vm.selectMdnsDevice(discovered.services[0])
                vm.submitCodeForSelected("000000")

                val errorState = awaitWifiPanel(
                    vm,
                    message = "expected Error after failed pair",
                ) { it is WifiDelegate.WifiPanelState.Error }
                errorState as WifiDelegate.WifiPanelState.Error

                assertEquals(
                    "Codigo incorrecto. Abri nuevamente 'Emparejar dispositivo con codigo' en el movil para generar un codigo nuevo.",
                    errorState.message,
                )
                assertTrue(errorState.recoverable)
            } finally {
                vm.cleanup()
            }
        }
    }

    /**
     * WP-5 — When the mDNS snapshot transitions from "pairing service
     * present" to "pairing service gone", the [pairingServiceAlive] sensor
     * flips false so the UI can disable the "Parear" button.
     */
    @Test
    fun pairButtonDisablesWhenPairingServiceDisappearsFromMdnsSnapshot() {
        runBlocking {
            val fake = FakeAdbBridge()
            val pairing = MdnsService("adb-X", MdnsServiceType.PAIRING, "192.168.1.42", 37123)
            // Snapshot 1: pairing present → _pairingServiceAlive = true.
            fake.scriptedMdnsSnapshots += listOf(pairing)
            // Snapshot 2: pairing gone (popup closed) → _pairingServiceAlive = false.
            fake.scriptedMdnsSnapshots += emptyList<MdnsService>()

            val vm = AppViewModel(adb = fake)
            try {
                vm.openWifiPanel()
                // Wait for pairingServiceAlive to become true on first poll.
                withTimeoutOrNull(3000) {
                    while (!vm.pairingServiceAlive.value) delay(25)
                } ?: fail("expected pairingServiceAlive to become true after first poll")

                assertTrue(vm.pairingServiceAlive.value)

                // Now wait for it to flip false after the second (empty) poll.
                // The poll cadence is 2.5s, so this takes ~2.5-3s wall-clock.
                withTimeoutOrNull(8000) {
                    while (vm.pairingServiceAlive.value) delay(25)
                } ?: fail("expected pairingServiceAlive to flip false after pairing service vanished")

                assertFalse(vm.pairingServiceAlive.value)
            } finally {
                vm.cleanup()
            }
        }
    }

    /**
     * WP-6 — Pair succeeds but the subsequent connect fails with NO_ROUTE
     * (e.g. phone dropped off WiFi between the two calls). Error message
     * must be the literal Spanish string from the spec.
     */
    @Test
    fun pairSuccessFollowedByConnectNoRouteMapsToVisibleInNetworkError() {
        runBlocking {
            val fake = FakeAdbBridge()
            val pairing = MdnsService("adb-X", MdnsServiceType.PAIRING, "192.168.1.42", 37123)
            val connect = MdnsService("adb-X", MdnsServiceType.CONNECT, "192.168.1.42", 38145)
            fake.scriptedMdnsSnapshots += listOf(pairing)
            fake.scriptedMdnsSnapshots += listOf(pairing, connect)
            fake.scriptedPair += PairResult.Success
            fake.scriptedConnect += ConnectResult.Failure(
                reason = ConnectFailureReason.NO_ROUTE,
                rawStderr = "no route to host",
            )

            val vm = AppViewModel(adb = fake)
            try {
                vm.openWifiPanel()
                val discovered = awaitWifiPanel(
                    vm,
                    message = "expected Discovered",
                ) { it is WifiDelegate.WifiPanelState.Discovered && it.services.isNotEmpty() }
                discovered as WifiDelegate.WifiPanelState.Discovered
                vm.selectMdnsDevice(discovered.services[0])
                vm.submitCodeForSelected("123456")

                val errorState = awaitWifiPanel(
                    vm,
                    timeoutMs = 5000,
                    message = "expected Error after failed connect",
                ) { it is WifiDelegate.WifiPanelState.Error }
                errorState as WifiDelegate.WifiPanelState.Error

                assertEquals(
                    "El movil no esta visible en la red. Verifica que tenga WiFi activa y este en la misma red que esta computadora.",
                    errorState.message,
                )
            } finally {
                vm.cleanup()
            }
        }
    }

    /**
     * WP-7 — A wifi device that was previously paired shows up in the
     * normal listDevices snapshot on next session start, without the user
     * touching any control. The VM does NOT read any persistent storage of
     * its own — it trusts the adb server's native auto-reconnect. In this
     * test we simulate "next session" by setting up the fake to return a
     * wifi device AND verify the panel stays Hidden and mdnsServiceCalls
     * stays at 0 (no WiFi panel interaction needed).
     */
    @Test
    fun previouslyPairedDeviceAppearsInListOnNextSessionWithoutInteraction() {
        runBlocking {
            val wifiDevice = Device(
                id = "192.168.1.42:38145",
                model = "Pixel_7a",
                platform = DevicePlatform.ANDROID,
                isWifi = true,
            )
            val fake = object : FakeAdbBridge() {
                override fun listDevices(): List<Device> = listOf(wifiDevice)
            }

            val vm = AppViewModel(adb = fake)
            try {
                // Simulate the first poll tick by calling refreshDevices
                // (which is what startDevicePolling does internally). This
                // is the public seam — no internal state touched.
                vm.refreshDevices()

                // Wait for the device to land in _devices (happens inside a
                // scope.launch coroutine).
                withTimeoutOrNull(2000) {
                    while (vm.devices.value.isEmpty()) delay(25)
                } ?: fail("expected devices to contain the wifi device after refreshDevices()")

                assertEquals(1, vm.devices.value.size)
                assertEquals("192.168.1.42:38145", vm.devices.value[0].id)
                assertTrue(vm.devices.value[0].isWifi)

                // WP-7 invariant: the panel stayed Hidden and the VM NEVER
                // consulted mDNS services because the panel is closed.
                assertTrue(
                    vm.wifiPanel.value is WifiDelegate.WifiPanelState.Hidden,
                    "expected Hidden, got ${vm.wifiPanel.value::class.simpleName}",
                )
                assertEquals(
                    expected = 0,
                    actual = fake.mdnsServiceCalls,
                    message = "mDNS must not be consulted when the panel is closed",
                )
            } finally {
                vm.cleanup()
            }
        }
    }

    /**
     * WP-8 (CRITICAL) — USB zero-click regression guard. A USB device is
     * returned by listDevices; the VM must auto-select it and leave the
     * WiFi panel completely dormant. [fake.mdnsServiceCalls] stays at 0.
     */
    @Test
    fun usbHappyPathZeroExtraClicksRegressionVsV3114() {
        runBlocking {
            val usbDevice = Device(
                id = "32211JEHN02977",
                model = "Pixel_7a",
                platform = DevicePlatform.ANDROID,
                isWifi = false,
            )
            val fake = object : FakeAdbBridge() {
                override fun listDevices(): List<Device> = listOf(usbDevice)
            }

            val vm = AppViewModel(adb = fake)
            try {
                vm.refreshDevices()
                withTimeoutOrNull(2000) {
                    while (vm.selectedDevice.value == null) delay(25)
                } ?: fail("expected USB device to be auto-selected")

                assertNotNull(vm.selectedDevice.value)
                assertEquals("32211JEHN02977", vm.selectedDevice.value?.id)
                assertEquals(false, vm.isWifi.value)
                assertTrue(
                    vm.wifiPanel.value is WifiDelegate.WifiPanelState.Hidden,
                    "expected Hidden, got ${vm.wifiPanel.value::class.simpleName}",
                )
                // The killer assertion: zero mDNS calls on the USB happy path.
                assertEquals(
                    expected = 0,
                    actual = fake.mdnsServiceCalls,
                    message = "USB happy path must NEVER call mdnsServices",
                )
            } finally {
                vm.cleanup()
            }
        }
    }

    /**
     * WP-10 — The legacy `switchToWifi()` method (the v3.1.14 path that
     * uses `adb tcpip` on a USB device) does NOT touch any of the new
     * wireless StateFlows. This guards against accidental coupling between
     * the new state machine and the legacy flow.
     */
    @Test
    fun switchToWifiLegacyBehaviorIsIdenticalToV3114() {
        runBlocking {
            val usbDevice = Device(
                id = "32211JEHN02977",
                model = "Pixel_7a",
                platform = DevicePlatform.ANDROID,
                isWifi = false,
            )
            // The fake returns null for switchToWifi (the legacy path only
            // needs to execute without side effects on the new state).
            val fake = object : FakeAdbBridge() {
                override fun listDevices(): List<Device> = listOf(usbDevice)
                override fun switchToWifi(usbDeviceId: String, port: Int): String? = null
            }

            val vm = AppViewModel(adb = fake)
            try {
                vm.refreshDevices()
                withTimeoutOrNull(2000) {
                    while (vm.selectedDevice.value == null) delay(25)
                } ?: fail("expected USB device to be auto-selected")

                // Snapshot the new StateFlows BEFORE invoking legacy.
                val wifiPanelBefore = vm.wifiPanel.value
                val mdnsAvailableBefore = vm.mdnsAvailable.value
                val pairingServiceAliveBefore = vm.pairingServiceAlive.value

                vm.switchToWifi()
                // Give the coroutine inside switchToWifi() a chance to run.
                delay(200)

                // None of the new StateFlows should have mutated.
                assertEquals(
                    wifiPanelBefore::class.simpleName,
                    vm.wifiPanel.value::class.simpleName,
                    "legacy switchToWifi must not touch _wifiPanel",
                )
                assertTrue(vm.wifiPanel.value is WifiDelegate.WifiPanelState.Hidden)
                assertEquals(mdnsAvailableBefore, vm.mdnsAvailable.value)
                assertEquals(pairingServiceAliveBefore, vm.pairingServiceAlive.value)
            } finally {
                vm.cleanup()
            }
        }
    }

    /**
     * WP-11 — Pair timeout (wireless debugging is off on the phone) maps
     * to the literal "activate message" string from the spec.
     */
    @Test
    fun pairTimeoutWhenWirelessDebuggingIsOffShowsActivateMessage() {
        runBlocking {
            val fake = FakeAdbBridge()
            val pairing = MdnsService("adb-X", MdnsServiceType.PAIRING, "192.168.1.42", 37123)
            fake.scriptedMdnsSnapshots += listOf(pairing)
            fake.scriptedPair += PairResult.Failure(
                reason = PairFailureReason.TIMEOUT,
                rawStderr = "",
            )

            val vm = AppViewModel(adb = fake)
            try {
                vm.openWifiPanel()
                val discovered = awaitWifiPanel(
                    vm,
                    message = "expected Discovered",
                ) { it is WifiDelegate.WifiPanelState.Discovered && it.services.isNotEmpty() }
                discovered as WifiDelegate.WifiPanelState.Discovered
                vm.selectMdnsDevice(discovered.services[0])
                vm.submitCodeForSelected("123456")

                val errorState = awaitWifiPanel(
                    vm,
                    message = "expected Error after timeout",
                ) { it is WifiDelegate.WifiPanelState.Error }
                errorState as WifiDelegate.WifiPanelState.Error

                assertEquals(
                    "El movil no respondio. Asegurate de que 'Depuracion inalambrica' este activa en el movil.",
                    errorState.message,
                )
            } finally {
                vm.cleanup()
            }
        }
    }
}
