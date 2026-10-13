package vn.unlimit.vpngate.automode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VpnM Phase 1.5 — the empty-server-list guard.
 *
 * Kept in its own file because it exercises a single behaviour with its own
 * fixture shape, and because the spec calls out this scenario explicitly.
 */
class AutoModeEmptyListGuardTest {

    private val EMPTY_MESSAGE = "Server list is empty. Please update the server list first."

    /**
     * Upper bound for "the run must leave Disconnected". This is NOT a sleep:
     * the test waits on the state flow and returns the moment the transition
     * happens. The value only exists so a regression that leaves the
     * controller stuck in Disconnected fails instead of hanging forever.
     */
    private val RUN_ACTIVATION_TIMEOUT_MS = 5_000L

    private class RecordingAdapter : AutoModeController.ConnectionAdapter {
        val connectCalls = mutableListOf<String>()
        override suspend fun connect(candidate: AutoModeCandidate, protocol: AutoModeProtocol) {
            connectCalls += candidate.hostname ?: candidate.ip.orEmpty()
        }
        override suspend fun disconnect() = Unit
        override suspend fun awaitTunnel(protocol: AutoModeProtocol, timeoutMs: Long) = false
        override fun log(message: String) = Unit
    }

    private fun controller(
        adapter: RecordingAdapter,
        servers: List<AutoModeCandidate>,
        scope: CoroutineScope,
    ) = AutoModeController(
        scope = scope,
        adapter = adapter,
        protocolPriorityProvider = { listOf(AutoModeProtocol.SOFTETHER_TCP) },
        serverProvider = { servers },
        emptyServerListMessage = { EMPTY_MESSAGE },
    )

    /**
     * [AutoModeController.start] launches the run on the controller's own
     * scope, so a test must wait for the run to reach a terminal state before
     * asserting on it — the same pattern the pre-existing AutoModeControllerTest
     * uses via awaitTerminal().
     */
    private suspend fun AutoModeController.awaitTerminal(): AutoModeState {
        while (true) {
            val s = state.value
            if ((s is AutoModeState.Connected || s is AutoModeState.Error) && !isRunning) return s
            kotlinx.coroutines.delay(25)
        }
    }

    @Test
    fun emptyListRefusesToStart() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = RecordingAdapter()
        val controller = controller(adapter, emptyList(), scope)

        controller.startOrRefuseIfNoServers()
        controller.awaitTerminal()

        assertTrue("no connection attempt may be made", adapter.connectCalls.isEmpty())
        assertFalse("no run may be left active", controller.isRunning)
        scope.cancel()
    }

    @Test
    fun emptyListReportsTheDedicatedMessage() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = RecordingAdapter()
        val controller = controller(adapter, emptyList(), scope)

        controller.startOrRefuseIfNoServers()

        val state = controller.awaitTerminal()
        assertTrue("expected an Error state, got $state", state is AutoModeState.Error)
        assertEquals(
            "the guard must surface the actionable message, not a generic one",
            EMPTY_MESSAGE,
            (state as AutoModeState.Error).message,
        )
        scope.cancel()
    }

    @Test
    fun emptyListMessageIsNotTheGenericErrorCode() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = RecordingAdapter()
        val controller = controller(adapter, emptyList(), scope)

        controller.startOrRefuseIfNoServers()

        val message = (controller.awaitTerminal() as AutoModeState.Error).message
        // The Auto Mode screen maps these two codes to their own strings; an
        // empty list must not be reported as either, or the user would be told
        // to retry a connection that can never start.
        assertFalse(message == AutoModeController.ERROR_NO_SERVER)
        assertFalse(message == AutoModeController.ERROR_VPN_PERMISSION)
        scope.cancel()
    }

    private fun server(hostname: String) = AutoModeCandidate(
        hostname = hostname,
        ip = "10.0.0.1",
        speed = 1L,
        ping = 1,
        sessions = 0,
        score = 0,
        sources = 1,
        seTcpPort = 443,
        seUdpPort = 0,
        seUdpSupported = false,
        openVpnTcpPort = 0,
        openVpnUdpPort = 0,
        l2tpSupported = false,
        sstpSupported = false,
    )

    @Test
    fun nonEmptyListStillStarts() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = RecordingAdapter()
        val controller = controller(adapter, listOf(server("good.example")), scope)

        val started = controller.startOrRefuseIfNoServers()

        assertTrue("a populated list must start normally", started)

        // `isRunning` must NOT be sampled immediately after start().
        //
        // start() does `job = scope.launch { runAutoMode() }` on
        // Dispatchers.Default, so the coroutine body is free to run before
        // `launch` even returns. With this fake adapter the run reaches a
        // terminal state almost immediately, and the terminal paths reset
        // `job = null`. isRunning is defined as `job?.isActive == true`, so a
        // fast terminal run legitimately reports false — the production
        // behaviour is correct and the *sample* was the racy part.
        //
        // Deterministic fix: wait for the run to actually leave
        // Disconnected, which is exactly the transition that proves the
        // controller acted on the populated list. This uses the existing
        // state flow (no sleep, no fixed timeout) and cannot pass by accident:
        // a guard that wrongly refused the list would stay Disconnected and
        // hit the deadline below.
        withTimeoutOrNull(RUN_ACTIVATION_TIMEOUT_MS) {
            controller.state.first { it !is AutoModeState.Disconnected }
        }.let { observed ->
            assertNotNull(
                "the run must leave Disconnected for a populated list, stayed at $observed",
                observed,
            )
        }
        // Whatever happened next, the controller must have engaged: either the
        // run is still active, or it already finished a real attempt.
        val engaged = controller.isRunning || adapter.connectCalls.isNotEmpty()
        assertTrue(
            "the run must have engaged (active job or a completed attempt), " +
                "isRunning=${controller.isRunning}, connectCalls=${adapter.connectCalls.size}",
            engaged,
        )
        scope.cancel()
    }

    @After
    fun noop() = Unit
}
