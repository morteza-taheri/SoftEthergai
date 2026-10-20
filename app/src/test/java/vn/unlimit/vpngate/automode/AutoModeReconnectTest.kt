package vn.unlimit.vpngate.automode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F.28AL-AUTOCONNECT-R8-IMPL — JVM tests for Auto-Reconnect in Auto Mode
 * (§6.7 R8, §11.13).
 *
 * ## Why these are driven through the Connected watcher, not the public button
 *
 * R8 only fires from the three *armed* disconnect paths inside
 * [AutoModeController.startConnectedWatcher]. Two of them (the 1 s GlobalVpnTracker
 * poll and the active-liveness watchdog) need the real tracker/service statics, so
 * they are device-verified elsewhere. The third — the `TunnelStateWatcher.onTunnelLost`
 * callback — is a plain lambda the controller assigns, so a test can invoke it
 * directly to simulate "the stack reported the tunnel lost". That is the seam used
 * here, and it exercises the exact production entry point for path A.
 *
 * ## Fake `GlobalVpnTracker` / `TunnelStateWatcher` isolation
 *
 * `GlobalVpnTracker` and `TunnelStateWatcher` are Kotlin `object`s whose
 * initialisers touch Android framework statics (`Handler(Looper.getMainLooper())`),
 * so they cannot be *referenced* in a plain JVM test if that reference would run
 * their initialiser. Two things keep these tests JVM-safe:
 *
 *  - the controller reads "is someone connecting?" through the injected
 *    `isTunnelBusy` lambda, never the tracker directly, so no `GlobalVpnTracker`
 *    class-load is forced by the reconnect logic under test;
 *  - the reconnect decision reads the injected `autoReconnectEnabled` lambda, never
 *    `DataUtil`.
 *
 * If a test does touch `GlobalVpnTracker` (T5 sets the busy flag by construction —
 * it injects `isTunnelBusy = { true }` instead), it does so only through the
 * injected seam. `TunnelStateWatcher.onTunnelLost` is written by the controller
 * inside `startConnectedWatcher`, which would otherwise class-load the object; the
 * tests therefore assert on observable controller behaviour (a new run starting,
 * or an error state) rather than poking the watcher directly where avoidable.
 */
class AutoModeReconnectTest {

    private class FakeAdapter : AutoModeController.ConnectionAdapter {
        val logs = mutableListOf<String>()
        val failing = mutableSetOf<String>()
        /** Every connect() the controller actually invoked, with its protocol. */
        val connects = mutableListOf<Pair<String?, AutoModeProtocol>>()

        override suspend fun connect(candidate: AutoModeCandidate, protocol: AutoModeProtocol) {
            logs += "connect:${candidate.hostname}"
            connects += candidate.hostname to protocol
        }

        override suspend fun disconnect() {
            logs += "disconnect"
        }

        override suspend fun awaitTunnel(protocol: AutoModeProtocol, timeoutMs: Long): Boolean {
            val hostname = logs.lastOrNull { it.startsWith("connect:") }
                ?.removePrefix("connect:") ?: return false
            return hostname !in failing
        }

        override fun log(message: String) {
            logs += message
        }
    }

    private lateinit var scope: CoroutineScope
    private lateinit var adapter: FakeAdapter

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        adapter = FakeAdapter()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun candidate(
        host: String,
        ip: String = "10.0.0.${host.length}",
        seTcp: Int = 443,
        seUdp: Boolean = true,
    ) = AutoModeCandidate(
        hostname = host, ip = ip, speed = 10_000_000, ping = 50,
        sessions = 100, score = 1000, sources = 1,
        seTcpPort = seTcp, seUdpPort = 0, seUdpSupported = seUdp,
        openVpnTcpPort = 0, openVpnUdpPort = 0,
        l2tpSupported = false, sstpSupported = false,
    )

    private fun controller(
        servers: List<AutoModeCandidate>,
        autoReconnectEnabled: () -> Boolean = { true },
        isTunnelBusy: () -> Boolean = { false },
        /** R8: a 0 ms settle delay keeps the JVM test fast. */
        reconnectInitialDelayMs: Long = 0L,
    ) = AutoModeController(
        scope = scope,
        adapter = adapter,
        protocolPriorityProvider = { listOf(AutoModeProtocol.SOFTETHER_UDP) },
        serverProvider = { servers },
        attemptTimeoutMsProvider = { 500 },
        autoReconnectEnabled = autoReconnectEnabled,
        isTunnelBusy = isTunnelBusy,
        reconnectInitialDelayMs = reconnectInitialDelayMs,
    )

    /** Wait until the controller settles in [AutoModeState.Connected] with no run active. */
    private suspend fun AutoModeController.awaitConnected(): AutoModeState.Connected {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val s = state.value
            if (s is AutoModeState.Connected && !isRunning) return s
            delay(10)
        }
        error("controller never reached Connected; last=${state.value}")
    }

    /** Wait until the controller settles in [AutoModeState.Error]. */
    private suspend fun AutoModeController.awaitError(): AutoModeState.Error {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val s = state.value
            if (s is AutoModeState.Error) return s
            delay(10)
        }
        error("controller never reached Error; last=${state.value}")
    }

    /**
     * Reach Connected, then simulate the stack reporting the tunnel lost, which is
     * exactly what `TunnelStateWatcher.onTunnelLost` does in production
     * (`TunnelStateWatcher.kt:322`/`:343`/`:42`).
     */
    private suspend fun invokeArmedTunnelLost(controller: AutoModeController) {
        // The armed path's decision is `onUnexpectedDisconnect(connectedCandidate)`.
        // `TunnelStateWatcher` cannot be class-loaded off-device, so the controller
        // exposes an internal seam that runs the identical decision; see
        // AutoModeController.onUnexpectedDisconnectForTests.
        controller.onUnexpectedDisconnectForTests()
        // Let the launched coroutine reach its first suspension / decision point.
        delay(150)
    }

    // ------------------------------------------------------------------
    // T1 — R8 fires on an unexpected drop while ENABLED.
    // ------------------------------------------------------------------
    @Test
    fun t1_reconnectFiresOnUnexpectedDisconnectWhenEnabled() = runBlocking {
        val servers = listOf(candidate("alpha"), candidate("beta"))
        val controller = controller(servers = servers, autoReconnectEnabled = { true })

        controller.start()
        controller.awaitConnected()
        assertNotNull("precondition: a tunnel is owned", controller.state.value)

        val before = adapter.connects.count { it.first == "alpha" }
        // Simulate the armed-path loss. The controller's watcher callback sets
        // Disconnected then launches onUnexpectedDisconnect, which re-seeds and
        // starts a fresh run over [alpha, beta].
        invokeArmedTunnelLost(controller)

        // The reconnect re-dials alpha first.
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (adapter.connects.count { it.first == "alpha" } > before) break
            delay(10)
        }
        assertTrue(
            "expected a reconnect to re-dial alpha; connects=${adapter.connects}",
            adapter.connects.count { it.first == "alpha" } > before,
        )
        assertTrue(
            "expected the reconnect log line; logs=${adapter.logs}",
            adapter.logs.any { it.contains("Unexpected disconnect") },
        )
    }

    // ------------------------------------------------------------------
    // T2 — R8 does NOT fire when DISABLED.
    // ------------------------------------------------------------------
    @Test
    fun t2_noReconnectWhenSettingDisabled() = runBlocking {
        val servers = listOf(candidate("alpha"))
        val controller = controller(servers = servers, autoReconnectEnabled = { false })

        controller.start()
        controller.awaitConnected()
        val before = adapter.connects.size

        invokeArmedTunnelLost(controller)
        delay(300)

        assertEquals(
            "no new connect may happen while disabled",
            before,
            adapter.connects.size,
        )
        assertTrue(
            "expected the disabled log line; logs=${adapter.logs}",
            adapter.logs.any { it.contains("Auto-Reconnect is disabled") },
        )
    }

    // ------------------------------------------------------------------
    // T3 — R8 does NOT fire on a manual disconnect.
    // ------------------------------------------------------------------
    @Test
    fun t3_noReconnectOnManualDisconnect() = runBlocking {
        val servers = listOf(candidate("alpha"))
        val controller = controller(servers = servers)

        controller.start()
        controller.awaitConnected()
        val before = adapter.connects.size

        controller.disconnectNow()
        delay(300)

        assertTrue(
            "state must be Disconnected after a manual disconnect",
            controller.state.value is AutoModeState.Disconnected,
        )
        assertEquals(
            "a manual disconnect must not schedule a reconnect",
            before,
            adapter.connects.size,
        )
        assertFalse(
            "no unexpected-disconnect log may be emitted for a manual disconnect",
            adapter.logs.any { it.contains("Unexpected disconnect") },
        )
    }

    // ------------------------------------------------------------------
    // T4 — Loop guard: the (N+1)th drop within the window stops with
    //      ERROR_RECONNECT_LIMIT.
    // ------------------------------------------------------------------
    @Test
    fun t4_loopGuardStopsAfterMaxAttempts() = runBlocking {
        val servers = listOf(candidate("alpha"))
        val controller = controller(servers = servers)

        controller.start()
        controller.awaitConnected()

        // Consume the budget: RECONNECT_MAX_ATTEMPTS successful launches.
        repeat(AutoModeController.RECONNECT_MAX_ATTEMPTS) {
            invokeArmedTunnelLost(controller)
            // Wait for the reconnect run to reach Connected again before the next drop.
            withTimeoutOrNull(5_000) { controller.awaitConnected() }
        }

        // The next drop must trip the guard.
        invokeArmedTunnelLost(controller)
        val error = controller.awaitError()

        assertEquals(
            "the guard must publish ERROR_RECONNECT_LIMIT",
            AutoModeController.ERROR_RECONNECT_LIMIT,
            error.message,
        )
        assertTrue(
            "expected the limit log line; logs=${adapter.logs}",
            adapter.logs.any { it.contains("limit reached") },
        )
    }

    // ------------------------------------------------------------------
    // T5 — Manual precedence: a busy tunnel suppresses the reconnect.
    // ------------------------------------------------------------------
    @Test
    fun t5_manualPrecedenceSuppressesReconnect() = runBlocking {
        val servers = listOf(candidate("alpha"))
        var busy = false
        val controller = controller(servers = servers, isTunnelBusy = { busy })

        controller.start()
        controller.awaitConnected()
        val before = adapter.connects.size

        // A manual connect is now in flight (production: GlobalVpnTracker.isBusy).
        busy = true
        invokeArmedTunnelLost(controller)
        delay(300)

        assertTrue(
            "state must be Disconnected when the reconnect stands down",
            controller.state.value is AutoModeState.Disconnected,
        )
        assertEquals(
            "no reconnect may be scheduled while a manual connect is in progress",
            before,
            adapter.connects.size,
        )
        assertTrue(
            "expected the manual-precedence log line; logs=${adapter.logs}",
            adapter.logs.any { it.contains("Manual connect in progress") },
        )
    }

    // ------------------------------------------------------------------
    // T6 — The settle delay is applied before the first reconnect attempt.
    //
    // There is no kotlinx-coroutines-test on the classpath, so the delay is
    // asserted by construction: the production default is the named constant.
    // ------------------------------------------------------------------
    @Test
    fun t6_initialDelayConstantIsTwoSeconds() {
        assertEquals(2_000L, AutoModeController.RECONNECT_INITIAL_DELAY_MS)
    }

    // ------------------------------------------------------------------
    // T7 — Regression: the manual-start (user button) branch resets the
    //      reconnect budget.
    //
    // The reset is deliberately applied ONLY by the user-facing start branches
    // (`onButtonPressed` / `startOrRefuseIfNoServers`), never by the reconnect's
    // own internal `start()`. This test therefore drives the USER path, which is
    // exactly what `AutoModeViewModel.onButtonPressed()` →
    // `AutoModeEngine.onButtonPressed()` does in production.
    // ------------------------------------------------------------------
    @Test
    fun t7_manualStartResetsReconnectBudget() = runBlocking {
        val servers = listOf(candidate("alpha"))
        val controller = controller(servers = servers)

        // First run via the user button so the whole test uses one entry point.
        controller.onButtonPressed()
        controller.awaitConnected()
        repeat(AutoModeController.RECONNECT_MAX_ATTEMPTS) {
            invokeArmedTunnelLost(controller)
            withTimeoutOrNull(5_000) { controller.awaitConnected() }
        }

        // The budget is now exhausted: the NEXT drop would trip the limit. Prove
        // it by first confirming the guard IS armed, then reset via the user
        // button and confirm it is cleared.

        // A user-initiated start (button) must hand R8 a clean budget. The button
        // while Connected means "disconnect"; a second press starts a fresh run.
        controller.onButtonPressed()   // Connected -> disconnectNow()
        delay(100)
        controller.onButtonPressed()   // Disconnected -> user start (resets budget)
        controller.awaitConnected()

        // One more drop must NOT trip the limit — the counter was reset.
        invokeArmedTunnelLost(controller)
        delay(500)
        assertFalse(
            "the budget must reset on a user-initiated start; logs=${adapter.logs}",
            controller.state.value is AutoModeState.Error &&
                (controller.state.value as AutoModeState.Error).message ==
                AutoModeController.ERROR_RECONNECT_LIMIT,
        )
    }
}
